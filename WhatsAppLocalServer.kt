package com.example.whatsapp.server

import android.content.Context
import android.util.Log
import com.example.whatsapp.client.WhatsAppCloudApiClient
import com.example.whatsapp.config.WhatsAppConfigStore
import com.example.whatsapp.data.WhatsAppAuditLogEntity
import com.example.whatsapp.data.WhatsAppDao
import com.example.whatsapp.data.WhatsAppRuleEntity
import com.example.whatsapp.data.WhatsAppScheduledMessageEntity
import com.example.whatsapp.engine.IncomingWhatsAppMessage
import com.example.whatsapp.engine.WhatsAppAutomationEngine
import com.example.whatsapp.model.WhatsAppMessageStatus
import com.example.whatsapp.model.WhatsAppRepeatInterval
import com.example.whatsapp.service.WhatsAppSchedulerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Local HTTP Backend Server & Meta Webhook Receiver for WhatsApp Cloud API.
 *
 * NOTE: This local server is intended for DEVELOPMENT, TESTING, and LOCAL AUTOMATION.
 * Meta's production Cloud API Webhooks require a public HTTPS URL. For live production
 * receiving on this device, route incoming traffic to this port via a secure tunnel
 * (such as ngrok, Cloudflare Tunnel, or an intermediate HTTPS gateway).
 *
 * Endpoints implemented:
 * 1. GET  /api/whatsapp/webhook -> Official Meta Webhook verification handshake:
 *    Validates hub.mode == "subscribe" && hub.verify_token == verifyToken, returns hub.challenge.
 * 2. POST /api/whatsapp/webhook -> Official Meta Webhook incoming message payload parser:
 *    Returns 200 OK immediately, parses asynchronously to WhatsAppAutomationEngine.
 * 3. GET  /api/whatsapp/status -> System status JSON (Tokens are masked, NEVER raw).
 * 4. POST /api/whatsapp/connect -> Triggers credentials test connection.
 * 5. POST /api/whatsapp/send -> REST API message composer dispatch.
 * 6. GET  /api/whatsapp/messages -> Recent messages.
 * 7. GET  /api/whatsapp/contacts -> Contacts list.
 * 8. GET  /api/whatsapp/rules & POST /api/whatsapp/rules -> Rule management.
 * 9. GET  /api/whatsapp/scheduled & POST /api/whatsapp/scheduled -> Scheduled messages.
 */
class WhatsAppLocalServer(
    private val context: Context,
    private val configStore: WhatsAppConfigStore,
    private val dao: WhatsAppDao,
    private val automationEngine: WhatsAppAutomationEngine,
    private val apiClient: WhatsAppCloudApiClient
) {

    private val tag = "WhatsAppLocalServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newFixedThreadPool(6)
    private val scope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun start(port: Int = configStore.webhookPort.value) {
        if (isRunning) {
            Log.d(tag, "Server already running on port $port")
            return
        }

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            isRunning = true
            Log.i(tag, "WhatsApp Webhook & Local Backend started on port $port [DEV/LOCAL TESTING]")

            threadPool.execute {
                while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        threadPool.execute {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(tag, "ServerSocket accept error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start WhatsAppLocalServer on port $port: ${e.message}", e)
            isRunning = false
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(tag, "WhatsApp Webhook & Local Backend stopped.")
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
    }

    fun isServerActive(): Boolean = isRunning

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            // Read HTTP headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx != -1) {
                    val key = line!!.substring(0, colonIdx).trim().lowercase()
                    val value = line!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read request body if present
            val bodyBuilder = StringBuilder()
            if (contentLength > 0) {
                val buffer = CharArray(1024)
                var bytesReadTotal = 0
                while (bytesReadTotal < contentLength) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, contentLength - bytesReadTotal))
                    if (count == -1) break
                    bodyBuilder.append(buffer, 0, count)
                    bytesReadTotal += count
                }
            }
            val body = bodyBuilder.toString()

            // Parse URL path and query parameters
            val questionIdx = fullPath.indexOf('?')
            val path = if (questionIdx != -1) fullPath.substring(0, questionIdx) else fullPath
            val queryParams = if (questionIdx != -1) parseQuery(fullPath.substring(questionIdx + 1)) else emptyMap()

            // Route request
            routeRequest(method, path, queryParams, body, output)
            output.flush()
        } catch (e: Exception) {
            Log.w(tag, "Client request error: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun routeRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: String,
        out: OutputStream
    ) {
        when {
            // 1. Meta Webhook GET Handshake (Verification)
            method == "GET" && (path == "/api/whatsapp/webhook" || path == "/webhook") -> {
                handleWebhookVerification(queryParams, out)
            }

            // 2. Meta Webhook POST Incoming Events
            method == "POST" && (path == "/api/whatsapp/webhook" || path == "/webhook") -> {
                handleWebhookEvent(body, out)
            }

            // 3. API Status (Sanitized, masked tokens)
            method == "GET" && path == "/api/whatsapp/status" -> {
                val statusJson = JSONObject().apply {
                    put("providerMode", configStore.providerMode.value.name)
                    put("automationEnabled", configStore.isAutomationEnabled.value)
                    put("aiAutoReplyEnabled", configStore.isAiAutoReplyEnabled.value)
                    put("rulesEnabled", configStore.isRuleAutomationEnabled.value)
                    put("scheduledEnabled", configStore.isScheduledMessagesEnabled.value)
                    put("mode", configStore.automationMode.value.name)
                    put("connected", configStore.isConnected.value)
                    put("statusMessage", configStore.connectionStatusMessage.value)
                    put("phoneNumberId", configStore.phoneNumberId.value)
                    put("maskedAccessToken", configStore.getMaskedAccessToken())
                    put("port", configStore.webhookPort.value)
                    put("serverType", "DEVELOPMENT_LOCAL_SERVER")
                    put("productionNotice", "For Meta production webhooks, expose this port via HTTPS tunnel.")
                }
                sendJsonResponse(out, 200, statusJson.toString())
            }

            // 4. API Connect / Test
            method == "POST" && path == "/api/whatsapp/connect" -> {
                scope.launch {
                    val testResult = apiClient.testConnection()
                    val responseJson = JSONObject().apply {
                        when (testResult) {
                            is com.example.whatsapp.client.WhatsAppApiResult.Success -> {
                                put("success", true)
                                put("verifiedName", testResult.data.verifiedName)
                                put("displayNumber", testResult.data.displayPhoneNumber)
                                put("qualityRating", testResult.data.qualityRating)
                            }
                            is com.example.whatsapp.client.WhatsAppApiResult.Error -> {
                                put("success", false)
                                put("error", testResult.message)
                                put("details", testResult.details)
                                put("errorType", testResult.errorType.name)
                            }
                        }
                    }
                    sendJsonResponse(out, 200, responseJson.toString())
                }
            }

            // 5. API Send Message
            method == "POST" && path == "/api/whatsapp/send" -> {
                try {
                    val json = JSONObject(body)
                    val to = json.optString("to")
                    val message = json.optString("message")
                    val replyToId = json.optString("replyToId").ifBlank { null }

                    if (to.isBlank() || message.isBlank()) {
                        sendJsonResponse(out, 400, """{"error":"Missing 'to' or 'message' parameter"}""")
                        return
                    }

                    scope.launch {
                        val result = apiClient.sendTextMessage(to, message, replyToId)
                        when (result) {
                            is com.example.whatsapp.client.WhatsAppApiResult.Success -> {
                                sendJsonResponse(out, 200, """{"success":true,"messageId":"${result.data.messageId}"}""")
                            }
                            is com.example.whatsapp.client.WhatsAppApiResult.Error -> {
                                val errJson = JSONObject().apply {
                                    put("success", false)
                                    put("error", result.message)
                                    put("details", result.details)
                                }
                                sendJsonResponse(out, 500, errJson.toString())
                            }
                        }
                    }
                } catch (e: Exception) {
                    sendJsonResponse(out, 400, """{"error":"Invalid JSON: ${e.message}"}""")
                }
            }

            // 6. API Messages History
            method == "GET" && path == "/api/whatsapp/messages" -> {
                scope.launch {
                    val list = dao.getAllMessages().first().take(50)
                    val array = JSONArray()
                    for (msg in list) {
                        array.put(JSONObject().apply {
                            put("id", msg.id)
                            put("messageId", msg.whatsappMessageId)
                            put("senderOrRecipient", msg.senderOrRecipientNumber)
                            put("contactName", msg.contactName)
                            put("text", msg.text)
                            put("direction", msg.direction)
                            put("status", msg.status)
                            put("isAiReply", msg.isAiReply)
                            put("timestamp", msg.timestamp)
                        })
                    }
                    sendJsonResponse(out, 200, JSONObject().put("messages", array).toString())
                }
            }

            // 7. API Contacts
            method == "GET" && path == "/api/whatsapp/contacts" -> {
                scope.launch {
                    val list = dao.getAllContacts().first()
                    val array = JSONArray()
                    for (c in list) {
                        array.put(JSONObject().apply {
                            put("id", c.id)
                            put("phoneNumber", c.phoneNumber)
                            put("displayName", c.displayName)
                            put("lastMessageSnippet", c.lastMessageSnippet)
                            put("lastActiveTimestamp", c.lastActiveTimestamp)
                            put("isAutomationAllowed", c.isAutomationAllowed)
                            put("optInVerified", c.optInVerified)
                        })
                    }
                    sendJsonResponse(out, 200, JSONObject().put("contacts", array).toString())
                }
            }

            // 8. API Rules (GET & POST)
            method == "GET" && path == "/api/whatsapp/rules" -> {
                scope.launch {
                    val list = dao.getAllRules().first()
                    val array = JSONArray()
                    for (r in list) {
                        array.put(JSONObject().apply {
                            put("id", r.id)
                            put("name", r.name)
                            put("enabled", r.enabled)
                            put("ruleType", r.ruleType)
                            put("matchKeyword", r.matchKeyword)
                            put("predefinedReply", r.predefinedReply)
                        })
                    }
                    sendJsonResponse(out, 200, JSONObject().put("rules", array).toString())
                }
            }
            method == "POST" && path == "/api/whatsapp/rules" -> {
                try {
                    val json = JSONObject(body)
                    val name = json.optString("name")
                    val type = json.optString("ruleType", "KEYWORD")
                    val keyword = json.optString("matchKeyword", "")
                    val reply = json.optString("predefinedReply", "")

                    if (name.isBlank() || reply.isBlank()) {
                        sendJsonResponse(out, 400, """{"error":"'name' and 'predefinedReply' are required"}""")
                        return
                    }

                    scope.launch {
                        val rule = WhatsAppRuleEntity(
                            name = name,
                            ruleType = type,
                            matchKeyword = keyword,
                            predefinedReply = reply,
                            enabled = true
                        )
                        val id = dao.insertRule(rule)
                        sendJsonResponse(out, 200, """{"success":true,"ruleId":$id}""")
                    }
                } catch (e: Exception) {
                    sendJsonResponse(out, 400, """{"error":"Invalid rule JSON: ${e.message}"}""")
                }
            }

            // 9. API Scheduled Messages (GET & POST)
            method == "GET" && path == "/api/whatsapp/scheduled" -> {
                scope.launch {
                    val list = dao.getAllScheduledMessages().first()
                    val array = JSONArray()
                    for (s in list) {
                        array.put(JSONObject().apply {
                            put("id", s.id)
                            put("recipientNumber", s.recipientNumber)
                            put("recipientName", s.recipientName)
                            put("messageText", s.messageText)
                            put("scheduledTimeMillis", s.scheduledTimeMillis)
                            put("repeatInterval", s.repeatInterval)
                            put("enabled", s.enabled)
                            put("deliveryStatus", s.deliveryStatus)
                        })
                    }
                    sendJsonResponse(out, 200, JSONObject().put("scheduled", array).toString())
                }
            }
            method == "POST" && path == "/api/whatsapp/scheduled" -> {
                try {
                    val json = JSONObject(body)
                    val recipient = json.optString("recipient")
                    val msg = json.optString("message")
                    val time = json.optLong("scheduledTimeMillis", System.currentTimeMillis() + 60000)
                    val repeat = json.optString("repeatInterval", WhatsAppRepeatInterval.ONCE.name)

                    if (recipient.isBlank() || msg.isBlank()) {
                        sendJsonResponse(out, 400, """{"error":"'recipient' and 'message' are required"}""")
                        return
                    }

                    scope.launch {
                        val item = WhatsAppScheduledMessageEntity(
                            recipientNumber = recipient,
                            recipientName = recipient,
                            messageText = msg,
                            scheduledTimeMillis = time,
                            repeatInterval = repeat,
                            enabled = true
                        )
                        val id = dao.insertScheduledMessage(item)
                        WhatsAppSchedulerHelper.scheduleMessage(context, item.copy(id = id))
                        sendJsonResponse(out, 200, """{"success":true,"scheduledId":$id}""")
                    }
                } catch (e: Exception) {
                    sendJsonResponse(out, 400, """{"error":"Invalid scheduled JSON: ${e.message}"}""")
                }
            }

            // Default 404
            else -> {
                sendJsonResponse(out, 404, """{"error":"Endpoint not found"}""")
            }
        }
    }

    /**
     * Official Meta Webhook verification handshake:
     * Validates:
     * - hub.mode == "subscribe"
     * - hub.verify_token == configuredToken
     * Returns: hub.challenge as plain text HTTP 200
     */
    private fun handleWebhookVerification(queryParams: Map<String, String>, out: OutputStream) {
        val mode = queryParams["hub.mode"]
        val token = queryParams["hub.verify_token"]
        val challenge = queryParams["hub.challenge"]

        val expectedToken = configStore.verifyToken.value

        Log.i(tag, "Webhook verification request received: mode=$mode, token_matches=${token == expectedToken}")

        if (mode == "subscribe" && token == expectedToken && !challenge.isNullOrBlank()) {
            Log.i(tag, "Webhook successfully verified with Meta!")
            scope.launch {
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "WEBHOOK_VERIFIED",
                        details = "Meta Webhook handshake verified successfully.",
                        status = "SUCCESS"
                    )
                )
            }
            sendPlainTextResponse(out, 200, challenge)
        } else {
            Log.w(tag, "Webhook verification failed: token mismatch or bad mode.")
            scope.launch {
                dao.insertAuditLog(
                    WhatsAppAuditLogEntity(
                        actionType = "WEBHOOK_VERIFICATION_FAILED",
                        details = "Verification failed: token mismatch.",
                        status = "FAILED"
                    )
                )
            }
            sendPlainTextResponse(out, 403, "Verification token mismatch")
        }
    }

    /**
     * Official Meta Webhook event receiver:
     * Parses incoming messages array and passes to WhatsAppAutomationEngine.
     * Returns HTTP 200 immediately to prevent Meta from timing out or retrying.
     */
    private fun handleWebhookEvent(body: String, out: OutputStream) {
        // Return 200 OK immediately
        sendJsonResponse(out, 200, """{"status":"EVENT_RECEIVED"}""")

        if (body.isBlank()) return

        scope.launch {
            try {
                val json = JSONObject(body)
                val entryArray = json.optJSONArray("entry") ?: return@launch

                for (i in 0 until entryArray.length()) {
                    val entryObj = entryArray.getJSONObject(i)
                    val changesArray = entryObj.optJSONArray("changes") ?: continue

                    for (j in 0 until changesArray.length()) {
                        val changeObj = changesArray.getJSONObject(j)
                        val valueObj = changeObj.optJSONObject("value") ?: continue

                        // Process incoming status updates (sent, delivered, read, failed)
                        val statusesArray = valueObj.optJSONArray("statuses")
                        if (statusesArray != null) {
                            for (s in 0 until statusesArray.length()) {
                                val statusObj = statusesArray.getJSONObject(s)
                                val statusMsgId = statusObj.optString("id")
                                val statusState = statusObj.optString("status").uppercase()
                                if (statusMsgId.isNotBlank()) {
                                    dao.updateMessageStatus(statusMsgId, statusState)
                                }
                            }
                        }

                        // Process incoming messages
                        val messagesArray = valueObj.optJSONArray("messages") ?: continue
                        val contactsArray = valueObj.optJSONArray("contacts")

                        var senderName = ""
                        if (contactsArray != null && contactsArray.length() > 0) {
                            val contactProfile = contactsArray.getJSONObject(0).optJSONObject("profile")
                            senderName = contactProfile?.optString("name", "") ?: ""
                        }

                        for (k in 0 until messagesArray.length()) {
                            val msgObj = messagesArray.getJSONObject(k)
                            val messageId = msgObj.optString("id")
                            val from = msgObj.optString("from")
                            val timestampStr = msgObj.optString("timestamp")
                            val timestamp = timestampStr.toLongOrNull()?.let { it * 1000L } ?: System.currentTimeMillis()
                            val type = msgObj.optString("type")

                            val messageText = when (type) {
                                "text" -> msgObj.optJSONObject("text")?.optString("body", "") ?: ""
                                "interactive" -> {
                                    val interactiveObj = msgObj.optJSONObject("interactive")
                                    val buttonReply = interactiveObj?.optJSONObject("button_reply")?.optString("title")
                                    val listReply = interactiveObj?.optJSONObject("list_reply")?.optString("title")
                                    buttonReply ?: listReply ?: "[Interactive selection]"
                                }
                                "image" -> "[Image: ${msgObj.optJSONObject("image")?.optString("caption", "Photo")}]"
                                "document" -> "[Document: ${msgObj.optJSONObject("document")?.optString("filename", "File")}]"
                                "audio" -> "[Voice message]"
                                "video" -> "[Video message]"
                                else -> "[Unsupported message type: $type]"
                            }

                            if (messageId.isNotBlank() && from.isNotBlank()) {
                                val incoming = IncomingWhatsAppMessage(
                                    messageId = messageId,
                                    senderNumber = from,
                                    senderName = senderName,
                                    text = messageText,
                                    timestamp = timestamp,
                                    rawJson = body
                                )
                                automationEngine.processIncomingMessage(incoming)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse webhook JSON event: ${e.message}", e)
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun sendJsonResponse(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
    }

    private fun sendPlainTextResponse(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
    }
}
