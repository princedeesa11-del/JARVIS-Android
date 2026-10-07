package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.AutomationRule
import com.example.data.local.entity.MemoryItem
import com.example.service.ScreenCaptureService
import com.example.tools.ToolRegistry
import com.example.vision.OcrEngine
import com.example.voice.VoiceState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisComprehensiveTest {

    @Test
    fun `test voice state enum states and characteristics`() {
        val states = VoiceState.values()
        assertTrue(states.contains(VoiceState.IDLE))
        assertTrue(states.contains(VoiceState.WAKE_ONLY))
        assertTrue(states.contains(VoiceState.WAKE_DETECTED))
        assertTrue(states.contains(VoiceState.COMMAND_CAPTURE))
        assertTrue(states.contains(VoiceState.PROCESSING))
        assertTrue(states.contains(VoiceState.SPEAKING))
        assertTrue(states.contains(VoiceState.ERROR))
        assertTrue(states.contains(VoiceState.RECOVERING))
        assertTrue(states.contains(VoiceState.WAKE_WORD_READY))
        assertTrue(states.contains(VoiceState.LISTENING))
        assertTrue(states.contains(VoiceState.EXECUTING))
        assertTrue(states.contains(VoiceState.INTERRUPTED))
    }

    @Test
    fun `test wake word detector instantiation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val porcupine = com.example.voice.wakeword.PorcupineWakeWordDetector(
            context = context,
            accessKeyOverride = "dummy_test_key",
            onWakeWordDetected = {}
        )
        assertNotNull(porcupine)
        assertEquals("Picovoice Porcupine (On-Device)", porcupine.engineName)
    }

    @Test
    fun `test wake word engine status enum`() {
        val statuses = com.example.voice.wakeword.WakeWordEngineStatus.values()
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.UNINITIALIZED))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.READY))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.LISTENING))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.PAUSED))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.STOPPED))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.MIC_PERMISSION_DENIED))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.MICROPHONE_UNAVAILABLE))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.WAKE_ENGINE_INIT_FAILED))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.WAKE_ENGINE_RUNTIME_ERROR))
        assertTrue(statuses.contains(com.example.voice.wakeword.WakeWordEngineStatus.AUDIO_ERROR))
    }

    @Test
    fun `test memory item creation and defaults`() {
        val memory = MemoryItem(
            key = "user_preference",
            content = "Prefers concise responses",
            category = "preference",
            memoryType = "long-term",
            persona = "JARVIS"
        )
        assertEquals("user_preference", memory.key)
        assertEquals("Prefers concise responses", memory.content)
        assertEquals("preference", memory.category)
        assertEquals("long-term", memory.memoryType)
        assertEquals("JARVIS", memory.persona)
        assertFalse(memory.isArchived)
    }

    @Test
    fun `test automation rule schema fields`() {
        val rule = AutomationRule(
            name = "Low Battery Alert",
            enabled = true,
            trigger = "BATTERY_LOW",
            conditions = "20",
            actions = "SPEAK_MESSAGE",
            actionPayload = "Battery low, please connect charger."
        )
        assertEquals("Low Battery Alert", rule.name)
        assertTrue(rule.enabled)
        assertEquals("BATTERY_LOW", rule.trigger)
        assertEquals("20", rule.conditions)
        assertEquals("SPEAK_MESSAGE", rule.actions)
        assertEquals(0, rule.runCount)
        assertEquals(0, rule.failureCount)
        assertNull(rule.lastRun)
    }

    @Test
    fun `test screen capture service constants`() {
        assertEquals("jarvis_screen_capture_channel", ScreenCaptureService.CHANNEL_ID)
        assertEquals(2002, ScreenCaptureService.NOTIFICATION_ID)
    }

    @Test
    fun `test ocr engine instantiation`() {
        val engine = OcrEngine()
        assertNotNull(engine)
    }

    @Test
    fun `test all tools have descriptions and parameter definitions`() {
        val tools = ToolRegistry.allTools
        for (tool in tools) {
            assertTrue(tool.name.isNotBlank())
            assertTrue(tool.description.isNotBlank())
            for (param in tool.parameters) {
                assertTrue(param.name.isNotBlank())
                assertTrue(param.type.isNotBlank())
            }
        }
    }

    @Test
    fun `test database migration definition exists`() {
        assertNotNull(com.example.data.local.JarvisDatabase.MIGRATION_1_2)
        assertEquals(1, com.example.data.local.JarvisDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, com.example.data.local.JarvisDatabase.MIGRATION_1_2.endVersion)
    }

    @Test
    fun `test memory item supports categories and personas`() {
        val assistantMemory = MemoryItem(
            key = "user_hobby",
            content = "Loves stargazing",
            category = "EPISODIC",
            persona = "JARVIS"
        )
        val systemMemory = MemoryItem(
            key = "user_coffee",
            content = "Black no sugar",
            category = "PREFERENCE",
            persona = "SYSTEM"
        )
        assertEquals("JARVIS", assistantMemory.persona)
        assertEquals("EPISODIC", assistantMemory.category)
        assertEquals("SYSTEM", systemMemory.persona)
        assertEquals("PREFERENCE", systemMemory.category)
    }

    @Test
    fun `test web project manager creates react vite template and exports zip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = com.example.web.WebProjectManager(context)
        val projectFolder = manager.createProject(
            projectId = 9999L,
            projectName = "Test React App",
            description = "Vite React single page test",
            type = "REACT_SPA"
        )
        assertTrue(projectFolder.exists())
        val files = manager.getProjectFiles(9999L)
        assertTrue(files.isNotEmpty())
        assertTrue(files.any { it.name.contains("package.json") || it.name.contains("App.jsx") })

        val zipFile = manager.exportProjectAsZip(9999L)
        assertNotNull(zipFile)
        assertTrue(zipFile!!.exists())
        assertTrue(zipFile.length() > 0)
    }

    @Test
    fun `test new tools are in registry`() {
        assertNotNull(ToolRegistry.allTools.find { it.name == "capture_screen" })
        assertNotNull(ToolRegistry.allTools.find { it.name == "export_web_project_zip" })
        assertNotNull(ToolRegistry.allTools.find { it.name == "draft_message" })
    }

    @Test
    fun `test messaging result status enum`() {
        val statuses = com.example.tools.MessagingResultStatus.values()
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.MESSAGE_COMPOSER_OPENED))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.MESSAGE_DRAFT_CREATED))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.MESSAGE_SENT))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.MESSAGE_FAILED))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.USER_CANCELLED))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.SMS_SENT))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.SMS_FAILED))
        assertTrue(statuses.contains(com.example.tools.MessagingResultStatus.SMS_PERMISSION_REQUIRED))
    }

    @Test
    fun `test semantic memory engine vectorization and similarity`() {
        val text1 = "Remind me to buy groceries and milk"
        val text2 = "Purchase food, groceries and fresh milk"
        val text3 = "Calculate the trajectory of a rocket"

        val vec1 = com.example.ai.SemanticMemoryEngine.computeVector(text1)
        val vec2 = com.example.ai.SemanticMemoryEngine.computeVector(text2)
        val vec3 = com.example.ai.SemanticMemoryEngine.computeVector(text3)

        val sim12 = com.example.ai.SemanticMemoryEngine.cosineSimilarity(vec1, vec2)
        val sim13 = com.example.ai.SemanticMemoryEngine.cosineSimilarity(vec1, vec3)

        assertTrue("Grocery texts should have higher similarity than rocket text", sim12 > sim13)

        val serialized = com.example.ai.SemanticMemoryEngine.vectorToString(vec1)
        val deserialized = com.example.ai.SemanticMemoryEngine.stringToVector(serialized)
        assertEquals(vec1.size, deserialized.size)
    }

    @Test
    fun `test document manager file operations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val docHelper = com.example.tools.DocumentManagerHelper(context)

        val fileName = "test_unit_note.txt"
        val content = "JARVIS system test note content."

        val (saveOk, saveMsg) = docHelper.saveDocument(fileName, content)
        assertTrue(saveOk)

        val (readOk, readContent) = docHelper.readDocument(fileName)
        assertTrue(readOk)
        assertEquals(content, readContent)

        val list = docHelper.listDocuments()
        assertTrue(list.any { it["fileName"] == fileName })

        val (delOk, _) = docHelper.deleteDocument(fileName)
        assertTrue(delOk)
    }

    @Test
    fun `test all required tools present in registry`() {
        val tools = ToolRegistry.allTools.map { it.name }.toSet()
        val required = setOf(
            "send_sms", "prepare_sms", "get_sms_capability",
            "get_bluetooth_status", "list_bluetooth_devices", "start_bluetooth_discovery",
            "get_current_location", "create_geofence", "list_geofences", "delete_geofence",
            "list_documents", "save_document", "read_document", "delete_document",
            "media_control", "get_media_state",
            "get_wifi_status", "open_wifi_settings",
            "create_calendar_event", "read_calendar_events", "update_calendar_event", "delete_calendar_event",
            "semantic_memory_search", "remember", "forget_memory", "update_memory",
            "call_contact", "get_call_state",
            "extract_page_text", "research_topic", "compare_sources",
            "create_automation_rule", "list_automation_rules", "delete_automation_rule"
        )
        for (req in required) {
            assertTrue("ToolRegistry must contain tool: $req", tools.contains(req))
        }
    }

    @Test
    fun `test voice interruption state transition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val voiceManager = com.example.voice.VoiceManager(context)
        assertEquals(VoiceState.IDLE, voiceManager.voiceState.value)
        voiceManager.stopSpeaking(isBargeIn = true)
        // Barge-in stops speech and marks interrupted if speaking or stays idle
        assertFalse(voiceManager.isSpeaking.value)
    }

    @Test
    fun `test tool execution result structure`() {
        val result = com.example.tools.ToolResult(
            toolName = "draft_message",
            isSuccess = true,
            output = "The message composer is ready. Please press Send.",
            data = mapOf("status" to "MESSAGE_COMPOSER_OPENED")
        )
        assertTrue(result.isSuccess)
        assertEquals("draft_message", result.toolName)
        assertEquals("MESSAGE_COMPOSER_OPENED", result.data["status"])
    }

    @Test
    fun `test wake word normalization and variations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.voice.assistant.CommandRouter(context)

        // Standalone wake calls
        assertEquals("wake_call", router.normalize("Hey Jarvis"))
        assertEquals("wake_call", router.normalize("hey jarvis!"))
        assertEquals("wake_call", router.normalize("Wake up Jarvis"))
        assertEquals("wake_call", router.normalize("Wake up, Jarvis!"))
        assertEquals("wake_call", router.normalize("Jarvis"))
        assertEquals("wake_call", router.normalize("Jarvis!"))
        assertEquals("wake_call", router.normalize("wake up"))
        assertEquals("wake_call", router.normalize("jarvis wake up"))

        // Phonetic variants (Indian English / accents)
        assertEquals("wake_call", router.normalize("hey jervis"))
        assertEquals("wake_call", router.normalize("wake up jarwis"))
        assertEquals("wake_call", router.normalize("jarvees"))

        // Multilingual scripts
        assertEquals("wake_call", router.normalize("હે જાર્વિસ"))
        assertEquals("wake_call", router.normalize("જાર્વિસ"))
        assertEquals("wake_call", router.normalize("જાગ જાર્વિસ"))
        assertEquals("wake_call", router.normalize("हे जार्विस"))
        assertEquals("wake_call", router.normalize("जार्विस"))
        assertEquals("wake_call", router.normalize("जार्विस उठो"))

        // Wake word + command with punctuation stripped cleanly
        assertEquals("play first video", router.normalize("Hey Jarvis, play first video"))
        assertEquals("play first video of arijit singh", router.normalize("Wake up Jarvis! Play first video of Arijit Singh"))
        assertEquals("play kesariya on youtube", router.normalize("Jarvis: play Kesariya on YouTube"))
        assertEquals("send hello to mom on whatsapp", router.normalize("Hey Jarvis, send hello to Mom on WhatsApp"))
        assertEquals("turn on flashlight", router.normalize("Wake up Jarvis, please turn on flashlight"))
    }

    @Test
    fun `test acoustic wake word detector and unified sentinel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var detected = false
        val acoustic = com.example.voice.wakeword.AcousticWakeWordDetector(
            context = context,
            onWakeWordDetected = { detected = true }
        )
        assertNotNull(acoustic)
        assertEquals("JARVIS On-Device Acoustic Engine", acoustic.engineName)
        acoustic.start()
        assertTrue(acoustic.isListening)

        // Process quiet frames
        val emptyPcm = ShortArray(1024)
        acoustic.processPcm(emptyPcm, emptyPcm.size)
        acoustic.stop()
        assertFalse(acoustic.isListening)

        val unified = com.example.voice.wakeword.UnifiedWakeWordDetector(
            context = context,
            onWakeWordDetected = {}
        )
        assertNotNull(unified)
        assertEquals("JARVIS Multi-Layer Wake Word Engine", unified.engineName)
        unified.start()
        assertTrue(unified.isListening)
        unified.processPcm(emptyPcm, emptyPcm.size)
        unified.stop()
        assertFalse(unified.isListening)
    }

    @Test
    fun `test wake word 28 scenarios - false positives rejection and one-shot normalization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.voice.assistant.CommandRouter(context)

        // 1-3. Normal and accented wake words
        assertEquals("wake_call", router.normalize("Hey Jarvis"))
        assertEquals("wake_call", router.normalize("hey jervis"))
        assertEquals("wake_call", router.normalize("jarwis"))
        assertEquals("wake_call", router.normalize("હે જાર્વિસ"))
        assertEquals("wake_call", router.normalize("हे जार्विस"))

        // 8-11. False positive rejection (must NOT be treated as wake_call)
        assertNotEquals("wake_call", router.normalize("Today I watched a movie."))
        assertNotEquals("wake_call", router.normalize("Can you help me?"))
        assertNotEquals("wake_call", router.normalize("Jarvis was a character."))
        assertNotEquals("wake_call", router.normalize("Hey, what's happening?"))

        // 12-14. One-shot commands
        assertEquals("open youtube", router.normalize("Hey Jarvis, open YouTube"))
        assertEquals("play the first video", router.normalize("Wake up Jarvis, play the first video"))
        assertEquals("what is the weather", router.normalize("Hey Jarvis, what is the weather?"))
        assertEquals("send hello to mom", router.normalize("Hey Jarvis, send hello to Mom"))

        // 15. Command router routeAndExecute on wake_call returns blank speech to remain quiet
        kotlinx.coroutines.runBlocking {
            val wakeResult = router.routeAndExecute("Hey Jarvis")
            assertTrue(wakeResult.handled)
            assertEquals("", wakeResult.speechResponse)

            // One-shot execution
            val ytResult = router.routeAndExecute("Hey Jarvis, open YouTube")
            assertTrue(ytResult.handled)
            assertTrue(ytResult.speechResponse.contains("YouTube", ignoreCase = true))
        }
    }

    @Test
    fun `test sensitivity configuration and false trigger protection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = com.example.voice.wakeword.AcousticWakeWordDetector(context = context)

        // Test sensitivity levels
        detector.setSensitivity(com.example.voice.wakeword.WakeSensitivity.LOW)
        assertEquals(com.example.voice.wakeword.WakeSensitivity.LOW, detector.sensitivity)

        detector.setSensitivity(com.example.voice.wakeword.WakeSensitivity.MEDIUM)
        assertEquals(com.example.voice.wakeword.WakeSensitivity.MEDIUM, detector.sensitivity)

        detector.setSensitivity(com.example.voice.wakeword.WakeSensitivity.HIGH)
        assertEquals(com.example.voice.wakeword.WakeSensitivity.HIGH, detector.sensitivity)

        // Test false trigger protection toggle
        detector.setFalseTriggerProtection(false)
        assertFalse(detector.falseTriggerProtectionEnabled)
        detector.setFalseTriggerProtection(true)
        assertTrue(detector.falseTriggerProtectionEnabled)
    }

    @Test
    fun `test microphone state coordinator transitions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = com.example.voice.MicrophoneOwnershipCoordinator.getInstance(context)

        // Acquire for continuous mic
        val acquired = coordinator.acquireMicrophone(com.example.voice.AudioClient.CONTINUOUS_MIC)
        assertTrue(acquired)
        assertEquals(com.example.voice.AudioClient.CONTINUOUS_MIC, coordinator.currentOwner.value)
        assertEquals(com.example.voice.MicrophoneState.WAKE_WORD_MODE, coordinator.microphoneState.value)

        // Command mode transition
        coordinator.setMicrophoneState(com.example.voice.MicrophoneState.COMMAND_MODE)
        assertEquals(com.example.voice.MicrophoneState.COMMAND_MODE, coordinator.microphoneState.value)

        // Speaking playback transition
        coordinator.acquireAudioPlayback()
        assertEquals(com.example.voice.AudioClient.TTS_PLAYBACK, coordinator.currentOwner.value)
        assertEquals(com.example.voice.MicrophoneState.SPEAKING, coordinator.microphoneState.value)

        // Release
        coordinator.forceReset()
        assertEquals(com.example.voice.AudioClient.NONE, coordinator.currentOwner.value)
        assertEquals(com.example.voice.MicrophoneState.STOPPED, coordinator.microphoneState.value)
    }

    @Test
    fun `test wake phrase profile enum and detector profile switching`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = com.example.voice.wakeword.AcousticWakeWordDetector(context = context)

        // Test profiles
        detector.setWakePhraseProfile(com.example.voice.wakeword.WakePhraseProfile.HEY_JARVIS)
        assertEquals(com.example.voice.wakeword.WakePhraseProfile.HEY_JARVIS, detector.activeWakePhraseProfile)

        detector.setWakePhraseProfile(com.example.voice.wakeword.WakePhraseProfile.WAKE_UP_JARVIS)
        assertEquals(com.example.voice.wakeword.WakePhraseProfile.WAKE_UP_JARVIS, detector.activeWakePhraseProfile)

        detector.setWakePhraseProfile(com.example.voice.wakeword.WakePhraseProfile.JARVIS_ONLY)
        assertEquals(com.example.voice.wakeword.WakePhraseProfile.JARVIS_ONLY, detector.activeWakePhraseProfile)

        detector.setWakePhraseProfile(com.example.voice.wakeword.WakePhraseProfile.MULTI_PHRASE)
        assertEquals(com.example.voice.wakeword.WakePhraseProfile.MULTI_PHRASE, detector.activeWakePhraseProfile)
    }

    @Test
    fun `test multi-step compound command execution in CommandRouter`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.voice.assistant.CommandRouter(context)

        // Compound command with YouTube search and volume setting
        val result = router.routeAndExecute("search Arijit Singh songs on YouTube and set volume to 70%")
        assertTrue(result.handled)
        assertEquals("multi_step_execution", result.commandName)
        assertTrue(result.speechResponse.contains("Arijit Singh") || result.speechResponse.contains("Volume"))
    }

    @Test
    fun `test volume percentage control in CommandRouter`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.voice.assistant.CommandRouter(context)

        val result = router.routeAndExecute("increase volume to 80%")
        assertTrue(result.handled)
        assertEquals("set_volume", result.commandName)
        assertTrue(result.speechResponse.contains("80 percent"))
    }

    @Test
    fun `test critical safety actions require confirmation in CommandRouter`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = com.example.voice.assistant.CommandRouter(context)

        val restartResult = router.routeAndExecute("restart phone")
        assertTrue(restartResult.handled)
        assertEquals(com.example.voice.assistant.ExecutionStatus.NEEDS_CLARIFICATION, restartResult.status)
        assertTrue(restartResult.speechResponse.contains("critical action") || restartResult.speechResponse.contains("restart"))

        val resetResult = router.routeAndExecute("factory reset")
        assertTrue(resetResult.handled)
        assertEquals(com.example.voice.assistant.ExecutionStatus.NEEDS_CLARIFICATION, resetResult.status)
        assertTrue(resetResult.speechResponse.contains("dangerous action") || resetResult.speechResponse.contains("data loss"))
    }
}

