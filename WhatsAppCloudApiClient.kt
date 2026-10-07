package com.example.whatsapp.client

import android.util.Log
import com.example.whatsapp.config.WhatsAppConfigStore
import com.example.whatsapp.model.WhatsAppErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class WhatsAppApiResult<out T> {
    data class Success<out T>(val data: T) : WhatsAppApiResult<T>()
    data class Error(
        val code: Int,
        val message: String,
        val details: String = "",
        val errorType: WhatsAppErrorType = WhatsAppErrorType.UNKNOWN_ERROR
    ) : WhatsAppApiResult<Nothing>()
}

data class WhatsAppSendMessageResponse(
    val messageId: String,
    val recipientNumber: String
)

data class WhatsAppConnectionTestResult(
    val verified: Boolean,
    val displayPhoneNumber: String,
    val qualityRating: String,
    val verifiedName: String
)

/**
 * Official Meta WhatsApp Cloud API Client using Graph API v20.0.
 *
 * Implements:
 * - Connection verification via GET https://graph.facebook.com/v20.0/{PHONE_NUMBER_ID}
 * - Outgoing message dispatch via POST https://graph.facebook.com/v20.0/{PHONE_NUMBER_ID}/messages
 * - Automatic exponential backoff retries for transient 5xx server errors and network timeouts (max 2 retries)
 * - Immediate abort without retry on 4xx client errors (e.g. invalid token, expired token, missing permissions)
 * - Safe sanitization of error messages to never leak Bearer tokens or secrets
 */
class WhatsAppCloudApiClient(private val configStore: WhatsAppConfigStore) {

    private val tag = "WhatsAppCloudApiClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Tests Meta Graph API connection with configured credentials.
     * Queries display_phone_number, verified_name, and quality_rating.
     */
    suspend fun testConnection(): WhatsAppApiResult<WhatsAppConnectionTestResult> = withContext(Dispatchers.IO) {
        val phoneId = configStore.phoneNumberId.value
        val token = configStore.accessToken.value

        if (phoneId.isBlank() || token.isBlank()) {
            return@withContext WhatsAppApiResult.Error(
                code = 400,
                message = "Missing Credentials",
                details = "Phone Number ID and Access Token must be provided.",
                errorType = WhatsAppErrorType.INVALID_PARAMETER
            )
        }

        val url = "https://graph.facebook.com/v20.0/$phoneId?fields=display_phone_number,verified_name,quality_rating"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        try {
            val response = executeWithRetry(request, maxRetries = 1)
            val bodyString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val displayPhone = json.optString("display_phone_number", phoneId)
                val verifiedName = json.optString("verified_name", "WhatsApp Business")
                val quality = json.optString("quality_rating", "GREEN")

                configStore.updateConnectionStatus(true, "Connected ($displayPhone)")
                WhatsAppApiResult.Success(
                    WhatsAppConnectionTestResult(
                        verified = true,
                        displayPhoneNumber = displayPhone,
                        qualityRating = quality,
                        verifiedName = verifiedName
                    )
                )
            } else {
                val parsed = parseMetaApiError(bodyString, response.code)
                configStore.updateConnectionStatus(false, parsed.details)
                WhatsAppApiResult.Error(
                    code = response.code,
                    message = parsed.message,
                    details = parsed.details,
                    errorType = parsed.errorType
                )
            }
        } catch (e: Exception) {
            val safeErr = sanitizeErrorMessage(e.localizedMessage ?: "Network connection error")
            Log.e(tag, "Connection test exception: $safeErr")
            configStore.updateConnectionStatus(false, safeErr)
            WhatsAppApiResult.Error(
                code = 500,
                message = "Network Error",
                details = safeErr,
                errorType = WhatsAppErrorType.NETWORK_ERROR
            )
        }
    }

    /**
     * Sends a text message via Meta WhatsApp Cloud API.
     * Optionally links to a prior message via context `replyToMessageId`.
     */
    suspend fun sendTextMessage(
        recipientPhone: String,
        text: String,
        replyToMessageId: String? = null
    ): WhatsAppApiResult<WhatsAppSendMessageResponse> = withContext(Dispatchers.IO) {
        val phoneId = configStore.phoneNumberId.value
        val token = configStore.accessToken.value

        if (phoneId.isBlank() || token.isBlank()) {
            return@withContext WhatsAppApiResult.Error(
                code = 400,
                message = "Missing Credentials",
                details = "Phone Number ID and Access Token are required.",
                errorType = WhatsAppErrorType.INVALID_PARAMETER
            )
        }

        // Clean recipient number: digits only (no +, -, spaces)
        val cleanNumber = recipientPhone.replace(Regex("[^0-9]"), "")
        if (cleanNumber.isBlank() || cleanNumber.length < 7) {
            return@withContext WhatsAppApiResult.Error(
                code = 400,
                message = "Invalid Phone Number",
                details = "Recipient phone number must contain international digits with country code.",
                errorType = WhatsAppErrorType.INVALID_PARAMETER
            )
        }

        val payload = JSONObject().apply {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", cleanNumber)
            put("type", "text")
            put("text", JSONObject().apply {
                put("preview_url", false)
                put("body", text)
            })

            if (!replyToMessageId.isNullOrBlank()) {
                put("context", JSONObject().apply {
                    put("message_id", replyToMessageId)
                })
            }
        }

        val url = "https://graph.facebook.com/v20.0/$phoneId/messages"
        val requestBody = payload.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .build()

        try {
            val response = executeWithRetry(request, maxRetries = 2)
            val bodyString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val messagesArray = json.optJSONArray("messages")
                val messageId = if (messagesArray != null && messagesArray.length() > 0) {
                    messagesArray.getJSONObject(0).optString("id", "wam_${System.currentTimeMillis()}")
                } else {
                    "wam_${System.currentTimeMillis()}"
                }

                WhatsAppApiResult.Success(
                    WhatsAppSendMessageResponse(
                        messageId = messageId,
                        recipientNumber = cleanNumber
                    )
                )
            } else {
                val parsed = parseMetaApiError(bodyString, response.code)
                WhatsAppApiResult.Error(
                    code = response.code,
                    message = parsed.message,
                    details = parsed.details,
                    errorType = parsed.errorType
                )
            }
        } catch (e: Exception) {
            val safeErr = sanitizeErrorMessage(e.localizedMessage ?: "Failed to transmit message payload")
            Log.e(tag, "sendTextMessage error: $safeErr")
            WhatsAppApiResult.Error(
                code = 500,
                message = "Network Error",
                details = safeErr,
                errorType = WhatsAppErrorType.NETWORK_ERROR
            )
        }
    }

    /**
     * Executes OkHttp request with safe exponential backoff retries.
     * Retries only on network IOExceptions and 5xx Server Errors.
     * Never retries on 4xx Client Errors (401, 403, 400).
     */
    private suspend fun executeWithRetry(request: Request, maxRetries: Int = 2): okhttp3.Response {
        var attempts = 0
        var backoffMs = 1000L

        while (true) {
            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful || response.code < 500 || attempts >= maxRetries) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                if (attempts >= maxRetries) throw e
                Log.w(tag, "Transient network failure on attempt $attempts: ${e.message}. Retrying in ${backoffMs}ms...")
            }

            attempts++
            delay(backoffMs)
            backoffMs *= 2
        }
    }

    private data class ParsedError(val message: String, val details: String, val errorType: WhatsAppErrorType)

    private fun parseMetaApiError(jsonBody: String, httpCode: Int): ParsedError {
        return try {
            val json = JSONObject(jsonBody)
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val msg = errorObj.optString("message", "")
                val type = errorObj.optString("type", "")
                val code = errorObj.optInt("code", httpCode)
                val subcode = errorObj.optInt("error_subcode", 0)
                val userTitle = errorObj.optJSONObject("error_data")?.optString("details")

                val errorType = when {
                    code == 190 || subcode == 463 || code == 100 && type == "OAuthException" -> WhatsAppErrorType.AUTHENTICATION_ERROR
                    code == 80007 || code == 4 || httpCode == 429 -> WhatsAppErrorType.RATE_LIMIT_ERROR
                    code in 100..104 -> WhatsAppErrorType.INVALID_PARAMETER
                    httpCode >= 500 -> WhatsAppErrorType.SERVER_ERROR
                    else -> WhatsAppErrorType.UNKNOWN_ERROR
                }

                val safeMessage = when (errorType) {
                    WhatsAppErrorType.AUTHENTICATION_ERROR -> "Authentication Failed (Invalid or Expired Token)"
                    WhatsAppErrorType.RATE_LIMIT_ERROR -> "Meta API Rate Limit Exceeded"
                    WhatsAppErrorType.INVALID_PARAMETER -> "Invalid API Parameters or Phone ID"
                    WhatsAppErrorType.SERVER_ERROR -> "Meta Cloud Server Error"
                    WhatsAppErrorType.UNKNOWN_ERROR -> "API Request Error ($httpCode)"
                    WhatsAppErrorType.NETWORK_ERROR -> "Network Connection Error"
                }

                val safeDetails = userTitle ?: if (msg.isNotBlank()) sanitizeErrorMessage(msg) else "HTTP $httpCode (Error code $code)"
                ParsedError(safeMessage, safeDetails, errorType)
            } else {
                ParsedError("HTTP Error $httpCode", "Server returned HTTP status $httpCode", WhatsAppErrorType.UNKNOWN_ERROR)
            }
        } catch (_: Exception) {
            ParsedError("HTTP Error $httpCode", "Unexpected response from WhatsApp API (HTTP $httpCode)", WhatsAppErrorType.UNKNOWN_ERROR)
        }
    }

    private fun sanitizeErrorMessage(message: String): String {
        // Strip any EAAB tokens or bearer credentials from error logs or UI messages
        return message.replace(Regex("EAA[A-Za-z0-9_-]+"), "••••••••••••")
            .replace(Regex("Bearer\\s+[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE), "Bearer ••••••")
    }
}
