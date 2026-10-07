package com.example.automation.whatsapp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Record of an automated WhatsApp reply for user review.
 */
data class WhatsAppReplyRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val incomingSnippet: String,
    val replySent: String,
    val success: Boolean,
    val statusMessage: String
)

/**
 * Manages persistent preferences and activity history for WhatsApp Auto-Reply.
 * Completely local, zero tracking, zero remote network storage.
 */
class WhatsAppAutoReplyPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _replyMessage = MutableStateFlow(
        prefs.getString(KEY_MESSAGE, DEFAULT_REPLY_MESSAGE) ?: DEFAULT_REPLY_MESSAGE
    )
    val replyMessage: StateFlow<String> = _replyMessage.asStateFlow()

    private val _replyToEveryone = MutableStateFlow(prefs.getBoolean(KEY_REPLY_TO_EVERYONE, true))
    val replyToEveryone: StateFlow<Boolean> = _replyToEveryone.asStateFlow()

    private val _onlyWhenAway = MutableStateFlow(prefs.getBoolean(KEY_ONLY_WHEN_AWAY, false))
    val onlyWhenAway: StateFlow<Boolean> = _onlyWhenAway.asStateFlow()

    private val _cooldownMinutes = MutableStateFlow(prefs.getInt(KEY_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES))
    val cooldownMinutes: StateFlow<Int> = _cooldownMinutes.asStateFlow()

    private val _replyToGroups = MutableStateFlow(prefs.getBoolean(KEY_REPLY_TO_GROUPS, false))
    val replyToGroups: StateFlow<Boolean> = _replyToGroups.asStateFlow()

    private val _totalRepliesSent = MutableStateFlow(prefs.getInt(KEY_TOTAL_REPLIES, 0))
    val totalRepliesSent: StateFlow<Int> = _totalRepliesSent.asStateFlow()

    private val _history = MutableStateFlow<List<WhatsAppReplyRecord>>(loadHistoryFromPrefs())
    val history: StateFlow<List<WhatsAppReplyRecord>> = _history.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled
    }

    fun setReplyMessage(message: String) {
        val cleanMsg = message.trim().ifEmpty { DEFAULT_REPLY_MESSAGE }
        prefs.edit().putString(KEY_MESSAGE, cleanMsg).apply()
        _replyMessage.value = cleanMsg
    }

    fun setReplyToEveryone(value: Boolean) {
        prefs.edit().putBoolean(KEY_REPLY_TO_EVERYONE, value).apply()
        _replyToEveryone.value = value
    }

    fun setOnlyWhenAway(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONLY_WHEN_AWAY, value).apply()
        _onlyWhenAway.value = value
    }

    fun setCooldownMinutes(minutes: Int) {
        val valid = minutes.coerceIn(0, 1440)
        prefs.edit().putInt(KEY_COOLDOWN_MINUTES, valid).apply()
        _cooldownMinutes.value = valid
    }

    fun setReplyToGroups(value: Boolean) {
        prefs.edit().putBoolean(KEY_REPLY_TO_GROUPS, value).apply()
        _replyToGroups.value = value
    }

    fun addHistoryRecord(record: WhatsAppReplyRecord) {
        val current = _history.value.toMutableList()
        current.add(0, record)
        val trimmed = current.take(MAX_HISTORY_ITEMS)
        _history.value = trimmed
        saveHistoryToPrefs(trimmed)

        if (record.success) {
            val total = _totalRepliesSent.value + 1
            _totalRepliesSent.value = total
            prefs.edit().putInt(KEY_TOTAL_REPLIES, total).apply()
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        _totalRepliesSent.value = 0
        prefs.edit().remove(KEY_HISTORY_JSON).putInt(KEY_TOTAL_REPLIES, 0).apply()
    }

    private fun loadHistoryFromPrefs(): List<WhatsAppReplyRecord> {
        val json = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<WhatsAppReplyRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    WhatsAppReplyRecord(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        sender = obj.optString("sender", "Unknown"),
                        incomingSnippet = obj.optString("incoming", ""),
                        replySent = obj.optString("reply", ""),
                        success = obj.optBoolean("success", true),
                        statusMessage = obj.optString("status", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistoryToPrefs(list: List<WhatsAppReplyRecord>) {
        try {
            val array = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("sender", item.sender)
                    put("incoming", item.incomingSnippet)
                    put("reply", item.replySent)
                    put("success", item.success)
                    put("status", item.statusMessage)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
        } catch (_: Exception) {
            // Ignore format errors
        }
    }

    companion object {
        const val PREFS_NAME = "jarvis_whatsapp_auto_reply_prefs"
        const val KEY_ENABLED = "key_whatsapp_auto_reply_enabled"
        const val KEY_MESSAGE = "key_whatsapp_auto_reply_message"
        const val KEY_REPLY_TO_EVERYONE = "key_whatsapp_reply_to_everyone"
        const val KEY_ONLY_WHEN_AWAY = "key_whatsapp_only_when_away"
        const val KEY_COOLDOWN_MINUTES = "key_whatsapp_cooldown_minutes"
        const val KEY_REPLY_TO_GROUPS = "key_whatsapp_reply_to_groups"
        const val KEY_TOTAL_REPLIES = "key_whatsapp_total_replies"
        const val KEY_HISTORY_JSON = "key_whatsapp_history_json"

        const val DEFAULT_REPLY_MESSAGE = "Hi, I'm currently unavailable. I'll get back to you soon."
        const val DEFAULT_COOLDOWN_MINUTES = 5
        private const val MAX_HISTORY_ITEMS = 30

        @Volatile
        private var instance: WhatsAppAutoReplyPreferences? = null

        fun getInstance(context: Context): WhatsAppAutoReplyPreferences {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppAutoReplyPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
