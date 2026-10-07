package com.example

import com.example.ai.ConversationContextManager
import com.example.data.local.entity.ConversationMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationContextTest {

    private lateinit var contextManager: ConversationContextManager

    @Before
    fun setUp() {
        contextManager = ConversationContextManager()
    }

    @Test
    fun testMultiTurnMessaging_NeedsSlotThenResolves() {
        // Step 1: User says "message Rahul"
        val resolution1 = contextManager.resolveFollowUp("message Rahul")
        assertTrue(resolution1 is ConversationContextManager.ContextualResolution.NeedsSlot)
        val needsSlot = resolution1 as ConversationContextManager.ContextualResolution.NeedsSlot
        assertEquals("sms_message", needsSlot.slotName)
        assertTrue(needsSlot.promptUser.contains("Rahul"))

        // Step 2: User responds "Tell him I am coming home"
        val resolution2 = contextManager.resolveFollowUp("Tell him I am coming home")
        assertTrue(resolution2 is ConversationContextManager.ContextualResolution.Resolved)
        val resolved = resolution2 as ConversationContextManager.ContextualResolution.Resolved
        assertEquals("send_sms", resolved.action)
        assertEquals("Rahul", resolved.arguments["recipient"])
        assertEquals("I am coming home", resolved.arguments["message"])
    }

    @Test
    fun testDirectMessagingWithBody_ResolvesImmediately() {
        val resolution = contextManager.resolveFollowUp("send message to Tony saying Flight plan is confirmed")
        assertTrue(resolution is ConversationContextManager.ContextualResolution.Resolved)
        val resolved = resolution as ConversationContextManager.ContextualResolution.Resolved
        assertEquals("send_sms", resolved.action)
        assertEquals("Tony", resolved.arguments["recipient"])
        assertEquals("Flight plan is confirmed", resolved.arguments["message"])
    }

    @Test
    fun testContextualSearchInRecentlyOpenedApp() {
        // App is opened
        contextManager.updateApp("YouTube")

        // Follow-up search
        val resolution = contextManager.resolveFollowUp("search for Quantum Realm physics")
        assertTrue(resolution is ConversationContextManager.ContextualResolution.Resolved)
        val resolved = resolution as ConversationContextManager.ContextualResolution.Resolved
        assertEquals("search_in_app", resolved.action)
        assertEquals("YouTube", resolved.arguments["app"])
        assertEquals("Quantum Realm physics", resolved.arguments["query"])
    }

    @Test
    fun testMultiTurnCalendarScheduling() {
        // Step 1: "create a meeting tomorrow"
        val step1 = contextManager.resolveFollowUp("create a meeting tomorrow")
        assertTrue(step1 is ConversationContextManager.ContextualResolution.NeedsSlot)
        val needsSlot = step1 as ConversationContextManager.ContextualResolution.NeedsSlot
        assertEquals("calendar_time", needsSlot.slotName)

        // Step 2: "at 5 pm"
        val step2 = contextManager.resolveFollowUp("at 5 pm")
        assertTrue(step2 is ConversationContextManager.ContextualResolution.Resolved)
        val resolved = step2 as ConversationContextManager.ContextualResolution.Resolved
        assertEquals("create_calendar_event", resolved.action)
        assertEquals("Meeting", resolved.arguments["title"])
        val startMillis = resolved.arguments["startTimeMillis"] as Long
        val endMillis = resolved.arguments["endTimeMillis"] as Long
        assertTrue("End time should be after start time", endMillis > startMillis)
    }

    @Test
    fun testConversationMessageSchemaFields() {
        val msg = ConversationMessage(
            role = "assistant",
            content = "Directive complete",
            persona = "JARVIS",
            source = "voice",
            responseType = "TOOL_ACTION",
            executionState = "SUCCESS",
            requiredPermission = null
        )

        assertEquals("voice", msg.source)
        assertEquals("TOOL_ACTION", msg.responseType)
        assertEquals("SUCCESS", msg.executionState)
    }
}
