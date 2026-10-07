package com.example.touchguard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single security log event for Touch Guard.
 */
data class TouchGuardLog(
    val id: String,
    val timestamp: Long,
    val eventType: TouchGuardEventType,
    val title: String,
    val description: String,
    val photoPath: String? = null
)

enum class TouchGuardEventType(val displayName: String) {
    ARMED("System Armed"),
    DISARMED("System Disarmed"),
    SCREEN_WAKE("Screen Woken / Unlocked"),
    MOTION_DETECTED("Motion / Phone Picked Up"),
    CHARGER_DISCONNECTED("Charger Disconnected")
}

/**
 * Preferences & Evidence Store for Touch Guard.
 * Manages configuration, sensitivity settings, arm delays, and locally saved intruder incident logs.
 */
class TouchGuardPreferences(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("touch_guard_prefs", Context.MODE_PRIVATE)

    private val _isTouchGuardEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isTouchGuardEnabled: StateFlow<Boolean> = _isTouchGuardEnabled.asStateFlow()

    private val _isArmed = MutableStateFlow(prefs.getBoolean(KEY_ARMED, false))
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _armDelaySeconds = MutableStateFlow(prefs.getInt(KEY_ARM_DELAY_SEC, 5))
    val armDelaySeconds: StateFlow<Int> = _armDelaySeconds.asStateFlow()

    private val _monitorScreenUnlock = MutableStateFlow(prefs.getBoolean(KEY_MONITOR_SCREEN, true))
    val monitorScreenUnlock: StateFlow<Boolean> = _monitorScreenUnlock.asStateFlow()

    private val _monitorMotion = MutableStateFlow(prefs.getBoolean(KEY_MONITOR_MOTION, true))
    val monitorMotion: StateFlow<Boolean> = _monitorMotion.asStateFlow()

    private val _monitorCharger = MutableStateFlow(prefs.getBoolean(KEY_MONITOR_CHARGER, true))
    val monitorCharger: StateFlow<Boolean> = _monitorCharger.asStateFlow()

    private val _motionSensitivity = MutableStateFlow(prefs.getFloat(KEY_MOTION_SENSITIVITY, 2.5f))
    val motionSensitivity: StateFlow<Float> = _motionSensitivity.asStateFlow()

    private val _captureFrontPhoto = MutableStateFlow(prefs.getBoolean(KEY_CAPTURE_PHOTO, true))
    val captureFrontPhoto: StateFlow<Boolean> = _captureFrontPhoto.asStateFlow()

    private val _audibleWarning = MutableStateFlow(prefs.getBoolean(KEY_AUDIBLE_WARNING, true))
    val audibleWarning: StateFlow<Boolean> = _audibleWarning.asStateFlow()

    private val _sirenAlarm = MutableStateFlow(prefs.getBoolean(KEY_SIREN_ALARM, true))
    val sirenAlarm: StateFlow<Boolean> = _sirenAlarm.asStateFlow()

    private val _logsFlow = MutableStateFlow(loadLogs())
    val logs: StateFlow<List<TouchGuardLog>> = _logsFlow.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _isTouchGuardEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled && _isArmed.value) {
            setArmed(false)
        }
    }

    fun setArmed(armed: Boolean) {
        _isArmed.value = armed
        prefs.edit().putBoolean(KEY_ARMED, armed).apply()
    }

    fun setArmDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 60)
        _armDelaySeconds.value = clamped
        prefs.edit().putInt(KEY_ARM_DELAY_SEC, clamped).apply()
    }

    fun setMonitorScreenUnlock(enabled: Boolean) {
        _monitorScreenUnlock.value = enabled
        prefs.edit().putBoolean(KEY_MONITOR_SCREEN, enabled).apply()
    }

    fun setMonitorMotion(enabled: Boolean) {
        _monitorMotion.value = enabled
        prefs.edit().putBoolean(KEY_MONITOR_MOTION, enabled).apply()
    }

    fun setMonitorCharger(enabled: Boolean) {
        _monitorCharger.value = enabled
        prefs.edit().putBoolean(KEY_MONITOR_CHARGER, enabled).apply()
    }

    fun setMotionSensitivity(value: Float) {
        val clamped = value.coerceIn(1.0f, 6.0f)
        _motionSensitivity.value = clamped
        prefs.edit().putFloat(KEY_MOTION_SENSITIVITY, clamped).apply()
    }

    fun setCaptureFrontPhoto(enabled: Boolean) {
        _captureFrontPhoto.value = enabled
        prefs.edit().putBoolean(KEY_CAPTURE_PHOTO, enabled).apply()
    }

    fun setAudibleWarning(enabled: Boolean) {
        _audibleWarning.value = enabled
        prefs.edit().putBoolean(KEY_AUDIBLE_WARNING, enabled).apply()
    }

    fun setSirenAlarm(enabled: Boolean) {
        _sirenAlarm.value = enabled
        prefs.edit().putBoolean(KEY_SIREN_ALARM, enabled).apply()
    }

    @Synchronized
    fun addLog(log: TouchGuardLog) {
        val current = _logsFlow.value.toMutableList()
        current.add(0, log)
        val trimmed = if (current.size > 100) current.take(100) else current
        _logsFlow.value = trimmed
        saveLogs(trimmed)
    }

    @Synchronized
    fun clearLogs() {
        _logsFlow.value = emptyList()
        saveLogs(emptyList())

        // Delete evidence photos
        val evidenceDir = File(context.filesDir, "touch_guard_evidence")
        if (evidenceDir.exists()) {
            evidenceDir.listFiles()?.forEach { it.delete() }
        }
    }

    @Synchronized
    fun deleteLog(logId: String) {
        val current = _logsFlow.value.toMutableList()
        val found = current.firstOrNull { it.id == logId }
        if (found != null) {
            found.photoPath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {}
            }
            current.remove(found)
            _logsFlow.value = current
            saveLogs(current)
        }
    }

    private fun loadLogs(): List<TouchGuardLog> {
        val jsonString = prefs.getString(KEY_LOGS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<TouchGuardLog>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val typeName = obj.optString("eventType", TouchGuardEventType.MOTION_DETECTED.name)
                val eventType = try {
                    TouchGuardEventType.valueOf(typeName)
                } catch (_: Exception) {
                    TouchGuardEventType.MOTION_DETECTED
                }
                list.add(
                    TouchGuardLog(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        eventType = eventType,
                        title = obj.getString("title"),
                        description = obj.getString("description"),
                        photoPath = obj.optString("photoPath").takeIf { it.isNotBlank() }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLogs(list: List<TouchGuardLog>) {
        try {
            val array = JSONArray()
            list.forEach { log ->
                val obj = JSONObject().apply {
                    put("id", log.id)
                    put("timestamp", log.timestamp)
                    put("eventType", log.eventType.name)
                    put("title", log.title)
                    put("description", log.description)
                    put("photoPath", log.photoPath ?: "")
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_LOGS_JSON, array.toString()).apply()
        } catch (_: Exception) {}
    }

    companion object {
        private const val KEY_ENABLED = "touch_guard_enabled"
        private const val KEY_ARMED = "touch_guard_armed"
        private const val KEY_ARM_DELAY_SEC = "touch_guard_arm_delay_sec"
        private const val KEY_MONITOR_SCREEN = "touch_guard_monitor_screen"
        private const val KEY_MONITOR_MOTION = "touch_guard_monitor_motion"
        private const val KEY_MONITOR_CHARGER = "touch_guard_monitor_charger"
        private const val KEY_MOTION_SENSITIVITY = "touch_guard_motion_sens"
        private const val KEY_CAPTURE_PHOTO = "touch_guard_capture_photo"
        private const val KEY_AUDIBLE_WARNING = "touch_guard_audible_warning"
        private const val KEY_SIREN_ALARM = "touch_guard_siren_alarm"
        private const val KEY_LOGS_JSON = "touch_guard_logs_json"

        @Volatile
        private var INSTANCE: TouchGuardPreferences? = null

        fun getInstance(context: Context): TouchGuardPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchGuardPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
