package com.m4lych.oblivion

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.m4lych.oblivion.data.ImportWorldResult
import com.m4lych.oblivion.data.OblivionPreferences
import com.m4lych.oblivion.data.DiscordAuthClient
import com.m4lych.oblivion.data.DiscordAuthEvents
import com.m4lych.oblivion.data.SavedServer
import com.m4lych.oblivion.data.SkinPreviewSelection
import com.m4lych.oblivion.data.WorldLibrary
import com.m4lych.oblivion.domain.ServerAddressValidator
import com.m4lych.oblivion.logging.AppLogger
import com.m4lych.oblivion.logging.LogLevel
import com.m4lych.oblivion.game.GameLaunchViewModel
import com.m4lych.oblivion.game.LaunchPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val Obsidian = Color(0xFF090A12)
private val Navy = Color(0xFF111827)
private val Surface = Color(0xFF1A1B28)
private val Plum = Color(0xFF30233D)
private val Silver = Color(0xFFC8C5D1)
private val PaleGold = Color(0xFFC7AD74)
private val Teal = Color(0xFF206F6D)
private val Muted = Color(0xFFAAA7B6)
private const val MAX_SKIN_PNG_BYTES = 16L * 1024 * 1024
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logger = AppLogger(this)
        logger.write(LogLevel.INFO, "startup", "Application launched")
        setContent { OblivionTheme { OblivionApp(logger) } }
        handleDiscordCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDiscordCallback(intent)
    }

    private fun handleDiscordCallback(incoming: Intent?) {
        val callback = incoming?.data ?: return
        if (callback.scheme != "oblivion" || callback.host != "auth" || callback.path != "/discord") return
        lifecycleScope.launch {
            val result = DiscordAuthClient.complete(applicationContext, callback)
            DiscordAuthEvents.emit(result)
            AppLogger(this@MainActivity).write(
                if (result is com.m4lych.oblivion.data.DiscordAuthResult.Connected) LogLevel.INFO else LogLevel.WARNING,
                "discord-auth",
                "Discord callback completed: ${result.javaClass.simpleName}"
            )
        }
    }
}

@Composable
private fun OblivionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PaleGold, secondary = Color(0xFF77B8B1), background = Obsidian,
            surface = Surface, onPrimary = Obsidian, onBackground = Silver, onSurface = Silver
        ), content = content
    )
}

private data class Tab(val route: String, val label: String)
private val tabs = listOf(Tab("home", "Home"), Tab("library", "Library"), Tab("community", "Community"), Tab("profile", "Profile"), Tab("settings", "Settings"))

@Composable
private fun OblivionApp(logger: AppLogger) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { OblivionPreferences(context) }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Scaffold(
        containerColor = Obsidian,
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                tabs.forEach { tab ->
                    val icon = when (tab.route) {
                        "home" -> Icons.Filled.Home
                        "library" -> Icons.Filled.Folder
                        "community" -> Icons.Filled.People
                        "profile" -> Icons.Filled.Face
                        else -> Icons.Filled.Settings
                    }
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { nav.navigate(tab.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(prefs, onOpen = { nav.navigate(it) }, logger = logger) }
            composable("play") { PlayScreen() }
            composable("profile") { ProfileScreen(prefs, logger) }
            composable("character") { CharacterScreen(logger, prefs) }
            composable("library") { LibraryScreen(logger) }
            composable("community") { CommunityScreen(onOpen = { nav.navigate(it) }) }
            composable("settings") { SettingsScreen(prefs, onOpen = { nav.navigate(it) }) }
            composable("diagnostics") { DiagnosticsScreen(logger) }
            composable("admin") { AdminScreen() }
            composable("about") { AboutScreen() }
            composable("news") { NewsScreen() }
            composable("marketplace") { MarketplaceScreen() }
            composable("servers") { ServersScreen(prefs) }
            composable("friends") { ConfigPage("Friends", "The local Oblivion friend system is designed for the backend API but is not active until identity and service hosting are configured.") }
        }
    }
}

@Composable
private fun HomeScreen(prefs: OblivionPreferences, onOpen: (String) -> Unit, logger: AppLogger) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val name by prefs.displayName.collectAsStateWithLifecycle(initialValue = "Wanderer")
    var discordName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lifecycleOwner) {
        discordName = DiscordAuthClient.currentUsername(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Refresh after returning from the system browser used for OAuth.
                lifecycleOwner.lifecycleScope.launch { discordName = DiscordAuthClient.currentUsername(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        try { kotlinx.coroutines.awaitCancellation() } finally { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        DiscordAuthEvents.events.collect { result ->
            when (result) {
                is com.m4lych.oblivion.data.DiscordAuthResult.Connected -> discordName = result.username
                is com.m4lych.oblivion.data.DiscordAuthResult.Error -> discordName = DiscordAuthClient.currentUsername(context)
                is com.m4lych.oblivion.data.DiscordAuthResult.Started -> Unit
                com.m4lych.oblivion.data.DiscordAuthResult.Disconnected -> discordName = null
            }
        }
    }
    val installed = remember { context.packageManager.getLaunchIntentForPackage("com.mojang.minecraftpe") != null }
    BoxWithConstraints(Modifier.fillMaxSize().background(Obsidian)) {
        val wide = maxWidth > 650.dp
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 28.dp)) {
            item {
                BrandHeader()
                Text("THE VEIL BETWEEN WORLDS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp))
                Text("Your next world awaits.", color = Silver, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            item {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterCard(name, Modifier.weight(1f).height(310.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            InstallCard(installed)
                            PlayButton(installed) { onOpen("play") }
                            ServiceStatusCard(discordName)
                        }
                    }
                } else {
                    CharacterCard(name, Modifier.fillMaxWidth().height(270.dp))
                    InstallCard(installed)
                    PlayButton(installed) { onOpen("play") }
                    ServiceStatusCard(discordName)
                }
            }
            item {
                Text("ENTER THE ARCHIVE", color = PaleGold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    FeatureTile("Worlds & packs", "Import and organize", Modifier.weight(1f)) { onOpen("library") }
                    FeatureTile("Community", "Friends and news", Modifier.weight(1f)) { onOpen("community") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    FeatureTile("Character", "Skin preview", Modifier.weight(1f)) { onOpen("character") }
                    FeatureTile("Reports", "Logs and diagnostics", Modifier.weight(1f)) { onOpen("diagnostics") }
                }
            }
            item { Text("Official Minecraft updates are not connected yet. Oblivion will show sourced release notes once the feed is configured.", color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Plum), contentAlignment = Alignment.Center) { Text("O", color = PaleGold, fontSize = 24.sp, fontWeight = FontWeight.Light) }
        Column(Modifier.padding(start = 10.dp)) {
            Text("OBLIVION", color = Silver, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 15.sp)
            Text("BEDROCK COMPANION", color = Muted, letterSpacing = 1.2.sp, fontSize = 9.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("LOCAL MODE", color = PaleGold, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.clip(RoundedCornerShape(40.dp)).background(Surface).padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun CharacterCard(name: String, modifier: Modifier = Modifier, skinTint: Color = Plum) {
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Navy)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Plum, Navy, Obsidian)))
            val cx = size.width * .5f
            val scale = size.minDimension / 230f
            drawCircle(color = Color(0x446B587A), radius = 95f * scale, center = androidx.compose.ui.geometry.Offset(cx, size.height * .47f))
            // Original geometric placeholder; no Minecraft game assets are included.
            drawRect(Color(0xFF292433), androidx.compose.ui.geometry.Offset(cx - 31 * scale, size.height * .22f), androidx.compose.ui.geometry.Size(62 * scale, 62 * scale))
            drawRect(Color(0xFFB9B2BC), androidx.compose.ui.geometry.Offset(cx - 23 * scale, size.height * .25f), androidx.compose.ui.geometry.Size(46 * scale, 44 * scale))
            drawRect(Color(0xFF272638), androidx.compose.ui.geometry.Offset(cx - 16 * scale, size.height * .315f), androidx.compose.ui.geometry.Size(7 * scale, 5 * scale))
            drawRect(Color(0xFF272638), androidx.compose.ui.geometry.Offset(cx + 9 * scale, size.height * .315f), androidx.compose.ui.geometry.Size(7 * scale, 5 * scale))
            drawRect(skinTint, androidx.compose.ui.geometry.Offset(cx - 35 * scale, size.height * .48f), androidx.compose.ui.geometry.Size(70 * scale, 73 * scale))
            drawRect(Color(0xFF2B2939), androidx.compose.ui.geometry.Offset(cx - 62 * scale, size.height * .49f), androidx.compose.ui.geometry.Size(24 * scale, 70 * scale))
            drawRect(Color(0xFF2B2939), androidx.compose.ui.geometry.Offset(cx + 38 * scale, size.height * .49f), androidx.compose.ui.geometry.Size(24 * scale, 70 * scale))
            drawRect(Color(0xFF181923), androidx.compose.ui.geometry.Offset(cx - 33 * scale, size.height * .77f), androidx.compose.ui.geometry.Size(28 * scale, 48 * scale))
            drawRect(Color(0xFF181923), androidx.compose.ui.geometry.Offset(cx + 5 * scale, size.height * .77f), androidx.compose.ui.geometry.Size(28 * scale, 48 * scale))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text("CURRENT TRAVELER", color = PaleGold, fontSize = 9.sp, letterSpacing = 1.7.sp)
            Text(name, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("Preview only · not applied in Minecraft", color = Silver, fontSize = 11.sp)
        }
        Text("✦", color = PaleGold, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
    }
}

@Composable
private fun InstallCard(installed: Boolean) {
    InfoCard(title = "MINECRAFT BEDROCK") {
        Text(if (installed) "Official app detected on this device" else "Official app not detected", color = Silver, fontSize = 15.sp)
        Text("Microsoft, Xbox, and Minecraft ownership are not verified by Oblivion.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PlayButton(installed: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text("PLAY", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
    }
}

@Composable
private fun PlayScreen() {
    val launchManager: GameLaunchViewModel = viewModel()
    val state by launchManager.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { launchManager.requestInClientLaunch() }
    androidx.compose.runtime.DisposableEffect(owner, launchManager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) launchManager.onHostResumed() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    ScreenColumn("Play", "Oblivion checks whether it can host Bedrock gameplay. An external app handoff is shown separately as a compatibility fallback.") {
        InfoCard(when (state.phase) {
            LaunchPhase.PREPARING_IN_CLIENT -> "CHECKING IN-CLIENT SUPPORT"
            LaunchPhase.IN_CLIENT_UNAVAILABLE -> "IN-CLIENT GAMEPLAY UNAVAILABLE"
            LaunchPhase.EXTERNAL_FALLBACK_STARTING -> "OPENING SEPARATE APP"
            LaunchPhase.EXTERNAL_FALLBACK_RETURNED -> "RETURNED TO OBLIVION"
            LaunchPhase.FAILED -> "LAUNCH CHECK FAILED"
            LaunchPhase.INTERRUPTED -> "LAUNCH FLOW INTERRUPTED"
            LaunchPhase.IDLE -> "READY TO CHECK"
        }) {
            Text(state.message.ifBlank { "Checking the configured in-client runtime provider." }, color = Silver, fontSize = 13.sp, lineHeight = 19.sp)
            if (state.phase == LaunchPhase.PREPARING_IN_CLIENT) Text("Checking…", color = PaleGold, fontSize = 12.sp)
        }
        if (state.phase == LaunchPhase.IN_CLIENT_UNAVAILABLE || state.phase == LaunchPhase.FAILED || state.phase == LaunchPhase.INTERRUPTED) {
            Button(onClick = { launchManager.requestInClientLaunch() }, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Retry in-client check") }
        }
        InfoCard("OPTIONAL COMPATIBILITY FALLBACK") {
            Text("This opens the official Minecraft app as a separate Android app. It does not host gameplay inside Oblivion or let Oblivion control the running session.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedButton(onClick = { launchManager.openExternalCompatibilityFallback() }) {
                Text(if (state.gameInstalled) "Open Minecraft separately" else "Find Minecraft on Google Play")
            }
        }
        InfoCard("WHAT WOULD ENABLE HOSTING") {
            Text("Oblivion needs a Minecraft-authorized Android runtime/embedding interface, or an independently redistributable runtime with a legitimate license and supported authentication. None is available or configured in this project. Android cross-app activity embedding requires the game app to opt in and Android 13 or later.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ServiceStatusCard(discordName: String?) {
    InfoCard("ACCOUNT CONNECTIONS") {
        StatusLine("Microsoft account", "Not connected · MSAL app registration required")
        StatusLine("Discord", discordName?.let { "Connected as $it" } ?: "Not connected · API configuration required")
        StatusLine("Oblivion role", "Not assigned · server account not configured")
    }
}

@Composable
private fun StatusLine(title: String, state: String) {
    Column(Modifier.padding(top = 7.dp)) {
        Text(title, color = Silver, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(state, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun FeatureTile(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(78.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Silver)) {
        Column(horizontalAlignment = Alignment.Start) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 10.sp, color = Muted) }
    }
}

@Composable
private fun ProfileScreen(prefs: OblivionPreferences, logger: AppLogger) {
    val context = LocalContext.current
    val name by prefs.displayName.collectAsStateWithLifecycle(initialValue = "Wanderer")
    var field by remember(name) { mutableStateOf(name) }
    var status by remember { mutableStateOf("") }
    var discordName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        discordName = DiscordAuthClient.currentUsername(context)
        DiscordAuthEvents.events.collect { result ->
            when (result) {
                is com.m4lych.oblivion.data.DiscordAuthResult.Connected -> discordName = result.username
                is com.m4lych.oblivion.data.DiscordAuthResult.Error -> discordName = DiscordAuthClient.currentUsername(context)
                is com.m4lych.oblivion.data.DiscordAuthResult.Started -> Unit
                com.m4lych.oblivion.data.DiscordAuthResult.Disconnected -> discordName = null
            }
        }
    }
    ScreenColumn("Your profile", "A private, device-local profile. Create/link an Oblivion account when the service is configured.") {
        CharacterCard(name, Modifier.fillMaxWidth().height(245.dp))
        InfoCard("ACCOUNT STATUS") {
            StatusLine("Oblivion ID", "Local profile only · no server ID has been issued")
            StatusLine("Role badges", "Unavailable until a server account is linked")
            StatusLine("Microsoft / Xbox", "Not connected / not verified")
            StatusLine("Discord", discordName?.let { "Connected as $it" } ?: "Not connected")
        }
        OutlinedTextField(value = field, onValueChange = { field = it.take(24) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val cleaned = field.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").take(24)
            if (cleaned.length < 3) status = "Use 3–24 letters, numbers, spaces, _ or -."
            else scope.launch { prefs.saveDisplayName(cleaned); status = "Saved on this device."; logger.write(LogLevel.INFO, "profile", "Local display name updated") }
        }, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Save local profile") }
        if (status.isNotBlank()) Text(status, color = PaleGold)
        Text("Privacy: this name stays on this device until you choose to connect an account.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun CharacterScreen(logger: AppLogger, prefs: OblivionPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedPreview by prefs.skinPreview.collectAsStateWithLifecycle(initialValue = SkinPreviewSelection())
    var skin by remember { mutableStateOf("Original placeholder") }
    var sampledColor by remember { mutableStateOf(Plum) }
    var feedback by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    LaunchedEffect(savedPreview) {
        val safeName = savedPreview.fileName.takeIf { Regex("^skin_[A-Za-z0-9_-]{1,64}\\.png$").matches(it) }
        val file = safeName?.let { File(File(context.filesDir, "skins"), it) }
        if (file?.isFile == true) {
            skin = file.name
            sampledColor = Color(savedPreview.tintArgb)
        } else {
            skin = "Original placeholder"
            sampledColor = Plum
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) scope.launch {
            importing = true
            feedback = "Validating skin PNG…"
            val outcome = runCatching {
                val imported = withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, "skins").apply { mkdirs() }
                    check(directory.isDirectory) { "Could not create the skin library." }
                    val fileName = "skin_${UUID.randomUUID()}.png"
                    val temporary = File(directory, ".$fileName.tmp")
                    val target = File(directory, fileName)
                    try {
                        val source = context.contentResolver.openInputStream(uri) ?: error("Could not read the selected file.")
                        var copied = 0L
                        source.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    require(copied <= MAX_SKIN_PNG_BYTES) { "Skin PNG exceeds the 16 MiB import limit." }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val header = ByteArray(PNG_SIGNATURE.size)
                        val headerBytes = temporary.inputStream().use { it.read(header) }
                        require(headerBytes == PNG_SIGNATURE.size && header.contentEquals(PNG_SIGNATURE)) { "Choose a valid PNG file." }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(temporary.absolutePath, bounds)
                        require(bounds.outWidth == 64 && (bounds.outHeight == 64 || bounds.outHeight == 32)) { "Choose a 64×64 or 64×32 PNG skin." }
                        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val image = BitmapFactory.decodeFile(temporary.absolutePath, options) ?: error("Could not decode the PNG image.")
                        val tint = try { image.getPixel(image.width / 2, image.height / 2) } finally { image.recycle() }
                        check(temporary.renameTo(target)) { "Could not save the skin PNG." }
                        SkinPreviewSelection(target.name, tint) to copied
                    } finally {
                        temporary.delete()
                    }
                }
                prefs.saveSkinPreview(imported.first.fileName, imported.first.tintArgb)
                imported
            }
            outcome.onSuccess { (selection, bytes) ->
                skin = selection.fileName
                sampledColor = Color(selection.tintArgb)
                feedback = "Saved to Oblivion. This launcher preview does not change Minecraft."
                logger.write(LogLevel.INFO, "skin", "Imported user skin PNG, $bytes bytes")
            }.onFailure { error ->
                feedback = error.message ?: "Skin import failed. Choose a valid PNG and try again."
                logger.write(LogLevel.WARNING, "skin", "Skin import rejected")
            }
            importing = false
        }
    }
    ScreenColumn("Character archive", "Import a Bedrock-sized skin PNG and preview it in Oblivion.") {
        CharacterCard("Traveler", Modifier.fillMaxWidth().height(290.dp), sampledColor)
        InfoCard("SELECTED SKIN") { Text(skin, color = Silver); Text("Preview uses a color sample; no 3D model or in-game skin installation is claimed.", color = Muted, fontSize = 12.sp) }
        Button(onClick = { picker.launch(arrayOf("image/png")) }, enabled = !importing, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text(if (importing) "Importing…" else "Import skin PNG") }
        if (feedback.isNotBlank()) Text(feedback, color = PaleGold, fontSize = 12.sp)
    }
}

@Composable
private fun LibraryScreen(logger: AppLogger) {
    val context = LocalContext.current
    val library = remember { WorldLibrary(File(context.filesDir, "worlds")) }
    var worlds by remember { mutableStateOf(library.list()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<com.m4lych.oblivion.data.LocalWorld?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "import.mcworld" } ?: "import.mcworld"
                val stream = context.contentResolver.openInputStream(uri)
                if (stream == null) ImportWorldResult.Rejected("Oblivion could not read that file.") else library.import(name, stream)
            }
            worlds = withContext(Dispatchers.IO) { library.list() }
            loading = false
            message = when (result) {
                is ImportWorldResult.Imported -> "Saved to Oblivion's private library. Open it in Minecraft using the game's supported import flow."
                ImportWorldResult.Duplicate -> "This world is already in the Oblivion library."
                is ImportWorldResult.Rejected -> result.message
            }
            logger.write(if (result is ImportWorldResult.Imported) LogLevel.INFO else LogLevel.WARNING, "world-import", "World import result: ${result.javaClass.simpleName}")
        }
    }
    ScreenColumn("World library", "Import copies to Oblivion storage. They are not installed in or activated by Minecraft.") {
        Button(onClick = { picker.launch(arrayOf("application/octet-stream", "application/zip")) }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text(if (loading) "Importing…" else "Import .mcworld") }
        if (message.isNotBlank()) Text(message, color = PaleGold, fontSize = 12.sp)
        if (worlds.isEmpty()) InfoCard("YOUR LIBRARY IS QUIET") { Text("Imported worlds will appear here. Files remain in Oblivion's app-private storage.", color = Muted) }
        worlds.forEach { world ->
            InfoCard(world.name) {
                Text("${readableSize(world.size)} · ${DateFormat.getDateTimeInstance().format(Date(world.importedAt))}", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", world.file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share world archive"))
                        }.onFailure { message = "Could not open Android sharing." }
                    }) { Text("Share") }
                    OutlinedButton(onClick = { pendingDelete = world }) { Text("Remove copy") }
                }
            }
        }
    }
    pendingDelete?.let { world -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Remove local copy?") }, text = { Text("${world.name} will be deleted from Oblivion's private library. The source and Minecraft worlds are not affected.") }, confirmButton = { Button(onClick = { scope.launch { withContext(Dispatchers.IO) { library.remove(world) }; worlds = withContext(Dispatchers.IO) { library.list() }; pendingDelete = null; message = "Local copy removed." } }) { Text("Remove") } }, dismissButton = { OutlinedButton(onClick = { pendingDelete = null }) { Text("Cancel") } }) }
}

@Composable
private fun CommunityScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discordName by remember { mutableStateOf<String?>(null) }
    var authMessage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        discordName = DiscordAuthClient.currentUsername(context)
        DiscordAuthEvents.events.collect { result ->
            when (result) {
                is com.m4lych.oblivion.data.DiscordAuthResult.Connected -> {
                    discordName = DiscordAuthClient.currentUsername(context) ?: result.username
                    authMessage = "Discord identity linked to the Oblivion account. Community membership is verified separately by the service."
                }
                is com.m4lych.oblivion.data.DiscordAuthResult.Error -> authMessage = result.message
                is com.m4lych.oblivion.data.DiscordAuthResult.Started -> Unit
                com.m4lych.oblivion.data.DiscordAuthResult.Disconnected -> discordName = null
            }
        }
    }
    ScreenColumn("Community & discovery", "Oblivion accounts and role data require the deployed service. Local features remain available without it.") {
        FeatureTile("News & changelog", "Official feeds not configured", Modifier.fillMaxWidth()) { onOpen("news") }
        FeatureTile("Marketplace", "Catalog service not configured", Modifier.fillMaxWidth()) { onOpen("marketplace") }
        FeatureTile("Friends", "Oblivion friend API not configured", Modifier.fillMaxWidth()) { onOpen("friends") }
        FeatureTile("Saved servers", "Manage local server notes", Modifier.fillMaxWidth()) { onOpen("servers") }
        InfoCard("DISCORD ACCOUNT") {
            Text(discordName?.let { "Connected: $it" } ?: "Not connected", color = Silver, fontSize = 13.sp)
            Text("Guild membership and Oblivion roles are verified by the backend. OAuth alone does not grant Member.", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (discordName == null) Button(onClick = {
                    authMessage = "Starting secure Discord sign-in…"
                    scope.launch {
                        when (val result = DiscordAuthClient.start(context)) {
                            is com.m4lych.oblivion.data.DiscordAuthResult.Started -> runCatching {
                                DiscordAuthClient.openAuthorizationPage(context, result.authorizeUrl)
                            }.onSuccess { authMessage = "Continue sign-in in your browser, then return to Oblivion." }
                                .onFailure { authMessage = "No browser could open Discord sign-in." }
                            is com.m4lych.oblivion.data.DiscordAuthResult.Error -> authMessage = result.message
                            is com.m4lych.oblivion.data.DiscordAuthResult.Connected -> Unit
                            com.m4lych.oblivion.data.DiscordAuthResult.Disconnected -> Unit
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Connect Discord") }
                else OutlinedButton(onClick = {
                    scope.launch {
                        val revoked = DiscordAuthClient.signOut(context)
                        discordName = null
                        DiscordAuthEvents.emit(com.m4lych.oblivion.data.DiscordAuthResult.Disconnected)
                        authMessage = if (revoked) "Discord session disconnected and revoked." else "Disconnected on this device. Server revocation could not be confirmed; the session will expire automatically."
                    }
                }) { Text("Disconnect") }
            }
            if (authMessage.isNotBlank()) Text(authMessage, color = PaleGold, fontSize = 12.sp)
            Text("Microsoft sign-in is not configured. Basic Oblivion navigation does not require an account.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NewsScreen() = ConfigPage("News & changelog", "Official Minecraft changelog feed is not configured. No release notes are shown until a source and refresh service are connected.")

@Composable
private fun MarketplaceScreen() = ConfigPage("Marketplace", "No catalog backend is configured. Oblivion does not offer downloads until source, license, compatibility, and entitlement metadata can be verified.")

@Composable
private fun ServersScreen(prefs: OblivionPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by prefs.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedServer?>(null) }
    val visibleServers = remember(saved, search) {
        saved.filter { it.name.contains(search.trim(), ignoreCase = true) || it.address.contains(search.trim(), ignoreCase = true) }
    }
    ScreenColumn("Server notes", "Saved locally for reference. Oblivion cannot connect to or edit the official Minecraft server list.") {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it.take(255) }, label = { Text("Host or host:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val cleanName = name.trim()
            val cleanAddress = address.trim()
            when {
                cleanName.isEmpty() -> status = "Enter a server name."
                !ServerAddressValidator.isValid(cleanAddress) -> status = "Enter a valid host or host:port (port 1–65535)."
                else -> scope.launch {
                    runCatching {
                        prefs.saveServer(SavedServer(editingId ?: UUID.randomUUID().toString(), cleanName, cleanAddress))
                    }.onSuccess {
                        status = if (editingId == null) "Server note saved on this device." else "Server note updated."
                        name = ""
                        address = ""
                        editingId = null
                    }.onFailure {
                        status = "Could not save this server note."
                    }
                }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
            Text(if (editingId == null) "Save server note" else "Update server note")
        }
        if (editingId != null) OutlinedButton(onClick = { editingId = null; name = ""; address = "" }) { Text("Cancel edit") }
        if (status.isNotBlank()) Text(status, color = PaleGold, fontSize = 12.sp)
        OutlinedTextField(search, { search = it.take(80) }, label = { Text("Search saved servers") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (saved.isEmpty()) InfoCard("NO SAVED SERVERS") { Text("Server notes are stored on this device and remain available offline.", color = Muted) }
        else if (visibleServers.isEmpty()) Text("No servers match this search.", color = Muted)
        visibleServers.forEach { server ->
            InfoCard(server.name) {
                Text(server.address, color = Silver)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Minecraft server address", server.address))
                        status = if (clipboard == null) "Clipboard is unavailable." else "Server address copied."
                    }) { Text("Copy") }
                    OutlinedButton(onClick = { editingId = server.id; name = server.name; address = server.address; status = "Editing ${server.name}." }) { Text("Edit") }
                    OutlinedButton(onClick = { pendingDelete = server }) { Text("Remove") }
                }
            }
        }
    }
    pendingDelete?.let { server -> AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("Remove server note?") },
        text = { Text("${server.name} will be removed from Oblivion's local server notes.") },
        confirmButton = { Button(onClick = {
            scope.launch {
                prefs.removeServer(server.id)
                pendingDelete = null
                if (editingId == server.id) { editingId = null; name = ""; address = "" }
                status = "Server note removed."
            }
        }) { Text("Remove") } },
        dismissButton = { OutlinedButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
    ) }
}

@Composable
private fun SettingsScreen(prefs: OblivionPreferences, onOpen: (String) -> Unit) {
    val analytics by prefs.optionalAnalytics.collectAsStateWithLifecycle(initialValue = false)
    val reducedMotion by prefs.reducedMotion.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    ScreenColumn("Settings", "Preferences are stored on this device.") {
        PreferenceRow("Optional analytics", "Off by default. Enabling only records app diagnostics after an analytics endpoint is configured.", analytics) { scope.launch { prefs.setOptionalAnalytics(it) } }
        PreferenceRow("Reduce motion", "Use simpler transitions where supported.", reducedMotion) { scope.launch { prefs.setReducedMotion(it) } }
        FeatureTile("Diagnostic logs", "View, redact and share local logs", Modifier.fillMaxWidth()) { onOpen("diagnostics") }
        FeatureTile("Owner & staff tools", "Available only after authorized account linking", Modifier.fillMaxWidth()) { onOpen("admin") }
        FeatureTile("Credits & licenses", "Project and third-party notices", Modifier.fillMaxWidth()) { onOpen("about") }
        Text("Microsoft sign-in, CurseForge and other service functions need configuration. Discord uses the public API URL supplied as a Gradle property; secrets stay on the backend.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PreferenceRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    InfoCard(title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(subtitle, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun DiagnosticsScreen(logger: AppLogger) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf(false) }
    var reportStatus by remember { mutableStateOf("") }
    val logText = remember { logger.read() }
    ScreenColumn("Diagnostics", "Local structured logs are redacted and rotated. Reports are never uploaded automatically.") {
        Button(onClick = { preview = true }, colors = ButtonDefaults.buttonColors(containerColor = Teal)) { Text("Send diagnostic report") }
        OutlinedButton(onClick = {
            runCatching {
                val file = logger.exportFile()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share redacted log"))
            }.onFailure { reportStatus = "Could not open Android sharing." }
        }) { Text("Export and share log") }
        if (reportStatus.isNotBlank()) Text(reportStatus, color = PaleGold)
        InfoCard("RECENT LOG") { Text(if (logText.isBlank()) "No log entries yet." else logText.takeLast(8000), color = Muted, fontSize = 10.sp, lineHeight = 15.sp, maxLines = 90, overflow = TextOverflow.Ellipsis) }
    }
    if (preview) AlertDialog(
        onDismissRequest = { preview = false }, title = { Text("Review diagnostic report") },
        text = { Text("Would include app version 0.1.0 and the redacted local log (${logText.length} characters). Passwords, bearer tokens and common secret fields are filtered. Reports are not uploaded because no backend URL is configured.\n\nPreview:\n${logText.takeLast(900)}") },
        confirmButton = { Button(onClick = { preview = false; reportStatus = "Report was not uploaded: configure the protected backend endpoint first." }) { Text("I understand") } },
        dismissButton = { OutlinedButton(onClick = { preview = false }) { Text("Cancel") } }
    )
}

@Composable
private fun AdminScreen() = ConfigPage("Administration", "No authenticated Oblivion session is configured. Privileged controls are not available. The backend design requires server-side permission checks for every action.")

@Composable
private fun AboutScreen() {
    ScreenColumn("Credits & licenses", "Oblivion is an independent project and is not affiliated with Mojang, Microsoft, Discord, CurseForge, or Minecraft Dungeons.") {
        InfoCard("PROJECT") { Text("Oblivion · client-side Bedrock launcher companion", color = Silver); Text("Owner and team links: not configured.", color = Muted) }
        InfoCard("THIRD-PARTY") { Text("Jetpack Compose, AndroidX, Kotlin and Cloudflare Workers. See THIRD_PARTY_NOTICES.md for license references.", color = Muted) }
        InfoCard("ASSETS") { Text("This build uses original geometric UI art and no Minecraft or Ave Mujica artwork.", color = Muted) }
        Text("Project license is pending maintainer selection. No distribution license is implied.", color = PaleGold, fontSize = 12.sp)
    }
}

@Composable
private fun ConfigPage(title: String, message: String) {
    ScreenColumn(title, "External service status") { InfoCard("NOT CONFIGURED") { Text(message, color = Muted, fontSize = 13.sp) } }
}

@Composable
private fun ScreenColumn(title: String, subtitle: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Obsidian).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 28.dp)) {
        item {
            Text("OBLIVION", color = PaleGold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            Text(title, color = Silver, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = PaleGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            content()
        }
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KiB"
}
