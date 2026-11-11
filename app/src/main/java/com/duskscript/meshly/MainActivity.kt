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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF101820)
private val Panel = Color(0xFF192631)
private val Mint = Color(0xFF65E6C2)
private val Muted = Color(0xFF9BAEB8)

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
    var username by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var selectedChat by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        if (savedName.isBlank()) {
            WelcomeScreen(
                username = username,
                onUsernameChange = { username = it },
                onContinue = { savedName = username.trim() }
            )
        } else if (selectedChat != null) {
            ChatScreen(name = selectedChat!!, onBack = { selectedChat = null })
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
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Profile") },
                            label = { Text("Profile") }
                        )
                    }
                }
            ) { padding ->
                when (tab) {
                    0 -> ChatsScreen(savedName, Modifier.padding(padding), onOpen = { selectedChat = it })
                    1 -> NearbyScreen(Modifier.padding(padding))
                    else -> ProfileScreen(savedName, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    username: String,
    onUsernameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(68.dp).background(Mint, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("M", color = Ink, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black) }
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
        Button(
            onClick = onContinue,
            enabled = username.trim().length >= 2,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create local profile") }
        Spacer(Modifier.height(12.dp))
        Text("No phone number, email address or account required.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChatsScreen(name: String, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Meshly", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Welcome, $name", color = Muted)
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Mint.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = Mint)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Find people nearby", fontWeight = FontWeight.SemiBold)
                    Text("Connect on the same local network", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.Add, contentDescription = null, tint = Mint)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("YOUR CHATS", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
        Text("When nearby people connect, your conversations will appear here.", color = Muted)
    }
}

@Composable
private fun NearbyScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Nearby", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Discover Meshly users on your local network.", color = Muted)
        Spacer(Modifier.height(28.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = Mint, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(14.dp))
                Text("Local discovery is coming online", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("The next build connects devices over the same Wi-Fi or phone hotspot.", color = Muted)
            }
        }
    }
}

@Composable
private fun ProfileScreen(name: String, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Your profile", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("DISPLAY NAME", color = Muted, style = MaterialTheme.typography.labelMedium)
        Text(name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Text("Meshly Alpha 0.1.0", color = Muted)
        Text("A private, local-first messenger. No central account.", color = Muted)
    }
}

@Composable
private fun ChatScreen(name: String, onBack: () -> Unit) {
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("‹  $name", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { onBack() })
        Spacer(Modifier.weight(1f))
        Text("Messages will appear here after a local connection is established.", color = Muted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message") })
            Button(onClick = { message = "" }, enabled = message.isNotBlank()) { Text("Send") }
        }
    }
}
