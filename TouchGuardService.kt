package com.example.touchguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * Foreground Service for JARVIS Touch Guard Security.
 * Continuously monitors:
 * 1. Screen wake / unlock attempts.
 * 2. Device motion / phone pick-up via accelerometer.
 * 3. Power charger disconnect events.
 *
 * Implements arming delay countdown (to allow user to set down phone and walk away without false alarms),
 * dynamic baseline motion calibration, front-camera evidence snapshotting, vocal TTS warnings,
 * emergency sirens, high-priority notifications, and persistent event logging.
 */
class TouchGuardService : Service(), SensorEventListener {

    private val tag = "TouchGuardService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: TouchGuardPreferences
    private var alarmPlayer: TouchGuardAlarmPlayer? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Motion detection baseline calibration
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var isCalibrated = false
    private var sampleCount = 0
    private var lastTriggerTimeMs = 0L

    // Arming countdown timer
    private var armingRunnable: Runnable? = null
    private var isCurrentlyArmed = false
    private var countdownRemainingSeconds = 0

    // Broadcast receiver for Screen and Charger events
    private var hardwareBroadcastReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = TouchGuardPreferences.getInstance(this)
        alarmPlayer = TouchGuardAlarmPlayer(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchGuard:PartialWakeLock")?.apply {
            setReferenceCounted(false)
        }

        createNotificationChannels()
        registerHardwareReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISARM -> {
                disarmTouchGuard(manual = true)
            }
            ACTION_ARM_NOW -> {
                startArmingCountdown()
            }
            ACTION_SILENCE_ALARM -> {
                silenceAlarm()
            }
            else -> {
                // If service was started with armed = true in prefs, arm it directly
                if (prefs.isArmed.value && !isCurrentlyArmed) {
                    startArmingCountdown()
                }
            }
        }

        startForeground(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification())
        return START_STICKY
    }

    private fun registerHardwareReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }

        hardwareBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, receivedIntent: Intent?) {
                if (!isCurrentlyArmed) return

                when (receivedIntent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        if (prefs.monitorScreenUnlock.value) {
                            triggerSecurityBreach(
                                eventType = TouchGuardEventType.SCREEN_WAKE,
                                title = "Screen Woken / Unlocked",
                                description = "Someone woke the screen or attempted unlock while JARVIS Touch Guard was armed."
                            )
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (prefs.monitorCharger.value) {
                            triggerSecurityBreach(
                                eventType = TouchGuardEventType.CHARGER_DISCONNECTED,
                                title = "Charger Disconnected",
                                description = "The charging cable was unplugged without authorization."
                            )
                        }
                    }
                }
            }
        }

        registerReceiver(hardwareBroadcastReceiver, filter)
    }

    fun startArmingCountdown() {
        silenceAlarm()
        val delaySec = prefs.armDelaySeconds.value
        if (delaySec <= 0) {
            activateArmState()
            return
        }

        armingRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRemainingSeconds = delaySec
        _serviceState.value = TouchGuardServiceState.COUNTDOWN
        _countdownSec.value = countdownRemainingSeconds
        updateForegroundNotification("Arming Touch Guard in ${countdownRemainingSeconds}s... Step away from device.")

        // Vocal heads-up
        if (prefs.audibleWarning.value) {
            alarmPlayer?.speakWarning("Arming JARVIS Touch Guard in $delaySec seconds.")
        }

        armingRunnable = object : Runnable {
            override fun run() {
                countdownRemainingSeconds--
                _countdownSec.value = countdownRemainingSeconds
                if (countdownRemainingSeconds > 0) {
                    updateForegroundNotification("Arming Touch Guard in ${countdownRemainingSeconds}s...")
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    activateArmState()
                }
            }
        }
        mainHandler.postDelayed(armingRunnable!!, 1000L)
    }

    private fun activateArmState() {
        armingRunnable = null
        isCurrentlyArmed = true
        prefs.setArmed(true)
        _serviceState.value = TouchGuardServiceState.ARMED
        _countdownSec.value = 0

        // Acquire wake lock to keep background sensors alert
        try {
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (_: Exception) {}

        // Reset accelerometer calibration
        isCalibrated = false
        sampleCount = 0
        if (prefs.monitorMotion.value && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }

        updateForegroundNotification("Touch Guard Armed • Monitoring for unauthorized touches")

        if (prefs.audibleWarning.value) {
            alarmPlayer?.speakWarning("Touch Guard armed. Security perimeter active.")
        }

        // Add log entry
        prefs.addLog(
            TouchGuardLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                eventType = TouchGuardEventType.ARMED,
                title = "Touch Guard Armed",
                description = "Security perimeter active with motion, charger, and screen monitors."
            )
        )
    }

    fun disarmTouchGuard(manual: Boolean) {
        armingRunnable?.let { mainHandler.removeCallbacks(it) }
        armingRunnable = null
        isCurrentlyArmed = false
        prefs.setArmed(false)
        _serviceState.value = TouchGuardServiceState.IDLE
        _countdownSec.value = 0

        silenceAlarm()

        try {
            sensorManager?.unregisterListener(this)
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        updateForegroundNotification("Touch Guard Standby • Disarmed")

        if (manual && prefs.audibleWarning.value) {
            alarmPlayer?.speakWarning("JARVIS Touch Guard disarmed. Welcome back.")
        }

        prefs.addLog(
            TouchGuardLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                eventType = TouchGuardEventType.DISARMED,
                title = "Touch Guard Disarmed",
                description = "Security system disarmed by device owner."
            )
        )
    }

    fun silenceAlarm() {
        alarmPlayer?.stopAll()
        _isAlarmRinging.value = false
    }

    // Accelerometer Motion Listener
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isCurrentlyArmed || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Smooth calibration phase (15 samples) to establish baseline resting orientation
        if (!isCalibrated) {
            gravityX = (gravityX * sampleCount + x) / (sampleCount + 1)
            gravityY = (gravityY * sampleCount + y) / (sampleCount + 1)
            gravityZ = (gravityZ * sampleCount + z) / (sampleCount + 1)
            sampleCount++
            if (sampleCount >= 15) {
                isCalibrated = true
                Log.i(tag, "Sensor baseline calibrated: ($gravityX, $gravityY, $gravityZ)")
            }
            return
        }

        // Calculate delta acceleration relative to gravity baseline
        val deltaX = x - gravityX
        val deltaY = y - gravityY
        val deltaZ = z - gravityZ
        val netAcceleration = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat()

        val sensitivityThreshold = prefs.motionSensitivity.value

        // Prevent false triggers: Check threshold and require debounce of at least 8 seconds between triggers
        if (netAcceleration > sensitivityThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTimeMs > 8000L) {
                lastTriggerTimeMs = now
                triggerSecurityBreach(
                    eventType = TouchGuardEventType.MOTION_DETECTED,
                    title = "Phone picked up",
                    description = "Significant physical motion detected (${String.format("%.1f", netAcceleration)} m/s²). Phone moved or picked up."
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerSecurityBreach(
        eventType: TouchGuardEventType,
        title: String,
        description: String
    ) {
        val now = System.currentTimeMillis()
        Log.w(tag, "SECURITY BREACH DETECTED: $title - $description")

        _lastAlertMessage.value = "ALERT: $title"
        _isAlarmRinging.value = true

        // 1. Capture front camera evidence photo (if permitted & enabled)
        var capturedPhotoFile: File? = null
        if (prefs.captureFrontPhoto.value) {
            val hasCameraPerm = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCameraPerm) {
                TouchGuardCameraCapturer.captureFrontPhoto(this, title) { photo ->
                    capturedPhotoFile = photo
                    recordBreachLog(eventType, title, description, photo?.absolutePath)
                }
            } else {
                recordBreachLog(eventType, title, "$description (Camera permission not granted; photo omitted)", null)
            }
        } else {
            recordBreachLog(eventType, title, description, null)
        }

        // 2. Play audible vocal warning & alarm siren
        if (prefs.audibleWarning.value) {
            alarmPlayer?.speakWarning("Warning! JARVIS Touch Guard activated. Unauthorized access detected! Please step away.")
        }
        if (prefs.sirenAlarm.value) {
            alarmPlayer?.playSiren(loop = true)
        }
        alarmPlayer?.triggerVibration()

        // 3. Post immediate high-priority intruder notification to owner
        postIntruderAlertNotification(title, description)
    }

    private fun recordBreachLog(
        eventType: TouchGuardEventType,
        title: String,
        description: String,
        photoPath: String?
    ) {
        val log = TouchGuardLog(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            title = title,
            description = description,
            photoPath = photoPath
        )
        prefs.addLog(log)
    }

    private fun postIntruderAlertNotification(title: String, description: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAV_TOUCH_GUARD", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(this, TouchGuardService::class.java).apply {
            action = ACTION_SILENCE_ALARM
        }
        val silencePending = PendingIntent.getService(
            this,
            1002,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disarmIntent = Intent(this, TouchGuardService::class.java).apply {
            action = ACTION_DISARM
        }
        val disarmPending = PendingIntent.getService(
            this,
            1003,
            disarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("🚨 JARVIS Touch Guard: $title")
            .setContentText(description)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Silence Siren", silencePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disarm", disarmPending)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val fgChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "JARVIS Touch Guard Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors device security perimeter when armed."
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "JARVIS Touch Guard Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for unauthorized device touches or movement."
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(fgChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildForegroundNotification(status: String = "Touch Guard Active"): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("JARVIS Touch Guard")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateForegroundNotification(status: String) {
        val notification = buildForegroundNotification(status)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_FOREGROUND, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isCurrentlyArmed = false
        silenceAlarm()
        alarmPlayer?.release()
        armingRunnable?.let { mainHandler.removeCallbacks(it) }

        try {
            sensorManager?.unregisterListener(this)
            hardwareBroadcastReceiver?.let { unregisterReceiver(it) }
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        serviceScope.cancel()
        _serviceState.value = TouchGuardServiceState.STOPPED
    }

    companion object {
        const val ACTION_START_SERVICE = "com.example.touchguard.ACTION_START"
        const val ACTION_STOP_SERVICE = "com.example.touchguard.ACTION_STOP"
        const val ACTION_ARM_NOW = "com.example.touchguard.ACTION_ARM"
        const val ACTION_DISARM = "com.example.touchguard.ACTION_DISARM"
        const val ACTION_SILENCE_ALARM = "com.example.touchguard.ACTION_SILENCE"

        const val CHANNEL_ID_FOREGROUND = "touch_guard_fg_channel"
        const val CHANNEL_ID_ALERTS = "touch_guard_alerts_channel"
        const val NOTIFICATION_ID_FOREGROUND = 8801
        const val NOTIFICATION_ID_ALERT = 8802

        enum class TouchGuardServiceState {
            STOPPED,
            IDLE,
            COUNTDOWN,
            ARMED
        }

        private val _serviceState = MutableStateFlow(TouchGuardServiceState.STOPPED)
        val serviceState: StateFlow<TouchGuardServiceState> = _serviceState.asStateFlow()

        private val _countdownSec = MutableStateFlow(0)
        val countdownSec: StateFlow<Int> = _countdownSec.asStateFlow()

        private val _isAlarmRinging = MutableStateFlow(false)
        val isAlarmRinging: StateFlow<Boolean> = _isAlarmRinging.asStateFlow()

        private val _lastAlertMessage = MutableStateFlow<String?>(null)
        val lastAlertMessage: StateFlow<String?> = _lastAlertMessage.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, TouchGuardService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun arm(context: Context) {
            val intent = Intent(context, TouchGuardService::class.java).apply {
                action = ACTION_ARM_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disarm(context: Context) {
            val intent = Intent(context, TouchGuardService::class.java).apply {
                action = ACTION_DISARM
            }
            context.startService(intent)
        }

        fun silence(context: Context) {
            val intent = Intent(context, TouchGuardService::class.java).apply {
                action = ACTION_SILENCE_ALARM
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TouchGuardService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
