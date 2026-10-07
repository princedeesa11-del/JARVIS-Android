package com.example.ai.memory

/**
 * Clean memory categories conforming to MASTER PROMPT 2/5 Section 3:
 * USER_PREFERENCE
 * PERSONAL_CONTEXT
 * ROUTINE
 * IMPORTANT_FACT
 * PROJECT_CONTEXT
 * TASK_CONTEXT
 * DEVICE_PREFERENCE
 * COMMUNICATION_PREFERENCE
 * TEMPORARY_CONTEXT
 */
enum class MemoryCategory(
    val code: String,
    val displayName: String,
    val defaultImportance: Float
) {
    USER_PREFERENCE("USER_PREFERENCE", "User Preference", 0.85f),
    PERSONAL_CONTEXT("PERSONAL_CONTEXT", "Personal Context", 0.70f),
    ROUTINE("ROUTINE", "Routine", 0.75f),
    IMPORTANT_FACT("IMPORTANT_FACT", "Important Fact", 0.90f),
    PROJECT_CONTEXT("PROJECT_CONTEXT", "Project Context", 0.75f),
    TASK_CONTEXT("TASK_CONTEXT", "Task Context", 0.60f),
    DEVICE_PREFERENCE("DEVICE_PREFERENCE", "Device Preference", 0.70f),
    COMMUNICATION_PREFERENCE("COMMUNICATION_PREFERENCE", "Communication Preference", 0.80f),
    TEMPORARY_CONTEXT("TEMPORARY_CONTEXT", "Temporary Context", 0.35f);

    companion object {
        fun fromString(str: String?): MemoryCategory {
            if (str.isNullOrBlank()) return PERSONAL_CONTEXT
            val upper = str.trim().uppercase()
            return values().firstOrNull { it.name == upper || it.code == upper }
                ?: when {
                    upper.contains("PREF") && (upper.contains("DEV") || upper.contains("VOLUME") || upper.contains("SCREEN") || upper.contains("THEME") || upper.contains("DARK") || upper.contains("LIGHT")) -> DEVICE_PREFERENCE
                    upper.contains("PREF") && (upper.contains("COMM") || upper.contains("CALL") || upper.contains("SMS") || upper.contains("MESSAGE") || upper.contains("CHAT")) -> COMMUNICATION_PREFERENCE
                    upper.contains("PREF") -> USER_PREFERENCE
                    upper.contains("ROUTINE") || upper.contains("HABIT") || upper.contains("SCHEDULE") || upper.contains("EVERY") -> ROUTINE
                    upper.contains("FACT") || upper.contains("BIRTHDAY") || upper.contains("IMPORTANT") -> IMPORTANT_FACT
                    upper.contains("PROJ") || upper.contains("REPO") || upper.contains("CODE") || upper.contains("APP") -> PROJECT_CONTEXT
                    upper.contains("TASK") || upper.contains("TODO") -> TASK_CONTEXT
                    upper.contains("DEV") -> DEVICE_PREFERENCE
                    upper.contains("COMM") -> COMMUNICATION_PREFERENCE
                    upper.contains("TEMP") || upper.contains("SESSION") -> TEMPORARY_CONTEXT
                    else -> PERSONAL_CONTEXT
                }
        }

        fun inferCategory(key: String, content: String): MemoryCategory {
            val combined = "$key $content".lowercase()
            return when {
                combined.contains("routine") || combined.contains("every morning") || combined.contains("every evening") || combined.contains("at night") || combined.contains("daily") -> ROUTINE
                combined.contains("dark mode") || combined.contains("light mode") || combined.contains("theme") || combined.contains("volume") || combined.contains("display") || combined.contains("bluetooth") || combined.contains("wifi") -> DEVICE_PREFERENCE
                combined.contains("prefer sms") || combined.contains("prefer whatsapp") || combined.contains("don't call") || combined.contains("text me") || combined.contains("message format") -> COMMUNICATION_PREFERENCE
                combined.contains("project") || combined.contains("repository") || combined.contains("codebase") || combined.contains("jarvis development") -> PROJECT_CONTEXT
                combined.contains("task") || combined.contains("deadline") || combined.contains("assigned to") -> TASK_CONTEXT
                combined.contains("prefer") || combined.contains("likes") || combined.contains("dislikes") || combined.contains("favorite") || combined.contains("always want") -> USER_PREFERENCE
                combined.contains("important") || combined.contains("remember this fact") || combined.contains("never forget") || combined.contains("born in") || combined.contains("family") || combined.contains("wife") || combined.contains("child") || combined.contains("birthday") -> IMPORTANT_FACT
                combined.contains("temporary") || combined.contains("for now") || combined.contains("today only") -> TEMPORARY_CONTEXT
                else -> PERSONAL_CONTEXT
            }
        }
    }
}
