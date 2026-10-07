package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * Execution states for persistent automation actions:
 * SCHEDULED, RUNNING, SUCCESS, FAILED, CANCELLED, PERMISSION_REQUIRED, USER_ACTION_REQUIRED.
 */
enum class AutomationExecutionState {
    SCHEDULED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    PERMISSION_REQUIRED,
    USER_ACTION_REQUIRED,
    BLOCKED_BY_ANDROID
}

/**
 * Complete Automation Rule entity conforming to MASTER REQUIREMENT 2 & 3:
 * id, name, enabled, trigger, schedule, action, arguments, createdAt, updatedAt,
 * nextRunAt, lastRunAt, executionState, retryCount, errorMessage.
 */
@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val trigger: String, // "TIME_DAILY", "WEEKLY", "BATTERY_LOW", "BATTERY_CHARGING", "USER_COMMAND", "GEOFENCE"
    val schedule: String = "", // e.g. "08:00", "MON 09:00", "INTERVAL_60", "RRULE:FREQ=DAILY"
    val action: String = "", // Tool name, e.g. "speak", "send_sms", "create_calendar_event", "media_control", etc.
    val arguments: String = "", // JSON or payload string for tool arguments
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val executionState: String = AutomationExecutionState.SCHEDULED.name,
    val retryCount: Int = 0,
    val errorMessage: String? = null,

    // Backward compatibility fields
    val conditions: String = "",
    val actions: String = "",
    val actionPayload: String = "",
    val lastRun: Long? = null,
    val runCount: Int = 0,
    val failureCount: Int = 0
) {
    @get:Ignore
    val title: String get() = name

    @get:Ignore
    val isEnabled: Boolean get() = enabled

    @get:Ignore
    val triggerType: String get() = trigger

    @get:Ignore
    val triggerValue: String get() = if (schedule.isNotBlank()) schedule else conditions

    @get:Ignore
    val actionType: String get() = if (action.isNotBlank()) action else actions

    @get:Ignore
    val actionData: String get() = if (arguments.isNotBlank()) arguments else actionPayload

    @get:Ignore
    val lastTriggeredAt: Long? get() = lastRunAt ?: lastRun
}
