package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.AutomationExecutionRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(record: AutomationExecutionRecord)

    @Update
    suspend fun updateExecution(record: AutomationExecutionRecord)

    @Query("SELECT * FROM automation_execution_history WHERE automationId = :automationId ORDER BY startedAt DESC")
    fun getHistoryForAutomation(automationId: Long): Flow<List<AutomationExecutionRecord>>

    @Query("SELECT * FROM automation_execution_history ORDER BY startedAt DESC LIMIT :limit")
    fun getRecentHistory(limit: Int = 50): Flow<List<AutomationExecutionRecord>>

    @Query("SELECT * FROM automation_execution_history WHERE executionId = :executionId")
    suspend fun getExecutionById(executionId: String): AutomationExecutionRecord?

    @Query("SELECT * FROM automation_execution_history WHERE automationId = :automationId ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestExecution(automationId: Long): AutomationExecutionRecord?
}
