package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.GeofenceItem
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceDao {
    @Query("SELECT * FROM geofences ORDER BY createdAt DESC")
    fun getAllGeofences(): Flow<List<GeofenceItem>>

    @Query("SELECT * FROM geofences WHERE isEnabled = 1")
    suspend fun getEnabledGeofences(): List<GeofenceItem>

    @Query("SELECT * FROM geofences WHERE requestId = :requestId LIMIT 1")
    suspend fun getGeofenceById(requestId: String): GeofenceItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofence(geofence: GeofenceItem)

    @Update
    suspend fun updateGeofence(geofence: GeofenceItem)

    @Query("UPDATE geofences SET lastTriggeredAt = :timestamp WHERE requestId = :requestId")
    suspend fun recordTrigger(requestId: String, timestamp: Long)

    @Query("DELETE FROM geofences WHERE requestId = :requestId")
    suspend fun deleteGeofenceById(requestId: String)

    @Query("DELETE FROM geofences")
    suspend fun clearAll()
}
