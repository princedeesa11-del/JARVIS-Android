package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Legitimate AccessibilityService for JARVIS assistant.
 * Universal on-screen node inspection, multi-criteria element querying, gesture dispatching,
 * input text entry, and state verification across all accessible Android applications.
 * Strictly adheres to Android security: no keylogging, no password collection, no background surveillance.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (instance == null) {
            instance = this
        }
        // Events are observed transiently for UI updates during active automation
        event?.packageName?.let { pkg ->
            lastActivePackage = pkg.toString()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "JarvisAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Inspects visible interactive and structural nodes on the current screen.
     * Sanitizes sensitive fields: NEVER extracts or records password fields.
     */
    fun inspectCurrentScreen(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        collectNodes(root, elements)
        return elements
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>) {
        // Strictly never inspect or harvest password fields
        if (node.isPassword) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val displayText = text.ifBlank { contentDesc ?: "" }
        if ((displayText.isNotBlank() || isClickable || isEditable) && bounds.width() > 0 && bounds.height() > 0) {
            out.add(
                ScreenElement(
                    text = displayText.take(120),
                    className = node.className?.toString()?.substringAfterLast('.') ?: "",
                    isClickable = isClickable,
                    bounds = "${bounds.left},${bounds.top} to ${bounds.right},${bounds.bottom}",
                    contentDescription = contentDesc,
                    viewId = viewId,
                    isEditable = isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isEnabled = node.isEnabled,
                    packageName = node.packageName?.toString() ?: ""
                )
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectNodes(child, out)
            }
        }
    }

    /**
     * Finds nodes matching a flexible multi-criteria query (text, contentDesc, viewId, class, clickable).
     */
    fun findNodes(query: NodeQuery): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val matches = mutableListOf<AccessibilityNodeInfo>()
        traverseAndMatch(root, query, matches)
        return matches
    }

    private fun traverseAndMatch(node: AccessibilityNodeInfo, query: NodeQuery, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isPassword) return

        if (matchesQuery(node, query)) {
            out.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndMatch(child, query, out)
        }
    }

    private fun matchesQuery(node: AccessibilityNodeInfo, query: NodeQuery): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        val nodeId = node.viewIdResourceName ?: ""
        val nodeClass = node.className?.toString() ?: ""

        if (!query.text.isNullOrBlank()) {
            val target = query.text
            val matchesText = if (query.exactMatch) {
                nodeText.equals(target, ignoreCase = true) || nodeDesc.equals(target, ignoreCase = true)
            } else {
                nodeText.contains(target, ignoreCase = true) || nodeDesc.contains(target, ignoreCase = true)
            }
            if (!matchesText) return false
        }

        if (!query.contentDescription.isNullOrBlank()) {
            val target = query.contentDescription
            val matchesDesc = if (query.exactMatch) {
                nodeDesc.equals(target, ignoreCase = true)
            } else {
                nodeDesc.contains(target, ignoreCase = true)
            }
            if (!matchesDesc) return false
        }

        if (!query.viewId.isNullOrBlank()) {
            val target = query.viewId
            if (!nodeId.contains(target, ignoreCase = true)) return false
        }

        if (!query.className.isNullOrBlank()) {
            if (!nodeClass.contains(query.className, ignoreCase = true)) return false
        }

        if (query.isClickable != null && node.isClickable != query.isClickable) {
            return false
        }

        if (query.isEditable != null && node.isEditable != query.isEditable) {
            return false
        }

        return true
    }

    /**
     * Clicks a node identified by flexible query. If target node isn't directly clickable, walks up parents.
     */
    fun clickNode(query: NodeQuery): AccessibilityActionResult {
        val matches = findNodes(query)
        if (matches.isEmpty()) {
            return AccessibilityActionResult(
                success = false,
                state = "ELEMENT_NOT_FOUND",
                message = "No element matching query '$query' found on screen."
            )
        }

        for (node in matches) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return AccessibilityActionResult(
                    success = true,
                    state = "BUTTON_CLICKED",
                    message = "Successfully clicked element [${node.className}] '${node.text ?: node.contentDescription}'",
                    matchedCount = matches.size
                )
            }
            // Walk up hierarchy to find clickable container (e.g. Card, ListItem, Button wrapper)
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return AccessibilityActionResult(
                        success = true,
                        state = "BUTTON_CLICKED",
                        message = "Clicked clickable parent [${parent.className}] of '${node.text ?: node.contentDescription}'",
                        matchedCount = matches.size
                    )
                }
                parent = parent.parent
            }
        }

        // If node isn't clickable via action, fallback to tap at its screen center bounds using gesture
        val firstNode = matches.first()
        val bounds = Rect()
        firstNode.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val gestured = clickCoordinates(cx, cy)
            if (gestured) {
                return AccessibilityActionResult(
                    success = true,
                    state = "BUTTON_CLICKED",
                    message = "Performed assisted tap at screen coordinates ($cx, $cy)",
                    matchedCount = matches.size
                )
            }
        }

        return AccessibilityActionResult(
            success = false,
            state = "ACTION_FAILED",
            message = "Found matching element, but click action could not be performed."
        )
    }

    /**
     * Backward-compatible simple click by text.
     */
    fun clickNodeWithText(targetText: String): Boolean {
        val res = clickNode(NodeQuery(text = targetText))
        return res.success
    }

    /**
     * Clicks an element by visible text, retrying with short delays as the UI renders.
     */
    suspend fun clickByText(text: String, retries: Int = 3, retryDelayMs: Long = 400L): Boolean {
        for (attempt in 1..retries) {
            val res = clickNode(NodeQuery(text = text, exactMatch = false))
            if (res.success) {
                Log.i(TAG, "clickByText('$text') succeeded on attempt $attempt")
                return true
            }
            if (attempt < retries) {
                delay(retryDelayMs)
            }
        }
        Log.w(TAG, "clickByText('$text') failed after $retries attempts")
        return false
    }

    /**
     * Clicks an element by content description, retrying with short delays as the UI renders.
     */
    suspend fun clickByContentDescription(desc: String, retries: Int = 3, retryDelayMs: Long = 400L): Boolean {
        for (attempt in 1..retries) {
            val res = clickNode(NodeQuery(contentDescription = desc, exactMatch = false))
            if (res.success) {
                Log.i(TAG, "clickByContentDescription('$desc') succeeded on attempt $attempt")
                return true
            }
            if (attempt < retries) {
                delay(retryDelayMs)
            }
        }
        Log.w(TAG, "clickByContentDescription('$desc') failed after $retries attempts")
        return false
    }

    /**
     * Finds the Send button in messaging/WhatsApp chat UI.
     * Searches by viewId ("send", ":id/send", "send_button"),
     * contentDescription ("Send", "Send message", "भेजें", "મોકલો"),
     * or text.
     */
    fun findSendButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var found: AccessibilityNodeInfo? = null

        fun traverse(node: AccessibilityNodeInfo) {
            if (found != null || node.isPassword) return
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

            val isSendId = id.endsWith(":id/send") || id.contains("/send") || id.contains("send_button")
            val isSendDesc = desc == "send" || desc == "send message" || desc == "send voice" ||
                             desc.contains("send") || desc.contains("भेजें") || desc.contains("મોકલો")
            val isSendText = text == "send" || text.contains("भेजें") || text.contains("મોકલો")

            if (isSendId || isSendDesc || isSendText) {
                found = node
                return
            }

            for (i in 0 until node.childCount) {
                if (found != null) return
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(root)
        return found
    }

    /**
     * Finds the editable text input field in the active window (e.g. WhatsApp chat composer).
     */
    fun findEditableInputField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null

        fun traverse(node: AccessibilityNodeInfo) {
            if (node.isPassword) return
            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (id.contains("entry") || id.contains("message") || id.contains("input")) {
                    best = node
                    return
                }
                if (best == null) {
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                if (best != null && (best?.viewIdResourceName?.contains("entry") == true || best?.viewIdResourceName?.contains("message") == true)) {
                    return
                }
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(root)
        return best
    }

    /**
     * Sets text into an editable node using ACTION_SET_TEXT (with ACTION_PASTE fallback).
     */
    fun setInputFieldText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val res = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!res) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
                return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (_: Exception) {}
        }
        return res
    }

    /**
     * Performs a safe click on an AccessibilityNodeInfo:
     * 1. Direct performAction(ACTION_CLICK) if node is clickable
     * 2. Walk up parent hierarchy to find clickable container
     * 3. Fallback to center-point coordinate tap gesture
     */
    fun clickNodeSafely(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "clickNodeSafely: clicked node directly via performAction")
            return true
        }

        var p = node.parent
        while (p != null) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "clickNodeSafely: clicked clickable parent via performAction")
                return true
            }
            p = p.parent
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            Log.i(TAG, "clickNodeSafely: fallback coordinate tap at ($cx, $cy)")
            return clickCoordinates(cx, cy)
        }
        return false
    }

    /**
     * Verifies if WhatsApp message was sent by checking:
     * 1. Input field is empty / cleared of the original text
     * 2. Send button is no longer present (replaced by mic/voice note button)
     * 3. Message bubble appears in conversation hierarchy
     */
    suspend fun verifyMessageSent(messageText: String, timeoutMs: Long = 3000L): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == "com.whatsapp") {
                val sendBtn = findSendButton()
                val inputField = findEditableInputField()
                val inputText = inputField?.text?.toString() ?: ""

                // Once sent in WhatsApp:
                // 1) Input field is blank
                // 2) Send button is gone (replaced by voice note icon)
                if (inputText.isBlank() && sendBtn == null) {
                    Log.i(TAG, "[AUTOMATION] verification=true (input cleared, send button replaced)")
                    return true
                }

                // Check message bubbles in conversation stream
                var messageInChat = false
                fun searchChat(node: AccessibilityNodeInfo) {
                    if (messageInChat) return
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    if ((text.contains(messageText, ignoreCase = true) || desc.contains(messageText, ignoreCase = true)) &&
                        !node.isEditable) {
                        messageInChat = true
                        return
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { searchChat(it) }
                    }
                }
                searchChat(root)
                if (messageInChat && (inputText.isBlank() || inputText != messageText)) {
                    Log.i(TAG, "[AUTOMATION] verification=true (message confirmed in conversation bubble)")
                    return true
                }
            }
            delay(250)
        }
        Log.w(TAG, "[AUTOMATION] verification=false (WhatsApp send could not be verified within ${timeoutMs}ms)")
        return false
    }

    /**
     * Waits up to timeoutMs for the given package to become active in the foreground.
     */
    suspend fun waitForPackage(targetPackage: String, timeoutMs: Long = 4000L): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == targetPackage) {
                return true
            }
            delay(200)
        }
        return false
    }

    /**
     * Checks if YouTube watch/player screen or controls are active.
     */
    fun isYouTubePlayerScreen(root: AccessibilityNodeInfo): Boolean {
        var detected = false
        fun traverse(node: AccessibilityNodeInfo) {
            if (detected) return
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""

            val isPlayerId = id.contains("player_view") ||
                             id.contains("watch_while_layout") ||
                             id.contains("player_control_container") ||
                             id.contains("youtube_controls") ||
                             id.contains("fullscreen_button") ||
                             id.contains("modern_overlay") ||
                             id.contains("time_bar") ||
                             id.contains("watch_panel") ||
                             id.contains("player_video_title")

            val isPlayerDesc = desc == "pause video" ||
                               desc == "play video" ||
                               desc == "replay video" ||
                               desc.contains("collapse player") ||
                               desc.contains("minimize player") ||
                               desc.contains("seek slider") ||
                               desc.contains("skip ad") ||
                               desc.contains("full screen")

            if (isPlayerId || isPlayerDesc) {
                detected = true
                return
            }

            for (i in 0 until node.childCount) {
                if (detected) return
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(root)
        return detected
    }

    /**
     * Verifies that YouTube player screen actually opened after tapping a video.
     */
    suspend fun verifyVideoOpened(timeoutMs: Long = 4000L): Boolean {
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == "com.google.android.youtube") {
                if (isYouTubePlayerScreen(root)) {
                    Log.i(TAG, "[AUTOMATION] verification=true (YouTube player screen active)")
                    return true
                }
            }
            delay(300)
        }
        Log.w(TAG, "[AUTOMATION] verification=false (YouTube player not detected within ${timeoutMs}ms)")
        return false
    }

    /**
     * Locates the first REAL video result in YouTube search results or home feed.
     * Skips search bar / top header controls and filter chips, and returns the topmost video card.
     */
    suspend fun findFirstVideoResult(retries: Int = 4, retryDelayMs: Long = 500L): Pair<AccessibilityNodeInfo, Rect>? {
        for (attempt in 1..retries) {
            val root = rootInActiveWindow
            if (root != null) {
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val screenWidth = displayMetrics.widthPixels
                // Ignore top bar / search box region (top 12% of screen)
                val searchBarBottom = (screenHeight * 0.12f).toInt()
                val bottomNavTop = (screenHeight * 0.90f).toInt()

                val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

                fun traverse(node: AccessibilityNodeInfo) {
                    if (node.isPassword) return
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)

                    // Video rows/cards sit below search bar and above bottom nav bar
                    if (bounds.top >= searchBarBottom && bounds.bottom <= bottomNavTop &&
                        bounds.width() >= (screenWidth * 0.40f) && bounds.height() >= 130
                    ) {
                        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                        val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                        val isChip = bounds.height() < 160 && bounds.width() < (screenWidth * 0.35f)

                        if (!isChip) {
                            val isVideoDesc = desc.contains("views") ||
                                              desc.contains("view") ||
                                              desc.contains("ago") ||
                                              desc.contains("seconds") ||
                                              desc.contains("minutes") ||
                                              desc.contains("hours") ||
                                              desc.contains("streamed") ||
                                              desc.contains("by")
                            val isVideoId = id.contains("video") ||
                                            id.contains("item") ||
                                            id.contains("thumbnail") ||
                                            id.contains("compact_renderer") ||
                                            id.contains("entry_point")

                            if (node.isClickable || isVideoDesc || isVideoId || isClickableAncestor(node)) {
                                candidates.add(Pair(node, bounds))
                            }
                        }
                    }

                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { traverse(it) }
                    }
                }

                traverse(root)

                val best = candidates
                    .sortedWith(
                        compareByDescending<Pair<AccessibilityNodeInfo, Rect>> { (node, _) ->
                            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                            desc.contains("views") || id.contains("video")
                        }.thenBy { (_, bounds) -> bounds.top }
                    )
                    .firstOrNull()

                if (best != null) {
                    return best
                }
            }

            if (attempt < retries) {
                delay(retryDelayMs)
            }
        }
        return null
    }

    /**
     * Clicks the Send button in WhatsApp chat UI.
     */
    suspend fun clickSendButton(retries: Int = 3, retryDelayMs: Long = 400L): Boolean {
        for (attempt in 1..retries) {
            val sendBtn = findSendButton()
            if (sendBtn != null && clickNodeSafely(sendBtn)) {
                Log.i(TAG, "WhatsApp Send clicked successfully")
                return true
            }
            if (attempt < retries) {
                delay(retryDelayMs)
            }
        }
        Log.w(TAG, "clickSendButton failed after $retries attempts")
        return false
    }

    /**
     * Locates and clicks the first video result in YouTube search results.
     */
    suspend fun clickFirstYouTubeVideoResult(retries: Int = 3, retryDelayMs: Long = 400L): Boolean {
        val result = findFirstVideoResult(retries, retryDelayMs)
        if (result != null) {
            val (node, _) = result
            return clickNodeSafely(node)
        }
        return false
    }

    /**
     * Reusable node discovery helper: Finds clickable node matching query.
     */
    fun findClickableNode(query: NodeQuery): AccessibilityNodeInfo? {
        val matches = findNodes(query)
        return matches.firstOrNull { it.isClickable || isClickableAncestor(it) }
    }

    fun findClickableNode(targetText: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val matches = findNodes(NodeQuery(text = targetText, exactMatch = exact))
        return matches.firstOrNull { it.isClickable || isClickableAncestor(it) }
    }

    /**
     * Reusable node discovery helper: Finds node by text.
     */
    fun findNodeByText(targetText: String, exact: Boolean = false): AccessibilityNodeInfo? {
        return findNodes(NodeQuery(text = targetText, exactMatch = exact)).firstOrNull()
    }

    /**
     * Reusable node discovery helper: Finds node by content description.
     */
    fun findNodeByContentDescription(desc: String, exact: Boolean = false): AccessibilityNodeInfo? {
        return findNodes(NodeQuery(contentDescription = desc, exactMatch = exact)).firstOrNull()
    }

    /**
     * Reusable helper: Finds the active message/chat input field.
     */
    fun findMessageInput(): AccessibilityNodeInfo? = findEditableInputField()

    /**
     * Reusable helper: Finds the first video result in YouTube.
     */
    suspend fun findFirstYouTubeVideo(retries: Int = 4, retryDelayMs: Long = 500L): AccessibilityNodeInfo? {
        return findFirstVideoResult(retries, retryDelayMs)?.first
    }

    /**
     * Reusable helper: Verifies YouTube player screen opened.
     */
    suspend fun verifyYouTubePlayerOpened(timeoutMs: Long = 4000L): Boolean {
        return verifyVideoOpened(timeoutMs)
    }

    /**
     * Reusable helper: Verifies that WhatsApp chat with recipient or number is loaded.
     * Also checks for error dialogs like "Phone number isn't on WhatsApp".
     */
    fun verifyWhatsAppChatOpen(expectedNameOrPhone: String): WhatsAppChatStatus {
        val root = rootInActiveWindow ?: return WhatsAppChatStatus.NOT_OPEN
        if (root.packageName?.toString() != "com.whatsapp") return WhatsAppChatStatus.NOT_OPEN

        // Check for error dialog: "phone number isn't on WhatsApp"
        var errorDetected: String? = null
        fun checkError(node: AccessibilityNodeInfo) {
            if (errorDetected != null) return
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = "$text $desc".lowercase(Locale.ROOT)
            if (combined.contains("isn't on whatsapp") ||
                combined.contains("not on whatsapp") ||
                combined.contains("invalid phone number") ||
                combined.contains("isn’t on whatsapp")
            ) {
                errorDetected = text.ifBlank { "Phone number is not registered on WhatsApp." }
                return
            }
            for (i in 0 until node.childCount) {
                if (errorDetected != null) return
                node.getChild(i)?.let { checkError(it) }
            }
        }
        checkError(root)
        if (errorDetected != null) {
            return WhatsAppChatStatus.INVALID_NUMBER(errorDetected ?: "Phone number is not registered on WhatsApp.")
        }

        // Check for presence of chat composer or conversation title
        val hasComposer = findEditableInputField() != null
        val hasSendOrVoice = findSendButton() != null || findNodeByContentDescription("Voice message") != null
        if (hasComposer || hasSendOrVoice) {
            return WhatsAppChatStatus.OPEN_VERIFIED
        }

        return WhatsAppChatStatus.NOT_OPEN
    }

    /**
     * Performs a random, strictly SAFE UI tap on the foreground application.
     * Collects all clickable nodes on screen, strictly filters out all destructive
     * and critical actions (Delete, Buy, Send, Call, Logout, Permissions, etc.),
     * picks one random safe element, clicks it, and reports the exact element.
     */
    fun tapRandomSafeNode(): TapResult {
        val root = rootInActiveWindow ?: return TapResult(false, "No active application screen detected.")
        val appPkg = root.packageName?.toString() ?: "current app"
        Log.i(TAG, "[AUTOMATION] command=RANDOM_SAFE_UI_TAP")
        Log.i(TAG, "[AUTOMATION] targetApp=$appPkg")

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Filter out status bar (top 5%) and system navigation bar (bottom 7%)
        val safeTop = (screenHeight * 0.05f).toInt()
        val safeBottom = (screenHeight * 0.93f).toInt()

        val dangerousTerms = setOf(
            "delete", "remove", "uninstall", "buy", "purchase", "pay", "payment",
            "transfer", "send", "call", "emergency", "sos", "logout", "log out",
            "sign out", "deactivate", "erase", "wipe", "format", "clear data",
            "permission", "allow", "deny", "revoke", "reset", "factory reset",
            "shutdown", "power off", "restart", "reboot", "confirm", "destructive",
            "block", "report", "disable", "cancel subscription", "discard", "trash"
        )

        val safeNodes = mutableListOf<AccessibilityNodeInfo>()

        fun traverse(node: AccessibilityNodeInfo) {
            if (node.isPassword) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Must be within safe screen area and have tangible dimensions
            if (bounds.top >= safeTop && bounds.bottom <= safeBottom &&
                bounds.width() >= 30 && bounds.height() >= 30 &&
                bounds.left >= 0 && bounds.right <= screenWidth
            ) {
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                val hasDangerous = dangerousTerms.any { term ->
                    text.contains(term) || desc.contains(term) || id.contains(term)
                }

                if (!hasDangerous && (node.isClickable || isClickableAncestor(node))) {
                    safeNodes.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(root)

        Log.i(TAG, "[AUTOMATION] nodesFound=${safeNodes.size}")
        if (safeNodes.isEmpty()) {
            Log.w(TAG, "[AUTOMATION] result=FAILED (No safe clickable elements found)")
            return TapResult(false, "No safe clickable elements found on the current screen.")
        }

        val chosen = safeNodes.random()
        val label = chosen.text?.toString()?.take(40)
            ?: chosen.contentDescription?.toString()?.take(40)
            ?: chosen.viewIdResourceName?.substringAfterLast('/')?.take(40)
            ?: "element"

        Log.i(TAG, "[AUTOMATION] selectedNode=$label")
        Log.i(TAG, "[AUTOMATION] action=ACTION_CLICK")

        val clicked = clickNodeSafely(chosen)
        Log.i(TAG, "[AUTOMATION] verification=$clicked")
        Log.i(TAG, "[AUTOMATION] result=${if (clicked) "SUCCESS" else "FAILED"}")

        return if (clicked) {
            TapResult(true, "Tapped \"$label\" on screen.", label)
        } else {
            TapResult(false, "Failed to tap \"$label\".", label)
        }
    }

    private fun isClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        while (p != null) {
            if (p.isClickable) return true
            p = p.parent
        }
        return false
    }

    /**
     * Long clicks on an accessible node matching query.
     */
    fun longClickNode(query: NodeQuery): AccessibilityActionResult {
        val matches = findNodes(query)
        if (matches.isEmpty()) {
            return AccessibilityActionResult(false, "ELEMENT_NOT_FOUND", "Element not found.")
        }
        for (node in matches) {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return AccessibilityActionResult(true, "ACTION_COMPLETED", "Long clicked element '${node.text ?: node.contentDescription}'")
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isLongClickable && parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    return AccessibilityActionResult(true, "ACTION_COMPLETED", "Long clicked parent of '${node.text ?: node.contentDescription}'")
                }
                parent = parent.parent
            }
        }
        return AccessibilityActionResult(false, "ACTION_FAILED", "Could not perform long click.")
    }

    fun longClickNodeWithText(targetText: String): Boolean {
        val res = longClickNode(NodeQuery(text = targetText))
        return res.success
    }

    /**
     * Sets text on an editable input field.
     */
    fun setTextOnNode(query: NodeQuery, text: String): AccessibilityActionResult {
        val matches = findNodes(query)
        val targetNode = matches.firstOrNull { it.isEditable } ?: matches.firstOrNull() ?: run {
            // If query target not found, locate first editable field on screen
            val allEditable = findNodes(NodeQuery(isEditable = true))
            allEditable.firstOrNull()
        }

        if (targetNode == null) {
            return AccessibilityActionResult(false, "ELEMENT_NOT_FOUND", "No editable text input found on screen.")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            AccessibilityActionResult(true, "TEXT_ENTERED", "Entered text '$text' into [${targetNode.className}]")
        } else {
            AccessibilityActionResult(false, "ACTION_FAILED", "Failed to set text on input field.")
        }
    }

    fun setTextOnNode(targetText: String, newText: String): Boolean {
        val res = setTextOnNode(NodeQuery(text = targetText, isEditable = true), newText)
        return res.success
    }

    /**
     * Types text into the currently focused editable input or first editable element.
     */
    fun typeText(text: String): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return AccessibilityActionResult(false, "ERROR", "No active window.")
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) return AccessibilityActionResult(true, "TEXT_ENTERED", "Typed text into focused input.")
        }

        // Fallback to first editable node on screen
        return setTextOnNode(NodeQuery(isEditable = true), text)
    }

    /**
     * Scrolls the current active window or scrollable container.
     */
    fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.contains("UP", ignoreCase = true) || direction.contains("BACK", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findScrollable(child)
                if (res != null) return res
            }
            return null
        }

        val scrollable = findScrollable(root)
        if (scrollable != null && scrollable.performAction(action)) {
            return true
        }

        // Fallback to swipe gesture if native scroll action is unhandled
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return if (direction.contains("UP", ignoreCase = true)) {
            swipe(w / 2f, h * 0.3f, w / 2f, h * 0.7f, 300L)
        } else {
            swipe(w / 2f, h * 0.7f, w / 2f, h * 0.3f, 300L)
        }
    }

    /**
     * Dispatches a legitimate swipe gesture between two points.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches an assisted tap at exact screen coordinates.
     */
    fun clickCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Focuses an element.
     */
    fun focusNode(query: NodeQuery): Boolean {
        val matches = findNodes(query)
        for (node in matches) {
            if (node.isFocusable && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                return true
            }
        }
        return false
    }

    fun focusNode(targetText: String): Boolean {
        return focusNode(NodeQuery(text = targetText))
    }

    /**
     * System-level navigation.
     */
    fun performSystemAction(actionName: String): Boolean {
        val action = when (actionName.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "POWER_DIALOG", "POWER_MENU" -> GLOBAL_ACTION_POWER_DIALOG
            "TAKE_SCREENSHOT", "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    /**
     * Computes a structural signature hash of visible on-screen elements to verify if the UI changed.
     */
    fun getScreenSignature(): String {
        val elements = inspectCurrentScreen()
        return elements.joinToString("|") { "${it.text}:${it.className}:${it.bounds}" }.hashCode().toString()
    }

    /**
     * Verifies if an element matching query is present and visible on screen.
     */
    fun verifyElementPresent(query: NodeQuery): Boolean {
        return findNodes(query).isNotEmpty()
    }

    /**
     * Verifies if specific text was entered into an input or appears on screen.
     */
    fun verifyTextEntered(expectedText: String): Boolean {
        val nodes = inspectCurrentScreen()
        return nodes.any { it.text.contains(expectedText, ignoreCase = true) }
    }

    /**
     * Finds a keypad button node corresponding to [digit] ('0'-'9').
     * Matches by text, contentDescription, or standard keypad resource IDs.
     */
    fun findKeypadDigit(digit: Char): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val digitStr = digit.toString()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName ?: ""

            val matchesDigit = (text == digitStr || desc.startsWith(digitStr) || desc.equals(digitStr, ignoreCase = true)) ||
                    id.endsWith("key_$digit") || id.endsWith("key$digit") || id.endsWith("digit$digit") || id.endsWith("pin_key_$digit")

            if (matchesDigit && (node.isClickable || node.parent?.isClickable == true || hasValidBounds(node))) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun hasValidBounds(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0
    }

    /**
     * Types a PIN into a visible lockscreen keypad.
     * Never logs digits or persists plaintext. Single pass only.
     */
    fun enterPinKeypad(pin: CharArray, keyDelayMs: Long): Boolean {
        if (pin.isEmpty()) return false

        for (digit in pin) {
            val node = findKeypadDigit(digit)
            if (node == null) {
                // If direct digit button cannot be found, try coordinate-based fallback if keypad container is detected
                return false
            }

            var clicked = false
            if (node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked && node.parent?.isClickable == true) {
                clicked = node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    clicked = clickCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
                }
            }

            if (!clicked) return false

            try {
                Thread.sleep(keyDelayMs.coerceIn(50L, 500L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        // Check if an "Enter", "OK", or checkmark button is present and click it
        findConfirmOrEnterButton()?.let { enterNode ->
            if (enterNode.isClickable) {
                enterNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val b = Rect()
                enterNode.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0) {
                    clickCoordinates(b.exactCenterX(), b.exactCenterY())
                }
            }
        }

        return true
    }

    /**
     * Looks for a confirmation button on the keypad (e.g. "OK", "Enter", checkmark icon).
     */
    private fun findConfirmOrEnterButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (text.equals("OK", ignoreCase = true) || text.equals("Enter", ignoreCase = true) ||
                desc.contains("enter", ignoreCase = true) || desc.contains("done", ignoreCase = true) ||
                id.contains("key_enter") || id.contains("button_enter") || id.contains("confirm")
            ) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Locates the bounds of the lockscreen pattern view if exposed by the Android UI.
     */
    fun findPatternViewBounds(): Rect? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""

            if (className.contains("LockPatternView", ignoreCase = true) ||
                className.contains("PatternView", ignoreCase = true) ||
                id.contains("pattern_view", ignoreCase = true) ||
                id.contains("lockPattern", ignoreCase = true)
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 100 && bounds.height() > 100) {
                    return bounds
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Dispatches a continuous pattern gesture stroke over the pattern grid.
     */
    fun dispatchPatternGesture(pattern: List<Int>, durationMs: Long = 400L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || pattern.size < 4) return false

        val bounds = findPatternViewBounds() ?: run {
            // Default fallback region for standard phone layout if view is not individually indexed
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val side = (w * 0.85f).toInt()
            val left = (w - side) / 2
            val top = (h * 0.45f).toInt()
            Rect(left, top, left + side, top + side)
        }

        val path = Path()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        fun dotToPoint(dot: Int): Pair<Float, Float> {
            val col = dot % 3
            val row = dot / 3
            val x = bounds.left + (col * 2 + 1) * (w / 6f)
            val y = bounds.top + (row * 2 + 1) * (h / 6f)
            return Pair(x, y)
        }

        val first = dotToPoint(pattern.first())
        path.moveTo(first.first, first.second)

        for (i in 1 until pattern.size) {
            val pt = dotToPoint(pattern[i])
            path.lineTo(pt.first, pt.second)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(200L, 1000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Swipes up from bottom center to raise Android's PIN/Pattern prompt if Keyguard is at bouncer/clock.
     */
    fun swipeUpToDismissBouncer(): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return swipe(w / 2f, h * 0.85f, w / 2f, h * 0.25f, 250L)
    }

    companion object {
        private const val TAG = "JarvisA11y"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        var lastActivePackage: String = ""
            private set

        fun isRunning(): Boolean = instance != null
    }
}

data class ScreenElement(
    val text: String,
    val className: String,
    val isClickable: Boolean,
    val bounds: String,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val packageName: String = ""
)

data class NodeQuery(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val isClickable: Boolean? = null,
    val isEditable: Boolean? = null,
    val exactMatch: Boolean = false
)

data class AccessibilityActionResult(
    val success: Boolean,
    val state: String,
    val message: String,
    val matchedCount: Int = 0
)

sealed class WhatsAppChatStatus {
    object OPEN_VERIFIED : WhatsAppChatStatus()
    object NOT_OPEN : WhatsAppChatStatus()
    data class INVALID_NUMBER(val message: String) : WhatsAppChatStatus()
}

data class TapResult(
    val success: Boolean,
    val description: String,
    val nodeLabel: String? = null
)
