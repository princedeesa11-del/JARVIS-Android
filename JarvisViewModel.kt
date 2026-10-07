package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApp
import com.example.ai.*
import com.example.data.local.entity.*
import com.example.tools.ToolRegistry
import com.example.ui.components.OrbState
import com.example.voice.SupportedLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class VisionState(
    val selectedBitmap: Bitmap? = null,
    val prompt: String = "Analyze this image and extract all text, objects, and key insights.",
    val isAnalyzing: Boolean = false,
    val resultText: String = "",
    val error: String? = null
)

data class WebStudioState(
    val activeProject: WebProjectItem? = null,
    val currentFileName: String = "index.html",
    val editorContent: String = "",
    val isPreviewActive: Boolean = false,
    val previewHtml: String = "",
    val availableFiles: List<String> = emptyList(),
    val exportStatus: String? = null,
    val buildStatus: String = "READY"
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApp
    private val repository = app.repository
    private val toolExecutor = app.toolExecutor
    private val voiceManager = app.voiceManager
    private val geminiProvider = app.geminiAIProvider
    private val offlineProvider = app.localOfflineProvider
    private val webProjectManager = app.webProjectManager
    val geminiConfigManager = app.geminiConfigManager

    // Current Persona
    private val _activePersona = MutableStateFlow(Persona.JARVIS)
    val activePersona: StateFlow<Persona> = _activePersona.asStateFlow()

    // Orb State
    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    // Streaming response
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _activeExecutingTool = MutableStateFlow<String?>(null)
    val activeExecutingTool: StateFlow<String?> = _activeExecutingTool.asStateFlow()

    val contextManager: ConversationContextManager = offlineProvider.getContextManager()
    val micCoordinator = com.example.voice.MicrophoneOwnershipCoordinator.getInstance(app)
    val pendingConfirmation = toolExecutor.automationEngine.activePendingConfirmation
    val liveSessionManager = GeminiLiveSessionManager(
        context = app,
        geminiProvider = geminiProvider,
        offlineProvider = offlineProvider,
        toolExecutor = toolExecutor,
        voiceManager = voiceManager,
        micCoordinator = micCoordinator
    )
    val liveSessionState = liveSessionManager.sessionState

    // TTS Enabled
    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    // Appearance & Orb Customization State
    private val prefs = app.getSharedPreferences("jarvis_appearance_prefs", Context.MODE_PRIVATE)
    private val _orbStyle = MutableStateFlow(
        com.example.ui.theme.OrbStyle.fromId(prefs.getString("pref_orb_style", com.example.ui.theme.OrbStyle.PULSE_REACTOR.id) ?: com.example.ui.theme.OrbStyle.PULSE_REACTOR.id)
    )
    val orbStyle: StateFlow<com.example.ui.theme.OrbStyle> = _orbStyle.asStateFlow()

    private val _orbColorTheme = MutableStateFlow(
        com.example.ui.theme.OrbColorTheme.fromId(prefs.getString("pref_orb_color", com.example.ui.theme.OrbColorTheme.JARVIS_DEFAULT.id) ?: com.example.ui.theme.OrbColorTheme.JARVIS_DEFAULT.id)
    )
    val orbColorTheme: StateFlow<com.example.ui.theme.OrbColorTheme> = _orbColorTheme.asStateFlow()

    private val _orbSizeDp = MutableStateFlow(
        prefs.getInt("pref_orb_size_dp", 140).coerceIn(80, 240)
    )
    val orbSizeDp: StateFlow<Int> = _orbSizeDp.asStateFlow()

    private val _useOrbOnHome = MutableStateFlow(
        prefs.getBoolean("pref_use_orb_on_home", true)
    )
    val useOrbOnHome: StateFlow<Boolean> = _useOrbOnHome.asStateFlow()

    // Voice Persona & Pitch/Rate Style (INDIAN_MALE, JARVIS, MAYA_STYLE, VENOM_STYLE)
    val activeVoiceStyle: StateFlow<com.example.voice.JarvisVoiceStyle> = voiceManager.activeVoiceStyle
    val selectedVoiceName: StateFlow<String> = voiceManager.selectedVoiceName
    val ttsEngineName: StateFlow<String> = voiceManager.ttsEngineName
    val availableVoices: StateFlow<List<com.example.voice.tts.TtsVoiceItem>> = voiceManager.availableVoices
    val speechPitch: StateFlow<Float> = voiceManager.speechPitch
    val speechRate: StateFlow<Float> = voiceManager.speechRate

    fun setVoiceStyle(style: com.example.voice.JarvisVoiceStyle) {
        voiceManager.applyVoiceStyle(style)
    }

    fun testVoiceStyle(style: com.example.voice.JarvisVoiceStyle) {
        setVoiceStyle(style)
        voiceManager.speak(style.testPhrase)
    }

    fun selectCustomVoice(voiceName: String) {
        voiceManager.selectCustomSystemVoice(voiceName)
    }

    fun clearCustomVoice() {
        voiceManager.clearCustomSystemVoice()
    }

    fun setSpeechPitch(pitch: Float) {
        voiceManager.setPitch(pitch)
    }

    fun setSpeechRate(rate: Float) {
        voiceManager.setSpeechRate(rate)
    }

    fun testCurrentVoice(sampleText: String? = null) {
        voiceManager.testCurrentVoice(sampleText)
    }

    fun setOrbStyle(style: com.example.ui.theme.OrbStyle) {
        _orbStyle.value = style
        prefs.edit().putString("pref_orb_style", style.id).apply()
    }

    fun setOrbColorTheme(colorTheme: com.example.ui.theme.OrbColorTheme) {
        _orbColorTheme.value = colorTheme
        prefs.edit().putString("pref_orb_color", colorTheme.id).apply()
    }

    fun setOrbSizeDp(size: Int) {
        val clamped = size.coerceIn(80, 240)
        _orbSizeDp.value = clamped
        prefs.edit().putInt("pref_orb_size_dp", clamped).apply()
    }

    fun setUseOrbOnHome(enabled: Boolean) {
        _useOrbOnHome.value = enabled
        prefs.edit().putBoolean("pref_use_orb_on_home", enabled).apply()
    }

    // Settings & Personal Preferences
    private val appPrefs = app.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    val selectedModel: StateFlow<String> = geminiConfigManager.selectedModel
    val selectedAudioModel: StateFlow<String> = geminiConfigManager.selectedAudioModel
    val customApiKey: StateFlow<String> = geminiConfigManager.customApiKey
    val geminiStatus: StateFlow<com.example.ai.GeminiStatus> = geminiConfigManager.connectionStatus
    val keySource: com.example.ai.KeySource get() = geminiConfigManager.getKeySource()

    init {
        // Authoritative config is automatically loaded by GeminiConfigManager in JarvisApp
    }

    private val _screenCaptureRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val screenCaptureRequest: SharedFlow<Unit> = _screenCaptureRequest.asSharedFlow()

    fun requestScreenCapture() {
        _screenCaptureRequest.tryEmit(Unit)
    }

    fun onScreenCaptured(bitmap: Bitmap, customPrompt: String? = null) {
        val prompt = customPrompt ?: "Look at this captured device screen and summarize what is visible, including any open applications, visible text, or important status information."
        _visionState.value = _visionState.value.copy(selectedBitmap = bitmap, prompt = prompt)
        analyzeVisionImage(bitmap, prompt)
    }

    // Vision State
    private val _visionState = MutableStateFlow(VisionState())
    val visionState: StateFlow<VisionState> = _visionState.asStateFlow()

    // Web Studio State
    private val _webStudioState = MutableStateFlow(WebStudioState())
    val webStudioState: StateFlow<WebStudioState> = _webStudioState.asStateFlow()

    // Database flows
    val messages: StateFlow<List<ConversationMessage>> = repository.getMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryItem>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderItem>> = repository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val webProjects: StateFlow<List<WebProjectItem>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val automationRules: StateFlow<List<AutomationRule>> = repository.allAutomationRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toolExecutions: StateFlow<List<ToolExecutionRecord>> = repository.recentToolExecutions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Voice State pass-through
    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val rmsLevel: StateFlow<Float> = voiceManager.rmsLevel
    val activeLanguage: StateFlow<SupportedLanguage> = voiceManager.activeLanguage
    val recognizedText: StateFlow<String> = voiceManager.recognizedText
    val voiceState: StateFlow<com.example.voice.VoiceState> = app.jarvisVoiceAssistant.assistantState
    val wakeWordStatus: StateFlow<com.example.voice.wakeword.WakeWordEngineStatus> =
        com.example.service.WakeWordService.engineStatus

    private val sharedPrefs = app.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    private val _wakeSensitivity = MutableStateFlow(
        com.example.voice.wakeword.WakeSensitivity.fromString(sharedPrefs.getString("pref_wake_sensitivity", "MEDIUM"))
    )
    val wakeSensitivity: StateFlow<com.example.voice.wakeword.WakeSensitivity> = _wakeSensitivity.asStateFlow()

    private val _isBackgroundWakeEnabled = MutableStateFlow(
        sharedPrefs.getBoolean("pref_wake_background", true)
    )
    val isBackgroundWakeEnabled: StateFlow<Boolean> = _isBackgroundWakeEnabled.asStateFlow()

    private val _isBargeInEnabled = MutableStateFlow(
        sharedPrefs.getBoolean("pref_wake_barge_in", true)
    )
    val isBargeInEnabled: StateFlow<Boolean> = _isBargeInEnabled.asStateFlow()

    private val _wakePhraseProfile = MutableStateFlow(
        com.example.voice.wakeword.WakePhraseProfile.fromString(sharedPrefs.getString("pref_wake_phrase_profile", "MULTI_PHRASE"))
    )
    val wakePhraseProfile: StateFlow<com.example.voice.wakeword.WakePhraseProfile> = _wakePhraseProfile.asStateFlow()

    private val _isFalseTriggerProtectionEnabled = MutableStateFlow(
        sharedPrefs.getBoolean("pref_wake_false_trigger_protection", true)
    )
    val isFalseTriggerProtectionEnabled: StateFlow<Boolean> = _isFalseTriggerProtectionEnabled.asStateFlow()

    fun setWakeSensitivity(sensitivity: com.example.voice.wakeword.WakeSensitivity) {
        sharedPrefs.edit().putString("pref_wake_sensitivity", sensitivity.name).apply()
        _wakeSensitivity.value = sensitivity
        voiceManager.setSensitivity(sensitivity)
    }

    fun setWakePhraseProfile(profile: com.example.voice.wakeword.WakePhraseProfile) {
        sharedPrefs.edit().putString("pref_wake_phrase_profile", profile.name).apply()
        _wakePhraseProfile.value = profile
        voiceManager.setWakePhraseProfile(profile)
    }

    fun setBackgroundWake(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("pref_wake_background", enabled).apply()
        _isBackgroundWakeEnabled.value = enabled
    }

    fun setBargeIn(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("pref_wake_barge_in", enabled).apply()
        _isBargeInEnabled.value = enabled
        voiceManager.setBargeInEnabled(enabled)
    }

    fun setFalseTriggerProtection(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("pref_wake_false_trigger_protection", enabled).apply()
        _isFalseTriggerProtectionEnabled.value = enabled
        voiceManager.setFalseTriggerProtection(enabled)
    }

    init {
        // Sync Orb state with assistant state machine
        viewModelScope.launch {
            app.jarvisVoiceAssistant.assistantState.collect { state ->
                _orbState.value = when (state) {
                    com.example.voice.VoiceState.COMMAND_CAPTURE,
                    com.example.voice.VoiceState.WAKE_DETECTED,
                    com.example.voice.VoiceState.COMMAND_LISTENING,
                    com.example.voice.VoiceState.WAKE_CONFIRMED,
                    com.example.voice.VoiceState.LISTENING,
                    com.example.voice.VoiceState.LISTENING_AGAIN -> OrbState.LISTENING
                    com.example.voice.VoiceState.RECOVERING,
                    com.example.voice.VoiceState.RECOGNIZING,
                    com.example.voice.VoiceState.PROCESSING -> OrbState.THINKING
                    com.example.voice.VoiceState.EXECUTING_COMMAND,
                    com.example.voice.VoiceState.EXECUTING -> OrbState.EXECUTING
                    com.example.voice.VoiceState.SPEAKING -> OrbState.SPEAKING
                    com.example.voice.VoiceState.ERROR -> OrbState.ERROR
                    else -> if (_isBusy.value) OrbState.THINKING else OrbState.IDLE
                }
            }
        }

        // Seed initial welcoming message if conversation is empty
        viewModelScope.launch {
            val recents = repository.getRecentMessages(1)
            if (recents.isEmpty()) {
                repository.addMessage(
                    role = "assistant",
                    content = Persona.JARVIS.greeting,
                    persona = Persona.JARVIS.displayName
                )
            }
        }
    }

    fun switchPersona(persona: Persona) {
        _activePersona.value = persona
        when (persona) {
            Persona.JARVIS -> voiceManager.applyVoiceStyle(voiceManager.activeVoiceStyle.value)
        }
        viewModelScope.launch {
            repository.addMessage(
                role = "assistant",
                content = persona.greeting,
                persona = persona.displayName
            )
            if (_isTtsEnabled.value) {
                voiceManager.speak(persona.greeting)
            }
        }
    }

    fun setLanguage(language: SupportedLanguage) {
        voiceManager.setLanguage(language)
    }

    fun toggleTts() {
        _isTtsEnabled.value = !_isTtsEnabled.value
        if (!_isTtsEnabled.value) {
            voiceManager.stopSpeaking()
        }
    }

    private var lastMicClickTime = 0L

    fun toggleVoiceListening() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMicClickTime < 450L) {
            return
        }
        lastMicClickTime = now

        val isListening = voiceManager.isListening.value ||
            app.jarvisVoiceAssistant.assistantState.value == com.example.voice.AssistantState.LISTENING ||
            app.jarvisVoiceAssistant.assistantState.value == com.example.voice.AssistantState.LISTENING_AGAIN

        if (isListening) {
            app.jarvisVoiceAssistant.pauseAssistant()
        } else {
            com.example.service.WakeWordService.start(app)
            app.jarvisVoiceAssistant.startAssistant(continuous = true)
        }
    }

    fun startJarvisWakeWord() {
        com.example.service.WakeWordService.start(app)
        app.jarvisVoiceAssistant.startAssistant(continuous = true)
    }

    fun stopJarvisWakeWord() {
        com.example.service.WakeWordService.stop(app)
        app.jarvisVoiceAssistant.stopAssistant()
    }

    fun stopSpeaking() {
        voiceManager.stopSpeaking()
    }

    fun setCustomApiKey(key: String) {
        geminiConfigManager.saveApiKey(key)
    }

    fun setModel(model: String) {
        geminiConfigManager.saveSelectedModel(model)
        geminiProvider.updateModel(model)
    }

    fun setAudioModel(model: String) {
        geminiConfigManager.saveSelectedAudioModel(model)
        geminiProvider.updateAudioModel(model)
    }

    suspend fun testGeminiApiKey(key: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        geminiProvider.testApiKey(key)
    }

    fun sendMessage(userText: String, isVoice: Boolean = false) {
        val query = userText.trim()
        if (query.isBlank() || _isBusy.value) return

        _isBusy.value = true
        _streamingText.value = ""
        val source = if (isVoice) "voice" else "text"

        viewModelScope.launch(Dispatchers.IO) {
            // Check fast local command routing
            val localResult = app.commandRouter.routeAndExecute(query)
            if (localResult.handled) {
                _isBusy.value = false
                _orbState.value = OrbState.EXECUTING
                repository.addMessage(
                    role = "assistant",
                    content = localResult.speechResponse,
                    persona = _activePersona.value.displayName,
                    source = source,
                    responseType = "TOOL_ACTION",
                    executionState = if (localResult.success) "COMPLETED" else "FAILED"
                )
                // AUTOMATIC TEXT-TO-SPEECH
                voiceManager.speak(localResult.speechResponse)
                return@launch
            }
            // Check if there is an active pending confirmation (e.g. user says yes/confirm or no/cancel)
            val pending = toolExecutor.automationEngine.activePendingConfirmation.value
            if (pending != null) {
                val clean = query.trim().lowercase()
                val positiveConfirm = listOf("yes", "confirm", "proceed", "send", "send it", "do it", "sure", "ok", "okay", "haan", "sahi hai")
                val negativeCancel = listOf("no", "cancel", "don't send", "abort", "stop", "never mind", "nahi", "ruk")

                if (positiveConfirm.any { clean == it || clean.startsWith(it) }) {
                    confirmPendingAction(true)
                    return@launch
                } else if (negativeCancel.any { clean == it || clean.startsWith(it) }) {
                    confirmPendingAction(false)
                    return@launch
                }
            }

            // Check if command triggers a saved custom workflow routine
            val matchedWorkflow = toolExecutor.workflowStore.findWorkflowForCommand(query)
            if (matchedWorkflow != null) {
                _isBusy.value = true
                _orbState.value = OrbState.EXECUTING
                voiceManager.updateState(com.example.voice.VoiceState.EXECUTING)
                val call = ToolCallRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "run_multi_step_workflow",
                    arguments = mapOf(
                        "appName" to matchedWorkflow.name,
                        "workflowStepsJson" to matchedWorkflow.arguments
                    )
                )
                val res = toolExecutor.execute(call)
                _isBusy.value = false
                _orbState.value = OrbState.IDLE
                repository.addMessage(
                    role = "assistant",
                    content = res.output,
                    toolCall = "run_multi_step_workflow",
                    toolResult = res.output,
                    persona = _activePersona.value.displayName,
                    source = source,
                    responseType = if (res.statusCode == "USER_ACTION_REQUIRED") "USER_ACTION_REQUIRED" else "TOOL_ACTION",
                    executionState = res.statusCode ?: (if (res.isSuccess) "SUCCESS" else "FAILED")
                )
                if (_isTtsEnabled.value && res.output.isNotBlank()) {
                    voiceManager.speak(res.output)
                } else {
                    com.example.service.WakeWordService.resume(app)
                }
                return@launch
            }

            // Save user message to database
            repository.addMessage(
                role = "user",
                content = query,
                persona = _activePersona.value.displayName,
                source = source
            )

            val lower = query.lowercase()
            if ((lower.contains("look at") || lower.contains("summarize") || lower.contains("what is on") || lower.contains("what's on") || lower.contains("read")) && lower.contains("screen")) {
                requestScreenCapture()
                val reply = "Initiating display screen capture. Please confirm screen capture permission in the system dialog to proceed with vision analysis."
                repository.addMessage(
                    role = "assistant",
                    content = reply,
                    persona = _activePersona.value.displayName,
                    source = source,
                    responseType = "USER_ACTION_REQUIRED",
                    executionState = "USER_ACTION_REQUIRED"
                )
                _isBusy.value = false
                if (_isTtsEnabled.value) {
                    voiceManager.speak(reply)
                }
                return@launch
            }

            // Short-term conversational context check
            val contextResolution = contextManager.resolveFollowUp(query)
            if (contextResolution is ConversationContextManager.ContextualResolution.NeedsSlot) {
                repository.addMessage(
                    role = "assistant",
                    content = contextResolution.promptUser,
                    persona = _activePersona.value.displayName,
                    source = source,
                    responseType = "CONFIRMATION_REQUIRED",
                    executionState = "SUCCESS"
                )
                _isBusy.value = false
                if (_isTtsEnabled.value) {
                    voiceManager.speak(contextResolution.promptUser)
                } else {
                    com.example.service.WakeWordService.resume(app)
                }
                return@launch
            }

            // Gather recent memory facts to supply into context
            val recalledMemories = repository.getRecentMemories(10)
            val memoryContext = recalledMemories.joinToString("\n") { "• [${it.category}] ${it.key}: ${it.content}" }

            val pUserName = appPrefs.getString("user_display_name", "Prince")?.ifBlank { "Prince" } ?: "Prince"
            val pGender = appPrefs.getString("user_gender_preference", "") ?: ""
            val pFavSong = appPrefs.getString("pref_favorite_song", "") ?: ""
            val pMusicApp = appPrefs.getString("pref_music_service", "") ?: ""
            val personalContext = buildString {
                append("• User name: $pUserName\n")
                if (pGender.isNotBlank() && pGender != "Prefer not to say") append("• User gender preference: $pGender\n")
                if (pFavSong.isNotBlank()) append("• Favorite song: $pFavSong\n")
                if (pMusicApp.isNotBlank()) append("• Preferred music app: $pMusicApp\n")
            }

            val systemInstruction = _activePersona.value.buildSystemInstruction(memoryContext, personalContext)

            // Prepare conversation history
            val conversationHistory = repository.getRecentMessages(10).reversed()
            val aiMessages = conversationHistory.map {
                AIMessage(role = it.role, text = it.content)
            }

            val toolsSchema = ToolRegistry.buildGeminiToolsJson()

            // Select provider: if custom_api_key is not blank, always use GeminiAIProvider
            val customKey = geminiConfigManager.customApiKey.value.trim().ifEmpty {
                appPrefs.getString("custom_api_key", "")?.trim() ?: ""
            }
            val keyExists = customKey.isNotBlank() || geminiConfigManager.hasValidKeyConfigured()
            val provider: AIProvider = if (keyExists) geminiProvider else offlineProvider

            var response = provider.generateResponse(aiMessages, systemInstruction, toolsSchema)

            // Do not use offline provider when key exists. Show real informative error instead.
            if (keyExists && !response.isSuccess) {
                val reason = response.errorMessage?.ifBlank { "Request failed" } ?: "Request failed"
                android.util.Log.w("JarvisViewModel", "[GEMINI] Request failed: $reason")
                val formattedMessage = if (reason.contains("503") || reason.contains("demand", ignoreCase = true) || reason.contains("busy", ignoreCase = true)) {
                    "Gemini servers are currently experiencing high demand. Please try again in a moment."
                } else if (reason.contains("429") || reason.contains("quota", ignoreCase = true)) {
                    "Gemini rate limit reached. Please wait a moment before trying again."
                } else {
                    "Gemini error: $reason"
                }
                response = response.copy(
                    text = formattedMessage,
                    isSuccess = false
                )
            }

            var assistantReply = response.text
            val toolCalls = response.toolCalls

            var toolCallName: String? = null
            var toolResultOutput: String? = null
            var responseType = "INFORMATIONAL"
            var executionState = "SUCCESS"
            var requiredPermission: String? = null

            if (toolCalls.isNotEmpty()) {
                for (toolCall in toolCalls) {
                    toolCallName = toolCall.name
                    _activeExecutingTool.value = toolCall.name
                    _orbState.value = OrbState.EXECUTING
                    voiceManager.updateState(com.example.voice.VoiceState.EXECUTING)

                    val result = toolExecutor.execute(toolCall)
                    _activeExecutingTool.value = null
                    toolResultOutput = result.output

                    // Multi-turn Gemini Tool Loop: feed verified tool result back to Gemini to synthesize natural response
                    val followUpHistory = aiMessages.toMutableList().apply {
                        add(AIMessage(role = "model", text = "", toolCall = toolCall))
                        add(AIMessage(role = "user", text = "", toolCall = toolCall, toolResult = result.output))
                    }
                    val synthesized = provider.generateResponse(followUpHistory, systemInstruction, null)
                    if (synthesized.isSuccess && synthesized.text.isNotBlank()) {
                        assistantReply = synthesized.text
                    }

                    // Update contextual state
                    contextManager.updateAction(toolCall.name, toolCall.arguments)
                    if (toolCall.name == "launch_app") {
                        val appArg = toolCall.arguments["appName"] as? String ?: ""
                        if (appArg.isNotBlank()) contextManager.updateApp(appArg)
                    }

                    // Determine response type and execution state
                    if (toolExecutor.automationEngine.activePendingConfirmation.value != null ||
                        result.statusCode == "USER_ACTION_REQUIRED" ||
                        result.output.contains("USER_ACTION_REQUIRED")) {
                        responseType = "USER_ACTION_REQUIRED"
                        executionState = "USER_ACTION_REQUIRED"
                    } else if (result.output.contains("Permission required", ignoreCase = true) ||
                        result.output.contains("READ_CALL_LOG", ignoreCase = true)) {
                        responseType = "PERMISSION_REQUIRED"
                        requiredPermission = android.Manifest.permission.READ_CALL_LOG
                        executionState = "PERMISSION_REQUIRED"
                    } else if (toolCall.name == "control_wifi" || toolCall.name == "control_bluetooth" || result.output.contains("Settings", ignoreCase = true)) {
                        responseType = "USER_ACTION_REQUIRED"
                        executionState = "USER_ACTION_REQUIRED"
                    } else if (!result.isSuccess) {
                        responseType = "ERROR"
                        executionState = "FAILED"
                    } else {
                        responseType = "TOOL_ACTION"
                        executionState = "SUCCESS"
                    }

                    // If a web project was created, automatically switch active project in WebStudioState
                    if (toolCall.name == "create_web_project" && result.isSuccess) {
                        val createdId = (result.data["projectId"] as? Number)?.toLong()
                        if (createdId != null) {
                            loadWebProject(createdId)
                        }
                    }

                    if (toolCall.name == "capture_screen") {
                        requestScreenCapture()
                    }
                }
            } else {
                responseType = "INFORMATIONAL"
                executionState = "SUCCESS"
            }

            // Save assistant response
            val finalContent = buildString {
                if (assistantReply.isNotBlank()) append(assistantReply)
                if (toolResultOutput != null && assistantReply.isBlank()) {
                    append(toolResultOutput)
                }
            }.trim()

            repository.addMessage(
                role = "assistant",
                content = finalContent,
                toolCall = toolCallName,
                toolResult = toolResultOutput,
                persona = _activePersona.value.displayName,
                source = source,
                responseType = responseType,
                executionState = executionState,
                requiredPermission = requiredPermission
            )

            _isBusy.value = false

            // Voice response
            if (_isTtsEnabled.value && finalContent.isNotBlank()) {
                voiceManager.speak(finalContent)
            } else {
                com.example.service.WakeWordService.resume(app)
            }
        }
    }

    fun retryMessage(message: ConversationMessage) {
        if (_isBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val recents = repository.getRecentMessages(10)
            val userDirective = recents.findLast { it.role == "user" && it.timestamp <= message.timestamp }?.content
                ?: message.content
            sendMessage(userDirective, isVoice = message.source == "voice")
        }
    }

    fun speakText(text: String) {
        voiceManager.speak(text)
    }

    fun cancelPendingAction() {
        confirmPendingAction(false)
    }

    fun confirmPendingAction(confirmed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _orbState.value = OrbState.EXECUTING
            voiceManager.updateState(com.example.voice.VoiceState.EXECUTING)

            val toolCall = ToolCallRequest(
                id = java.util.UUID.randomUUID().toString(),
                name = "confirm_pending_action",
                arguments = mapOf("confirmed" to confirmed)
            )
            val result = toolExecutor.execute(toolCall)
            _isBusy.value = false
            _orbState.value = OrbState.IDLE

            repository.addMessage(
                role = "assistant",
                content = result.output,
                toolCall = "confirm_pending_action",
                toolResult = result.output,
                persona = _activePersona.value.displayName,
                responseType = if (confirmed) "TOOL_ACTION" else "INFORMATIONAL",
                executionState = if (result.isSuccess) "SUCCESS" else "CANCELLED"
            )

            if (_isTtsEnabled.value && result.output.isNotBlank()) {
                voiceManager.speak(result.output)
            } else {
                com.example.service.WakeWordService.resume(app)
            }
        }
    }

    fun startLiveSession() {
        val memoryContext = memories.value.take(10).joinToString("\n") { "• [${it.category}] ${it.key}: ${it.content}" }
        val systemInstruction = _activePersona.value.buildSystemInstruction(memoryContext)
        liveSessionManager.startLiveSession(systemInstruction)
    }

    fun stopLiveSession() {
        liveSessionManager.stopLiveSession()
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearChat()
            repository.addMessage(
                role = "assistant",
                content = _activePersona.value.greeting,
                persona = _activePersona.value.displayName
            )
        }
    }

    // Vision Analysis
    fun setVisionImage(bitmap: Bitmap?) {
        _visionState.value = _visionState.value.copy(selectedBitmap = bitmap, resultText = "", error = null)
    }

    fun setVisionPrompt(prompt: String) {
        _visionState.value = _visionState.value.copy(prompt = prompt)
    }

    fun analyzeVisionImage(targetBitmap: Bitmap? = null, customPrompt: String? = null) {
        val bitmap = targetBitmap ?: _visionState.value.selectedBitmap ?: return
        val prompt = customPrompt ?: _visionState.value.prompt.ifBlank { "Analyze this image and describe everything in detail." }

        _visionState.value = _visionState.value.copy(selectedBitmap = bitmap, prompt = prompt, isAnalyzing = true, error = null, resultText = "")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val response = geminiProvider.analyzeVision(prompt, base64)
                if (response.isSuccess) {
                    _visionState.value = _visionState.value.copy(
                        isAnalyzing = false,
                        resultText = response.text
                    )
                    repository.addMessage(
                        role = "assistant",
                        content = "[VISION SUMMARY]:\n${response.text}",
                        persona = _activePersona.value.displayName
                    )
                    if (_isTtsEnabled.value) {
                        voiceManager.speak(response.text.take(300))
                    }
                } else {
                    // Fallback to local OCR if network fails
                    val ocrEngine = com.example.vision.OcrEngine()
                    val ocrResult = ocrEngine.recognizeText(bitmap)
                    val resultText = if (ocrResult.isSuccess && ocrResult.fullText.isNotBlank()) {
                        "Local OCR text extracted from screen:\n\n" + ocrResult.fullText.take(600)
                    } else {
                        response.errorMessage ?: "Vision analysis failed"
                    }
                    _visionState.value = _visionState.value.copy(
                        isAnalyzing = false,
                        resultText = resultText
                    )
                    repository.addMessage(
                        role = "assistant",
                        content = resultText,
                        persona = _activePersona.value.displayName
                    )
                    if (_isTtsEnabled.value) {
                        voiceManager.speak(resultText.take(200))
                    }
                }
            } catch (e: Exception) {
                _visionState.value = _visionState.value.copy(
                    isAnalyzing = false,
                    error = e.localizedMessage ?: "Unknown error"
                )
            }
        }
    }

    fun runOnDeviceOcr() {
        val bitmap = _visionState.value.selectedBitmap ?: return
        _visionState.value = _visionState.value.copy(isAnalyzing = true, error = null, resultText = "")
        viewModelScope.launch(Dispatchers.Default) {
            val ocrEngine = com.example.vision.OcrEngine()
            val ocrResult = ocrEngine.recognizeText(bitmap)
            if (ocrResult.isSuccess) {
                val formatted = "ML KIT ON-DEVICE OCR (${ocrResult.blocks.size} blocks):\n\n" + ocrResult.fullText
                _visionState.value = _visionState.value.copy(
                    isAnalyzing = false,
                    resultText = formatted
                )
                if (_isTtsEnabled.value) {
                    voiceManager.speak(ocrResult.fullText.take(200))
                }
            } else {
                _visionState.value = _visionState.value.copy(
                    isAnalyzing = false,
                    error = ocrResult.error ?: "OCR extraction failed"
                )
            }
        }
    }

    // Memory Management
    fun saveMemory(key: String, content: String, category: String = "general") {
        viewModelScope.launch {
            repository.saveMemory(key, content, category, _activePersona.value.displayName)
        }
    }

    fun updateMemory(id: Long, key: String, content: String, category: String = "general") {
        viewModelScope.launch {
            val existing = repository.getMemoryById(id)
            if (existing != null) {
                repository.updateMemory(
                    existing.copy(
                        key = key.trim(),
                        content = content.trim(),
                        category = category.trim().lowercase(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            repository.deleteMemory(id)
        }
    }

    // Web Studio Management
    fun loadWebProject(projectId: Long) {
        viewModelScope.launch {
            val project = repository.getProjectById(projectId)
            if (project != null) {
                val files = webProjectManager.getProjectFiles(projectId)
                val defaultFile = files.find { it.name == "index.html" || it.name == "src/App.jsx" } ?: files.firstOrNull()
                val preview = webProjectManager.getBundledHtmlForPreview(projectId)
                val build = webProjectManager.getBuildStatus(projectId)

                _webStudioState.value = WebStudioState(
                    activeProject = project,
                    currentFileName = defaultFile?.name ?: "index.html",
                    editorContent = defaultFile?.content ?: "",
                    previewHtml = preview,
                    availableFiles = files.map { it.name },
                    buildStatus = build
                )
            }
        }
    }

    fun selectWebFile(fileName: String) {
        val projectId = _webStudioState.value.activeProject?.id ?: return
        val files = webProjectManager.getProjectFiles(projectId)
        val file = files.find { it.name == fileName }
        if (file != null) {
            _webStudioState.value = _webStudioState.value.copy(
                currentFileName = fileName,
                editorContent = file.content,
                availableFiles = files.map { it.name }
            )
        }
    }

    fun updateEditorContent(newCode: String) {
        _webStudioState.value = _webStudioState.value.copy(editorContent = newCode)
    }

    fun saveCurrentWebFile() {
        val state = _webStudioState.value
        val projectId = state.activeProject?.id ?: return
        webProjectManager.writeFile(projectId, state.currentFileName, state.editorContent)
        val updatedPreview = webProjectManager.getBundledHtmlForPreview(projectId)
        val files = webProjectManager.getProjectFiles(projectId)
        val build = webProjectManager.getBuildStatus(projectId)
        _webStudioState.value = state.copy(
            previewHtml = updatedPreview,
            availableFiles = files.map { it.name },
            buildStatus = build
        )
    }

    fun exportProjectZip(): File? {
        val projectId = _webStudioState.value.activeProject?.id ?: return null
        val zipFile = webProjectManager.exportProjectAsZip(projectId)
        if (zipFile != null && zipFile.exists()) {
            _webStudioState.value = _webStudioState.value.copy(
                exportStatus = "Exported: ${zipFile.name} (${zipFile.length()} bytes)"
            )
        }
        return zipFile
    }

    fun createNewWebProject(name: String, description: String, type: String = "HTML_CSS_JS") {
        viewModelScope.launch {
            val item = WebProjectItem(name = name, description = description, projectType = type, projectPath = "")
            val id = repository.saveProject(item)
            val folder = webProjectManager.createProject(id, name, description, type)
            val files = webProjectManager.getProjectFiles(id)
            repository.updateProject(item.copy(id = id, projectPath = folder.absolutePath, filesCount = files.size))
            loadWebProject(id)
        }
    }

    fun toggleWebPreview(active: Boolean) {
        val state = _webStudioState.value
        val projectId = state.activeProject?.id ?: return
        val html = webProjectManager.getBundledHtmlForPreview(projectId)
        _webStudioState.value = state.copy(isPreviewActive = active, previewHtml = html)
    }

    fun modifyActiveProject(action: String, details: String = "") {
        val project = _webStudioState.value.activeProject ?: return
        viewModelScope.launch {
            when (action.uppercase()) {
                "ADD_JARVIS_KNOWLEDGE", "JARVIS_KNOWLEDGE" -> webProjectManager.addJarvisKnowledge(project.id)
                "ADD_CONTACT", "CONTACT" -> webProjectManager.addContactSection(project.id)
                "ADD_ANIMATIONS", "ANIMATIONS" -> webProjectManager.addAnimations(project.id)
                "ADD_USER_INFO", "USER_INFO" -> {
                    val parts = details.split("|").map { it.trim() }
                    val name = parts.getOrNull(0) ?: "Project Architect"
                    val title = parts.getOrNull(1) ?: "Full-Stack Engineer"
                    val bio = parts.getOrNull(2) ?: "Building autonomous systems and intuitive user interfaces."
                    val skills = parts.drop(3).ifEmpty { listOf("Kotlin", "Jetpack Compose", "React", "AI Tooling") }
                    webProjectManager.addUserInformation(project.id, name, title, bio, skills)
                }
                else -> webProjectManager.addJarvisKnowledge(project.id)
            }
            loadWebProject(project.id)
        }
    }

    fun shareProjectZip() {
        val project = _webStudioState.value.activeProject ?: return
        val zipFile = webProjectManager.exportProjectAsZip(project.id)
        if (zipFile != null && zipFile.exists()) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    zipFile
                )
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Project: ${project.name} ZIP")
                    putExtra(android.content.Intent.EXTRA_TEXT, "Exported project '${project.name}' created by JARVIS Neural Web Studio.")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = android.content.Intent.createChooser(shareIntent, "Share Project Archive").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(chooser)
                _webStudioState.value = _webStudioState.value.copy(
                    exportStatus = "Shared archive: ${zipFile.name}"
                )
            } catch (e: Exception) {
                _webStudioState.value = _webStudioState.value.copy(
                    exportStatus = "Export share failed: ${e.message}"
                )
            }
        }
    }

    // Automation Management
    fun createAutomationRule(title: String, triggerType: String, triggerValue: String, actionType: String, actionPayload: String) {
        viewModelScope.launch {
            repository.saveAutomationRule(
                AutomationRule(
                    name = title,
                    trigger = triggerType,
                    conditions = triggerValue,
                    actions = actionType,
                    actionPayload = actionPayload
                )
            )
        }
    }

    fun toggleAutomationRule(id: Long, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setAutomationEnabled(id, isEnabled)
        }
    }

    fun deleteAutomationRule(id: Long) {
        viewModelScope.launch {
            repository.deleteAutomationRule(id)
        }
    }

    // ==========================================
    // OFFICIAL WHATSAPP CLOUD API AUTOMATION
    // ==========================================
    val whatsAppConfigStore = app.whatsAppConfigStore
    val whatsAppApiClient = app.whatsAppApiClient
    val whatsAppDao = app.database.whatsAppDao()

    val whatsAppMessages: StateFlow<List<com.example.whatsapp.data.WhatsAppMessageEntity>> =
        whatsAppDao.getAllMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whatsAppContacts: StateFlow<List<com.example.whatsapp.data.WhatsAppContactEntity>> =
        whatsAppDao.getAllContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whatsAppRules: StateFlow<List<com.example.whatsapp.data.WhatsAppRuleEntity>> =
        whatsAppDao.getAllRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whatsAppScheduled: StateFlow<List<com.example.whatsapp.data.WhatsAppScheduledMessageEntity>> =
        whatsAppDao.getAllScheduledMessages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whatsAppAuditLogs: StateFlow<List<com.example.whatsapp.data.WhatsAppAuditLogEntity>> =
        whatsAppDao.getRecentAuditLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whatsAppProviderMode = whatsAppConfigStore.providerMode
    val whatsAppAiAutoReplyEnabled = whatsAppConfigStore.isAiAutoReplyEnabled
    val whatsAppRuleAutomationEnabled = whatsAppConfigStore.isRuleAutomationEnabled
    val whatsAppScheduledEnabled = whatsAppConfigStore.isScheduledMessagesEnabled
    val whatsAppOptInRequired = whatsAppConfigStore.optInRequired
    val whatsAppRateLimitSeconds = whatsAppConfigStore.rateLimitSeconds

    fun updateWhatsAppCredentials(phoneId: String, wabaId: String, token: String, verifyTok: String, port: Int, publicUrl: String) {
        whatsAppConfigStore.updateCredentials(phoneId, wabaId, token, verifyTok, port, publicUrl)
        if (port != whatsAppConfigStore.webhookPort.value) {
            app.whatsAppLocalServer.stop()
            app.whatsAppLocalServer.start(port)
        }
    }

    fun clearWhatsAppCredentials() {
        whatsAppConfigStore.clearCredentials()
    }

    fun setWhatsAppProviderMode(mode: com.example.whatsapp.model.WhatsAppProviderMode) {
        whatsAppConfigStore.updateProviderMode(mode)
    }

    fun setWhatsAppAutomationEnabled(enabled: Boolean) {
        whatsAppConfigStore.updateAutomationEnabled(enabled)
    }

    fun setWhatsAppAiAutoReplyEnabled(enabled: Boolean) {
        whatsAppConfigStore.updateAiAutoReplyEnabled(enabled)
    }

    fun setWhatsAppRuleAutomationEnabled(enabled: Boolean) {
        whatsAppConfigStore.updateRuleAutomationEnabled(enabled)
    }

    fun setWhatsAppScheduledEnabled(enabled: Boolean) {
        whatsAppConfigStore.updateScheduledMessagesEnabled(enabled)
    }

    fun setWhatsAppOptInRequired(required: Boolean) {
        whatsAppConfigStore.updateOptInRequired(required)
    }

    fun setWhatsAppRateLimitSeconds(seconds: Int) {
        whatsAppConfigStore.updateRateLimitSeconds(seconds)
    }

    fun setWhatsAppAutomationMode(mode: com.example.whatsapp.model.WhatsAppAutomationMode) {
        whatsAppConfigStore.updateAutomationMode(mode)
    }

    fun testWhatsAppConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = whatsAppApiClient.testConnection()
            when (res) {
                is com.example.whatsapp.client.WhatsAppApiResult.Success -> {
                    onResult(true, "Verified: ${res.data.verifiedName} (${res.data.displayPhoneNumber})")
                }
                is com.example.whatsapp.client.WhatsAppApiResult.Error -> {
                    onResult(false, "${res.message}: ${res.details}")
                }
            }
        }
    }

    fun sendWhatsAppMessage(recipientPhone: String, text: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = whatsAppApiClient.sendTextMessage(recipientPhone, text)
            when (res) {
                is com.example.whatsapp.client.WhatsAppApiResult.Success -> {
                    val msg = com.example.whatsapp.data.WhatsAppMessageEntity(
                        whatsappMessageId = res.data.messageId,
                        senderOrRecipientNumber = recipientPhone,
                        contactName = recipientPhone,
                        text = text,
                        direction = com.example.whatsapp.model.WhatsAppMessageDirection.OUTGOING.name,
                        status = com.example.whatsapp.model.WhatsAppMessageStatus.SENT.name,
                        isAiReply = false
                    )
                    whatsAppDao.insertMessage(msg)
                    whatsAppDao.updateContactActivity(recipientPhone, text.take(80), System.currentTimeMillis())
                    onComplete(true, "Message sent successfully (ID: ${res.data.messageId})")
                }
                is com.example.whatsapp.client.WhatsAppApiResult.Error -> {
                    onComplete(false, "${res.message}: ${res.details}")
                }
            }
        }
    }

    fun createWhatsAppRule(name: String, ruleType: String, keyword: String, reply: String, startHour: String, endHour: String) {
        viewModelScope.launch {
            val entity = com.example.whatsapp.data.WhatsAppRuleEntity(
                name = name,
                ruleType = ruleType,
                matchKeyword = keyword,
                predefinedReply = reply,
                workingHoursStart = startHour,
                workingHoursEnd = endHour
            )
            whatsAppDao.insertRule(entity)
        }
    }

    fun deleteWhatsAppRule(id: Long) {
        viewModelScope.launch {
            whatsAppDao.deleteRule(id)
        }
    }

    fun toggleWhatsAppRule(rule: com.example.whatsapp.data.WhatsAppRuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            whatsAppDao.updateRule(rule.copy(enabled = enabled))
        }
    }

    fun scheduleWhatsAppMessage(recipient: String, name: String, text: String, timeMillis: Long, repeat: String) {
        viewModelScope.launch {
            val item = com.example.whatsapp.data.WhatsAppScheduledMessageEntity(
                recipientNumber = recipient,
                recipientName = name,
                messageText = text,
                scheduledTimeMillis = timeMillis,
                repeatInterval = repeat,
                enabled = true
            )
            val id = whatsAppDao.insertScheduledMessage(item)
            com.example.whatsapp.service.WhatsAppSchedulerHelper.scheduleMessage(app, item.copy(id = id))
        }
    }

    fun deleteScheduledWhatsAppMessage(id: Long) {
        viewModelScope.launch {
            com.example.whatsapp.service.WhatsAppSchedulerHelper.cancelSchedule(app, id)
            whatsAppDao.deleteScheduledMessage(id)
        }
    }

    fun toggleContactAutomation(contact: com.example.whatsapp.data.WhatsAppContactEntity, allowed: Boolean) {
        viewModelScope.launch {
            whatsAppDao.insertOrUpdateContact(contact.copy(isAutomationAllowed = allowed))
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.stopSpeaking()
    }
}
