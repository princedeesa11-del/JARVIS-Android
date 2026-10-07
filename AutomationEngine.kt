package com.example.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ai.ToolCallRequest
import com.example.data.local.entity.AutomationExecutionRecord
import com.example.data.local.entity.AutomationExecutionState
import com.example.data.local.entity.AutomationRule
import com.example.data.repository.JarvisRepository
import com.example.receiver.JarvisAlarmScheduler
import com.example.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Real Android Automation Engine conforming to MASTER REQUIREMENT 2 & 3:
 * Dispatches any JARVIS tool via ToolExecutor/ToolRegistry without duplicating logic.
 * Tracks explicit execution states: SCHEDULED, RUNNING, SUCCESS, FAILED, CANCELLED,
 * PERMISSION_REQUIRED, USER_ACTION_REQUIRED, BLOCKED_BY_ANDROID.
 * Persists all state transitions and execution history in Room.
 */
class AutomationEngine(
    private val context: Context,
    private val repository: JarvisRepository,
    private val toolExecutor: ToolExecutor
) {
    private val tag = "AutomationEngine"

    suspend fun executeRule(
        rule: AutomationRule,
        onNotificationRequest: ((String) -> Unit)? = null
    ): AutomationExecutionState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val executionId = "exec_${rule.id}_$now"
        Log.i(tag, "Executing automation rule '${rule.name}' (#${rule.id}). Action: '${rule.actionType}'")

        // 1. Mark state RUNNING in rules and record in execution history
        repository.database.automationDao().updateExecutionState(rule.id, AutomationExecutionState.RUNNING.name, now)
        val initialRecord = AutomationExecutionRecord(
            executionId = executionId,
            automationId = rule.id,
            startedAt = now,
            state = AutomationExecutionState.RUNNING.name
        )
        repository.logAutomationExecution(initialRecord)

        try {
            val state = dispatchAction(rule, onNotificationRequest)
            val completedAt = System.currentTimeMillis()
            var resultStr: String? = null
            var errorStr: String? = null

            when (state) {
                AutomationExecutionState.SUCCESS -> {
                    resultStr = "Execution succeeded"
                    val nextRun = JarvisAlarmScheduler.calculateNextTriggerMillis(rule, now)
                    repository.database.automationDao().recordRunSuccess(rule.id, now, nextRun)
                    // If recurring, reschedule alarm and set state to SCHEDULED for next time
                    if (nextRun != null && rule.enabled) {
                        repository.database.automationDao().updateExecutionState(rule.id, AutomationExecutionState.SCHEDULED.name, now)
                        JarvisAlarmScheduler.scheduleAutomationRule(context, rule)
                    }
                }
                AutomationExecutionState.PERMISSION_REQUIRED -> {
                    errorStr = "Runtime permission required to execute action '${rule.actionType}'"
                    repository.database.automationDao().recordRunFailure(
                        rule.id, now, AutomationExecutionState.PERMISSION_REQUIRED.name, errorStr
                    )
                    postNotification("Action requires permission", "Automation '${rule.name}' needs system permission to proceed.")
                }
                AutomationExecutionState.USER_ACTION_REQUIRED -> {
                    resultStr = "User action required to complete action '${rule.actionType}'"
                    repository.database.automationDao().recordRunFailure(
                        rule.id, now, AutomationExecutionState.USER_ACTION_REQUIRED.name, resultStr
                    )
                    postNotification("User action required", "Automation '${rule.name}' opened system dialog or settings for confirmation.")
                }
                AutomationExecutionState.BLOCKED_BY_ANDROID -> {
                    errorStr = "Action '${rule.actionType}' is blocked by Android security constraints"
                    repository.database.automationDao().recordRunFailure(
                        rule.id, now, AutomationExecutionState.BLOCKED_BY_ANDROID.name, errorStr
                    )
                    postNotification("Action restricted", "Automation '${rule.name}' could not execute directly due to Android security restrictions.")
                }
                else -> {
                    errorStr = "Underlying tool execution failed."
                    repository.database.automationDao().recordRunFailure(
                        rule.id, now, AutomationExecutionState.FAILED.name, errorStr
                    )
                }
            }

            repository.updateAutomationExecution(
                initialRecord.copy(
                    completedAt = completedAt,
                    state = state.name,
                    result = resultStr,
                    error = errorStr
                )
            )

            state
        } catch (e: Exception) {
            Log.e(tag, "Rule #${rule.id} failed with exception: ${e.message}", e)
            val completedAt = System.currentTimeMillis()
            val errorMsg = e.message ?: "Unknown error"
            repository.database.automationDao().recordRunFailure(
                rule.id, now, AutomationExecutionState.FAILED.name, errorMsg
            )
            repository.updateAutomationExecution(
                initialRecord.copy(
                    completedAt = completedAt,
                    state = AutomationExecutionState.FAILED.name,
                    error = errorMsg
                )
            )
            AutomationExecutionState.FAILED
        }
    }

    private suspend fun dispatchAction(
        rule: AutomationRule,
        onNotificationRequest: ((String) -> Unit)?
    ): AutomationExecutionState {
        val action = rule.actionType.trim().lowercase()
        val rawPayload = rule.actionData.trim()

        // 1. Direct system announcements
        if (action == "speak" || action == "speak_message") {
            val msg = rawPayload.ifBlank { "Scheduled routine triggered: ${rule.name}" }
            if (onNotificationRequest != null) {
                onNotificationRequest(msg)
            } else {
                postNotification("JARVIS Announcement", msg)
            }
            return AutomationExecutionState.SUCCESS
        }

        if (action == "notification" || action == "send_notification") {
            val msg = rawPayload.ifBlank { "Automation routine '${rule.name}' has been executed." }
            postNotification("JARVIS Routine", msg)
            return AutomationExecutionState.SUCCESS
        }

        // 2. Map actions to ToolExecutor ToolCallRequests
        val toolCall = buildToolCall(rule.id, action, rawPayload)
        val result = toolExecutor.execute(toolCall)

        return when {
            result.isSuccess -> {
                Log.i(tag, "Tool '${toolCall.name}' returned SUCCESS for rule #${rule.id}")
                AutomationExecutionState.SUCCESS
            }
            result.errorMessage?.contains("permission", ignoreCase = true) == true ||
            result.output.contains("PERMISSION_REQUIRED", ignoreCase = true) ||
            result.data?.toString()?.contains("PERMISSION_REQUIRED", ignoreCase = true) == true -> {
                AutomationExecutionState.PERMISSION_REQUIRED
            }
            result.errorMessage?.contains("USER_ACTION_REQUIRED", ignoreCase = true) == true ||
            result.output.contains("USER_ACTION_REQUIRED", ignoreCase = true) ||
            result.data?.toString()?.contains("USER_ACTION_REQUIRED", ignoreCase = true) == true -> {
                AutomationExecutionState.USER_ACTION_REQUIRED
            }
            result.errorMessage?.contains("BLOCKED_BY_ANDROID", ignoreCase = true) == true ||
            result.output.contains("BLOCKED_BY_ANDROID", ignoreCase = true) ||
            result.data?.toString()?.contains("BLOCKED_BY_ANDROID", ignoreCase = true) == true -> {
                AutomationExecutionState.BLOCKED_BY_ANDROID
            }
            else -> {
                Log.w(tag, "Tool '${toolCall.name}' failed for rule #${rule.id}: ${result.errorMessage}")
                AutomationExecutionState.FAILED
            }
        }
    }

    private fun buildToolCall(ruleId: Long, action: String, rawPayload: String): ToolCallRequest {
        val parsedArgs = parseArgs(rawPayload)

        return when (action) {
            "launch_app", "launch app" -> {
                val app = parsedArgs["appName"]?.toString() ?: rawPayload.ifBlank { "Settings" }
                ToolCallRequest("auto_$ruleId", "launch_app", mapOf("appName" to app))
            }
            "open_url", "open url" -> {
                val url = parsedArgs["url"]?.toString() ?: rawPayload.ifBlank { "https://google.com" }
                ToolCallRequest("auto_$ruleId", "open_url", mapOf("url" to url))
            }
            "web_research", "web research", "search_web", "search web" -> {
                val query = parsedArgs["query"]?.toString() ?: rawPayload.ifBlank { "News updates" }
                ToolCallRequest("auto_$ruleId", "search_web", mapOf("query" to query))
            }
            "send_sms", "send sms" -> {
                val recipients = parsedArgs["recipients"]?.toString() ?: parsedArgs["phoneNumber"]?.toString() ?: ""
                val message = parsedArgs["message"]?.toString() ?: rawPayload
                ToolCallRequest("auto_$ruleId", "send_sms", mapOf("recipients" to recipients, "message" to message))
            }
            "create_calendar_event", "create calendar event" -> {
                val title = parsedArgs["title"]?.toString() ?: rawPayload.ifBlank { "Scheduled Event" }
                val args = mutableMapOf<String, Any>("action" to "CREATE", "title" to title)
                parsedArgs["delayHours"]?.let { args["delayHours"] = it }
                parsedArgs["durationHours"]?.let { args["durationHours"] = it }
                parsedArgs["rrule"]?.let { args["rrule"] = it }
                parsedArgs["attendees"]?.let { args["attendees"] = it }
                parsedArgs["reminders"]?.let { args["reminders"] = it }
                ToolCallRequest("auto_$ruleId", "calendar_manage", args)
            }
            "update_calendar_event", "update calendar event" -> {
                val args = mutableMapOf<String, Any>("action" to "UPDATE")
                args.putAll(parsedArgs)
                ToolCallRequest("auto_$ruleId", "calendar_manage", args)
            }
            "delete_calendar_event", "delete calendar event" -> {
                val args = mutableMapOf<String, Any>("action" to "DELETE")
                args.putAll(parsedArgs)
                ToolCallRequest("auto_$ruleId", "calendar_manage", args)
            }
            "media_play", "media play" -> {
                ToolCallRequest("auto_$ruleId", "media_control", mapOf("action" to "PLAY"))
            }
            "media_pause", "media pause" -> {
                ToolCallRequest("auto_$ruleId", "media_control", mapOf("action" to "PAUSE"))
            }
            "media_next", "media next" -> {
                ToolCallRequest("auto_$ruleId", "media_control", mapOf("action" to "NEXT"))
            }
            "media_previous", "media previous" -> {
                ToolCallRequest("auto_$ruleId", "media_control", mapOf("action" to "PREVIOUS"))
            }
            "volume_control", "adjust_volume" -> {
                val dir = parsedArgs["direction"]?.toString() ?: if (rawPayload.contains("down", ignoreCase = true)) "DOWN" else "UP"
                ToolCallRequest("auto_$ruleId", "adjust_volume", mapOf("direction" to dir))
            }
            "flashlight", "control_flashlight" -> {
                val enabled = parsedArgs["enabled"]?.toString()?.toBoolean() ?: !rawPayload.contains("off", ignoreCase = true)
                ToolCallRequest("auto_$ruleId", "control_flashlight", mapOf("enabled" to enabled))
            }
            "bluetooth_operation", "bluetooth", "manage_bluetooth" -> {
                val op = parsedArgs["operation"]?.toString() ?: rawPayload.ifBlank { "STATUS" }
                ToolCallRequest("auto_$ruleId", "manage_bluetooth", mapOf("operation" to op))
            }
            "wifi_operation", "wifi", "manage_wifi" -> {
                val op = parsedArgs["operation"]?.toString() ?: rawPayload.ifBlank { "STATUS" }
                ToolCallRequest("auto_$ruleId", "manage_wifi", mapOf("operation" to op))
            }
            "location_action", "get_location" -> {
                ToolCallRequest("auto_$ruleId", "get_location", parsedArgs)
            }
            "geofence_action", "manage_geofence" -> {
                ToolCallRequest("auto_$ruleId", "manage_geofence", parsedArgs)
            }
            "memory_operation", "write_memory" -> {
                val key = parsedArgs["key"]?.toString() ?: "automation_memory_$ruleId"
                val content = parsedArgs["content"]?.toString() ?: rawPayload
                ToolCallRequest("auto_$ruleId", "write_memory", mapOf("key" to key, "content" to content))
            }
            "call_operation", "dial_operation", "dial_phone_number" -> {
                val num = parsedArgs["phoneNumber"]?.toString() ?: rawPayload
                ToolCallRequest("auto_$ruleId", "dial_phone_number", mapOf("phoneNumber" to num))
            }
            // If action matches an existing tool directly:
            else -> {
                val toolName = if (action.startsWith("tool_")) action.removePrefix("tool_") else action
                ToolCallRequest("auto_$ruleId", toolName, if (parsedArgs.isNotEmpty()) parsedArgs else mapOf("payload" to rawPayload))
            }
        }
    }

    private fun parseArgs(raw: String): Map<String, Any> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.get(k)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun postNotification(title: String, message: String) {
        val channelId = "jarvis_automation_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JARVIS Automation Rules",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Automation routine results and alerts"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    /**
     * Periodic trigger checker for conditions (battery, daily, intervals).
     */
    suspend fun checkAndExecuteTriggers(
        currentBatteryPct: Int,
        isCharging: Boolean,
        onNotificationRequest: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val rules = repository.getEnabledRules()
        val now = System.currentTimeMillis()

        for (rule in rules) {
            var shouldTrigger = false
            when (rule.triggerType.uppercase()) {
                "BATTERY_LOW" -> {
                    val threshold = rule.triggerValue.toIntOrNull() ?: 20
                    if (currentBatteryPct <= threshold && !isCharging) {
                        shouldTrigger = true
                    }
                }
                "BATTERY_CHARGING" -> {
                    if (isCharging) {
                        shouldTrigger = true
                    }
                }
            }

            if (shouldTrigger) {
                // Debounce within 15 minutes for stateful sensor triggers
                if (rule.lastRunAt != null && (now - rule.lastRunAt) < 15 * 60 * 1000) {
                    continue
                }
                executeRule(rule, onNotificationRequest)
            }
        }
    }
}
