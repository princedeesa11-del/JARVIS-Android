package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SupportedLanguage(val code: String, val displayName: String, val locale: Locale) {
    AUTO("auto", "Auto (Detect Script & Context)", Locale.forLanguageTag("en-IN")),
    ENGLISH_IN("en-IN", "English (India)", Locale.forLanguageTag("en-IN")),
    HINDI("hi", "हिन्दी (Hindi)", Locale.forLanguageTag("hi-IN")),
    GUJARATI("gu", "ગુજરાતી (Gujarati)", Locale.forLanguageTag("gu-IN")),
    ENGLISH("en", "English (Global)", Locale.US);

    companion object {
        fun fromCode(code: String?): SupportedLanguage {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Strict Voice Assistant state machine adhering strictly to Section 1:
 * IDLE -> WAKE_ONLY -> WAKE_DETECTED -> COMMAND_CAPTURE -> PROCESSING -> SPEAKING -> (automatic return to WAKE_ONLY)
 * Along with ERROR and RECOVERING states and backward-compatibility aliases.
 */
enum class VoiceState {
    IDLE,
    WAKE_ONLY,
    WAKE_DETECTED,
    COMMAND_CAPTURE,
    PROCESSING,
    SPEAKING,
    ERROR,
    RECOVERING,

    // Backward-compatibility and sub-state aliases
    STANDBY,
    WAKE_LISTENING,
    WAKE_CANDIDATE,
    WAKE_CONFIRMED,
    COMMAND_LISTENING,
    RECOGNIZING,
    EXECUTING,
    EXECUTING_COMMAND,
    LISTENING,
    LISTENING_AGAIN,
    WAKE_WORD_READY,
    INTERRUPTED
}

typealias AssistantState = VoiceState

class VoiceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val TAG = "VoiceManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    fun setSensitivity(sensitivity: com.example.voice.wakeword.WakeSensitivity) {
        continuousMic.setSensitivity(sensitivity)
    }

    fun setFalseTriggerProtection(enabled: Boolean) {
        continuousMic.setFalseTriggerProtection(enabled)
    }

    fun setBargeInEnabled(enabled: Boolean) {
        continuousMic.isBargeInEnabled = enabled
    }

    fun setWakePhraseProfile(profile: com.example.voice.wakeword.WakePhraseProfile) {
        continuousMic.setWakePhraseProfile(profile)
    }

    fun setCustomWakeModelPath(path: String?) {
        continuousMic.setCustomModelPath(path)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null

    val continuousMic = com.example.voice.continuous.ContinuousMicEngine(context.applicationContext)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _activeLanguage = MutableStateFlow(
        SupportedLanguage.fromCode(prefs.getString("pref_voice_language", "en-IN"))
    )
    val activeLanguage: StateFlow<SupportedLanguage> = _activeLanguage.asStateFlow()

    // Persistent Voice Profile & Custom System Voice Management
    private val _activeVoiceStyle = MutableStateFlow(
        JarvisVoiceStyle.fromId(prefs.getString("pref_voice_style", JarvisVoiceStyle.INDIAN_MALE.id))
    )
    val activeVoiceStyle: StateFlow<JarvisVoiceStyle> = _activeVoiceStyle.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow(
        prefs.getString("pref_custom_voice_name", null) ?: "Jarvis Indian Male (en-IN)"
    )
    val selectedVoiceName: StateFlow<String> = _selectedVoiceName.asStateFlow()

    private val _ttsEngineName = MutableStateFlow("Initializing TTS Engine...")
    val ttsEngineName: StateFlow<String> = _ttsEngineName.asStateFlow()

    private val _installedEngines = MutableStateFlow<List<com.example.voice.tts.TtsEngineItem>>(emptyList())
    val installedEngines: StateFlow<List<com.example.voice.tts.TtsEngineItem>> = _installedEngines.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<com.example.voice.tts.TtsVoiceItem>>(emptyList())
    val availableVoices: StateFlow<List<com.example.voice.tts.TtsVoiceItem>> = _availableVoices.asStateFlow()

    private val _speechPitch = MutableStateFlow(
        prefs.getFloat("pref_voice_pitch", _activeVoiceStyle.value.pitch)
    )
    val speechPitch: StateFlow<Float> = _speechPitch.asStateFlow()

    private val _speechRate = MutableStateFlow(
        prefs.getFloat("pref_voice_speed", _activeVoiceStyle.value.speechRate)
    )
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private var speechWatchdogJob: Job? = null

    var onSpeechFinalResult: ((String) -> Unit)? = null
    var onSpeechAudioRecorded: ((ByteArray) -> Unit)? = null
    var onWakeWordDetected: ((String) -> Unit)? = null
    var onTtsFinished: (() -> Unit)? = null
    var onErrorOccurred: ((Int) -> Unit)? = null
    var onBargeInDetected: (() -> Unit)? = null

    init {
        scope.launch {
            continuousMic.rmsLevel.collect { rms ->
                if (_isListening.value) {
                    _rmsLevel.value = rms
                }
            }
        }

        continuousMic.onWakeWordDetected = { phrase ->
            onWakeWordDetected?.invoke(phrase)
        }

        continuousMic.onSpeechRecorded = { wavBytes ->
            onSpeechAudioRecorded?.invoke(wavBytes)
        }

        continuousMic.onSpeechStarted = {
            stopSpeaking(isBargeIn = true)
        }

        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        mainHandler.post {
            try {
                val preferredEngine = prefs.getString("pref_tts_engine_package", null)
                if (!preferredEngine.isNullOrBlank()) {
                    textToSpeech = TextToSpeech(context.applicationContext, this, preferredEngine)
                } else {
                    textToSpeech = TextToSpeech(context.applicationContext, this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate TextToSpeech: ${e.message}")
                try {
                    textToSpeech = TextToSpeech(context.applicationContext, this)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback default TextToSpeech instantiation failed: ${e2.message}")
                }
            }
        }
    }

    fun selectTtsEngine(packageName: String) {
        val currentEngine = prefs.getString("pref_tts_engine_package", null)
        if (currentEngine == packageName) return

        prefs.edit().putString("pref_tts_engine_package", packageName).apply()
        mainHandler.post {
            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
            } catch (_: Exception) {}
            isTtsReady = false
            _ttsEngineName.value = "Switching Engine to $packageName..."
            initTextToSpeech()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            try {
                _ttsEngineName.value = com.example.voice.tts.TtsVoiceResolver.getEngineName(textToSpeech, context)
                refreshInstalledEngines()
                refreshAvailableVoices()
                applyEffectiveVoiceAndModulation()
            } catch (e: Exception) {
                Log.w(TAG, "Error applying initial voice config: ${e.message}")
            }
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    _voiceState.value = VoiceState.SPEAKING
                    continuousMic.isTtsSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    speechWatchdogJob?.cancel()
                    speechWatchdogJob = null
                    _isSpeaking.value = false
                    continuousMic.isTtsSpeaking = false
                    continuousMic.resetSpeechBuffer()
                    _voiceState.value = VoiceState.LISTENING
                    mainHandler.post { onTtsFinished?.invoke() }
                }

                override fun onError(utteranceId: String?) {
                    speechWatchdogJob?.cancel()
                    speechWatchdogJob = null
                    _isSpeaking.value = false
                    continuousMic.isTtsSpeaking = false
                    continuousMic.resetSpeechBuffer()
                    _voiceState.value = VoiceState.ERROR
                    mainHandler.post { onTtsFinished?.invoke() }
                }
            })
            _voiceState.value = VoiceState.IDLE

            // Play any pending speech request queued during async TTS initialization
            pendingSpeechText?.let { pending ->
                pendingSpeechText = null
                speak(pending)
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
            val preferredEngine = prefs.getString("pref_tts_engine_package", null)
            if (!preferredEngine.isNullOrBlank()) {
                Log.w(TAG, "Selected TTS engine '$preferredEngine' failed; resetting to system default.")
                prefs.edit().remove("pref_tts_engine_package").apply()
                initTextToSpeech()
            } else {
                _ttsEngineName.value = "TTS Engine Unavailable ($status)"
                _voiceState.value = VoiceState.ERROR
            }
        }
    }

    fun refreshInstalledEngines() {
        val selectedPkg = prefs.getString("pref_tts_engine_package", null)
        _installedEngines.value = com.example.voice.tts.TtsVoiceResolver.getInstalledEngines(textToSpeech, selectedPkg)
    }

    fun refreshAvailableVoices() {
        val customSelected = prefs.getString("pref_custom_voice_name", null)
        _availableVoices.value = com.example.voice.tts.TtsVoiceResolver.getAvailableVoices(textToSpeech, customSelected)
    }

    /**
     * Applies the effective voice and modulation based on:
     * 1. Custom selected system voice (if specified by user in settings)
     * 2. Active voice profile (Jarvis Indian Male prefers en-IN male, hi-IN, gu-IN)
     * 3. Fallback resolution if previously saved voice is no longer present
     * 4. Configured pitch and speech rate
     */
    fun applyEffectiveVoiceAndModulation(overrideLanguageTag: String? = null) {
        if (!isTtsReady || textToSpeech == null) return

        try {
            val customVoiceName = prefs.getString("pref_custom_voice_name", null)
            val resolution = com.example.voice.tts.TtsVoiceResolver.resolveVoice(
                tts = textToSpeech,
                preferredVoiceName = customVoiceName,
                voiceStyle = _activeVoiceStyle.value,
                preferredLanguageCode = overrideLanguageTag ?: _activeLanguage.value.code
            )

            if (resolution.voice != null) {
                try {
                    textToSpeech?.voice = resolution.voice
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to assign resolved voice: ${e.message}")
                }
            }

            // If a previously saved custom voice disappeared, clear preference to avoid stale state
            if (resolution.isFallback && !customVoiceName.isNullOrBlank()) {
                Log.i(TAG, "Custom voice '$customVoiceName' no longer available; falling back to: ${resolution.displayName}")
                prefs.edit().remove("pref_custom_voice_name").apply()
            }

            _selectedVoiceName.value = resolution.displayName
            textToSpeech?.setPitch(_speechPitch.value.coerceIn(0.5f, 2.0f))
            textToSpeech?.setSpeechRate(_speechRate.value.coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            Log.e(TAG, "applyEffectiveVoiceAndModulation error: ${e.message}")
        }
    }

    private fun fallbackToIndianMaleVoice(overrideLanguageTag: String? = null) {
        val targetLang = overrideLanguageTag ?: _activeLanguage.value.code
        val bestVoice = com.example.voice.tts.TtsVoiceResolver.findBestIndianMaleVoice(textToSpeech, targetLang)
        if (bestVoice != null) {
            textToSpeech?.voice = bestVoice
            _selectedVoiceName.value = "Jarvis Indian Male (${bestVoice.locale.toLanguageTag()})"
        } else {
            val result = textToSpeech?.setLanguage(_activeLanguage.value.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.US)
            }
            _selectedVoiceName.value = "Jarvis Indian Male (System Fallback)"
        }
    }

    fun setLanguage(language: SupportedLanguage) {
        _activeLanguage.value = language
        prefs.edit().putString("pref_voice_language", language.code).apply()
        applyEffectiveVoiceAndModulation(language.code)
    }

    private fun applyLanguage(language: SupportedLanguage) {
        try {
            val result = textToSpeech?.setLanguage(language.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.US)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyLanguage error: ${e.message}")
        }
    }

    fun setVoiceProfile(pitch: Float, rate: Float) {
        _speechPitch.value = pitch
        _speechRate.value = rate
        prefs.edit()
            .putFloat("pref_voice_pitch", pitch)
            .putFloat("pref_voice_speed", rate)
            .apply()
        textToSpeech?.setPitch(pitch)
        textToSpeech?.setSpeechRate(rate)
    }

    fun applyVoiceStyle(style: JarvisVoiceStyle) {
        _activeVoiceStyle.value = style
        prefs.edit()
            .putString("pref_voice_style", style.id)
            .remove("pref_custom_voice_name")
            .putFloat("pref_voice_pitch", style.pitch)
            .putFloat("pref_voice_speed", style.speechRate)
            .apply()

        _speechPitch.value = style.pitch
        _speechRate.value = style.speechRate

        refreshAvailableVoices()
        applyEffectiveVoiceAndModulation()
    }

    fun selectCustomSystemVoice(voiceName: String) {
        prefs.edit().putString("pref_custom_voice_name", voiceName).apply()
        refreshAvailableVoices()
        applyEffectiveVoiceAndModulation()
    }

    fun clearCustomSystemVoice() {
        prefs.edit().remove("pref_custom_voice_name").apply()
        refreshAvailableVoices()
        applyEffectiveVoiceAndModulation()
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        _speechPitch.value = clamped
        prefs.edit().putFloat("pref_voice_pitch", clamped).apply()
        textToSpeech?.setPitch(clamped)
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        _speechRate.value = clamped
        prefs.edit().putFloat("pref_voice_speed", clamped).apply()
        textToSpeech?.setSpeechRate(clamped)
    }

    fun getVoiceStyle(): JarvisVoiceStyle = _activeVoiceStyle.value

    fun testCurrentVoice(sampleText: String? = null) {
        val textToSpeak = sampleText ?: when (_activeLanguage.value) {
            SupportedLanguage.AUTO -> "Hello, I am Jarvis. Neural systems are online and standing by for your directive."
            SupportedLanguage.HINDI -> "नमस्ते! मैं जार्विस हूँ। आपकी क्या सेवा कर सकता हूँ?"
            SupportedLanguage.GUJARATI -> "નમસ્તે! હું જાર્વિસ છું. આપની શી સેવા કરી શકું?"
            SupportedLanguage.ENGLISH_IN -> "Hello, I am Jarvis. Ready for your directive, sir."
            SupportedLanguage.ENGLISH -> "Hello, I am Jarvis. Neural systems are online and standing by."
        }
        speak(textToSpeak, utteranceId = "jarvis_test_voice")
    }

    fun updateState(state: VoiceState) {
        _voiceState.value = state
    }

    @Synchronized
    fun startListening() {
        mainHandler.post {
            // Barge-in: Stop active speech immediately
            stopSpeaking(isBargeIn = true)

            _recognizedText.value = ""
            _isListening.value = true
            _voiceState.value = VoiceState.LISTENING

            // Ensure no competing SpeechRecognizer is running
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}

            // Start the continuous hardware microphone engine as the SINGLE authoritative mic consumer
            // This guarantees:
            // 1. Android privacy green dot in status bar stays solid ON continuously.
            // 2. Zero 3-4 second silence timeouts.
            // 3. High-precision 60 FPS RMS audio levels.
            // 4. Real-time VAD voice capture and barge-in.
            continuousMic.start()
        }
    }

    /**
     * Dedicated method for optional one-shot speech recognition if ever requested outside continuous mode.
     * Prevents competing with ContinuousMicEngine.
     */
    fun startOneShotSpeechRecognizer() {
        mainHandler.post {
            if (continuousMic.isRecording.value) {
                Log.d(TAG, "Continuous mic is active; skipping separate SpeechRecognizer.")
                return@post
            }
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {}

                if (speechRecognizer == null) {
                    try {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                            setRecognitionListener(createListener())
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "SpeechRecognizer creation deferred: ${e.message}")
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, _activeLanguage.value.locale.toString())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                try {
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    Log.d(TAG, "One-shot SpeechRecognizer start failed: ${e.message}")
                }
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            // Do NOT call continuousMic.stop() here.
            // Keep ContinuousMicEngine running so green indicator stays ON.
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            _isListening.value = false
            _rmsLevel.value = 0f
            if (_voiceState.value == VoiceState.LISTENING) {
                _voiceState.value = VoiceState.PROCESSING
            }
        }
    }

    @Synchronized
    fun cancel() {
        mainHandler.post {
            continuousMic.stop()
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            _isListening.value = false
            _rmsLevel.value = 0f
            stopSpeaking()
            _voiceState.value = VoiceState.IDLE
        }
    }

    @Synchronized
    fun speak(text: String, utteranceId: String = "jarvis_reply", keepMicRunning: Boolean = true) {
        if (text.isBlank()) {
            _voiceState.value = VoiceState.IDLE
            return
        }

        stopSpeaking()

        if (!keepMicRunning) {
            stopListening()
        } else {
            // Keep the hardware mic recording so the green dot NEVER drops!
            continuousMic.isTtsSpeaking = true
        }

        if (!isTtsReady || textToSpeech == null) {
            Log.d(TAG, "TTS not ready yet; queuing speech text.")
            pendingSpeechText = text
            return
        }

        _isSpeaking.value = true
        _voiceState.value = VoiceState.SPEAKING

        // Safety watchdog: prevent assistant from freezing if a TTS engine stalls
        speechWatchdogJob?.cancel()
        speechWatchdogJob = scope.launch {
            val maxDurationMs = (text.length * 140L + 6000L).coerceIn(5000L, 35000L)
            delay(maxDurationMs)
            if (_isSpeaking.value) {
                Log.w(TAG, "Speech watchdog timer expired ($maxDurationMs ms); recovering state cleanly.")
                _isSpeaking.value = false
                continuousMic.isTtsSpeaking = false
                continuousMic.resetSpeechBuffer()
                _voiceState.value = VoiceState.IDLE
                onTtsFinished?.invoke()
            }
        }

        mainHandler.post {
            try {
                val segments = com.example.voice.tts.TtsVoiceResolver.splitIntoLanguageSegments(text)
                if (segments.isEmpty()) {
                    _isSpeaking.value = false
                    continuousMic.isTtsSpeaking = false
                    _voiceState.value = VoiceState.IDLE
                    speechWatchdogJob?.cancel()
                    return@post
                }

                for (i in segments.indices) {
                    val segment = segments[i]
                    val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val segUtteranceId = if (i == segments.lastIndex) utteranceId else "${utteranceId}_part_$i"

                    // Multi-lingual script-to-voice routing
                    when (segment.languageCode) {
                        "gu" -> {
                            val guVoice = com.example.voice.tts.TtsVoiceResolver.findBestVoiceForLanguage(textToSpeech, "gu", preferMale = true)
                            if (guVoice != null) {
                                textToSpeech?.voice = guVoice
                            } else {
                                val res = textToSpeech?.setLanguage(Locale.forLanguageTag("gu-IN"))
                                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    textToSpeech?.setLanguage(Locale.forLanguageTag("hi-IN"))
                                }
                            }
                        }
                        "hi" -> {
                            val hiVoice = com.example.voice.tts.TtsVoiceResolver.findBestVoiceForLanguage(textToSpeech, "hi", preferMale = true)
                            if (hiVoice != null) {
                                textToSpeech?.voice = hiVoice
                            } else {
                                val res = textToSpeech?.setLanguage(Locale.forLanguageTag("hi-IN"))
                                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    textToSpeech?.setLanguage(Locale.US)
                                }
                            }
                        }
                        else -> {
                            applyEffectiveVoiceAndModulation()
                        }
                    }

                    textToSpeech?.setPitch(_speechPitch.value.coerceIn(0.5f, 2.0f))
                    textToSpeech?.setSpeechRate(_speechRate.value.coerceIn(0.5f, 2.0f))
                    textToSpeech?.speak(segment.text, queueMode, null, segUtteranceId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                speechWatchdogJob?.cancel()
                speechWatchdogJob = null
                _isSpeaking.value = false
                continuousMic.isTtsSpeaking = false
                _voiceState.value = VoiceState.ERROR
                onTtsFinished?.invoke()
            }
        }
    }

    @Synchronized
    fun stopSpeaking(isBargeIn: Boolean = false) {
        speechWatchdogJob?.cancel()
        speechWatchdogJob = null
        if (_isSpeaking.value || textToSpeech?.isSpeaking == true) {
            try {
                textToSpeech?.stop()
            } catch (_: Exception) {}
            _isSpeaking.value = false
            continuousMic.isTtsSpeaking = false
            _voiceState.value = if (isBargeIn) VoiceState.INTERRUPTED else VoiceState.IDLE
            if (isBargeIn) {
                mainHandler.post { onBargeInDetected?.invoke() }
            }
        }
    }

    private fun createListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _voiceState.value = VoiceState.LISTENING
        }

        override fun onBeginningOfSpeech() {
            stopSpeaking(isBargeIn = true)
            _voiceState.value = VoiceState.LISTENING
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isListening.value = false
            _rmsLevel.value = 0f
            _voiceState.value = VoiceState.PROCESSING
        }

        override fun onError(error: Int) {
            Log.w(TAG, "RecognitionListener onError: $error")
            _isListening.value = false
            _rmsLevel.value = 0f
            _voiceState.value = VoiceState.ERROR
            onErrorOccurred?.invoke(error)
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _rmsLevel.value = 0f
            _voiceState.value = VoiceState.PROCESSING
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalQuery = matches?.firstOrNull() ?: ""
            if (finalQuery.isNotBlank()) {
                _recognizedText.value = finalQuery
                onSpeechFinalResult?.invoke(finalQuery)
            } else {
                _voiceState.value = VoiceState.IDLE
                onErrorOccurred?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                _recognizedText.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun destroy() {
        mainHandler.post {
            continuousMic.stop()
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null

            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
            } catch (_: Exception) {}
            textToSpeech = null
            isTtsReady = false
            _voiceState.value = VoiceState.IDLE
        }
    }
}
