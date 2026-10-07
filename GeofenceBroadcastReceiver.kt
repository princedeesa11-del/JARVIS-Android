package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.local.JarvisDatabase
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by Google Play Services Geofencing when entering,
 * exiting, or dwelling in a monitored geographic area.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e(TAG, "Geofencing error code: ${geofencingEvent.errorCode}, message: $errorMessage")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()

        val transitionStr = when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "Entered"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "Exited"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "Dwelling at"
            else -> "Triggered"
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = JarvisDatabase.getInstance(context)
                for (fence in triggeringGeofences) {
                    val reqId = fence.requestId
                    val record = db.geofenceDao().getGeofenceById(reqId)
                    val title = record?.name ?: reqId
                    val now = System.currentTimeMillis()
                    db.geofenceDao().recordTrigger(reqId, now)

                    val payload = record?.actionPayload?.ifBlank { null }
                        ?: "JARVIS Location Alert: $transitionStr $title"

                    postNotification(context, title, payload)
                    Log.i(TAG, "Geofence $reqId ($transitionStr) handled successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing geofence transition: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postNotification(context: Context, title: String, message: String) {
        val channelId = "jarvis_geofence_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JARVIS Geofence Triggers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for location geofences and automations"
            }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("JARVIS Geofence: $title")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
