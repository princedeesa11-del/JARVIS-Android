package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.ai.Persona
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import com.example.service.WakeWordService
import com.example.tools.ToolRegistry
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*
import com.example.voice.wakeword.WakePhraseProfile
import com.example.voice.wakeword.WakeSensitivity

/**
 * AdvancedSettingsScreen preserves the complete existing core configuration
 * functionality of Jarvis:
 * - Appearance & Orb Customization
 * - Touch Guard Intruder Security
 * - Screen Lock Sentry
 * - WhatsApp Auto-Reply
 * - Wake Word Sentinel & Picovoice Porcupine
 * - Gemini Model Runtime selection
 * - Custom Gemini API Key configuration
 * - Persona & Vocal Profile
 * - System Services & Permissions
 * - System Telemetry
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    viewModel: JarvisViewModel,
    onBack: () -> Unit,
    onNavigateTo: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activePersona by viewModel.activePersona.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val customKey by viewModel.customApiKey.collectAsState()
    var keyInput by remember { mutableStateOf(customKey) }
    val wakeWordStatus by viewModel.wakeWordStatus.collectAsState()
    val wakeSensitivity by viewModel.wakeSensitivity.collectAsState()
    val wakePhraseProfile by viewModel.wakePhraseProfile.collectAsState()
    val isBackgroundWakeEnabled by viewModel.isBackgroundWakeEnabled.collectAsState()
    val isBargeInEnabled by viewModel.isBargeInEnabled.collectAsState()
    val isFalseTriggerProtectionEnabled by viewModel.isFalseTriggerProtectionEnabled.collectAsState()
    val prefs = remember { context.getSharedPreferences("jarvis_prefs", android.content.Context.MODE_PRIVATE) }
    var picovoiceKeyInput by remember { mutableStateOf(prefs.getString("picovoice_access_key", "") ?: "") }
    var wakeWordActive by remember { mutableStateOf(WakeWordService.isRunning()) }

    var hasMicPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasCameraPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasContactsPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
    }
    var hasCalendarPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    var hasNotificationPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasMicPerm = perms[Manifest.permission.RECORD_AUDIO] ?: hasMicPerm
        hasCameraPerm = perms[Manifest.permission.CAMERA] ?: hasCameraPerm
        hasContactsPerm = perms[Manifest.permission.READ_CONTACTS] ?: hasContactsPerm
        hasCalendarPerm = perms[Manifest.permission.READ_CALENDAR] ?: hasCalendarPerm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPerm = perms[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPerm
        }
    }

    val buildConfigKeyPresent = try {
        BuildConfig.GEMINI_API_KEY.isNotEmpty() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY"
    } catch (_: Exception) {
        false
    }

    var showAppearanceSubscreen by remember { mutableStateOf(false) }
    var showTouchGuardSubscreen by remember { mutableStateOf(false) }
    var showScreenLockSubscreen by remember { mutableStateOf(false) }
    var showPatternPinSubscreen by remember { mutableStateOf(false) }
    var showWhatsAppAutoReplySubscreen by remember { mutableStateOf(false) }

    if (showWhatsAppAutoReplySubscreen) {
        WhatsAppAutoReplyScreen(
            onBack = { showWhatsAppAutoReplySubscreen = false },
            modifier = modifier
        )
        return
    }

    if (showPatternPinSubscreen) {
        PatternPinScreen(
            onBack = { showPatternPinSubscreen = false },
            modifier = modifier
        )
        return
    }

    if (showScreenLockSubscreen) {
        ScreenLockScreen(
            onBack = { showScreenLockSubscreen = false },
            onNavigateToPatternPin = { showPatternPinSubscreen = true },
            modifier = modifier
        )
        return
    }

    if (showAppearanceSubscreen) {
        AppearanceScreen(
            viewModel = viewModel,
            onBack = { showAppearanceSubscreen = false },
            modifier = modifier
        )
        return
    }

    if (showTouchGuardSubscreen) {
        TouchGuardScreen(
            viewModel = viewModel,
            onBack = { showTouchGuardSubscreen = false },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = JarvisCyan
                        )
                    }
                },
                title = {
                    Text(
                        text = "Advanced Settings",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            val msg = if (JarvisNotificationListenerService.isRunning()) {
                                "Notification Listener Active"
                            } else {
                                "Notifications: Normal Mode"
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("settings_notification_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notifications",
                            tint = JarvisCyan
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF0A2540), Color(0xFF001933))
                                )
                            )
                            .border(1.dp, JarvisCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .testTag("maya_top_bar_logo"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "JARVIS Logo",
                            tint = JarvisCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF070F1E))
            )
        },
        containerColor = Color(0xFF070F1E),
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // SECTION: APPEARANCE & HOLOGRAPHIC ORB CUSTOMIZATION
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "APPEARANCE & VISUAL IDENTITY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAppearanceSubscreen = true }
                        .testTag("appearance_settings_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Holographic Orb",
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Orb & Appearance Customization",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Customize Orb Style (Pulse Reactor, Swarm, F.R.I.D.A.Y.), Colors, Scale, and Home Display mode.",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Customizer",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // SECTION: TOUCH GUARD SECURITY SENTRY
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "PERIMETER & INTRUDER SECURITY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTouchGuardSubscreen = true }
                        .testTag("touch_guard_settings_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Touch Guard",
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Touch Guard Security",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Anti-theft sentry: screen wake, phone motion/lift, charger disconnect, front-camera evidence, and alarm.",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Touch Guard",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // SECTION: SCREEN LOCK
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SCREEN LOCK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showScreenLockSubscreen = true }
                        .testTag("screen_lock_settings_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Screen Lock",
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Screen lock",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Waking the screen and optionally unlocking it when JARVIS needs to perform a screen action.",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Screen Lock",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // SECTION: WHATSAPP AUTO-REPLY
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "WHATSAPP AUTO-REPLY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWhatsAppAutoReplySubscreen = true }
                        .testTag("whatsapp_autoreply_settings_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Message,
                                    contentDescription = "WhatsApp Auto-Reply",
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "WhatsApp Auto-Reply",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Automatically reply to incoming WhatsApp messages",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open WhatsApp Auto-Reply",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }



            // SECTION: AI PROVIDER & MODEL
            item {
                Text(
                    text = "AI BRAIN & MODEL RUNTIME",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Gemini Model", fontWeight = FontWeight.Bold, color = JarvisTextPrimary)
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf(
                            "gemini-3.5-flash" to "Gemini 3.5 Flash (Recommended: Fast, Multimodal, Tool Calling)",
                            "gemini-3.1-pro-preview" to "Gemini 3.1 Pro (Advanced Multi-step Reasoning)",
                            "gemini-2.5-flash" to "Gemini 2.5 Flash (Ultra-fast Low Latency)"
                        ).forEach { (modelId, desc) ->
                            val isSelected = selectedModel == modelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setModel(modelId) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setModel(modelId) },
                                    colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = modelId,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Text(desc, fontSize = 11.sp, color = JarvisTextSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // SECTION: API KEY STATUS & OVERRIDE
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (buildConfigKeyPresent || customKey.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "Key status",
                                tint = if (buildConfigKeyPresent || customKey.isNotBlank()) JarvisAccentGreen else JarvisGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (buildConfigKeyPresent) "Secrets Panel API Key: Active" else "API Key: Local Offline Core",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "You can override or supply a custom Gemini API key below. When blank, JARVIS uses the build secrets key or seamless offline fallback.",
                            fontSize = 12.sp,
                            color = JarvisTextSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("Custom Gemini API Key (Optional)", color = JarvisCyan) },
                            placeholder = { Text("AIzaSy...", color = JarvisTextSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.setCustomApiKey(keyInput.trim()) },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Apply Key", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // SECTION: ASSISTANT PERSONA
            item {
                Text(
                    text = "PERSONALITY & VOCAL PROFILE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = JarvisCyan.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active System",
                                        tint = JarvisCyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "J.A.R.V.I.S.",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = JarvisCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = JarvisAccentGreen.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "PRIMARY ASSISTANT",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = JarvisAccentGreen,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Professional, calm, intelligent, concise, task-focused intelligence.",
                                    fontSize = 12.sp,
                                    color = JarvisTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.speakText(Persona.JARVIS.greeting) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF162D4A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Test voice", tint = JarvisCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Vocal Synthesis", color = JarvisCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // SECTION: ON-DEVICE WAKE-WORD SENTINEL
            item {
                Text(
                    text = "ON-DEVICE WAKE-WORD SENTINEL",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Master Wake Word Switch
                        val isEngineRunning = wakeWordStatus == com.example.voice.wakeword.WakeWordEngineStatus.LISTENING ||
                                wakeWordStatus == com.example.voice.wakeword.WakeWordEngineStatus.READY ||
                                wakeWordStatus == com.example.voice.wakeword.WakeWordEngineStatus.PAUSED

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Wake Word Detection",
                                    fontWeight = FontWeight.Bold,
                                    color = JarvisTextPrimary,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isEngineRunning) "Actively listening for 'Hey Jarvis'" else "Wake word detection disabled",
                                    fontSize = 12.sp,
                                    color = if (isEngineRunning) JarvisAccentGreen else JarvisTextSecondary
                                )
                            }

                            Switch(
                                checked = isEngineRunning,
                                onCheckedChange = { active ->
                                    if (active) {
                                        if (!hasMicPerm) {
                                            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                        } else {
                                            viewModel.startJarvisWakeWord()
                                        }
                                    } else {
                                        viewModel.stopJarvisWakeWord()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = JarvisCyan
                                )
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = JarvisCardBorder.copy(alpha = 0.5f)
                        )

                        // Wake Phrases display
                        Text(
                            text = "Wake Phrase Profile",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (wakePhraseProfile) {
                                WakePhraseProfile.HEY_JARVIS -> "Listening strictly for 'Hey Jarvis' (Primary wake phrase)"
                                WakePhraseProfile.WAKE_UP_JARVIS -> "Listening strictly for 'Wake up Jarvis'"
                                WakePhraseProfile.JARVIS_ONLY -> "Listening strictly for 'Jarvis'"
                                WakePhraseProfile.MULTI_PHRASE -> "Listening for 'Hey Jarvis', 'Wake up Jarvis', and 'Jarvis'"
                            },
                            fontSize = 11.sp,
                            color = JarvisTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                WakePhraseProfile.HEY_JARVIS to "Hey Jarvis",
                                WakePhraseProfile.WAKE_UP_JARVIS to "Wake Up",
                                WakePhraseProfile.JARVIS_ONLY to "Jarvis",
                                WakePhraseProfile.MULTI_PHRASE to "All"
                            ).forEach { (profile, label) ->
                                val isSelected = wakePhraseProfile == profile
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else JarvisCardBg,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) JarvisCyan else JarvisCardBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setWakePhraseProfile(profile) }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) JarvisCyan else JarvisTextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = JarvisCardBorder.copy(alpha = 0.5f)
                        )

                        // Sensitivity Selector (LOW, MEDIUM, HIGH)
                        Text(
                            text = "Wake Sensitivity: ${wakeSensitivity.name}",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (wakeSensitivity) {
                                WakeSensitivity.LOW -> "Fewer false triggers; requires clearer and closer speech"
                                WakeSensitivity.MEDIUM -> "Balanced sensitivity for everyday environments (Recommended)"
                                WakeSensitivity.HIGH -> "More sensitive to quiet, soft, or distant speech"
                            },
                            fontSize = 11.sp,
                            color = JarvisTextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WakeSensitivity.entries.forEach { sens ->
                                val isSelected = wakeSensitivity == sens
                                OutlinedButton(
                                    onClick = { viewModel.setWakeSensitivity(sens) },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else Color.Transparent
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) JarvisCyan else JarvisCardBorder
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = sens.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) JarvisCyan else JarvisTextSecondary
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = JarvisCardBorder.copy(alpha = 0.5f)
                        )

                        // Background Wake Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Background Wake", fontWeight = FontWeight.SemiBold, color = JarvisTextPrimary, fontSize = 13.sp)
                                Text("Trigger JARVIS even when phone is locked or other apps are open", fontSize = 11.sp, color = JarvisTextSecondary)
                            }
                            Switch(
                                checked = isBackgroundWakeEnabled,
                                onCheckedChange = { viewModel.setBackgroundWake(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = JarvisCyan)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Barge-In Interruption Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Barge-In Interruption", fontWeight = FontWeight.SemiBold, color = JarvisTextPrimary, fontSize = 13.sp)
                                Text("Instantly interrupt JARVIS speech when you start talking", fontSize = 11.sp, color = JarvisTextSecondary)
                            }
                            Switch(
                                checked = isBargeInEnabled,
                                onCheckedChange = { viewModel.setBargeIn(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = JarvisCyan)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // False Trigger Protection Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("False Trigger Protection", fontWeight = FontWeight.SemiBold, color = JarvisTextPrimary, fontSize = 13.sp)
                                Text("Suppresses accidental activations from normal room conversation", fontSize = 11.sp, color = JarvisTextSecondary)
                            }
                            Switch(
                                checked = isFalseTriggerProtectionEnabled,
                                onCheckedChange = { viewModel.setFalseTriggerProtection(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = JarvisCyan)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = JarvisCardBorder.copy(alpha = 0.5f)
                        )

                        // Live Diagnostics Status Dashboard
                        Text(
                            text = "LIVE SYSTEM STATUS",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = JarvisCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Microphone Permission:", fontSize = 12.sp, color = JarvisTextSecondary)
                            Text(
                                text = if (hasMicPerm) "GRANTED" else "DENIED",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (hasMicPerm) JarvisAccentGreen else JarvisAccentRed
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Foreground Service:", fontSize = 12.sp, color = JarvisTextSecondary)
                            Text(
                                text = if (WakeWordService.isRunning()) "RUNNING" else "STOPPED",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (WakeWordService.isRunning()) JarvisAccentGreen else JarvisTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Wake Engine Status:", fontSize = 12.sp, color = JarvisTextSecondary)
                            Text(
                                text = wakeWordStatus.name,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = when (wakeWordStatus) {
                                    com.example.voice.wakeword.WakeWordEngineStatus.LISTENING -> JarvisGold
                                    com.example.voice.wakeword.WakeWordEngineStatus.READY -> JarvisAccentGreen
                                    com.example.voice.wakeword.WakeWordEngineStatus.PAUSED -> JarvisCyan
                                    com.example.voice.wakeword.WakeWordEngineStatus.MIC_PERMISSION_DENIED -> JarvisAccentRed
                                    else -> JarvisTextSecondary
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Optional Picovoice Porcupine AccessKey
                        Text(
                            text = "Optional: Picovoice Porcupine AccessKey",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Porcupine provides optional secondary on-device acoustic keyword streaming. Free key from console.picovoice.ai.",
                            fontSize = 11.sp,
                            color = JarvisTextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = picovoiceKeyInput,
                            onValueChange = { picovoiceKeyInput = it },
                            label = { Text("Picovoice AccessKey", color = JarvisCyan) },
                            placeholder = { Text("Enter key from console.picovoice.ai", color = JarvisTextSecondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("picovoice_key_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                prefs.edit().putString("picovoice_access_key", picovoiceKeyInput.trim()).apply()
                                viewModel.stopJarvisWakeWord()
                                viewModel.startJarvisWakeWord()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save & Restart", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // SECTION: SYSTEM SERVICES & PERMISSIONS
            item {
                Text(
                    text = "HARDWARE & SYSTEM SERVICES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PermissionRow("Microphone (Voice Input)", hasMicPerm)
                        PermissionRow("Camera (Vision AI / Torch)", hasCameraPerm)
                        PermissionRow("Contacts (Search & Dial)", hasContactsPerm)
                        PermissionRow("Calendar (Read & Schedule)", hasCalendarPerm)
                        PermissionRow("Notifications (Alerts)", hasNotificationPerm)
                        PermissionRow("Accessibility Service (Inspection)", JarvisAccessibilityService.isRunning())
                        PermissionRow("Notification Listener (Summaries)", JarvisNotificationListenerService.isRunning())

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val reqs = mutableListOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.READ_CALENDAR,
                                        Manifest.permission.WRITE_CALENDAR
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        reqs.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permLauncher.launch(reqs.toTypedArray())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14243C)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Grant Permissions", color = JarvisCyan, fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14243C)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accessibility", color = JarvisCyan, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // SECTION: DIAGNOSTICS & SYSTEM INFO
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("SYSTEM TELEMETRY", fontWeight = FontWeight.Bold, color = JarvisCyan, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("• Registered Core Tools: ${ToolRegistry.allTools.size}", fontSize = 12.sp, color = JarvisTextPrimary)
                        Text("• Database: Room SQLite (jarvis_core.db)", fontSize = 12.sp, color = JarvisTextPrimary)
                        Text("• Architecture: Clean MVVM + Jetpack Compose", fontSize = 12.sp, color = JarvisTextPrimary)
                        Text("• Voice Languages: English, Hindi, Gujarati", fontSize = 12.sp, color = JarvisTextPrimary)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, isGranted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = JarvisTextPrimary, fontSize = 13.sp)
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isGranted) JarvisAccentGreen.copy(alpha = 0.2f) else JarvisAccentRed.copy(alpha = 0.2f)
        ) {
            Text(
                text = if (isGranted) "ACTIVE" else "OFFLINE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGranted) JarvisAccentGreen else JarvisAccentRed,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
