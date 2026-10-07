package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.WebProjectItem
import kotlinx.coroutines.flow.Flow

@Dao
interface WebProjectDao {
    @Query("SELECT * FROM web_projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<WebProjectItem>>

    @Query("SELECT * FROM web_projects ORDER BY updatedAt DESC")
    suspend fun getAllProjectsList(): List<WebProjectItem>

    @Query("SELECT * FROM web_projects WHERE id = :id")
    suspend fun getProjectById(id: Long): WebProjectItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: WebProjectItem): Long

    @Update
    suspend fun updateProject(project: WebProjectItem)

    @Query("DELETE FROM web_projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)
}
