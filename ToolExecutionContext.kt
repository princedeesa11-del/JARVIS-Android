package com.example.tools

import android.content.Context
import com.example.data.repository.JarvisRepository

data class ToolExecutionContext(
    val context: Context,
    val repository: JarvisRepository,
    val isAutomated: Boolean = false,
    val activePersonaName: String = "JARVIS"
)
