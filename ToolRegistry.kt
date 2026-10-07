package com.example.tools

import org.json.JSONArray
import org.json.JSONObject

object ToolRegistry {
    val allTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "launch_app",
            description = "Launch an installed Android application by name (e.g. YouTube, Maps, Camera, WhatsApp, Settings, Chrome, Calculator)",
            parameters = listOf(
                ToolParameter("appName", "STRING", "The display name or type of the application to launch", true)
            )
        ),
        ToolDefinition(
            name = "open_url",
            description = "Open a valid web URL in the system default web browser",
            parameters = listOf(
                ToolParameter("url", "STRING", "The web address (http/https) to open", true)
            )
        ),
        ToolDefinition(
            name = "get_battery_status",
            description = "Get real-time device battery level, charging status, power source, and health",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_device_info",
            description = "Get device hardware profile including Android version, model, manufacturer, and available RAM",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "get_network_status",
            description = "Check current internet connection status, WiFi state, and cellular connectivity",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "calculate",
            description = "Compute a mathematical expression (e.g. '25 * 40 + 150', 'sqrt(144)', '15% of 850')",
            parameters = listOf(
                ToolParameter("expression", "STRING", "The math expression to evaluate", true)
            )
        ),
        ToolDefinition(
            name = "set_reminder",
            description = "Schedule a real reminder and alarm notification with title and delay in minutes",
            parameters = listOf(
                ToolParameter("title", "STRING", "What to be reminded of", true),
                ToolParameter("delayMinutes", "INTEGER", "Number of minutes from now to trigger the reminder", true)
            )
        ),
        ToolDefinition(
            name = "copy_to_clipboard",
            description = "Copy text or code snippet to the Android system clipboard",
            parameters = listOf(
                ToolParameter("text", "STRING", "The text to copy to clipboard", true)
            )
        ),
        ToolDefinition(
            name = "share_content",
            description = "Share text, links, or notes with other Android applications via the system share sheet",
            parameters = listOf(
                ToolParameter("text", "STRING", "The content to share", true),
                ToolParameter("title", "STRING", "Optional subject or share sheet title", false)
            )
        ),
        ToolDefinition(
            name = "dial_phone_number",
            description = "Open the phone dialer with a specified contact number or initiate a call via system dialer",
            parameters = listOf(
                ToolParameter("phoneNumber", "STRING", "The phone number to dial", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "lookup_contact",
            description = "Search device contacts by name to retrieve their phone number",
            parameters = listOf(
                ToolParameter("name", "STRING", "Contact name to search", true)
            ),
            requiredPermission = android.Manifest.permission.READ_CONTACTS
        ),
        ToolDefinition(
            name = "draft_message",
            description = "Open the messaging composer for SMS or WhatsApp with recipient and body. State 'The message composer is ready. Please press Send.' Do not claim sent.",
            parameters = listOf(
                ToolParameter("phoneNumber", "STRING", "The recipient phone number or contact name", true),
                ToolParameter("message", "STRING", "The message body to draft", true),
                ToolParameter("channel", "STRING", "Channel: 'SMS' or 'WHATSAPP'", false)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "capture_screen",
            description = "Capture the device screen frame for AI vision analysis and OCR understanding (requires user consent)",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "calendar_operation",
            description = "Perform real calendar operations: read upcoming events or create a new calendar entry",
            parameters = listOf(
                ToolParameter("action", "STRING", "'READ' to get upcoming events, or 'CREATE' to schedule an event", true),
                ToolParameter("title", "STRING", "Event title (required for CREATE)", false),
                ToolParameter("delayHours", "INTEGER", "Start time in hours from now (for CREATE)", false),
                ToolParameter("durationHours", "INTEGER", "Event duration in hours (for CREATE)", false)
            ),
            requiredPermission = android.Manifest.permission.READ_CALENDAR
        ),
        ToolDefinition(
            name = "get_active_notifications",
            description = "Read currently active device notifications using NotificationListenerService (requires permission)",
            parameters = emptyList(),
            requiredPermission = "SPECIAL_NOTIFICATION_LISTENER"
        ),
        ToolDefinition(
            name = "accessibility_action",
            description = "Inspect on-screen elements or perform assistive actions (CLICK, LONG_CLICK, SCROLL, NAVIGATE, FOCUS, SET_TEXT, FIND) via AccessibilityService",
            parameters = listOf(
                ToolParameter("action", "STRING", "'INSPECT', 'CLICK', 'LONG_CLICK', 'SCROLL', 'NAVIGATE' (BACK/HOME/RECENTS/NOTIFICATIONS), 'FOCUS', 'SET_TEXT', or 'FIND'", true),
                ToolParameter("targetText", "STRING", "Text to match for CLICK/FOCUS/SET_TEXT, direction for SCROLL (UP/DOWN), or target for NAVIGATE", false),
                ToolParameter("inputText", "STRING", "Text content to enter into field when action is SET_TEXT", false)
            ),
            requiredPermission = "SPECIAL_ACCESSIBILITY"
        ),
        ToolDefinition(
            name = "search_web",
            description = "Search the web for up-to-date information, news, documentation, or facts",
            parameters = listOf(
                ToolParameter("query", "STRING", "The search query", true)
            )
        ),
        ToolDefinition(
            name = "read_memory",
            description = "Search or retrieve persistent long-term memories saved about the user",
            parameters = listOf(
                ToolParameter("query", "STRING", "Keywords or topic to search for, or empty string to read all recent memories", false)
            )
        ),
        ToolDefinition(
            name = "write_memory",
            description = "Store a permanent memory, user preference, instruction, or key fact into the database",
            parameters = listOf(
                ToolParameter("key", "STRING", "A short identifier or title for the memory", true),
                ToolParameter("content", "STRING", "The detailed information or preference to remember", true),
                ToolParameter("category", "STRING", "Category: general, preference, task, fact", false)
            )
        ),
        ToolDefinition(
            name = "delete_memory_by_query",
            description = "Delete/forget specific memories matching a query from the database",
            parameters = listOf(
                ToolParameter("query", "STRING", "Keyword or identifier of the memory to remove", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "control_flashlight",
            description = "Turn the phone camera flashlight/torch on or off",
            parameters = listOf(
                ToolParameter("enabled", "BOOLEAN", "True to turn on, false to turn off", true)
            )
        ),
        ToolDefinition(
            name = "adjust_volume",
            description = "Adjust the Android media volume up or down",
            parameters = listOf(
                ToolParameter("direction", "STRING", "Direction: 'UP' or 'DOWN'", true)
            )
        ),
        ToolDefinition(
            name = "list_web_projects",
            description = "List all existing web projects generated on device with IDs, names, types, and file counts",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "read_web_file",
            description = "Read code content of a file in an existing web project",
            parameters = listOf(
                ToolParameter("projectId", "INTEGER", "ID of the web project", true),
                ToolParameter("fileName", "STRING", "File name (e.g. index.html, style.css, app.js)", true)
            )
        ),
        ToolDefinition(
            name = "modify_web_project",
            description = "Incrementally modify or upgrade a web project (e.g. add Jarvis knowledge showcase, add contact page, add animations, make responsive, add user info, or append custom section)",
            parameters = listOf(
                ToolParameter("projectId", "INTEGER", "ID of the web project to modify", true),
                ToolParameter("action", "STRING", "Action: 'ADD_JARVIS_KNOWLEDGE', 'ADD_CONTACT', 'ADD_ANIMATIONS', 'ADD_USER_INFO', or 'CUSTOM_SECTION'", true),
                ToolParameter("details", "STRING", "Optional additional details, name, or content", false)
            )
        ),
        ToolDefinition(
            name = "create_web_project",
            description = "Create a real web software project with HTML, CSS, JavaScript or React files on the device filesystem",
            parameters = listOf(
                ToolParameter("projectName", "STRING", "Name of the project", true),
                ToolParameter("description", "STRING", "Description and requirements of what the website does", true),
                ToolParameter("type", "STRING", "Project type: 'HTML_CSS_JS' or 'REACT_SPA'", false)
            )
        ),
        ToolDefinition(
            name = "edit_web_file",
            description = "Write or edit a file in an existing web project",
            parameters = listOf(
                ToolParameter("projectId", "INTEGER", "ID of the web project", true),
                ToolParameter("fileName", "STRING", "File name (e.g. index.html, src/App.jsx, style.css)", true),
                ToolParameter("content", "STRING", "Full code content to write to the file", true)
            )
        ),
        ToolDefinition(
            name = "export_web_project_zip",
            description = "Package and export a web project into a standard downloadable/shareable ZIP archive",
            parameters = listOf(
                ToolParameter("projectId", "INTEGER", "ID of the web project to export", true)
            )
        ),
        // SMS Management
        ToolDefinition(
            name = "send_sms",
            description = "Directly send an SMS text message using Android SmsManager (requires SEND_SMS permission)",
            parameters = listOf(
                ToolParameter("recipients", "STRING", "Target phone number(s), comma or semicolon separated", true),
                ToolParameter("message", "STRING", "The text message content to send", true)
            ),
            requiresConfirmation = true,
            requiredPermission = android.Manifest.permission.SEND_SMS
        ),
        ToolDefinition(
            name = "prepare_sms",
            description = "Prepare an SMS draft in the Android system messaging composer",
            parameters = listOf(
                ToolParameter("recipients", "STRING", "Recipient phone number(s)", true),
                ToolParameter("message", "STRING", "The text message content to draft", true)
            )
        ),
        ToolDefinition(
            name = "get_sms_capability",
            description = "Check device cellular telephony SMS hardware capability, SIM card readiness, and permissions",
            parameters = emptyList()
        ),
        // WhatsApp Official Cloud API
        ToolDefinition(
            name = "send_whatsapp_cloud_message",
            description = "Send a WhatsApp message via official Meta WhatsApp Cloud API to a recipient phone number (requires country code)",
            parameters = listOf(
                ToolParameter("recipientPhone", "STRING", "Phone number with country code (e.g. +919876543210)", true),
                ToolParameter("message", "STRING", "The message text to deliver", true)
            )
        ),
        ToolDefinition(
            name = "check_whatsapp_cloud_status",
            description = "Check official WhatsApp Cloud API connection status and automation mode",
            parameters = emptyList()
        ),
        // YouTube Automation Tools
        ToolDefinition(
            name = "play_youtube",
            description = "Search and automatically open/play a song or video on YouTube",
            parameters = listOf(
                ToolParameter("query", "STRING", "Song title, artist, or video query (e.g. Kesariya, Arijit Singh, Believer)", true)
            )
        ),
        ToolDefinition(
            name = "search_youtube",
            description = "Search YouTube for videos without auto-playing, displaying results or opening search page",
            parameters = listOf(
                ToolParameter("query", "STRING", "Search terms to look up on YouTube", true)
            )
        ),
        ToolDefinition(
            name = "control_youtube",
            description = "Control YouTube video playback (PAUSE, RESUME, STOP, NEXT, PREVIOUS, MUTE, UNMUTE, VOLUME_UP, VOLUME_DOWN, OPEN)",
            parameters = listOf(
                ToolParameter("command", "STRING", "Control action: 'PAUSE', 'RESUME', 'STOP', 'NEXT', 'PREVIOUS', 'MUTE', 'UNMUTE', 'VOLUME_UP', 'VOLUME_DOWN', 'OPEN'", true)
            )
        ),
        // Bluetooth
        ToolDefinition(
            name = "get_bluetooth_status",
            description = "Check real-time device Bluetooth hardware status, enabled state, and connected permissions",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "list_bluetooth_devices",
            description = "List all paired and bonded Bluetooth devices with MAC addresses and types",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "start_bluetooth_discovery",
            description = "Initiate nearby Bluetooth device discovery",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "open_bluetooth_settings",
            description = "Open Android system Bluetooth settings screen",
            parameters = emptyList()
        ),
        // Location & Geofencing
        ToolDefinition(
            name = "get_current_location",
            description = "Get current GPS coordinates (latitude, longitude, accuracy) using FusedLocationProviderClient",
            parameters = emptyList(),
            requiredPermission = android.Manifest.permission.ACCESS_FINE_LOCATION
        ),
        ToolDefinition(
            name = "create_geofence",
            description = "Create and register a geographic boundary with enter/exit triggers in Room and GeofencingClient",
            parameters = listOf(
                ToolParameter("requestId", "STRING", "Unique ID for the geofence", true),
                ToolParameter("name", "STRING", "Descriptive name (e.g. Home, Office)", true),
                ToolParameter("latitude", "STRING", "Latitude coordinate", true),
                ToolParameter("longitude", "STRING", "Longitude coordinate", true),
                ToolParameter("radiusMeters", "STRING", "Radius in meters (default 150)", false),
                ToolParameter("actionPayload", "STRING", "Speech or trigger text when entered/exited", false)
            ),
            requiredPermission = android.Manifest.permission.ACCESS_FINE_LOCATION
        ),
        ToolDefinition(
            name = "delete_geofence",
            description = "Remove a registered geofence by its ID",
            parameters = listOf(
                ToolParameter("requestId", "STRING", "ID of the geofence to delete", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "list_geofences",
            description = "List all active persistent geofences stored in the local database",
            parameters = emptyList()
        ),
        // Documents & Files
        ToolDefinition(
            name = "list_documents",
            description = "List files and documents stored in the JARVIS app documents repository",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "save_document",
            description = "Save or update a text document in JARVIS documents storage",
            parameters = listOf(
                ToolParameter("fileName", "STRING", "File name (e.g. report.txt, notes.md)", true),
                ToolParameter("content", "STRING", "Text content to save", true)
            )
        ),
        ToolDefinition(
            name = "read_document",
            description = "Read the content of a document stored in JARVIS documents storage",
            parameters = listOf(
                ToolParameter("fileName", "STRING", "File name to read", true)
            )
        ),
        ToolDefinition(
            name = "delete_document",
            description = "Delete a document from JARVIS documents storage",
            parameters = listOf(
                ToolParameter("fileName", "STRING", "File name to delete", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "prepare_create_document",
            description = "Open Android Storage Access Framework (SAF) picker to save a file",
            parameters = listOf(
                ToolParameter("suggestedFileName", "STRING", "Initial file name", true),
                ToolParameter("mimeType", "STRING", "MIME type (e.g. text/plain, application/json)", false)
            )
        ),
        ToolDefinition(
            name = "prepare_open_document",
            description = "Open Android Storage Access Framework (SAF) document picker to choose a file",
            parameters = listOf(
                ToolParameter("mimeType", "STRING", "MIME filter (e.g. */*, text/*)", false)
            )
        ),
        // Media Controls
        ToolDefinition(
            name = "media_control",
            description = "Send playback transport controls to active Android media player (PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP)",
            parameters = listOf(
                ToolParameter("command", "STRING", "Playback command: 'PLAY', 'PAUSE', 'TOGGLE', 'NEXT', 'PREVIOUS', 'STOP'", true)
            )
        ),
        ToolDefinition(
            name = "get_media_state",
            description = "Check if audio/music is actively playing on the device and get current volume level",
            parameters = emptyList()
        ),
        // Wi-Fi
        ToolDefinition(
            name = "get_wifi_status",
            description = "Check Wi-Fi enabled state, connection status, SSID, and metered network state",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "open_wifi_settings",
            description = "Open the Android system Internet / Wi-Fi settings panel",
            parameters = emptyList()
        ),
        // Calendar CRUD
        ToolDefinition(
            name = "create_calendar_event",
            description = "Create a calendar event with title, start/end time, description, location, and optional recurrence (RRULE)",
            parameters = listOf(
                ToolParameter("title", "STRING", "Event title", true),
                ToolParameter("startTimeMillis", "STRING", "Start timestamp in milliseconds or relative hours", true),
                ToolParameter("endTimeMillis", "STRING", "End timestamp in milliseconds (optional)", false),
                ToolParameter("description", "STRING", "Event description", false),
                ToolParameter("location", "STRING", "Event location", false),
                ToolParameter("recurrenceRule", "STRING", "Recurrence rule (e.g. 'FREQ=WEEKLY;BYDAY=MO')", false)
            ),
            requiredPermission = android.Manifest.permission.WRITE_CALENDAR
        ),
        ToolDefinition(
            name = "read_calendar_events",
            description = "Read upcoming events from device calendars for the specified number of days ahead",
            parameters = listOf(
                ToolParameter("daysAhead", "STRING", "Number of days ahead to scan (default 7)", false)
            ),
            requiredPermission = android.Manifest.permission.READ_CALENDAR
        ),
        ToolDefinition(
            name = "update_calendar_event",
            description = "Update the title, description, or location of an existing calendar event by ID",
            parameters = listOf(
                ToolParameter("eventId", "STRING", "The numeric event ID to update", true),
                ToolParameter("title", "STRING", "New title", false),
                ToolParameter("description", "STRING", "New description", false),
                ToolParameter("location", "STRING", "New location", false)
            ),
            requiredPermission = android.Manifest.permission.WRITE_CALENDAR
        ),
        ToolDefinition(
            name = "delete_calendar_event",
            description = "Delete a calendar event by its numeric ID",
            parameters = listOf(
                ToolParameter("eventId", "STRING", "The numeric event ID to delete", true)
            ),
            requiresConfirmation = true,
            requiredPermission = android.Manifest.permission.WRITE_CALENDAR
        ),
        // Semantic Long-Term Memory
        ToolDefinition(
            name = "semantic_memory_search",
            description = "Perform real on-device semantic search over persistent long-term memories using vector cosine similarity",
            parameters = listOf(
                ToolParameter("query", "STRING", "Search query or concept", true),
                ToolParameter("limit", "STRING", "Maximum results to retrieve (default 5)", false)
            )
        ),
        ToolDefinition(
            name = "remember",
            description = "Save an explicit long-term user memory or preference with on-device semantic vector representation",
            parameters = listOf(
                ToolParameter("key", "STRING", "Short identifier or title for the memory", true),
                ToolParameter("content", "STRING", "Detailed memory content", true),
                ToolParameter("category", "STRING", "Category: preference, fact, task, instruction", false),
                ToolParameter("importance", "STRING", "Importance score between 0.0 and 1.0", false)
            )
        ),
        ToolDefinition(
            name = "forget_memory",
            description = "Delete a specific memory by its numeric ID or key",
            parameters = listOf(
                ToolParameter("identifier", "STRING", "Numeric ID or exact key of the memory to delete", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "update_memory",
            description = "Update the content of an existing memory by its numeric ID",
            parameters = listOf(
                ToolParameter("id", "STRING", "Numeric ID of the memory to update", true),
                ToolParameter("content", "STRING", "New memory content", true)
            )
        ),
        ToolDefinition(
            name = "get_saved_memories",
            description = "Retrieve a structured summary of all persistent user preferences, routines, and important facts",
            parameters = emptyList()
        ),
        // Telephony & Call State
        ToolDefinition(
            name = "call_contact",
            description = "Place a phone call directly or open the system dialer with target phone number",
            parameters = listOf(
                ToolParameter("phoneNumber", "STRING", "Phone number to call", true),
                ToolParameter("directCall", "BOOLEAN", "True to call directly if permitted, false to open dialer", false)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "get_call_state",
            description = "Check real-time telephony call state (IDLE, RINGING, ACTIVE, DIALER_OPENED)",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "read_call_log",
            description = "Query Android system call history with caller name, number, duration, timestamp, and call type (INCOMING, OUTGOING, MISSED, REJECTED)",
            parameters = listOf(
                ToolParameter("limit", "INTEGER", "Maximum number of recent call log entries to retrieve (default: 10)", false)
            ),
            requiredPermission = android.Manifest.permission.READ_CALL_LOG
        ),
        // Web Research Workflows
        ToolDefinition(
            name = "extract_page_text",
            description = "Fetch readable text and title from a web URL, stripping scripts and HTML markup",
            parameters = listOf(
                ToolParameter("url", "STRING", "HTTP/HTTPS web address to extract", true)
            )
        ),
        ToolDefinition(
            name = "follow_link",
            description = "Follow a link from search results or extracted pages to read its full text",
            parameters = listOf(
                ToolParameter("url", "STRING", "The URL to navigate to and extract text from", true)
            )
        ),
        ToolDefinition(
            name = "research_topic",
            description = "Perform a multi-query web research workflow on a topic, extracting summaries and preserving citations",
            parameters = listOf(
                ToolParameter("topic", "STRING", "Subject or topic to research in depth", true)
            )
        ),
        ToolDefinition(
            name = "research_web",
            description = "Deep web research engine that breaks down a complex query, reads sources, and synthesizes key findings with citations",
            parameters = listOf(
                ToolParameter("query", "STRING", "Research inquiry or subject", true)
            )
        ),
        ToolDefinition(
            name = "compare_sources",
            description = "Extract and compare web content from multiple URLs to identify agreements and differences",
            parameters = listOf(
                ToolParameter("urls", "STRING", "Comma-separated list of web URLs to compare", true)
            )
        ),
        ToolDefinition(
            name = "manage_wifi",
            description = "Manage Wi-Fi connectivity: check status, scan for available networks, or open system Wi-Fi panel",
            parameters = listOf(
                ToolParameter("operation", "STRING", "Operation: 'STATUS', 'SCAN', or 'SETTINGS'", true)
            )
        ),
        ToolDefinition(
            name = "manage_bluetooth",
            description = "Manage Bluetooth: check state, list paired devices, start discovery, or open settings",
            parameters = listOf(
                ToolParameter("operation", "STRING", "Operation: 'STATUS', 'PAIRED_DEVICES', 'DISCOVERY', or 'SETTINGS'", true)
            )
        ),
        ToolDefinition(
            name = "calendar_manage",
            description = "Unified Android Calendar management with full support for CREATE, READ, UPDATE, DELETE, recurrence (RRULE), attendees, and reminders",
            parameters = listOf(
                ToolParameter("action", "STRING", "Action: 'CREATE', 'READ', 'UPDATE', or 'DELETE'", true),
                ToolParameter("title", "STRING", "Event title (for CREATE / UPDATE)", false),
                ToolParameter("startTimeMillis", "STRING", "Start timestamp in ms or delayHours", false),
                ToolParameter("endTimeMillis", "STRING", "End timestamp in ms or durationHours", false),
                ToolParameter("description", "STRING", "Event notes or description", false),
                ToolParameter("location", "STRING", "Location of event", false),
                ToolParameter("recurrenceRule", "STRING", "RRULE (e.g. FREQ=WEEKLY;BYDAY=MO)", false),
                ToolParameter("attendees", "STRING", "Comma-separated emails of attendees", false),
                ToolParameter("reminders", "STRING", "Comma-separated reminder minutes before event (e.g. '15,60')", false),
                ToolParameter("eventId", "STRING", "Event ID for UPDATE or DELETE", false),
                ToolParameter("mode", "STRING", "Recurrence scope: 'SINGLE_INSTANCE', 'THIS_AND_FUTURE', or 'ENTIRE_SERIES'", false),
                ToolParameter("instanceTimeMillis", "STRING", "Timestamp in ms of the specific occurrence for recurring events", false)
            ),
            requiredPermission = android.Manifest.permission.WRITE_CALENDAR
        ),
        // Automation Management
        ToolDefinition(
            name = "create_automation_rule",
            description = "Create a persistent automation rule in Room (TIME_DAILY, BATTERY_LOW, BATTERY_CHARGING, GEOFENCE)",
            parameters = listOf(
                ToolParameter("name", "STRING", "Automation name", true),
                ToolParameter("trigger", "STRING", "Trigger type: TIME_DAILY, BATTERY_LOW, BATTERY_CHARGING, GEOFENCE", true),
                ToolParameter("conditions", "STRING", "Condition parameter (e.g. '08:00' for daily time, '20' for battery %)", false),
                ToolParameter("action", "STRING", "Action: SPEAK_MESSAGE, LAUNCH_APP, SEND_NOTIFICATION", true),
                ToolParameter("actionPayload", "STRING", "Message to speak or app to launch", false)
            )
        ),
        ToolDefinition(
            name = "list_automation_rules",
            description = "List all configured persistent automation rules and their execution statistics",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "delete_automation_rule",
            description = "Delete a persistent automation rule by its numeric ID",
            parameters = listOf(
                ToolParameter("id", "STRING", "ID of the rule to delete", true)
            ),
            requiresConfirmation = true
        ),
        ToolDefinition(
            name = "run_automation_now",
            description = "Immediately trigger execution of an automation rule by its numeric ID",
            parameters = listOf(
                ToolParameter("id", "STRING", "ID of the rule to execute", true)
            )
        ),
        // Comprehensive Device Control Additions
        ToolDefinition(
            name = "set_volume",
            description = "Set Android device volume level, percentage, stream, or mute/unmute state",
            parameters = listOf(
                ToolParameter("stream", "STRING", "Audio stream: 'MEDIA', 'RING', 'ALARM', or 'NOTIFICATION'", false),
                ToolParameter("percent", "INTEGER", "Target volume percentage from 0 to 100", false),
                ToolParameter("direction", "STRING", "Optional relative adjustment: 'UP' or 'DOWN'", false),
                ToolParameter("mute", "BOOLEAN", "True to mute, false to unmute", false)
            )
        ),
        ToolDefinition(
            name = "open_settings",
            description = "Directly open Android system settings for specific hardware or features (e.g. bluetooth, wifi, sound, display, apps, accessibility, location, date, battery, developer)",
            parameters = listOf(
                ToolParameter("category", "STRING", "Settings screen: 'bluetooth', 'wifi', 'sound', 'display', 'apps', 'accessibility', 'location', 'date', 'battery', 'developer', or 'system'", true)
            )
        ),
        ToolDefinition(
            name = "open_app_settings",
            description = "Open the Android system App Info / Settings screen for a specific application",
            parameters = listOf(
                ToolParameter("appName", "STRING", "The app name or package identifier", true)
            )
        ),
        ToolDefinition(
            name = "is_app_installed",
            description = "Check if an application is installed and available on the Android device",
            parameters = listOf(
                ToolParameter("appName", "STRING", "App name or package name to check", true)
            )
        ),
        ToolDefinition(
            name = "read_clipboard",
            description = "Read the current text snippet copied to the Android system clipboard",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "clear_clipboard",
            description = "Clear the contents of the Android system clipboard",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "open_file",
            description = "Open a stored document or file with the Android system default viewer application",
            parameters = listOf(
                ToolParameter("fileName", "STRING", "File name or path to open", true)
            )
        ),
        ToolDefinition(
            name = "get_storage_info",
            description = "Query device storage capacity, internal/external available disk space, and memory statistics",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "dismiss_notification",
            description = "Dismiss an active device notification by its key or title",
            parameters = listOf(
                ToolParameter("query", "STRING", "Notification key, app name, or title text to dismiss (or 'ALL' to dismiss all)", true)
            ),
            requiredPermission = "SPECIAL_NOTIFICATION_LISTENER"
        ),
        ToolDefinition(
            name = "reply_notification",
            description = "Reply directly to an active messaging notification if it supports inline reply",
            parameters = listOf(
                ToolParameter("query", "STRING", "App name or title of the notification to reply to", true),
                ToolParameter("message", "STRING", "Text message reply to send", true)
            ),
            requiredPermission = "SPECIAL_NOTIFICATION_LISTENER"
        ),
        // Universal App Automation & Multi-Step Workflows
        ToolDefinition(
            name = "universal_app_action",
            description = "Perform a universal action on any installed Android application using accessibility UI inspection (OPEN_APP, CLICK, LONG_CLICK, TYPE_TEXT, SET_TEXT, SCROLL, SWIPE, BACK, HOME, RECENTS)",
            parameters = listOf(
                ToolParameter("appName", "STRING", "Target application name (e.g. WhatsApp, Chrome, YouTube, Settings, Gmail, Instagram)", false),
                ToolParameter("action", "STRING", "Action to perform: 'OPEN_APP', 'CLICK', 'LONG_CLICK', 'TYPE_TEXT', 'SET_TEXT', 'SCROLL', 'SWIPE', 'BACK', 'HOME', 'RECENTS'", true),
                ToolParameter("targetText", "STRING", "Text, button label, or content description to click/target", false),
                ToolParameter("inputText", "STRING", "Text to type or enter into input field", false),
                ToolParameter("scrollDirection", "STRING", "Direction for scroll/swipe: 'UP', 'DOWN', 'LEFT', 'RIGHT'", false)
            ),
            requiredPermission = "SPECIAL_ACCESSIBILITY"
        ),
        ToolDefinition(
            name = "run_multi_step_workflow",
            description = "Execute a multi-step automation sequence across apps (e.g. open app -> search -> click result -> type text -> confirm -> send/verify)",
            parameters = listOf(
                ToolParameter("appName", "STRING", "Primary app name for the workflow", true),
                ToolParameter("workflowStepsJson", "STRING", "JSON array of steps with actionType, targetQuery/text, textValue, requiresConfirmation", true),
                ToolParameter("requiresConfirmation", "BOOLEAN", "True if high-impact action (send message, publish post, delete, payment) requires user confirmation", false),
                ToolParameter("confirmationPrompt", "STRING", "User-facing confirmation question before final action", false)
            ),
            requiredPermission = "SPECIAL_ACCESSIBILITY"
        ),
        ToolDefinition(
            name = "inspect_screen_ui",
            description = "Deeply inspect current screen accessibility node hierarchy, text, buttons, inputs, IDs, and states",
            parameters = emptyList(),
            requiredPermission = "SPECIAL_ACCESSIBILITY"
        ),
        ToolDefinition(
            name = "confirm_pending_action",
            description = "Confirm or cancel a pending high-impact action waiting for user approval (send message, post, call, payment)",
            parameters = listOf(
                ToolParameter("confirmed", "BOOLEAN", "True to approve and execute the pending action, false to cancel", true)
            )
        ),
        ToolDefinition(
            name = "save_automation_workflow",
            description = "Save a reusable learned automation workflow pattern for voice trigger without storing sensitive credentials",
            parameters = listOf(
                ToolParameter("name", "STRING", "Workflow name", true),
                ToolParameter("triggerPhrase", "STRING", "Natural language trigger phrase (e.g. 'start work routine', 'morning news')", true),
                ToolParameter("stepsJson", "STRING", "JSON string representing workflow steps", true),
                ToolParameter("description", "STRING", "Description of what this workflow does", false)
            )
        ),
        ToolDefinition(
            name = "list_saved_workflows",
            description = "List all persistent learned automation workflows stored in JARVIS memory",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "configure_touch_guard",
            description = "Configure or query JARVIS Touch Guard security sentry. Actions: 'arm', 'disarm', 'status', 'silence', 'enable', 'disable', 'logs'.",
            parameters = listOf(
                ToolParameter("action", "STRING", "Action to perform: 'arm', 'disarm', 'status', 'silence', 'enable', 'disable', 'logs'", true)
            )
        ),
        ToolDefinition(
            name = "configure_screen_lock",
            description = "Manage JARVIS Screen Lock settings and diagnostic testing. Actions: 'status', 'wake_on', 'wake_off', 'unlock_on', 'unlock_off', 'test_unlock'.",
            parameters = listOf(
                ToolParameter("action", "STRING", "Action to perform: 'status', 'wake_on', 'wake_off', 'unlock_on', 'unlock_off', 'test_unlock'", true)
            )
        ),
        ToolDefinition(
            name = "configure_whatsapp_auto_reply",
            description = "Configure or query WhatsApp Auto-Reply settings. Actions: 'status', 'enable', 'disable', 'set_message', 'clear_history'.",
            parameters = listOf(
                ToolParameter("action", "STRING", "Action to perform: 'status', 'enable', 'disable', 'set_message', 'clear_history'", true),
                ToolParameter("message", "STRING", "Custom reply message to set (used with 'set_message')", false)
            )
        )
    )

    fun getToolDefinition(name: String): ToolDefinition? = allTools.find { it.name == name }

    /**
     * Formats the tool declarations as a JSON string for Gemini REST API function calling.
     */
    fun buildGeminiToolsJson(): String {
        val toolsArray = JSONArray()
        val functionDeclarations = JSONArray()

        for (tool in allTools) {
            val fnObj = JSONObject()
            fnObj.put("name", tool.name)
            fnObj.put("description", tool.description)

            val paramsObj = JSONObject()
            paramsObj.put("type", "OBJECT")

            val propsObj = JSONObject()
            val requiredArray = JSONArray()

            for (p in tool.parameters) {
                val prop = JSONObject()
                prop.put("type", p.type)
                prop.put("description", p.description)
                propsObj.put(p.name, prop)

                if (p.isRequired) {
                    requiredArray.put(p.name)
                }
            }

            paramsObj.put("properties", propsObj)
            if (requiredArray.length() > 0) {
                paramsObj.put("required", requiredArray)
            }

            fnObj.put("parameters", paramsObj)
            functionDeclarations.put(fnObj)
        }

        val container = JSONObject()
        container.put("functionDeclarations", functionDeclarations)
        toolsArray.put(container)

        return toolsArray.toString()
    }
}
