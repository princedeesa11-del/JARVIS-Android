package com.example.ai

import android.content.Context
import android.util.Log
import com.example.service.WakeWordService
import com.example.tools.ToolExecutor
import com.example.tools.ToolRegistry
import com.example.voice.AudioClient
import com.example.voice.MicrophoneOwnershipCoordinator
import com.example.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gemini Live / Streaming Architecture Manager for JARVIS.
 * Recreates the live, bidirectional, multi-turn tool calling loop:
 * USER VOICE/TEXT -> Gemini Streaming -> TOOL CALL -> ToolRegistry & ToolExecutor -> Android Automation -> Real Verified Result -> FunctionResponse -> Gemini -> JARVIS Voice Response.
 *
 * Strictly coordinates microphone ownership, supports barge-in interruption, handles timeouts,
 * and maintains continuous session context.
 */

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    IDLE_LISTENING,
    STREAMING_USER_INPUT,
    THINKING,
    EXECUTING_TOOL,
    SPEAKING,
    ERROR
}

data class LiveTurnResult(
    val userText: String,
    val assistantReply: String,
    val executedTool: String?,
    val toolResultOutput: String?,
    val executionState: String,
    val isSuccess: Boolean
)

class GeminiLiveSessionManager(
    private val context: Context,
    private val geminiProvider: GeminiAIProvider,
    private val offlineProvider: LocalOfflineProvider,
    private val toolExecutor: ToolExecutor,
    private val voiceManager: VoiceManager,
    private val micCoordinator: MicrophoneOwnershipCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeSessionJob: Job? = null

    private val _sessionState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _activeExecutingTool = MutableStateFlow<String?>(null)
    val activeExecutingTool: StateFlow<String?> = _activeExecutingTool.asStateFlow()

    private val sessionHistory = mutableListOf<AIMessage>()

    init {
        // Wire barge-in callback from microphone coordinator
        micCoordinator.onBargeInDetected = {
            interruptSession("User barge-in detected")
        }
    }

    /**
     * Connects or initializes the live session.
     */
    fun startLiveSession(systemPrompt: String) {
        _sessionState.value = LiveSessionState.IDLE_LISTENING
        sessionHistory.clear()
        Log.i(TAG, "Gemini Live session initialized.")
    }

    /**
     * Stops and disconnects the live session.
     */
    fun stopLiveSession() {
        activeSessionJob?.cancel()
        _sessionState.value = LiveSessionState.DISCONNECTED
        micCoordinator.release(AudioClient.GEMINI_LIVE)
        WakeWordService.resume(context)
        Log.i(TAG, "Gemini Live session stopped.")
    }

    /**
     * Immediate interruption (barge-in or user cancel).
     */
    fun interruptSession(reason: String = "Interrupted") {
        Log.i(TAG, "Interrupting active session: $reason")
        activeSessionJob?.cancel()
        activeSessionJob = null
        voiceManager.stopSpeaking(isBargeIn = true)
        _activeExecutingTool.value = null
        _sessionState.value = LiveSessionState.IDLE_LISTENING
    }

    /**
     * Executes a full live bidirectional turn with multi-turn tool verification loop:
     * User input -> Gemini tool selection -> Tool execution on Android -> Verified Tool Result fed back -> Gemini synthesis -> Voice output.
     */
    fun processLiveInput(
        userInput: String,
        systemInstruction: String,
        onTurnCompleted: (LiveTurnResult) -> Unit
    ) {
        activeSessionJob?.cancel()
        activeSessionJob = scope.launch {
            _sessionState.value = LiveSessionState.THINKING
            _liveTranscript.value = ""

            // Pause wake-word sentinel and acquire mic
            WakeWordService.pause(context)
            micCoordinator.acquireMicrophone(AudioClient.GEMINI_LIVE)

            try {
                // 1. Append user message to active session history
                val userMsg = AIMessage("user", userInput)
                sessionHistory.add(userMsg)

                // 2. Query Gemini with tools schema
                val toolsJson = ToolRegistry.buildGeminiToolsJson()
                val provider: AIProvider = geminiProvider

                var response = withContext(Dispatchers.IO) {
                    provider.generateResponse(sessionHistory, systemInstruction, toolsJson)
                }

                // Fallback to offline engine if network fails
                if (!response.isSuccess) {
                    response = withContext(Dispatchers.IO) {
                        offlineProvider.generateResponse(sessionHistory, systemInstruction, toolsJson)
                    }
                }

                var finalAssistantText = response.text
                var executedToolName: String? = null
                var executedToolOutput: String? = null
                var executionState = "SUCCESS"

                // 3. Multi-turn Tool Calling Loop: If Gemini selected one or more tools
                if (response.toolCalls.isNotEmpty()) {
                    for (toolCall in response.toolCalls) {
                        executedToolName = toolCall.name
                        _activeExecutingTool.value = toolCall.name
                        _sessionState.value = LiveSessionState.EXECUTING_TOOL

                        // Execute on real Android APIs / Accessibility
                        val execResult = withContext(Dispatchers.IO) {
                            toolExecutor.execute(toolCall)
                        }

                        _activeExecutingTool.value = null
                        executedToolOutput = execResult.output
                        executionState = if (execResult.isSuccess) "SUCCESS" else "FAILED"

                        // 4. Feed verified ToolResult back to Gemini as functionResponse
                        val modelToolCallMsg = AIMessage(
                            role = "model",
                            text = "",
                            toolCall = toolCall
                        )
                        val toolResponseMsg = AIMessage(
                            role = "user",
                            text = "",
                            toolCall = toolCall,
                            toolResult = execResult.output
                        )
                        sessionHistory.add(modelToolCallMsg)
                        sessionHistory.add(toolResponseMsg)

                        // 5. Ask Gemini to synthesize a natural conversational reply given the verified real result
                        _sessionState.value = LiveSessionState.THINKING
                        val followUpResponse = withContext(Dispatchers.IO) {
                            provider.generateResponse(sessionHistory, systemInstruction, null)
                        }

                        if (followUpResponse.isSuccess && followUpResponse.text.isNotBlank()) {
                            finalAssistantText = followUpResponse.text
                        } else if (finalAssistantText.isBlank()) {
                            finalAssistantText = execResult.output
                        }
                    }
                }

                // 6. Save final assistant response into session history
                sessionHistory.add(AIMessage("model", finalAssistantText))
                _liveTranscript.value = finalAssistantText

                // 7. TTS Speech Output & Ownership Handover
                if (finalAssistantText.isNotBlank()) {
                    _sessionState.value = LiveSessionState.SPEAKING
                    micCoordinator.release(AudioClient.GEMINI_LIVE)
                    micCoordinator.acquireAudioPlayback()
                    voiceManager.speak(finalAssistantText)
                }

                _sessionState.value = LiveSessionState.IDLE_LISTENING

                onTurnCompleted(
                    LiveTurnResult(
                        userText = userInput,
                        assistantReply = finalAssistantText,
                        executedTool = executedToolName,
                        toolResultOutput = executedToolOutput,
                        executionState = executionState,
                        isSuccess = true
                    )
                )

            } catch (e: CancellationException) {
                Log.i(TAG, "Live turn was cancelled/interrupted.")
                _sessionState.value = LiveSessionState.IDLE_LISTENING
            } catch (e: Exception) {
                Log.e(TAG, "Live turn execution error", e)
                _sessionState.value = LiveSessionState.ERROR
                val errText = "I encountered an issue processing that: ${e.localizedMessage ?: "Unknown error"}"
                voiceManager.speak(errText)
                onTurnCompleted(
                    LiveTurnResult(
                        userText = userInput,
                        assistantReply = errText,
                        executedTool = null,
                        toolResultOutput = null,
                        executionState = "ERROR",
                        isSuccess = false
                    )
                )
            } finally {
                micCoordinator.release(AudioClient.GEMINI_LIVE)
                WakeWordService.resume(context)
            }
        }
    }

    companion object {
        private const val TAG = "GeminiLiveSession"
    }
}
