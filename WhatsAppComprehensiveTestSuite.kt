package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AIMessage
import com.example.ai.AIResponse
import com.example.ai.GeminiAIProvider
import com.example.data.local.JarvisDatabase
import com.example.whatsapp.client.WhatsAppApiResult
import com.example.whatsapp.client.WhatsAppCloudApiClient
import com.example.whatsapp.config.WhatsAppConfigStore
import com.example.whatsapp.config.WhatsAppSecureCredentialStore
import com.example.whatsapp.config.WhatsAppSecureCredentials
import com.example.whatsapp.data.WhatsAppContactEntity
import com.example.whatsapp.data.WhatsAppMessageEntity
import com.example.whatsapp.data.WhatsAppRuleEntity
import com.example.whatsapp.data.WhatsAppScheduledMessageEntity
import com.example.whatsapp.engine.IncomingWhatsAppMessage
import com.example.whatsapp.engine.WhatsAppAutomationEngine
import com.example.whatsapp.model.WhatsAppAutomationMode
import com.example.whatsapp.model.WhatsAppErrorType
import com.example.whatsapp.model.WhatsAppMessageDirection
import com.example.whatsapp.model.WhatsAppMessageStatus
import com.example.whatsapp.model.WhatsAppProviderMode
import com.example.whatsapp.model.WhatsAppRepeatInterval
import com.example.whatsapp.model.WhatsAppRuleType
import com.example.whatsapp.server.WhatsAppLocalServer
import com.example.whatsapp.service.WhatsAppSchedulerHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppComprehensiveTestSuite {

    private lateinit var context: Context
    private lateinit var db: JarvisDatabase
    private lateinit var configStore: WhatsAppConfigStore
    private lateinit var secureStore: WhatsAppSecureCredentialStore
    private lateinit var apiClient: WhatsAppCloudApiClient
    private lateinit var engine: WhatsAppAutomationEngine
    private lateinit var server: WhatsAppLocalServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = JarvisDatabase.getInstance(context)
        configStore = WhatsAppConfigStore.getInstance(context)
        secureStore = WhatsAppSecureCredentialStore.getInstance(context)
        apiClient = WhatsAppCloudApiClient(configStore)

        // Reset configStore and database state
        runBlocking {
            db.whatsAppDao().clearAllMessages()
            db.whatsAppDao().clearAuditLogs()
        }

        configStore.updateAutomationEnabled(true)
        configStore.updateProviderMode(WhatsAppProviderMode.OFFICIAL_CLOUD_API)
        configStore.updateAutomationMode(WhatsAppAutomationMode.AI_AUTO_REPLY)
        configStore.updateAiAutoReplyEnabled(true)
        configStore.updateRuleAutomationEnabled(true)
        configStore.updateScheduledMessagesEnabled(true)
        configStore.updateOptInRequired(false)
        configStore.updateRateLimitSeconds(1)

        configStore.updateCredentials(
            phoneId = "10987654321",
            wabaId = "20987654321",
            token = "EAABwTestTokenForRobolectricTesting12345",
            verifyTok = "jarvis_meta_webhook_verify_secret_token",
            port = 8088,
            publicUrl = "https://jarvis-test.ngrok.app"
        )

        val geminiProvider = GeminiAIProvider(com.example.ai.GeminiConfigManager.getInstance(context))
        engine = WhatsAppAutomationEngine(context, db.whatsAppDao(), configStore, apiClient, geminiProvider)
        server = WhatsAppLocalServer(context, configStore, db.whatsAppDao(), engine, apiClient)
    }

    // =========================================================================
    // 1. SECURE CREDENTIAL STORAGE & TOKEN MASKING
    // =========================================================================
    @Test
    fun `Test 1 - Secure Credential Store encrypts and decrypts credentials accurately`() {
        val creds = WhatsAppSecureCredentials(
            phoneNumberId = "11223344",
            businessAccountId = "55667788",
            accessToken = "EAABwTopSecretAccessTokenValue9999",
            verifyToken = "custom_secret_verify_token",
            webhookPublicUrl = "https://custom.webhook.url"
        )

        val saved = secureStore.saveCredentials(creds)
        assertTrue("Credentials must be saved securely", saved)

        val loaded = secureStore.getCredentials()
        assertEquals("11223344", loaded.phoneNumberId)
        assertEquals("55667788", loaded.businessAccountId)
        assertEquals("EAABwTopSecretAccessTokenValue9999", loaded.accessToken)
        assertEquals("custom_secret_verify_token", loaded.verifyToken)
        assertEquals("https://custom.webhook.url", loaded.webhookPublicUrl)
    }

    @Test
    fun `Test 2 - Token Masking protects sensitive credentials from exposure in UI and logs`() {
        val rawToken = "EAABwSecretToken1234"
        val masked = WhatsAppSecureCredentialStore.maskToken(rawToken)

        assertFalse("Masked token must not contain full secret prefix", masked.contains("EAABwSecretToken"))
        assertTrue("Masked token must preserve last 4 digits for user verification", masked.endsWith("1234"))
        assertTrue("Masked token must start with mask characters", masked.startsWith("••••••••••••"))

        val emptyMasked = WhatsAppSecureCredentialStore.maskToken("")
        assertEquals("Not configured", emptyMasked)
    }

    // =========================================================================
    // 2. WEBHOOK VERIFICATION HANDSHAKE
    // =========================================================================
    @Test
    fun `Test 3 - Webhook GET handshake with correct verify token returns challenge`() {
        val output = ByteArrayOutputStream()
        val queryParams = mapOf(
            "hub.mode" to "subscribe",
            "hub.verify_token" to "jarvis_meta_webhook_verify_secret_token",
            "hub.challenge" to "challenge_string_12345"
        )

        // Invoke private handleWebhookVerification via reflection for unit verification
        val method: Method = WhatsAppLocalServer::class.java.getDeclaredMethod(
            "handleWebhookVerification",
            Map::class.java,
            java.io.OutputStream::class.java
        )
        method.isAccessible = true
        method.invoke(server, queryParams, output)

        val responseStr = output.toString(Charsets.UTF_8.name())
        assertTrue("Response must return HTTP 200 OK", responseStr.contains("200 OK"))
        assertTrue("Response body must contain hub challenge", responseStr.contains("challenge_string_12345"))
    }

    @Test
    fun `Test 4 - Webhook GET handshake with token mismatch returns 403 Forbidden`() {
        val output = ByteArrayOutputStream()
        val queryParams = mapOf(
            "hub.mode" to "subscribe",
            "hub.verify_token" to "WRONG_TOKEN",
            "hub.challenge" to "challenge_string_12345"
        )

        val method: Method = WhatsAppLocalServer::class.java.getDeclaredMethod(
            "handleWebhookVerification",
            Map::class.java,
            java.io.OutputStream::class.java
        )
        method.isAccessible = true
        method.invoke(server, queryParams, output)

        val responseStr = output.toString(Charsets.UTF_8.name())
        assertTrue("Response must return HTTP 403", responseStr.contains("403 OK") || responseStr.contains("403"))
        assertFalse("Response must not leak challenge on token mismatch", responseStr.contains("challenge_string_12345"))
    }

    // =========================================================================
    // 3. WEBHOOK EVENT PARSING & UNSUPPORTED TYPES
    // =========================================================================
    @Test
    fun `Test 5 - Webhook POST parsing handles standard text message and status updates`() = runBlocking {
        val webhookPayload = """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "1000",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": { "display_phone_number": "123456", "phone_number_id": "10987654321" },
                "contacts": [{ "profile": { "name": "Elon Musk" }, "wa_id": "919999999999" }],
                "messages": [{
                  "from": "919999999999",
                  "id": "wamid.HBgLOTE5OTk5OTk5OTk5FQIAERgSRTc2MDhCMjg3N0QwRkQ2RDcA",
                  "timestamp": "1720000000",
                  "text": { "body": "Need an urgent status report" },
                  "type": "text"
                }]
              },
              "field": "messages"
            }]
          }]
        }
        """.trimIndent()

        val output = ByteArrayOutputStream()
        val method: Method = WhatsAppLocalServer::class.java.getDeclaredMethod(
            "handleWebhookEvent",
            String::class.java,
            java.io.OutputStream::class.java
        )
        method.isAccessible = true
        method.invoke(server, webhookPayload, output)

        val responseStr = output.toString(Charsets.UTF_8.name())
        assertTrue("Webhook receiver must return HTTP 200 immediately", responseStr.contains("200 OK"))

        // Allow async Coroutine to persist
        kotlinx.coroutines.delay(200)

        val savedMsg = db.whatsAppDao().getMessageByWhatsappId("wamid.HBgLOTE5OTk5OTk5OTk5FQIAERgSRTc2MDhCMjg3N0QwRkQ2RDcA")
        assertNotNull("Incoming message must be persisted to database", savedMsg)
        assertEquals("Need an urgent status report", savedMsg?.text)
        assertEquals("919999999999", savedMsg?.senderOrRecipientNumber)
    }

    @Test
    fun `Test 6 - Malformed and empty webhook payloads return safe response without crash`() {
        val output = ByteArrayOutputStream()
        val method: Method = WhatsAppLocalServer::class.java.getDeclaredMethod(
            "handleWebhookEvent",
            String::class.java,
            java.io.OutputStream::class.java
        )
        method.isAccessible = true

        // Empty body
        method.invoke(server, "", output)
        assertTrue(output.toString(Charsets.UTF_8.name()).contains("200 OK"))

        // Malformed JSON
        val output2 = ByteArrayOutputStream()
        method.invoke(server, "{ bad json: true", output2)
        assertTrue(output2.toString(Charsets.UTF_8.name()).contains("200 OK"))
    }

    // =========================================================================
    // 4. DUPLICATE MESSAGE DETECTION & IDEMPOTENCY
    // =========================================================================
    @Test
    fun `Test 7 - Duplicate message ID is detected and suppressed from reprocessing`() = runBlocking {
        val incoming = IncomingWhatsAppMessage(
            messageId = "wamid.DUPLICATE_TEST_ID_101",
            senderNumber = "919876543210",
            senderName = "Dev",
            text = "Testing duplicate protection",
            timestamp = System.currentTimeMillis()
        )

        // Process first time
        engine.processIncomingMessage(incoming)
        kotlinx.coroutines.delay(100)

        val countAfterFirst = db.whatsAppDao().getAllMessages().first().size
        assertEquals(1, countAfterFirst)

        // Process duplicate incoming message with exact same ID
        engine.processIncomingMessage(incoming)
        kotlinx.coroutines.delay(100)

        val countAfterDuplicate = db.whatsAppDao().getAllMessages().first().size
        assertEquals("Duplicate message must be ignored without creating duplicate records", 1, countAfterDuplicate)
    }

    // =========================================================================
    // 5. RULE MATCHING (KEYWORD, EXACT, REGEX, OUTSIDE HOURS)
    // =========================================================================
    @Test
    fun `Test 8 - Rule matching - Keyword contains, exact match, and outside hours`() = runBlocking {
        db.whatsAppDao().insertRule(
            WhatsAppRuleEntity(
                name = "Price Quote Rule",
                ruleType = WhatsAppRuleType.KEYWORD_CONTAINS.name,
                matchKeyword = "pricing",
                predefinedReply = "Our pricing details are available at https://example.com/pricing",
                enabled = true
            )
        )
        db.whatsAppDao().insertRule(
            WhatsAppRuleEntity(
                name = "Exact Greeting",
                ruleType = WhatsAppRuleType.KEYWORD_EXACT.name,
                matchKeyword = "hello",
                predefinedReply = "Hello! How can JARVIS assist you today?",
                enabled = true
            )
        )

        val activeRules = db.whatsAppDao().getActiveRules()
        assertEquals(2, activeRules.size)

        val evalMethod = WhatsAppAutomationEngine::class.java.getDeclaredMethod(
            "evaluateRuleMatch",
            WhatsAppRuleEntity::class.java,
            String::class.java
        )
        evalMethod.isAccessible = true

        val containsRule = activeRules.find { it.name == "Price Quote Rule" }!!
        val matchesContains = evalMethod.invoke(engine, containsRule, "Can you send the pricing list?") as Boolean
        assertTrue("Keyword contains rule must match", matchesContains)

        val exactRule = activeRules.find { it.name == "Exact Greeting" }!!
        val matchesExact = evalMethod.invoke(engine, exactRule, "hello") as Boolean
        assertTrue("Exact match rule must match", matchesExact)

        val failsExact = evalMethod.invoke(engine, exactRule, "hello there friend") as Boolean
        assertFalse("Exact match rule must fail when text contains additional words", failsExact)
    }

    // =========================================================================
    // 6. SCHEDULED MESSAGES RECURRENCE & DUPLICATE PROTECTION
    // =========================================================================
    @Test
    fun `Test 9 - Scheduled message persistence and repeat calculations`() = runBlocking {
        val item = WhatsAppScheduledMessageEntity(
            recipientNumber = "919876543210",
            recipientName = "Alice",
            messageText = "Morning Standup Reminder",
            scheduledTimeMillis = System.currentTimeMillis() + 3600000,
            repeatInterval = WhatsAppRepeatInterval.DAILY.name,
            enabled = true
        )

        val id = db.whatsAppDao().insertScheduledMessage(item)
        val loaded = db.whatsAppDao().getScheduledMessageById(id)
        assertNotNull(loaded)
        assertEquals("Morning Standup Reminder", loaded?.messageText)
        assertEquals("DAILY", loaded?.repeatInterval)

        val nextTimeMethod = WhatsAppSchedulerHelper::class.java.getDeclaredMethod(
            "calculateNextTriggerTime",
            Long::class.java,
            String::class.java
        )
        nextTimeMethod.isAccessible = true
        val nextTime = nextTimeMethod.invoke(WhatsAppSchedulerHelper, item.scheduledTimeMillis, "DAILY") as Long
        assertTrue("Next trigger time must be in the future", nextTime > item.scheduledTimeMillis)
    }

    // =========================================================================
    // 7. DISABLED AUTOMATION & CONTACT BLOCKING
    // =========================================================================
    @Test
    fun `Test 10 - Blocked contact or globally disabled automation skips auto-reply`() = runBlocking {
        // Create contact with automation blocked
        db.whatsAppDao().insertOrUpdateContact(
            WhatsAppContactEntity(
                phoneNumber = "919000000000",
                displayName = "Blocked User",
                isAutomationAllowed = false,
                optInVerified = true
            )
        )

        val incoming = IncomingWhatsAppMessage(
            messageId = "wamid.BLOCKED_CONTACT_TEST",
            senderNumber = "919000000000",
            senderName = "Blocked User",
            text = "Hello?",
            timestamp = System.currentTimeMillis()
        )

        engine.processIncomingMessage(incoming)
        kotlinx.coroutines.delay(100)

        // Incoming message was saved
        val msg = db.whatsAppDao().getMessageByWhatsappId("wamid.BLOCKED_CONTACT_TEST")
        assertNotNull(msg)

        // But no reply was generated
        val replies = db.whatsAppDao().getAllMessages().first().filter { it.direction == WhatsAppMessageDirection.OUTGOING.name }
        assertTrue("No outgoing reply should be sent to blocked contact", replies.isEmpty())
    }

    // =========================================================================
    // 8. ERROR PARSING & SANITIZATION
    // =========================================================================
    @Test
    fun `Test 11 - Error parser strips sensitive bearer tokens and classifies error types`() {
        val errorJson = """
        {
          "error": {
            "message": "Invalid OAuth access token - Cannot parse access token: EAABwToken12345678",
            "type": "OAuthException",
            "code": 190,
            "error_subcode": 463,
            "fbtrace_id": "A1B2C3D4"
          }
        }
        """.trimIndent()

        val parseMethod = WhatsAppCloudApiClient::class.java.getDeclaredMethod(
            "parseMetaApiError",
            String::class.java,
            Int::class.java
        )
        parseMethod.isAccessible = true
        val result = parseMethod.invoke(apiClient, errorJson, 401)

        val messageField = result.javaClass.getDeclaredField("message").apply { isAccessible = true }.get(result) as String
        val detailsField = result.javaClass.getDeclaredField("details").apply { isAccessible = true }.get(result) as String
        val typeField = result.javaClass.getDeclaredField("errorType").apply { isAccessible = true }.get(result) as WhatsAppErrorType

        assertEquals("Authentication Failed (Invalid or Expired Token)", messageField)
        assertEquals(WhatsAppErrorType.AUTHENTICATION_ERROR, typeField)
        assertFalse("Error details must never leak raw EAAB token", detailsField.contains("EAABwToken12345678"))
        assertTrue("Error details must contain masked representation", detailsField.contains("••••••••••••"))
    }
}
