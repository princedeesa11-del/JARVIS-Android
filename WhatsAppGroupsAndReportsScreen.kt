package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppGroupsAndReportsScreen(
    onBack: () -> Unit,
    onOpenAutoReply: () -> Unit = {},
    onOpenCloudApi: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }

    var selectedFormat by remember {
        mutableStateOf(prefs.getString("pref_wa_report_format", "Executive Summary") ?: "Executive Summary")
    }
    var autoSummarizeGroups by remember {
        mutableStateOf(prefs.getBoolean("pref_wa_auto_summarize", true))
    }
    var newGroupName by remember { mutableStateOf("") }
    var groupsList by remember {
        val saved = prefs.getString("pref_wa_groups_list", "Core Dev Team,Executive Board,Family Group") ?: ""
        mutableStateOf(saved.split(",").filter { it.isNotBlank() }.toMutableList())
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
                        text = "WhatsApp Groups & Reports",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "WhatsApp Monitoring: Active", Toast.LENGTH_SHORT).show()
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "AUTO-REPLY SENTRY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // AUTO-REPLY LINK CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAutoReply() }
                        .testTag("open_autoreply_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
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
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Configure automatic replies, notification listener, away-mode & reply history",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open",
                            tint = JarvisCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // OFFICIAL WHATSAPP CLOUD API CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCloudApi() }
                        .testTag("open_cloud_api_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00E676).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676)),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = "WhatsApp Cloud API",
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "WhatsApp Cloud API (Official)",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Meta Graph API v20.0, Webhook receiver, AI auto-reply, contacts & scheduling",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            item {
                Text(
                    text = "MONITORED GROUPS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // GROUPS MANAGEMENT CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "WhatsApp Groups for Daily Synthesis",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 14.sp
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newGroupName,
                                onValueChange = { newGroupName = it },
                                label = { Text("Group Name", color = JarvisCyan) },
                                placeholder = { Text("e.g. Project Alpha", color = JarvisTextSecondary) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JarvisCyan,
                                    unfocusedBorderColor = JarvisCardBorder,
                                    focusedTextColor = JarvisTextPrimary,
                                    unfocusedTextColor = JarvisTextPrimary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newGroupName.isNotBlank()) {
                                        groupsList.add(newGroupName.trim())
                                        prefs.edit().putString("pref_wa_groups_list", groupsList.joinToString(",")).apply()
                                        newGroupName = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("Add", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Flow of chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            groupsList.forEach { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F223D), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(group, color = JarvisTextPrimary, fontSize = 13.sp)
                                    IconButton(
                                        onClick = {
                                            groupsList.remove(group)
                                            prefs.edit().putString("pref_wa_groups_list", groupsList.joinToString(",")).apply()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = JarvisAccentRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "REPORT FORMATS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // REPORT FORMATS CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("JARVIS Group Digest Format", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)

                        listOf(
                            "Executive Summary" to "High-level takeaways, key announcements, and decisions",
                            "Action Items & Tasks" to "Extracted deadlines, assignees, and urgent queries",
                            "Chronological Brief" to "Complete recap formatted as a concise timeline"
                        ).forEach { (format, description) ->
                            val isSelected = selectedFormat == format
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedFormat = format
                                        prefs.edit().putString("pref_wa_report_format", format).apply()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedFormat = format
                                        prefs.edit().putString("pref_wa_report_format", format).apply()
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = format,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                        fontSize = 13.sp
                                    )
                                    Text(description, fontSize = 11.sp, color = JarvisTextSecondary)
                                }
                            }
                        }

                        HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automated Evening Digest", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 13.sp)
                                Text("Generate digest automatically at 19:00 daily", fontSize = 11.sp, color = JarvisTextSecondary)
                            }
                            Switch(
                                checked = autoSummarizeGroups,
                                onCheckedChange = {
                                    autoSummarizeGroups = it
                                    prefs.edit().putBoolean("pref_wa_auto_summarize", it).apply()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = JarvisCyan
                                )
                            )
                        }

                        Button(
                            onClick = {
                                Toast.makeText(context, "Sample $selectedFormat report compiled for ${groupsList.size} groups", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF132A4A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = JarvisCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Preview Sample Report", color = JarvisCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
