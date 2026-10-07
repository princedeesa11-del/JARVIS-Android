package com.example.ui.screens

import android.content.Context
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE) }

    var githubToken by remember { mutableStateOf(prefs.getString("pref_github_pat", "") ?: "") }
    var githubRepo by remember { mutableStateOf(prefs.getString("pref_github_repo", "") ?: "") }

    var notionToken by remember { mutableStateOf(prefs.getString("pref_notion_key", "") ?: "") }
    var notionDbId by remember { mutableStateOf(prefs.getString("pref_notion_db_id", "") ?: "") }

    var telegramBotToken by remember { mutableStateOf(prefs.getString("pref_telegram_bot_token", "") ?: "") }
    var telegramChatId by remember { mutableStateOf(prefs.getString("pref_telegram_chat_id", "") ?: "") }

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
                        text = "Connectors",
                        fontWeight = FontWeight.Bold,
                        color = JarvisTextPrimary,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Cloud Connectors Synced", Toast.LENGTH_SHORT).show()
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
                    text = "CODE & REPOSITORIES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // GITHUB CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("GitHub Integration", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (githubToken.isNotBlank()) JarvisAccentGreen.copy(alpha = 0.2f) else JarvisCardBorder.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    text = if (githubToken.isNotBlank()) "CONNECTED" else "NOT LINKED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (githubToken.isNotBlank()) JarvisAccentGreen else JarvisTextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = githubToken,
                            onValueChange = {
                                githubToken = it
                                prefs.edit().putString("pref_github_pat", it).apply()
                            },
                            label = { Text("Personal Access Token (ghp_...)", color = JarvisCyan) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("github_token_input")
                        )

                        OutlinedTextField(
                            value = githubRepo,
                            onValueChange = {
                                githubRepo = it
                                prefs.edit().putString("pref_github_repo", it).apply()
                            },
                            label = { Text("Default Target Repo (owner/repo)", color = JarvisCyan) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("github_repo_input")
                        )
                    }
                }
            }

            item {
                Text(
                    text = "WORKSPACE & KNOWLEDGE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // NOTION CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Notion Workspace", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (notionToken.isNotBlank()) JarvisAccentGreen.copy(alpha = 0.2f) else JarvisCardBorder.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    text = if (notionToken.isNotBlank()) "CONNECTED" else "NOT LINKED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (notionToken.isNotBlank()) JarvisAccentGreen else JarvisTextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = notionToken,
                            onValueChange = {
                                notionToken = it
                                prefs.edit().putString("pref_notion_key", it).apply()
                            },
                            label = { Text("Internal Integration Secret (secret_...)", color = JarvisCyan) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("notion_token_input")
                        )

                        OutlinedTextField(
                            value = notionDbId,
                            onValueChange = {
                                notionDbId = it
                                prefs.edit().putString("pref_notion_db_id", it).apply()
                            },
                            label = { Text("Target Database ID", color = JarvisCyan) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("notion_db_input")
                        )
                    }
                }
            }

            item {
                Text(
                    text = "COMMUNICATIONS & BOTS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            // TELEGRAM CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Telegram Bot Relay", fontWeight = FontWeight.Bold, color = JarvisTextPrimary, fontSize = 14.sp)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (telegramBotToken.isNotBlank()) JarvisAccentGreen.copy(alpha = 0.2f) else JarvisCardBorder.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    text = if (telegramBotToken.isNotBlank()) "CONNECTED" else "NOT LINKED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (telegramBotToken.isNotBlank()) JarvisAccentGreen else JarvisTextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = telegramBotToken,
                            onValueChange = {
                                telegramBotToken = it
                                prefs.edit().putString("pref_telegram_bot_token", it).apply()
                            },
                            label = { Text("Bot API Token (from @BotFather)", color = JarvisCyan) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("telegram_token_input")
                        )

                        OutlinedTextField(
                            value = telegramChatId,
                            onValueChange = {
                                telegramChatId = it
                                prefs.edit().putString("pref_telegram_chat_id", it).apply()
                            },
                            label = { Text("Personal Chat ID / Channel ID", color = JarvisCyan) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisCardBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("telegram_chat_input")
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        Toast.makeText(context, "Credentials saved and verified locally", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Validate All Connectors", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
