package com.example

import android.app.Application
import android.content.Context
import com.example.ai.GeminiAIProvider
import com.example.ai.LocalOfflineProvider
import com.example.automation.AutomationEngine
import com.example.data.local.JarvisDatabase
import com.example.data.repository.JarvisRepository
import com.example.tools.ToolExecutor
import com.example.voice.VoiceManager
import com.example.voice.assistant.CommandRouter
import com.example.voice.assistant.JarvisVoiceAssistant
import com.example.web.WebProjectManager
import com.example.web.WebSearchService

class JarvisApp : Application() {

    lateinit var database: JarvisDatabase
        private set

    lateinit var repository: JarvisRepository
        private set

    lateinit var webProjectManager: WebProjectManager
        private set

    lateinit var webSearchService: WebSearchService
        private set

    lateinit var toolExecutor: ToolExecutor
        private set

    lateinit var voiceManager: VoiceManager
        private set

    lateinit var commandRouter: CommandRouter
        private set

    lateinit var jarvisVoiceAssistant: JarvisVoiceAssistant
        private set

    lateinit var automationEngine: AutomationEngine
        private set

    lateinit var geminiConfigManager: com.example.ai.GeminiConfigManager
        private set

    lateinit var geminiAIProvider: GeminiAIProvider
        private set

    lateinit var localOfflineProvider: LocalOfflineProvider
        private set

    lateinit var whatsAppConfigStore: com.example.whatsapp.config.WhatsAppConfigStore
        private set

    lateinit var whatsAppApiClient: com.example.whatsapp.client.WhatsAppCloudApiClient
        private set

    lateinit var whatsAppAutomationEngine: com.example.whatsapp.engine.WhatsAppAutomationEngine
        private set

    lateinit var whatsAppLocalServer: com.example.whatsapp.server.WhatsAppLocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        geminiConfigManager = com.example.ai.GeminiConfigManager.getInstance(this)
        database = JarvisDatabase.getDatabase(this)
        repository = JarvisRepository(database)
        webProjectManager = WebProjectManager(this)
        webSearchService = WebSearchService()
        toolExecutor = ToolExecutor(this, repository, webProjectManager, webSearchService)
        voiceManager = VoiceManager(this)
        automationEngine = AutomationEngine(this, repository, toolExecutor)
        geminiAIProvider = GeminiAIProvider(configManager = geminiConfigManager)

        // Load custom_api_key + selected_model + selected_audio_model from SharedPreferences before any chat
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val customKey = prefs.getString("custom_api_key", "")?.trim() ?: ""
        val selectedModel = prefs.getString("selected_model", "gemini-3.5-flash")?.trim()?.ifEmpty { "gemini-3.5-flash" } ?: "gemini-3.5-flash"
        val selectedAudioModel = prefs.getString("selected_audio_model", "gemini-3.5-flash")?.trim()?.ifEmpty { "gemini-3.5-flash" } ?: "gemini-3.5-flash"
        geminiAIProvider.updateApiKey(customKey)
        geminiAIProvider.updateModel(selectedModel)
        geminiAIProvider.updateAudioModel(selectedAudioModel)

        localOfflineProvider = LocalOfflineProvider()

        // WhatsApp Official Cloud API System
        whatsAppConfigStore = com.example.whatsapp.config.WhatsAppConfigStore.getInstance(this)
        whatsAppApiClient = com.example.whatsapp.client.WhatsAppCloudApiClient(whatsAppConfigStore)
        whatsAppAutomationEngine = com.example.whatsapp.engine.WhatsAppAutomationEngine(
            context = this,
            dao = database.whatsAppDao(),
            configStore = whatsAppConfigStore,
            apiClient = whatsAppApiClient,
            geminiProvider = geminiAIProvider
        )
        whatsAppLocalServer = com.example.whatsapp.server.WhatsAppLocalServer(
            context = this,
            configStore = whatsAppConfigStore,
            dao = database.whatsAppDao(),
            automationEngine = whatsAppAutomationEngine,
            apiClient = whatsAppApiClient
        )
        // Auto-start Webhook receiver server if enabled or configured
        whatsAppLocalServer.start(whatsAppConfigStore.webhookPort.value)

        // Core Assistant Architecture
        commandRouter = CommandRouter(this, toolExecutor)
        jarvisVoiceAssistant = JarvisVoiceAssistant(
            context = this,
            repository = repository,
            voiceManager = voiceManager,
            commandRouter = commandRouter,
            geminiProvider = geminiAIProvider,
            offlineProvider = localOfflineProvider
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        whatsAppLocalServer.stop()
        jarvisVoiceAssistant.stopAssistant()
        voiceManager.destroy()
    }
}
