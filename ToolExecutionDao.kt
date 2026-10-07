package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.ToolExecutionRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolExecutionDao {
    @Query("SELECT * FROM tool_executions ORDER BY timestamp DESC LIMIT 50")
    fun getRecentExecutions(): Flow<List<ToolExecutionRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: ToolExecutionRecord): Long

    @Query("DELETE FROM tool_executions")
    suspend fun clearHistory()
}
