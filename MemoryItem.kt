package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent memory entity supporting full lifecycle:
 * CREATE, READ, UPDATE, DELETE, SEARCH, and ARCHIVE/RESTORE.
 * Categories: "preference", "fact", "task", "instruction", "conversation", "persona"
 * MemoryTypes: "short-term", "long-term", "preference", "task", "conversation", "persona"
 */
@Entity(tableName = "memories")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val content: String,
    val category: String = "general",
    val memoryType: String = "long-term",
    val persona: String = "GLOBAL",
    val isArchived: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "user_interaction",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val importance: Float = 0.5f,
    val confidence: Float = 1.0f,
    val tags: String = "",
    val embeddingVector: String = "",
    val metadata: String = "{}"
)
