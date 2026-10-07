package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.MemoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE isArchived = 0 ORDER BY timestamp DESC")
    fun getActiveMemories(): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories WHERE isArchived = 1 ORDER BY timestamp DESC")
    fun getArchivedMemories(): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories WHERE category = :category AND isArchived = 0 ORDER BY timestamp DESC")
    fun getMemoriesByCategory(category: String): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories WHERE memoryType = :memoryType AND isArchived = 0 ORDER BY timestamp DESC")
    fun getMemoriesByType(memoryType: String): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories WHERE (`key` LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND isArchived = 0 ORDER BY timestamp DESC")
    fun searchMemories(query: String): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memories WHERE isArchived = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int = 20): List<MemoryItem>

    @Query("SELECT * FROM memories WHERE isArchived = 0 ORDER BY timestamp DESC")
    suspend fun getAllActiveMemoriesList(): List<MemoryItem>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryItem?

    @Query("SELECT * FROM memories WHERE `key` = :key AND isArchived = 0 LIMIT 1")
    suspend fun getMemoryByKey(key: String): MemoryItem?

    @Query("SELECT * FROM memories WHERE (`key` LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND isArchived = 0")
    suspend fun searchMemoriesList(query: String): List<MemoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Update
    suspend fun updateMemory(memory: MemoryItem)

    @Query("UPDATE memories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Delete
    suspend fun deleteMemory(memory: MemoryItem)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}
