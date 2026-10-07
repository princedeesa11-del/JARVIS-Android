package com.example.automation.whatsapp

import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of evaluating and processing an incoming WhatsApp notification.
 */
sealed class AutoReplyResult {
    data class Replied(val sender: String, val text: String, val reply: String) : AutoReplyResult()
    data class Skipped(val reason: String) : AutoReplyResult()
    data class Failed(val error: String) : AutoReplyResult()
}

/**
 * Core engine for detecting WhatsApp incoming messages and dispatching automated replies
 * via standard Android Notification RemoteInput actions.
 *
 * Privacy-first: 100% on-device, zero internet transmission, zero licensing/paywall constraints.
 */
class WhatsAppAutoReplyEngine private constructor(private val context: Context) {

    private val preferences = WhatsAppAutoReplyPreferences.getInstance(context)

    // Cooldown tracking: Normalized sender -> Timestamp of last successful auto-reply
    private val lastRepliedTimestamps = ConcurrentHashMap<String, Long>()

    // Message deduplication cache: Notification key + message content hash -> Timestamp
    private val processedMessageHashes = ConcurrentHashMap<String, Long>()

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    private val keyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    /**
     * Inspects a newly posted notification from JarvisNotificationListenerService.
     * If eligible, automatically dispatches the configured reply.
     */
    fun handleNotification(
        sbn: StatusBarNotification,
        listenerService: Context
    ): AutoReplyResult {
        // 1. Verify feature is enabled and provider mode allows local automation
        val configStore = com.example.whatsapp.config.WhatsAppConfigStore.getInstance(context)
        val providerMode = configStore.providerMode.value

        if (providerMode == com.example.whatsapp.model.WhatsAppProviderMode.OFF) {
            return AutoReplyResult.Skipped("WhatsApp automation is globally OFF.")
        }

        if (providerMode == com.example.whatsapp.model.WhatsAppProviderMode.OFFICIAL_CLOUD_API) {
            return AutoReplyResult.Skipped("Official Cloud API provider active; local automation suppressed.")
        }

        if (!preferences.isEnabled.value && !configStore.isAutomationEnabled.value) {
            return AutoReplyResult.Skipped("WhatsApp Auto-Reply is disabled.")
        }

        // 2. Verify target package is WhatsApp or WhatsApp Business
        val pkg = sbn.packageName
        if (pkg != PACKAGE_WHATSAPP && pkg != PACKAGE_WHATSAPP_BUSINESS) {
            return AutoReplyResult.Skipped("Ignored non-WhatsApp package: $pkg")
        }

        val notification = sbn.notification ?: return AutoReplyResult.Skipped("Null notification payload.")
        val extras = notification.extras ?: return AutoReplyResult.Skipped("Null notification extras.")

        // 3. Filter out system/utility/call notifications
        val category = notification.category
        if (category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_MISSED_CALL ||
            category == Notification.CATEGORY_PROGRESS ||
            category == Notification.CATEGORY_SERVICE
        ) {
            return AutoReplyResult.Skipped("Ignored notification category: $category")
        }

        // 4. Extract sender and incoming text
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) {
            return AutoReplyResult.Skipped("Notification title and body are empty.")
        }

        // Ignore common background status messages
        val lowerText = text.lowercase()
        val lowerTitle = title.lowercase()
        if (lowerText.contains("checking for new messages") ||
            lowerText.contains("whatsapp web is currently active") ||
            lowerText.contains("backup in progress") ||
            lowerText.contains("incoming voice call") ||
            lowerText.contains("incoming video call") ||
            lowerTitle.contains("whatsapp web")
        ) {
            return AutoReplyResult.Skipped("Ignored utility/status message: $text")
        }

        // 5. Avoid replying to our own outgoing messages (e.g. sender marked as "You")
        if (lowerTitle == "you" || lowerTitle.startsWith("you:") || lowerText.startsWith("you:")) {
            return AutoReplyResult.Skipped("Ignored outgoing/self message.")
        }

        // 6. Check if group conversation
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
                (title.contains(":") && !title.endsWith(":"))
        if (isGroup && !preferences.replyToGroups.value) {
            return AutoReplyResult.Skipped("Group message ignored by user preference.")
        }

        // 7. Check 'Only when away' setting
        if (preferences.onlyWhenAway.value) {
            val isScreenOn = powerManager?.isInteractive ?: true
            val isLocked = keyguardManager?.isKeyguardLocked ?: false
            if (isScreenOn && !isLocked) {
                return AutoReplyResult.Skipped("Screen is active and phone is unlocked (user is not away).")
            }
        }

        // 8. Sender identification for cooldown tracking
        val senderKey = if (title.isNotBlank()) title.lowercase() else sbn.key
        val now = System.currentTimeMillis()

        // 9. Cooldown check
        val cooldownMs = preferences.cooldownMinutes.value * 60 * 1000L
        val lastReplied = lastRepliedTimestamps[senderKey]
        if (lastReplied != null && (now - lastReplied) < cooldownMs) {
            val remainingSec = ((cooldownMs - (now - lastReplied)) / 1000).coerceAtLeast(1)
            Log.d(TAG, "Cooldown active for '$senderKey': $remainingSec seconds remaining.")
            return AutoReplyResult.Skipped("Cooldown active ($remainingSec s remaining).")
        }

        // 10. Deduplication check (prevent duplicate reply if Android re-posts the notification)
        val messageHash = "${sbn.key}|${title.hashCode()}|${text.hashCode()}"
        val lastProcessed = processedMessageHashes[messageHash]
        if (lastProcessed != null && (now - lastProcessed) < 120_000L) {
            return AutoReplyResult.Skipped("Duplicate notification already handled.")
        }

        // 11. Find RemoteInput reply action
        val actions = notification.actions ?: return AutoReplyResult.Failed("WhatsApp notification has no interactive actions.")
        var replyAction: Notification.Action? = null
        var targetRemoteInput: RemoteInput? = null

        // First pass: look for explicit reply semantic action or action containing RemoteInput
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isNotEmpty()) {
                val isSemanticReply = action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
                val hasReplyText = action.title?.toString()?.contains("reply", ignoreCase = true) == true

                if (isSemanticReply || hasReplyText || replyAction == null) {
                    replyAction = action
                    targetRemoteInput = remoteInputs.firstOrNull()
                    if (isSemanticReply || hasReplyText) break
                }
            }
        }

        if (replyAction == null || targetRemoteInput == null) {
            return AutoReplyResult.Failed("No RemoteInput reply action found in WhatsApp notification.")
        }

        // 12. Dispatch the reply
        val replyMessage = preferences.replyMessage.value.ifBlank {
            WhatsAppAutoReplyPreferences.DEFAULT_REPLY_MESSAGE
        }

        return try {
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(targetRemoteInput.resultKey, replyMessage)
            }
            RemoteInput.addResultsToIntent(arrayOf(targetRemoteInput), intent, bundle)

            replyAction.actionIntent.send(listenerService, 0, intent)

            // Mark successful dispatch
            lastRepliedTimestamps[senderKey] = now
            processedMessageHashes[messageHash] = now

            // Clean up old hashes periodically
            if (processedMessageHashes.size > 200) {
                val expiry = now - 300_000L
                processedMessageHashes.entries.removeIf { it.value < expiry }
            }

            // Log record
            preferences.addHistoryRecord(
                WhatsAppReplyRecord(
                    sender = title.ifBlank { "WhatsApp Contact" },
                    incomingSnippet = text.take(100),
                    replySent = replyMessage,
                    success = true,
                    statusMessage = "Replied automatically"
                )
            )

            Log.i(TAG, "Sent WhatsApp auto-reply to '$title': '$replyMessage'")
            AutoReplyResult.Replied(title, text, replyMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp auto-reply: ${e.message}", e)
            preferences.addHistoryRecord(
                WhatsAppReplyRecord(
                    sender = title.ifBlank { "WhatsApp Contact" },
                    incomingSnippet = text.take(100),
                    replySent = replyMessage,
                    success = false,
                    statusMessage = "Error: ${e.message}"
                )
            )
            AutoReplyResult.Failed("Send error: ${e.message}")
        }
    }

    /**
     * Clear active cooldowns (e.g. for testing).
     */
    fun resetCooldowns() {
        lastRepliedTimestamps.clear()
        processedMessageHashes.clear()
    }

    companion object {
        const val TAG = "WhatsAppAutoReply"
        const val PACKAGE_WHATSAPP = "com.whatsapp"
        const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        @Volatile
        private var instance: WhatsAppAutoReplyEngine? = null

        fun getInstance(context: Context): WhatsAppAutoReplyEngine {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppAutoReplyEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
