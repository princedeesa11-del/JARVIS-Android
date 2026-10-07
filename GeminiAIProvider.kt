package com.example.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Authoritative Production Gemini AI Provider for JARVIS.
 * Single runtime connection engine for:
 * - Conversational text AI (JarvisVoiceAssistant, JarvisViewModel, JarvisMainScreen, MayaHomeScreen)
 * - Tool-calling / Android automation (ToolRegistry, ToolExecutor)
 * - Vision Multimodal AI (CameraX, ScreenCaptureService)
 * - Audio speech transcription (ContinuousMicEngine)
 */
class GeminiAIProvider(
    private var configManager: GeminiConfigManager? = null,
    private var customApiKeyOverride: String? = null,
    private var selectedModelOverride: String? = null,
    private var selectedAudioModelOverride: String? = null
) : AIProvider {

    override val providerName: String = "Gemini AI"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun attachConfigManager(manager: GeminiConfigManager) {
        configManager = manager
    }

    fun updateApiKey(newKey: String?) {
        val trimmed = newKey?.trim() ?: ""
        customApiKeyOverride = trimmed
        configManager?.saveApiKey(trimmed)
    }

    fun updateModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isNotEmpty()) {
            selectedModelOverride = trimmed
            configManager?.saveSelectedModel(trimmed)
        }
    }

    fun updateAudioModel(modelName: String) {
        val trimmed = modelName.trim()
        if (trimmed.isNotEmpty()) {
            selectedAudioModelOverride = trimmed
            configManager?.saveSelectedAudioModel(trimmed)
        }
    }

    fun getEffectiveApiKey(): String {
        val fromManager = configManager?.getEffectiveApiKey()?.trim()
        if (!fromManager.isNullOrEmpty()) return fromManager

        val override = customApiKeyOverride?.trim()
        if (!override.isNullOrEmpty()) return override

        return try {
            val buildConfigKey = BuildConfig.GEMINI_API_KEY.trim()
            if (buildConfigKey.isNotEmpty() && buildConfigKey != "MY_GEMINI_API_KEY") {
                buildConfigKey
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun getEffectiveModel(): String {
        val fromManager = configManager?.getSelectedModel()?.trim()
        if (!fromManager.isNullOrEmpty()) return fromManager

        val override = selectedModelOverride?.trim()
        if (!override.isNullOrEmpty()) return override

        return GeminiConfigManager.DEFAULT_MODEL
    }

    fun getEffectiveAudioModel(): String {
        val fromManager = configManager?.getEffectiveAudioModel()?.trim()
        if (!fromManager.isNullOrEmpty()) return fromManager

        val override = selectedAudioModelOverride?.trim()
        if (!override.isNullOrEmpty()) return override

        return getEffectiveModel()
    }

    fun getKeySource(): KeySource {
        if (!customApiKeyOverride.isNullOrEmpty()) return KeySource.CUSTOM
        return configManager?.getKeySource() ?: KeySource.NONE
    }

    fun getFallbackModels(primaryModel: String): List<String> {
        val candidates = listOf(
            primaryModel,
            "gemini-flash-latest",
            "gemini-3.1-flash-lite-preview",
            "gemini-3.5-flash",
            "gemini-2.5-flash"
        )
        return candidates.distinct()
    }

    fun getVisionFallbackModels(primaryModel: String): List<String> {
        val candidates = listOf(
            primaryModel,
            "gemini-flash-latest",
            "gemini-2.5-flash-image",
            "gemini-3.1-flash-image-preview",
            "gemini-3.5-flash"
        )
        return candidates.distinct()
    }

    fun getAudioFallbackModels(primaryModel: String): List<String> {
        val candidates = listOf(
            primaryModel,
            "gemini-flash-latest",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-flash",
            "gemini-3.1-flash-lite-preview"
        )
        return candidates.distinct()
    }

    private fun isRetriableOrFallbackCode(code: Int): Boolean {
        return code == 503 || // Service Unavailable / High demand
                code == 429 || // Too Many Requests / Quota spike
                code == 500 || // Internal Server Error
                code == 502 || // Bad Gateway
                code == 504 || // Gateway Timeout
                code == 404    // Model not found
    }

    /**
     * Tests an API key against the Gemini API using the exact same model, endpoint,
     * and HTTP client used in normal assistant conversations.
     * When validation succeeds, persists and updates the authoritative config immediately.
     */
    suspend fun testApiKey(testKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = testKey.trim()
        if (key.isEmpty() || key == "MY_GEMINI_API_KEY") {
            return@withContext Pair(false, "API key cannot be empty or placeholder.")
        }
        val primaryModel = getEffectiveModel()
        val modelsToTry = getFallbackModels(primaryModel)

        var lastError = "Validation failed"
        for (model in modelsToTry) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            Log.i(TAG, "[GEMINI] Validating API key against model: $model")

            for (attempt in 1..2) {
                try {
                    val payload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", "Ping test. Respond with: OK")
                                    })
                                })
                            })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("maxOutputTokens", 16)
                            put("temperature", 0.1)
                        })
                    }

                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        updateApiKey(key)
                        configManager?.updateStatus(GeminiStatus.ONLINE, "Validated with $model")
                        Log.i(TAG, "[GEMINI] API key validation SUCCEEDED for model $model")
                        return@withContext Pair(true, "Key validated successfully! Connected to $model.")
                    }

                    val errorMsg = parseErrorMessage(responseBody, response.code)
                    lastError = errorMsg
                    val status = classifyError(response.code, errorMsg)

                    // If auth failure (401/403), stop immediately
                    if (response.code == 401 || response.code == 403) {
                        configManager?.updateStatus(status, errorMsg)
                        Log.e(TAG, "[GEMINI] API key validation FAILED ($response.code): $errorMsg")
                        return@withContext Pair(false, "Invalid API key ($errorMsg).")
                    }

                    if (isRetriableOrFallbackCode(response.code)) {
                        if (attempt == 1) {
                            Log.w(TAG, "[GEMINI] Model $model returned ${response.code} ($errorMsg) during test. Retrying...")
                            delay(600)
                            continue
                        } else {
                            Log.w(TAG, "[GEMINI] Model $model overloaded during test. Trying fallback model...")
                            break
                        }
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    val isNetwork = e is UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true
                    lastError = e.localizedMessage ?: "Network error"
                    if (attempt == 1 && !isNetwork) {
                        delay(500)
                    } else {
                        break
                    }
                }
            }
        }

        configManager?.updateStatus(GeminiStatus.SERVER_ERROR, lastError)
        Pair(false, "Key validation failed ($lastError).")
    }

    override suspend fun generateResponse(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String?
    ): AIResponse = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        val primaryModel = getEffectiveModel()
        val keySource = getKeySource()

        Log.i(TAG, "[GEMINI] runtime_request_started")
        Log.i(TAG, "[GEMINI] api_key_present=${apiKey.isNotEmpty()}")
        Log.i(TAG, "[GEMINI] key_source=$keySource")
        Log.i(TAG, "[GEMINI] model=$primaryModel")

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "[GEMINI] fallback_triggered=true")
            Log.w(TAG, "[GEMINI] fallback_reason=API key missing or placeholder")
            configManager?.updateStatus(GeminiStatus.API_KEY_INVALID, "API key missing")
            return@withContext AIResponse(
                text = "Gemini API key is not configured. Please enter your key in Personal Settings or the Secrets panel.",
                isSuccess = false,
                errorMessage = "API key missing"
            )
        }

        val modelsToTry = getFallbackModels(primaryModel)
        var lastErrorMsg = ""
        var lastStatusCode = 0

        for ((modelIndex, currentModel) in modelsToTry.withIndex()) {
            val isFallbackModel = currentModel != primaryModel
            if (isFallbackModel) {
                Log.w(TAG, "[GEMINI] Model $primaryModel busy/unavailable. Falling back to model: $currentModel (attempt ${modelIndex + 1}/${modelsToTry.size})")
            }

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$currentModel:generateContent?key=$apiKey"
            val payload = buildRequestPayload(messages, systemInstruction, toolsSchemaJson)

            var attemptsForModel = 0
            val maxAttemptsForModel = 2

            while (attemptsForModel < maxAttemptsForModel) {
                attemptsForModel++
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val code = response.code
                    val responseBody = response.body?.string() ?: ""

                    Log.i(TAG, "[GEMINI] model=$currentModel response_code=$code")

                    if (response.isSuccessful) {
                        val parsed = parseGenerateContentResponse(responseBody)
                        Log.i(TAG, "[GEMINI] response_parsed=${parsed.isSuccess}")
                        Log.i(TAG, "[GEMINI] final_reply_length=${parsed.text.length}")
                        Log.i(TAG, "[GEMINI] fallback_triggered=false")
                        if (isFallbackModel) {
                            Log.i(TAG, "[GEMINI] Model $primaryModel was overloaded. Successfully served by fallback $currentModel")
                        }
                        configManager?.updateStatus(GeminiStatus.ONLINE, "Online via $currentModel")
                        return@withContext parsed
                    }

                    val errorMsg = parseErrorMessage(responseBody, code)
                    lastErrorMsg = errorMsg
                    lastStatusCode = code

                    // If 400 with tool schema, retry without tools on this model
                    if (code == 400 && !toolsSchemaJson.isNullOrBlank()) {
                        Log.w(TAG, "[GEMINI] Tool schema rejected with 400 on $currentModel. Retrying without tools...")
                        val fallbackPayload = buildRequestPayload(messages, systemInstruction, null)
                        val retryRequest = Request.Builder()
                            .url(endpoint)
                            .post(fallbackPayload.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        val retryResponse = httpClient.newCall(retryRequest).execute()
                        if (retryResponse.isSuccessful) {
                            val retryBody = retryResponse.body?.string() ?: ""
                            val parsed = parseGenerateContentResponse(retryBody)
                            Log.i(TAG, "[GEMINI] fallback_triggered=false")
                            configManager?.updateStatus(GeminiStatus.ONLINE, "Online via $currentModel")
                            return@withContext parsed
                        }
                    }

                    // If unauthorized/bad key (401/403), stop immediately without trying fallback models
                    if (code == 401 || code == 403) {
                        val status = classifyError(code, errorMsg)
                        configManager?.updateStatus(status, errorMsg)
                        Log.e(TAG, "[GEMINI] Authentication error on $currentModel: $errorMsg")
                        return@withContext AIResponse(
                            text = "Invalid API key ($errorMsg). Please check your key in Settings.",
                            isSuccess = false,
                            errorMessage = errorMsg
                        )
                    }

                    // If transient 503 (high demand) or 429 (rate limit), wait briefly and retry or fall back
                    if (isRetriableOrFallbackCode(code)) {
                        if (attemptsForModel < maxAttemptsForModel) {
                            Log.w(TAG, "[GEMINI] $currentModel returned HTTP $code ($errorMsg). Retrying in 600ms...")
                            delay(600)
                            continue
                        } else {
                            Log.w(TAG, "[GEMINI] $currentModel returned HTTP $code after retry. Cascading to fallback model...")
                            break // Move to next model in modelsToTry
                        }
                    } else {
                        // Non-retriable error
                        break
                    }
                } catch (e: Exception) {
                    val isNetwork = e is UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true
                    lastErrorMsg = e.localizedMessage ?: "Network error"
                    lastStatusCode = if (isNetwork) -1 else -2
                    Log.w(TAG, "[GEMINI] Exception on $currentModel: ${e.message}")
                    if (attemptsForModel < maxAttemptsForModel && !isNetwork) {
                        delay(500)
                    } else {
                        break
                    }
                }
            }
        }

        // All models in the fallback chain were exhausted
        val status = classifyError(lastStatusCode, lastErrorMsg)
        configManager?.updateStatus(status, lastErrorMsg)
        Log.e(TAG, "[GEMINI] fallback_triggered=true")
        Log.e(TAG, "[GEMINI] fallback_reason=All models exhausted. Last error (HTTP $lastStatusCode): $lastErrorMsg")

        AIResponse(
            text = "Request failed ($lastErrorMsg)",
            isSuccess = false,
            errorMessage = lastErrorMsg
        )
    }

    override fun generateStream(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String?
    ): Flow<AIStreamChunk> = flow {
        val apiKey = getEffectiveApiKey()
        val primaryModel = getEffectiveModel()

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            emit(AIStreamChunk("Gemini API key not configured. Please set your key in Settings.", isDone = true, errorMessage = "API key missing"))
            return@flow
        }

        val modelsToTry = getFallbackModels(primaryModel)
        var streamStarted = false

        for (model in modelsToTry) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
            val payload = buildRequestPayload(messages, systemInstruction, toolsSchemaJson)

            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        val err = parseErrorMessage(response.body?.string() ?: "", response.code)
                        if (isRetriableOrFallbackCode(response.code)) {
                            if (attempts < 2) {
                                delay(600)
                                continue
                            } else {
                                break // try next model
                            }
                        } else {
                            emit(AIStreamChunk("Error: $err", isDone = true, errorMessage = err))
                            return@flow
                        }
                    }

                    val inputStream = response.body?.byteStream()
                    if (inputStream == null) {
                        break
                    }

                    val reader = BufferedReader(InputStreamReader(inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line?.trim() ?: continue
                        if (!raw.startsWith("data:")) continue
                        val jsonStr = raw.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        try {
                            val root = JSONObject(jsonStr)
                            val candidates = root.optJSONArray("candidates") ?: continue
                            val firstCandidate = candidates.optJSONObject(0) ?: continue
                            val content = firstCandidate.optJSONObject("content") ?: continue
                            val parts = content.optJSONArray("parts") ?: continue

                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("text")) {
                                    val text = part.getString("text")
                                    emit(AIStreamChunk(deltaText = text))
                                }
                                if (part.has("functionCall")) {
                                    val fc = part.getJSONObject("functionCall")
                                    val fnName = fc.getString("name")
                                    val argsObj = fc.optJSONObject("args") ?: JSONObject()
                                    val argsMap = mutableMapOf<String, Any?>()
                                    val keys = argsObj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        argsMap[k] = argsObj.opt(k)
                                    }
                                    emit(
                                        AIStreamChunk(
                                            deltaText = "",
                                            toolCall = ToolCallRequest(
                                                id = UUID.randomUUID().toString(),
                                                name = fnName,
                                                arguments = argsMap
                                            )
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    streamStarted = true
                    emit(AIStreamChunk("", isDone = true))
                    return@flow
                } catch (e: Exception) {
                    if (attempts < 2) {
                        delay(500)
                    } else {
                        break
                    }
                }
            }
        }

        if (!streamStarted) {
            emit(AIStreamChunk("Connection error: All Gemini models currently busy. Please retry.", isDone = true, errorMessage = "Models busy"))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun analyzeVision(
        prompt: String,
        imageBase64: String,
        mimeType: String
    ): AIResponse = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        val primaryModel = getEffectiveModel()

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext AIResponse(
                text = "Gemini API key is required for Vision AI. Configure it in Settings.",
                isSuccess = false,
                errorMessage = "API key missing"
            )
        }

        val modelsToTry = getVisionFallbackModels(primaryModel)
        var lastErrorMsg = "Vision analysis failed"

        for (model in modelsToTry) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val json = JSONObject()
            val contents = JSONArray()
            val contentObj = JSONObject()
            val parts = JSONArray()

            val textPart = JSONObject().put("text", prompt)
            parts.put(textPart)

            val imagePart = JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", mimeType)
                    .put("data", imageBase64)
            )
            parts.put(imagePart)

            contentObj.put("parts", parts)
            contents.put(contentObj)
            json.put("contents", contents)

            for (attempt in 1..2) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        return@withContext parseGenerateContentResponse(responseBody)
                    }

                    val errorMsg = parseErrorMessage(responseBody, response.code)
                    lastErrorMsg = errorMsg

                    if (response.code == 401 || response.code == 403) {
                        return@withContext AIResponse(
                            text = "Vision analysis failed ($errorMsg)",
                            isSuccess = false,
                            errorMessage = errorMsg
                        )
                    }

                    if (isRetriableOrFallbackCode(response.code)) {
                        if (attempt == 1) {
                            delay(600)
                            continue
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    lastErrorMsg = e.localizedMessage ?: "Unknown error"
                    if (attempt == 1) delay(500) else break
                }
            }
        }

        AIResponse(
            text = "Vision analysis failed: $lastErrorMsg",
            isSuccess = false,
            errorMessage = lastErrorMsg
        )
    }

    /**
     * Builds request payload adhering strictly to Google Gemini REST API requirements:
     * 1. The contents list MUST start with a message where role is "user".
     * 2. Message roles MUST strictly alternate between "user" and "model".
     * 3. Consecutive messages of the same role are combined with newline separators.
     * 4. Empty message parts are strictly excluded.
     * 5. The final message is guaranteed to be "user".
     */
    fun buildRequestPayload(
        messages: List<AIMessage>,
        systemInstruction: String,
        toolsSchemaJson: String?
    ): JSONObject {
        val json = JSONObject()

        // 1. System Instruction
        if (systemInstruction.isNotBlank()) {
            val sysPart = JSONObject().put("text", systemInstruction)
            val sysContent = JSONObject().put("parts", JSONArray().put(sysPart))
            json.put("systemInstruction", sysContent)
        }

        // 2. Strict Alternating Multi-turn Sanitization
        val sanitizedContents = mutableListOf<JSONObject>()
        var currentRole: String? = null
        var currentParts = JSONArray()

        for (msg in messages) {
            val rawRole = if (msg.role == "assistant" || msg.role == "model") "model" else "user"

            val partList = mutableListOf<JSONObject>()
            if (msg.text.isNotBlank()) {
                partList.add(JSONObject().put("text", msg.text))
            }
            if (msg.toolCall != null) {
                val fcObj = JSONObject()
                    .put("name", msg.toolCall.name)
                    .put("args", JSONObject(msg.toolCall.arguments))
                partList.add(JSONObject().put("functionCall", fcObj))
            }
            if (msg.toolResult != null) {
                val frObj = JSONObject()
                    .put("name", msg.toolCall?.name ?: "tool")
                    .put("response", JSONObject().put("result", msg.toolResult))
                partList.add(JSONObject().put("functionResponse", frObj))
            }

            if (partList.isEmpty()) continue

            // If the role matches the current accumulator, merge parts into the same turn
            if (currentRole == rawRole) {
                for (p in partList) {
                    currentParts.put(p)
                }
            } else {
                // If there was an active turn, commit it
                if (currentRole != null && currentParts.length() > 0) {
                    val turnObj = JSONObject()
                        .put("role", currentRole)
                        .put("parts", currentParts)
                    sanitizedContents.add(turnObj)
                }
                currentRole = rawRole
                currentParts = JSONArray()
                for (p in partList) {
                    currentParts.put(p)
                }
            }
        }

        // Commit remaining turn
        if (currentRole != null && currentParts.length() > 0) {
            sanitizedContents.add(JSONObject().put("role", currentRole).put("parts", currentParts))
        }

        // Rule: Gemini requires the first content to have role "user"
        while (sanitizedContents.isNotEmpty() && sanitizedContents.first().optString("role") == "model") {
            sanitizedContents.removeAt(0)
        }

        // Rule: If contents is completely empty, provide an initial user greeting
        if (sanitizedContents.isEmpty()) {
            val defaultUser = JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", "Hello JARVIS.")))
            }
            sanitizedContents.add(defaultUser)
        }

        val contentsArray = JSONArray()
        for (item in sanitizedContents) {
            contentsArray.put(item)
        }
        json.put("contents", contentsArray)

        // 3. Tools
        if (!toolsSchemaJson.isNullOrBlank()) {
            try {
                val toolsArray = JSONArray(toolsSchemaJson)
                json.put("tools", toolsArray)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse toolsSchemaJson: ${e.message}")
            }
        }

        // 4. Generation Config
        val genConfig = JSONObject()
            .put("temperature", 0.7)
            .put("topP", 0.95)
        json.put("generationConfig", genConfig)

        return json
    }

    private fun parseGenerateContentResponse(jsonString: String): AIResponse {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return AIResponse("No response generated by model.")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        val stringBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCallRequest>()

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    stringBuilder.append(part.getString("text"))
                }
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    val fnName = fc.getString("name")
                    val argsObj = fc.optJSONObject("args") ?: JSONObject()
                    val argsMap = mutableMapOf<String, Any?>()
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        argsMap[k] = argsObj.opt(k)
                    }
                    toolCalls.add(
                        ToolCallRequest(
                            id = UUID.randomUUID().toString(),
                            name = fnName,
                            arguments = argsMap
                        )
                    )
                }
            }
        }

        return AIResponse(
            text = stringBuilder.toString(),
            toolCalls = toolCalls,
            isSuccess = true
        )
    }

    private fun parseErrorMessage(errorBody: String, code: Int): String {
        return try {
            val json = JSONObject(errorBody)
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message") ?: "HTTP error $code"
            when (code) {
                429 -> "Rate limit or quota exceeded: $msg"
                401, 403 -> "Authentication error: $msg"
                404 -> "Model not found ($msg)"
                else -> msg
            }
        } catch (_: Exception) {
            "HTTP $code: $errorBody"
        }
    }

    private fun classifyError(code: Int, message: String): GeminiStatus {
        return when {
            code == 429 -> GeminiStatus.RATE_LIMITED
            code == 401 || code == 403 || message.contains("API_KEY_INVALID", ignoreCase = true) -> GeminiStatus.API_KEY_INVALID
            code == 404 -> GeminiStatus.MODEL_ERROR
            code >= 500 -> GeminiStatus.SERVER_ERROR
            message.contains("network", ignoreCase = true) || message.contains("host", ignoreCase = true) -> GeminiStatus.NETWORK_ERROR
            else -> GeminiStatus.AUTH_ERROR
        }
    }

    /**
     * Speech audio processing.
     * Uses the authoritative audio-compatible Gemini multimodal model to transcribe speech audio
     * and synthesize the verbatim transcript.
     */
    suspend fun processSpeechAudio(
        wavBytes: ByteArray,
        systemInstruction: String = "You are JARVIS — a continuous always-on personal AI assistant for Prince. If Prince spoke, transcribe what was said accurately. Never mention AI models. Format your reply EXACTLY like this:\nTRANSCRIPT: <verbatim words spoken>\nRESPONSE: <your concise spoken reply as JARVIS>"
    ): SpeechProcessingResult = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        val primaryAudioModel = getEffectiveAudioModel()

        Log.i(TAG, "[GEMINI] voice_request_started")
        Log.i(TAG, "[GEMINI] audio_model=$primaryAudioModel")
        Log.i(TAG, "[GEMINI] api_key_present=${apiKey.isNotEmpty()}")
        Log.i(TAG, "[GEMINI] audio_bytes=${wavBytes.size}")

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "[GEMINI] voice_request_completed=false")
            configManager?.updateStatus(GeminiStatus.API_KEY_INVALID, "Gemini API key is not configured")
            return@withContext SpeechProcessingResult(
                transcript = "",
                response = "",
                isSuccess = false,
                errorMessage = "Gemini API key is not configured",
                status = GeminiStatus.API_KEY_INVALID
            )
        }

        if (wavBytes.isEmpty() || wavBytes.size < 44) {
            Log.i(TAG, "[GEMINI] transcript_received=false")
            Log.i(TAG, "[GEMINI] voice_request_completed=true")
            return@withContext SpeechProcessingResult(
                transcript = "",
                response = "",
                isSuccess = true,
                isActualSilence = true,
                status = GeminiStatus.ONLINE
            )
        }

        val modelsToTry = getAudioFallbackModels(primaryAudioModel)
        var lastErrorMsg = "Audio processing failed"
        var lastStatus = GeminiStatus.SERVER_ERROR

        val base64Audio = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/wav")
                                put("data", base64Audio)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", "Listen to this audio. Transcribe what Prince said and formulate a helpful JARVIS assistant response (Gujarati preferred, then Hindi or English). Keep response very short (1–2 sentences).\nFormat EXACTLY like this:\nTRANSCRIPT: <verbatim words spoken>\nRESPONSE: <your concise spoken reply as JARVIS>")
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 256)
            })
        }

        for (model in modelsToTry) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            for (attempt in 1..2) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val code = response.code
                    val responseBody = response.body?.string() ?: ""

                    Log.i(TAG, "[GEMINI] audio_model=$model http_status=$code")

                    if (response.isSuccessful) {
                        val parsed = parseGenerateContentResponse(responseBody)
                        val fullText = parsed.text.trim()

                        var transcript = ""
                        var reply = fullText

                        if (fullText.contains("TRANSCRIPT:") && fullText.contains("RESPONSE:")) {
                            transcript = fullText.substringAfter("TRANSCRIPT:").substringBefore("RESPONSE:").trim()
                            reply = fullText.substringAfter("RESPONSE:").trim()
                        } else if (fullText.contains("TRANSCRIPT:")) {
                            transcript = fullText.substringAfter("TRANSCRIPT:").trim()
                            reply = transcript
                        }

                        val isSilence = transcript.isEmpty() ||
                                transcript.equals("[SILENCE]", ignoreCase = true) ||
                                transcript.equals("(silence)", ignoreCase = true) ||
                                transcript.equals("silence", ignoreCase = true)

                        Log.i(TAG, "[GEMINI] transcript_received=${!isSilence && transcript.isNotBlank()}")
                        Log.i(TAG, "[GEMINI] voice_request_completed=true (via $model)")
                        configManager?.updateStatus(GeminiStatus.ONLINE, "Voice processed via $model")

                        return@withContext SpeechProcessingResult(
                            transcript = if (isSilence) "" else transcript,
                            response = reply,
                            isSuccess = true,
                            isActualSilence = isSilence,
                            status = GeminiStatus.ONLINE
                        )
                    }

                    val errorMsg = parseErrorMessage(responseBody, code)
                    lastErrorMsg = errorMsg
                    lastStatus = classifyError(code, errorMsg)

                    if (code == 401 || code == 403) {
                        configManager?.updateStatus(lastStatus, errorMsg)
                        Log.e(TAG, "[GEMINI] voice_request_completed=false (Auth error)")
                        return@withContext SpeechProcessingResult(
                            transcript = "",
                            response = "",
                            isSuccess = false,
                            errorMessage = errorMsg,
                            status = lastStatus
                        )
                    }

                    if (isRetriableOrFallbackCode(code)) {
                        if (attempt == 1) {
                            Log.w(TAG, "[GEMINI] Audio model $model returned HTTP $code ($errorMsg). Retrying...")
                            delay(600)
                            continue
                        } else {
                            Log.w(TAG, "[GEMINI] Audio model $model busy. Trying fallback model...")
                            break
                        }
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    val isNetwork = e is UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true
                    lastStatus = if (isNetwork) GeminiStatus.NETWORK_ERROR else GeminiStatus.SERVER_ERROR
                    lastErrorMsg = e.localizedMessage ?: "Voice processing error"
                    if (attempt == 1 && !isNetwork) {
                        delay(500)
                    } else {
                        break
                    }
                }
            }
        }

        configManager?.updateStatus(lastStatus, lastErrorMsg)
        Log.e(TAG, "[GEMINI] voice_request_completed=false ($lastErrorMsg)")
        SpeechProcessingResult(
            transcript = "",
            response = "",
            isSuccess = false,
            errorMessage = lastErrorMsg,
            status = lastStatus
        )
    }

    companion object {
        private const val TAG = "GeminiAIProvider"
    }
}
