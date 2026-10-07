package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent geofence entity supporting location-based triggers and automations.
 */
@Entity(tableName = "geofences")
data class GeofenceItem(
    @PrimaryKey val requestId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
    val transitionTypes: Int = 1, // 1 = ENTER, 2 = EXIT, 4 = DWELL
    val actionPayload: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null
)
