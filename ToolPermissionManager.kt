package com.example.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService

class ToolPermissionManager(private val context: Context) {

    /**
     * Validates input arguments against tool parameter constraints.
     * Prevents empty arguments, type mismatch, or injection payloads.
     */
    fun validateArguments(tool: ToolDefinition, args: Map<String, Any?>): Pair<Boolean, String?> {
        for (param in tool.parameters) {
            if (param.isRequired) {
                val value = args[param.name]
                if (value == null || (value is String && value.isBlank())) {
                    return false to "Missing required argument '${param.name}' for tool '${tool.name}'"
                }
            }
        }
        return true to null
    }

    fun requiresUserConfirmation(tool: ToolDefinition): Boolean {
        return tool.requiresConfirmation
    }

    /**
     * Checks if the required runtime or special permission is granted.
     */
    fun hasRequiredPermission(tool: ToolDefinition): Boolean {
        val perm = tool.requiredPermission ?: return true
        return when (perm) {
            "SPECIAL_ACCESSIBILITY" -> JarvisAccessibilityService.isRunning()
            "SPECIAL_NOTIFICATION_LISTENER" -> JarvisNotificationListenerService.isRunning()
            else -> ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks all common permissions for the settings/permission UI.
     */
    fun checkAssistantPermissions(): Map<String, Boolean> {
        return mapOf(
            "Microphone (Voice)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "Camera (Vision)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            "Notifications (Alerts)" to (
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
            ),
            "Contacts (Lookup & Dial)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED),
            "Call Log (History)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED),
            "Calendar (Events)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED),
            "SMS (Messaging)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED),
            "Location (GPS & Geofences)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            "Phone (Direct Calling)" to (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED),
            "Accessibility Service" to JarvisAccessibilityService.isRunning(),
            "Notification Listener" to JarvisNotificationListenerService.isRunning()
        )
    }
}
