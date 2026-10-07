package com.example.whatsapp.model

/**
 * WhatsApp message transmission status
 */
enum class WhatsAppMessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    RECEIVED
}

/**
 * Message transmission direction
 */
enum class WhatsAppMessageDirection {
    INCOMING,
    OUTGOING
}

/**
 * WhatsApp automation operating mode
 */
enum class WhatsAppAutomationMode {
    OFF,
    MANUAL,
    AI_AUTO_REPLY
}

/**
 * WhatsApp automation provider mode
 * Keeps Local Android Automation and Official Meta Cloud API distinctly selectable
 */
enum class WhatsAppProviderMode(val label: String, val description: String) {
    OFF(
        label = "Off",
        description = "All WhatsApp automation is disabled"
    ),
    LOCAL_ANDROID_AUTOMATION(
        label = "Local Android",
        description = "On-device notification listener & accessibility automation (no Meta account required)"
    ),
    OFFICIAL_CLOUD_API(
        label = "Official Cloud API",
        description = "Meta WhatsApp Business Platform Graph API & Webhook"
    ),
    AUTO_SELECT(
        label = "Auto Select",
        description = "Prefers Official Cloud API when connected; falls back to Local Android Automation"
    )
}

/**
 * Rule trigger criteria types
 */
enum class WhatsAppRuleType(val displayName: String) {
    KEYWORD_CONTAINS("Contains Keyword"),
    KEYWORD_EXACT("Exact Match"),
    KEYWORD_STARTS_WITH("Starts With"),
    KEYWORD_REGEX("Regex Match"),
    OUTSIDE_HOURS("Outside Working Hours"),
    INTENT_AI("AI Handover")
}

/**
 * Scheduled message repeat frequency
 */
enum class WhatsAppRepeatInterval {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY
}

/**
 * Classified WhatsApp Cloud API error types for safe handling
 */
enum class WhatsAppErrorType {
    AUTHENTICATION_ERROR,
    RATE_LIMIT_ERROR,
    INVALID_PARAMETER,
    NETWORK_ERROR,
    SERVER_ERROR,
    UNKNOWN_ERROR
}
