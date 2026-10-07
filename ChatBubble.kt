package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ConversationMessage
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatBubble(
    message: ConversationMessage,
    onSpeak: (String) -> Unit = {},
    onRetry: (ConversationMessage) -> Unit = {},
    onRequestPermission: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val formattedTime = try {
        timeFormatter.format(Date(message.timestamp))
    } catch (_: Exception) {
        ""
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    val bubbleBg = if (isUser) Color(0xFF14243B) else JarvisCardBg
    val borderColor = when {
        message.responseType == "ERROR" -> JarvisAccentRed.copy(alpha = 0.6f)
        message.responseType == "PERMISSION_REQUIRED" -> Color(0xFFFFB74D)
        isUser -> Color(0xFF234470)
        else -> JarvisCardBorder
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Directive Header: Persona / Source + Timestamp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            if (isUser) {
                Icon(
                    imageVector = if (message.source == "voice") Icons.Default.PlayArrow else Icons.Default.Edit,
                    contentDescription = null,
                    tint = if (message.source == "voice") JarvisGold else JarvisCyan,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (message.source == "voice") "VOICE DIRECTIVE" else "TEXT DIRECTIVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisTextSecondary,
                    letterSpacing = 1.sp
                )
            } else {
                Text(
                    text = "${message.persona} CORE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan,
                    letterSpacing = 1.sp
                )

                // Response Type Pill
                Spacer(modifier = Modifier.width(6.dp))
                val (badgeColor, badgeText) = when (message.responseType) {
                    "TOOL_ACTION" -> Color(0xFF00E5FF) to "ACTION EXECUTED"
                    "PERMISSION_REQUIRED" -> Color(0xFFFFB74D) to "PERMISSION REQUIRED"
                    "USER_ACTION_REQUIRED" -> JarvisGold to "ACTION REQUIRED"
                    "CONFIRMATION_REQUIRED" -> JarvisAccentPurple to "INPUT NEEDED"
                    "ERROR" -> JarvisAccentRed to "SYSTEM ALERT"
                    else -> JarvisCyanDim to "INFORMATIONAL"
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (formattedTime.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formattedTime,
                    fontSize = 9.sp,
                    color = JarvisTextSecondary.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Message Box
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .border(1.dp, borderColor, bubbleShape)
                .padding(14.dp)
        ) {
            Column {
                // Tool Call Badge if present
                if (!message.toolCallJson.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFF07111F),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Tool Call",
                                tint = JarvisCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TOOL: ${message.toolCallJson}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = JarvisCyan,
                                maxLines = 2
                            )
                        }
                    }
                }

                // Text Content
                SelectionContainerOrText(message.content)

                // Tool Result Badge if present
                if (!message.toolResultJson.isNullOrBlank()) {
                    val isResultError = message.executionState == "FAILED" || message.responseType == "ERROR"
                    Surface(
                        color = if (isResultError) Color(0xFF240E10) else Color(0xFF061814),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isResultError) JarvisAccentRed.copy(alpha = 0.5f) else JarvisAccentGreen.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = if (isResultError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = "Tool Result",
                                tint = if (isResultError) JarvisAccentRed else JarvisAccentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = message.toolResultJson,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isResultError) Color(0xFFFF8A80) else Color(0xFFB9F6CA),
                                maxLines = 4
                            )
                        }
                    }
                }

                // Contextual Action Buttons based on responseType
                when (message.responseType) {
                    "PERMISSION_REQUIRED" -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onRequestPermission(message.requiredPermission ?: "android.permission.READ_CALL_LOG")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFB74D),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Grant Permission",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    "USER_ACTION_REQUIRED" -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = JarvisGold
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisGold.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Open Settings",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    "ERROR" -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onRetry(message) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = JarvisAccentRed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAccentRed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Retry Directive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Action Bar (for assistant messages)
                if (!isUser && message.content.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { onSpeak(message.content) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Speak aloud",
                                tint = JarvisCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy text",
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerOrText(text: String) {
    Text(
        text = text,
        color = JarvisTextPrimary,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
}

