package com.example.tools

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.local.entity.WebProjectItem
import com.example.data.repository.JarvisRepository
import com.example.receiver.JarvisAlarmReceiver
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import com.example.service.NodeQuery
import com.example.automation.AutomationStep
import com.example.automation.UniversalAppAutomationEngine
import com.example.automation.AutomationMemoryStore
import com.example.web.WebProjectManager
import com.example.web.WebSearchService
import com.example.ai.SemanticMemoryEngine
import com.example.data.local.entity.AutomationRule
import com.example.receiver.JarvisAlarmScheduler
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.TimeZone

class ToolExecutor(
    private val context: Context,
    private val repository: JarvisRepository,
    private val webProjectManager: WebProjectManager = WebProjectManager(context),
    private val webSearchService: WebSearchService = WebSearchService()
) {
    private val permissionManager = ToolPermissionManager(context)
    private val smsHelper = SmsManagerHelper(context)
    private val bluetoothHelper = BluetoothHelper(context)
    private val locationHelper = LocationGeofenceHelper(context, repository)
    private val documentHelper = DocumentManagerHelper(context)
    private val mediaHelper = MediaControlHelper(context)
    private val wifiHelper = WifiControlHelper(context)
    private val calendarHelper = CalendarHelper(context)
    private val telephonyHelper = TelephonyHelper(context)
    val memoryManager = com.example.ai.memory.MemoryManager(repository.database.memoryDao())
    val automationEngine = UniversalAppAutomationEngine(context)
    val workflowStore = AutomationMemoryStore(repository.database.automationDao())

    suspend fun execute(
        toolCall: com.example.ai.ToolCallRequest,
        execContext: ToolExecutionContext? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Tool validation
        val def = ToolRegistry.getToolDefinition(toolCall.name)
        if (def != null) {
            val (valid, validationErr) = permissionManager.validateArguments(def, toolCall.arguments)
            if (!valid) {
                return@withContext ToolResult(
                    toolName = toolCall.name,
                    isSuccess = false,
                    output = validationErr ?: "Invalid parameters",
                    errorMessage = validationErr
                )
            }
            if (!permissionManager.hasRequiredPermission(def)) {
                return@withContext ToolResult(
                    toolName = toolCall.name,
                    isSuccess = false,
                    output = "Permission denied or required service not enabled: ${def.requiredPermission}. Please grant access in Settings.",
                    errorMessage = "PERMISSION_DENIED"
                )
            }
        }

        val result = try {
            when (toolCall.name) {
                "launch_app" -> executeLaunchApp(toolCall.arguments["appName"]?.toString() ?: "")
                "open_url" -> executeOpenUrl(toolCall.arguments["url"]?.toString() ?: "")
                "get_battery_status" -> executeGetBatteryStatus()
                "get_device_info" -> executeGetDeviceInfo()
                "get_network_status" -> executeGetNetworkStatus()
                "calculate" -> executeCalculate(toolCall.arguments["expression"]?.toString() ?: "")
                "set_reminder" -> {
                    val title = toolCall.arguments["title"]?.toString() ?: "Reminder"
                    val delay = (toolCall.arguments["delayMinutes"] as? Number)?.toLong() ?: 5L
                    executeSetReminder(title, delay)
                }
                "copy_to_clipboard" -> executeCopyToClipboard(toolCall.arguments["text"]?.toString() ?: "")
                "share_content" -> executeShareContent(
                    toolCall.arguments["text"]?.toString() ?: "",
                    toolCall.arguments["title"]?.toString() ?: "Shared from JARVIS"
                )
                "dial_phone_number" -> executeDialPhoneNumber(
                    toolCall.arguments["phoneNumber"]?.toString() ?: "",
                    toolCall.arguments["directCall"] as? Boolean ?: false
                )
                "lookup_contact" -> executeLookupContact(toolCall.arguments["name"]?.toString() ?: "")
                "draft_message", "send_message" -> executeDraftMessage(
                    toolCall.arguments["phoneNumber"]?.toString() ?: toolCall.arguments["recipient"]?.toString() ?: "",
                    toolCall.arguments["message"]?.toString() ?: "",
                    toolCall.arguments["channel"]?.toString() ?: "SMS"
                )
                "capture_screen" -> executeCaptureScreen()
                "calendar_operation" -> executeCalendarOperation(
                    action = toolCall.arguments["action"]?.toString() ?: "READ",
                    title = toolCall.arguments["title"]?.toString() ?: "JARVIS Directive",
                    delayHours = (toolCall.arguments["delayHours"] as? Number)?.toLong() ?: 1L,
                    durationHours = (toolCall.arguments["durationHours"] as? Number)?.toLong() ?: 1L
                )
                "get_active_notifications" -> executeGetActiveNotifications()
                "accessibility_action" -> executeAccessibilityAction(
                    action = toolCall.arguments["action"]?.toString() ?: "INSPECT",
                    targetText = toolCall.arguments["targetText"]?.toString() ?: "",
                    inputText = toolCall.arguments["inputText"]?.toString() ?: ""
                )
                "search_web" -> executeSearchWeb(toolCall.arguments["query"]?.toString() ?: "")
                "read_memory" -> executeReadMemory(toolCall.arguments["query"]?.toString() ?: "")
                "write_memory" -> {
                    val key = toolCall.arguments["key"]?.toString() ?: "Note"
                    val content = toolCall.arguments["content"]?.toString() ?: ""
                    val category = toolCall.arguments["category"]?.toString() ?: "general"
                    val persona = execContext?.activePersonaName ?: "JARVIS"
                    executeWriteMemory(key, content, category, persona)
                }
                "delete_memory_by_query" -> executeDeleteMemory(toolCall.arguments["query"]?.toString() ?: "")
                "control_flashlight" -> {
                    val enabled = toolCall.arguments["enabled"] as? Boolean ?: true
                    executeControlFlashlight(enabled)
                }
                "adjust_volume" -> executeAdjustVolume(toolCall.arguments["direction"]?.toString() ?: "UP")
                "list_web_projects" -> executeListWebProjects()
                "read_web_file" -> {
                    val projectId = (toolCall.arguments["projectId"] as? Number)?.toLong() ?: 1L
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: "index.html"
                    executeReadWebFile(projectId, fileName)
                }
                "modify_web_project" -> {
                    val projectId = (toolCall.arguments["projectId"] as? Number)?.toLong() ?: 1L
                    val action = toolCall.arguments["action"]?.toString() ?: "ADD_JARVIS_KNOWLEDGE"
                    val details = toolCall.arguments["details"]?.toString() ?: ""
                    executeModifyWebProject(projectId, action, details)
                }
                "create_web_project" -> {
                    val name = toolCall.arguments["projectName"]?.toString() ?: "Web Application"
                    val desc = toolCall.arguments["description"]?.toString() ?: "Modern responsive website"
                    val type = toolCall.arguments["type"]?.toString() ?: "HTML_CSS_JS"
                    executeCreateWebProject(name, desc, type)
                }
                "edit_web_file" -> {
                    val projectId = (toolCall.arguments["projectId"] as? Number)?.toLong() ?: 1L
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: "index.html"
                    val content = toolCall.arguments["content"]?.toString() ?: ""
                    executeEditWebFile(projectId, fileName, content)
                }
                "export_web_project_zip" -> {
                    val projectId = (toolCall.arguments["projectId"] as? Number)?.toLong() ?: 1L
                    executeExportWebProjectZip(projectId)
                }
                // SMS Management
                "send_sms" -> {
                    val recipients = toolCall.arguments["recipients"]?.toString()
                        ?: toolCall.arguments["phoneNumber"]?.toString() ?: ""
                    val message = toolCall.arguments["message"]?.toString() ?: ""
                    val smsRes = smsHelper.sendDirectSms(recipients, message)
                    ToolResult(
                        toolName = "send_sms",
                        isSuccess = smsRes.isSuccess,
                        output = smsRes.message,
                        data = mapOf("status" to smsRes.status.name, "recipients" to smsRes.recipients),
                        errorMessage = if (!smsRes.isSuccess) smsRes.status.name else null
                    )
                }
                "prepare_sms" -> {
                    val recipients = toolCall.arguments["recipients"]?.toString()
                        ?: toolCall.arguments["phoneNumber"]?.toString() ?: ""
                    val message = toolCall.arguments["message"]?.toString() ?: ""
                    val prepRes = smsHelper.prepareSmsInComposer(recipients, message)
                    ToolResult(
                        toolName = "prepare_sms",
                        isSuccess = prepRes.isSuccess,
                        output = prepRes.message,
                        data = mapOf("status" to prepRes.status.name, "recipients" to prepRes.recipients)
                    )
                }
                "get_sms_capability" -> {
                    val caps = smsHelper.getSmsCapability()
                    ToolResult("get_sms_capability", true, "SMS Capability: $caps", caps)
                }

                // Official WhatsApp Cloud API tools
                "send_whatsapp_cloud_message" -> {
                    val recipientPhone = toolCall.arguments["recipientPhone"]?.toString()
                        ?: toolCall.arguments["to"]?.toString()
                        ?: toolCall.arguments["phoneNumber"]?.toString() ?: ""
                    val message = toolCall.arguments["message"]?.toString() ?: ""

                    if (recipientPhone.isBlank() || message.isBlank()) {
                        ToolResult(
                            toolName = "send_whatsapp_cloud_message",
                            isSuccess = false,
                            output = "Recipient phone number and message text are required.",
                            errorMessage = "MISSING_PARAMS"
                        )
                    } else {
                        val configStore = com.example.whatsapp.config.WhatsAppConfigStore.getInstance(context)
                        val apiClient = com.example.whatsapp.client.WhatsAppCloudApiClient(configStore)
                        val apiResult = apiClient.sendTextMessage(recipientPhone, message)

                        when (apiResult) {
                            is com.example.whatsapp.client.WhatsAppApiResult.Success -> {
                                val db = com.example.data.local.JarvisDatabase.getInstance(context)
                                val msgEntity = com.example.whatsapp.data.WhatsAppMessageEntity(
                                    whatsappMessageId = apiResult.data.messageId,
                                    senderOrRecipientNumber = recipientPhone,
                                    contactName = recipientPhone,
                                    text = message,
                                    direction = com.example.whatsapp.model.WhatsAppMessageDirection.OUTGOING.name,
                                    status = com.example.whatsapp.model.WhatsAppMessageStatus.SENT.name,
                                    isAiReply = false
                                )
                                db.whatsAppDao().insertMessage(msgEntity)
                                db.whatsAppDao().updateContactActivity(recipientPhone, message.take(80), System.currentTimeMillis())

                                ToolResult(
                                    toolName = "send_whatsapp_cloud_message",
                                    isSuccess = true,
                                    output = "WhatsApp Cloud API message successfully sent to $recipientPhone. Message ID: ${apiResult.data.messageId}",
                                    data = mapOf("messageId" to apiResult.data.messageId, "recipient" to recipientPhone)
                                )
                            }
                            is com.example.whatsapp.client.WhatsAppApiResult.Error -> {
                                ToolResult(
                                    toolName = "send_whatsapp_cloud_message",
                                    isSuccess = false,
                                    output = "Failed to send WhatsApp message via Meta Cloud API: ${apiResult.message} (${apiResult.details})",
                                    errorMessage = apiResult.details
                                )
                            }
                        }
                    }
                }
                "check_whatsapp_cloud_status" -> {
                    val configStore = com.example.whatsapp.config.WhatsAppConfigStore.getInstance(context)
                    val statusText = "WhatsApp Cloud API Status: " +
                            "Automation=${if (configStore.isAutomationEnabled.value) "ENABLED" else "DISABLED"}, " +
                            "Mode=${configStore.automationMode.value.name}, " +
                            "Connected=${configStore.isConnected.value}, " +
                            "Details=${configStore.connectionStatusMessage.value}"

                    ToolResult(
                        toolName = "check_whatsapp_cloud_status",
                        isSuccess = true,
                        output = statusText,
                        data = mapOf(
                            "automationEnabled" to configStore.isAutomationEnabled.value,
                            "mode" to configStore.automationMode.value.name,
                            "connected" to configStore.isConnected.value,
                            "phoneNumberId" to configStore.phoneNumberId.value
                        )
                    )
                }

                // YouTube Automation Execution
                "play_youtube" -> {
                    val query = toolCall.arguments["query"]?.toString() ?: ""
                    val ytEngine = com.example.youtube.engine.YouTubeAutomationEngine.getInstance(context)
                    val cmdRes = ytEngine.executePlay(query)
                    ToolResult(
                        toolName = "play_youtube",
                        isSuccess = cmdRes.success,
                        output = cmdRes.speechResponse,
                        data = mapOf("query" to query, "details" to (cmdRes.details ?: ""))
                    )
                }
                "search_youtube" -> {
                    val query = toolCall.arguments["query"]?.toString() ?: ""
                    val ytEngine = com.example.youtube.engine.YouTubeAutomationEngine.getInstance(context)
                    val cmdRes = ytEngine.executeSearch(query)
                    ToolResult(
                        toolName = "search_youtube",
                        isSuccess = cmdRes.success,
                        output = cmdRes.speechResponse,
                        data = mapOf("query" to query)
                    )
                }
                "control_youtube" -> {
                    val cmd = toolCall.arguments["command"]?.toString()?.uppercase() ?: "PLAY"
                    val ytEngine = com.example.youtube.engine.YouTubeAutomationEngine.getInstance(context)
                    val cmdRes = when (cmd) {
                        "PAUSE" -> ytEngine.executePause()
                        "RESUME", "PLAY" -> ytEngine.executeResume()
                        "STOP" -> ytEngine.executeStop()
                        "NEXT" -> ytEngine.executeNext()
                        "PREVIOUS" -> ytEngine.executePrevious()
                        "MUTE" -> ytEngine.executeMute(true)
                        "UNMUTE" -> ytEngine.executeMute(false)
                        "VOLUME_UP" -> ytEngine.executeVolumeAdjust(up = true)
                        "VOLUME_DOWN" -> ytEngine.executeVolumeAdjust(up = false)
                        "OPEN" -> ytEngine.executeOpen()
                        else -> ytEngine.executeResume()
                    }
                    ToolResult(
                        toolName = "control_youtube",
                        isSuccess = cmdRes.success,
                        output = cmdRes.speechResponse,
                        data = mapOf("command" to cmd)
                    )
                }

                // Bluetooth
                "get_bluetooth_status" -> {
                    val btStatus = bluetoothHelper.getBluetoothStatus()
                    ToolResult("get_bluetooth_status", true, "Bluetooth Status: $btStatus", btStatus)
                }
                "list_bluetooth_devices" -> {
                    val devices = bluetoothHelper.getPairedDevices()
                    val text = if (devices.isEmpty()) "No paired Bluetooth devices found." else "Paired devices:\n" + devices.joinToString("\n") { "${it["name"]} (${it["address"]}) [${it["type"]}]" }
                    ToolResult("list_bluetooth_devices", true, text, mapOf("devices" to devices))
                }
                "start_bluetooth_discovery" -> {
                    val (status, msg) = bluetoothHelper.startDiscovery()
                    ToolResult("start_bluetooth_discovery", status == NetworkControlStatus.SUCCESS, msg, mapOf("status" to status.name))
                }
                "open_bluetooth_settings" -> {
                    val (status, msg) = bluetoothHelper.openBluetoothSettings()
                    ToolResult("open_bluetooth_settings", status == NetworkControlStatus.SUCCESS || status == NetworkControlStatus.USER_ACTION_REQUIRED, msg, mapOf("status" to status.name))
                }
                "manage_bluetooth" -> {
                    val op = toolCall.arguments["operation"]?.toString()?.uppercase() ?: "STATUS"
                    when (op) {
                        "STATUS" -> {
                            val st = bluetoothHelper.getBluetoothStatus()
                            ToolResult("manage_bluetooth", true, "Bluetooth: $st", st)
                        }
                        "PAIRED_DEVICES", "LIST" -> {
                            val devs = bluetoothHelper.getPairedDevices()
                            ToolResult("manage_bluetooth", true, "Paired devices (${devs.size}):\n" + devs.joinToString("\n") { "${it["name"]} (${it["address"]})" }, mapOf("devices" to devs))
                        }
                        "DISCOVERY", "SCAN" -> {
                            val (status, msg) = bluetoothHelper.startDiscovery()
                            ToolResult("manage_bluetooth", status == NetworkControlStatus.SUCCESS, msg, mapOf("status" to status.name))
                        }
                        "SETTINGS", "PANEL" -> {
                            val (status, msg) = bluetoothHelper.openBluetoothSettings()
                            ToolResult("manage_bluetooth", status == NetworkControlStatus.USER_ACTION_REQUIRED || status == NetworkControlStatus.SUCCESS, msg, mapOf("status" to status.name))
                        }
                        else -> ToolResult("manage_bluetooth", false, "Unsupported Bluetooth operation: $op", errorMessage = "UNSUPPORTED_OPERATION")
                    }
                }

                // Location & Geofencing
                "get_current_location" -> {
                    val locData = locationHelper.getCurrentLocation()
                    val isOk = locData["success"] as? Boolean ?: false
                    val text = if (isOk) "Current coordinates: (${locData["latitude"]}, ${locData["longitude"]}), Accuracy: ${locData["accuracyMeters"]}m" else (locData["message"]?.toString() ?: "Failed to acquire location")
                    ToolResult("get_current_location", isOk, text, locData)
                }
                "create_geofence" -> {
                    val reqId = toolCall.arguments["requestId"]?.toString() ?: "fence_${System.currentTimeMillis()}"
                    val name = toolCall.arguments["name"]?.toString() ?: "Geofence"
                    val lat = (toolCall.arguments["latitude"] as? Number)?.toDouble() ?: toolCall.arguments["latitude"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val lng = (toolCall.arguments["longitude"] as? Number)?.toDouble() ?: toolCall.arguments["longitude"]?.toString()?.toDoubleOrNull() ?: 0.0
                    val radius = (toolCall.arguments["radiusMeters"] as? Number)?.toFloat() ?: toolCall.arguments["radiusMeters"]?.toString()?.toFloatOrNull() ?: 150f
                    val payload = toolCall.arguments["actionPayload"]?.toString() ?: ""
                    val (ok, msg) = locationHelper.createGeofence(reqId, name, lat, lng, radius, payload)
                    ToolResult("create_geofence", ok, msg, mapOf("requestId" to reqId, "name" to name))
                }
                "delete_geofence" -> {
                    val reqId = toolCall.arguments["requestId"]?.toString() ?: ""
                    val (ok, msg) = locationHelper.deleteGeofence(reqId)
                    ToolResult("delete_geofence", ok, msg)
                }
                "list_geofences" -> {
                    val fences = locationHelper.listGeofences()
                    val text = if (fences.isEmpty()) "No active geofences configured." else "Active Geofences:\n" + fences.joinToString("\n") { "• ${it.name} (${it.requestId}) at (${it.latitude}, ${it.longitude}) radius: ${it.radiusMeters}m" }
                    ToolResult("list_geofences", true, text, mapOf("count" to fences.size))
                }

                // Documents & Files
                "list_documents" -> {
                    val docs = documentHelper.listDocuments()
                    val text = if (docs.isEmpty()) "No documents in storage." else "Documents:\n" + docs.joinToString("\n") { "• ${it["fileName"]} (${it["sizeBytes"]} bytes, ${it["lastModified"]})" }
                    ToolResult("list_documents", true, text, mapOf("documents" to docs))
                }
                "save_document" -> {
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: "document.txt"
                    val content = toolCall.arguments["content"]?.toString() ?: ""
                    val (ok, msg) = documentHelper.saveDocument(fileName, content)
                    ToolResult("save_document", ok, msg)
                }
                "read_document" -> {
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: ""
                    val (ok, msg) = documentHelper.readDocument(fileName)
                    ToolResult("read_document", ok, msg)
                }
                "delete_document" -> {
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: ""
                    val (ok, msg) = documentHelper.deleteDocument(fileName)
                    ToolResult("delete_document", ok, msg)
                }
                "prepare_create_document" -> {
                    val name = toolCall.arguments["suggestedFileName"]?.toString() ?: "doc.txt"
                    val mime = toolCall.arguments["mimeType"]?.toString() ?: "text/plain"
                    val (ok, msg) = documentHelper.prepareCreateDocument(mime, name)
                    ToolResult("prepare_create_document", ok, msg)
                }
                "prepare_open_document" -> {
                    val mime = toolCall.arguments["mimeType"]?.toString() ?: "*/*"
                    val (ok, msg) = documentHelper.prepareOpenDocument(mime)
                    ToolResult("prepare_open_document", ok, msg)
                }

                // Media Controls
                "media_control" -> {
                    val cmd = toolCall.arguments["command"]?.toString()?.uppercase() ?: "TOGGLE"
                    val keyCode = when (cmd) {
                        "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
                        "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
                        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val (ok, msg) = mediaHelper.dispatchMediaKey(keyCode)
                    ToolResult("media_control", ok, msg)
                }
                "get_media_state" -> {
                    val state = mediaHelper.getPlaybackStatus()
                    ToolResult("get_media_state", true, "Playback status: $state", state)
                }

                // Wi-Fi
                "get_wifi_status" -> {
                    val status = wifiHelper.getWifiStatus()
                    ToolResult("get_wifi_status", true, "Wi-Fi status: $status", status)
                }
                "open_wifi_settings" -> {
                    val (status, msg) = wifiHelper.openWifiSettings()
                    ToolResult("open_wifi_settings", status == NetworkControlStatus.SUCCESS || status == NetworkControlStatus.USER_ACTION_REQUIRED, msg, mapOf("status" to status.name))
                }
                "scan_wifi_networks" -> {
                    val (status, networks) = wifiHelper.scanAvailableNetworks()
                    ToolResult(
                        "scan_wifi_networks",
                        status == NetworkControlStatus.SUCCESS,
                        if (status == NetworkControlStatus.SUCCESS) "Found ${networks.size} Wi-Fi networks." else "Wi-Fi scan: ${status.name}",
                        mapOf("status" to status.name, "networks" to networks)
                    )
                }
                "manage_wifi" -> {
                    val op = toolCall.arguments["operation"]?.toString()?.uppercase() ?: "STATUS"
                    when (op) {
                        "STATUS" -> {
                            val st = wifiHelper.getWifiStatus()
                            ToolResult("manage_wifi", true, "Wi-Fi status: $st", st)
                        }
                        "SCAN" -> {
                            val (status, networks) = wifiHelper.scanAvailableNetworks()
                            ToolResult("manage_wifi", status == NetworkControlStatus.SUCCESS, "Found ${networks.size} networks.", mapOf("status" to status.name, "networks" to networks))
                        }
                        "SETTINGS", "PANEL" -> {
                            val (status, msg) = wifiHelper.openWifiSettings()
                            ToolResult("manage_wifi", status == NetworkControlStatus.SUCCESS || status == NetworkControlStatus.USER_ACTION_REQUIRED, msg, mapOf("status" to status.name))
                        }
                        else -> ToolResult("manage_wifi", false, "Unsupported Wi-Fi operation: $op", errorMessage = "UNSUPPORTED_OPERATION")
                    }
                }

                // Calendar CRUD
                "create_calendar_event" -> {
                    val title = toolCall.arguments["title"]?.toString() ?: "JARVIS Event"
                    val start = (toolCall.arguments["startTimeMillis"] as? Number)?.toLong()
                        ?: toolCall.arguments["startTimeMillis"]?.toString()?.toLongOrNull()
                        ?: (System.currentTimeMillis() + 3600_000L)
                    val end = (toolCall.arguments["endTimeMillis"] as? Number)?.toLong()
                        ?: toolCall.arguments["endTimeMillis"]?.toString()?.toLongOrNull()
                        ?: (start + 3600_000L)
                    val desc = toolCall.arguments["description"]?.toString() ?: ""
                    val loc = toolCall.arguments["location"]?.toString() ?: ""
                    val rrule = toolCall.arguments["recurrenceRule"]?.toString() ?: toolCall.arguments["rrule"]?.toString() ?: ""
                    val attendeesStr = toolCall.arguments["attendees"]?.toString() ?: ""
                    val attendeesList = if (attendeesStr.isNotBlank()) attendeesStr.split(",").map { it.trim() } else emptyList()
                    val remindersStr = toolCall.arguments["reminders"]?.toString() ?: ""
                    val reminderMinutes = if (remindersStr.isNotBlank()) {
                        remindersStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    } else {
                        // Section 15: MEMORY + CALENDAR INTEGRATION
                        // Check if user has stored reminder preference (e.g. 15-min or 30-min reminders)
                        val storedPref = repository.database.memoryDao().getAllActiveMemoriesList().firstOrNull {
                            !it.isArchived && (it.category.contains("PREFERENCE", true) || it.category.contains("ROUTINE", true)) &&
                            (it.key.contains("reminder", true) || it.key.contains("calendar", true) || it.content.contains("reminder", true))
                        }
                        val min = storedPref?.content?.let { Regex("(\\d+)\\s*(?:min|minute)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                        if (min != null) listOf(min) else listOf(15)
                    }
                    val attendeesPairs = attendeesList.map { Pair(it, it) }

                    val calRes = calendarHelper.createEvent(
                        title = title,
                        description = desc,
                        location = loc,
                        startTimeMillis = start,
                        endTimeMillis = end,
                        allDay = false,
                        recurrenceRule = rrule,
                        remindersMinutes = reminderMinutes,
                        attendees = attendeesPairs,
                        selectedCalendarId = null
                    )
                    ToolResult("create_calendar_event", calRes.isSuccess, calRes.message, calRes.data)
                }
                "read_calendar_events" -> {
                    val days = (toolCall.arguments["daysAhead"] as? Number)?.toInt()
                        ?: toolCall.arguments["daysAhead"]?.toString()?.toIntOrNull() ?: 7
                    val calRes = calendarHelper.readUpcomingEvents(days)
                    ToolResult("read_calendar_events", calRes.isSuccess, calRes.message, calRes.data)
                }
                "update_calendar_event" -> {
                    val eventId = (toolCall.arguments["eventId"] as? Number)?.toLong()
                        ?: toolCall.arguments["eventId"]?.toString()?.toLongOrNull() ?: 0L
                    val title = toolCall.arguments["title"]?.toString()
                    val desc = toolCall.arguments["description"]?.toString()
                    val loc = toolCall.arguments["location"]?.toString()
                    val calRes = calendarHelper.updateEvent(eventId, title, desc, loc)
                    ToolResult("update_calendar_event", calRes.isSuccess, calRes.message)
                }
                "delete_calendar_event" -> {
                    val eventId = (toolCall.arguments["eventId"] as? Number)?.toLong()
                        ?: toolCall.arguments["eventId"]?.toString()?.toLongOrNull() ?: 0L
                    val calRes = calendarHelper.deleteEvent(eventId)
                    ToolResult("delete_calendar_event", calRes.isSuccess, calRes.message)
                }
                "calendar_manage" -> {
                    val action = toolCall.arguments["action"]?.toString()?.uppercase() ?: "CREATE"
                    val title = toolCall.arguments["title"]?.toString() ?: "JARVIS Event"
                    val delayHours = (toolCall.arguments["delayHours"] as? Number)?.toInt()
                        ?: toolCall.arguments["delayHours"]?.toString()?.toIntOrNull() ?: 1
                    val durationHours = (toolCall.arguments["durationHours"] as? Number)?.toInt()
                        ?: toolCall.arguments["durationHours"]?.toString()?.toIntOrNull() ?: 1
                    val start = (toolCall.arguments["startTimeMillis"] as? Number)?.toLong()
                        ?: toolCall.arguments["startTimeMillis"]?.toString()?.toLongOrNull()
                        ?: (System.currentTimeMillis() + (delayHours * 3600_000L))
                    val end = (toolCall.arguments["endTimeMillis"] as? Number)?.toLong()
                        ?: toolCall.arguments["endTimeMillis"]?.toString()?.toLongOrNull()
                        ?: (start + (durationHours * 3600_000L))
                    val desc = toolCall.arguments["description"]?.toString() ?: ""
                    val loc = toolCall.arguments["location"]?.toString() ?: ""
                    val rrule = toolCall.arguments["recurrenceRule"]?.toString() ?: toolCall.arguments["rrule"]?.toString() ?: ""
                    val attendeesStr = toolCall.arguments["attendees"]?.toString() ?: ""
                    val attendeesList = if (attendeesStr.isNotBlank()) attendeesStr.split(",").map { it.trim() } else emptyList()
                    val remindersStr = toolCall.arguments["reminders"]?.toString() ?: ""
                    val reminderMinutes = if (remindersStr.isNotBlank()) {
                        remindersStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    } else {
                        val storedPref = repository.database.memoryDao().getAllActiveMemoriesList().firstOrNull {
                            !it.isArchived && (it.category.contains("PREFERENCE", true) || it.category.contains("ROUTINE", true)) &&
                            (it.key.contains("reminder", true) || it.key.contains("calendar", true) || it.content.contains("reminder", true))
                        }
                        val min = storedPref?.content?.let { Regex("(\\d+)\\s*(?:min|minute)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                        if (min != null) listOf(min) else listOf(15)
                    }

                    when (action) {
                        "CREATE" -> {
                            val attendeesPairs = attendeesList.map { Pair(it, it) }
                            val res = calendarHelper.createEvent(
                                title = title,
                                description = desc,
                                location = loc,
                                startTimeMillis = start,
                                endTimeMillis = end,
                                allDay = false,
                                recurrenceRule = rrule,
                                remindersMinutes = reminderMinutes,
                                attendees = attendeesPairs,
                                selectedCalendarId = null
                            )
                            ToolResult("calendar_manage", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                        }
                        "READ", "LIST" -> {
                            val days = (toolCall.arguments["daysAhead"] as? Number)?.toInt() ?: 7
                            val res = calendarHelper.readUpcomingEvents(days)
                            ToolResult("calendar_manage", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                        }
                        "UPDATE" -> {
                            val eventId = (toolCall.arguments["eventId"] as? Number)?.toLong()
                                ?: toolCall.arguments["eventId"]?.toString()?.toLongOrNull() ?: 0L
                            val modeStr = toolCall.arguments["mode"]?.toString()?.uppercase()
                            val instanceTime = (toolCall.arguments["instanceTimeMillis"] as? Number)?.toLong()
                                ?: toolCall.arguments["instanceTimeMillis"]?.toString()?.toLongOrNull()

                            val res = if (instanceTime != null && (modeStr == "SINGLE_INSTANCE" || modeStr == "THIS_AND_FUTURE")) {
                                val mode = if (modeStr == "THIS_AND_FUTURE") CalendarHelper.RecurrenceOperationMode.THIS_AND_FUTURE else CalendarHelper.RecurrenceOperationMode.SINGLE_INSTANCE
                                calendarHelper.modifyRecurringOccurrence(
                                    seriesEventId = eventId,
                                    originalInstanceTimeMillis = instanceTime,
                                    mode = mode,
                                    newTitle = title,
                                    newStartMillis = start,
                                    newEndMillis = end,
                                    newLocation = loc,
                                    newDescription = desc
                                )
                            } else {
                                calendarHelper.updateEvent(eventId, title, desc, loc, start, end, rrule)
                            }
                            ToolResult("calendar_manage", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                        }
                        "DELETE" -> {
                            val eventId = (toolCall.arguments["eventId"] as? Number)?.toLong()
                                ?: toolCall.arguments["eventId"]?.toString()?.toLongOrNull() ?: 0L
                            val modeStr = toolCall.arguments["mode"]?.toString()?.uppercase()
                            val instanceTime = (toolCall.arguments["instanceTimeMillis"] as? Number)?.toLong()
                                ?: toolCall.arguments["instanceTimeMillis"]?.toString()?.toLongOrNull()

                            val res = if (instanceTime != null && (modeStr == "SINGLE_INSTANCE" || modeStr == "THIS_AND_FUTURE")) {
                                val mode = if (modeStr == "THIS_AND_FUTURE") CalendarHelper.RecurrenceOperationMode.THIS_AND_FUTURE else CalendarHelper.RecurrenceOperationMode.SINGLE_INSTANCE
                                calendarHelper.deleteRecurringOccurrence(
                                    seriesEventId = eventId,
                                    originalInstanceTimeMillis = instanceTime,
                                    mode = mode
                                )
                            } else {
                                calendarHelper.deleteEvent(eventId)
                            }
                            ToolResult("calendar_manage", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                        }
                        "INSTANCES" -> {
                            val eventId = (toolCall.arguments["eventId"] as? Number)?.toLong()
                                ?: toolCall.arguments["eventId"]?.toString()?.toLongOrNull()
                            val instances = calendarHelper.queryRecurrenceInstances(eventId, start, end)
                            val text = if (instances.isEmpty()) "No recurrence instances found." else "Recurrence Instances:\n" + instances.joinToString("\n") {
                                "• ${it.title} on ${Date(it.beginTimeMillis)}"
                            }
                            ToolResult("calendar_manage", true, text, mapOf("count" to instances.size))
                        }
                        else -> ToolResult("calendar_manage", false, "Unsupported calendar action '$action'", errorMessage = "UNSUPPORTED_ACTION")
                    }
                }

                // Semantic Long-Term Memory
                "semantic_memory_search" -> {
                    val query = toolCall.arguments["query"]?.toString() ?: ""
                    val limit = (toolCall.arguments["limit"] as? Number)?.toInt()
                        ?: toolCall.arguments["limit"]?.toString()?.toIntOrNull() ?: 5
                    val scored = memoryManager.searchMemories(query, limit = limit)
                    val text = if (scored.isEmpty()) "No semantically matching memories found for '$query'."
                    else "Semantically relevant memories:\n" + scored.joinToString("\n") { "• [Score: ${String.format(java.util.Locale.US, "%.2f", it.score)}] [${it.memory.category}] ${it.memory.key}: ${it.memory.content}" }
                    ToolResult("semantic_memory_search", true, text, mapOf("count" to scored.size))
                }
                "remember" -> {
                    val key = toolCall.arguments["key"]?.toString() ?: "Note"
                    val content = toolCall.arguments["content"]?.toString() ?: ""
                    val category = toolCall.arguments["category"]?.toString()
                    val importance = (toolCall.arguments["importance"] as? Number)?.toFloat()
                        ?: toolCall.arguments["importance"]?.toString()?.toFloatOrNull()
                    val res = memoryManager.saveOrUpdateMemory(
                        key = key,
                        content = content,
                        category = category,
                        importance = importance,
                        confidence = 1.0f,
                        source = "explicit_command"
                    )
                    when (res) {
                        is com.example.ai.memory.MemoryOperationResult.Saved -> ToolResult("remember", true, "[${res.statusCode}] ${res.detail} (ID: #${res.memoryId}, Category: ${res.category.displayName})", mapOf("id" to res.memoryId, "status" to res.statusCode))
                        is com.example.ai.memory.MemoryOperationResult.Updated -> ToolResult("remember", true, "[${res.statusCode}] ${res.detail}", mapOf("id" to res.memoryId, "status" to res.statusCode))
                        is com.example.ai.memory.MemoryOperationResult.Failed -> ToolResult("remember", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
                        else -> ToolResult("remember", false, "[${res.statusCode}] ${res.message}", errorMessage = res.statusCode)
                    }
                }
                "forget_memory" -> {
                    val idOrKey = toolCall.arguments["identifier"]?.toString()
                        ?: toolCall.arguments["id"]?.toString()
                        ?: toolCall.arguments["query"]?.toString() ?: ""
                    val res = memoryManager.forgetMemory(idOrKey)
                    when (res) {
                        is com.example.ai.memory.MemoryOperationResult.Deleted -> ToolResult("forget_memory", true, "[${res.statusCode}] ${res.detail}", mapOf("status" to res.statusCode))
                        is com.example.ai.memory.MemoryOperationResult.NotFound -> ToolResult("forget_memory", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
                        is com.example.ai.memory.MemoryOperationResult.Failed -> ToolResult("forget_memory", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
                        else -> ToolResult("forget_memory", false, "[${res.statusCode}] ${res.message}", errorMessage = res.statusCode)
                    }
                }
                "update_memory" -> {
                    val idOrKey = toolCall.arguments["id"]?.toString()
                        ?: toolCall.arguments["identifier"]?.toString() ?: ""
                    val content = toolCall.arguments["content"]?.toString() ?: ""
                    val res = memoryManager.updateMemoryContent(idOrKey, content)
                    when (res) {
                        is com.example.ai.memory.MemoryOperationResult.Updated -> ToolResult("update_memory", true, "[${res.statusCode}] ${res.detail}", mapOf("id" to res.memoryId, "status" to res.statusCode))
                        is com.example.ai.memory.MemoryOperationResult.NotFound -> ToolResult("update_memory", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
                        is com.example.ai.memory.MemoryOperationResult.Failed -> ToolResult("update_memory", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
                        else -> ToolResult("update_memory", false, "[${res.statusCode}] ${res.message}", errorMessage = res.statusCode)
                    }
                }
                "get_saved_memories" -> {
                    val summary = memoryManager.getPreferencesSummary()
                    ToolResult("get_saved_memories", true, summary)
                }

                // Telephony & Call State
                "call_contact" -> {
                    val number = toolCall.arguments["phoneNumber"]?.toString() ?: ""
                    val direct = toolCall.arguments["directCall"] as? Boolean ?: false
                    val callRes = telephonyHelper.dialOrCall(number, direct)
                    ToolResult("call_contact", callRes.isSuccess, callRes.message, mapOf("state" to callRes.state.name, "number" to callRes.number))
                }
                "get_call_state" -> {
                    val state = telephonyHelper.getCallState()
                    ToolResult("get_call_state", true, "Telephony Call State: $state", state)
                }
                "read_call_log" -> {
                    val limit = (toolCall.arguments["limit"] as? Number)?.toInt()
                        ?: toolCall.arguments["limit"]?.toString()?.toIntOrNull() ?: 10
                    val res = telephonyHelper.getCallHistory(limit)
                    val text = if (res.entries.isEmpty()) {
                        res.message
                    } else {
                        "Call History:\n" + res.entries.joinToString("\n") {
                            val nameStr = if (!it.cachedName.isNullOrBlank()) " (${it.cachedName})" else ""
                            "• [${it.type}] ${it.number}$nameStr - ${it.durationSeconds}s on ${Date(it.date)}"
                        }
                    }
                    ToolResult(
                        toolName = "read_call_log",
                        isSuccess = res.isSuccess,
                        output = text,
                        data = mapOf("status" to res.status.name, "count" to res.entries.size),
                        errorMessage = if (!res.isSuccess) res.status.name else null
                    )
                }

                // Web Research
                "extract_page_text" -> {
                    val url = toolCall.arguments["url"]?.toString() ?: ""
                    val (ok, text) = webSearchService.extractPageText(url)
                    ToolResult("extract_page_text", ok, text)
                }
                "research_topic", "research_web" -> {
                    val topic = toolCall.arguments["topic"]?.toString() ?: toolCall.arguments["query"]?.toString() ?: ""
                    val report = webSearchService.researchTopic(topic)
                    ToolResult("research_topic", true, report.summary, mapOf("confidence" to report.confidenceScore, "sourcesCount" to report.sources.size, "keyFindings" to report.keyFindings))
                }
                "compare_sources" -> {
                    val urlsStr = toolCall.arguments["urls"]?.toString() ?: ""
                    val urls = urlsStr.split(",").map { it.trim() }
                    val (ok, comparison) = webSearchService.compareSources(urls)
                    ToolResult("compare_sources", ok, comparison)
                }
                "follow_link" -> {
                    val url = toolCall.arguments["url"]?.toString() ?: ""
                    val (ok, text) = webSearchService.extractPageText(url)
                    ToolResult("follow_link", ok, text)
                }

                // Automation Rules
                "create_automation_rule" -> {
                    val name = toolCall.arguments["name"]?.toString() ?: "Automation"
                    val trigger = toolCall.arguments["trigger"]?.toString() ?: "TIME_DAILY"
                    val cond = toolCall.arguments["conditions"]?.toString() ?: ""
                    val action = toolCall.arguments["action"]?.toString() ?: "SPEAK_MESSAGE"
                    val payload = toolCall.arguments["actionPayload"]?.toString() ?: ""
                    val rule = AutomationRule(
                        name = name,
                        trigger = trigger,
                        conditions = cond,
                        actions = action,
                        actionPayload = payload
                    )
                    val ruleId = repository.saveAutomationRule(rule)
                    JarvisAlarmScheduler.scheduleAutomationRule(context, rule.copy(id = ruleId))

                    // Section 13 & 14: Save Routine to Memory
                    memoryManager.saveOrUpdateMemory(
                        key = "Routine: $name",
                        content = "Automated routine '$name' triggers at $trigger doing $action with condition '$cond'",
                        category = com.example.ai.memory.MemoryCategory.ROUTINE.code,
                        source = "automation_engine"
                    )
                    ToolResult("create_automation_rule", true, "Automation rule '$name' created (ID: $ruleId).", mapOf("ruleId" to ruleId))
                }
                "list_automation_rules" -> {
                    val rules = repository.getEnabledRules()
                    val text = if (rules.isEmpty()) "No automation rules currently active." else "Active Automation Rules:\n" + rules.joinToString("\n") { "• #${it.id}: '${it.name}' [Trigger: ${it.trigger}, Action: ${it.actions}] (Runs: ${it.runCount})" }
                    ToolResult("list_automation_rules", true, text, mapOf("count" to rules.size))
                }
                "delete_automation_rule" -> {
                    val ruleId = (toolCall.arguments["id"] as? Number)?.toLong()
                        ?: toolCall.arguments["id"]?.toString()?.toLongOrNull() ?: 0L
                    JarvisAlarmScheduler.cancelAutomationRule(context, ruleId)
                    repository.deleteAutomationRule(ruleId)
                    ToolResult("delete_automation_rule", true, "Automation rule #$ruleId deleted.")
                }
                "run_automation_now" -> {
                    val ruleId = (toolCall.arguments["id"] as? Number)?.toLong()
                        ?: toolCall.arguments["id"]?.toString()?.toLongOrNull() ?: 0L
                    val rules = repository.getEnabledRules()
                    val rule = rules.find { it.id == ruleId }
                    if (rule != null) {
                        repository.markRuleTriggered(ruleId, System.currentTimeMillis())
                        ToolResult("run_automation_now", true, "Triggered automation rule '${rule.name}'. Action: ${rule.actions} (${rule.actionPayload})")
                    } else {
                        ToolResult("run_automation_now", false, "Automation rule #$ruleId not found.")
                    }
                }
                // Comprehensive Device Control Additions
                "set_volume" -> {
                    val stream = toolCall.arguments["stream"]?.toString() ?: "MEDIA"
                    val percent = (toolCall.arguments["percent"] as? Number)?.toInt()
                        ?: toolCall.arguments["percent"]?.toString()?.toIntOrNull()
                    val direction = toolCall.arguments["direction"]?.toString()
                    val mute = toolCall.arguments["mute"] as? Boolean
                    executeSetVolume(stream, percent, direction, mute)
                }
                "open_settings" -> {
                    val category = toolCall.arguments["category"]?.toString() ?: "system"
                    executeOpenSettings(category)
                }
                "open_app_settings" -> {
                    val appName = toolCall.arguments["appName"]?.toString() ?: ""
                    executeOpenAppSettings(appName)
                }
                "is_app_installed" -> {
                    val appName = toolCall.arguments["appName"]?.toString() ?: ""
                    executeIsAppInstalled(appName)
                }
                "read_clipboard" -> executeReadClipboard()
                "clear_clipboard" -> executeClearClipboard()
                "open_file" -> {
                    val fileName = toolCall.arguments["fileName"]?.toString() ?: ""
                    executeOpenFile(fileName)
                }
                "get_storage_info" -> executeGetStorageInfo()
                "dismiss_notification" -> {
                    val query = toolCall.arguments["query"]?.toString() ?: ""
                    executeDismissNotification(query)
                }
                "reply_notification" -> {
                    val query = toolCall.arguments["query"]?.toString() ?: ""
                    val message = toolCall.arguments["message"]?.toString() ?: ""
                    executeReplyNotification(query, message)
                }
                "universal_app_action" -> {
                    val app = toolCall.arguments["appName"]?.toString()
                    val action = toolCall.arguments["action"]?.toString() ?: "CLICK"
                    val target = toolCall.arguments["targetText"]?.toString()
                    val textVal = toolCall.arguments["inputText"]?.toString()
                    val scrollDir = toolCall.arguments["scrollDirection"]?.toString()
                    executeUniversalAppAction(app, action, target, textVal, scrollDir)
                }
                "run_multi_step_workflow" -> {
                    val app = toolCall.arguments["appName"]?.toString() ?: ""
                    val stepsJson = toolCall.arguments["workflowStepsJson"]?.toString() ?: "[]"
                    val reqConfirm = toolCall.arguments["requiresConfirmation"] as? Boolean ?: false
                    val prompt = toolCall.arguments["confirmationPrompt"]?.toString()
                    executeMultiStepWorkflow(app, stepsJson, reqConfirm, prompt)
                }
                "inspect_screen_ui" -> executeInspectScreenUi()
                "confirm_pending_action" -> {
                    val confirmed = toolCall.arguments["confirmed"] as? Boolean ?: false
                    executeConfirmPendingAction(confirmed)
                }
                "save_automation_workflow" -> {
                    val name = toolCall.arguments["name"]?.toString() ?: "Custom Workflow"
                    val trigger = toolCall.arguments["triggerPhrase"]?.toString() ?: ""
                    val steps = toolCall.arguments["stepsJson"]?.toString() ?: "[]"
                    val desc = toolCall.arguments["description"]?.toString() ?: ""
                    executeSaveWorkflow(name, trigger, steps, desc)
                }
                "list_saved_workflows" -> executeListWorkflows()
                "configure_touch_guard" -> {
                    val action = toolCall.arguments["action"]?.toString()?.lowercase() ?: "status"
                    val prefs = com.example.touchguard.TouchGuardPreferences.getInstance(context)
                    when (action) {
                        "arm" -> {
                            prefs.setEnabled(true)
                            com.example.touchguard.TouchGuardService.arm(context)
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard arm sequence activated. Perimeter sentinel will lock in ${prefs.armDelaySeconds.value} seconds.")
                        }
                        "disarm" -> {
                            com.example.touchguard.TouchGuardService.disarm(context)
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard has been disarmed. Perimeter monitoring standing down.")
                        }
                        "silence" -> {
                            com.example.touchguard.TouchGuardService.silence(context)
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard siren and vocal warnings silenced.")
                        }
                        "enable" -> {
                            prefs.setEnabled(true)
                            com.example.touchguard.TouchGuardService.start(context)
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard enabled and daemon started.")
                        }
                        "disable" -> {
                            prefs.setEnabled(false)
                            com.example.touchguard.TouchGuardService.stop(context)
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard disabled and sentinel service stopped.")
                        }
                        "logs" -> {
                            val logs = prefs.logs.value
                            val text = if (logs.isEmpty()) "No security incidents recorded." else "Security Logs (${logs.size}):\n" + logs.take(5).joinToString("\n") { "• [${it.title}] ${it.description} (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp))})" }
                            ToolResult("configure_touch_guard", true, text, mapOf("count" to logs.size))
                        }
                        else -> {
                            val isArmed = prefs.isArmed.value
                            val isEnabled = prefs.isTouchGuardEnabled.value
                            val logsCount = prefs.logs.value.size
                            ToolResult("configure_touch_guard", true, "JARVIS Touch Guard Status: Enabled=$isEnabled, Armed=$isArmed, Incident Logs=$logsCount", mapOf("enabled" to isEnabled, "armed" to isArmed, "logs" to logsCount))
                        }
                    }
                }
                "configure_screen_lock" -> {
                    val action = toolCall.arguments["action"]?.toString()?.lowercase() ?: "status"
                    val manager = com.example.security.ScreenLockManager.getInstance(context)
                    val status = manager.credentialStatus.value
                    val a11yActive = manager.isAccessibilityServiceRunning()

                    when (action) {
                        "wake_on", "turn_wake_on" -> {
                            manager.setWakeScreenEnabled(true)
                            ToolResult("configure_screen_lock", true, "Screen wake is now enabled. JARVIS will wake the display for actions requiring screen interaction.")
                        }
                        "wake_off", "turn_wake_off" -> {
                            manager.setWakeScreenEnabled(false)
                            ToolResult("configure_screen_lock", true, "Screen wake is now disabled.")
                        }
                        "unlock_on", "turn_unlock_on" -> {
                            if (!status.configured) {
                                ToolResult("configure_screen_lock", false, "Cannot enable automatic unlock: No pattern or PIN has been configured in Screen Lock settings.")
                            } else {
                                manager.setUnlockEnabled(true)
                                ToolResult("configure_screen_lock", true, "Automatic screen unlock is now enabled for JARVIS.")
                            }
                        }
                        "unlock_off", "turn_unlock_off" -> {
                            manager.setUnlockEnabled(false)
                            ToolResult("configure_screen_lock", true, "Automatic screen unlock is now disabled. JARVIS will ask you to unlock manually.")
                        }
                        "test_unlock", "test" -> {
                            if (!status.configured) {
                                ToolResult("configure_screen_lock", false, "Cannot test unlock: No pattern or PIN is configured.")
                            } else if (!a11yActive) {
                                ToolResult("configure_screen_lock", false, "Cannot test unlock: JARVIS Accessibility Service is OFF.")
                            } else {
                                manager.executeUnlockTest(
                                    onStatusUpdate = {},
                                    onComplete = {}
                                )
                                ToolResult("configure_screen_lock", true, "Screen lock diagnostic test initiated. The screen will lock and attempt authorized unlock.")
                            }
                        }
                        else -> {
                            val desc = "Screen Lock Status: Configured=${status.configured}, CredentialType=${status.type}, UnlockEnabled=${status.unlockEnabled}, WakeEnabled=${manager.isWakeScreenEnabled.value}, AccessibilityServiceActive=$a11yActive"
                            ToolResult("configure_screen_lock", true, desc, mapOf(
                                "configured" to status.configured,
                                "type" to status.type.name,
                                "unlock_enabled" to status.unlockEnabled,
                                "wake_enabled" to manager.isWakeScreenEnabled.value,
                                "accessibility_active" to a11yActive
                            ))
                        }
                    }
                }
                "configure_whatsapp_auto_reply" -> {
                    val action = toolCall.arguments["action"]?.toString()?.lowercase() ?: "status"
                    val prefs = com.example.automation.whatsapp.WhatsAppAutoReplyPreferences.getInstance(context)
                    val hasAccess = com.example.service.JarvisNotificationListenerService.hasNotificationAccess(context)

                    when (action) {
                        "enable", "turn_on" -> {
                            prefs.setEnabled(true)
                            ToolResult("configure_whatsapp_auto_reply", true, "WhatsApp Auto-Reply is now enabled. Notification access status: ${if (hasAccess) "Granted" else "Requires permission in Settings"}")
                        }
                        "disable", "turn_off" -> {
                            prefs.setEnabled(false)
                            ToolResult("configure_whatsapp_auto_reply", true, "WhatsApp Auto-Reply is now disabled.")
                        }
                        "set_message" -> {
                            val msg = toolCall.arguments["message"]?.toString() ?: ""
                            if (msg.isBlank()) {
                                ToolResult("configure_whatsapp_auto_reply", false, "Message argument cannot be blank.")
                            } else {
                                prefs.setReplyMessage(msg)
                                ToolResult("configure_whatsapp_auto_reply", true, "WhatsApp auto-reply message updated: '$msg'")
                            }
                        }
                        "clear_history" -> {
                            prefs.clearHistory()
                            ToolResult("configure_whatsapp_auto_reply", true, "WhatsApp auto-reply history cleared.")
                        }
                        else -> {
                            val isEnabled = prefs.isEnabled.value
                            val msg = prefs.replyMessage.value
                            val cooldown = prefs.cooldownMinutes.value
                            val total = prefs.totalRepliesSent.value
                            val statusDesc = "WhatsApp Auto-Reply Status: Enabled=$isEnabled, NotificationAccess=$hasAccess, Message='$msg', Cooldown=${cooldown}m, TotalSent=$total"
                            ToolResult("configure_whatsapp_auto_reply", true, statusDesc, mapOf(
                                "enabled" to isEnabled,
                                "notification_access" to hasAccess,
                                "message" to msg,
                                "cooldown_minutes" to cooldown,
                                "total_replies" to total
                            ))
                        }
                    }
                }
                else -> ToolResult(
                    toolName = toolCall.name,
                    isSuccess = false,
                    output = "Tool '${toolCall.name}' is not recognized."
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = toolCall.name,
                isSuccess = false,
                output = "Execution failed: ${e.localizedMessage}",
                errorMessage = e.message
            )
        }

        val duration = System.currentTimeMillis() - startTime
        repository.logToolExecution(
            toolName = toolCall.name,
            args = toolCall.arguments.toString(),
            result = result.output,
            status = if (result.isSuccess) "SUCCESS" else "FAILED",
            duration = duration
        )

        result
    }

    private fun executeLaunchApp(appName: String): ToolResult {
        val trimmed = appName.trim()
        if (trimmed.isBlank()) {
            return ToolResult(
                toolName = "launch_app",
                isSuccess = false,
                output = "APP_NOT_FOUND: App name cannot be blank.",
                statusCode = ToolExecutionStatusCode.NOT_FOUND.name,
                errorMessage = "APP_NOT_FOUND"
            )
        }
        val lower = trimmed.lowercase()
        val pm = context.packageManager

        // Special system intent routes
        if (lower.contains("camera")) {
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return if (cameraIntent.resolveActivity(pm) != null) {
                try {
                    context.startActivity(cameraIntent)
                    ToolResult("launch_app", true, "Camera launched successfully.", statusCode = ToolExecutionStatusCode.SUCCESS.name)
                } catch (e: Exception) {
                    ToolResult("launch_app", false, "Failed to launch camera: ${e.message}", statusCode = ToolExecutionStatusCode.FAILED.name, errorMessage = e.message)
                }
            } else {
                ToolResult("launch_app", false, "APP_NOT_FOUND: Camera application not available.", statusCode = ToolExecutionStatusCode.NOT_FOUND.name, errorMessage = "APP_NOT_FOUND")
            }
        }

        if (lower.contains("setting")) {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return try {
                context.startActivity(settingsIntent)
                ToolResult("launch_app", true, "Device Settings opened.", statusCode = ToolExecutionStatusCode.SUCCESS.name)
            } catch (e: Exception) {
                ToolResult("launch_app", false, "Failed to open settings: ${e.message}", statusCode = ToolExecutionStatusCode.FAILED.name, errorMessage = e.message)
            }
        }

        // Direct package name launch if input looks like a package ID
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            val launchIntent = pm.getLaunchIntentForPackage(trimmed)
            if (launchIntent != null) {
                return try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolResult("launch_app", true, "Launched application: $trimmed.", statusCode = ToolExecutionStatusCode.SUCCESS.name, data = mapOf("package" to trimmed))
                } catch (e: Exception) {
                    ToolResult("launch_app", false, "Failed to launch $trimmed: ${e.message}", statusCode = ToolExecutionStatusCode.FAILED.name, errorMessage = e.message)
                }
            }
        }

        // Known common package aliases
        val targetPackage = when {
            lower.contains("yt music") || lower.contains("youtube music") -> "com.google.android.apps.youtube.music"
            lower.contains("spotify") -> "com.spotify.music"
            lower.contains("music") -> {
                val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                when (prefs.getString("pref_music_service", "YT Music")) {
                    "Spotify" -> "com.spotify.music"
                    "YouTube" -> "com.google.android.youtube"
                    else -> "com.google.android.apps.youtube.music"
                }
            }
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("chrome") -> "com.android.chrome"
            lower.contains("map") -> "com.google.android.apps.maps"
            lower.contains("whatsapp") -> "com.whatsapp"
            lower.contains("calc") -> "com.google.android.calculator"
            lower.contains("clock") || lower.contains("alarm") -> "com.google.android.deskclock"
            lower.contains("calendar") -> "com.google.android.calendar"
            lower.contains("gmail") || lower.contains("email") -> "com.google.android.gm"
            else -> null
        }

        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                return try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    ToolResult("launch_app", true, "Launched application: $trimmed ($targetPackage).", statusCode = ToolExecutionStatusCode.SUCCESS.name, data = mapOf("package" to targetPackage))
                } catch (e: Exception) {
                    ToolResult("launch_app", false, "Failed to launch $targetPackage: ${e.message}", statusCode = ToolExecutionStatusCode.FAILED.name, errorMessage = e.message)
                }
            }
        }

        // Search installed applications
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.equals(trimmed, ignoreCase = true) || label.lowercase().contains(lower) || lower.contains(label.lowercase())) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return ToolResult("launch_app", true, "Launched: $label (${app.packageName}).", statusCode = ToolExecutionStatusCode.SUCCESS.name, data = mapOf("package" to app.packageName, "app" to label))
                    }
                }
            }
        } catch (e: Exception) {
            // pass
        }

        return ToolResult(
            toolName = "launch_app",
            isSuccess = false,
            output = "APP_NOT_FOUND: Could not locate an installed app matching '$trimmed'.",
            statusCode = ToolExecutionStatusCode.NOT_FOUND.name,
            errorMessage = "APP_NOT_FOUND"
        )
    }

    private fun executeOpenUrl(url: String): ToolResult {
        val sanitizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitizedUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            ToolResult("open_url", true, "Opened URL in browser: $sanitizedUrl")
        } catch (e: Exception) {
            ToolResult("open_url", false, "Failed to open URL: ${e.message}")
        }
    }

    private fun executeShareContent(text: String, title: String): ToolResult {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
            type = "text/plain"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val shareIntent = Intent.createChooser(sendIntent, title).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(shareIntent)
            ToolResult("share_content", true, "Dispatched share dialog for content: '${text.take(40)}...'")
        } catch (e: Exception) {
            ToolResult("share_content", false, "Share intent error: ${e.message}")
        }
    }

    private fun executeDialPhoneNumber(phoneNumber: String, directCall: Boolean = false): ToolResult {
        val cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleaned.isBlank()) {
            return ToolResult("dial_phone_number", false, "Invalid phone number provided.", errorMessage = "INVALID_NUMBER")
        }

        if (directCall && ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleaned")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return try {
                context.startActivity(callIntent)
                ToolResult(
                    "dial_phone_number",
                    true,
                    "CALL_INITIATED: Outgoing call initiated to $cleaned via cellular telephony.",
                    mapOf("status" to "CALL_INITIATED", "number" to cleaned)
                )
            } catch (e: Exception) {
                ToolResult("dial_phone_number", false, "Failed to initiate direct call: ${e.message}", errorMessage = "CALL_ERROR")
            }
        }

        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(dialIntent)
            ToolResult(
                "dial_phone_number",
                true,
                "DIALER_OPENED: Opened system phone dialer with: $cleaned. User manual call dispatch required.",
                mapOf("status" to "DIALER_OPENED", "number" to cleaned)
            )
        } catch (e: Exception) {
            ToolResult("dial_phone_number", false, "Failed to launch phone dialer: ${e.message}", errorMessage = "DIALER_ERROR")
        }
    }

    private fun executeLookupContact(name: String): ToolResult {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult("lookup_contact", false, "Permission READ_CONTACTS is required to search contacts.", errorMessage = "PERMISSION_DENIED")
        }
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val matches = mutableListOf<String>()
                do {
                    val contactName = it.getString(0) ?: "Unknown"
                    val number = it.getString(1) ?: ""
                    matches.add("$contactName: $number")
                } while (it.moveToNext() && matches.size < 5)
                return ToolResult("lookup_contact", true, "Found contact(s):\n" + matches.joinToString("\n"), mapOf("count" to matches.size))
            }
        }
        return ToolResult("lookup_contact", true, "No contact matching '$name' was found in your device contacts.", mapOf("count" to 0))
    }

    private fun executeDraftMessage(
        recipient: String,
        message: String,
        channel: String = "SMS"
    ): ToolResult {
        if (recipient.isBlank()) {
            return ToolResult(
                "draft_message",
                false,
                "Recipient phone number or contact name is required.",
                errorMessage = MessagingResultStatus.MESSAGE_FAILED.name
            )
        }

        var targetNumber = recipient.trim()
        // Auto-resolve contact if non-numeric
        if (!targetNumber.matches("^[+0-9\\-\\s()]+$".toRegex())) {
            val resolved = resolveContactNumber(targetNumber)
            if (resolved != null) {
                targetNumber = resolved
            }
        }

        val cleanPhone = targetNumber.replace("[^0-9+]".toRegex(), "")

        return if (channel.equals("WHATSAPP", ignoreCase = true)) {
            try {
                val encodedText = Uri.encode(message)
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedText")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(
                    "draft_message",
                    true,
                    "The message composer is ready. Please press Send.",
                    mapOf(
                        "status" to MessagingResultStatus.MESSAGE_COMPOSER_OPENED.name,
                        "recipient" to cleanPhone,
                        "channel" to "WhatsApp"
                    )
                )
            } catch (e: Exception) {
                ToolResult(
                    "draft_message",
                    false,
                    "Could not open WhatsApp: ${e.message}",
                    errorMessage = MessagingResultStatus.MESSAGE_FAILED.name
                )
            }
        } else {
            // SMS Composer
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$cleanPhone")
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ToolResult(
                    "draft_message",
                    true,
                    "The message composer is ready. Please press Send.",
                    mapOf(
                        "status" to MessagingResultStatus.MESSAGE_COMPOSER_OPENED.name,
                        "recipient" to cleanPhone,
                        "channel" to "SMS"
                    )
                )
            } catch (e: Exception) {
                ToolResult(
                    "draft_message",
                    false,
                    "Could not open SMS composer: ${e.message}",
                    errorMessage = MessagingResultStatus.MESSAGE_FAILED.name
                )
            }
        }
    }

    private fun resolveContactNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeCalendarOperation(
        action: String,
        title: String,
        delayHours: Long,
        durationHours: Long
    ): ToolResult {
        return when (action.uppercase()) {
            "READ", "LIST" -> {
                val res = calendarHelper.readUpcomingEvents(7)
                ToolResult("calendar_operation", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
            }
            "CREATE" -> {
                val startMillis = System.currentTimeMillis() + (delayHours * 60 * 60 * 1000L)
                val endMillis = startMillis + (durationHours * 60 * 60 * 1000L)
                val res = calendarHelper.createEvent(
                    title = title.ifBlank { "JARVIS Event" },
                    description = "Scheduled via JARVIS Assistant",
                    startTimeMillis = startMillis,
                    endTimeMillis = endMillis
                )
                ToolResult("calendar_operation", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
            }
            "UPDATE" -> {
                val listRes = calendarHelper.readUpcomingEvents(30)
                @Suppress("UNCHECKED_CAST")
                val events = listRes.data?.get("events") as? List<Map<String, Any>>
                val matched = events?.find { it["title"]?.toString()?.contains(title, ignoreCase = true) == true }
                val eventId = (matched?.get("id") as? Number)?.toLong()
                if (eventId != null) {
                    val res = calendarHelper.updateEvent(eventId = eventId, title = title, description = "Updated by JARVIS at ${System.currentTimeMillis()}")
                    ToolResult("calendar_operation", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                } else {
                    ToolResult("calendar_operation", false, "CALENDAR_NOT_FOUND: No event found matching '$title' to update.", errorMessage = "NOT_FOUND")
                }
            }
            "DELETE" -> {
                val listRes = calendarHelper.readUpcomingEvents(30)
                @Suppress("UNCHECKED_CAST")
                val events = listRes.data?.get("events") as? List<Map<String, Any>>
                val matched = events?.find { it["title"]?.toString()?.contains(title, ignoreCase = true) == true }
                val eventId = (matched?.get("id") as? Number)?.toLong()
                if (eventId != null) {
                    val res = calendarHelper.deleteEvent(eventId)
                    ToolResult("calendar_operation", res.isSuccess, res.message, res.data, if (!res.isSuccess) res.status.name else null)
                } else {
                    ToolResult("calendar_operation", false, "CALENDAR_NOT_FOUND: No event found matching '$title' to delete.", errorMessage = "NOT_FOUND")
                }
            }
            else -> ToolResult("calendar_operation", false, "Unsupported calendar action: '$action'. Supported: READ, CREATE, UPDATE, DELETE.", errorMessage = "UNSUPPORTED_ACTION")
        }
    }

    private fun executeGetActiveNotifications(): ToolResult {
        val service = JarvisNotificationListenerService.instance
        if (service == null) {
            return ToolResult(
                toolName = "get_active_notifications",
                isSuccess = false,
                output = "NOTIFICATION_ACCESS_REQUIRED: Notification Listener is not enabled. Please enable JARVIS in Settings -> Notification Access.",
                statusCode = ToolExecutionStatusCode.PERMISSION_REQUIRED.name,
                errorMessage = "NOTIFICATION_ACCESS_REQUIRED"
            )
        }
        val summaries = service.getActiveNotificationSummaries(10)
        if (summaries.isEmpty()) {
            return ToolResult(
                toolName = "get_active_notifications",
                isSuccess = true,
                output = "No active unread notifications detected on device.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name
            )
        }
        val sb = StringBuilder("Active Notifications (${summaries.size}):\n")
        for (s in summaries) {
            sb.append("• [${s.appName}] ${s.title}: ${s.snippet}\n")
        }
        return ToolResult(
            toolName = "get_active_notifications",
            isSuccess = true,
            output = sb.toString().trim(),
            statusCode = ToolExecutionStatusCode.SUCCESS.name,
            data = mapOf("count" to summaries.size)
        )
    }

    private fun executeAccessibilityAction(action: String, targetText: String, inputText: String = ""): ToolResult {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return ToolResult(
                toolName = "accessibility_action",
                isSuccess = false,
                output = "ACCESSIBILITY_SERVICE_REQUIRED: Accessibility Service is not active. Please enable JARVIS Accessibility in Android Settings.",
                statusCode = ToolExecutionStatusCode.PERMISSION_REQUIRED.name,
                errorMessage = "ACCESSIBILITY_SERVICE_REQUIRED"
            )
        }

        return when (action.uppercase()) {
            "INSPECT" -> {
                val nodes = service.inspectCurrentScreen()
                if (nodes.isEmpty()) {
                    ToolResult("accessibility_action", true, "Screen inspected: No accessible UI elements detected.", statusCode = ToolExecutionStatusCode.SUCCESS.name)
                } else {
                    val summary = nodes.take(10).joinToString("\n") { "• [${it.className}] \"${it.text}\" (clickable=${it.isClickable})" }
                    ToolResult("accessibility_action", true, "Visible On-Screen Elements (${nodes.size} found):\n$summary", statusCode = ToolExecutionStatusCode.SUCCESS.name, data = mapOf("count" to nodes.size))
                }
            }
            "CLICK" -> {
                if (targetText.isBlank()) {
                    ToolResult("accessibility_action", false, "Target text for CLICK action cannot be blank.", statusCode = ToolExecutionStatusCode.FAILED.name)
                } else {
                    val clicked = service.clickNodeWithText(targetText)
                    ToolResult(
                        "accessibility_action",
                        clicked,
                        if (clicked) "Assisted click executed on UI element containing '$targetText'."
                        else "No clickable element with text '$targetText' was found on screen.",
                        statusCode = if (clicked) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_FOUND.name
                    )
                }
            }
            "LONG_CLICK" -> {
                if (targetText.isBlank()) {
                    ToolResult("accessibility_action", false, "Target text for LONG_CLICK action cannot be blank.", statusCode = ToolExecutionStatusCode.FAILED.name)
                } else {
                    val clicked = service.longClickNodeWithText(targetText)
                    ToolResult(
                        "accessibility_action",
                        clicked,
                        if (clicked) "Assisted long click executed on UI element containing '$targetText'."
                        else "No long-clickable element with text '$targetText' was found on screen.",
                        statusCode = if (clicked) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_FOUND.name
                    )
                }
            }
            "SCROLL" -> {
                val dir = if (targetText.contains("UP", ignoreCase = true) || targetText.contains("BACK", ignoreCase = true)) "UP" else "DOWN"
                val scrolled = service.scrollScreen(dir)
                ToolResult(
                    toolName = "accessibility_action",
                    isSuccess = scrolled,
                    output = if (scrolled) "Scrolled screen $dir." else "No scrollable container found on current screen.",
                    statusCode = if (scrolled) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_AVAILABLE.name
                )
            }
            "NAVIGATE" -> {
                val success = service.performSystemAction(targetText)
                ToolResult(
                    toolName = "accessibility_action",
                    isSuccess = success,
                    output = if (success) "Executed system navigation action: $targetText" else "Could not perform system navigation '$targetText'.",
                    statusCode = if (success) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name
                )
            }
            "FOCUS" -> {
                if (targetText.isBlank()) {
                    ToolResult("accessibility_action", false, "Target text for FOCUS cannot be blank.", statusCode = ToolExecutionStatusCode.FAILED.name)
                } else {
                    val focused = service.focusNode(targetText)
                    ToolResult(
                        "accessibility_action",
                        focused,
                        if (focused) "Accessibility focus placed on element '$targetText'."
                        else "No focusable element with text '$targetText' found.",
                        statusCode = if (focused) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_FOUND.name
                    )
                }
            }
            "SET_TEXT" -> {
                val textToEnter = if (inputText.isNotBlank()) inputText else targetText
                val searchTarget = if (inputText.isNotBlank()) targetText else ""
                val entered = service.setTextOnNode(searchTarget, textToEnter)
                ToolResult(
                    "accessibility_action",
                    entered,
                    if (entered) "Input text \"$textToEnter\" entered into text field."
                    else "No editable text field located on current screen.",
                    statusCode = if (entered) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_FOUND.name
                )
            }
            "FIND" -> {
                val nodes = service.inspectCurrentScreen().filter {
                    it.text.contains(targetText, ignoreCase = true)
                }
                if (nodes.isEmpty()) {
                    ToolResult("accessibility_action", true, "No elements matching '$targetText' found on screen.", statusCode = ToolExecutionStatusCode.NOT_FOUND.name)
                } else {
                    val summary = nodes.joinToString("\n") { "• [${it.className}] \"${it.text}\" (clickable=${it.isClickable}, bounds=${it.bounds})" }
                    ToolResult("accessibility_action", true, "Found ${nodes.size} matching element(s):\n$summary", statusCode = ToolExecutionStatusCode.SUCCESS.name)
                }
            }
            else -> ToolResult("accessibility_action", false, "Unknown accessibility action: $action", statusCode = ToolExecutionStatusCode.FAILED.name)
        }
    }

    private fun executeGetBatteryStatus(): ToolResult {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Float = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()) else -1f

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Power"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery Discharging"
        }

        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0

        val output = buildString {
            append("Battery Status: ${batteryPct.toInt()}%\n")
            append("Power Source: $plugType\n")
            append("Charging: ${if (isCharging) "Yes" else "No"}\n")
            append("Temperature: ${tempC}°C")
        }

        return ToolResult(
            toolName = "get_battery_status",
            isSuccess = true,
            output = output,
            data = mapOf("percentage" to batteryPct.toInt(), "charging" to isCharging, "temperature" to tempC)
        )
    }

    private fun executeGetDeviceInfo(): ToolResult {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024 * 1024 * 1024.0)
        val availRamGb = memInfo.availMem / (1024 * 1024 * 1024.0)

        val output = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Hardware: ${Build.HARDWARE}\n")
            append("RAM: %.1f GB free of %.1f GB".format(availRamGb, totalRamGb))
        }

        return ToolResult(
            toolName = "get_device_info",
            isSuccess = true,
            output = output
        )
    }

    private fun executeGetNetworkStatus(): ToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)

        val isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val type = when {
            hasWifi -> "Wi-Fi (High speed broadband)"
            hasCellular -> "Cellular Mobile Data"
            isConnected -> "Active Internet"
            else -> "Disconnected / Offline"
        }

        val output = "Network Status: $type | Connected: ${if (isConnected) "Online" else "Offline"}"
        return ToolResult("get_network_status", true, output, mapOf("online" to isConnected, "type" to type))
    }

    private fun executeCalculate(expr: String): ToolResult {
        return try {
            val sanitized = expr.replace(" ", "")
            val result = evalSimpleMath(sanitized)
            ToolResult("calculate", true, "Calculation: $expr = $result", mapOf("result" to result))
        } catch (e: Exception) {
            ToolResult("calculate", false, "Math evaluation error: ${e.message}")
        }
    }

    private fun evalSimpleMath(expression: String): Double {
        var str = expression
        if (str.contains("+")) {
            val parts = str.split("+")
            return parts.sumOf { evalSimpleMath(it) }
        }
        if (str.contains("-") && !str.startsWith("-")) {
            val parts = str.split("-")
            var res = evalSimpleMath(parts[0])
            for (i in 1 until parts.size) {
                res -= evalSimpleMath(parts[i])
            }
            return res
        }
        if (str.contains("*")) {
            val parts = str.split("*")
            var res = 1.0
            for (p in parts) res *= evalSimpleMath(p)
            return res
        }
        if (str.contains("/")) {
            val parts = str.split("/")
            var res = evalSimpleMath(parts[0])
            for (i in 1 until parts.size) {
                val denom = evalSimpleMath(parts[i])
                if (denom == 0.0) throw ArithmeticException("Division by zero")
                res /= denom
            }
            return res
        }
        if (str.contains("%")) {
            val parts = str.split("%")
            val p0 = evalSimpleMath(parts[0])
            val p1 = if (parts.size > 1 && parts[1].isNotBlank()) evalSimpleMath(parts[1]) else 100.0
            return p0 % p1
        }
        return str.toDouble()
    }

    private suspend fun executeSetReminder(title: String, delayMinutes: Long): ToolResult {
        val triggerTime = System.currentTimeMillis() + (delayMinutes * 60 * 1000)
        val id = repository.createReminder(title, "Scheduled via JARVIS Assistant", triggerTime)
        val reminder = com.example.data.local.entity.ReminderItem(
            id = id,
            title = title,
            notes = "Scheduled via JARVIS Assistant",
            triggerTimeMillis = triggerTime
        )
        com.example.receiver.JarvisAlarmScheduler.scheduleReminder(context, reminder)
        return ToolResult(
            "set_reminder",
            true,
            "Reminder successfully scheduled: '$title' in $delayMinutes minute(s). An alarm notification will trigger on time.",
            mapOf("reminderId" to id, "triggerTime" to triggerTime)
        )
    }

    private fun executeCopyToClipboard(text: String): ToolResult {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("JARVIS", text)
        cb?.setPrimaryClip(clip)
        return ToolResult("copy_to_clipboard", true, "Copied to clipboard: ${text.take(60)}...")
    }

    private suspend fun executeSearchWeb(query: String): ToolResult {
        // Section 16: MEMORY + WEB RESEARCH INTEGRATION
        // If query references user's project, enrich with relevant project context from memory
        val enrichedQuery = if (query.contains("project", true) || query.contains("our app", true) || query.contains("my codebase", true)) {
            val projectMems = repository.database.memoryDao().getAllActiveMemoriesList().filter {
                it.category == com.example.ai.memory.MemoryCategory.PROJECT_CONTEXT.code
            }
            if (projectMems.isNotEmpty()) {
                val projKeywords = projectMems.joinToString(" ") { it.key }
                "$query ($projKeywords)"
            } else query
        } else query

        val (success, content) = webSearchService.search(enrichedQuery)
        return ToolResult("search_web", success, content)
    }

    private suspend fun executeReadMemory(query: String): ToolResult {
        if (query.isBlank() || query.contains("preference", true) || query.contains("about me", true) || query.contains("all", true)) {
            val summary = memoryManager.getPreferencesSummary()
            return ToolResult("read_memory", true, summary)
        }
        val scored = memoryManager.searchMemories(query, limit = 5)
        if (scored.isEmpty()) {
            return ToolResult("read_memory", true, "No memories matched query '$query'.")
        }
        val sb = StringBuilder("Recalled Memories (${scored.size}):\n")
        for (m in scored) {
            sb.append("• [${m.memory.category}] ${m.memory.key}: ${m.memory.content}\n")
        }
        return ToolResult("read_memory", true, sb.toString().trim())
    }

    private suspend fun executeWriteMemory(key: String, content: String, category: String, persona: String, memoryType: String = "long-term"): ToolResult {
        val res = memoryManager.saveOrUpdateMemory(
            key = key,
            content = content,
            category = category,
            confidence = 1.0f,
            source = "explicit_command"
        )
        return when (res) {
            is com.example.ai.memory.MemoryOperationResult.Saved -> ToolResult("write_memory", true, "[${res.statusCode}] Stored memory #${res.memoryId} under '${res.key}': ${res.detail}", mapOf("id" to res.memoryId, "status" to res.statusCode))
            is com.example.ai.memory.MemoryOperationResult.Updated -> ToolResult("write_memory", true, "[${res.statusCode}] Updated memory #${res.memoryId} under '${res.key}': ${res.detail}", mapOf("id" to res.memoryId, "status" to res.statusCode))
            is com.example.ai.memory.MemoryOperationResult.Failed -> ToolResult("write_memory", false, "[${res.statusCode}] Failed to store memory: ${res.detail}", errorMessage = res.statusCode)
            else -> ToolResult("write_memory", false, "[${res.statusCode}] ${res.message}", errorMessage = res.statusCode)
        }
    }

    private suspend fun executeDeleteMemory(query: String): ToolResult {
        val res = memoryManager.forgetMemory(query)
        return when (res) {
            is com.example.ai.memory.MemoryOperationResult.Deleted -> ToolResult("delete_memory_by_query", true, "[${res.statusCode}] ${res.detail}", mapOf("status" to res.statusCode))
            is com.example.ai.memory.MemoryOperationResult.NotFound -> ToolResult("delete_memory_by_query", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
            is com.example.ai.memory.MemoryOperationResult.Failed -> ToolResult("delete_memory_by_query", false, "[${res.statusCode}] ${res.detail}", errorMessage = res.statusCode)
            else -> ToolResult("delete_memory_by_query", false, "[${res.statusCode}] ${res.message}", errorMessage = res.statusCode)
        }
    }

    private fun executeControlFlashlight(enabled: Boolean): ToolResult {
        val hasFlash = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        if (!hasFlash) {
            return ToolResult(
                toolName = "control_flashlight",
                isSuccess = false,
                output = "FEATURE_CAMERA_FLASH is not available on this device hardware.",
                statusCode = ToolExecutionStatusCode.NOT_AVAILABLE.name,
                errorMessage = "HARDWARE_UNAVAILABLE",
                data = mapOf("state" to "UNAVAILABLE")
            )
        }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            return ToolResult(
                toolName = "control_flashlight",
                isSuccess = false,
                output = "CameraManager is unavailable on this device.",
                statusCode = ToolExecutionStatusCode.FAILED.name,
                errorMessage = "SERVICE_UNAVAILABLE",
                data = mapOf("state" to "UNAVAILABLE")
            )
        }

        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull()

            if (cameraId != null) {
                cm.setTorchMode(cameraId, enabled)
                ToolResult(
                    toolName = "control_flashlight",
                    isSuccess = true,
                    output = if (enabled) "Flashlight turned ON." else "Flashlight turned OFF.",
                    statusCode = ToolExecutionStatusCode.SUCCESS.name,
                    data = mapOf("state" to if (enabled) "ON" else "OFF", "cameraId" to cameraId)
                )
            } else {
                ToolResult(
                    toolName = "control_flashlight",
                    isSuccess = false,
                    output = "No camera with flashlight capability found.",
                    statusCode = ToolExecutionStatusCode.NOT_AVAILABLE.name,
                    data = mapOf("state" to "UNAVAILABLE")
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = "control_flashlight",
                isSuccess = false,
                output = "Flashlight control error: ${e.message}",
                statusCode = ToolExecutionStatusCode.FAILED.name,
                errorMessage = e.message
            )
        }
    }

    private fun executeAdjustVolume(direction: String): ToolResult {
        val (ok, msg) = mediaHelper.adjustVolume(direction, "MEDIA")
        return ToolResult(
            toolName = "adjust_volume",
            isSuccess = ok,
            output = msg,
            statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name
        )
    }

    private fun executeSetVolume(
        stream: String,
        percent: Int?,
        direction: String?,
        mute: Boolean?
    ): ToolResult {
        if (mute != null) {
            val (ok, msg) = mediaHelper.setMute(mute, stream)
            return ToolResult(
                toolName = "set_volume",
                isSuccess = ok,
                output = msg,
                statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name,
                data = mapOf("stream" to stream, "mute" to mute)
            )
        }
        if (percent != null) {
            val (ok, msg) = mediaHelper.setVolumePercent(percent, stream)
            return ToolResult(
                toolName = "set_volume",
                isSuccess = ok,
                output = msg,
                statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name,
                data = mapOf("percent" to percent, "stream" to stream)
            )
        }
        val dir = direction ?: "UP"
        val (ok, msg) = mediaHelper.adjustVolume(dir, stream)
        return ToolResult(
            toolName = "set_volume",
            isSuccess = ok,
            output = msg,
            statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name,
            data = mapOf("direction" to dir, "stream" to stream)
        )
    }

    private fun executeOpenSettings(category: String): ToolResult {
        val intentAction = when (category.lowercase().trim()) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi", "wi-fi", "internet", "network" -> Settings.ACTION_WIFI_SETTINGS
            "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
            "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps", "application", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "date", "time", "clock" -> Settings.ACTION_DATE_SETTINGS
            "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "developer", "development" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(intentAction).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(
                toolName = "open_settings",
                isSuccess = true,
                output = "Opened Android $category settings.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name,
                data = mapOf("category" to category, "action" to intentAction)
            )
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                ToolResult(
                    toolName = "open_settings",
                    isSuccess = true,
                    output = "Opened Android general settings (specific screen unavailable: ${e.message}).",
                    statusCode = ToolExecutionStatusCode.SUCCESS.name
                )
            } catch (ex: Exception) {
                ToolResult(
                    toolName = "open_settings",
                    isSuccess = false,
                    output = "Failed to launch Settings: ${ex.message}",
                    statusCode = ToolExecutionStatusCode.FAILED.name,
                    errorMessage = ex.message
                )
            }
        }
    }

    private fun executeOpenAppSettings(appName: String): ToolResult {
        val trimmed = appName.trim()
        val pm = context.packageManager
        var targetPkg: String? = null

        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            targetPkg = trimmed
        } else {
            val installedApps = pm.getInstalledApplications(0)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.equals(trimmed, ignoreCase = true) || label.lowercase().contains(trimmed.lowercase())) {
                    targetPkg = app.packageName
                    break
                }
            }
        }

        if (targetPkg == null) {
            return ToolResult(
                toolName = "open_app_settings",
                isSuccess = false,
                output = "APP_NOT_FOUND: Could not find application '$trimmed' to open its settings.",
                statusCode = ToolExecutionStatusCode.NOT_FOUND.name,
                errorMessage = "APP_NOT_FOUND"
            )
        }

        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$targetPkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(
                toolName = "open_app_settings",
                isSuccess = true,
                output = "Opened App Info settings for $targetPkg.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name,
                data = mapOf("package" to targetPkg)
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "open_app_settings",
                isSuccess = false,
                output = "Failed to open app settings for $targetPkg: ${e.message}",
                statusCode = ToolExecutionStatusCode.FAILED.name,
                errorMessage = e.message
            )
        }
    }

    private fun executeIsAppInstalled(appName: String): ToolResult {
        val trimmed = appName.trim()
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(0)
        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString()
            if (app.packageName.equals(trimmed, ignoreCase = true) ||
                label.equals(trimmed, ignoreCase = true) ||
                label.lowercase().contains(trimmed.lowercase())
            ) {
                return ToolResult(
                    toolName = "is_app_installed",
                    isSuccess = true,
                    output = "Application is installed: $label (${app.packageName}) [Enabled: ${app.enabled}].",
                    statusCode = ToolExecutionStatusCode.SUCCESS.name,
                    data = mapOf("installed" to true, "package" to app.packageName, "name" to label, "enabled" to app.enabled)
                )
            }
        }

        return ToolResult(
            toolName = "is_app_installed",
            isSuccess = true,
            output = "Application '$trimmed' is NOT installed on this device.",
            statusCode = ToolExecutionStatusCode.SUCCESS.name,
            data = mapOf("installed" to false, "query" to trimmed)
        )
    }

    private fun executeReadClipboard(): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        return if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context).toString()
            ToolResult(
                toolName = "read_clipboard",
                isSuccess = true,
                output = if (text.isNotBlank()) "Clipboard content:\n$text" else "Clipboard is empty.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name,
                data = mapOf("content" to text)
            )
        } else {
            ToolResult(
                toolName = "read_clipboard",
                isSuccess = true,
                output = "Clipboard is empty.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name,
                data = mapOf("content" to "")
            )
        }
    }

    private fun executeClearClipboard(): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm?.clearPrimaryClip()
            } else {
                cm?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            ToolResult(
                toolName = "clear_clipboard",
                isSuccess = true,
                output = "Clipboard successfully cleared.",
                statusCode = ToolExecutionStatusCode.SUCCESS.name
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "clear_clipboard",
                isSuccess = false,
                output = "Failed to clear clipboard: ${e.message}",
                statusCode = ToolExecutionStatusCode.FAILED.name,
                errorMessage = e.message
            )
        }
    }

    private fun executeOpenFile(fileName: String): ToolResult {
        val (ok, msg) = documentHelper.openFileWithDefaultViewer(fileName)
        return ToolResult(
            toolName = "open_file",
            isSuccess = ok,
            output = msg,
            statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name
        )
    }

    private fun executeGetStorageInfo(): ToolResult {
        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalInternalGb = (totalBlocks * blockSize) / (1024 * 1024 * 1024.0)
        val freeInternalGb = (availableBlocks * blockSize) / (1024 * 1024 * 1024.0)
        val usedInternalGb = totalInternalGb - freeInternalGb
        val pctUsed = if (totalInternalGb > 0) ((usedInternalGb / totalInternalGb) * 100).toInt() else 0

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val totalRamGb = (memInfo.totalMem) / (1024 * 1024 * 1024.0)
        val availRamGb = (memInfo.availMem) / (1024 * 1024 * 1024.0)

        val output = buildString {
            append("Storage & Memory Telemetry:\n")
            append("• Internal Storage: %.1f GB free of %.1f GB (%d%% used)\n".format(freeInternalGb, totalInternalGb, pctUsed))
            append("• RAM: %.1f GB available of %.1f GB".format(availRamGb, totalRamGb))
        }

        return ToolResult(
            toolName = "get_storage_info",
            isSuccess = true,
            output = output,
            statusCode = ToolExecutionStatusCode.SUCCESS.name,
            data = mapOf(
                "totalStorageGb" to totalInternalGb,
                "freeStorageGb" to freeInternalGb,
                "percentUsed" to pctUsed,
                "totalRamGb" to totalRamGb,
                "availRamGb" to availRamGb
            )
        )
    }

    private fun executeDismissNotification(query: String): ToolResult {
        val service = JarvisNotificationListenerService.instance
        if (service == null) {
            return ToolResult(
                toolName = "dismiss_notification",
                isSuccess = false,
                output = "NOTIFICATION_ACCESS_REQUIRED: Notification Listener is not active.",
                statusCode = ToolExecutionStatusCode.PERMISSION_REQUIRED.name,
                errorMessage = "NOTIFICATION_ACCESS_REQUIRED"
            )
        }
        val ok = if (query.equals("ALL", ignoreCase = true)) {
            service.dismissAllNotifications()
        } else {
            service.dismissNotification(query)
        }
        val msg = if (ok) {
            if (query.equals("ALL", ignoreCase = true)) "All active notifications dismissed."
            else "Dismissed notification matching '$query'."
        } else {
            if (query.equals("ALL", ignoreCase = true)) "Could not dismiss notifications."
            else "Notification matching '$query' not found."
        }
        return ToolResult(
            toolName = "dismiss_notification",
            isSuccess = ok,
            output = msg,
            statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.NOT_FOUND.name
        )
    }

    private fun executeReplyNotification(query: String, message: String): ToolResult {
        val service = JarvisNotificationListenerService.instance
        if (service == null) {
            return ToolResult(
                toolName = "reply_notification",
                isSuccess = false,
                output = "NOTIFICATION_ACCESS_REQUIRED: Notification Listener is not active.",
                statusCode = ToolExecutionStatusCode.PERMISSION_REQUIRED.name,
                errorMessage = "NOTIFICATION_ACCESS_REQUIRED"
            )
        }
        val (ok, msg) = service.replyToNotification(query, message)
        return ToolResult(
            toolName = "reply_notification",
            isSuccess = ok,
            output = msg,
            statusCode = if (ok) ToolExecutionStatusCode.SUCCESS.name else ToolExecutionStatusCode.FAILED.name
        )
    }

    private suspend fun executeCreateWebProject(name: String, desc: String, type: String): ToolResult {
        val resolvedType = if (type.contains("REACT", ignoreCase = true) ||
            desc.contains("react", ignoreCase = true) ||
            desc.contains("portfolio", ignoreCase = true)
        ) {
            "REACT_SPA"
        } else {
            type
        }

        val projectItem = WebProjectItem(
            name = name,
            description = desc,
            projectType = resolvedType,
            projectPath = ""
        )
        val id = repository.saveProject(projectItem)
        val folder = webProjectManager.createProject(id, name, desc, resolvedType)
        val files = webProjectManager.getProjectFiles(id)
        repository.updateProject(projectItem.copy(id = id, projectPath = folder.absolutePath, filesCount = files.size))

        val buildStatus = webProjectManager.getBuildStatus(id)

        val summary = if (resolvedType == "REACT_SPA") {
            "Vite + React project '$name' (#$id) initialized successfully on device filesystem with modular structure (package.json, vite.config.js, index.html, src/App.jsx, src/components/, public/). Files generated: ${files.size}. Status: $buildStatus. Ready for preview in Web Studio or export to ZIP."
        } else {
            "Web project '$name' (#$id) initialized successfully with responsive files (index.html, style.css, app.js). You can preview and edit it in the Web Studio tab."
        }

        return ToolResult(
            "create_web_project",
            true,
            summary,
            mapOf("projectId" to id, "type" to resolvedType, "filesCount" to files.size, "buildStatus" to buildStatus)
        )
    }

    private fun executeEditWebFile(projectId: Long, fileName: String, content: String): ToolResult {
        val success = webProjectManager.writeFile(projectId, fileName, content)
        return ToolResult(
            "edit_web_file",
            success,
            if (success) "Updated file '$fileName' in project #$projectId." else "Failed to write file '$fileName'."
        )
    }

    private fun executeExportWebProjectZip(projectId: Long): ToolResult {
        val zipFile = webProjectManager.exportProjectAsZip(projectId)
        return if (zipFile != null && zipFile.exists()) {
            ToolResult(
                "export_web_project_zip",
                true,
                "Exported project #$projectId to ZIP archive (${zipFile.length()} bytes): ${zipFile.absolutePath}",
                mapOf("projectId" to projectId, "zipPath" to zipFile.absolutePath, "sizeBytes" to zipFile.length())
            )
        } else {
            ToolResult(
                "export_web_project_zip",
                false,
                "Failed to export project #$projectId as ZIP."
            )
        }
    }

    private suspend fun executeListWebProjects(): ToolResult {
        val projects = repository.getAllProjectsList()
        val text = if (projects.isEmpty()) {
            "No web projects currently created in JARVIS Web Studio."
        } else {
            "Active Web Projects (${projects.size}):\n" + projects.joinToString("\n") { p ->
                "• [#${p.id}] ${p.name} (${p.projectType}) - ${p.filesCount} files. Description: ${p.description}"
            }
        }
        return ToolResult(
            "list_web_projects",
            true,
            text,
            mapOf("count" to projects.size, "projects" to projects.map { mapOf("id" to it.id, "name" to it.name) })
        )
    }

    private fun executeReadWebFile(projectId: Long, fileName: String): ToolResult {
        val content = webProjectManager.readFile(projectId, fileName)
        return if (content != null) {
            ToolResult(
                "read_web_file",
                true,
                "File '$fileName' in project #$projectId:\n\n$content",
                mapOf("projectId" to projectId, "fileName" to fileName, "length" to content.length)
            )
        } else {
            ToolResult(
                "read_web_file",
                false,
                "File '$fileName' does not exist in project #$projectId."
            )
        }
    }

    private suspend fun executeModifyWebProject(projectId: Long, action: String, details: String): ToolResult {
        val project = repository.getProjectById(projectId)
            ?: return ToolResult("modify_web_project", false, "Project #$projectId not found.")

        val success = when (action.uppercase()) {
            "ADD_JARVIS_KNOWLEDGE", "JARVIS_KNOWLEDGE" -> {
                webProjectManager.addJarvisKnowledge(projectId)
            }
            "ADD_CONTACT", "CONTACT" -> {
                webProjectManager.addContactSection(projectId)
            }
            "ADD_ANIMATIONS", "ANIMATIONS" -> {
                webProjectManager.addAnimations(projectId)
            }
            "ADD_USER_INFO", "USER_INFO" -> {
                val parts = details.split("|").map { it.trim() }
                val name = parts.getOrNull(0) ?: "Project Architect"
                val title = parts.getOrNull(1) ?: "Full-Stack Engineer"
                val bio = parts.getOrNull(2) ?: "Building autonomous systems and intuitive user interfaces."
                val skills = parts.drop(3).ifEmpty { listOf("Kotlin", "Jetpack Compose", "React", "AI Tooling") }
                webProjectManager.addUserInformation(projectId, name, title, bio, skills)
            }
            else -> {
                webProjectManager.addJarvisKnowledge(projectId)
            }
        }

        val updatedFiles = webProjectManager.getProjectFiles(projectId)
        repository.updateProject(project.copy(filesCount = updatedFiles.size))

        return ToolResult(
            "modify_web_project",
            success,
            if (success) "Successfully updated project #${projectId} ('${project.name}') with '$action'. Changes are immediately live in Web Studio preview."
            else "Failed to apply '$action' to project #$projectId."
        )
    }

    private fun executeCaptureScreen(): ToolResult {
        return ToolResult(
            "capture_screen",
            true,
            "Initiated screen analysis request. Please grant screen capture permission if prompted by the system.",
            mapOf("action" to "REQUEST_PROJECTION_CONSENT")
        )
    }

    private suspend fun executeUniversalAppAction(
        appName: String?,
        action: String,
        targetText: String?,
        inputText: String?,
        scrollDirection: String?
    ): ToolResult {
        val steps = mutableListOf<AutomationStep>()
        var stepIdx = 1
        if (!appName.isNullOrBlank()) {
            steps.add(
                AutomationStep(
                    stepIndex = stepIdx++,
                    actionType = "LAUNCH_APP",
                    appName = appName
                )
            )
        }
        steps.add(
            AutomationStep(
                stepIndex = stepIdx,
                actionType = action.uppercase(),
                appName = appName,
                targetQuery = if (!targetText.isNullOrBlank()) NodeQuery(text = targetText) else null,
                textValue = inputText ?: targetText,
                scrollDirection = scrollDirection
            )
        )
        val res = automationEngine.executeSteps(steps)
        return ToolResult(
            toolName = "universal_app_action",
            isSuccess = res.isSuccess,
            output = "[${res.finalState}] ${res.summary}",
            statusCode = res.finalState,
            data = mapOf("state" to res.finalState, "stepsCount" to res.stepReports.size)
        )
    }

    private suspend fun executeMultiStepWorkflow(
        appName: String,
        stepsJson: String,
        requiresConfirmation: Boolean,
        confirmationPrompt: String?
    ): ToolResult {
        val parsedSteps = mutableListOf<AutomationStep>()
        try {
            val array = org.json.JSONArray(stepsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val action = obj.optString("actionType", obj.optString("action", "CLICK")).uppercase()
                val target = obj.optString("target", obj.optString("targetText", ""))
                val textVal = obj.optString("textValue", obj.optString("inputText", ""))
                val confirm = obj.optBoolean("requiresConfirmation", false)
                val prompt = obj.optString("confirmationPrompt", "")
                parsedSteps.add(
                    AutomationStep(
                        stepIndex = i + 1,
                        actionType = action,
                        appName = appName,
                        targetQuery = if (target.isNotBlank()) NodeQuery(text = target) else null,
                        textValue = textVal.ifBlank { null },
                        requiresConfirmation = confirm || (requiresConfirmation && i == array.length() - 1),
                        confirmationPrompt = prompt.ifBlank { confirmationPrompt }
                    )
                )
            }
        } catch (e: Exception) {
            return ToolResult(
                toolName = "run_multi_step_workflow",
                isSuccess = false,
                output = "Failed to parse workflow JSON: ${e.message}",
                errorMessage = e.message
            )
        }

        if (parsedSteps.isEmpty()) {
            return ToolResult(
                toolName = "run_multi_step_workflow",
                isSuccess = false,
                output = "No valid steps provided in workflow.",
                errorMessage = "EMPTY_WORKFLOW"
            )
        }

        val planResult = automationEngine.executeSteps(parsedSteps)
        return ToolResult(
            toolName = "run_multi_step_workflow",
            isSuccess = planResult.isSuccess,
            output = "[${planResult.finalState}] ${planResult.summary}",
            statusCode = planResult.finalState,
            data = mapOf(
                "state" to planResult.finalState,
                "stepsExecuted" to planResult.stepReports.size,
                "hasPendingConfirmation" to (planResult.pendingConfirmation != null)
            )
        )
    }

    private fun executeInspectScreenUi(): ToolResult {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            return ToolResult(
                toolName = "inspect_screen_ui",
                isSuccess = false,
                output = "Accessibility Service is not enabled. Please enable JARVIS Accessibility in Android Settings.",
                errorMessage = "ACCESSIBILITY_DISABLED"
            )
        }
        val nodes = service.inspectCurrentScreen()
        if (nodes.isEmpty()) {
            return ToolResult(
                toolName = "inspect_screen_ui",
                isSuccess = true,
                output = "Active window is currently blank or protected (e.g. secure screen).",
                data = mapOf("count" to 0)
            )
        }
        val summary = nodes.take(40).joinToString("\n") {
            "• [${it.className}] \"${it.text}\" (clickable=${it.isClickable}, editable=${it.isEditable}, id=${it.viewId ?: "none"}, bounds=${it.bounds})"
        }
        return ToolResult(
            toolName = "inspect_screen_ui",
            isSuccess = true,
            output = "Visible Screen Elements (${nodes.size} total):\n$summary",
            data = mapOf("count" to nodes.size)
        )
    }

    private suspend fun executeConfirmPendingAction(confirmed: Boolean): ToolResult {
        if (!confirmed) {
            val cancelMsg = automationEngine.cancelPendingAction()
            return ToolResult(
                toolName = "confirm_pending_action",
                isSuccess = true,
                output = cancelMsg,
                statusCode = "CANCELLED"
            )
        }
        val nextStep = automationEngine.confirmPendingAction()
        if (nextStep == null) {
            return ToolResult(
                toolName = "confirm_pending_action",
                isSuccess = true,
                output = "No pending action was waiting for confirmation.",
                statusCode = "NO_PENDING_ACTION"
            )
        }
        val res = automationEngine.executeSteps(listOf(nextStep), userAlreadyConfirmed = true)
        return ToolResult(
            toolName = "confirm_pending_action",
            isSuccess = res.isSuccess,
            output = "[${res.finalState}] Confirmed action completed: ${res.summary}",
            statusCode = res.finalState
        )
    }

    private suspend fun executeSaveWorkflow(name: String, trigger: String, steps: String, desc: String): ToolResult {
        val id = workflowStore.saveWorkflow(name, trigger, steps, desc)
        return ToolResult(
            toolName = "save_automation_workflow",
            isSuccess = true,
            output = "Workflow '$name' saved successfully with ID $id. Trigger phrase: '$trigger'.",
            data = mapOf("id" to id)
        )
    }

    private suspend fun executeListWorkflows(): ToolResult {
        val all = repository.database.automationDao().getEnabledRules()
            .filter { it.trigger == "USER_COMMAND" }
        val text = if (all.isEmpty()) {
            "No custom learned workflows currently saved."
        } else {
            "Saved Workflows (${all.size}):\n" + all.joinToString("\n") {
                "• [ID ${it.id}] \"${it.name}\" - Trigger: \"${it.schedule}\""
            }
        }
        return ToolResult(
            toolName = "list_saved_workflows",
            isSuccess = true,
            output = text,
            data = mapOf("count" to all.size)
        )
    }
}
