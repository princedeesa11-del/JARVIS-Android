package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.AutomationRule
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<AutomationRule>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<AutomationRule>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun getRuleById(id: Long): AutomationRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AutomationRule): Long

    @Update
    suspend fun updateRule(rule: AutomationRule)

    @Query("UPDATE automation_rules SET enabled = :enabled, updatedAt = :timestamp WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE automation_rules SET executionState = :state, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateExecutionState(id: Long, state: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE automation_rules SET 
            lastRun = :timestamp, 
            lastRunAt = :timestamp, 
            runCount = runCount + 1, 
            executionState = 'SUCCESS',
            nextRunAt = :nextRun,
            errorMessage = NULL,
            updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun recordRunSuccess(id: Long, timestamp: Long, nextRun: Long? = null)

    @Query("""
        UPDATE automation_rules SET 
            lastRun = :timestamp, 
            lastRunAt = :timestamp, 
            failureCount = failureCount + 1, 
            retryCount = retryCount + 1,
            executionState = :state,
            errorMessage = :errorMsg,
            updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun recordRunFailure(id: Long, timestamp: Long, state: String = "FAILED", errorMsg: String? = null)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)
}
