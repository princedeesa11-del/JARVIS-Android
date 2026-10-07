package com.example.whatsapp.engine

import android.content.Context
import android.util.Log
import com.example.ai.AIMessage
import com.example.ai.GeminiAIProvider
import com.example.whatsapp.client.WhatsAppApiResult
import com.example.whatsapp.client.WhatsAppCloudApiClient
import com.example.whatsapp.config.WhatsAppConfigStore
import com.example.whatsapp.data.WhatsAppAuditLogEntity
import com.example.whatsapp.data.WhatsAppContactEntity
import com.example.whatsapp.data.WhatsAppDao
import com.example.whatsapp.data.WhatsAppMessageEntity
import com.example.whatsapp.model.WhatsAppAutomationMode
import com.example.whatsapp.model.WhatsAppMessageDirection
import com.example.whatsapp.model.WhatsAppMessageStatus
import com.example.whatsapp.model.WhatsAppProviderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class IncomingWhatsAppMessage(
    val messageId: String,
    val senderNumber: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val rawJson: String = ""
)

/**
 * WhatsAppAutomationEngine handles incoming messages from Webhook,
 * deduplication/idempotency protection, loop and self-message suppression,
 * rule evaluation, conversation memory extraction, and dispatching AI/Rule replies.
 */
class WhatsAppAutomationEngine(
    private val context: Context,
    private val dao: WhatsAppDao,
    private val configStore: WhatsAppConfigStore,
    private val apiClient: WhatsAppCloudApiClient,
    private val geminiProvider: GeminiAIProvider
) {

    private val tag = "WhatsAppAutomationEng"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Bounded thread-safe LRU set for in-memory deduplication (max 1500 IDs, evicts oldest, never clears all)
    private val processedMessageIds: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(200, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 1500
            }
        }
    )

    // Per-sender rate limiting: Sender Phone -> Timestamp of last sent reply
    private val recentSenderReplies = ConcurrentHashMap<String, Long>()

    /**
     * Processes an incoming message received via Meta Webhook.
     */
    fun processIncomingMessage(incoming: IncomingWhatsAppMessage) {
        scope.launch {
            // 1. Deduplication / Idempotency protection
            if (processedMessageIds.containsKey(incoming.messageId)) {
                Log.d(tag, "Message ${incoming.messageId} already processed recently; skipping duplicate webhook event.")
                return@launch
            }

            val existing = dao.getMessageByWhatsappId(incoming.messageId)
            if (existing != null) {
                Log.d(tag, "Message ${incoming.messageId} already stored in database; skipping duplicate webhook replay.")
                return@launch
            }

            // Check if we already answered this specific message ID
            val existingReply = dao.getReplyForMessageId(incoming.messageId)
            if (existingReply != null) {
                Log.d(tag, "Reply already dispatched for message ${incoming.messageId}; skipping.")
                return@launch
            }

            processedMessageIds[incoming.messageId] = System.currentTimeMillis()

            Log.i(tag, "Incoming WhatsApp message from ${incoming.senderNumber} (${incoming.senderName}): '${incoming.text}'")

            // 2. Persist incoming message into Room
            val messageEntity = WhatsAppMessageEntity(
                whatsappMessageId = incoming.messageId,
                senderOrRecipientNumber = incoming.senderNumber,
                contactName = incoming.senderName,
                text = incoming.text,
                messageType = "text",
                direction = WhatsAppMessageDirection.INCOMING.name,
                status = WhatsAppMessageStatus.RECEIVED.name,
                isAiReply = false,
                timestamp = incoming.timestamp
            )
            dao.insertMessage(messageEntity)

            // 3. Update or create Contact record
            val existingContact = dao.getContactByPhone(incoming.senderNumber)
            val updatedContact = if (existingContact != null) {
                existingContact.copy(
                    displayName = if (existingContact.displayName.isBlank() || existingContact.displayName == existingContact.phoneNumber) {
                        incoming.senderName.ifBlank { existingContact.phoneNumber }
                    } else existingContact.displayName,
                    lastMessageSnippet = incoming.text.take(80),
                    lastActiveTimestamp = incoming.timestamp
                )
            } else {
                WhatsAppContactEntity(
                    phoneNumber = incoming.senderNumber,
                    displayName = incoming.senderName.ifBlank { incoming.senderNumber },
                    lastMessageSnippet = incoming.text.take(80),
                    lastActiveTimestamp = incoming.timestamp,
                    isAutomationAllowed = true,
                    optInVerified = true
                )
            }
            dao.insertOrUpdateContact(updatedContact)

            // 4. Provider Mode & Global Switch Validation
            val providerMode = configStore.providerMode.value
            if (providerMode == WhatsAppProviderMode.OFF) {
                Log.d(tag, "WhatsApp Provider Mode is OFF; message stored for manual review.")
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "MESSAGE_RECEIVED_MODE_OFF",
                        details = "Received message while provider mode is OFF",
                        targetNumber = incoming.senderNumber
                    )
                )
                return@launch
            }

            if (providerMode == WhatsAppProviderMode.LOCAL_ANDROID_AUTOMATION) {
                Log.d(tag, "Provider mode is LOCAL_ANDROID_AUTOMATION. Webhook message recorded, reply deferred to local listener.")
                return@launch
            }

            if (!configStore.isAutomationEnabled.value) {
                Log.d(tag, "WhatsApp automation is globally DISABLED; message saved for manual review.")
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "MESSAGE_RECEIVED_NO_REPLY",
                        details = "Received message; global automation switch is OFF",
                        targetNumber = incoming.senderNumber
                    )
                )
                return@launch
            }

            // 5. Contact permission & Opt-in check
            if (!updatedContact.isAutomationAllowed) {
                Log.d(tag, "Automation explicitly disabled for contact ${incoming.senderNumber}; skipping reply.")
                return@launch
            }

            if (configStore.optInRequired.value && !updatedContact.optInVerified) {
                Log.w(tag, "Opt-in consent not verified for ${incoming.senderNumber}; skipping automated response per policy.")
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "OPT_IN_REQUIRED_SKIPPED",
                        details = "Suppressed reply because recipient has not verified opt-in consent",
                        targetNumber = incoming.senderNumber,
                        status = "SKIPPED"
                    )
                )
                return@launch
            }

            // 6. Loop prevention: Never respond to our own registered phone number
            val myPhoneId = configStore.phoneNumberId.value.trim()
            if (myPhoneId.isNotBlank() && incoming.senderNumber.contains(myPhoneId)) {
                Log.d(tag, "Sender number matches our own Phone ID; suppressing self-loop.")
                return@launch
            }

            // 7. Check automation operating mode
            when (configStore.automationMode.value) {
                WhatsAppAutomationMode.OFF -> {
                    Log.d(tag, "Automation Mode is OFF.")
                }
                WhatsAppAutomationMode.MANUAL -> {
                    Log.d(tag, "Automation Mode is MANUAL; user will compose reply manually from dashboard.")
                }
                WhatsAppAutomationMode.AI_AUTO_REPLY -> {
                    evaluateAndExecuteAutoReply(incoming, updatedContact)
                }
            }
        }
    }

    private suspend fun evaluateAndExecuteAutoReply(
        incoming: IncomingWhatsAppMessage,
        contact: WhatsAppContactEntity
    ) {
        val now = System.currentTimeMillis()
        val lastReplyTime = recentSenderReplies[incoming.senderNumber] ?: 0L
        val minIntervalMs = configStore.rateLimitSeconds.value * 1000L

        // Rate limiting: Anti-spam / anti-loop protection per contact
        if (now - lastReplyTime < minIntervalMs) {
            val waitRemaining = (minIntervalMs - (now - lastReplyTime)) / 1000
            Log.w(tag, "Anti-loop throttling active for ${incoming.senderNumber} (${waitRemaining}s left); skipping reply.")
            return
        }

        // 1. Check custom rules if enabled
        var customReplyText: String? = null
        var matchedRuleName: String? = null

        if (configStore.isRuleAutomationEnabled.value) {
            val activeRules = dao.getActiveRules()
            for (rule in activeRules) {
                val matches = evaluateRuleMatch(rule, incoming.text)
                if (matches) {
                    customReplyText = rule.predefinedReply
                    matchedRuleName = rule.name
                    dao.insertAuditLog(
                        WhatsAppAuditLogEntity(
                            actionType = "RULE_TRIGGERED",
                            details = "Rule '${rule.name}' (${rule.ruleType}) triggered",
                            targetNumber = incoming.senderNumber
                        )
                    )
                    break
                }
            }
        }

        // 2. If no rule matched, invoke JARVIS AI (if AI auto-reply is enabled)
        val finalReply = if (!customReplyText.isNullOrBlank()) {
            customReplyText
        } else if (configStore.isAiAutoReplyEnabled.value) {
            generateJarvisAiReply(incoming, contact)
        } else {
            null
        }

        if (finalReply.isNullOrBlank()) {
            Log.d(tag, "No rule matched and AI Auto-Reply disabled or blank; no reply dispatched.")
            return
        }

        // 3. Send reply through official WhatsApp Cloud API
        sendOutgoingReply(
            recipientPhone = incoming.senderNumber,
            recipientName = contact.displayName,
            replyText = finalReply,
            inReplyToMessageId = incoming.messageId,
            isAiGenerated = customReplyText == null
        )
    }

    private fun evaluateRuleMatch(rule: com.example.whatsapp.data.WhatsAppRuleEntity, text: String): Boolean {
        val trimmedText = text.trim()
        val keyword = rule.matchKeyword.trim()

        return when (rule.ruleType.uppercase()) {
            "KEYWORD", "KEYWORD_CONTAINS" -> {
                keyword.isNotBlank() && trimmedText.contains(keyword, ignoreCase = true)
            }
            "KEYWORD_EXACT" -> {
                keyword.isNotBlank() && trimmedText.equals(keyword, ignoreCase = true)
            }
            "KEYWORD_STARTS_WITH" -> {
                keyword.isNotBlank() && trimmedText.startsWith(keyword, ignoreCase = true)
            }
            "KEYWORD_REGEX" -> {
                try {
                    keyword.isNotBlank() && Regex(keyword, RegexOption.IGNORE_CASE).containsMatchIn(trimmedText)
                } catch (e: Exception) {
                    Log.w(tag, "Invalid regex pattern in rule '${rule.name}': ${e.message}")
                    false
                }
            }
            "OUTSIDE_HOURS" -> {
                isOutsideWorkingHours(rule.workingHoursStart, rule.workingHoursEnd)
            }
            "INTENT_AI" -> {
                false // Handover to AI fallback
            }
            else -> false
        }
    }

    /**
     * Generates a context-aware reply using JARVIS AI (Gemini).
     * Loads the last 6 messages from the conversation history to maintain context.
     */
    private suspend fun generateJarvisAiReply(
        incoming: IncomingWhatsAppMessage,
        contact: WhatsAppContactEntity
    ): String {
        return try {
            // Load conversation history for contextual memory
            val recentMessages = dao.getRecentConversationMessages(incoming.senderNumber, limit = 6).reversed()
            val historyContextBuilder = StringBuilder()

            if (recentMessages.isNotEmpty()) {
                historyContextBuilder.append("Recent conversation history:\n")
                for (msg in recentMessages) {
                    val roleLabel = if (msg.direction == WhatsAppMessageDirection.INCOMING.name) {
                        contact.displayName.ifBlank { "User" }
                    } else {
                        "JARVIS"
                    }
                    historyContextBuilder.append("[$roleLabel]: ${msg.text}\n")
                }
                historyContextBuilder.append("\n")
            }

            val prompt = """
$historyContextBuilder
Latest incoming message from ${contact.displayName} (${incoming.senderNumber}):
"${incoming.text}"

Instructions:
1. Provide a polite, helpful, and concise response (1 to 3 sentences maximum).
2. If the sender is asking for an update, meeting, scheduling, or technical assistance, acknowledge it respectfully and state that their message has been logged for immediate user review.
3. If they wrote in Hindi, Gujarati, or English, reply naturally in the same language.
4. Do NOT use markdown symbols like asterisks, code blocks, or raw JSON. Keep it natural and readable for standard WhatsApp chat.
""".trimIndent()

            val aiResponse = geminiProvider.generateResponse(
                messages = listOf(
                    AIMessage(role = "user", text = prompt)
                ),
                systemInstruction = "You are JARVIS, a highly capable, polite, and professional autonomous AI assistant responding on WhatsApp on behalf of the user. Output clear, concise message text only."
            )

            val cleaned = aiResponse.text
                .replace("*", "")
                .replace("```", "")
                .trim()

            cleaned.ifBlank {
                "Hello, I have received your message and will get back to you shortly. — JARVIS"
            }
        } catch (e: Exception) {
            Log.e(tag, "AI reply generation failed: ${e.message}")
            "Hello, your message has been received and noted. — JARVIS"
        }
    }

    private suspend fun sendOutgoingReply(
        recipientPhone: String,
        recipientName: String,
        replyText: String,
        inReplyToMessageId: String?,
        isAiGenerated: Boolean
    ) {
        val result = apiClient.sendTextMessage(
            recipientPhone = recipientPhone,
            text = replyText,
            replyToMessageId = inReplyToMessageId
        )

        when (result) {
            is WhatsAppApiResult.Success -> {
                recentSenderReplies[recipientPhone] = System.currentTimeMillis()

                val sentEntity = WhatsAppMessageEntity(
                    whatsappMessageId = result.data.messageId,
                    senderOrRecipientNumber = recipientPhone,
                    contactName = recipientName,
                    text = replyText,
                    direction = WhatsAppMessageDirection.OUTGOING.name,
                    status = WhatsAppMessageStatus.SENT.name,
                    isAiReply = isAiGenerated,
                    replyToMessageId = inReplyToMessageId,
                    timestamp = System.currentTimeMillis()
                )
                dao.insertMessage(sentEntity)

                // Update contact snippet & activity timestamp
                dao.updateContactActivity(recipientPhone, replyText.take(80), System.currentTimeMillis())

                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = if (isAiGenerated) "AI_REPLY_SENT" else "RULE_REPLY_SENT",
                        details = "Sent reply: '${replyText.take(60)}'",
                        targetNumber = recipientPhone,
                        status = "SUCCESS"
                    )
                )
                Log.i(tag, "Successfully dispatched WhatsApp reply to $recipientPhone: $replyText")
            }
            is WhatsAppApiResult.Error -> {
                Log.e(tag, "Failed to send WhatsApp reply: ${result.message} - ${result.details}")
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "REPLY_SEND_FAILED",
                        details = "${result.message}: ${result.details}",
                        targetNumber = recipientPhone,
                        status = "ERROR"
                    )
                )
            }
        }
    }

    private fun isOutsideWorkingHours(startTime: String, endTime: String): Boolean {
        return try {
            val nowCal = Calendar.getInstance()
            val sdf = SimpleDateFormat("HH:mm", Locale.US)
            val nowTime = sdf.format(nowCal.time)

            nowTime < startTime || nowTime > endTime
        } catch (_: Exception) {
            false
        }
    }
}
