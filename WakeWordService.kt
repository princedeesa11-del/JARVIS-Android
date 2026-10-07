package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.JarvisApp
import com.example.MainActivity
import com.example.voice.wakeword.WakeWordEngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service for continuous JARVIS voice assistant execution.
 * Operates in the background with FOREGROUND_SERVICE_TYPE_MICROPHONE,
 * ensuring seamless speech recognition, intent execution, and automatic TTS
 * even when the user switches apps or minimizes the app.
 */
class WakeWordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val assistant = (application as? JarvisApp)?.jarvisVoiceAssistant

        when (action) {
            ACTION_STOP -> {
                assistant?.stopAssistant()
                _engineStatus.value = WakeWordEngineStatus.STOPPED
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                assistant?.pauseAssistant()
                _engineStatus.value = WakeWordEngineStatus.PAUSED
                updateNotification("JARVIS Standby • Paused")
                return START_STICKY
            }
            ACTION_RESUME -> {
                assistant?.resumeAssistant()
                _engineStatus.value = WakeWordEngineStatus.LISTENING
                updateNotification("JARVIS Active • Listening for Directives")
                return START_STICKY
            }
        }

        // 1. Establish Foreground Service with microphone type
        Log.i("WAKE", "[WAKE] service_started: foreground microphone service initiated")
        val notification = buildForegroundNotification("JARVIS Active • Listening for Directives")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Start the core voice assistant pipeline
        _engineStatus.value = WakeWordEngineStatus.LISTENING
        assistant?.startAssistant(continuous = true)

        return START_STICKY
    }

    private fun updateNotification(contentText: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Pause / Resume toggle
        val isPaused = _engineStatus.value == WakeWordEngineStatus.PAUSED
        val toggleActionIntent = Intent(this, WakeWordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pendingToggle = PendingIntent.getService(
            this, 1, toggleActionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Stop service
        val stopActionIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopActionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Voice Assistant")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pendingToggle
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                pendingStop
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Assistant Sentinel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for JARVIS continuous voice assistant"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as? JarvisApp)?.jarvisVoiceAssistant?.stopAssistant()
        _engineStatus.value = WakeWordEngineStatus.STOPPED
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
    }

    companion object {
        private const val TAG = "WakeWordService"
        const val CHANNEL_ID = "jarvis_wakeword_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.example.action.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.example.action.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.example.action.RESUME_WAKE_WORD"

        private val _engineStatus = MutableStateFlow(WakeWordEngineStatus.UNINITIALIZED)
        val engineStatus: StateFlow<WakeWordEngineStatus> = _engineStatus.asStateFlow()

        @Volatile
        var activeServiceInstance: WakeWordService? = null
            private set

        fun isRunning(): Boolean = activeServiceInstance != null

        var onWakeWordTriggered: ((phrase: String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateServiceNotification(context: Context, text: String) {
            activeServiceInstance?.updateNotification(text)
        }
    }
}
