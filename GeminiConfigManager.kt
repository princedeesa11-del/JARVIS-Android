package com.example.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection and error states for Gemini runtime interactions.
 */
enum class GeminiStatus {
    KEY_CONFIGURED,
    CONNECTING,
    ONLINE,
    NETWORK_ERROR,
    AUTH_ERROR,
    RATE_LIMITED,
    MODEL_ERROR,
    SERVER_ERROR,
    PARSING_ERROR,
    OFFLINE,
    API_KEY_INVALID
}

enum class KeySource {
    CUSTOM,
    BUILD_CONFIG,
    NONE
}

/**
 * Authoritative Single Source of Truth for Gemini API configuration, credentials,
 * selected model, and runtime diagnostics across all JARVIS components:
 * - JarvisVoiceAssistant
 * - JarvisViewModel
 * - ContinuousMicEngine / VoiceManager
 * - GeminiLiveSessionManager
 * - Settings screens
 */
class GeminiConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _customApiKey = MutableStateFlow(prefs.getString(KEY_CUSTOM_API_KEY, "")?.trim() ?: "")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(
        prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL)?.trim()?.ifEmpty { DEFAULT_MODEL } ?: DEFAULT_MODEL
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _selectedAudioModel = MutableStateFlow(
        prefs.getString(KEY_AUDIO_MODEL, DEFAULT_AUDIO_MODEL)?.trim()?.ifEmpty { DEFAULT_AUDIO_MODEL } ?: DEFAULT_AUDIO_MODEL
    )
    val selectedAudioModel: StateFlow<String> = _selectedAudioModel.asStateFlow()

    private val _connectionStatus = MutableStateFlow(
        if (hasValidKeyConfigured()) GeminiStatus.KEY_CONFIGURED else GeminiStatus.OFFLINE
    )
    val connectionStatus: StateFlow<GeminiStatus> = _connectionStatus.asStateFlow()

    private val _lastDiagnostic = MutableStateFlow("Initialized")
    val lastDiagnostic: StateFlow<String> = _lastDiagnostic.asStateFlow()

    fun getEffectiveApiKey(): String {
        val custom = _customApiKey.value.trim()
        if (custom.isNotEmpty()) return custom

        return try {
            val buildConfigKey = BuildConfig.GEMINI_API_KEY.trim()
            if (buildConfigKey.isNotEmpty() && buildConfigKey != "MY_GEMINI_API_KEY") {
                buildConfigKey
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun getKeySource(): KeySource {
        val custom = _customApiKey.value.trim()
        if (custom.isNotEmpty()) return KeySource.CUSTOM

        val buildConfigKey = try { BuildConfig.GEMINI_API_KEY.trim() } catch (_: Exception) { "" }
        if (buildConfigKey.isNotEmpty() && buildConfigKey != "MY_GEMINI_API_KEY") {
            return KeySource.BUILD_CONFIG
        }
        return KeySource.NONE
    }

    fun hasValidKeyConfigured(): Boolean {
        return getEffectiveApiKey().isNotEmpty()
    }

    fun getSelectedModel(): String {
        val model = _selectedModel.value.trim()
        return if (model.isNotEmpty()) model else DEFAULT_MODEL
    }

    fun getSelectedAudioModel(): String {
        val audio = _selectedAudioModel.value.trim()
        if (audio.isNotEmpty()) return audio
        return getSelectedModel()
    }

    fun getEffectiveAudioModel(): String = getSelectedAudioModel()

    @Synchronized
    fun saveApiKey(newKey: String) {
        val trimmed = newKey.trim()
        _customApiKey.value = trimmed
        prefs.edit().putString(KEY_CUSTOM_API_KEY, trimmed).apply()
        _connectionStatus.value = if (trimmed.isNotEmpty()) GeminiStatus.KEY_CONFIGURED else GeminiStatus.OFFLINE
        Log.i(TAG, "[GEMINI] API key saved. Key present=${trimmed.isNotEmpty()}, source=${getKeySource()}")
    }

    @Synchronized
    fun saveSelectedModel(newModel: String) {
        val trimmed = newModel.trim().ifEmpty { DEFAULT_MODEL }
        _selectedModel.value = trimmed
        prefs.edit().putString(KEY_SELECTED_MODEL, trimmed).apply()
        Log.i(TAG, "[GEMINI] Selected model updated to '$trimmed'")
    }

    @Synchronized
    fun saveSelectedAudioModel(newAudioModel: String) {
        val trimmed = newAudioModel.trim().ifEmpty { DEFAULT_AUDIO_MODEL }
        _selectedAudioModel.value = trimmed
        prefs.edit().putString(KEY_AUDIO_MODEL, trimmed).apply()
        Log.i(TAG, "[GEMINI] Selected audio model updated to '$trimmed'")
    }

    fun updateStatus(status: GeminiStatus, diagnosticDetail: String? = null) {
        _connectionStatus.value = status
        if (diagnosticDetail != null) {
            _lastDiagnostic.value = diagnosticDetail
        }
        Log.i(TAG, "[GEMINI] Runtime status changed to $status (${diagnosticDetail ?: "no detail"})")
    }

    companion object {
        private const val TAG = "GeminiConfigManager"
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_AUDIO_MODEL = "selected_audio_model"
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        const val DEFAULT_AUDIO_MODEL = "gemini-3.5-flash"

        @Volatile
        private var instance: GeminiConfigManager? = null

        fun getInstance(context: Context): GeminiConfigManager {
            return instance ?: synchronized(this) {
                instance ?: GeminiConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
