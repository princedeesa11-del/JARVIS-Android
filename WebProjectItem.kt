package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "web_projects")
data class WebProjectItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val projectType: String = "HTML_CSS_JS", // "HTML_CSS_JS", "REACT_SPA"
    val projectPath: String, // directory path on disk
    val filesCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
