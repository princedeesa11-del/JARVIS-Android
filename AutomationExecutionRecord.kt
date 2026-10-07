package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent execution log for individual automation rule trigger runs.
 * Satisfies MASTER REQUIREMENT 3:
 * Persist: executionId, automationId, startedAt, completedAt, state, result, error, retryCount.
 */
@Entity(tableName = "automation_execution_history")
data class AutomationExecutionRecord(
    @PrimaryKey val executionId: String,
    val automationId: Long,
    val startedAt: Long,
    val completedAt: Long? = null,
    val state: String, // AutomationExecutionState name
    val result: String? = null,
    val error: String? = null,
    val retryCount: Int = 0
)
