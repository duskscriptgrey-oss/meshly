package com.duskscript.meshly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF192631)
private val Mint = Color(0xFF65E6C2)
private val Muted = Color(0xFF9BAEB8)
private val SoftMint = Color(0xFF203D3B)

data class ChatMessage(
    val peerId: String,
    val peerName: String,
    val text: String,
    val outgoing: Boolean,
    val time: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshlyTheme { MeshlyApp() } }
    }
}

@Composable
private fun MeshlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Ink,
            background = Ink,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
            secondary = Muted
        ),
        content = content
    )
}

@Composable
private fun MeshlyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("meshly_local", android.content.Context.MODE_PRIVATE) }
    var username by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf(prefs.getString("display_name", "") ?: "") }
    var tab by remember { mutableStateOf(0) }
    var selectedPeer by remember { mutableStateOf<NearbyPeer?>(null) }
    var editingProfile by remember { mutableStateOf(false) }
    var openingSettings by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf(emptyList<NearbyPeer>()) }
    val messagesState = remember { mutableStateOf(loadMessages(prefs)) }
    val messages = messagesState.value
    val localDeviceId = remember {
        prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("device_id", it).apply()
            }
    }
    var networkStatus by remember { mutableStateOf("Preparing local discovery…") }
    val coroutineScope = rememberCoroutineScope()

    fun appendMessage(message: ChatMessage) {
        val updated = (messagesState.value + message).takeLast(500)
        messagesState.value = updated
        saveMessages(prefs, updated)
    }

    val transport = remember(savedName, localDeviceId) {
        if (savedName.isBlank()) null else LocalMeshTransport(
            context = context,
            displayName = savedName,
            deviceId = localDeviceId,
            onPeersChanged = { peers = it },
            onMessage = { peer, message ->
                appendMessage(
                    ChatMessage(
                        peerId = peer.id,
                        peerName = peer.name,
                        text = message,
                        outgoing = false,
                        time = System.currentTimeMillis()
                    )
                )
            },
            onStatus = { networkStatus = it }
        )
    }

    DisposableEffect(transport) {
        transport?.start()
        onDispose { transport?.stop() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        if (savedName.isBlank()) {
            WelcomeScreen(
                username = username,
                onUsernameChange = { username = it },
                onContinue = {
                    val clean = username.trim()
                    savedName = clean
                    prefs.edit().putString("display_name", clean).apply()
                }
            )
        } else if (selectedPeer != null) {
            ChatScreen(
                peer = selectedPeer!!,
                messages = messages.filter { belongsToPeer(it, selectedPeer!!) },
                onBack = { selectedPeer = null },
                onSend = { text, onSent ->
                    val peer = selectedPeer!!
                    coroutineScope.launch {
                        val result = transport?.send(peer, text)
                        if (result?.isSuccess == true) {
                            appendMessage(
                                ChatMessage(
                                    peerId = peer.id,
                                    peerName = peer.name,
                                    text = text,
                                    outgoing = true,
                                    time = System.currentTimeMillis()
                                )
                            )
                            onSent()
                        } else {
                            val detail = result?.exceptionOrNull()?.localizedMessage
                            networkStatus = if (detail.isNullOrBlank()) {
                                "Message not sent. Check both phones are on the same Wi-Fi or hotspot."
                            } else {
                                "Message not sent: $detail"
                            }
                        }
                    }
                }
            )
        } else if (openingSettings) {
            SettingsScreen(status = networkStatus, onBack = { openingSettings = false })
        } else {
            Scaffold(
                containerColor = Ink,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Chats") },
                            label = { Text("Chats") }
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Groups, contentDescription = "Nearby") },
                            label = { Text("Nearby") }
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                            label = { Text("Profile") }
                        )
                    }
                }
            ) { padding ->
                when (tab) {
                    0 -> ChatsScreen(
                        name = savedName,
                        peers = peers,
                        messages = messages,
                        modifier = Modifier.padding(padding),
                        onOpen = { selectedPeer = it },
                        onNearby = { tab = 1 }
                    )
                    1 -> NearbyScreen(
                        peers = peers,
                        status = networkStatus,
                        modifier = Modifier.padding(padding),
                        onOpen = { selectedPeer = it }
                    )
                    else -> ProfileScreen(
                        name = savedName,
                        modifier = Modifier.padding(padding),
                        onEdit = { editingProfile = true },
                        onSettings = { openingSettings = true }
                    )
                }
            }
        }
    }

    if (editingProfile) {
        EditProfileDialog(
            currentName = savedName,
            onDismiss = { editingProfile = false },
            onSave = { newName ->
                savedName = newName
                prefs.edit().putString("display_name", newName).apply()
                editingProfile = false
            }
        )
    }
}

@Composable
private fun WelcomeScreen(username: String, onUsernameChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BrandMark()
        Spacer(Modifier.height(28.dp))
        Text("Your people. Your network.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Chat nearby, even when the internet isn't around.", color = Muted)
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Choose a display name") },
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onContinue, enabled = username.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text("Create local profile")
        }
        Spacer(Modifier.height(12.dp))
        Text("No phone number, email address or account required.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BrandMark() {
    Box(Modifier.size(62.dp).background(Mint, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Text("M", color = Ink, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ChatsScreen(
    name: String,
    peers: List<NearbyPeer>,
    messages: List<ChatMessage>,
    modifier: Modifier,
    onOpen: (NearbyPeer) -> Unit,
    onNearby: () -> Unit
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Meshly", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Welcome, $name", color = Muted)
        Spacer(Modifier.height(22.dp))
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNearby),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(SoftMint, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = Mint)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Find people nearby", fontWeight = FontWeight.SemiBold)
                    Text(if (peers.isEmpty()) "Discover Meshly users on your network" else "${peers.size} nearby ${if (peers.size == 1) "person" else "people"} found", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.Add, contentDescription = null, tint = Mint)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("YOUR CHATS", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        val conversations = peers.filter { peer -> messages.any { belongsToPeer(it, peer) } }
        if (conversations.isEmpty()) {
            Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
            Text("When you connect with someone nearby, your conversations will appear here.", color = Muted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(conversations, key = { it.id }) { peer ->
                    val latest = messages.lastOrNull { belongsToPeer(it, peer) }
                    PeerCard(peer = peer, subtitle = latest?.text ?: "Start a conversation", onClick = { onOpen(peer) })
                }
            }
        }
    }
}

@Composable
private fun NearbyScreen(
    peers: List<NearbyPeer>,
    status: String,
    modifier: Modifier,
    onOpen: (NearbyPeer) -> Unit
) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Nearby", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Find Meshly users sharing your Wi-Fi or hotspot.", color = Muted)
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Mint, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("AVAILABLE DEVICES", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (peers.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = Mint, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Looking for your people", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Keep Meshly open on both phones and connect them to the same Wi-Fi or hotspot.", color = Muted)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(peers, key = { it.id }) { peer ->
                    PeerCard(peer = peer, subtitle = "Available on your local network", onClick = { onOpen(peer) })
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: NearbyPeer, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(SoftMint, CircleShape), contentAlignment = Alignment.Center) {
                Text(peer.name.take(1).uppercase(), color = Mint, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(peer.name, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Icon(Icons.Default.ChatBubble, contentDescription = "Open chat", tint = Mint)
        }
    }
}

@Composable
private fun ProfileScreen(name: String, modifier: Modifier, onEdit: () -> Unit, onSettings: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Your profile", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).background(SoftMint, CircleShape), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = Mint, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Meshly member", color = Muted)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit profile") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSettings),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Mint)
                Text("Settings", modifier = Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Meshly Alpha 0.2.0", color = Muted)
        Text("A local-first messenger. Your messages travel directly between nearby devices.", color = Muted)
    }
}

@Composable
private fun EditProfileDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit your profile") },
        text = {
            Column {
                Text("Profile picture support is planned for the next profile update.", color = Muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.trim().length >= 2) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsScreen(status: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("CONNECTION", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Nearby discovery", fontWeight = FontWeight.SemiBold)
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("For this alpha, both phones need to be on the same Wi-Fi network or phone hotspot.", color = Muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("PRIVACY", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Local-first messaging", fontWeight = FontWeight.SemiBold)
                Text("This build sends messages directly over your local network. End-to-end encryption and pairing verification are not implemented yet, so use test messages only.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChatScreen(
    peer: NearbyPeer,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String, () -> Unit) -> Unit
) {
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Column {
                Text(peer.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Nearby connection", color = Mint, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Say hello to ${peer.name}. Your messages will appear here.", color = Muted)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.outgoing) Arrangement.End else Arrangement.Start) {
                        Surface(color = if (item.outgoing) SoftMint else Panel, shape = RoundedCornerShape(18.dp)) {
                            Text(item.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Write a message…") },
                maxLines = 4
            )
            IconButton(
                onClick = {
                    val clean = message.trim()
                    if (clean.isNotEmpty()) {
                        onSend(clean) { message = "" }
                    }
                },
                enabled = message.isNotBlank()
            ) { Icon(Icons.Default.Send, contentDescription = "Send", tint = Mint) }
        }
    }
}

private fun loadMessages(prefs: android.content.SharedPreferences): List<ChatMessage> {
    return try {
        val array = JSONArray(prefs.getString("chat_history", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val peerName = item.optString("peer")
            ChatMessage(
                peerId = item.optString("peerId").ifBlank { peerName },
                peerName = peerName,
                text = item.optString("text"),
                outgoing = item.optBoolean("outgoing"),
                time = item.optLong("time")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun belongsToPeer(message: ChatMessage, peer: NearbyPeer): Boolean {
    return message.peerId == peer.id ||
        (message.peerId == message.peerName && message.peerName == peer.name)
}

private fun saveMessages(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.takeLast(500).forEach { message ->
        array.put(JSONObject().apply {
            put("peerId", message.peerId)
            put("peer", message.peerName)
            put("text", message.text)
            put("outgoing", message.outgoing)
            put("time", message.time)
        })
    }
    prefs.edit().putString("chat_history", array.toString()).apply()
}
