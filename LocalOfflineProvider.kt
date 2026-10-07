package com.example.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class LocalOfflineProvider(
    private val contextManager: ConversationContextManager = ConversationContextManager()
) : AIProvider {
    override val providerName: String = "JARVIS Local Offline Core"

    fun getContextManager(): ConversationContextManager = contextManager

    override suspend fun generateResponse(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String?
    ): AIResponse {
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.text?.trim() ?: ""
        return processLocalIntent(lastUserMessage)
    }

    override fun generateStream(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String?
    ): Flow<AIStreamChunk> = flow {
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.text?.trim() ?: ""
        val response = processLocalIntent(lastUserMessage)
        if (response.toolCalls.isNotEmpty()) {
            for (tc in response.toolCalls) {
                emit(AIStreamChunk("", toolCall = tc))
            }
        }
        val words = response.text.split(" ")
        for (w in words) {
            emit(AIStreamChunk("$w "))
            kotlinx.coroutines.delay(20)
        }
        emit(AIStreamChunk("", isDone = true))
    }

    override suspend fun analyzeVision(
        prompt: String,
        imageBase64: String,
        mimeType: String
    ): AIResponse {
        return AIResponse(
            text = "Vision AI requires an active internet connection and Gemini API key. Please check your network and API key settings.",
            isSuccess = false,
            errorMessage = "Offline mode - Vision requires cloud API"
        )
    }

    private fun processLocalIntent(query: String): AIResponse {
        val lower = query.lowercase().trim()

        // 0. Check multi-turn conversation context resolution first
        when (val resolution = contextManager.resolveFollowUp(query)) {
            is ConversationContextManager.ContextualResolution.NeedsSlot -> {
                return AIResponse(text = resolution.promptUser)
            }
            is ConversationContextManager.ContextualResolution.Resolved -> {
                return when (resolution.action) {
                    "send_sms" -> AIResponse(
                        text = resolution.explanation,
                        toolCalls = listOf(
                            ToolCallRequest(
                                id = UUID.randomUUID().toString(),
                                name = "send_sms",
                                arguments = resolution.arguments
                            )
                        )
                    )
                    "create_calendar_event" -> AIResponse(
                        text = resolution.explanation,
                        toolCalls = listOf(
                            ToolCallRequest(
                                id = UUID.randomUUID().toString(),
                                name = "calendar_operation",
                                arguments = mapOf(
                                    "action" to "CREATE",
                                    "title" to (resolution.arguments["title"] ?: "Meeting"),
                                    "startTimeMillis" to (resolution.arguments["startTimeMillis"] ?: System.currentTimeMillis()),
                                    "endTimeMillis" to (resolution.arguments["endTimeMillis"] ?: (System.currentTimeMillis() + 3600_000L))
                                )
                            )
                        )
                    )
                    "search_in_app" -> {
                        val app = resolution.arguments["app"] as? String ?: "YouTube"
                        val searchQuery = resolution.arguments["query"] as? String ?: ""
                        AIResponse(
                            text = resolution.explanation,
                            toolCalls = listOf(
                                ToolCallRequest(
                                    id = UUID.randomUUID().toString(),
                                    name = "launch_app",
                                    arguments = mapOf("appName" to app, "query" to searchQuery)
                                )
                            )
                        )
                    }
                    else -> AIResponse(text = resolution.explanation)
                }
            }
            ConversationContextManager.ContextualResolution.None -> {
                // Continue to standard intent parsing
            }
        }

        // 1. App Launching: "open youtube", "launch whatsapp", "open camera"
        val openAppMatch = Regex("^(?:open|launch)\\s+([a-zA-Z0-9 ]+)$").find(lower)
        if (openAppMatch != null) {
            val appName = openAppMatch.groupValues[1].trim()
            contextManager.updateApp(appName)
            return AIResponse(
                text = "Executing command: Opening $appName.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "launch_app",
                        arguments = mapOf("appName" to appName)
                    )
                )
            )
        }

        // Direct SMS / Messaging: "message Rahul saying ...", "send sms to 12345 ..."
        val directMsgMatch = Regex("^(?:send\\s+(?:a\\s+)?(?:message|sms)\\s+to|message)\\s+([a-zA-Z0-9+ ]+?)(?:\\s+(?:saying|and\\s+say|that)\\s+(.+))?$").find(lower)
        if (directMsgMatch != null) {
            val recipient = directMsgMatch.groupValues[1].trim()
            val msgBody = directMsgMatch.groupValues.getOrNull(2)?.trim()
            if (!msgBody.isNullOrBlank()) {
                contextManager.updateAction("send_sms", mapOf("recipient" to recipient))
                return AIResponse(
                    text = "Sending message to $recipient: \"$msgBody\".",
                    toolCalls = listOf(
                        ToolCallRequest(
                            id = UUID.randomUUID().toString(),
                            name = "send_sms",
                            arguments = mapOf("recipient" to recipient, "message" to msgBody)
                        )
                    )
                )
            } else {
                contextManager.setPendingSlot("sms_message", mapOf("recipient" to recipient))
                return AIResponse(text = "What message would you like to send to $recipient?")
            }
        }

        // Call Log / Call History: "access call history", "read call log", "recent calls"
        if (lower.contains("call log") || lower.contains("call history") || lower.contains("recent calls")) {
            return AIResponse(
                text = "Querying device call history telemetrics.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_call_log",
                        arguments = mapOf("limit" to 10)
                    )
                )
            )
        }

        // Wi-Fi and Bluetooth settings: "open wifi settings", "turn on bluetooth"
        if (lower.contains("wifi") && (lower.contains("setting") || lower.contains("open"))) {
            return AIResponse(
                text = "Opening device Wi-Fi connectivity settings.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "control_wifi",
                        arguments = mapOf("action" to "OPEN_SETTINGS")
                    )
                )
            )
        }
        if (lower.contains("bluetooth")) {
            val enable = !lower.contains("off") && !lower.contains("disable")
            return AIResponse(
                text = if (enable) "Activating Bluetooth adapter." else "Deactivating Bluetooth adapter.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "control_bluetooth",
                        arguments = mapOf("enabled" to enable)
                    )
                )
            )
        }

        // Media playback: "play", "pause", "resume", "next track", "previous track"
        if (lower == "play" || lower == "pause" || lower == "resume" || lower == "next track" || lower == "previous track" || lower == "stop music") {
            val cmd = when {
                lower.contains("next") -> "NEXT"
                lower.contains("prev") -> "PREVIOUS"
                lower.contains("stop") -> "STOP"
                lower.contains("pause") -> "PAUSE"
                else -> "PLAY"
            }
            return AIResponse(
                text = "Executing media playback directive: $cmd.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "media_control",
                        arguments = mapOf("command" to cmd)
                    )
                )
            )
        }

        // 2. Battery status: "battery", "battery status", "how much battery"
        if (lower.contains("battery")) {
            return AIResponse(
                text = "Checking system power and battery telemetry.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_battery_status",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // 3. Network status: "network", "wifi", "internet connection"
        if (lower.contains("network") || lower.contains("wifi") || lower.contains("connection")) {
            return AIResponse(
                text = "Checking active network adapters and connectivity.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_network_status",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // 4. Device info: "device info", "hardware status", "system info"
        if (lower.contains("device info") || lower.contains("system info") || lower.contains("hardware")) {
            return AIResponse(
                text = "Querying system hardware profile.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_device_info",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // 5. Notifications: dismiss or read notifications
        if (lower.contains("clear notification") || lower.contains("dismiss notification") || lower.contains("delete notification") || lower.contains("clear all notification")) {
            return AIResponse(
                text = "Dismissing active notifications.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "dismiss_notification",
                        arguments = mapOf("query" to "ALL")
                    )
                )
            )
        }
        if (lower.contains("notification") || lower.contains("alerts") || lower.contains("unread message")) {
            return AIResponse(
                text = "Querying active device notifications via notification listener.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_active_notifications",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // 6. Calendar: "calendar", "upcoming events", "schedule"
        if (lower.contains("calendar") || lower.contains("upcoming event") || lower.contains("my schedule")) {
            return AIResponse(
                text = "Querying upcoming calendar entries.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "calendar_operation",
                        arguments = mapOf("action" to "READ")
                    )
                )
            )
        }

        // 7. Inspect Screen / Accessibility: "inspect screen", "what is on screen", "click"
        if (lower.contains("inspect screen") || lower.contains("read screen") || lower.contains("what's on my screen")) {
            return AIResponse(
                text = "Inspecting accessible on-screen elements.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "accessibility_action",
                        arguments = mapOf("action" to "INSPECT")
                    )
                )
            )
        }

        val clickMatch = Regex("^click\\s+(.+)$").find(lower)
        if (clickMatch != null) {
            val target = clickMatch.groupValues[1].trim()
            return AIResponse(
                text = "Executing assisted tap on '$target'.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "accessibility_action",
                        arguments = mapOf("action" to "CLICK", "targetText" to target)
                    )
                )
            )
        }

        // 8. Contact Lookup: "lookup contact ...", "find contact ..."
        val contactMatch = Regex("^(?:lookup|find|search)\\s+contact\\s+(.+)$").find(lower)
        if (contactMatch != null) {
            val name = contactMatch.groupValues[1].trim()
            return AIResponse(
                text = "Searching address book for $name.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "lookup_contact",
                        arguments = mapOf("name" to name)
                    )
                )
            )
        }

        // 9. Phone call / Dial: "dial 12345", "call 12345"
        val callMatch = Regex("^(?:call|dial)\\s+([0-9+ ]+)$").find(lower)
        if (callMatch != null) {
            val number = callMatch.groupValues[1].trim()
            return AIResponse(
                text = "Launching system dialer with $number.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "dial_phone_number",
                        arguments = mapOf("phoneNumber" to number)
                    )
                )
            )
        }

        // 10. Calculator / Math: "calculate 45*12", "what is 20 + 30", "50 / 2"
        val calcMatch = Regex("(?:calculate|what is|compute)?\\s*([0-9+\\-*/%^(). ]{3,})").find(lower)
        if (calcMatch != null && calcMatch.groupValues[1].any { it in "+-*/%" }) {
            val expr = calcMatch.groupValues[1].trim()
            return AIResponse(
                text = "Evaluating mathematical expression: $expr.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "calculate",
                        arguments = mapOf("expression" to expr)
                    )
                )
            )
        }

        // 11. Flashlight / Torch: "turn on flashlight", "torch on", "torch off"
        if (lower.contains("torch") || lower.contains("flashlight")) {
            val enabled = !lower.contains("off")
            return AIResponse(
                text = if (enabled) "Activating flashlight." else "Deactivating flashlight.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "control_flashlight",
                        arguments = mapOf("enabled" to enabled)
                    )
                )
            )
        }

        // 12. Volume control & Mute: "volume up", "volume down", "set volume to 50%", "mute", "unmute"
        if (lower == "mute" || lower == "unmute" || lower.startsWith("mute ") || lower.startsWith("unmute ")) {
            val isMute = !lower.contains("unmute")
            return AIResponse(
                text = if (isMute) "Muting device audio output." else "Unmuting device audio output.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "set_volume",
                        arguments = mapOf("mute" to isMute)
                    )
                )
            )
        }

        val volumePctMatch = Regex("(?:set\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*%?").find(lower)
        if (volumePctMatch != null) {
            val pct = volumePctMatch.groupValues[1].toIntOrNull() ?: 50
            return AIResponse(
                text = "Setting media volume level to $pct%.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "set_volume",
                        arguments = mapOf("percent" to pct, "stream" to "MEDIA")
                    )
                )
            )
        }

        if (lower.contains("volume")) {
            val direction = if (lower.contains("down") || lower.contains("lower")) "DOWN" else "UP"
            return AIResponse(
                text = "Adjusting audio volume $direction.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "adjust_volume",
                        arguments = mapOf("direction" to direction)
                    )
                )
            )
        }

        // Storage & Memory Telemetry
        if (lower.contains("storage") || lower.contains("disk space") || lower.contains("internal storage") || lower.contains("ram usage")) {
            return AIResponse(
                text = "Analyzing device storage and RAM telemetry.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_storage_info",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // Clipboard
        if (lower.contains("read clipboard") || lower.contains("what's on clipboard") || lower.contains("paste") || lower == "clipboard") {
            return AIResponse(
                text = "Reading active clipboard buffer.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "read_clipboard",
                        arguments = emptyMap()
                    )
                )
            )
        }
        if (lower.contains("clear clipboard") || lower.contains("empty clipboard")) {
            return AIResponse(
                text = "Clearing system clipboard buffer.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "clear_clipboard",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // App Installed Check: "is youtube installed", "check if whatsapp is installed"
        val isInstalledMatch = Regex("(?:is\\s+([a-zA-Z0-9 ]+)\\s+installed|check\\s+if\\s+([a-zA-Z0-9 ]+)\\s+is\\s+installed)").find(lower)
        if (isInstalledMatch != null) {
            val app = (isInstalledMatch.groupValues[1].ifBlank { isInstalledMatch.groupValues[2] }).trim()
            return AIResponse(
                text = "Verifying package manager installation for $app.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "is_app_installed",
                        arguments = mapOf("appName" to app)
                    )
                )
            )
        }

        // 13. Reminders: "set reminder to buy milk in 10 minutes", "remind me to call John"
        val remindMatch = Regex("(?:set\\s+reminder|remind\\s+me)(?:\\s+to)?\\s+(.+?)(?:\\s+in\\s+(\\d+)\\s*(?:min|minute|minutes))?$").find(lower)
        if (remindMatch != null) {
            val title = remindMatch.groupValues[1].trim()
            val delayStr = remindMatch.groupValues.getOrNull(2)
            val delayMinutes = delayStr?.toIntOrNull() ?: 5
            return AIResponse(
                text = "Setting reminder: '$title' in $delayMinutes minutes.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "set_reminder",
                        arguments = mapOf("title" to title, "delayMinutes" to delayMinutes)
                    )
                )
            )
        }

        // 14. Memory recall: "what do you remember about...", "show my preferences", "list memories"
        val recallAboutMatch = Regex("(?:what do you remember about|what is my stored preference for|what do you know about)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)
        if (recallAboutMatch != null) {
            val topic = recallAboutMatch.groupValues[1].trim()
            return AIResponse(
                text = "Recalling stored memory records regarding '$topic'.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "read_memory",
                        arguments = mapOf("query" to topic)
                    )
                )
            )
        }

        if (lower.contains("what do you remember") || lower.contains("my memories") || lower.contains("list memories") || lower.contains("show my preferences") || lower.contains("show preferences")) {
            return AIResponse(
                text = "Retrieving saved preferences and neural memory bank.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "get_saved_memories",
                        arguments = emptyMap()
                    )
                )
            )
        }

        // 15. Memory update: "update my preference about ... to ...", "change my preference ... to ..."
        val updatePrefMatch = Regex("(?:update|change)\\s+(?:my\\s+)?preference\\s+(?:about|for)?\\s*(.+?)\\s+to\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)
        if (updatePrefMatch != null) {
            val key = updatePrefMatch.groupValues[1].trim()
            val newContent = updatePrefMatch.groupValues[2].trim()
            return AIResponse(
                text = "Updating preference '$key' to '$newContent'.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "remember",
                        arguments = mapOf(
                            "key" to key,
                            "content" to newContent,
                            "category" to com.example.ai.memory.MemoryCategory.USER_PREFERENCE.code
                        )
                    )
                )
            )
        }

        // 16. Memory write: "remember that my favorite color is cyan", "remember this: ..."
        val rememberMatch = Regex("^remember\\s+(?:that\\s+|this:?\\s*)?(.+)$", RegexOption.IGNORE_CASE).find(lower)
        if (rememberMatch != null) {
            val fact = rememberMatch.groupValues[1].trim()
            val inferred = com.example.ai.memory.MemoryCategory.inferCategory("User Fact", fact)
            return AIResponse(
                text = "Committing fact to persistent memory bank.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "remember",
                        arguments = mapOf("key" to "User Preference / Fact", "content" to fact, "category" to inferred.code)
                    )
                )
            )
        }

        // 17. Memory delete: "forget that ...", "delete memory ..."
        val forgetMatch = Regex("^(?:forget|delete\\s+memory)(?:\\s+that)?\\s+(.+)$", RegexOption.IGNORE_CASE).find(lower)
        if (forgetMatch != null) {
            val target = forgetMatch.groupValues[1].trim()
            return AIResponse(
                text = "Purging memory related to '$target'.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "forget_memory",
                        arguments = mapOf("identifier" to target)
                    )
                )
            )
        }

        // 17. Web project creation: "create portfolio website", "build a landing page"
        val webProjectMatch = Regex("(?:create|build|generate|make)\\s+(?:a\\s+)?(?:website|web page|landing page|portfolio|spa)\\s*(.*)").find(lower)
        if (webProjectMatch != null) {
            val desc = webProjectMatch.groupValues[1].ifBlank { "Modern web application" }
            return AIResponse(
                text = "Initializing web engineering suite for project: $desc.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "create_web_project",
                        arguments = mapOf("projectName" to "My Web Project", "description" to desc, "type" to "HTML_CSS_JS")
                    )
                )
            )
        }

        // 18. Web Search: "search web for ...", "google ..."
        val searchMatch = Regex("^(?:search|google|find on web)\\s+(?:for\\s+)?(.+)$").find(lower)
        if (searchMatch != null) {
            val queryParam = searchMatch.groupValues[1].trim()
            return AIResponse(
                text = "Executing web search for: $queryParam.",
                toolCalls = listOf(
                    ToolCallRequest(
                        id = UUID.randomUUID().toString(),
                        name = "search_web",
                        arguments = mapOf("query" to queryParam)
                    )
                )
            )
        }

        // Default conversational response: concise, loyal, natural (1-2 sentences max)
        val isGujarati = query.any { it in '\u0A80'..'\u0AFF' }
        val isHindi = query.any { it in '\u0900'..'\u097F' }
        val fallbackText = when {
            isGujarati -> "સમજાયું, પ્રિન્સ. સિસ્ટમ તૈયાર છે."
            isHindi -> "समझ गया, प्रिंस। सिस्टम तैयार है।"
            else -> "Directive received, Prince. Systems standing by."
        }
        return AIResponse(text = fallbackText)
    }
}
