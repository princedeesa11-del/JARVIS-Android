package com.example.ai

import android.util.Log
import java.util.Calendar

/**
 * Short-term conversational context manager for natural multi-turn dialog.
 * Resolves follow-up commands, missing slots, and referenced apps/entities
 * without persisting ephemeral interaction turns to long-term memory.
 */
class ConversationContextManager {

    private val tag = "ConversationContext"

    data class ShortTermContext(
        var lastActiveApp: String? = null,
        var lastAction: String? = null,
        var pendingSlot: String? = null, // e.g. "sms_recipient", "sms_message", "calendar_time", "search_query"
        var pendingEntities: MutableMap<String, Any?> = mutableMapOf(),
        var lastUserDirective: String? = null,
        var lastUpdatedMs: Long = System.currentTimeMillis()
    )

    private var currentContext = ShortTermContext()
    private val contextExpiryMs = 180_000L // 3 minutes TTL for short-term follow-up context

    @Synchronized
    fun getContext(): ShortTermContext {
        // Expire context if inactive for more than 3 minutes
        if (System.currentTimeMillis() - currentContext.lastUpdatedMs > contextExpiryMs) {
            clear()
        }
        return currentContext
    }

    @Synchronized
    fun updateApp(appName: String) {
        currentContext.lastActiveApp = appName
        currentContext.lastAction = "launch_app"
        currentContext.lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun updateAction(actionName: String, entities: Map<String, Any?> = emptyMap()) {
        currentContext.lastAction = actionName
        currentContext.pendingEntities.putAll(entities)
        currentContext.lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun setPendingSlot(slotName: String, entities: Map<String, Any?> = emptyMap()) {
        currentContext.pendingSlot = slotName
        currentContext.pendingEntities.putAll(entities)
        currentContext.lastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun clearPendingSlot() {
        currentContext.pendingSlot = null
        currentContext.pendingEntities.clear()
    }

    @Synchronized
    fun clear() {
        currentContext = ShortTermContext()
    }

    sealed class ContextualResolution {
        data class Resolved(
            val action: String,
            val arguments: Map<String, Any>,
            val explanation: String
        ) : ContextualResolution()

        data class NeedsSlot(
            val slotName: String,
            val promptUser: String
        ) : ContextualResolution()

        object None : ContextualResolution()
    }

    /**
     * Resolves short-term contextual follow-up utterances.
     * Examples:
     * - "Now message Rahul" -> NeedsSlot("sms_message") or sends SMS
     * - "Tell him I'm coming home" -> Resolved("send_sms", {recipient: "Rahul", message: "I'm coming home"})
     * - "Search for Iron Man" -> Resolved("launch_app_search", {app: "YouTube", query: "Iron Man"})
     * - "At 5 PM" -> Resolved("calendar_create", {title: "Meeting", time: ...})
     */
    @Synchronized
    fun resolveFollowUp(rawUtterance: String): ContextualResolution {
        val query = rawUtterance.trim()
        val lower = query.lowercase()
        val ctx = getContext()

        // 1. Check if we have an active pending slot awaiting user input
        when (ctx.pendingSlot) {
            "sms_message" -> {
                val recipient = (ctx.pendingEntities["recipient"] as? String) ?: "contact"
                // Extract message body: strip "tell him" or "say" or use entire text
                var messageBody = query
                val tellMatch = Regex("^(?:tell\\s+(?:him|her|them|[a-zA-Z0-9]+)|say)\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(query)
                if (tellMatch != null) {
                    messageBody = tellMatch.groupValues[1].trim()
                }

                clearPendingSlot()
                ctx.lastAction = "send_sms"
                return ContextualResolution.Resolved(
                    action = "send_sms",
                    arguments = mapOf(
                        "recipient" to recipient,
                        "message" to messageBody
                    ),
                    explanation = "Sending message to $recipient: \"$messageBody\""
                )
            }

            "calendar_time" -> {
                val title = (ctx.pendingEntities["title"] as? String) ?: "Meeting"
                val dateStr = (ctx.pendingEntities["date"] as? String) ?: "tomorrow"
                clearPendingSlot()

                // Calculate timestamp for given time
                val timeMillis = parseTimeToMillis(dateStr, query)
                ctx.lastAction = "create_calendar_event"
                return ContextualResolution.Resolved(
                    action = "create_calendar_event",
                    arguments = mapOf(
                        "title" to title,
                        "startTimeMillis" to timeMillis,
                        "endTimeMillis" to (timeMillis + 3600_000L)
                    ),
                    explanation = "Scheduling \"$title\" for $dateStr based on previous context."
                )
            }

            "search_query" -> {
                val targetApp = ctx.lastActiveApp ?: "YouTube"
                clearPendingSlot()
                return ContextualResolution.Resolved(
                    action = "search_in_app",
                    arguments = mapOf(
                        "app" to targetApp,
                        "query" to query
                    ),
                    explanation = "Searching for \"$query\" in $targetApp."
                )
            }
        }

        // 2. Multi-turn messaging initiation: "now message Rahul" or "message Rahul"
        val messageMatch = Regex("^(?:now\\s+)?(?:message|text|send\\s+(?:a\\s+)?message\\s+to|sms)\\s+([a-zA-Z0-9+ ]+?)(?:\\s+(?:saying|and\\s+say|that)\\s+(.+))?$", RegexOption.IGNORE_CASE).find(query)
        if (messageMatch != null) {
            val recipient = messageMatch.groupValues[1].trim()
            val directMessage = messageMatch.groupValues.getOrNull(2)?.trim()

            if (!directMessage.isNullOrBlank()) {
                clearPendingSlot()
                ctx.lastAction = "send_sms"
                return ContextualResolution.Resolved(
                    action = "send_sms",
                    arguments = mapOf("recipient" to recipient, "message" to directMessage),
                    explanation = "Sending message to $recipient: \"$directMessage\""
                )
            } else {
                setPendingSlot("sms_message", mapOf("recipient" to recipient))
                return ContextualResolution.NeedsSlot(
                    slotName = "sms_message",
                    promptUser = "What message would you like to send to $recipient?"
                )
            }
        }

        // 3. Contextual search in recently opened app: e.g. opened YouTube, now "search for Iron Man"
        if (ctx.lastActiveApp != null) {
            val searchMatch = Regex("^(?:search|look\\s+up|find)(?:\\s+for)?\\s+(.+)$", RegexOption.IGNORE_CASE).find(query)
            if (searchMatch != null) {
                val searchQuery = searchMatch.groupValues[1].trim()
                return ContextualResolution.Resolved(
                    action = "search_in_app",
                    arguments = mapOf(
                        "app" to ctx.lastActiveApp!!,
                        "query" to searchQuery
                    ),
                    explanation = "Searching for \"$searchQuery\" in ${ctx.lastActiveApp}."
                )
            }
        }

        // 4. Multi-turn calendar scheduling: "create a meeting tomorrow"
        val calendarMatch = Regex("^(?:create|schedule|set(?:\\s+up)?)(?:\\s+a)?\\s+(meeting|event|appointment|call)(?:\\s+(tomorrow|today|next\\s+\\w+))?$", RegexOption.IGNORE_CASE).find(lower)
        if (calendarMatch != null) {
            val title = calendarMatch.groupValues[1].replaceFirstChar { it.uppercase() }
            val dateStr = calendarMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "tomorrow"
            setPendingSlot("calendar_time", mapOf("title" to title, "date" to dateStr))
            return ContextualResolution.NeedsSlot(
                slotName = "calendar_time",
                promptUser = "At what time would you like to schedule the $title for $dateStr?"
            )
        }

        return ContextualResolution.None
    }

    private fun parseTimeToMillis(dateHint: String, timeInput: String): Long {
        val cal = Calendar.getInstance()
        if (dateHint.contains("tomorrow", ignoreCase = true)) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Parse e.g. "at 5 pm", "5:30 pm", "17:00", "5pm"
        val timeClean = timeInput.lowercase().replace("at", "").trim()
        val isPm = timeClean.contains("pm")
        val digits = Regex("(\\d{1,2})(?::(\\d{2}))?").find(timeClean)

        if (digits != null) {
            var hour = digits.groupValues[1].toIntOrNull() ?: 12
            val minute = digits.groupValues[2].toIntOrNull() ?: 0
            if (isPm && hour < 12) hour += 12
            if (!isPm && timeClean.contains("am") && hour == 12) hour = 0
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        } else {
            // Default to 10:00 AM if unparseable
            cal.set(Calendar.HOUR_OF_DAY, 10)
            cal.set(Calendar.MINUTE, 0)
        }
        return cal.timeInMillis
    }
}
