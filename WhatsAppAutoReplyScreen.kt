package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.whatsapp.WhatsAppAutoReplyPreferences
import com.example.service.JarvisNotificationListenerService
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WhatsApp Auto-Reply Settings & Management Screen.
 * 100% Free, local-only, zero licensing, zero paywall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppAutoReplyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { WhatsAppAutoReplyPreferences.getInstance(context) }

    val isEnabled by prefs.isEnabled.collectAsState()
    val replyMessage by prefs.replyMessage.collectAsState()
    val replyToEveryone by prefs.replyToEveryone.collectAsState()
    val onlyWhenAway by prefs.onlyWhenAway.collectAsState()
    val cooldownMinutes by prefs.cooldownMinutes.collectAsState()
    val replyToGroups by prefs.replyToGroups.collectAsState()
    val history by prefs.history.collectAsState()
    val totalReplies by prefs.totalRepliesSent.collectAsState()

    var hasNotificationAccess by remember {
        mutableStateOf(JarvisNotificationListenerService.hasNotificationAccess(context))
    }

    var messageInput by remember(replyMessage) { mutableStateOf(replyMessage) }
    var showSavedSnackbar by remember { mutableStateOf(false) }

    // Recheck notification access when screen resumes or composes
    LaunchedEffect(Unit) {
        hasNotificationAccess = JarvisNotificationListenerService.hasNotificationAccess(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "WhatsApp Auto-Reply",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = JarvisCyan,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("whatsapp_autoreply_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = JarvisCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisDeepBg)
            )
        },
        containerColor = JarvisDeepBg,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TOP BANNER: Status & Subtitle
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, if (isEnabled && hasNotificationAccess) JarvisCyan.copy(alpha = 0.6f) else JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("whatsapp_autoreply_status_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "WhatsApp Auto-Reply",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Automatically reply to incoming WhatsApp messages",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isEnabled) JarvisCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, if (isEnabled) JarvisCyan else JarvisCardBorder)
                        ) {
                            Text(
                                text = if (isEnabled) "Active" else "Off",
                                color = if (isEnabled) JarvisCyan else JarvisTextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = JarvisCardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isEnabled) "Auto-reply is active" else "Auto-reply is off",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEnabled) JarvisCyan else JarvisTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            // NOTIFICATION ACCESS WARNING (If Missing)
            if (!hasNotificationAccess) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisError),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("whatsapp_autoreply_permission_warning")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Permission Warning",
                                tint = JarvisError,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Notification access required",
                                color = JarvisError,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Android requires Notification Access so JARVIS can detect incoming WhatsApp messages and send your configured auto-reply via the notification's standard reply action.",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisError.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, JarvisError),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grant_notification_access_btn")
                        ) {
                            Text(
                                text = "Grant Notification Access",
                                color = JarvisTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                // NOTIFICATION ACCESS GRANTED BADGE
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Permission Granted",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Access Granted",
                                color = JarvisCyan,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Jarvis Notification Listener is connected and monitoring incoming WhatsApp messages.",
                                color = JarvisTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // MAIN TOGGLE CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("whatsapp_autoreply_toggle_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Auto-Reply",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isEnabled) "Auto-reply is active for incoming WhatsApp chats" else "Auto-reply is off",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { desired ->
                                prefs.setEnabled(desired)
                                hasNotificationAccess = JarvisNotificationListenerService.hasNotificationAccess(context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisCyan,
                                checkedTrackColor = JarvisCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisCardBorder
                            ),
                            modifier = Modifier.testTag("whatsapp_autoreply_main_switch")
                        )
                    }
                }
            }

            // AUTO-REPLY MESSAGE CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("whatsapp_autoreply_message_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Auto-Reply Message",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 15.sp
                        )

                        TextButton(
                            onClick = {
                                messageInput = WhatsAppAutoReplyPreferences.DEFAULT_REPLY_MESSAGE
                                prefs.setReplyMessage(messageInput)
                            }
                        ) {
                            Text("Reset", color = JarvisCyan, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = {
                            messageInput = it
                            prefs.setReplyMessage(it)
                        },
                        placeholder = { Text("Enter auto-reply text...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisCardBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            focusedLabelColor = JarvisCyan,
                            unfocusedLabelColor = JarvisTextSecondary
                        ),
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("whatsapp_autoreply_text_field")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Sent automatically to eligible WhatsApp messages.",
                            color = JarvisTextSecondary,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "${messageInput.length} chars",
                            color = JarvisTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // RULES & RECIPIENT SETTINGS CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Reply Rules & Conditions",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option: Reply to everyone
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reply to everyone",
                                fontWeight = FontWeight.SemiBold,
                                color = JarvisTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Send auto-reply to any incoming WhatsApp contact",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = replyToEveryone,
                            onCheckedChange = { prefs.setReplyToEveryone(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisCyan,
                                checkedTrackColor = JarvisCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisCardBorder
                            ),
                            modifier = Modifier.testTag("reply_to_everyone_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = JarvisCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Option: Only reply when I'm away
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Only reply when I'm away",
                                fontWeight = FontWeight.SemiBold,
                                color = JarvisTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Only send when screen is turned off or device is locked",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = onlyWhenAway,
                            onCheckedChange = { prefs.setOnlyWhenAway(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisCyan,
                                checkedTrackColor = JarvisCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisCardBorder
                            ),
                            modifier = Modifier.testTag("only_when_away_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = JarvisCardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Option: Reply to groups
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reply to WhatsApp groups",
                                fontWeight = FontWeight.SemiBold,
                                color = JarvisTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Off by default to prevent spamming group conversations",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = replyToGroups,
                            onCheckedChange = { prefs.setReplyToGroups(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisCyan,
                                checkedTrackColor = JarvisCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisCardBorder
                            ),
                            modifier = Modifier.testTag("reply_to_groups_switch")
                        )
                    }
                }
            }

            // COOLDOWN CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Reply Cooldown",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Prevents replying repeatedly to the same contact or conversation within a short timeframe to avoid spamming.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val cooldownOptions = listOf(
                        0 to "Immediate (0m)",
                        1 to "1 min",
                        5 to "5 min",
                        15 to "15 min",
                        30 to "30 min",
                        60 to "1 hour"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        cooldownOptions.take(3).forEach { (minutes, label) ->
                            val isSelected = cooldownMinutes == minutes
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, if (isSelected) JarvisCyan else JarvisCardBorder),
                                onClick = { prefs.setCooldownMinutes(minutes) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cooldown_${minutes}m_btn")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        cooldownOptions.drop(3).forEach { (minutes, label) ->
                            val isSelected = cooldownMinutes == minutes
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(1.dp, if (isSelected) JarvisCyan else JarvisCardBorder),
                                onClick = { prefs.setCooldownMinutes(minutes) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cooldown_${minutes}m_btn")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // RECENT REPLIES HISTORY CARD
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Recent Auto-Replies",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Total dispatched: $totalReplies",
                                color = JarvisCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (history.isNotEmpty()) {
                            IconButton(onClick = { prefs.clearHistory() }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear History",
                                    tint = JarvisTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (history.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Text(
                                text = "No auto-replies sent yet.",
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            history.take(8).forEach { item ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = JarvisDeepBg,
                                    border = BorderStroke(1.dp, JarvisCardBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.sender,
                                                fontWeight = FontWeight.Bold,
                                                color = JarvisTextPrimary,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = dateFormat.format(Date(item.timestamp)),
                                                color = JarvisTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }

                                        if (item.incomingSnippet.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Received: \"${item.incomingSnippet}\"",
                                                color = JarvisTextSecondary,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Replied: \"${item.replySent}\"",
                                            color = JarvisCyan,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
