package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*
import com.example.whatsapp.config.WhatsAppSecureCredentialStore
import com.example.whatsapp.model.WhatsAppAutomationMode
import com.example.whatsapp.model.WhatsAppProviderMode
import com.example.whatsapp.model.WhatsAppRepeatInterval
import com.example.whatsapp.model.WhatsAppRuleType
import java.text.SimpleDateFormat
import java.util.*

enum class WhatsAppDashboardTab(val label: String) {
    CONNECTION("Connection"),
    MESSAGES("Conversations"),
    COMPOSE("Compose"),
    CONTACTS("Contacts"),
    RULES("Rules"),
    SCHEDULED("Scheduled"),
    AUDIT("Activity Log")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppCloudDashboardScreen(
    viewModel: JarvisViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(WhatsAppDashboardTab.CONNECTION) }

    val configStore = viewModel.whatsAppConfigStore
    val isAutomationEnabled by configStore.isAutomationEnabled.collectAsState()
    val providerMode by configStore.providerMode.collectAsState()
    val automationMode by configStore.automationMode.collectAsState()
    val isAiReplyEnabled by configStore.isAiAutoReplyEnabled.collectAsState()
    val isRulesEnabled by configStore.isRuleAutomationEnabled.collectAsState()
    val isScheduledEnabled by configStore.isScheduledMessagesEnabled.collectAsState()
    val isConnected by configStore.isConnected.collectAsState()
    val statusMessage by configStore.connectionStatusMessage.collectAsState()
    val phoneId by configStore.phoneNumberId.collectAsState()
    val wabaId by configStore.businessAccountId.collectAsState()
    val accessToken by configStore.accessToken.collectAsState()
    val verifyToken by configStore.verifyToken.collectAsState()
    val webhookPort by configStore.webhookPort.collectAsState()
    val webhookPublicUrl by configStore.webhookPublicUrl.collectAsState()
    val optInRequired by configStore.optInRequired.collectAsState()
    val rateLimitSec by configStore.rateLimitSeconds.collectAsState()

    val messages by viewModel.whatsAppMessages.collectAsState()
    val contacts by viewModel.whatsAppContacts.collectAsState()
    val rules by viewModel.whatsAppRules.collectAsState()
    val scheduled by viewModel.whatsAppScheduled.collectAsState()
    val auditLogs by viewModel.whatsAppAuditLogs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("wa_back_btn")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = JarvisCyan
                        )
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "WhatsApp Automation",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 17.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isConnected -> Color(0xFF00E676)
                                            providerMode == WhatsAppProviderMode.OFF -> Color(0xFF757575)
                                            else -> Color(0xFFFF5252)
                                        }
                                    )
                            )
                        }
                        Text(
                            text = when {
                                isConnected -> "Meta Cloud API: Connected"
                                providerMode == WhatsAppProviderMode.LOCAL_ANDROID_AUTOMATION -> "Local Android Automation Active"
                                providerMode == WhatsAppProviderMode.OFF -> "Automation OFF"
                                else -> "Meta Cloud API: Standby / Disconnected"
                            },
                            fontSize = 11.sp,
                            color = if (isConnected) Color(0xFF00E676) else JarvisTextSecondary
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (isAutomationEnabled) "Auto ON" else "Auto OFF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAutomationEnabled) JarvisCyan else JarvisTextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = isAutomationEnabled,
                            onCheckedChange = { viewModel.setWhatsAppAutomationEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = JarvisCyan,
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisCardBg
                            ),
                            modifier = Modifier.testTag("wa_global_switch")
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
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = Color(0xFF0A1224),
                contentColor = JarvisCyan,
                edgePadding = 12.dp,
                divider = { HorizontalDivider(color = JarvisCardBorder) }
            ) {
                WhatsAppDashboardTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == tab) JarvisCyan else JarvisTextSecondary
                            )
                        },
                        modifier = Modifier.testTag("wa_tab_${tab.name.lowercase()}")
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                when (selectedTab) {
                    WhatsAppDashboardTab.CONNECTION -> ConnectionTab(
                        viewModel = viewModel,
                        isAutomationEnabled = isAutomationEnabled,
                        providerMode = providerMode,
                        automationMode = automationMode,
                        isAiReplyEnabled = isAiReplyEnabled,
                        isRulesEnabled = isRulesEnabled,
                        isScheduledEnabled = isScheduledEnabled,
                        isConnected = isConnected,
                        statusMessage = statusMessage,
                        phoneId = phoneId,
                        wabaId = wabaId,
                        accessToken = accessToken,
                        verifyToken = verifyToken,
                        webhookPort = webhookPort,
                        webhookPublicUrl = webhookPublicUrl,
                        optInRequired = optInRequired,
                        rateLimitSec = rateLimitSec
                    )
                    WhatsAppDashboardTab.MESSAGES -> MessagesTab(messages = messages)
                    WhatsAppDashboardTab.COMPOSE -> ComposeTab(viewModel = viewModel, contacts = contacts)
                    WhatsAppDashboardTab.CONTACTS -> ContactsTab(viewModel = viewModel, contacts = contacts)
                    WhatsAppDashboardTab.RULES -> RulesTab(viewModel = viewModel, rules = rules)
                    WhatsAppDashboardTab.SCHEDULED -> ScheduledTab(viewModel = viewModel, scheduled = scheduled)
                    WhatsAppDashboardTab.AUDIT -> AuditLogsTab(auditLogs = auditLogs)
                }
            }
        }
    }
}

@Composable
private fun ConnectionTab(
    viewModel: JarvisViewModel,
    isAutomationEnabled: Boolean,
    providerMode: WhatsAppProviderMode,
    automationMode: WhatsAppAutomationMode,
    isAiReplyEnabled: Boolean,
    isRulesEnabled: Boolean,
    isScheduledEnabled: Boolean,
    isConnected: Boolean,
    statusMessage: String,
    phoneId: String,
    wabaId: String,
    accessToken: String,
    verifyToken: String,
    webhookPort: Int,
    webhookPublicUrl: String,
    optInRequired: Boolean,
    rateLimitSec: Int
) {
    val context = LocalContext.current
    var showEditCredentialsDialog by remember { mutableStateOf(false) }

    var isTesting by remember { mutableStateOf(false) }
    var testFeedback by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Status Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, if (isConnected) Color(0xFF00E676).copy(alpha = 0.5f) else JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Connection Status",
                                    fontWeight = FontWeight.Bold,
                                    color = JarvisTextPrimary,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isConnected) "● Connected" else "● Disconnected",
                                    color = if (isConnected) Color(0xFF00E676) else Color(0xFFFF5252),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = statusMessage,
                                color = JarvisTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = {
                                isTesting = true
                                testFeedback = null
                                viewModel.testWhatsAppConnection { success, msg ->
                                    isTesting = false
                                    testFeedback = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isTesting && phoneId.isNotBlank() && accessToken.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                            } else {
                                Text("Test Connection", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    if (testFeedback != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = testFeedback!!,
                            color = if (isConnected) Color(0xFF00E676) else JarvisError,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // WhatsApp Provider Mode
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Automation Provider",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = providerMode.description,
                        color = JarvisCyan,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        WhatsAppProviderMode.values().forEach { mode ->
                            val isSelected = providerMode == mode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else Color(0xFF0F1E36),
                                border = BorderStroke(1.dp, if (isSelected) JarvisCyan else JarvisCardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setWhatsAppProviderMode(mode) }
                            ) {
                                Text(
                                    text = mode.label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) JarvisCyan else JarvisTextSecondary,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // Automation Fine-Grained Switches
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Automation Controls",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("AI Auto-Reply", color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Generate concise replies using JARVIS AI", color = JarvisTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isAiReplyEnabled,
                            onCheckedChange = { viewModel.setWhatsAppAiAutoReplyEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Rule Automation", color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Execute keyword & outside-hours rules", color = JarvisTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isRulesEnabled,
                            onCheckedChange = { viewModel.setWhatsAppRuleAutomationEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Scheduled Dispatch", color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Execute scheduled messages via AlarmManager", color = JarvisTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isScheduledEnabled,
                            onCheckedChange = { viewModel.setWhatsAppScheduledEnabled(it) }
                        )
                    }

                    HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Policy & Opt-In Verification", color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Require consent before automated messaging", color = JarvisTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = optInRequired,
                            onCheckedChange = { viewModel.setWhatsAppOptInRequired(it) }
                        )
                    }
                }
            }
        }

        // Credentials & Secret Masking Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Meta Cloud API Credentials",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = { showEditCredentialsDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Edit Config", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    CredentialDisplayRow(label = "Phone Number ID", value = phoneId.ifBlank { "Not configured" })
                    CredentialDisplayRow(label = "Business Account ID (WABA)", value = wabaId.ifBlank { "Not configured" })
                    CredentialDisplayRow(
                        label = "Access Token",
                        value = WhatsAppSecureCredentialStore.maskToken(accessToken),
                        isSecured = true
                    )
                    CredentialDisplayRow(
                        label = "Verify Token",
                        value = WhatsAppSecureCredentialStore.maskToken(verifyToken),
                        isSecured = true
                    )
                    CredentialDisplayRow(
                        label = "Local Webhook Port",
                        value = "Port $webhookPort (Development Webhook Receiver)"
                    )
                }
            }
        }

        // Architecture & Meta Setup Notice Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2E)),
                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Meta Webhook Configuration Notice", fontWeight = FontWeight.Bold, color = JarvisCyan, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Development Mode: WhatsAppLocalServer listens on port $webhookPort for local test deliveries.\n" +
                               "• Production Meta Webhooks: Meta requires a public HTTPS URL (port 443). To receive live webhooks from Meta on this device, expose port $webhookPort via a public HTTPS tunnel (e.g. ngrok http $webhookPort or Cloudflare Tunnel) and enter that URL in your Meta App Dashboard > WhatsApp > Configuration > Callback URL.",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }

    if (showEditCredentialsDialog) {
        EditCredentialsDialog(
            initialPhoneId = phoneId,
            initialWabaId = wabaId,
            initialToken = accessToken,
            initialVerifyToken = verifyToken,
            initialPort = webhookPort.toString(),
            initialPublicUrl = webhookPublicUrl,
            onDismiss = { showEditCredentialsDialog = false },
            onSave = { pId, wId, tok, vTok, portStr, pubUrl ->
                val p = portStr.toIntOrNull() ?: 8088
                viewModel.updateWhatsAppCredentials(pId, wId, tok, vTok, p, pubUrl)
                showEditCredentialsDialog = false
                Toast.makeText(context, "Credentials secured in hardware encryption.", Toast.LENGTH_SHORT).show()
            },
            onClear = {
                viewModel.clearWhatsAppCredentials()
                showEditCredentialsDialog = false
                Toast.makeText(context, "Credentials cleared.", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun CredentialDisplayRow(label: String, value: String, isSecured: Boolean = false) {
    Column {
        Text(text = label, fontSize = 11.sp, color = JarvisTextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (value.contains("Not configured")) JarvisTextSecondary else JarvisTextPrimary,
                fontFamily = FontFamily.Monospace
            )
            if (isSecured) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Encrypted in Hardware Keystore",
                    tint = JarvisCyan,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.4f))
    }
}

@Composable
private fun EditCredentialsDialog(
    initialPhoneId: String,
    initialWabaId: String,
    initialToken: String,
    initialVerifyToken: String,
    initialPort: String,
    initialPublicUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit,
    onClear: () -> Unit
) {
    var pId by remember { mutableStateOf(initialPhoneId) }
    var wId by remember { mutableStateOf(initialWabaId) }
    var token by remember { mutableStateOf(initialToken) }
    var vTok by remember { mutableStateOf(initialVerifyToken) }
    var port by remember { mutableStateOf(initialPort) }
    var pubUrl by remember { mutableStateOf(initialPublicUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure WhatsApp Credentials", color = JarvisTextPrimary, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pId,
                    onValueChange = { pId = it },
                    label = { Text("Phone Number ID") },
                    colors = waTextFieldColors(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = wId,
                    onValueChange = { wId = it },
                    label = { Text("Business Account ID (WABA)") },
                    colors = waTextFieldColors(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Meta Access Token (EAAB...)") },
                    placeholder = { Text("Paste new permanent or temporary token") },
                    colors = waTextFieldColors(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = vTok,
                    onValueChange = { vTok = it },
                    label = { Text("Webhook Verify Token") },
                    colors = waTextFieldColors(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        colors = waTextFieldColors(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pubUrl,
                        onValueChange = { pubUrl = it },
                        label = { Text("Public URL") },
                        colors = waTextFieldColors(),
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(pId, wId, token, vTok, port, pubUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
            ) {
                Text("Save Encrypted", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text("Clear All", color = JarvisError)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = JarvisTextSecondary)
                }
            }
        },
        containerColor = JarvisCardBg
    )
}

@Composable
private fun MessagesTab(messages: List<com.example.whatsapp.data.WhatsAppMessageEntity>) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No messages logged yet. Incoming & outgoing messages will appear here.", color = JarvisTextSecondary, fontSize = 13.sp)
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(messages) { msg ->
            val isIncoming = msg.direction == "INCOMING"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isIncoming) JarvisCardBg else Color(0xFF0F2648)
                ),
                border = BorderStroke(
                    1.dp,
                    if (msg.isAiReply) JarvisCyan.copy(alpha = 0.5f) else JarvisCardBorder
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isIncoming) "Incoming (${msg.senderOrRecipientNumber})" else "To: ${msg.senderOrRecipientNumber}",
                                fontWeight = FontWeight.Bold,
                                color = if (isIncoming) JarvisCyan else Color(0xFF00E676),
                                fontSize = 13.sp
                            )
                            if (msg.isAiReply) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = JarvisCyan.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "AI Reply",
                                        color = JarvisCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                            color = JarvisTextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = msg.text, color = JarvisTextPrimary, fontSize = 13.sp)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Status: ${msg.status} • ID: ${msg.whatsappMessageId.take(16)}...",
                        color = JarvisTextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeTab(viewModel: JarvisViewModel, contacts: List<com.example.whatsapp.data.WhatsAppContactEntity>) {
    val context = LocalContext.current
    var recipientPhone by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendStatusText by remember { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
            border = BorderStroke(1.dp, JarvisCardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Send WhatsApp Message",
                    fontWeight = FontWeight.Bold,
                    color = JarvisTextPrimary,
                    fontSize = 15.sp
                )

                OutlinedTextField(
                    value = recipientPhone,
                    onValueChange = { recipientPhone = it },
                    label = { Text("Recipient Phone Number", color = JarvisCyan) },
                    placeholder = { Text("e.g. +919876543210 (include country code)", color = JarvisTextSecondary) },
                    singleLine = true,
                    colors = waTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (contacts.isNotEmpty()) {
                    Text("Select from contacts:", fontSize = 11.sp, color = JarvisTextSecondary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        contacts.take(3).forEach { contact ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0F1E36),
                                border = BorderStroke(1.dp, JarvisCardBorder),
                                modifier = Modifier.clickable { recipientPhone = contact.phoneNumber }
                            ) {
                                Text(
                                    text = contact.displayName.take(12),
                                    fontSize = 11.sp,
                                    color = JarvisCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message Body", color = JarvisCyan) },
                    placeholder = { Text("Type your message...", color = JarvisTextSecondary) },
                    minLines = 4,
                    colors = waTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (recipientPhone.isBlank() || messageText.isBlank()) {
                            Toast.makeText(context, "Recipient and message required", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSending = true
                        sendStatusText = null
                        viewModel.sendWhatsAppMessage(recipientPhone, messageText) { success, msg ->
                            isSending = false
                            sendStatusText = msg
                            if (success) {
                                messageText = ""
                            }
                        }
                    },
                    enabled = !isSending,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black)
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send via Meta Cloud API", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                if (sendStatusText != null) {
                    Text(text = sendStatusText!!, color = JarvisCyan, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(viewModel: JarvisViewModel, contacts: List<com.example.whatsapp.data.WhatsAppContactEntity>) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.phoneNumber.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search contacts by name or number...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = JarvisCyan) },
            singleLine = true,
            colors = waTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        )

        if (filteredContacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (searchQuery.isNotBlank()) "No contacts match '$searchQuery'" else "No contacts discovered yet.",
                    color = JarvisTextSecondary,
                    fontSize = 13.sp
                )
            }
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredContacts) { contact ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = contact.displayName, fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                                if (contact.optInVerified) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF00E676).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "Opt-In",
                                            color = Color(0xFF00E676),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(text = contact.phoneNumber, color = JarvisCyan, fontSize = 12.sp)
                            if (contact.lastMessageSnippet.isNotBlank()) {
                                Text(text = "Last: ${contact.lastMessageSnippet}", color = JarvisTextSecondary, fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (contact.isAutomationAllowed) "Automation Allowed" else "Blocked",
                                fontSize = 11.sp,
                                color = if (contact.isAutomationAllowed) Color(0xFF00E676) else JarvisError,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = contact.isAutomationAllowed,
                                onCheckedChange = { viewModel.toggleContactAutomation(contact, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesTab(viewModel: JarvisViewModel, rules: List<com.example.whatsapp.data.WhatsAppRuleEntity>) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var ruleType by remember { mutableStateOf(WhatsAppRuleType.KEYWORD_CONTAINS.name) }
    var keyword by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { showDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Create Automation Rule", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (rules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No custom rules configured. AI Auto-Reply will answer general queries.", color = JarvisTextSecondary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules) { rule ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                        border = BorderStroke(1.dp, JarvisCardBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = rule.name, fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleWhatsAppRule(rule, it) })
                                    IconButton(onClick = { viewModel.deleteWhatsAppRule(rule.id) }) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = JarvisError)
                                    }
                                }
                            }
                            Text(text = "Trigger: ${rule.ruleType}", color = JarvisCyan, fontSize = 12.sp)
                            if (rule.matchKeyword.isNotBlank()) {
                                Text(text = "Keyword/Pattern: '${rule.matchKeyword}'", color = JarvisTextSecondary, fontSize = 12.sp)
                            }
                            Text(text = "Reply: '${rule.predefinedReply}'", color = JarvisTextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("New WhatsApp Rule", color = JarvisTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule Name", color = JarvisCyan) },
                        colors = waTextFieldColors(),
                        singleLine = true
                    )

                    // Rule Type Selector Chips
                    Text("Trigger Type:", fontSize = 11.sp, color = JarvisTextSecondary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            WhatsAppRuleType.KEYWORD_CONTAINS.name to "Contains",
                            WhatsAppRuleType.KEYWORD_EXACT.name to "Exact",
                            WhatsAppRuleType.OUTSIDE_HOURS.name to "Away Hours"
                        ).forEach { (typeVal, label) ->
                            FilterChip(
                                selected = ruleType == typeVal,
                                onClick = { ruleType = typeVal },
                                label = { Text(label, fontSize = 10.sp) }
                            )
                        }
                    }

                    if (ruleType != WhatsAppRuleType.OUTSIDE_HOURS.name) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("Trigger Keyword", color = JarvisCyan) },
                            colors = waTextFieldColors(),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        label = { Text("Predefined Reply Text", color = JarvisCyan) },
                        colors = waTextFieldColors(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && reply.isNotBlank()) {
                            viewModel.createWhatsAppRule(name, ruleType, keyword, reply, "09:00", "18:00")
                            showDialog = false
                            name = ""
                            keyword = ""
                            reply = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                ) {
                    Text("Save Rule", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel", color = JarvisTextSecondary) }
            },
            containerColor = JarvisCardBg
        )
    }
}

@Composable
private fun ScheduledTab(viewModel: JarvisViewModel, scheduled: List<com.example.whatsapp.data.WhatsAppScheduledMessageEntity>) {
    var showDialog by remember { mutableStateOf(false) }
    var recipient by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var delayMinutes by remember { mutableStateOf("15") }
    var selectedRepeat by remember { mutableStateOf(WhatsAppRepeatInterval.ONCE.name) }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { showDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Schedule, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Schedule WhatsApp Message", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (scheduled.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No scheduled messages pending.", color = JarvisTextSecondary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scheduled) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                        border = BorderStroke(1.dp, JarvisCardBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "To: ${item.recipientNumber}", fontWeight = FontWeight.Bold, color = JarvisCyan, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (item.deliveryStatus == "SENT") Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFFFFD700).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = item.deliveryStatus,
                                            color = if (item.deliveryStatus == "SENT") Color(0xFF00E676) else Color(0xFFFFD700),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteScheduledWhatsAppMessage(item.id) }) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Cancel", tint = JarvisError)
                                }
                            }
                            Text(text = item.messageText, color = JarvisTextPrimary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Scheduled: ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.scheduledTimeMillis))} • Repeat: ${item.repeatInterval}",
                                color = JarvisTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Schedule WhatsApp Message", color = JarvisTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = recipient,
                        onValueChange = { recipient = it },
                        label = { Text("Recipient Phone Number", color = JarvisCyan) },
                        colors = waTextFieldColors(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Message Text", color = JarvisCyan) },
                        colors = waTextFieldColors(),
                        minLines = 3
                    )
                    OutlinedTextField(
                        value = delayMinutes,
                        onValueChange = { delayMinutes = it },
                        label = { Text("Trigger Delay (minutes from now)", color = JarvisCyan) },
                        colors = waTextFieldColors(),
                        singleLine = true
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(WhatsAppRepeatInterval.ONCE.name, WhatsAppRepeatInterval.DAILY.name, WhatsAppRepeatInterval.WEEKLY.name).forEach { r ->
                            FilterChip(
                                selected = selectedRepeat == r,
                                onClick = { selectedRepeat = r },
                                label = { Text(r, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = delayMinutes.toIntOrNull() ?: 15
                        val triggerTime = System.currentTimeMillis() + (mins * 60 * 1000L)
                        if (recipient.isNotBlank() && text.isNotBlank()) {
                            viewModel.scheduleWhatsAppMessage(recipient, recipient, text, triggerTime, selectedRepeat)
                            showDialog = false
                            recipient = ""
                            text = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                ) {
                    Text("Schedule", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel", color = JarvisTextSecondary) }
            },
            containerColor = JarvisCardBg
        )
    }
}

@Composable
private fun AuditLogsTab(auditLogs: List<com.example.whatsapp.data.WhatsAppAuditLogEntity>) {
    if (auditLogs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No activity log events recorded yet.", color = JarvisTextSecondary, fontSize = 12.sp)
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
        items(auditLogs) { log ->
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = log.actionType, fontWeight = FontWeight.Bold, color = JarvisCyan, fontSize = 12.sp)
                        Text(text = log.details, color = JarvisTextPrimary, fontSize = 11.sp)
                        if (log.targetNumber.isNotBlank()) {
                            Text(text = "Target: ${log.targetNumber}", color = JarvisTextSecondary, fontSize = 10.sp)
                        }
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)),
                        color = JarvisTextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun waTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = JarvisCardBorder,
    focusedTextColor = JarvisTextPrimary,
    unfocusedTextColor = JarvisTextPrimary
)
