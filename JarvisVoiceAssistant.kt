package com.example.voice.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.ai.AIMessage
import com.example.ai.GeminiAIProvider
import com.example.ai.LocalOfflineProvider
import com.example.ai.Persona
import com.example.data.repository.JarvisRepository
import com.example.service.WakeWordService
import com.example.voice.AssistantState
import com.example.voice.MicrophoneOwnershipCoordinator
import com.example.voice.MicrophoneState
import com.example.voice.VoiceManager
import com.example.voice.VoiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class WakeSession(
    val id: Long,
    val phrase: String,
    val startTimeMs: Long = SystemClock.elapsedRealtime(),
    var isCommandReceived: Boolean = false
)

/**
 * Production-Ready Voice Orchestrator for JARVIS.
 * Coordinates local wake-word spotting, one-shot/two-step commands, barge-in,
 * fast local intent routing, and Gemini AI reasoning.
 */
class JarvisVoiceAssistant(
    private val context: Context,
    private val voiceManager: VoiceManager,
    private val geminiProvider: GeminiAIProvider,
    private val offlineProvider: LocalOfflineProvider,
    private val repository: JarvisRepository,
    private val commandRouter: CommandRouter
) {
    private val TAG = "JarvisVoiceAssistant"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _assistantState = MutableStateFlow(VoiceState.IDLE)
    val assistantState: StateFlow<VoiceState> = _assistantState.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _lastDirective = MutableStateFlow("")
    val lastDirective: StateFlow<String> = _lastDirective.asStateFlow()

    @Volatile
    var isAssistantActive: Boolean = false
        private set

    private val isExecuting = AtomicBoolean(false)

    @Volatile
    private var currentWakeSession: WakeSession? = null
    private var commandTimeoutJob: Job? = null

    @Volatile
    private var lastProcessedSpeech: String = ""
    @Volatile
    private var lastProcessedTimestamp: Long = 0L
    @Volatile
    private var lastWakeWordTimestamp: Long = 0L

    init {
        // Wire callbacks from VoiceManager
        voiceManager.onSpeechFinalResult = { text ->
            onSpeechCaptured(text)
        }

        voiceManager.onSpeechAudioRecorded = { wavBytes ->
            onSpeechAudioCaptured(wavBytes)
        }

        voiceManager.onWakeWordDetected = { phrase ->
            onWakeWordTriggered(phrase)
        }

        voiceManager.onTtsFinished = {
            onTtsCompleted()
        }

        voiceManager.onBargeInDetected = {
            if (isAssistantActive) {
                _assistantState.value = VoiceState.COMMAND_CAPTURE
                voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
                WakeWordService.updateServiceNotification(context, "JARVIS Active • Interrupted")
            }
        }

        voiceManager.onErrorOccurred = { errorCode ->
            onSpeechRecognitionError(errorCode)
        }
    }

    private suspend fun buildContextualMessages(currentQuery: String): List<AIMessage> {
        val recent = repository.getRecentMessages(limit = 12).reversed()
        val history = recent.filter { it.content.isNotBlank() }.map {
            AIMessage(
                role = if (it.role == "user") "user" else "model",
                text = it.content
            )
        }.toMutableList()

        if (history.isEmpty() || history.last().text != currentQuery || history.last().role != "user") {
            history.add(AIMessage(role = "user", text = currentQuery))
        }
        return history
    }

    private suspend fun buildJarvisSystemInstruction(): String {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val pUserName = prefs.getString("user_display_name", "Prince")?.ifBlank { "Prince" } ?: "Prince"
        val pGender = prefs.getString("user_gender_preference", "") ?: ""
        val personalContext = buildString {
            append("• User name: $pUserName\n")
            if (pGender.isNotBlank() && pGender != "Prefer not to say") append("• User gender: $pGender\n")
            append("• Continuous voice interaction mode.\n")
            append("• Reply in the same language Prince uses (Gujarati preferred, then Hindi or English).\n")
        }
        val memoryContext = repository.getRecentMemories(8).joinToString("\n") { "• [${it.category}] ${it.key}: ${it.content}" }
        return Persona.JARVIS.buildSystemInstruction(memoryContext, personalContext)
    }

    private fun cleanJarvisSpeech(text: String): String {
        var clean = text
            .replace(Regex("\\*\\*|\\*|#+|_|`"), "")
            .replace(Regex("(?i)I('m| am) listening[,.]?"), "")
            .replace(Regex("(?i)go ahead[,.]?"), "")
            .replace(Regex("(?i)please speak[,.]?"), "")
            .replace(Regex("(?i)I didn't catch that[,.]?"), "")
            .replace(Regex("(?i)try again[,.]?"), "")
            .replace(Regex("(?i)as an ai( model| language model)?[,.]?"), "")
            .trim()

        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.size > 2) {
            clean = sentences.take(2).joinToString(" ")
        }
        return clean.trim()
    }

    /**
     * Triggered immediately when real-time wake word is spotted on-device.
     */
    private fun onWakeWordTriggered(phrase: String) {
        val now = SystemClock.elapsedRealtime()
        lastWakeWordTimestamp = now
        val session = WakeSession(id = now, phrase = phrase)
        currentWakeSession = session
        Log.i("WAKE", "[WAKE] confirmed: '$phrase' (session #${session.id})")

        // Barge-in: if JARVIS was speaking, stop immediately
        if (_assistantState.value == VoiceState.SPEAKING) {
            voiceManager.stopSpeaking(isBargeIn = true)
        }

        isAssistantActive = true
        MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.COMMAND_MODE)
        
        // Strict voice state transitions: WAKE_DETECTED -> COMMAND_CAPTURE
        _assistantState.value = VoiceState.WAKE_DETECTED
        voiceManager.updateState(VoiceState.WAKE_DETECTED)
        
        mainHandler.post {
            _assistantState.value = VoiceState.COMMAND_CAPTURE
            voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
            WakeWordService.updateServiceNotification(context, "JARVIS • Listening for Command")
        }

        // Screen wake
        try {
            com.example.security.ScreenLockManager.getInstance(context).wakeScreen()
        } catch (_: Exception) {}

        WakeWordService.onWakeWordTriggered?.invoke(phrase)

        startCommandTimeout()
    }

    private fun startCommandTimeout() {
        commandTimeoutJob?.cancel()
        commandTimeoutJob = scope.launch {
            delay(7500L)
            if (_assistantState.value == VoiceState.COMMAND_CAPTURE || _assistantState.value == VoiceState.COMMAND_LISTENING) {
                Log.i("WAKE", "[WAKE] Command timeout reached with silence; returning to WAKE_ONLY")
                currentWakeSession = null
                withContext(Dispatchers.Main) {
                    _assistantState.value = VoiceState.WAKE_ONLY
                    voiceManager.updateState(VoiceState.WAKE_ONLY)
                    MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
                    WakeWordService.updateServiceNotification(context, "JARVIS Standby • Wake Word Active")
                }
            }
        }
    }

    /**
     * Start or activate continuous assistant listening pipeline.
     */
    fun startAssistant(continuous: Boolean = true) {
        isAssistantActive = continuous
        _assistantState.value = VoiceState.WAKE_ONLY
        voiceManager.updateState(VoiceState.WAKE_ONLY)
        MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
        WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for 'Hey Jarvis'")
        mainHandler.post {
            voiceManager.startListening()
        }
    }

    /**
     * Pause assistant listening.
     */
    fun pauseAssistant() {
        commandTimeoutJob?.cancel()
        mainHandler.post {
            voiceManager.cancel()
        }
        _assistantState.value = VoiceState.IDLE
        voiceManager.updateState(VoiceState.IDLE)
        MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.STOPPED)
        WakeWordService.updateServiceNotification(context, "JARVIS Standby • Paused")
    }

    /**
     * Resume assistant.
     */
    fun resumeAssistant() {
        if (isAssistantActive) {
            startAssistant(continuous = true)
        }
    }

    /**
     * Completely stop assistant and release resources.
     */
    fun stopAssistant() {
        commandTimeoutJob?.cancel()
        isAssistantActive = false
        currentWakeSession = null
        _assistantState.value = VoiceState.IDLE
        voiceManager.updateState(VoiceState.IDLE)
        MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.STOPPED)
        mainHandler.post {
            voiceManager.cancel()
        }
        WakeWordService.updateServiceNotification(context, "JARVIS Standby")
    }

    /**
     * Core text voice directive entry point.
     */
    fun onSpeechCaptured(rawText: String) {
        val query = rawText.trim()
        if (query.isBlank()) {
            if (isAssistantActive && _assistantState.value != VoiceState.SPEAKING && !isExecuting.get()) {
                _assistantState.value = VoiceState.WAKE_ONLY
                voiceManager.updateState(VoiceState.WAKE_ONLY)
            }
            return
        }

        if (isExecuting.get() || _assistantState.value == VoiceState.EXECUTING_COMMAND) {
            Log.d(TAG, "Automation directive actively running; skipping re-entrant input: '$query'")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val isGatedInWakeOnly = (_assistantState.value == VoiceState.WAKE_ONLY || _assistantState.value == VoiceState.WAKE_LISTENING)
        val hasRecentWake = (currentWakeSession != null && (now - lastWakeWordTimestamp) < 6500L)
        val normalized = commandRouter.normalize(query)
        val hasWakePrefix = (!query.equals(normalized, ignoreCase = true) && normalized.isNotBlank()) || normalized == "wake_call"

        // STRICT WAKE-WORD GATING (Section 1):
        // When Jarvis is in WAKE_ONLY mode, normal speech must NOT be treated as a user command.
        if (isGatedInWakeOnly && !hasRecentWake && !hasWakePrefix) {
            Log.i("WAKE", "[WAKE] gated: normal speech ignored in WAKE_ONLY mode without wake word ('$query')")
            return
        }

        commandTimeoutJob?.cancel()

        if (query.equals(lastProcessedSpeech, ignoreCase = true) && (now - lastProcessedTimestamp) < 1800L) {
            Log.d(TAG, "Suppressed duplicate utterance: '$query'")
            return
        }
        lastProcessedSpeech = query
        lastProcessedTimestamp = now

        // Standalone wake phrase detected (two-step wake: "Hey Jarvis")
        if (normalized == "wake_call" || normalized.isBlank()) {
            Log.i("WAKE", "[WAKE] Standalone wake phrase detected; entering COMMAND_CAPTURE mode silently.")
            lastWakeWordTimestamp = now
            currentWakeSession = WakeSession(id = now, phrase = "hey jarvis")
            _assistantState.value = VoiceState.COMMAND_CAPTURE
            voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
            MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.COMMAND_MODE)
            WakeWordService.updateServiceNotification(context, "JARVIS • Listening for Command")
            startCommandTimeout()
            return
        }

        val effectiveDirective = normalized
        _lastDirective.value = effectiveDirective
        _assistantState.value = VoiceState.PROCESSING
        voiceManager.updateState(VoiceState.PROCESSING)
        WakeWordService.updateServiceNotification(context, "JARVIS Processing: $effectiveDirective")

        scope.launch(Dispatchers.IO) {
            repository.addMessage(
                role = "user",
                content = query,
                persona = "JARVIS",
                source = "voice"
            )

            // Fast local intent routing
            isExecuting.set(true)
            val commandResult = try {
                commandRouter.routeAndExecute(effectiveDirective)
            } finally {
                isExecuting.set(false)
            }

            if (commandResult.handled) {
                val responseText = commandResult.speechResponse
                if (responseText.isNotBlank()) {
                    _assistantState.value = VoiceState.EXECUTING_COMMAND
                    _lastResponse.value = responseText

                    repository.addMessage(
                        role = "assistant",
                        content = responseText,
                        persona = "JARVIS",
                        source = "system",
                        responseType = "TOOL_ACTION",
                        executionState = commandResult.status.name
                    )

                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.SPEAKING
                        voiceManager.updateState(VoiceState.SPEAKING)
                        WakeWordService.updateServiceNotification(context, "Speaking: $responseText")
                        voiceManager.speak(responseText, keepMicRunning = true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.COMMAND_CAPTURE
                        voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
                        WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for Command")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    WakeWordService.updateServiceNotification(context, "JARVIS Computing...")
                }

                val messages = buildContextualMessages(effectiveDirective)
                val systemInstruction = buildJarvisSystemInstruction()

                val hasKey = geminiProvider.getEffectiveApiKey().isNotEmpty()
                val rawReply = try {
                    val aiResponse = if (hasKey) {
                        geminiProvider.generateResponse(messages, systemInstruction)
                    } else {
                        offlineProvider.generateResponse(messages, systemInstruction)
                    }

                    if (aiResponse.isSuccess && aiResponse.text.isNotBlank()) {
                        aiResponse.text
                    } else if (hasKey) {
                        val reason = aiResponse.errorMessage?.ifBlank { "Request failed" } ?: "Request failed"
                        Log.w(TAG, "[GEMINI] Online request failed: $reason")
                        "Gemini error: $reason"
                    } else {
                        val offlineRes = offlineProvider.generateResponse(messages, systemInstruction)
                        offlineRes.text.ifBlank { "At your command, Prince." }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[GEMINI] AI processing exception: ${e.message}")
                    if (hasKey) {
                        val reason = e.localizedMessage ?: "Unknown error"
                        "Gemini error: $reason"
                    } else {
                        val offlineRes = offlineProvider.generateResponse(messages, systemInstruction)
                        offlineRes.text.ifBlank { "At your command, Prince." }
                    }
                }

                val cleaned = cleanJarvisSpeech(rawReply)
                val responseText = cleaned.ifBlank { "At your command, Prince." }

                _lastResponse.value = responseText
                repository.addMessage(
                    role = "assistant",
                    content = responseText,
                    persona = "JARVIS",
                    source = "ai",
                    responseType = "INFORMATIONAL"
                )

                withContext(Dispatchers.Main) {
                    _assistantState.value = VoiceState.SPEAKING
                    voiceManager.updateState(VoiceState.SPEAKING)
                    WakeWordService.updateServiceNotification(context, "Speaking response...")
                    voiceManager.speak(responseText, keepMicRunning = true)
                }
            }
        }
    }

    /**
     * Continuous hardware audio capture entry point.
     * Receives 16kHz WAV audio bytes directly from ContinuousMicEngine.
     */
    fun onSpeechAudioCaptured(wavBytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val isGatedInWakeOnly = (_assistantState.value == VoiceState.WAKE_ONLY || _assistantState.value == VoiceState.WAKE_LISTENING)
        val hasRecentWake = (currentWakeSession != null && (now - lastWakeWordTimestamp) < 6500L)

        // STRICT WAKE-WORD GATING (Section 1):
        // When in WAKE_ONLY mode, normal speech without confirmed wake word must NEVER be treated as a command
        // and must NEVER be uploaded to Gemini.
        if (isGatedInWakeOnly && !hasRecentWake) {
            Log.i("WAKE", "[WAKE] gated: normal speech audio ignored in WAKE_ONLY mode without wake-word confirmation")
            return
        }

        commandTimeoutJob?.cancel()

        // Barge-in check: If user speaks over TTS playback, stop speaking immediately
        if (_assistantState.value == VoiceState.SPEAKING) {
            voiceManager.stopSpeaking(isBargeIn = true)
        }

        _assistantState.value = VoiceState.PROCESSING
        voiceManager.updateState(VoiceState.PROCESSING)
        WakeWordService.updateServiceNotification(context, "JARVIS Recognizing Speech...")

        scope.launch(Dispatchers.IO) {
            val systemInstruction = buildJarvisSystemInstruction()
            val result = try {
                geminiProvider.processSpeechAudio(wavBytes, systemInstruction = systemInstruction)
            } catch (e: Exception) {
                Log.e(TAG, "[GEMINI] Speech processing exception: ${e.message}")
                com.example.ai.SpeechProcessingResult(
                    isSuccess = false,
                    errorMessage = e.localizedMessage ?: "Speech processing error",
                    status = com.example.ai.GeminiStatus.SERVER_ERROR
                )
            }

            val hasKey = geminiProvider.getEffectiveApiKey().isNotEmpty()

            // 1. Real Gemini failure
            if (!result.isSuccess) {
                if (hasKey) {
                    val reason = result.errorMessage?.ifBlank { "Voice request failed" } ?: "Voice request failed"
                    val errorReply = if (reason.contains("503") || reason.contains("demand", ignoreCase = true) || reason.contains("busy", ignoreCase = true)) {
                        "Gemini servers are experiencing high demand right now. Please try again in a moment."
                    } else if (reason.contains("429") || reason.contains("quota", ignoreCase = true)) {
                        "Gemini rate limit reached. Please wait a moment before trying again."
                    } else {
                        "I had trouble connecting to Gemini. Please try again."
                    }
                    Log.w(TAG, "[GEMINI] Voice request failed: $reason")

                    _lastResponse.value = errorReply
                    repository.addMessage(
                        role = "assistant",
                        content = errorReply,
                        persona = "JARVIS",
                        source = "system",
                        responseType = "INFORMATIONAL"
                    )

                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.SPEAKING
                        voiceManager.updateState(VoiceState.SPEAKING)
                        WakeWordService.updateServiceNotification(context, errorReply)
                        voiceManager.speak(errorReply, keepMicRunning = true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (isAssistantActive) {
                            _assistantState.value = VoiceState.WAKE_ONLY
                            voiceManager.updateState(VoiceState.WAKE_ONLY)
                            MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
                            WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for 'Hey Jarvis'")
                        }
                    }
                }
                return@launch
            }

            // 2. Genuine silence or blank transcript: stay in command listening if in active session, else wake listening
            var effectiveQuery = result.transcript.trim()
            if (result.isActualSilence || effectiveQuery.isBlank()) {
                val postAudioNow = SystemClock.elapsedRealtime()
                if (postAudioNow - lastWakeWordTimestamp < 3500L) {
                    // Silence right after wake word: stay in COMMAND_CAPTURE silently
                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.COMMAND_CAPTURE
                        voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
                        WakeWordService.updateServiceNotification(context, "JARVIS • Listening for Command")
                    }
                    return@launch
                } else {
                    withContext(Dispatchers.Main) {
                        if (isAssistantActive) {
                            _assistantState.value = VoiceState.WAKE_ONLY
                            voiceManager.updateState(VoiceState.WAKE_ONLY)
                            MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
                            WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for 'Hey Jarvis'")
                        }
                    }
                    return@launch
                }
            }

            // Check if user utterance was just the wake word ("Hey Jarvis")
            val normalizedCheck = commandRouter.normalize(effectiveQuery)
            if (normalizedCheck == "wake_call") {
                Log.i(TAG, "Recognized wake call; remaining silently in COMMAND_CAPTURE")
                withContext(Dispatchers.Main) {
                    _assistantState.value = VoiceState.COMMAND_CAPTURE
                    voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
                    MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.COMMAND_MODE)
                    WakeWordService.updateServiceNotification(context, "JARVIS • Listening for Command")
                }
                startCommandTimeout()
                return@launch
            }

            _assistantState.value = VoiceState.PROCESSING
            voiceManager.updateState(VoiceState.PROCESSING)
            _lastDirective.value = effectiveQuery

            // Record user speech directive in repository
            repository.addMessage(
                role = "user",
                content = effectiveQuery,
                persona = "JARVIS",
                source = "voice"
            )

            // 3. Fast local intent routing (handles "Hey Jarvis, open YouTube" -> "open youtube")
            val commandResult = commandRouter.routeAndExecute(effectiveQuery)
            if (commandResult.handled) {
                val responseText = commandResult.speechResponse
                if (responseText.isNotBlank()) {
                    _assistantState.value = VoiceState.EXECUTING_COMMAND
                    _lastResponse.value = responseText

                    repository.addMessage(
                        role = "assistant",
                        content = responseText,
                        persona = "JARVIS",
                        source = "system",
                        responseType = "TOOL_ACTION",
                        executionState = if (commandResult.success) "SUCCESS" else "FAILED"
                    )

                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.SPEAKING
                        voiceManager.updateState(VoiceState.SPEAKING)
                        WakeWordService.updateServiceNotification(context, "JARVIS: $responseText")
                        voiceManager.speak(responseText, keepMicRunning = true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _assistantState.value = VoiceState.COMMAND_CAPTURE
                        voiceManager.updateState(VoiceState.COMMAND_CAPTURE)
                        WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for Command")
                    }
                }
            } else {
                // Informational or complex query passed to Gemini
                val messages = buildContextualMessages(effectiveQuery)
                val rawReply = if (hasKey) {
                    val aiResponse = geminiProvider.generateResponse(messages, systemInstruction)
                    if (aiResponse.isSuccess && aiResponse.text.isNotBlank()) {
                        aiResponse.text
                    } else {
                        val offlineRes = offlineProvider.generateResponse(messages, systemInstruction)
                        if (offlineRes.isSuccess && offlineRes.text.isNotBlank()) {
                            offlineRes.text
                        } else {
                            val reason = aiResponse.errorMessage ?: ""
                            if (reason.contains("503") || reason.contains("demand", ignoreCase = true) || reason.contains("busy", ignoreCase = true)) {
                                "Gemini is currently experiencing high demand. At your service, Prince."
                            } else {
                                "I was unable to reach Gemini, Prince. At your service."
                            }
                        }
                    }
                } else {
                    val offlineRes = offlineProvider.generateResponse(messages, systemInstruction)
                    offlineRes.text.ifBlank { "At your command, Prince." }
                }

                val cleaned = cleanJarvisSpeech(rawReply)
                val responseText = cleaned.ifBlank { "At your command, Prince." }

                _lastResponse.value = responseText
                repository.addMessage(
                    role = "assistant",
                    content = responseText,
                    persona = "JARVIS",
                    source = "ai",
                    responseType = "INFORMATIONAL"
                )

                withContext(Dispatchers.Main) {
                    _assistantState.value = VoiceState.SPEAKING
                    voiceManager.updateState(VoiceState.SPEAKING)
                    WakeWordService.updateServiceNotification(context, "Speaking response...")
                    voiceManager.speak(responseText, keepMicRunning = true)
                }
            }
        }
    }

    /**
     * Executes when TextToSpeech completes speaking.
     * Automatically transitions seamlessly back to WAKE_ONLY!
     */
    private fun onTtsCompleted() {
        currentWakeSession = null
        if (isAssistantActive) {
            _assistantState.value = VoiceState.WAKE_ONLY
            voiceManager.updateState(VoiceState.WAKE_ONLY)
            MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
            WakeWordService.updateServiceNotification(context, "JARVIS Active • Listening for 'Hey Jarvis'")
        } else {
            _assistantState.value = VoiceState.IDLE
            voiceManager.updateState(VoiceState.IDLE)
            MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.STOPPED)
            WakeWordService.updateServiceNotification(context, "JARVIS Standby")
        }
    }

    private fun onSpeechRecognitionError(errorCode: Int) {
        Log.d(TAG, "Speech status note: $errorCode")
        if (isAssistantActive && _assistantState.value != VoiceState.SPEAKING) {
            _assistantState.value = VoiceState.RECOVERING
            voiceManager.updateState(VoiceState.RECOVERING)
            scope.launch {
                delay(300L)
                if (isAssistantActive && _assistantState.value == VoiceState.RECOVERING) {
                    _assistantState.value = VoiceState.WAKE_ONLY
                    voiceManager.updateState(VoiceState.WAKE_ONLY)
                    MicrophoneOwnershipCoordinator.getInstance(context).setMicrophoneState(MicrophoneState.WAKE_WORD_MODE)
                }
            }
        }
    }

    fun processManualDirective(text: String) {
        onSpeechCaptured(text)
    }

    fun applyVoiceStyle(style: com.example.voice.JarvisVoiceStyle) {
        voiceManager.applyVoiceStyle(style)
    }

    fun speakAloud(text: String) {
        voiceManager.speak(text)
    }
}
