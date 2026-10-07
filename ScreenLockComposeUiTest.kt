package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.security.ScreenLockCredentialStore
import com.example.ui.screens.PatternPinScreen
import com.example.ui.screens.ScreenLockScreen
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
class ScreenLockComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ScreenLockCredentialStore.getInstance(context).clearCredential()
    }

    @Test
    fun `ScreenLockScreen displays title, wake switch, unlock row and a11y warning when service is off`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ScreenLockScreen(
                    onBack = {},
                    onNavigateToPatternPin = {}
                )
            }
        }

        // Title and sections exist
        composeTestRule.onNodeWithText("Screen lock").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wake the screen when she needs it").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wake_screen_switch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock with your pattern or PIN").assertIsDisplayed()

        // Accessibility warning shown when a11y service is OFF
        composeTestRule.onNodeWithTag("screen_lock_a11y_warning_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_lock_open_a11y_btn").assertIsDisplayed()
    }

    @Test
    fun `PatternPinScreen displays top status card, unlock switch, tabs and test section`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                PatternPinScreen(
                    onBack = {}
                )
            }
        }

        // Header and top status
        composeTestRule.onNodeWithText("Pattern & PIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not set up").assertIsDisplayed()

        // Unlock switch
        composeTestRule.onNodeWithTag("unlock_for_me_switch").assertIsDisplayed()

        // Tabs
        composeTestRule.onNodeWithTag("tab_pattern").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tab_pin").assertIsDisplayed()

        // Test section
        composeTestRule.onNodeWithTag("screen_lock_test_section_card").assertExists()
        composeTestRule.onNodeWithTag("lock_and_try_unlock_btn").assertExists()
    }

    @Test
    fun `PatternPinScreen warns user when toggling unlock with no credential configured`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                PatternPinScreen(
                    onBack = {}
                )
            }
        }

        // Attempt to turn on unlock switch without saving credential
        composeTestRule.onNodeWithTag("unlock_for_me_switch").performClick()

        // Warning message displayed
        composeTestRule.onNodeWithText("Save a pattern or a PIN below first.").assertIsDisplayed()
    }

    @Test
    fun `PatternPinScreen switches to PIN tab and allows entering digits`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                PatternPinScreen(
                    onBack = {}
                )
            }
        }

        // Click PIN tab
        composeTestRule.onNodeWithTag("tab_pin").performClick()

        // Check PIN inputs
        composeTestRule.onNodeWithTag("pin_input_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pin_confirm_input_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_pin_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clear_pin_btn").assertIsDisplayed()

        // Enter PIN
        composeTestRule.onNodeWithTag("pin_input_field").performTextInput("1234")
        composeTestRule.onNodeWithTag("pin_confirm_input_field").performTextInput("1234")

        // Save PIN
        composeTestRule.onNodeWithTag("save_pin_btn").performClick()

        // Success text displayed
        composeTestRule.onNodeWithText("PIN saved securely in Android Keystore.").assertIsDisplayed()
    }
}
