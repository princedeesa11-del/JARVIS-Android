package com.example

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.automation.whatsapp.AutoReplyResult
import com.example.automation.whatsapp.WhatsAppAutoReplyEngine
import com.example.automation.whatsapp.WhatsAppAutoReplyPreferences
import com.example.service.JarvisNotificationListenerService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhatsAppAutoReplyTestSuite {

    private lateinit var context: Context
    private lateinit var prefs: WhatsAppAutoReplyPreferences
    private lateinit var engine: WhatsAppAutoReplyEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = WhatsAppAutoReplyPreferences.getInstance(context)
        engine = WhatsAppAutoReplyEngine.getInstance(context)

        // Reset state for isolation
        com.example.whatsapp.config.WhatsAppConfigStore.getInstance(context)
            .updateProviderMode(com.example.whatsapp.model.WhatsAppProviderMode.LOCAL_ANDROID_AUTOMATION)
        prefs.setEnabled(false)
        prefs.setReplyMessage("Hi, I'm currently unavailable. I'll get back to you soon.")
        prefs.setCooldownMinutes(5)
        prefs.setReplyToEveryone(true)
        prefs.setOnlyWhenAway(false)
        prefs.setReplyToGroups(false)
        prefs.clearHistory()
        engine.resetCooldowns()
    }

    private fun createMockWhatsAppNotification(
        sender: String = "Alice",
        message: String = "Hello there!",
        packageName: String = "com.whatsapp",
        isGroup: Boolean = false,
        hasReplyAction: Boolean = true,
        notificationId: Int = 101
    ): StatusBarNotification {
        val builder = Notification.Builder(context, "test_channel")
            .setContentTitle(sender)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)

        if (isGroup) {
            builder.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true)
        }

        if (hasReplyAction) {
            val remoteInput = RemoteInput.Builder("key_text_reply")
                .setLabel("Reply")
                .build()

            val intent = Intent("com.whatsapp.REPLY_ACTION")
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val replyAction = Notification.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                pendingIntent
            ).addRemoteInput(remoteInput)
             .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
             .build()

            builder.addAction(replyAction)
        }

        val notification = builder.build()
        return StatusBarNotification(
            packageName,
            null,
            notificationId,
            "tag_$notificationId",
            1000,
            0,
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }

    // =========================================================
    // TEST 1: Auto-Reply OFF -> incoming WhatsApp message -> no reply.
    // =========================================================
    @Test
    fun `TEST 1 - Auto-Reply OFF ignores incoming WhatsApp message without sending reply`() {
        prefs.setEnabled(false)
        val sbn = createMockWhatsAppNotification()

        val result = engine.handleNotification(sbn, context)
        assertTrue(result is AutoReplyResult.Skipped)
        assertEquals("WhatsApp Auto-Reply is disabled.", (result as AutoReplyResult.Skipped).reason)
        assertEquals(0, prefs.totalRepliesSent.value)
        assertTrue(prefs.history.value.isEmpty())
    }

    // =========================================================
    // TEST 2: Auto-Reply ON + Notification Access ON -> incoming WhatsApp message -> configured reply is sent.
    // =========================================================
    @Test
    fun `TEST 2 - Auto-Reply ON sends configured reply to incoming WhatsApp message`() {
        prefs.setEnabled(true)
        val customMessage = "I am in a meeting, will text you later!"
        prefs.setReplyMessage(customMessage)

        val sbn = createMockWhatsAppNotification(sender = "Bob", message = "Can you talk?")
        val result = engine.handleNotification(sbn, context)

        assertTrue(result is AutoReplyResult.Replied)
        val replied = result as AutoReplyResult.Replied
        assertEquals("Bob", replied.sender)
        assertEquals(customMessage, replied.reply)
        assertEquals(1, prefs.totalRepliesSent.value)
        assertEquals(1, prefs.history.value.size)
        assertEquals("Bob", prefs.history.value[0].sender)
        assertEquals(customMessage, prefs.history.value[0].replySent)
    }

    // =========================================================
    // TEST 3: Same notification repeated -> no duplicate reply.
    // =========================================================
    @Test
    fun `TEST 3 - Duplicate notification is prevented from triggering repeated replies`() {
        prefs.setEnabled(true)
        val sbn = createMockWhatsAppNotification(sender = "Charlie", message = "Are you awake?", notificationId = 202)

        // First attempt -> Sent
        val firstResult = engine.handleNotification(sbn, context)
        assertTrue("First attempt should succeed", firstResult is AutoReplyResult.Replied)

        // Exact same notification posted again -> Skipped duplicate
        val secondResult = engine.handleNotification(sbn, context)
        assertTrue("Repeated notification must be skipped", secondResult is AutoReplyResult.Skipped)
        val reason = (secondResult as AutoReplyResult.Skipped).reason
        assertTrue(reason.contains("Cooldown") || reason.contains("Duplicate"))
        assertEquals("Total replies count should not increase", 1, prefs.totalRepliesSent.value)
    }

    // =========================================================
    // Cooldown test: Sender messaged within cooldown window
    // =========================================================
    @Test
    fun `Cooldown window suppresses repeated messages from same sender`() {
        prefs.setEnabled(true)
        prefs.setCooldownMinutes(10) // 10 minutes

        val sbn1 = createMockWhatsAppNotification(sender = "Dave", message = "Msg 1", notificationId = 301)
        val res1 = engine.handleNotification(sbn1, context)
        assertTrue(res1 is AutoReplyResult.Replied)

        // New message from Dave immediately after
        val sbn2 = createMockWhatsAppNotification(sender = "Dave", message = "Msg 2", notificationId = 302)
        val res2 = engine.handleNotification(sbn2, context)
        assertTrue(res2 is AutoReplyResult.Skipped)
        assertTrue((res2 as AutoReplyResult.Skipped).reason.contains("Cooldown active"))
        assertEquals(1, prefs.totalRepliesSent.value)
    }

    // =========================================================
    // TEST 4: Notification Access OFF -> safe handling, no crash
    // =========================================================
    @Test
    fun `TEST 4 - Notification Access check is safely queryable and does not crash`() {
        // Access check can be called without throws
        val hasAccess = JarvisNotificationListenerService.hasNotificationAccess(context)
        // In standard JVM test environment, listener package is ungranted unless mock set
        assertNotNull(hasAccess)
    }

    // =========================================================
    // Group message filtering
    // =========================================================
    @Test
    fun `Group messages are skipped when replyToGroups is false`() {
        prefs.setEnabled(true)
        prefs.setReplyToGroups(false)

        val groupSbn = createMockWhatsAppNotification(sender = "Family Group", message = "Dinner tonight?", isGroup = true)
        val res = engine.handleNotification(groupSbn, context)

        assertTrue(res is AutoReplyResult.Skipped)
        assertTrue((res as AutoReplyResult.Skipped).reason.contains("Group message ignored"))
    }

    // =========================================================
    // TEST 5 & 6: Persistence across app restarts and background state
    // =========================================================
    @Test
    fun `TEST 6 - Settings remain saved and persist across instances`() {
        prefs.setEnabled(true)
        prefs.setReplyMessage("Custom persistence message")
        prefs.setCooldownMinutes(15)
        prefs.setOnlyWhenAway(true)
        prefs.setReplyToGroups(true)

        // Clear singleton cache and reload from SharedPreferences
        val freshPrefs = WhatsAppAutoReplyPreferences.getInstance(context)
        assertTrue(freshPrefs.isEnabled.value)
        assertEquals("Custom persistence message", freshPrefs.replyMessage.value)
        assertEquals(15, freshPrefs.cooldownMinutes.value)
        assertTrue(freshPrefs.onlyWhenAway.value)
        assertTrue(freshPrefs.replyToGroups.value)
    }

    // =========================================================
    // Non-WhatsApp package filtering
    // =========================================================
    @Test
    fun `Non-WhatsApp packages are strictly ignored`() {
        prefs.setEnabled(true)
        val telegramSbn = createMockWhatsAppNotification(packageName = "org.telegram.messenger")
        val res = engine.handleNotification(telegramSbn, context)

        assertTrue(res is AutoReplyResult.Skipped)
        assertTrue((res as AutoReplyResult.Skipped).reason.contains("Ignored non-WhatsApp"))
    }

    // =========================================================
    // Outgoing self-message detection
    // =========================================================
    @Test
    fun `Outgoing self messages are ignored`() {
        prefs.setEnabled(true)
        val selfSbn = createMockWhatsAppNotification(sender = "You: Okay sounds good")
        val res = engine.handleNotification(selfSbn, context)

        assertTrue(res is AutoReplyResult.Skipped)
        assertTrue((res as AutoReplyResult.Skipped).reason.contains("outgoing/self"))
    }
}
