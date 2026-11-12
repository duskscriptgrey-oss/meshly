package com.duskscript.meshly

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NearbyPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int
)

class LocalMeshTransport(
    context: Context,
    private val displayName: String,
    private val deviceId: String,
    private val onPeersChanged: (List<NearbyPeer>) -> Unit,
    private val onMessage: (NearbyPeer, String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val SERVICE_TYPE = "_meshly._tcp."
        private const val PROTOCOL_PREFIX = "MESHLY2"
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, NearbyPeer>()
    private val serviceName = "Meshly-$deviceId"
    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var started = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (started) return
        started = true
        postStatus("Starting nearby discovery…")
        scope.launch {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                withContext(Dispatchers.Main) {
                    try {
                        multicastLock = wifi.createMulticastLock("meshly-discovery").apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        registerService(server.localPort)
                    } catch (error: Exception) {
                        postStatus("Discovery unavailable: ${error.localizedMessage ?: "check Wi-Fi"}")
                    }
                }
                while (started) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    scope.launch { receive(socket) }
                }
            } catch (error: Exception) {
                postStatus("Could not start local messaging: ${error.localizedMessage ?: "network error"}")
            }
        }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = this@LocalMeshTransport.serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("displayName", displayName.take(60))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postStatus("Could not advertise this phone (error $errorCode).")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                postStatus("You're discoverable on this local network.")
                beginDiscovery()
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postStatus("Discovery stopped with an error.")
            }
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun beginDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                postStatus("Looking for Meshly users on this network…")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == serviceName) return
                if (!serviceInfo.serviceType.contains("_meshly._tcp")) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            postStatus("Couldn't connect to one nearby listing. Still searching…")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            if (port <= 0) return
                            val name = info.attributes?.get("displayName")
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() }
                                ?: info.serviceName.removePrefix("Meshly-")
                            val peer = NearbyPeer(info.serviceName.removePrefix("Meshly-"), name, host, port)
                            peers[peer.id] = peer
                            publishPeers()
                        }
                    })
                } catch (_: Exception) {
                    postStatus("Nearby discovery is busy. Continuing to search…")
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.remove(serviceInfo.serviceName.removePrefix("Meshly-"))
                publishPeers()
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                postStatus("Discovery couldn't start (error $errorCode).")
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) { }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            postStatus("Nearby discovery error: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    suspend fun send(peer: NearbyPeer, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
                socket.soTimeout = 8_000
                val encodedName = Base64.encodeToString(displayName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val encodedText = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val messageId = UUID.randomUUID().toString()
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("$PROTOCOL_PREFIX\t$deviceId\t$encodedName\t$encodedText\t$messageId\t${System.currentTimeMillis()}")
                writer.newLine()
                writer.flush()
                socket.shutdownOutput()
                val acknowledgement = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                check(acknowledgement == "ACK\t$messageId") {
                    "The receiving phone did not confirm delivery."
                }
            }
        }
    }

    private suspend fun receive(socket: Socket) {
        socket.use { connection ->
            try {
                val line = connection.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
                val parts = line.split('\t')
                if (parts.size != 6 || parts[0] != PROTOCOL_PREFIX) return
                val senderDeviceId = parts[1].take(80)
                val sender = String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8).take(60)
                val message = String(Base64.decode(parts[3], Base64.DEFAULT), Charsets.UTF_8).take(4_000)
                val messageId = parts[4].take(80)
                if (senderDeviceId.isBlank() || sender.isBlank() || message.isBlank() || messageId.isBlank()) return
                val host = connection.inetAddress.hostAddress ?: "unknown"
                val peer = NearbyPeer(senderDeviceId, sender, host, 0)
                withContext(Dispatchers.Main) { onMessage(peer, message) }
                val writer = connection.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("ACK\t$messageId")
                writer.newLine()
                writer.flush()
            } catch (error: Exception) {
                postStatus("A message couldn't be received: ${error.localizedMessage ?: "check the local connection"}")
            }
        }
    }

    private fun publishPeers() {
        val snapshot = peers.values.sortedBy { it.name.lowercase() }
        main.post { onPeersChanged(snapshot) }
    }

    private fun postStatus(message: String) {
        main.post { onStatus(message) }
    }

    fun stop() {
        if (!started) return
        started = false
        try { discoveryListener?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) { }
        try { registrationListener?.let { nsd.unregisterService(it) } } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        peers.clear()
        publishPeers()
        scope.cancel()
    }
}
