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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
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
    private val onPeersChanged: (List<NearbyPeer>) -> Unit,
    private val onMessage: (NearbyPeer, String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val SERVICE_TYPE = "_meshly._tcp."
        private const val PROTOCOL_PREFIX = "MESHLY1"
        private const val DISCOVERY_PREFIX = "MESHLY_DISCOVERY1"
        private const val DISCOVERY_PORT = 45678
        private const val DISCOVERY_INTERVAL_MS = 2_000L
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, NearbyPeer>()
    private val udpLastSeen = ConcurrentHashMap<String, Long>()
    private val serviceId = UUID.randomUUID().toString().take(8)
    private val serviceName = "Meshly-$serviceId"
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
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
                        postStatus("Meshly is using fallback discovery: ${error.localizedMessage ?: "Wi-Fi discovery unavailable"}")
                    }
                }
                startUdpDiscovery(server.localPort)
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
                postStatus("Could not advertise through Wi-Fi discovery (error $errorCode). Using fallback discovery.")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                postStatus("Searching for nearby Meshly devices…")
                beginDiscovery()
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postStatus("Wi-Fi discovery stopped with an error.")
            }
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun beginDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                postStatus("Searching for nearby Meshly devices…")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == serviceName) return
                if (!serviceInfo.serviceType.contains("_meshly._tcp")) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            postStatus("Wi-Fi discovery missed a device; fallback discovery is still running.")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            if (port <= 0) return
                            val name = info.attributes?.get("displayName")
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() }
                                ?: info.serviceName.removePrefix("Meshly-")
                            val peer = NearbyPeer(info.serviceName, name, host, port)
                            peers[peer.id] = peer
                            publishPeers()
                        }
                    })
                } catch (_: Exception) {
                    postStatus("Wi-Fi discovery is busy; fallback discovery is still running.")
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lastUdpAnnouncement = udpLastSeen[serviceInfo.serviceName] ?: 0L
                if (System.currentTimeMillis() - lastUdpAnnouncement > 10_000L) {
                    peers.remove(serviceInfo.serviceName)
                    publishPeers()
                }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                postStatus("Wi-Fi discovery couldn't start (error $errorCode); fallback discovery is active.")
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) { }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            postStatus("Wi-Fi discovery error: ${error.localizedMessage ?: "unknown error"}")
        }
    }

    /**
     * DNS-SD/mDNS is not consistently forwarded by every Android hotspot implementation.
     * UDP broadcast provides a second discovery path and learns the peer's address from the
     * packet source, which is especially useful when the phone creating the hotspot is the host.
     */
    private fun startUdpDiscovery(port: Int) {
        scope.launch {
            try {
                val receiver = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                discoverySocket = receiver
                while (started && !receiver.isClosed) {
                    try {
                        val buffer = ByteArray(2_048)
                        val packet = DatagramPacket(buffer, buffer.size)
                        receiver.receive(packet)
                        handleDiscoveryPacket(packet)
                    } catch (_: SocketTimeoutException) {
                        // Re-check the started flag periodically so shutdown is prompt.
                    } catch (error: Exception) {
                        if (started) postStatus("Hotspot discovery interrupted: ${error.localizedMessage ?: "network error"}")
                        break
                    }
                }
            } catch (error: Exception) {
                postStatus("Hotspot fallback discovery unavailable: ${error.localizedMessage ?: "UDP bind failed"}")
            }
        }

        scope.launch {
            val sender = try {
                DatagramSocket().apply { broadcast = true }
            } catch (error: Exception) {
                postStatus("Could not start hotspot announcements: ${error.localizedMessage ?: "UDP unavailable"}")
                return@launch
            }
            sender.use { socket ->
                while (started && !socket.isClosed) {
                    try {
                        broadcastAnnouncement(socket, port)
                    } catch (error: Exception) {
                        postStatus("Nearby announcement retrying: ${error.localizedMessage ?: "network error"}")
                    }
                    delay(DISCOVERY_INTERVAL_MS)
                }
            }
        }
    }

    private fun broadcastAnnouncement(socket: DatagramSocket, port: Int) {
        val encodedName = Base64.encodeToString(displayName.take(60).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = "$DISCOVERY_PREFIX\t$serviceName\t$port\t$encodedName".toByteArray(Charsets.UTF_8)
        val destinations = Collections.synchronizedSet(LinkedHashSet<String>())
        destinations.add("255.255.255.255")

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.interfaceAddresses) {
                    val broadcast = address.broadcast
                    if (broadcast is Inet4Address) destinations.add(broadcast.hostAddress)
                }
            }
        } catch (_: Exception) {
            // The limited broadcast address above remains as a fallback.
        }

        destinations.forEach { destination ->
            try {
                val packet = DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(destination),
                    DISCOVERY_PORT
                )
                socket.send(packet)
            } catch (_: Exception) {
                // One unavailable interface must not prevent announcements on another.
            }
        }
    }

    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val parts = raw.split('\t')
        if (parts.size != 4 || parts[0] != DISCOVERY_PREFIX) return

        val peerId = parts[1]
        if (peerId == serviceName) return
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return
        val name = try {
            String(Base64.decode(parts[3], Base64.DEFAULT), Charsets.UTF_8).take(60)
        } catch (_: Exception) {
            return
        }
        val host = packet.address?.hostAddress ?: return
        if (name.isBlank() || host.isBlank()) return

        udpLastSeen[peerId] = System.currentTimeMillis()
        peers[peerId] = NearbyPeer(peerId, name, host, port)
        publishPeers()
    }

    suspend fun send(peer: NearbyPeer, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(peer.host, peer.port), 5_000)
                socket.soTimeout = 5_000

                val encodedName = Base64.encodeToString(displayName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val encodedText = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("$PROTOCOL_PREFIX\t$encodedName\t$encodedText\t${System.currentTimeMillis()}")
                writer.newLine()
                writer.flush()
                socket.shutdownOutput()

                val acknowledgement = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                check(acknowledgement == "$PROTOCOL_PREFIX\tACK") {
                    "The other phone did not acknowledge the message."
                }
            }
        }
    }

    private fun receive(socket: Socket) {
        socket.use { connection ->
            try {
                connection.soTimeout = 5_000
                val line = connection.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: return
                val parts = line.split('\t')
                if (parts.size != 4 || parts[0] != PROTOCOL_PREFIX) return

                val sender = String(Base64.decode(parts[1], Base64.DEFAULT), Charsets.UTF_8).take(60)
                val message = String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8).take(4_000)
                if (sender.isBlank() || message.isBlank()) return

                val host = connection.inetAddress.hostAddress ?: "unknown"
                val peer = NearbyPeer(host, sender, host, 0)
                main.post { onMessage(peer, message) }

                val writer = connection.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("$PROTOCOL_PREFIX\tACK")
                writer.newLine()
                writer.flush()
            } catch (_: Exception) {
                postStatus("A message couldn't be received or acknowledged. Check that both phones are on the same local network.")
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
        try { discoverySocket?.close() } catch (_: Exception) { }
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        peers.clear()
        udpLastSeen.clear()
        publishPeers()
        scope.cancel()
    }
}
