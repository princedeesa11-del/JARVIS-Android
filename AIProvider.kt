package com.example.ai

import kotlinx.coroutines.flow.Flow

data class AIMessage(
    val role: String, // "user", "assistant", "system", "tool"
    val text: String,
    val toolCall: ToolCallRequest? = null,
    val toolResult: String? = null
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class AIResponse(
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

data class AIStreamChunk(
    val deltaText: String,
    val toolCall: ToolCallRequest? = null,
    val isDone: Boolean = false,
    val errorMessage: String? = null
)

interface AIProvider {
    val providerName: String

    suspend fun generateResponse(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String? = null
    ): AIResponse

    fun generateStream(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String? = null
    ): Flow<AIStreamChunk>

    suspend fun analyzeVision(
        prompt: String,
        imageBase64: String,
        mimeType: String = "image/jpeg"
    ): AIResponse
}
