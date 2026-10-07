package com.example.ai

/**
 * Structured result from multimodal speech processing via Gemini API.
 * Captures transcript, synthesized conversational response, success/failure flags,
 * explicit silence detection, and granular connection status.
 */
data class SpeechProcessingResult(
    val transcript: String = "",
    val response: String = "",
    val isSuccess: Boolean = false,
    val isActualSilence: Boolean = false,
    val errorMessage: String? = null,
    val status: GeminiStatus = GeminiStatus.ONLINE
) {
    fun toPair(): Pair<String, String> = Pair(transcript, response)
}
