package com.example.data.repository

import com.example.data.local.JarvisDatabase
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

class JarvisRepository(val database: JarvisDatabase) {
    // Memory
    val activeMemories: Flow<List<MemoryItem>> = database.memoryDao().getActiveMemories()
    val allMemories: Flow<List<MemoryItem>> = database.memoryDao().getAllMemories()
    val archivedMemories: Flow<List<MemoryItem>> = database.memoryDao().getArchivedMemories()

    fun searchMemories(query: String): Flow<List<MemoryItem>> = database.memoryDao().searchMemories(query)
    suspend fun getRecentMemories(limit: Int = 20): List<MemoryItem> = database.memoryDao().getRecentMemories(limit)

    suspend fun saveMemory(
        key: String,
        content: String,
        category: String = "general",
        memoryType: String = "long-term",
        persona: String = "GLOBAL",
        importance: Float = 0.5f,
        confidence: Float = 1.0f,
        tags: String = "",
        embeddingVector: String = ""
    ): Long {
        // RULE 9: Never store API keys, passwords, tokens or secrets
        val sanitizedContent = sanitizeSecretData(content)
        val sanitizedKey = sanitizeSecretData(key)

        return database.memoryDao().insertMemory(
            MemoryItem(
                key = sanitizedKey,
                content = sanitizedContent,
                category = category,
                memoryType = memoryType,
                persona = persona,
                isArchived = false,
                importance = importance,
                confidence = confidence,
                tags = tags,
                embeddingVector = embeddingVector,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getMemoryById(id: Long): MemoryItem? = database.memoryDao().getMemoryById(id)
    suspend fun updateMemory(memory: MemoryItem) {
        val sanitized = memory.copy(
            key = sanitizeSecretData(memory.key),
            content = sanitizeSecretData(memory.content),
            updatedAt = System.currentTimeMillis()
        )
        database.memoryDao().updateMemory(sanitized)
    }

    suspend fun archiveMemory(id: Long, archived: Boolean = true) {
        database.memoryDao().setArchived(id, archived)
    }

    suspend fun restoreMemory(id: Long) {
        database.memoryDao().setArchived(id, false)
    }

    suspend fun deleteMemory(id: Long) = database.memoryDao().deleteById(id)
    suspend fun deleteMemoryByKey(key: String) = database.memoryDao().deleteByKey(key)

    private fun sanitizeSecretData(text: String): String {
        return text
            .replace(Regex("(?i)(api[_-]?key|secret|password|bearer|token)\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-]{8,})['\"]?"), "$1: [REDACTED]")
            .replace(Regex("AIzaSy[A-Za-z0-9_\\-]{33}"), "[REDACTED_API_KEY]")
            .replace(Regex("sk-[A-Za-z0-9]{20,}"), "[REDACTED_SECRET_KEY]")
    }

    // Conversations
    fun getMessages(sessionId: String = "default_session"): Flow<List<ConversationMessage>> =
        database.conversationDao().getMessages(sessionId)
    suspend fun getRecentMessages(limit: Int = 20): List<ConversationMessage> =
        database.conversationDao().getRecentMessages("default_session", limit)
    suspend fun addMessage(
        role: String,
        content: String,
        toolCall: String? = null,
        toolResult: String? = null,
        persona: String = "JARVIS",
        source: String = "text",
        responseType: String = "INFORMATIONAL",
        executionState: String = "SUCCESS",
        requiredPermission: String? = null
    ): Long {
        return database.conversationDao().insertMessage(
            ConversationMessage(
                role = role,
                content = content,
                toolCallJson = toolCall,
                toolResultJson = toolResult,
                persona = persona,
                source = source,
                responseType = responseType,
                executionState = executionState,
                requiredPermission = requiredPermission
            )
        )
    }
    suspend fun clearChat(sessionId: String = "default_session") =
        database.conversationDao().clearSession(sessionId)

    // Reminders
    val allReminders: Flow<List<ReminderItem>> = database.reminderDao().getAllReminders()
    val activeReminders: Flow<List<ReminderItem>> = database.reminderDao().getActiveReminders()
    suspend fun createReminder(title: String, notes: String, triggerTimeMillis: Long): Long {
        return database.reminderDao().insertReminder(
            ReminderItem(title = title, notes = notes, triggerTimeMillis = triggerTimeMillis)
        )
    }
    suspend fun setReminderCompleted(id: Long, completed: Boolean) =
        database.reminderDao().setCompleted(id, completed)
    suspend fun deleteReminder(id: Long) =
        database.reminderDao().deleteById(id)

    // Tool executions
    val recentToolExecutions: Flow<List<ToolExecutionRecord>> = database.toolExecutionDao().getRecentExecutions()
    suspend fun logToolExecution(toolName: String, args: String, result: String, status: String, duration: Long = 0) {
        database.toolExecutionDao().insertRecord(
            ToolExecutionRecord(
                toolName = toolName,
                argumentsJson = args,
                resultJson = result,
                status = status,
                durationMillis = duration
            )
        )
    }

    // Web Projects
    val allProjects: Flow<List<WebProjectItem>> = database.webProjectDao().getAllProjects()
    suspend fun getAllProjectsList(): List<WebProjectItem> = database.webProjectDao().getAllProjectsList()
    suspend fun getProjectById(id: Long): WebProjectItem? = database.webProjectDao().getProjectById(id)
    suspend fun saveProject(project: WebProjectItem): Long = database.webProjectDao().insertProject(project)
    suspend fun updateProject(project: WebProjectItem) = database.webProjectDao().updateProject(project)
    suspend fun deleteProject(id: Long) = database.webProjectDao().deleteProjectById(id)

    // Automations
    val allAutomationRules: Flow<List<AutomationRule>> = database.automationDao().getAllRules()
    suspend fun getEnabledRules(): List<AutomationRule> = database.automationDao().getEnabledRules()
    suspend fun saveAutomationRule(rule: AutomationRule): Long = database.automationDao().insertRule(rule)
    suspend fun setAutomationEnabled(id: Long, enabled: Boolean) = database.automationDao().setEnabled(id, enabled)
    suspend fun markRuleTriggered(id: Long, timestamp: Long) = database.automationDao().recordRunSuccess(id, timestamp)
    suspend fun markRuleFailed(id: Long, timestamp: Long) = database.automationDao().recordRunFailure(id, timestamp)
    suspend fun deleteAutomationRule(id: Long) = database.automationDao().deleteRuleById(id)

    // Automation Execution History
    val recentAutomationExecutions: Flow<List<AutomationExecutionRecord>> = database.automationExecutionDao().getRecentHistory()
    fun getExecutionHistoryForAutomation(automationId: Long): Flow<List<AutomationExecutionRecord>> =
        database.automationExecutionDao().getHistoryForAutomation(automationId)
    suspend fun logAutomationExecution(record: AutomationExecutionRecord) =
        database.automationExecutionDao().insertExecution(record)
    suspend fun updateAutomationExecution(record: AutomationExecutionRecord) =
        database.automationExecutionDao().updateExecution(record)

    // Geofences
    val allGeofences: Flow<List<GeofenceItem>> = database.geofenceDao().getAllGeofences()
    suspend fun getEnabledGeofences(): List<GeofenceItem> = database.geofenceDao().getEnabledGeofences()
    suspend fun getGeofenceById(requestId: String): GeofenceItem? = database.geofenceDao().getGeofenceById(requestId)
    suspend fun saveGeofence(geofence: GeofenceItem) = database.geofenceDao().insertGeofence(geofence)
    suspend fun updateGeofence(geofence: GeofenceItem) = database.geofenceDao().updateGeofence(geofence)
    suspend fun recordGeofenceTrigger(requestId: String, timestamp: Long) = database.geofenceDao().recordTrigger(requestId, timestamp)
    suspend fun deleteGeofence(requestId: String) = database.geofenceDao().deleteGeofenceById(requestId)
}
