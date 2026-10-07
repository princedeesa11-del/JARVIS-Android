package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tools.ToolRegistry
import com.example.tools.ToolPermissionManager
import com.example.service.WakeWordService
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("JARVIS", appName)
  }

  @Test
  fun `verify tool registry contains core and phone control tools`() {
    val tools = ToolRegistry.allTools
    assertTrue(tools.isNotEmpty())
    val toolNames = tools.map { it.name }

    // Core tools
    assertTrue(toolNames.contains("launch_app"))
    assertTrue(toolNames.contains("get_battery_status"))
    assertTrue(toolNames.contains("get_device_info"))
    assertTrue(toolNames.contains("get_network_status"))
    assertTrue(toolNames.contains("create_web_project"))
    assertTrue(toolNames.contains("write_memory"))
    assertTrue(toolNames.contains("read_memory"))

    // Phone control & system tools
    assertTrue(toolNames.contains("open_url"))
    assertTrue(toolNames.contains("share_content"))
    assertTrue(toolNames.contains("dial_phone_number"))
    assertTrue(toolNames.contains("lookup_contact"))
    assertTrue(toolNames.contains("draft_message"))
    assertTrue(toolNames.contains("calendar_operation"))
    assertTrue(toolNames.contains("get_active_notifications"))
    assertTrue(toolNames.contains("accessibility_action"))
  }

  @Test
  fun `verify tool permission manager validates tool arguments`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val permissionManager = ToolPermissionManager(context)

    val dialTool = ToolRegistry.allTools.firstOrNull { it.name == "dial_phone_number" }
    assertNotNull(dialTool)

    val (missingValid, missingMsg) = permissionManager.validateArguments(dialTool!!, emptyMap<String, Any?>())
    assertFalse(missingValid)
    assertNotNull(missingMsg)
    assertTrue(missingMsg!!.contains("phoneNumber"))

    val (valid, _) = permissionManager.validateArguments(dialTool, mapOf("phoneNumber" to "911"))
    assertTrue(valid)
  }

  @Test
  fun `verify service static instances default to stopped`() {
    assertFalse(WakeWordService.isRunning())
    assertFalse(JarvisAccessibilityService.isRunning())
    assertFalse(JarvisNotificationListenerService.isRunning())
  }
}
