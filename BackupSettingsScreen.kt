package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    viewModel: JarvisViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val memories by viewModel.memories.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val automationRules by viewModel.automationRules.collectAsState()

    var showClearChatDialog by remember { mutableStateOf(false) }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text("Clear Chat History", color = JarvisTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to erase all current conversation messages? Memory nodes will remain intact.", color = JarvisTextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearChat()
                        showClearChatDialog = false
                        Toast.makeText(context, "Chat history cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear", color = JarvisAccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel", color = JarvisCyan)
                }
            },
            containerColor = Color(0xFF0C1F38)
        )
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
                        text = "Backup",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Storage Core: Operational", Toast.LENGTH_SHORT).show()
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
                    text = "STORAGE TELEMETRY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // STATS CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Neural Memory Records", color = JarvisTextPrimary, fontSize = 14.sp)
                            Text("${memories.size} nodes", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Conversation Transcripts", color = JarvisTextPrimary, fontSize = 14.sp)
                            Text("${messages.size} entries", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = JarvisCardBorder.copy(alpha = 0.5f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Automated Routines", color = JarvisTextPrimary, fontSize = 14.sp)
                            Text("${automationRules.size} rules", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "EXPORT ARCHIVES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // EXPORT ACTIONS
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val exportText = buildString {
                                    appendLine("=== MAYA NEURAL MEMORY EXPORT ===")
                                    appendLine("Timestamp: ${System.currentTimeMillis()}")
                                    appendLine("Total Entries: ${memories.size}")
                                    appendLine()
                                    memories.forEach { mem ->
                                        appendLine("• [${mem.category}] ${mem.content} (importance: ${mem.importance})")
                                    }
                                }
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, exportText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export Neural Memories"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF132A4A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Export", tint = JarvisCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Memories (.txt / share)", color = JarvisCyan, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val chatText = buildString {
                                    appendLine("=== MAYA CONVERSATION TRANSCRIPT ===")
                                    appendLine("Total Messages: ${messages.size}")
                                    appendLine()
                                    messages.forEach { msg ->
                                        appendLine("[${msg.role.uppercase()}]: ${msg.content}")
                                    }
                                }
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, chatText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export Chat History"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF132A4A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = JarvisCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Chat Transcripts", color = JarvisCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "DATA PURGE & MAINTENANCE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // PURGE CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Clear Transient Conversation Buffer", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                        Text("Resets the active conversational context window while keeping long-term memories preserved.", fontSize = 12.sp, color = JarvisTextSecondary)

                        Button(
                            onClick = { showClearChatDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisAccentRed.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear", tint = JarvisAccentRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clear Chat History", color = JarvisAccentRed, fontWeight = FontWeight.Bold)
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
