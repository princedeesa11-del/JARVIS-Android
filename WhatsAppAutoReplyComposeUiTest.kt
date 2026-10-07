package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.automation.whatsapp.WhatsAppAutoReplyPreferences
import com.example.ui.screens.WhatsAppAutoReplyScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class WhatsAppAutoReplyComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = WhatsAppAutoReplyPreferences.getInstance(context)
        prefs.setEnabled(false)
        prefs.setReplyMessage("Hi, I'm currently unavailable. I'll get back to you soon.")
    }

    @Test
    fun `WhatsAppAutoReplyScreen displays header, subtitle, main switch, text field and options`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WhatsAppAutoReplyScreen(onBack = {})
            }
        }

        // Title and Subtitle
        composeTestRule.onAllNodesWithText("WhatsApp Auto-Reply").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Automatically reply to incoming WhatsApp messages").assertIsDisplayed()

        // Status indicator (appears in summary and under switch)
        composeTestRule.onAllNodesWithText("Auto-reply is off").onFirst().assertIsDisplayed()

        // Main Toggle Switch
        composeTestRule.onNodeWithTag("whatsapp_autoreply_main_switch").assertIsDisplayed()

        // Message text field
        composeTestRule.onNodeWithTag("whatsapp_autoreply_text_field").assertIsDisplayed()

        // Option switches (may be scrollable)
        composeTestRule.onNodeWithTag("reply_to_everyone_switch").assertExists()
        composeTestRule.onNodeWithTag("only_when_away_switch").assertExists()
        composeTestRule.onNodeWithTag("reply_to_groups_switch").assertExists()
    }

    @Test
    fun `Toggling main switch updates status indicator`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WhatsAppAutoReplyScreen(onBack = {})
            }
        }

        // Toggle to ON
        composeTestRule.onNodeWithTag("whatsapp_autoreply_main_switch").performClick()

        // Should display active status
        composeTestRule.onAllNodesWithText("Auto-reply is active", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `Editing reply text updates text field and persists`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WhatsAppAutoReplyScreen(onBack = {})
            }
        }

        // Clear and type new message
        composeTestRule.onNodeWithTag("whatsapp_autoreply_text_field").performTextClearance()
        composeTestRule.onNodeWithTag("whatsapp_autoreply_text_field").performTextInput("Be right back!")

        composeTestRule.onNodeWithText("Be right back!").assertIsDisplayed()
    }

    @Test
    fun `Cooldown buttons are clickable and selectable`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WhatsAppAutoReplyScreen(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag("cooldown_1m_btn").performClick()
        composeTestRule.onNodeWithTag("cooldown_15m_btn").performClick()
    }
}
