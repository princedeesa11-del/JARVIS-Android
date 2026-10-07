package com.example.tools

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.local.entity.GeofenceItem
import com.example.data.repository.JarvisRepository
import com.example.receiver.GeofenceBroadcastReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

/**
 * Real Location & Geofencing helper using FusedLocationProviderClient and GeofencingClient.
 * Strictly respects Android permissions and battery-efficient location tracking.
 */
class LocationGeofenceHelper(
    private val context: Context,
    private val repository: JarvisRepository
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    suspend fun getCurrentLocation(): Map<String, Any> {
        if (!hasLocationPermission()) {
            return mapOf(
                "success" to false,
                "status" to "PERMISSION_REQUIRED",
                "message" to "Location permission (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) is not granted."
            )
        }

        if (!isLocationEnabled()) {
            return mapOf(
                "success" to false,
                "status" to "LOCATION_DISABLED",
                "message" to "Device location/GPS is currently turned off in Android settings."
            )
        }

        return try {
            val cts = CancellationTokenSource()
            val location: Location? = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cts.token
            ).await() ?: fusedLocationClient.lastLocation.await()

            if (location != null) {
                mapOf(
                    "success" to true,
                    "status" to "LOCATION_ACQUIRED",
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "accuracyMeters" to location.accuracy,
                    "altitudeMeters" to (if (location.hasAltitude()) location.altitude else 0.0),
                    "provider" to (location.provider ?: "fused"),
                    "timestamp" to location.time
                )
            } else {
                mapOf(
                    "success" to false,
                    "status" to "LOCATION_UNAVAILABLE",
                    "message" to "Location signal is currently unavailable. Ensure the device has a clear signal."
                )
            }
        } catch (e: SecurityException) {
            mapOf("success" to false, "status" to "PERMISSION_DENIED", "message" to (e.message ?: "Security exception"))
        } catch (e: Exception) {
            mapOf("success" to false, "status" to "ERROR", "message" to (e.message ?: "Failed to acquire location"))
        }
    }

    suspend fun createGeofence(
        requestId: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 150f,
        actionPayload: String = ""
    ): Pair<Boolean, String> {
        if (!hasLocationPermission()) {
            return false to "Location permission required to register geofence."
        }

        // Background location check on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBg) {
                // We still save and register, but alert that background updates may be limited
            }
        }

        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(30_000) // 30 sec dwell
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = getGeofencePendingIntent()

        return try {
            geofencingClient.addGeofences(geofencingRequest, pendingIntent).await()

            // Persist in Room database
            val entity = GeofenceItem(
                requestId = requestId,
                name = name,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                transitionTypes = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL,
                actionPayload = actionPayload,
                isEnabled = true
            )
            repository.saveGeofence(entity)
            true to "Geofence '$name' ($requestId) registered at ($latitude, $longitude) with radius ${radiusMeters}m."
        } catch (e: SecurityException) {
            false to "Geofence permission denied: ${e.message}"
        } catch (e: Exception) {
            false to "Failed to register geofence: ${e.message}"
        }
    }

    suspend fun deleteGeofence(requestId: String): Pair<Boolean, String> {
        return try {
            geofencingClient.removeGeofences(listOf(requestId)).await()
            repository.deleteGeofence(requestId)
            true to "Geofence '$requestId' deleted successfully."
        } catch (e: Exception) {
            repository.deleteGeofence(requestId)
            true to "Geofence '$requestId' removed from local storage."
        }
    }

    suspend fun listGeofences(): List<GeofenceItem> {
        return repository.getEnabledGeofences()
    }

    private fun getGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
