package com.example.whatsapp.config

import android.content.Context
import android.content.SharedPreferences
import com.example.whatsapp.model.WhatsAppAutomationMode
import com.example.whatsapp.model.WhatsAppProviderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WhatsApp Configuration & Credential Store.
 * Sensitive credentials (Access Token, Verify Token, Account IDs) are stored
 * encrypted using AES-256-GCM authenticated encryption via WhatsAppSecureCredentialStore.
 * Non-sensitive operational toggles (provider mode, flags) are stored in SharedPreferences.
 */
class WhatsAppConfigStore private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val secureStore = WhatsAppSecureCredentialStore.getInstance(context)

    // Provider mode: OFF, LOCAL_ANDROID_AUTOMATION, OFFICIAL_CLOUD_API, AUTO_SELECT
    private val _providerMode = MutableStateFlow(
        try {
            WhatsAppProviderMode.valueOf(
                prefs.getString(KEY_PROVIDER_MODE, WhatsAppProviderMode.AUTO_SELECT.name)
                    ?: WhatsAppProviderMode.AUTO_SELECT.name
            )
        } catch (_: Exception) {
            WhatsAppProviderMode.AUTO_SELECT
        }
    )
    val providerMode: StateFlow<WhatsAppProviderMode> = _providerMode.asStateFlow()

    // Global Automation switch
    private val _isAutomationEnabled = MutableStateFlow(prefs.getBoolean(KEY_IS_ENABLED, false))
    val isAutomationEnabled: StateFlow<Boolean> = _isAutomationEnabled.asStateFlow()

    // Sub-switches
    private val _isAiAutoReplyEnabled = MutableStateFlow(prefs.getBoolean(KEY_AI_REPLY_ENABLED, true))
    val isAiAutoReplyEnabled: StateFlow<Boolean> = _isAiAutoReplyEnabled.asStateFlow()

    private val _isRuleAutomationEnabled = MutableStateFlow(prefs.getBoolean(KEY_RULES_ENABLED, true))
    val isRuleAutomationEnabled: StateFlow<Boolean> = _isRuleAutomationEnabled.asStateFlow()

    private val _isScheduledMessagesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SCHEDULED_ENABLED, true))
    val isScheduledMessagesEnabled: StateFlow<Boolean> = _isScheduledMessagesEnabled.asStateFlow()

    private val _automationMode = MutableStateFlow(
        try {
            WhatsAppAutomationMode.valueOf(
                prefs.getString(KEY_AUTOMATION_MODE, WhatsAppAutomationMode.AI_AUTO_REPLY.name)
                    ?: WhatsAppAutomationMode.AI_AUTO_REPLY.name
            )
        } catch (_: Exception) {
            WhatsAppAutomationMode.AI_AUTO_REPLY
        }
    )
    val automationMode: StateFlow<WhatsAppAutomationMode> = _automationMode.asStateFlow()

    // Credentials loaded from secure AES-256-GCM storage
    private val initialCreds = secureStore.getCredentials()

    private val _phoneNumberId = MutableStateFlow(initialCreds.phoneNumberId)
    val phoneNumberId: StateFlow<String> = _phoneNumberId.asStateFlow()

    private val _businessAccountId = MutableStateFlow(initialCreds.businessAccountId)
    val businessAccountId: StateFlow<String> = _businessAccountId.asStateFlow()

    private val _accessToken = MutableStateFlow(initialCreds.accessToken)
    val accessToken: StateFlow<String> = _accessToken.asStateFlow()

    private val _verifyToken = MutableStateFlow(initialCreds.verifyToken)
    val verifyToken: StateFlow<String> = _verifyToken.asStateFlow()

    private val _webhookPort = MutableStateFlow(prefs.getInt(KEY_WEBHOOK_PORT, DEFAULT_PORT))
    val webhookPort: StateFlow<Int> = _webhookPort.asStateFlow()

    private val _webhookPublicUrl = MutableStateFlow(initialCreds.webhookPublicUrl)
    val webhookPublicUrl: StateFlow<String> = _webhookPublicUrl.asStateFlow()

    private val _isConnected = MutableStateFlow(prefs.getBoolean(KEY_IS_CONNECTED, false))
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionStatusMessage = MutableStateFlow(
        prefs.getString(KEY_STATUS_MSG, "Not configured") ?: "Not configured"
    )
    val connectionStatusMessage: StateFlow<String> = _connectionStatusMessage.asStateFlow()

    private val _optInRequired = MutableStateFlow(prefs.getBoolean(KEY_OPT_IN_REQUIRED, true))
    val optInRequired: StateFlow<Boolean> = _optInRequired.asStateFlow()

    private val _rateLimitSeconds = MutableStateFlow(prefs.getInt(KEY_RATE_LIMIT_SEC, 4))
    val rateLimitSeconds: StateFlow<Int> = _rateLimitSeconds.asStateFlow()

    // Masked credential getters for safe UI display
    fun getMaskedAccessToken(): String = WhatsAppSecureCredentialStore.maskToken(_accessToken.value)
    fun getMaskedVerifyToken(): String = WhatsAppSecureCredentialStore.maskToken(_verifyToken.value)

    fun updateProviderMode(mode: WhatsAppProviderMode) {
        prefs.edit().putString(KEY_PROVIDER_MODE, mode.name).apply()
        _providerMode.value = mode
    }

    fun updateAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ENABLED, enabled).apply()
        _isAutomationEnabled.value = enabled
    }

    fun updateAiAutoReplyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_REPLY_ENABLED, enabled).apply()
        _isAiAutoReplyEnabled.value = enabled
    }

    fun updateRuleAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RULES_ENABLED, enabled).apply()
        _isRuleAutomationEnabled.value = enabled
    }

    fun updateScheduledMessagesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULED_ENABLED, enabled).apply()
        _isScheduledMessagesEnabled.value = enabled
    }

    fun updateAutomationMode(mode: WhatsAppAutomationMode) {
        prefs.edit().putString(KEY_AUTOMATION_MODE, mode.name).apply()
        _automationMode.value = mode
    }

    fun updateCredentials(
        phoneId: String,
        wabaId: String,
        token: String,
        verifyTok: String,
        port: Int = _webhookPort.value,
        publicUrl: String = _webhookPublicUrl.value
    ): Boolean {
        val creds = WhatsAppSecureCredentials(
            phoneNumberId = phoneId.trim(),
            businessAccountId = wabaId.trim(),
            accessToken = token.trim(),
            verifyToken = verifyTok.trim().ifBlank { DEFAULT_VERIFY_TOKEN },
            webhookPublicUrl = publicUrl.trim()
        )
        val saved = secureStore.saveCredentials(creds)

        prefs.edit().putInt(KEY_WEBHOOK_PORT, port).apply()

        _phoneNumberId.value = creds.phoneNumberId
        _businessAccountId.value = creds.businessAccountId
        _accessToken.value = creds.accessToken
        _verifyToken.value = creds.verifyToken
        _webhookPort.value = port
        _webhookPublicUrl.value = creds.webhookPublicUrl
        return saved
    }

    fun clearCredentials() {
        secureStore.clearCredentials()
        _phoneNumberId.value = ""
        _businessAccountId.value = ""
        _accessToken.value = ""
        _verifyToken.value = DEFAULT_VERIFY_TOKEN
        _webhookPublicUrl.value = ""
        updateConnectionStatus(false, "Credentials cleared")
    }

    fun updateConnectionStatus(connected: Boolean, message: String) {
        prefs.edit()
            .putBoolean(KEY_IS_CONNECTED, connected)
            .putString(KEY_STATUS_MSG, message)
            .apply()
        _isConnected.value = connected
        _connectionStatusMessage.value = message
    }

    fun updateOptInRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_OPT_IN_REQUIRED, required).apply()
        _optInRequired.value = required
    }

    fun updateRateLimitSeconds(seconds: Int) {
        val valid = seconds.coerceIn(1, 60)
        prefs.edit().putInt(KEY_RATE_LIMIT_SEC, valid).apply()
        _rateLimitSeconds.value = valid
    }

    fun isConfigured(): Boolean {
        return _phoneNumberId.value.isNotBlank() && _accessToken.value.isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_whatsapp_cloud_prefs"
        private const val KEY_PROVIDER_MODE = "key_wa_provider_mode"
        private const val KEY_IS_ENABLED = "key_wa_enabled"
        private const val KEY_AI_REPLY_ENABLED = "key_wa_ai_reply_enabled"
        private const val KEY_RULES_ENABLED = "key_wa_rules_enabled"
        private const val KEY_SCHEDULED_ENABLED = "key_wa_scheduled_enabled"
        private const val KEY_AUTOMATION_MODE = "key_wa_mode"
        private const val KEY_WEBHOOK_PORT = "key_wa_webhook_port"
        private const val KEY_IS_CONNECTED = "key_wa_is_connected"
        private const val KEY_STATUS_MSG = "key_wa_status_msg"
        private const val KEY_OPT_IN_REQUIRED = "key_wa_opt_in_required"
        private const val KEY_RATE_LIMIT_SEC = "key_wa_rate_limit_sec"

        const val DEFAULT_PORT = 8088
        const val DEFAULT_VERIFY_TOKEN = "jarvis_meta_webhook_verify_secret_token"

        @Volatile
        private var instance: WhatsAppConfigStore? = null

        fun getInstance(context: Context): WhatsAppConfigStore {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppConfigStore(context).also { instance = it }
            }
        }
    }
}
