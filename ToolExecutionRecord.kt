package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tool_executions")
data class ToolExecutionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String,
    val status: String, // "SUCCESS", "FAILED", "CONFIRMATION_REQUIRED", "DENIED"
    val durationMillis: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
