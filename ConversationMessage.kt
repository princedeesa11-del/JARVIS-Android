package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_messages")
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "default_session",
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val toolCallJson: String? = null,
    val toolResultJson: String? = null,
    val persona: String = "JARVIS",
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "text", // "text", "voice"
    val responseType: String = "INFORMATIONAL", // "INFORMATIONAL", "TOOL_ACTION", "CONFIRMATION_REQUIRED", "PERMISSION_REQUIRED", "USER_ACTION_REQUIRED", "ERROR"
    val executionState: String = "SUCCESS", // "SUCCESS", "FAILED", "RUNNING", "PERMISSION_REQUIRED", "USER_ACTION_REQUIRED"
    val requiredPermission: String? = null
)
