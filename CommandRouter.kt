package com.example.voice.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.service.JarvisAccessibilityService
import com.example.service.WhatsAppChatStatus
import com.example.tools.MediaControlHelper
import com.example.tools.ToolExecutor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExecutionStatus {
    SUCCESS,
    FAILED,
    PERMISSION_REQUIRED,
    APP_NOT_FOUND,
    TARGET_NOT_FOUND,
    AMBIGUOUS,
    TIMEOUT,
    VERIFICATION_FAILED,
    SENT_UNVERIFIED,
    PLAYBACK_UNVERIFIED,
    NOT_SUPPORTED,
    // Backward-compatibility aliases
    NEEDS_PERMISSION,
    NEEDS_CLARIFICATION,
    NOT_FOUND
}

sealed class ContactResolution {
    data class Single(val name: String, val number: String) : ContactResolution()
    data class Multiple(val matches: List<Pair<String, String>>) : ContactResolution()
    object NotFound : ContactResolution()
    object PermissionMissing : ContactResolution()
}

data class CommandResult(
    val handled: Boolean,
    val commandName: String,
    val speechResponse: String,
    val success: Boolean = true,
    val status: ExecutionStatus = if (success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
    val details: String? = null
)

class CommandRouter(
    private val context: Context,
    private val toolExecutor: ToolExecutor? = null
) {
    private val TAG = "CommandRouter"
    private val mediaHelper = MediaControlHelper(context)
    private val webSearchService = com.example.web.WebSearchService()
    val youtubeEngine = com.example.youtube.engine.YouTubeAutomationEngine.getInstance(context)
    private var isTorchOn = false

    /**
     * Clean and normalize raw user utterance.
     * Strips wake words and politeness prefixes for faster regex matching.
     */
    /**
     * Normalizes raw voice input for deterministic keyword matching:
     * - Trims and lowercases
     * - Strips wake phrases (Hey Jarvis, Wake up Jarvis, Jarvis, Wake up, Indian/Gujarati/Hindi phonetic variations)
     * - Strips following punctuation (commas, exclamation marks, colons, etc.)
     * - Detects standalone wake calls
     * - Cleans conversational fillers and polite prefixes
     */
    fun normalize(input: String): String {
        var clean = input.trim().lowercase(Locale.ROOT)
        // Remove trailing punctuation
        clean = clean.replace(Regex("[.,!?;:]+$"), "").trim()

        // Comprehensive wake phrase list with Indian English, Gujarati, and Hindi phonetic variations
        val wakePrefixes = listOf(
            // Primary wake phrases
            "hey jarvis", "wake up jarvis", "jarvis wake up", "wake up", "ok jarvis", "okay jarvis",
            "hi jarvis", "hello jarvis", "yo jarvis", "listen jarvis", "jarvis please", "jarvis",
            // Phonetic variants (Indian English, Gujarati-accented, Hindi-accented)
            "hey jervis", "wake up jervis", "jervis wake up", "jervis",
            "hey jarwis", "wake up jarwis", "jarwis",
            "hey jarvees", "wake up jarvees", "jarvees",
            "hey javris", "wake up javris", "javris",
            "hey zervis", "zervis",
            // Gujarati script
            "હે જાર્વિસ", "જાગ જાર્વિસ", "જાગો જાર્વિસ", "સુણ જાર્વિસ", "જાર્વિસ",
            // Hindi script
            "हे जार्विस", "जार्विस उठो", "सुनो जार्विस", "उठो जार्विस", "जार्विस",
            // Secondary assistants / backward compatibility
            "hey maya", "ok maya", "maya", "assistant"
        )

        // Iteratively strip wake phrases and delimiters (e.g. "wake up, jarvis!", "hey jarvis, jarvis")
        var changed = true
        while (changed) {
            changed = false
            // Check if current clean text is a standalone wake call
            for (wp in wakePrefixes) {
                if (clean == wp) {
                    return "wake_call"
                }
            }
            for (wp in wakePrefixes) {
                val prefixRegex = Regex("^" + Regex.escape(wp) + "[,.!?;:\\s]+", RegexOption.IGNORE_CASE)
                val match = prefixRegex.find(clean)
                if (match != null) {
                    val remainder = clean.substring(match.range.last + 1).trim()
                    if (remainder.isBlank()) {
                        return "wake_call"
                    }
                    clean = remainder
                    changed = true
                    break
                }
            }
        }

        for (wp in wakePrefixes) {
            if (clean == wp) {
                return "wake_call"
            }
        }

        // Remove polite request phrases
        val politePrefixes = listOf(
            "please ", "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you ", "i want you to ", "i want to "
        )
        for (pp in politePrefixes) {
            if (clean.startsWith(pp)) {
                clean = clean.removePrefix(pp).trim()
                break
            }
        }

        return clean.trim()
    }

    /**
     * Attempts to route and execute a command locally without AI round-trip latency.
     */
    suspend fun routeAndExecute(rawInput: String): CommandResult {
        if (rawInput.isBlank()) {
            return CommandResult(
                handled = true,
                commandName = "silence_hold",
                speechResponse = ""
            )
        }

        val normalized = normalize(rawInput)
        if (normalized == "wake_call") {
            return CommandResult(
                handled = true,
                commandName = "wake_acknowledge",
                speechResponse = ""
            )
        }

        if (normalized.isBlank()) {
            return CommandResult(
                handled = true,
                commandName = "silence_hold",
                speechResponse = ""
            )
        }

        Log.d(TAG, "Routing normalized directive: '$normalized' (from '$rawInput')")

        // Multi-step compound command support (e.g., "Open YouTube, search Arijit Singh songs, play the latest song and increase volume to 70%")
        val compoundSteps = splitCompoundDirective(rawInput)
        if (compoundSteps.size >= 2) {
            Log.d(TAG, "Executing multi-step compound directive with ${compoundSteps.size} steps: $compoundSteps")
            val results = mutableListOf<CommandResult>()
            for ((index, step) in compoundSteps.withIndex()) {
                val stepResult = executeSingleAction(step)
                results.add(stepResult)
                if (index < compoundSteps.size - 1) {
                    delay(350)
                }
            }
            val speechParts = results.mapNotNull {
                val resp = it.speechResponse.trim().trimEnd('.')
                if (resp.isNotBlank()) resp else null
            }
            val combinedSpeech = if (speechParts.isNotEmpty()) {
                speechParts.joinToString(", ") + "."
            } else {
                "Executed ${compoundSteps.size} commands in sequence."
            }
            return CommandResult(
                handled = true,
                commandName = "multi_step_execution",
                speechResponse = combinedSpeech,
                success = results.all { it.success },
                details = results.joinToString(" | ") { "${it.commandName}: ${it.speechResponse}" }
            )
        }

        return executeSingleAction(rawInput)
    }

    /**
     * Splits compound voice/text directives into sequential atomic steps.
     */
    private fun splitCompoundDirective(input: String): List<String> {
        val lower = input.trim().lowercase(Locale.ROOT)
        if (lower.startsWith("send ") || lower.startsWith("text ")) {
            return emptyList()
        }

        val connectorRegex = Regex("""(?i)(?:,\s*then\s+|,\s*and\s+then\s+|\s+and\s+then\s+|\s+then\s+|,\s*and\s+|\s+aur\s+phir\s+|\s+ane\s+pachi\s+|(?:,\s*|\s+and\s+|\s+aur\s+|\s+ane\s+)(?=(?:open|launch|start|search|play|turn|set|increase|decrease|volume|mute|unmute|pause|resume|close|lock|call|dial|chalao|kholo|vagaado|shodho)\b))""")
        val parts = input.split(connectorRegex).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        return if (parts.size >= 2) parts else emptyList()
    }

    /**
     * Executes a single atomic command.
     */
    suspend fun executeSingleAction(rawInput: String): CommandResult {
        val normalized = normalize(rawInput)
        if (normalized.isBlank()) {
            return CommandResult(handled = true, commandName = "noop", speechResponse = "")
        }

        // 1. YouTube specialized commands & PLAY_FIRST_VIDEO (supports English, Hindi, Hinglish, Gujarati)
        (youtubeEngine.handleDirective(rawInput) ?: youtubeEngine.handleDirective(normalized))?.let { return it }

        // 2. WhatsApp specialized commands & SEND_MESSAGE
        handleWhatsApp(normalized)?.let { return it }

        // 3. Random safe UI tap in active app
        handleRandomSafeUiTap(normalized)?.let { return it }

        // 4. Web & Google Search
        handleWebSearch(normalized)?.let { return it }

        // 5. Spotify & Media controls
        handleMedia(normalized)?.let { return it }

        // 6. Phone / Calling
        handlePhone(normalized)?.let { return it }

        // 7. Messages / SMS
        handleMessaging(normalized)?.let { return it }

        // 8. System & Device Hardware controls (Settings, WiFi, Bluetooth, Flashlight, Battery, Time)
        handleSystemControls(normalized)?.let { return it }

        // 9. General App Launching ("open <app>", "launch <app>")
        handleAppLaunching(normalized)?.let { return it }

        // 10. Conversational stop / cancel directives
        if (normalized in listOf("stop", "cancel", "be quiet", "shut up", "stand down", "sleep")) {
            return CommandResult(
                handled = true,
                commandName = "stop",
                speechResponse = "Standing by."
            )
        }

        // Not handled locally -> Send to AI reasoning engine
        return CommandResult(
            handled = false,
            commandName = "ai_fallback",
            speechResponse = ""
        )
    }

    /**
     * YouTube handler:
     * - "play first video [of/for query]" / "play [query] on youtube": opens search, locates first video result via Accessibility, clicks it, and verifies video player starts
     * - "search [query] on youtube": opens search results
     * - Multilingual support: Hindi ("youtube par search...", "youtube par pehla video chalao..."), Gujarati ("youtube ma...", etc.)
     */
    private suspend fun handleYouTube(input: String): CommandResult? {
        val ytPackage = "com.google.android.youtube"

        // Delegate to comprehensive YouTubeAutomationEngine first
        youtubeEngine.handleDirective(input)?.let { return it }

        // 1. Play first video & specific play patterns
        val playFirstPatterns = listOf(
            Regex("""^play\s+(?:the\s+)?first\s+video\s+of\s+(.*?)(?:\s+on\s+youtube)?$"""),
            Regex("""^play\s+(?:the\s+)?first\s+video\s+for\s+(.*?)(?:\s+on\s+youtube)?$"""),
            Regex("""^search\s+(?:for\s+)?(.*?)\s+and\s+play\s+(?:the\s+)?first\s+video$"""),
            Regex("""^search\s+(?:for\s+)?(.*?)\s+on\s+youtube\s+and\s+play\s+(?:the\s+)?first\s+video$"""),
            Regex("""^play\s+(?:the\s+)?first\s+video\s+(.*?)(?:\s+on\s+youtube)?$"""),
            Regex("""^play\s+(?:the\s+)?first\s+video$"""),
            Regex("""^play\s+(?:the\s+)?first\s+video\s+on\s+youtube$"""),
            Regex("""^youtube\s+par\s+(?:the\s+)?pehla\s+video\s+chalao$"""),
            Regex("""^pehla\s+video\s+chalao$"""),
            Regex("""^youtube\s+par\s+(?:the\s+)?pehlo\s+video\s+chalao$"""),
            Regex("""^pehlo\s+video\s+chalao$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+search\s+karo\s+aur\s+pehla\s+video\s+chalao$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+search\s+karo\s+ane\s+pehlo\s+video\s+chalao$"""),
            Regex("""^play\s+(.*?)\s+on\s+youtube$"""),
            Regex("""^play\s+(.*?)\s+in\s+youtube$"""),
            Regex("""^play\s+(.*?)\s+youtube$"""),
            Regex("""^play\s+on\s+youtube\s+(.*)$"""),
            Regex("""^play\s+in\s+youtube\s+(.*)$"""),
            Regex("""^youtube\s+play\s+(.*)$"""),
            Regex("""^youtube\s+par\s+play\s+(.*)$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+play(?:\s+karo)?$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+chalao$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+vagado$"""),
            Regex("""^youtube\s+pe\s+play\s+(.*)$"""),
            Regex("""^youtube\s+pe\s+(.*?)\s+chalao$"""),
            Regex("""^youtube\s+ma\s+play\s+(.*)$"""),
            Regex("""^youtube\s+ma\s+(.*?)\s+vagado$"""),
            Regex("""^chalao\s+(.*?)\s+on\s+youtube$"""),
            Regex("""^vagado\s+(.*?)\s+on\s+youtube$""")
        )

        for (pattern in playFirstPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val query = if (match.groupValues.size > 1) match.groupValues[1].trim() else ""
                return executePlayFirstVideo(query)
            }
        }

        // 2. Search patterns
        val searchPatterns = listOf(
            Regex("""^search\s+(.*?)\s+on\s+youtube$"""),
            Regex("""^search\s+youtube\s+for\s+(.*)$"""),
            Regex("""^youtube\s+search\s+(.*)$"""),
            Regex("""^search\s+on\s+youtube\s+(.*)$"""),
            Regex("""^search\s+(.*?)\s+in\s+youtube$"""),
            Regex("""^search\s+(.*?)\s+youtube$"""),
            Regex("""^youtube\s+par\s+search\s+(.*)$"""),
            Regex("""^youtube\s+par\s+(.*?)\s+search(?:\s+karo)?$"""),
            Regex("""^youtube\s+pe\s+search\s+(.*)$"""),
            Regex("""^youtube\s+pe\s+(.*?)\s+search(?:\s+karo)?$"""),
            Regex("""^youtube\s+ma\s+search\s+(.*)$"""),
            Regex("""^youtube\s+ma\s+(.*?)\s+search(?:\s+karo)?$"""),
            Regex("""^search\s+for\s+(.*?)\s+on\s+youtube$"""),
            Regex("""^search\s+for\s+(.*?)\s+youtube$""")
        )

        for (pattern in searchPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val query = match.groupValues[1].trim()
                if (query.isNotBlank()) {
                    executeYouTubeSearch(query)
                    return CommandResult(
                        handled = true,
                        commandName = "youtube_search",
                        speechResponse = "Searching $query on YouTube.",
                        success = true,
                        status = ExecutionStatus.SUCCESS
                    )
                }
            }
        }

        // 3. Direct YouTube open
        if (input == "open youtube" || input == "launch youtube" || input == "start youtube" || input == "youtube kholo" || input == "youtube chalu karo") {
            return openApp(ytPackage, "YouTube")
        }

        return null
    }

    /**
     * Executes PLAY_FIRST_VIDEO automation with full structured verification:
     * 1. Launches YouTube with search query or direct app launch
     * 2. Waits for YouTube to foreground
     * 3. Locates topmost video item in active window
     * 4. Clicks the video node safely
     * 5. Verifies video player screen opened before reporting success
     */
    private suspend fun executePlayFirstVideo(query: String): CommandResult {
        val ytPackage = "com.google.android.youtube"
        Log.i(TAG, "[AUTOMATION] command=PLAY_FIRST_VIDEO")
        Log.i(TAG, "[AUTOMATION] targetApp=$ytPackage")

        val a11y = JarvisAccessibilityService.instance
        Log.i(TAG, "[AUTOMATION] accessibilityEnabled=${a11y != null}")

        if (a11y == null) {
            Log.i(TAG, "[AUTOMATION] Accessibility not enabled, falling back to direct YouTube search & playback")
            return youtubeEngine.executePlay(query)
        }

        // Launch YouTube
        if (query.isNotBlank()) {
            executeYouTubeSearch(query)
        } else {
            openApp(ytPackage, "YouTube")
        }

        // Wait up to 4000ms for YouTube to foreground
        val foregrounded = a11y.waitForPackage(ytPackage, timeoutMs = 4000L)
        if (!foregrounded) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (YouTube failed to foreground)")
            return CommandResult(
                handled = true,
                commandName = "PLAY_FIRST_VIDEO",
                speechResponse = "YouTube took too long to open.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }

        // Find first real video result
        val videoCandidate = a11y.findFirstVideoResult(retries = 5, retryDelayMs = 500L)
        Log.i(TAG, "[AUTOMATION] nodesFound=${if (videoCandidate != null) 1 else 0}")

        if (videoCandidate == null) {
            Log.w(TAG, "[AUTOMATION] result=NOT_FOUND (No video result card found)")
            return CommandResult(
                handled = true,
                commandName = "PLAY_VIDEO",
                speechResponse = if (query.isNotBlank()) "Could not find video results for $query on YouTube." else "Could not find a video to play on YouTube.",
                success = false,
                status = ExecutionStatus.NOT_FOUND
            )
        }

        val (videoNode, _) = videoCandidate
        val videoTitle = videoNode.text?.toString()?.take(50)
            ?: videoNode.contentDescription?.toString()?.take(50)
            ?: "first video result"

        Log.i(TAG, "[AUTOMATION] selectedNode=$videoTitle")
        Log.i(TAG, "[AUTOMATION] action=ACTION_CLICK")

        val clicked = a11y.clickNodeSafely(videoNode)
        if (!clicked) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (Click action failed)")
            return CommandResult(
                handled = true,
                commandName = "PLAY_VIDEO",
                speechResponse = "Found $videoTitle, but could not play it.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }

        // Verify video player opened
        val verified = a11y.verifyYouTubePlayerOpened(timeoutMs = 4000L)
        Log.i(TAG, "[AUTOMATION] verification=$verified")
        Log.i(TAG, "[AUTOMATION] result=${if (verified) "SUCCESS" else "FAILED"}")

        return if (verified) {
            CommandResult(
                handled = true,
                commandName = "PLAY_VIDEO",
                speechResponse = if (query.isNotBlank()) "Playing $query." else "Playing the first video.",
                success = true,
                status = ExecutionStatus.SUCCESS,
                details = videoTitle
            )
        } else {
            CommandResult(
                handled = true,
                commandName = "PLAY_VIDEO",
                speechResponse = "Tapped video on YouTube, but playback could not be confirmed.",
                success = false,
                status = ExecutionStatus.FAILED,
                details = videoTitle
            )
        }
    }

    /**
     * Executes YouTube Search via reliable ACTION_VIEW intent with package preference and browser fallback.
     */
    private fun executeYouTubeSearch(query: String): Boolean {
        val searchUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val uri = Uri.parse(searchUrl)
        return try {
            val primaryIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(primaryIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Primary YouTube app launch failed, falling back to browser: ${e.message}")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback YouTube search failed: ${e2.message}")
                false
            }
        }
    }

    /**
     * WhatsApp handler supporting multi-language voice directives:
     * - "Send hello to Mom on WhatsApp"
     * - "Send hello to Mom"
     * - "Message Dad saying I'm coming home"
     * - "Send I'm coming home to Dad"
     * - "whatsapp par ram ne message moklo hello" (Gujarati)
     * - "whatsapp par mom ko message bhejo hello" (Hindi)
     */
    private suspend fun handleWhatsApp(input: String): CommandResult? {
        val waPackage = "com.whatsapp"

        if (input == "open whatsapp" || input == "launch whatsapp" || input == "start whatsapp" || input == "whatsapp kholo" || input == "whatsapp chalu karo") {
            return openApp(waPackage, "WhatsApp")
        }

        val parsed = parseSendMessageCommand(input)
        if (parsed != null) {
            val (recipient, messageText) = parsed
            return executeSendMessage(recipient, messageText)
        }

        if (input == "whatsapp") {
            return openApp(waPackage, "WhatsApp")
        }

        return null
    }

    /**
     * Comprehensive voice message command parser covering natural English, Gujarati, and Hindi formulations.
     */
    private fun parseSendMessageCommand(input: String): Pair<String, String>? {
        fun sanitize(r: String, m: String): Pair<String, String>? {
            val rec = r.trim('\'', '"', '“', '”', '`').trim()
            val msg = m.trim('\'', '"', '“', '”', '`').trim()
            if (rec.isNotBlank()) return Pair(rec, msg)
            return null
        }

        // 1. "send message to <recipient> on whatsapp saying/that/: <msg>"
        Regex("""^send\s+(?:a\s+)?message\s+to\s+(.*?)\s+on\s+whatsapp\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 2. "send message to <recipient> saying/that/: <msg>"
        Regex("""^send\s+(?:a\s+)?message\s+to\s+(.*?)\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 3. "message <recipient> on whatsapp saying/that/: <msg>"
        Regex("""^message\s+(.*?)\s+on\s+whatsapp\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 4. "message <recipient> saying/that/: <msg>"
        Regex("""^message\s+(.*?)\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 5. "send whatsapp message to <recipient> saying/that/: <msg>"
        Regex("""^send\s+whatsapp\s+(?:message\s+)?to\s+(.*?)\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 6. "send <recipient> a message on whatsapp saying/that/: <msg>"
        Regex("""^send\s+(.*?)\s+a\s+(?:whatsapp\s+)?message\s+(?:on\s+whatsapp\s+)?(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 7. "whatsapp <recipient> saying/that/: <msg>"
        Regex("""^whatsapp\s+(.*?)\s+(?:saying|that|:)\s+(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 8. "send <msg> to <recipient> on whatsapp" (e.g. "send 'i'm on my way' to john on whatsapp", "send hello to mom on whatsapp")
        Regex("""^send\s+(.*?)\s+to\s+(.*?)\s+on\s+whatsapp$""").find(input)?.let {
            val msg = it.groupValues[1].trim()
            val recipient = it.groupValues[2].trim()
            if (recipient.isNotBlank() && msg.isNotBlank()) return sanitize(recipient, msg)
        }

        // 9. "send <msg> to <recipient>" (e.g. "send hello to mom", "send 'i'm on my way' to john", "send i'm coming home to dad")
        Regex("""^send\s+(.*?)\s+to\s+(.*)$""").find(input)?.let {
            val msg = it.groupValues[1].trim()
            val recipient = it.groupValues[2].trim()
            // Avoid capturing commands like "send sms to" or "send email to"
            if (recipient.isNotBlank() && msg.isNotBlank() && msg != "sms" && msg != "text" && msg != "message" && msg != "a message") {
                return sanitize(recipient, msg)
            }
        }

        // 10. Gujarati: "whatsapp par/pe/ma <recipient> ne message moklo/karo <msg>"
        Regex("""^whatsapp\s+(?:par|pe|ma)\s+(.*?)\s+ne\s+message\s+(?:moklo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 11. Gujarati: "<recipient> ne whatsapp par/pe/ma message moklo/karo <msg>"
        Regex("""^(.*?)\s+ne\s+whatsapp\s+(?:par|pe|ma)\s+message\s+(?:moklo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 12. Gujarati: "<recipient> ne message moklo/karo <msg>"
        Regex("""^(.*?)\s+ne\s+message\s+(?:moklo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 13. Hindi: "whatsapp par/pe <recipient> ko message bhejo/karo <msg>"
        Regex("""^whatsapp\s+(?:par|pe)\s+(.*?)\s+ko\s+message\s+(?:bhejo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 14. Hindi: "<recipient> ko whatsapp par/pe message bhejo/karo <msg>"
        Regex("""^(.*?)\s+ko\s+whatsapp\s+(?:par|pe)\s+message\s+(?:bhejo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 15. Hindi: "<recipient> ko message bhejo/karo <msg>"
        Regex("""^(.*?)\s+ko\s+message\s+(?:bhejo|karo)\s*(.*)$""").find(input)?.let {
            return sanitize(it.groupValues[1], it.groupValues[2])
        }

        // 16. "send whatsapp message to <recipient>" (without prefilled text)
        Regex("""^send\s+whatsapp\s+(?:message\s+)?to\s+(.*)$""").find(input)?.let {
            val recipient = it.groupValues[1].trim()
            if (recipient.isNotBlank() && recipient != "message") return sanitize(recipient, "")
        }

        return null
    }

    /**
     * Executes SEND_MESSAGE automation with full structured verification:
     * 1. Resolves recipient with disambiguation and permission checks
     * 2. Opens WhatsApp chat directly via prefilled URL intent
     * 3. Waits for WhatsApp active window
     * 4. Verifies correct contact chat is active and number is registered
     * 5. Locates editable input field and verifies/inserts message text
     * 6. Locates and safely clicks the Send button
     * 7. Verifies message sent via empty composer and conversation bubble
     */
    private suspend fun executeSendMessage(recipient: String, messageText: String): CommandResult {
        val waPackage = "com.whatsapp"
        Log.i(TAG, "[AUTOMATION] command=SEND_MESSAGE")
        Log.i(TAG, "[AUTOMATION] targetApp=$waPackage")

        val a11y = JarvisAccessibilityService.instance
        Log.i(TAG, "[AUTOMATION] accessibilityEnabled=${a11y != null}")

        if (a11y == null) {
            Log.w(TAG, "[AUTOMATION] result=PERMISSION_REQUIRED (Accessibility Service is not enabled)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Please enable JARVIS in Accessibility settings to send WhatsApp messages automatically.",
                success = false,
                status = ExecutionStatus.PERMISSION_REQUIRED
            )
        }

        val pm = context.packageManager
        val isInstalled = pm.getLaunchIntentForPackage(waPackage) != null
        if (!isInstalled) {
            Log.w(TAG, "[AUTOMATION] result=APP_NOT_FOUND (WhatsApp is not installed)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "WhatsApp is not installed on this device.",
                success = false,
                status = ExecutionStatus.APP_NOT_FOUND
            )
        }

        val cleanRecipient = recipient.trim('\'', '"', '“', '”', '`').trim()
        val cleanMsg = messageText.trim('\'', '"', '“', '”', '`').trim()

        // Contact resolution with disambiguation
        val resolution = resolveContactDetails(cleanRecipient)
        val targetName: String
        val targetNumber: String

        when (resolution) {
            is ContactResolution.PermissionMissing -> {
                Log.w(TAG, "[AUTOMATION] result=PERMISSION_REQUIRED (Contacts permission missing)")
                return CommandResult(
                    handled = true,
                    commandName = "SEND_MESSAGE",
                    speechResponse = "Please grant Contacts permission to message $cleanRecipient.",
                    success = false,
                    status = ExecutionStatus.PERMISSION_REQUIRED
                )
            }
            is ContactResolution.NotFound -> {
                Log.w(TAG, "[AUTOMATION] result=TARGET_NOT_FOUND (Recipient not found in contacts)")
                return CommandResult(
                    handled = true,
                    commandName = "SEND_MESSAGE",
                    speechResponse = "Could not find a phone number for $cleanRecipient in your contacts.",
                    success = false,
                    status = ExecutionStatus.TARGET_NOT_FOUND
                )
            }
            is ContactResolution.Multiple -> {
                val names = resolution.matches.map { it.first }.distinct().take(3).joinToString(" or ")
                Log.w(TAG, "[AUTOMATION] result=AMBIGUOUS (Multiple contacts matched: $names)")
                return CommandResult(
                    handled = true,
                    commandName = "SEND_MESSAGE",
                    speechResponse = "I found multiple contacts for $cleanRecipient: $names. Which one would you like to message?",
                    success = false,
                    status = ExecutionStatus.AMBIGUOUS
                )
            }
            is ContactResolution.Single -> {
                targetName = resolution.name
                targetNumber = resolution.number
            }
        }

        val digitsOnly = targetNumber.replace(Regex("[^0-9]"), "")
        val cleanPhone = if (digitsOnly.length == 10 && (digitsOnly.startsWith("6") || digitsOnly.startsWith("7") || digitsOnly.startsWith("8") || digitsOnly.startsWith("9"))) {
            "91$digitsOnly"
        } else {
            digitsOnly
        }

        // Launch conversation via URL intent
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(cleanMsg)}")
        var launched = false
        try {
            val primaryIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(waPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(primaryIntent)
            launched = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed launching WhatsApp with package: ${e.message}, trying fallback")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                launched = true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed launching WhatsApp fallback: ${e2.message}")
            }
        }

        if (!launched) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (Could not launch WhatsApp intent)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Failed to launch WhatsApp for $targetName.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }

        // Wait up to 4000ms for WhatsApp to foreground
        val foregrounded = a11y.waitForPackage(waPackage, timeoutMs = 4000L)
        if (!foregrounded) {
            Log.w(TAG, "[AUTOMATION] result=TIMEOUT (WhatsApp failed to foreground within 4s)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "WhatsApp took too long to open.",
                success = false,
                status = ExecutionStatus.TIMEOUT
            )
        }

        // Brief delay for chat container to settle
        delay(700)

        // Verify correct contact chat is open and phone is registered on WhatsApp
        val chatStatus = a11y.verifyWhatsAppChatOpen(targetName)
        if (chatStatus is WhatsAppChatStatus.INVALID_NUMBER) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (${chatStatus.message})")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "$targetName's phone number is not registered on WhatsApp.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }

        // If cleanMsg is blank, we just open the conversation
        if (cleanMsg.isBlank()) {
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Opened WhatsApp chat for $targetName.",
                success = true,
                status = ExecutionStatus.SUCCESS
            )
        }

        // Ensure text is present in the composer
        val inputField = a11y.findMessageInput()
        if (inputField != null) {
            val currentText = inputField.text?.toString() ?: ""
            if (currentText.isBlank()) {
                a11y.setInputFieldText(inputField, cleanMsg)
                delay(300)
            }
        }

        // Locate real Send button
        val sendBtn = a11y.findSendButton()
        Log.i(TAG, "[AUTOMATION] nodesFound=${if (sendBtn != null) 1 else 0}")

        if (sendBtn == null) {
            Log.w(TAG, "[AUTOMATION] result=TARGET_NOT_FOUND (Send button not found)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Opened WhatsApp chat for $targetName, but could not find the Send button.",
                success = false,
                status = ExecutionStatus.TARGET_NOT_FOUND
            )
        }

        val sendLabel = sendBtn.contentDescription?.toString() ?: sendBtn.viewIdResourceName ?: "Send"
        Log.i(TAG, "[AUTOMATION] selectedNode=$sendLabel")
        Log.i(TAG, "[AUTOMATION] action=ACTION_CLICK")

        val clicked = a11y.clickNodeSafely(sendBtn)
        if (!clicked) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (Click action failed on Send button)")
            return CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Failed to click Send in WhatsApp.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }

        // Verify message was actually sent
        val verified = a11y.verifyMessageSent(cleanMsg, timeoutMs = 3500L)
        Log.i(TAG, "[AUTOMATION] verification=$verified")
        Log.i(TAG, "[AUTOMATION] result=${if (verified) "SUCCESS" else "SENT_UNVERIFIED"}")

        return if (verified) {
            CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Message sent to $targetName.",
                success = true,
                status = ExecutionStatus.SUCCESS,
                details = targetName
            )
        } else {
            CommandResult(
                handled = true,
                commandName = "SEND_MESSAGE",
                speechResponse = "Tapped Send in WhatsApp, but could not verify that the message was sent to $targetName.",
                success = false,
                status = ExecutionStatus.SENT_UNVERIFIED,
                details = targetName
            )
        }
    }

    /**
     * Random safe UI tap handler:
     * Discovers clickable nodes on the current screen, excludes all dangerous actions,
     * picks one safe element, taps it, and reports the action.
     */
    private fun handleRandomSafeUiTap(input: String): CommandResult? {
        val tapPatterns = listOf(
            Regex("""^tap\s+(?:a\s+)?random\s+(?:ui|element|button|item)$"""),
            Regex("""^tap\s+random\s+ui$"""),
            Regex("""^tap\s+something\s+random$"""),
            Regex("""^tap\s+random$"""),
            Regex("""^click\s+(?:a\s+)?random\s+(?:ui|element|button|item)$"""),
            Regex("""^click\s+random\s+ui$"""),
            Regex("""^click\s+something\s+random$"""),
            Regex("""^click\s+random$"""),
            Regex("""^random\s+ui\s+tap$"""),
            Regex("""^random\s+safe\s+tap$"""),
            Regex("""^random\s+tap$"""),
            Regex("""^random\s+click$"""),
            Regex("""^tap\s+something$"""),
            Regex("""^click\s+something$""")
        )

        for (pattern in tapPatterns) {
            if (pattern.matches(input)) {
                return executeRandomSafeUiTap()
            }
        }
        return null
    }

    private fun executeRandomSafeUiTap(): CommandResult {
        Log.w(TAG, "[AUTOMATION] Random tapping requested but rejected for safety.")
        return CommandResult(
            handled = true,
            commandName = "RANDOM_SAFE_UI_TAP",
            speechResponse = "Arbitrary screen tapping is disabled for safety. Please specify which exact button or element you want me to tap.",
            success = false,
            status = ExecutionStatus.NOT_SUPPORTED
        )
    }

    /**
     * Web and Online Search handling:
     * - "Search the web for..." -> Live on-device multi-source search (DuckDuckGo, Wikipedia) with real results & sources
     * - "Find the latest information about..." -> Live web search
     * - "Look up ... online" -> Live web search
     * - "Search Google for..." -> Opens Google in Android browser
     */
    private fun handleWebSearch(input: String): CommandResult? {
        // 1. Direct Web Search commands
        val webSearchPatterns = listOf(
            Regex("^search the web for (.*)$"),
            Regex("^search web for (.*)$"),
            Regex("^search online for (.*)$"),
            Regex("^find the latest information (?:about|on) (.*)$"),
            Regex("^find information (?:about|on) (.*)$"),
            Regex("^look up (.*) online$"),
            Regex("^find websites (?:about|for) (.*)$"),
            Regex("^search for (.*) online$")
        )

        for (pattern in webSearchPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                val query = if (match.groupValues.size > 1) match.groupValues[1].trim() else ""
                val effectiveQuery = if (query.isNotBlank()) query else "latest updates"
                if (!effectiveQuery.contains("youtube") && !effectiveQuery.contains("spotify")) {
                    return executeLiveWebSearch(effectiveQuery)
                }
            }
        }

        // 2. Explicit Browser Google Search commands
        val googlePrefixes = listOf("search google for ", "google search ", "google ", "open google for ")
        for (prefix in googlePrefixes) {
            if (input.startsWith(prefix)) {
                val query = input.removePrefix(prefix).trim()
                if (query.isNotBlank() && !query.contains("youtube") && !query.contains("spotify")) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    return try {
                        context.startActivity(intent)
                        CommandResult(
                            handled = true,
                            commandName = "google_search",
                            speechResponse = "Opening Google search for $query in your browser."
                        )
                    } catch (e: Exception) {
                        CommandResult(
                            handled = true,
                            commandName = "google_search",
                            speechResponse = "Failed to launch web browser: ${e.message}.",
                            success = false
                        )
                    }
                }
            }
        }

        return null
    }

    private fun executeLiveWebSearch(query: String): CommandResult {
        return try {
            val (success, content) = kotlinx.coroutines.runBlocking {
                webSearchService.search(query)
            }
            if (success) {
                val firstSnippet = content.lines()
                    .firstOrNull { it.trim().startsWith("• Snippet:") }
                    ?.removePrefix("• Snippet:")?.trim()
                    ?: content.lines().firstOrNull { it.contains("1. ") }?.trim()
                    ?: "Search results retrieved."

                val spoken = "According to web search results: ${firstSnippet.take(160)}. Full sources and links are displayed on screen."
                CommandResult(
                    handled = true,
                    commandName = "web_search",
                    speechResponse = spoken,
                    details = content,
                    success = true
                )
            } else {
                CommandResult(
                    handled = true,
                    commandName = "web_search",
                    speechResponse = "I was unable to retrieve search results for $query. Please verify your internet connection.",
                    details = content,
                    success = false
                )
            }
        } catch (e: Exception) {
            CommandResult(
                handled = true,
                commandName = "web_search",
                speechResponse = "Encountered a network error while searching the web.",
                details = e.message,
                success = false
            )
        }
    }

    /**
     * Spotify & General Media Playback
     */
    private fun handleMedia(input: String): CommandResult? {
        // Spotify
        if (input == "open spotify" || input == "launch spotify") {
            return openApp("com.spotify.music", "Spotify")
        }

        val spotifyPlayPatterns = listOf(
            Regex("^play (.*?) on spotify$"),
            Regex("^spotify play (.*)$")
        )
        for (p in spotifyPlayPatterns) {
            val match = p.find(input)
            if (match != null) {
                val song = match.groupValues[1].trim()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return try {
                    context.startActivity(intent)
                    CommandResult(
                        handled = true,
                        commandName = "spotify_play",
                        speechResponse = "Opening $song on Spotify."
                    )
                } catch (_: Exception) {
                    openApp("com.spotify.music", "Spotify")
                }
            }
        }

        // Media transport controls
        when (input) {
            "play music", "resume music", "play song" -> {
                mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                return CommandResult(handled = true, commandName = "media_play", speechResponse = "Resuming music playback.")
            }
            "pause music", "stop music", "pause playback" -> {
                mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                return CommandResult(handled = true, commandName = "media_pause", speechResponse = "Pausing music playback.")
            }
            "next song", "next track", "skip song", "skip track" -> {
                mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                return CommandResult(handled = true, commandName = "media_next", speechResponse = "Skipping to next track.")
            }
            "previous song", "previous track" -> {
                mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                return CommandResult(handled = true, commandName = "media_prev", speechResponse = "Returning to previous track.")
            }
            "volume up", "increase volume", "turn it up", "louder" -> {
                mediaHelper.adjustVolume("UP")
                return CommandResult(handled = true, commandName = "vol_up", speechResponse = "Increasing media volume.")
            }
            "volume down", "decrease volume", "turn it down", "softer" -> {
                mediaHelper.adjustVolume("DOWN")
                return CommandResult(handled = true, commandName = "vol_down", speechResponse = "Decreasing media volume.")
            }
            "mute", "silence", "mute media" -> {
                mediaHelper.setMute(true)
                return CommandResult(handled = true, commandName = "mute", speechResponse = "Media volume muted.")
            }
            "unmute" -> {
                mediaHelper.setMute(false)
                return CommandResult(handled = true, commandName = "unmute", speechResponse = "Media volume unmuted.")
            }
        }

        return null
    }

    /**
     * Phone & Calling
     */
    private fun handlePhone(input: String): CommandResult? {
        val callPatterns = listOf(
            Regex("^call (.*)$"),
            Regex("^dial (.*)$"),
            Regex("^phone (.*)$")
        )
        for (p in callPatterns) {
            val match = p.find(input)
            if (match != null) {
                val target = match.groupValues[1].trim()
                if (target.isBlank() || target == "phone") continue

                if (target.matches(Regex("^[+0-9\\s\\-]+$"))) {
                    val number = target.replace(Regex("[^0-9+]"), "")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    return try {
                        context.startActivity(intent)
                        CommandResult(
                            handled = true,
                            commandName = "dial_phone",
                            speechResponse = "Opening phone dialer for $target.",
                            status = ExecutionStatus.SUCCESS
                        )
                    } catch (e: Exception) {
                        CommandResult(
                            handled = true,
                            commandName = "dial_phone",
                            speechResponse = "Unable to open dialer: ${e.message}.",
                            success = false,
                            status = ExecutionStatus.FAILED
                        )
                    }
                }

                return when (val resolution = resolveContactDetails(target)) {
                    is ContactResolution.PermissionMissing -> {
                        CommandResult(
                            handled = true,
                            commandName = "dial_phone",
                            speechResponse = "Please grant Contacts permission to call $target.",
                            success = false,
                            status = ExecutionStatus.PERMISSION_REQUIRED
                        )
                    }
                    is ContactResolution.NotFound -> {
                        CommandResult(
                            handled = true,
                            commandName = "dial_phone",
                            speechResponse = "Could not find a phone number for $target in contacts.",
                            success = false,
                            status = ExecutionStatus.TARGET_NOT_FOUND
                        )
                    }
                    is ContactResolution.Multiple -> {
                        val names = resolution.matches.map { it.first }.distinct().take(3).joinToString(" or ")
                        CommandResult(
                            handled = true,
                            commandName = "dial_phone",
                            speechResponse = "I found multiple contacts for $target: $names. Which one would you like to call?",
                            success = false,
                            status = ExecutionStatus.AMBIGUOUS
                        )
                    }
                    is ContactResolution.Single -> {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${resolution.number}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                            CommandResult(
                                handled = true,
                                commandName = "dial_phone",
                                speechResponse = "Opening phone dialer for ${resolution.name}.",
                                status = ExecutionStatus.SUCCESS
                            )
                        } catch (e: Exception) {
                            CommandResult(
                                handled = true,
                                commandName = "dial_phone",
                                speechResponse = "Unable to open dialer: ${e.message}.",
                                success = false,
                                status = ExecutionStatus.FAILED
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Messages & SMS
     */
    private fun handleMessaging(input: String): CommandResult? {
        if (input == "open messages" || input == "open sms") {
            return openApp("com.google.android.apps.messaging", "Messages")
        }

        val smsPatterns = listOf(
            Regex("^send sms to (.*?) saying (.*)$"),
            Regex("^send text to (.*?) saying (.*)$"),
            Regex("^text (.*?) saying (.*)$")
        )
        for (p in smsPatterns) {
            val match = p.find(input)
            if (match != null) {
                val target = match.groupValues[1].trim()
                val messageText = match.groupValues[2].trim()

                if (target.matches(Regex("^[+0-9\\s\\-]+$"))) {
                    val number = target.replace(Regex("[^0-9+]"), "")
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        putExtra("sms_body", messageText)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    return try {
                        context.startActivity(intent)
                        CommandResult(
                            handled = true,
                            commandName = "send_sms",
                            speechResponse = "Opening SMS composer for $target.",
                            status = ExecutionStatus.SUCCESS
                        )
                    } catch (e: Exception) {
                        CommandResult(
                            handled = true,
                            commandName = "send_sms",
                            speechResponse = "Failed to launch SMS composer: ${e.message}.",
                            success = false,
                            status = ExecutionStatus.FAILED
                        )
                    }
                }

                return when (val resolution = resolveContactDetails(target)) {
                    is ContactResolution.PermissionMissing -> {
                        CommandResult(
                            handled = true,
                            commandName = "send_sms",
                            speechResponse = "Please grant Contacts permission to message $target.",
                            success = false,
                            status = ExecutionStatus.PERMISSION_REQUIRED
                        )
                    }
                    is ContactResolution.NotFound -> {
                        CommandResult(
                            handled = true,
                            commandName = "send_sms",
                            speechResponse = "Could not find a phone number for $target in contacts.",
                            success = false,
                            status = ExecutionStatus.TARGET_NOT_FOUND
                        )
                    }
                    is ContactResolution.Multiple -> {
                        val names = resolution.matches.map { it.first }.distinct().take(3).joinToString(" or ")
                        CommandResult(
                            handled = true,
                            commandName = "send_sms",
                            speechResponse = "I found multiple contacts for $target: $names. Which one would you like to text?",
                            success = false,
                            status = ExecutionStatus.AMBIGUOUS
                        )
                    }
                    is ContactResolution.Single -> {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${resolution.number}")).apply {
                            putExtra("sms_body", messageText)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                            CommandResult(
                                handled = true,
                                commandName = "send_sms",
                                speechResponse = "Opening SMS composer for ${resolution.name}.",
                                status = ExecutionStatus.SUCCESS
                            )
                        } catch (e: Exception) {
                            CommandResult(
                                handled = true,
                                commandName = "send_sms",
                                speechResponse = "Failed to launch SMS composer: ${e.message}.",
                                success = false,
                                status = ExecutionStatus.FAILED
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * System Hardware & Settings Controls
     */
    private fun handleSystemControls(input: String): CommandResult? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 1. Volume percentage adjustment (e.g., "increase volume to 70%", "set volume to 70%", "volume 50%")
        val volPctMatch = Regex("""(?:set|increase|decrease|change|turn|adjust)?\s*volume\s*(?:to|at)?\s*(\d{1,3})\s*%?""", RegexOption.IGNORE_CASE).find(input)
        if (volPctMatch != null) {
            val pct = volPctMatch.groupValues[1].toIntOrNull()
            if (pct != null && audioManager != null) {
                val clampedPct = pct.coerceIn(0, 100)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVol = (clampedPct * maxVol) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                return CommandResult(
                    handled = true,
                    commandName = "set_volume",
                    speechResponse = "Volume set to $clampedPct percent."
                )
            }
        }

        // 2. Relative Volume controls & Mute / Unmute
        if (input in listOf("volume up", "increase volume", "turn volume up", "sound up", "louder", "awaaz badhao", "awaaz tej karo")) {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return CommandResult(handled = true, commandName = "volume_up", speechResponse = "Volume increased.")
        }
        if (input in listOf("volume down", "decrease volume", "turn volume down", "sound down", "softer", "awaaz kam karo", "awaaz dheemi karo")) {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return CommandResult(handled = true, commandName = "volume_down", speechResponse = "Volume decreased.")
        }
        if (input in listOf("mute", "mute sound", "mute volume", "silence audio", "awaaz band karo")) {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return CommandResult(handled = true, commandName = "mute", speechResponse = "Volume muted.")
        }
        if (input in listOf("unmute", "unmute sound", "unmute volume", "awaaz chalu karo")) {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            return CommandResult(handled = true, commandName = "unmute", speechResponse = "Volume unmuted.")
        }

        // 3. Screen Lock
        if (input in listOf("lock screen", "lock phone", "lock device", "phone lock karo", "screen lock")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val locked = a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                return if (locked) {
                    CommandResult(handled = true, commandName = "lock_screen", speechResponse = "Screen locked.")
                } else {
                    CommandResult(handled = true, commandName = "lock_screen", speechResponse = "Unable to lock screen.")
                }
            } else {
                return CommandResult(
                    handled = true,
                    commandName = "lock_screen",
                    speechResponse = "Please enable Jarvis Accessibility Service to lock the screen automatically."
                )
            }
        }

        // 4. Go Home / Minimize / Close Current Screen
        if (input in listOf("close app", "close this app", "exit app", "go home", "home screen", "minimize")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                return CommandResult(handled = true, commandName = "go_home", speechResponse = "Returned to home screen.")
            } else {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return CommandResult(handled = true, commandName = "go_home", speechResponse = "Returned to home screen.")
            }
        }

        // 5. Silent Mode & Do Not Disturb
        if (input in listOf("silent mode", "enable silent mode", "turn on silent mode", "mute ringer", "do not disturb", "enable dnd", "dnd on")) {
            return try {
                audioManager?.ringerMode = AudioManager.RINGER_MODE_SILENT
                CommandResult(handled = true, commandName = "silent_mode", speechResponse = "Silent mode enabled.")
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                CommandResult(handled = true, commandName = "silent_mode", speechResponse = "Opening sound settings to configure Do Not Disturb.")
            }
        }

        // 6. Critical confirmation prompts for risky actions
        if (input in listOf("restart phone", "restart device", "reboot phone", "reboot device")) {
            return CommandResult(
                handled = true,
                commandName = "device_restart_confirm",
                speechResponse = "Restarting is a critical action. Are you sure you want to restart your device? Please say 'Confirm restart' to proceed.",
                status = ExecutionStatus.NEEDS_CLARIFICATION
            )
        }
        if (input in listOf("shutdown phone", "shutdown device", "power off", "turn off phone", "switch off phone")) {
            return CommandResult(
                handled = true,
                commandName = "device_shutdown_confirm",
                speechResponse = "Powering off is a critical action. Are you sure you want to shut down your device? Please say 'Confirm shutdown' to proceed.",
                status = ExecutionStatus.NEEDS_CLARIFICATION
            )
        }
        if (input in listOf("factory reset", "format phone", "wipe data", "delete all files")) {
            return CommandResult(
                handled = true,
                commandName = "risky_wipe_confirm",
                speechResponse = "This is a dangerous action that can cause permanent data loss. Are you completely sure? Please confirm explicitly.",
                status = ExecutionStatus.NEEDS_CLARIFICATION
            )
        }

        // Confirmed restart and shutdown execution
        if (input in listOf("confirm restart", "confirm reboot", "yes restart", "yes reboot")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performSystemAction("POWER_DIALOG")
                return CommandResult(
                    handled = true,
                    commandName = "device_restart_execute",
                    speechResponse = "Opening power menu. Tap restart to reboot your device.",
                    status = ExecutionStatus.SUCCESS
                )
            } else {
                return CommandResult(
                    handled = true,
                    commandName = "device_restart_execute",
                    speechResponse = "Accessibility Service is required to display the power dialog.",
                    status = ExecutionStatus.PERMISSION_REQUIRED
                )
            }
        }
        if (input in listOf("confirm shutdown", "confirm power off", "yes shutdown", "yes power off")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performSystemAction("POWER_DIALOG")
                return CommandResult(
                    handled = true,
                    commandName = "device_shutdown_execute",
                    speechResponse = "Opening power menu. Tap power off to shut down your device.",
                    status = ExecutionStatus.SUCCESS
                )
            } else {
                return CommandResult(
                    handled = true,
                    commandName = "device_shutdown_execute",
                    speechResponse = "Accessibility Service is required to display the power dialog.",
                    status = ExecutionStatus.PERMISSION_REQUIRED
                )
            }
        }

        // System Navigation & Accessibility Actions
        if (input in listOf("open notifications", "show notifications", "notifications", "notification shade")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performSystemAction("NOTIFICATIONS")
                return CommandResult(handled = true, commandName = "notifications", speechResponse = "Opening notifications.")
            }
        }
        if (input in listOf("open quick settings", "quick settings", "open control center", "control center")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performSystemAction("QUICK_SETTINGS")
                return CommandResult(handled = true, commandName = "quick_settings", speechResponse = "Opening Quick Settings.")
            }
        }
        if (input in listOf("recent apps", "recents", "open recents", "app switcher", "overview")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.performSystemAction("RECENTS")
                return CommandResult(handled = true, commandName = "recents", speechResponse = "Opening recent applications.")
            }
        }
        if (input in listOf("take screenshot", "screenshot", "capture screen", "screen capture")) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val taken = a11y.performSystemAction("TAKE_SCREENSHOT")
                return if (taken) {
                    CommandResult(handled = true, commandName = "screenshot", speechResponse = "Taking screenshot.")
                } else {
                    CommandResult(handled = true, commandName = "screenshot", speechResponse = "Unable to capture screenshot.")
                }
            } else {
                return CommandResult(handled = true, commandName = "screenshot", speechResponse = "Screenshot requires Android 9+ with Accessibility Service enabled.")
            }
        }

        when {
            input == "open settings" || input == "device settings" || input == "phone settings" -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return CommandResult(handled = true, commandName = "settings", speechResponse = "Opening device Settings.")
            }
            input.contains("bluetooth") && (input.contains("open") || input.contains("turn on") || input.contains("settings")) -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return CommandResult(handled = true, commandName = "bluetooth", speechResponse = "Opening Bluetooth settings.")
            }
            input.contains("wifi") && (input.contains("open") || input.contains("turn on") || input.contains("settings")) -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return CommandResult(handled = true, commandName = "wifi", speechResponse = "Opening Wi-Fi settings.")
            }
            input.contains("brightness") || input == "display settings" -> {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                return CommandResult(handled = true, commandName = "brightness", speechResponse = "Opening display and brightness settings.")
            }
            input.contains("hotspot") || input.contains("tethering") -> {
                val intent = Intent().apply {
                    setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return try {
                    context.startActivity(intent)
                    CommandResult(handled = true, commandName = "hotspot", speechResponse = "Opening Hotspot settings.")
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(fallbackIntent)
                    CommandResult(handled = true, commandName = "hotspot", speechResponse = "Opening wireless and network settings.")
                }
            }
            input.contains("mobile data") || input.contains("cellular data") -> {
                val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                return try {
                    context.startActivity(intent)
                    CommandResult(handled = true, commandName = "mobile_data", speechResponse = "Opening mobile network settings.")
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(fallbackIntent)
                    CommandResult(handled = true, commandName = "mobile_data", speechResponse = "Opening network settings.")
                }
            }
            input in listOf("turn on flashlight", "turn on torch", "flashlight on", "torch on") -> {
                return toggleFlashlight(true)
            }
            input in listOf("turn off flashlight", "turn off torch", "flashlight off", "torch off") -> {
                return toggleFlashlight(false)
            }
            input in listOf("battery status", "check battery", "battery level", "how much battery") -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = bm?.isCharging == true
                val statusText = if (level >= 0) {
                    "Battery is at $level percent${if (isCharging) ", charging" else ""}."
                } else {
                    "Unable to read battery level."
                }
                return CommandResult(handled = true, commandName = "battery", speechResponse = statusText)
            }
            input in listOf("what time is it", "time", "current time", "what's the time") -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                return CommandResult(handled = true, commandName = "time", speechResponse = "The current time is $timeStr.")
            }
            input in listOf("what is today's date", "what is the date", "date", "today's date", "what day is today") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                return CommandResult(handled = true, commandName = "date", speechResponse = "Today is $dateStr.")
            }
        }
        return null
    }

    private fun toggleFlashlight(enable: Boolean): CommandResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null && cameraManager != null) {
                cameraManager.setTorchMode(cameraId, enable)
                isTorchOn = enable
                CommandResult(
                    handled = true,
                    commandName = "flashlight",
                    speechResponse = if (enable) "Flashlight turned on." else "Flashlight turned off."
                )
            } else {
                CommandResult(
                    handled = true,
                    commandName = "flashlight",
                    speechResponse = "Flashlight hardware is unavailable on this device.",
                    success = false
                )
            }
        } catch (e: Exception) {
            CommandResult(
                handled = true,
                commandName = "flashlight",
                speechResponse = "Unable to toggle flashlight: ${e.message}.",
                success = false
            )
        }
    }

    /**
     * General App Launcher:
     * "open <app>", "launch <app>", "start <app>"
     */
    private fun handleAppLaunching(input: String): CommandResult? {
        val openPrefixes = listOf("open ", "launch ", "start ")
        for (prefix in openPrefixes) {
            if (input.startsWith(prefix)) {
                val appTarget = input.removePrefix(prefix).trim()
                if (appTarget.isNotBlank()) {
                    return openApp(appTarget)
                }
            }
        }
        return null
    }

    /**
     * Centralized Android app launcher complying with user spec:
     * - Reusable function
     * - Proper handling when app exists, not installed, or Intent unresolvable
     * - Returns a clear result for automatic TTS response
     */
    fun openApp(appNameOrPackage: String, friendlyName: String? = null): CommandResult {
        val trimmed = appNameOrPackage.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val pm = context.packageManager

        // Special system intent mappings
        when {
            lower.contains("camera") -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return if (intent.resolveActivity(pm) != null) {
                    try {
                        context.startActivity(intent)
                        CommandResult(true, "open_camera", "Opening Camera.")
                    } catch (e: Exception) {
                        CommandResult(true, "open_camera", "Failed to open camera: ${e.message}.", false)
                    }
                } else {
                    CommandResult(true, "open_camera", "Camera application is not available.", false)
                }
            }
            lower.contains("setting") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return CommandResult(true, "open_settings", "Opening device Settings.")
            }
            lower.contains("calc") -> {
                val calcPackages = listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator")
                for (cp in calcPackages) {
                    val intent = pm.getLaunchIntentForPackage(cp)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return CommandResult(true, "open_calculator", "Opening Calculator.")
                    }
                }
            }
            lower.contains("clock") || lower.contains("alarm") -> {
                val clockPackages = listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage")
                for (cp in clockPackages) {
                    val intent = pm.getLaunchIntentForPackage(cp)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return CommandResult(true, "open_clock", "Opening Clock.")
                    }
                }
            }
        }

        // Direct package alias lookups
        val knownPackage = when {
            lower == "youtube" -> "com.google.android.youtube"
            lower == "whatsapp" -> "com.whatsapp"
            lower == "chrome" || lower == "browser" -> "com.android.chrome"
            lower == "spotify" -> "com.spotify.music"
            lower == "maps" || lower == "map" || lower == "google maps" -> "com.google.android.apps.maps"
            lower == "gmail" || lower == "email" || lower == "mail" -> "com.google.android.gm"
            lower == "calendar" -> "com.google.android.calendar"
            lower == "photos" || lower == "gallery" -> "com.google.android.apps.photos"
            lower == "drive" -> "com.google.android.apps.docs"
            lower == "messages" || lower == "sms" -> "com.google.android.apps.messaging"
            lower == "instagram" -> "com.instagram.android"
            lower == "facebook" -> "com.facebook.katana"
            lower == "telegram" -> "org.telegram.messenger"
            lower == "discord" -> "com.discord"
            lower == "snapchat" -> "com.snapchat.android"
            lower == "netflix" -> "com.netflix.mediaclient"
            lower == "prime video" || lower == "amazon prime" -> "com.amazon.avod.thirdpartyclient"
            lower == "amazon" -> "in.amazon.mShop.android.shopping"
            lower == "flipkart" -> "com.flipkart.android"
            lower == "keep" || lower == "notes" || lower == "google keep" -> "com.google.android.keep"
            lower == "files" || lower == "google files" -> "com.google.android.apps.nbu.files"
            lower.contains(".") && !lower.contains(" ") -> lower
            else -> null
        }

        if (knownPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(knownPackage)
            if (launchIntent != null) {
                return try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val label = friendlyName ?: getAppLabel(knownPackage) ?: trimmed.replaceFirstChar { it.uppercase() }
                    CommandResult(true, "open_app", "Opening $label.")
                } catch (e: Exception) {
                    CommandResult(true, "open_app", "Failed to launch $knownPackage: ${e.message}.", false)
                }
            }
        }

        // Search installed applications dynamically
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val activities = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in activities) {
                val label = resolveInfo.loadLabel(pm).toString()
                if (label.equals(trimmed, ignoreCase = true) ||
                    label.lowercase(Locale.ROOT).contains(lower) ||
                    lower.contains(label.lowercase(Locale.ROOT))
                ) {
                    val pkg = resolveInfo.activityInfo.packageName
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return CommandResult(true, "open_app", "Opening $label.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic app query error: ${e.message}")
        }

        val targetName = friendlyName ?: trimmed.replaceFirstChar { it.uppercase() }
        return CommandResult(
            handled = true,
            commandName = "open_app",
            speechResponse = "$targetName is not installed on this device.",
            success = false
        )
    }

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun resolveContactDetails(recipient: String): ContactResolution {
        if (recipient.matches(Regex("^[+0-9\\s\\-]+$"))) {
            return ContactResolution.Single(recipient, recipient.replace(Regex("[^0-9+]"), ""))
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ContactResolution.PermissionMissing
        }

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val cursor = context.contentResolver.query(
                uri,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$recipient%"),
                null
            )
            val list = mutableListOf<Pair<String, String>>()
            cursor?.use {
                val numIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (it.moveToNext()) {
                    val num = it.getString(numIdx) ?: continue
                    val displayName = it.getString(nameIdx) ?: recipient
                    list.add(Pair(displayName, num))
                }
            }

            if (list.isEmpty()) {
                ContactResolution.NotFound
            } else {
                val distinctByName = list.groupBy { it.first.lowercase(Locale.ROOT) }
                if (distinctByName.size > 1) {
                    ContactResolution.Multiple(list)
                } else {
                    val firstMatch = list.first()
                    ContactResolution.Single(firstMatch.first, firstMatch.second)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving contacts for $recipient: ${e.message}")
            ContactResolution.NotFound
        }
    }

    private fun resolveContactNumber(name: String): String? {
        return when (val res = resolveContactDetails(name)) {
            is ContactResolution.Single -> res.number
            is ContactResolution.Multiple -> res.matches.firstOrNull()?.second
            else -> null
        }
    }
}
