package com.example.tools

data class ToolParameter(
    val name: String,
    val type: String, // "STRING", "INTEGER", "BOOLEAN", "NUMBER"
    val description: String,
    val isRequired: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val requiresConfirmation: Boolean = false,
    val requiredPermission: String? = null
)

enum class ToolExecutionStatusCode {
    SUCCESS,
    FAILED,
    PERMISSION_REQUIRED,
    USER_ACTION_REQUIRED,
    BLOCKED_BY_ANDROID,
    NOT_AVAILABLE,
    NOT_FOUND
}

data class ToolResult(
    val toolName: String,
    val isSuccess: Boolean,
    val output: String,
    val data: Map<String, Any?> = emptyMap(),
    val errorMessage: String? = null,
    val statusCode: String = if (isSuccess) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name
)

enum class MessagingResultStatus {
    MESSAGE_COMPOSER_OPENED,
    MESSAGE_DRAFT_CREATED,
    MESSAGE_SENT,
    MESSAGE_FAILED,
    USER_CANCELLED,
    SMS_SENT,
    SMS_FAILED,
    SMS_PERMISSION_REQUIRED,
    SMS_DRAFT_ONLY,
    SMS_COMPOSER_OPENED
}

