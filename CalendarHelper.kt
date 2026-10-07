package com.example.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Real Android Calendar management helper supporting complete CRUD:
 * Events, Multiple Reminders, Attendees, Recurrence (RRULE), Timezones, and Calendar Selection.
 * Conforms to CalendarContract specifications and reports explicit result states.
 */
class CalendarHelper(private val context: Context) {

    private val tag = "CalendarHelper"

    enum class CalendarStatus {
        CALENDAR_CREATED,
        CALENDAR_UPDATED,
        CALENDAR_DELETED,
        CALENDAR_FAILED,
        CALENDAR_PERMISSION_REQUIRED,
        CALENDAR_USER_ACTION_REQUIRED,
        CALENDAR_INSTANCE_UPDATED,
        CALENDAR_INSTANCE_DELETED,
        CALENDAR_SERIES_UPDATED,
        CALENDAR_SERIES_DELETED,
        CALENDAR_OPERATION_UNSUPPORTED,
        EVENT_CREATED,
        EVENT_UPDATED,
        EVENT_DELETED,
        EVENT_NOT_FOUND,
        NO_WRITABLE_CALENDAR,
        ERROR
    }

    enum class RecurrenceOperationMode {
        SINGLE_INSTANCE,
        THIS_AND_FUTURE,
        ENTIRE_SERIES
    }

    data class CalendarInstanceInfo(
        val eventId: Long,
        val title: String,
        val beginTimeMillis: Long,
        val endTimeMillis: Long,
        val startDay: Int,
        val endDay: Int,
        val location: String?,
        val allDay: Boolean
    )

    data class AttendeeInfo(
        val name: String,
        val email: String,
        val status: Int = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
    )

    data class CalendarEventDetails(
        val id: Long,
        val title: String,
        val description: String,
        val location: String,
        val startTimeMillis: Long,
        val endTimeMillis: Long,
        val allDay: Boolean,
        val recurrenceRule: String?,
        val timezone: String,
        val calendarId: Long,
        val attendees: List<AttendeeInfo> = emptyList(),
        val remindersMinutes: List<Int> = emptyList()
    )

    data class CalendarResult(
        val status: CalendarStatus,
        val isSuccess: Boolean,
        val message: String,
        val eventId: Long? = null,
        val data: Map<String, Any> = emptyMap()
    )

    private fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lists available calendars on the device for account/calendar selection.
     */
    fun listCalendars(): List<Map<String, Any>> {
        if (!hasReadPermission()) return emptyList()

        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE
        )

        val list = mutableListOf<Map<String, Any>>()
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    list.add(
                        mapOf(
                            "id" to it.getLong(0),
                            "displayName" to (it.getString(1) ?: "Default"),
                            "accountName" to (it.getString(2) ?: "Local"),
                            "isPrimary" to (it.getInt(3) == 1),
                            "timeZone" to (it.getString(4) ?: TimeZone.getDefault().id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to query calendars: ${e.message}", e)
        }
        return list
    }

    fun getWritableCalendarId(preferredCalendarId: Long? = null): Long? {
        val calendars = listCalendars()
        if (calendars.isEmpty()) return null
        if (preferredCalendarId != null && calendars.any { (it["id"] as? Long) == preferredCalendarId }) {
            return preferredCalendarId
        }
        val primary = calendars.find { it["isPrimary"] == true }
        return (primary?.get("id") as? Long) ?: (calendars.firstOrNull()?.get("id") as? Long)
    }

    /**
     * Builds and validates RFC 5545 recurrence rule (RRULE).
     * Accepts standard RRULE syntax or shorthand (DAILY, WEEKLY, MONTHLY, YEARLY).
     */
    fun formatRecurrenceRule(
        frequency: String, // "DAILY", "WEEKLY", "MONTHLY", "YEARLY"
        interval: Int = 1,
        count: Int? = null,
        untilDateUtc: String? = null,
        byDay: List<String> = emptyList() // "MO", "TU", "WE", "TH", "FR", "SA", "SU"
    ): String {
        val freqUpper = frequency.trim().uppercase()
        if (freqUpper.startsWith("FREQ=")) {
            return freqUpper
        }
        val validFreqs = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
        val finalFreq = if (validFreqs.contains(freqUpper)) freqUpper else "DAILY"

        val parts = mutableListOf("FREQ=$finalFreq")
        if (interval > 1) {
            parts.add("INTERVAL=$interval")
        }
        if (count != null && count > 0) {
            parts.add("COUNT=$count")
        } else if (!untilDateUtc.isNullOrBlank()) {
            parts.add("UNTIL=$untilDateUtc")
        }
        if (byDay.isNotEmpty()) {
            parts.add("BYDAY=${byDay.joinToString(",") { it.trim().uppercase() }}")
        }
        return parts.joinToString(";")
    }

    /**
     * Real Calendar event creation using CalendarContract with attendees, reminders, and recurrence.
     */
    fun createEvent(
        title: String,
        description: String = "",
        location: String = "",
        startTimeMillis: Long,
        endTimeMillis: Long,
        allDay: Boolean = false,
        recurrenceRule: String = "",
        remindersMinutes: List<Int> = listOf(15),
        attendees: List<Pair<String, String>> = emptyList(), // Pair(name, email)
        selectedCalendarId: Long? = null,
        timeZoneId: String? = null
    ): CalendarResult {
        if (!hasWritePermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission WRITE_CALENDAR is required to create calendar events."
            )
        }

        val calId = getWritableCalendarId(selectedCalendarId) ?: return CalendarResult(
            status = CalendarStatus.CALENDAR_USER_ACTION_REQUIRED,
            isSuccess = false,
            message = "No active writable calendar account found on this device. Please add a calendar account."
        )

        val validatedEndTime = if (endTimeMillis <= startTimeMillis) {
            startTimeMillis + (60 * 60 * 1000) // Default 1 hour duration
        } else {
            endTimeMillis
        }

        val tz = timeZoneId?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, validatedEndTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            if (recurrenceRule.isNotBlank()) {
                put(CalendarContract.Events.RRULE, recurrenceRule)
            }
        }

        return try {
            val uri: Uri? = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull()

            if (eventId == null) {
                return CalendarResult(
                    status = CalendarStatus.CALENDAR_FAILED,
                    isSuccess = false,
                    message = "ContentResolver failed to insert event into Calendar."
                )
            }

            // Insert multiple reminders
            for (minute in remindersMinutes.filter { it >= 0 }) {
                try {
                    val reminderValues = ContentValues().apply {
                        put(CalendarContract.Reminders.MINUTES, minute)
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                } catch (re: Exception) {
                    Log.w(tag, "Could not insert reminder ($minute min): ${re.message}")
                }
            }

            // Insert attendees
            for ((name, email) in attendees.filter { it.second.isNotBlank() }) {
                try {
                    val attendeeValues = ContentValues().apply {
                        put(CalendarContract.Attendees.EVENT_ID, eventId)
                        put(CalendarContract.Attendees.ATTENDEE_NAME, name)
                        put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                        put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                        put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                        put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                    }
                    context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, attendeeValues)
                } catch (ae: Exception) {
                    Log.w(tag, "Could not insert attendee ($email): ${ae.message}")
                }
            }

            val rruleDesc = if (recurrenceRule.isNotBlank()) " [Recurring: $recurrenceRule]" else ""
            CalendarResult(
                status = CalendarStatus.CALENDAR_CREATED,
                isSuccess = true,
                message = "CALENDAR_CREATED: Event '$title' scheduled successfully (Event ID: $eventId)$rruleDesc.",
                eventId = eventId,
                data = mapOf(
                    "eventId" to eventId,
                    "title" to title,
                    "calendarId" to calId,
                    "startTime" to startTimeMillis,
                    "recurrence" to recurrenceRule,
                    "reminders" to remindersMinutes,
                    "attendeeCount" to attendees.size
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to insert calendar event", e)
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to insert calendar event: ${e.message}"
            )
        }
    }

    /**
     * Reads a specific event including its attendees and reminders.
     */
    fun readEvent(eventId: Long): CalendarResult {
        if (!hasReadPermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission READ_CALENDAR is required."
            )
        }

        val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.CALENDAR_ID
        )

        return try {
            val cursor = context.contentResolver.query(eventUri, projection, null, null, null)
            var eventDetails: CalendarEventDetails? = null

            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "Untitled"
                    val desc = it.getString(2) ?: ""
                    val loc = it.getString(3) ?: ""
                    val start = it.getLong(4)
                    val end = it.getLong(5)
                    val allDay = it.getInt(6) == 1
                    val rrule = it.getString(7)
                    val tz = it.getString(8) ?: TimeZone.getDefault().id
                    val calId = it.getLong(9)

                    // Read reminders
                    val reminders = getEventReminders(id)
                    // Read attendees
                    val attendees = getEventAttendees(id)

                    eventDetails = CalendarEventDetails(
                        id = id,
                        title = title,
                        description = desc,
                        location = loc,
                        startTimeMillis = start,
                        endTimeMillis = end,
                        allDay = allDay,
                        recurrenceRule = rrule,
                        timezone = tz,
                        calendarId = calId,
                        attendees = attendees,
                        remindersMinutes = reminders
                    )
                }
            }

            if (eventDetails != null) {
                val details = eventDetails!!
                val sdf = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.US)
                val text = buildString {
                    append("Event #${details.id}: '${details.title}'\n")
                    append("Time: ${sdf.format(Date(details.startTimeMillis))} - ${sdf.format(Date(details.endTimeMillis))}\n")
                    if (details.location.isNotBlank()) append("Location: ${details.location}\n")
                    if (!details.recurrenceRule.isNullOrBlank()) append("Recurrence: ${details.recurrenceRule}\n")
                    if (details.remindersMinutes.isNotEmpty()) append("Reminders: ${details.remindersMinutes.joinToString { "${it}m" }}\n")
                    if (details.attendees.isNotEmpty()) append("Attendees: ${details.attendees.joinToString { "${it.name} <${it.email}>" }}\n")
                }
                CalendarResult(
                    status = CalendarStatus.CALENDAR_CREATED,
                    isSuccess = true,
                    message = text,
                    eventId = eventId,
                    data = mapOf(
                        "eventId" to details.id,
                        "title" to details.title,
                        "startTime" to details.startTimeMillis,
                        "endTime" to details.endTimeMillis,
                        "recurrenceRule" to (details.recurrenceRule ?: "")
                    )
                )
            } else {
                CalendarResult(
                    status = CalendarStatus.CALENDAR_FAILED,
                    isSuccess = false,
                    message = "Event #$eventId not found."
                )
            }
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Error reading event #$eventId: ${e.message}"
            )
        }
    }

    private fun getEventReminders(eventId: Long): List<Int> {
        val list = mutableListOf<Int>()
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    list.add(it.getInt(0))
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to read reminders for event #$eventId", e)
        }
        return list
    }

    private fun getEventAttendees(eventId: Long): List<AttendeeInfo> {
        val list = mutableListOf<AttendeeInfo>()
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees.ATTENDEE_NAME,
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS
                ),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: ""
                    val email = it.getString(1) ?: ""
                    val status = it.getInt(2)
                    if (email.isNotBlank() || name.isNotBlank()) {
                        list.add(AttendeeInfo(name, email, status))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to read attendees for event #$eventId", e)
        }
        return list
    }

    /**
     * Searches and lists upcoming events within daysAhead window.
     */
    fun readUpcomingEvents(daysAhead: Int = 7): CalendarResult {
        if (!hasReadPermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission READ_CALENDAR is required to query calendar events."
            )
        }

        val now = System.currentTimeMillis()
        val endRange = now + (daysAhead.toLong() * 24 * 60 * 60 * 1000)

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(now.toString(), endRange.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC LIMIT 50"

        val sdf = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.US)
        val events = mutableListOf<String>()

        return try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "Untitled"
                    val start = it.getLong(2)
                    val location = it.getString(4) ?: ""
                    val rrule = it.getString(6)
                    val locStr = if (location.isNotBlank()) " @ $location" else ""
                    val recurStr = if (!rrule.isNullOrBlank()) " [Recurring]" else ""
                    events.add("#$id: '$title' on ${sdf.format(Date(start))}$locStr$recurStr")
                }
            }
            val text = if (events.isEmpty()) {
                "No upcoming events found for the next $daysAhead days."
            } else {
                "Upcoming events:\n" + events.joinToString("\n")
            }
            CalendarResult(
                status = CalendarStatus.CALENDAR_CREATED,
                isSuccess = true,
                message = text,
                data = mapOf("count" to events.size)
            )
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Error querying calendar: ${e.message}"
            )
        }
    }

    /**
     * Looks up events by search keyword (title or description or location).
     */
    fun lookupEvents(query: String, maxResults: Int = 20): CalendarResult {
        if (!hasReadPermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission READ_CALENDAR is required to search events."
            )
        }

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.RRULE
        )
        val selection = "(${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ? OR ${CalendarContract.Events.EVENT_LOCATION} LIKE ?)"
        val arg = "%$query%"
        val selectionArgs = arrayOf(arg, arg, arg)
        val sortOrder = "${CalendarContract.Events.DTSTART} DESC LIMIT $maxResults"

        val sdf = SimpleDateFormat("EEE, MMM d, yyyy h:mm a", Locale.US)
        val results = mutableListOf<String>()

        return try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "Untitled"
                    val start = it.getLong(2)
                    val location = it.getString(3) ?: ""
                    val rrule = it.getString(4)
                    val locStr = if (location.isNotBlank()) " @ $location" else ""
                    val recurStr = if (!rrule.isNullOrBlank()) " [Recurring]" else ""
                    results.add("#$id: '$title' on ${sdf.format(Date(start))}$locStr$recurStr")
                }
            }
            val text = if (results.isEmpty()) {
                "No calendar events found matching '$query'."
            } else {
                "Matching events for '$query':\n" + results.joinToString("\n")
            }
            CalendarResult(
                status = CalendarStatus.CALENDAR_CREATED,
                isSuccess = true,
                message = text,
                data = mapOf("count" to results.size)
            )
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to search calendar: ${e.message}"
            )
        }
    }

    /**
     * Updates an existing event, including recurrence rules, location, and start/end time.
     */
    fun updateEvent(
        eventId: Long,
        title: String? = null,
        description: String? = null,
        location: String? = null,
        startTimeMillis: Long? = null,
        endTimeMillis: Long? = null,
        recurrenceRule: String? = null,
        newAttendeeEmail: String? = null,
        newReminderMinutes: Int? = null
    ): CalendarResult {
        if (!hasWritePermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission WRITE_CALENDAR required to update events."
            )
        }

        val values = ContentValues()
        if (title != null) values.put(CalendarContract.Events.TITLE, title)
        if (description != null) values.put(CalendarContract.Events.DESCRIPTION, description)
        if (location != null) values.put(CalendarContract.Events.EVENT_LOCATION, location)
        if (startTimeMillis != null) values.put(CalendarContract.Events.DTSTART, startTimeMillis)
        if (endTimeMillis != null) values.put(CalendarContract.Events.DTEND, endTimeMillis)
        if (recurrenceRule != null) values.put(CalendarContract.Events.RRULE, recurrenceRule)

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return try {
            val rows = context.contentResolver.update(uri, values, null, null)

            // Optional new attendee
            if (!newAttendeeEmail.isNullOrBlank()) {
                val attValues = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, eventId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, newAttendeeEmail)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                }
                context.contentResolver.insert(CalendarContract.Attendees.CONTENT_URI, attValues)
            }

            // Optional new reminder
            if (newReminderMinutes != null && newReminderMinutes >= 0) {
                val remValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, newReminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues)
            }

            if (rows > 0) {
                CalendarResult(
                    status = CalendarStatus.CALENDAR_UPDATED,
                    isSuccess = true,
                    message = "CALENDAR_UPDATED: Calendar event #$eventId updated successfully.",
                    eventId = eventId
                )
            } else {
                CalendarResult(
                    status = CalendarStatus.CALENDAR_FAILED,
                    isSuccess = false,
                    message = "Event #$eventId was not found or could not be updated."
                )
            }
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to update event: ${e.message}"
            )
        }
    }

    /**
     * Deletes a specific event or recurring series.
     */
    fun deleteEvent(eventId: Long): CalendarResult {
        if (!hasWritePermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission WRITE_CALENDAR required to delete events."
            )
        }

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                CalendarResult(
                    status = CalendarStatus.CALENDAR_DELETED,
                    isSuccess = true,
                    message = "CALENDAR_DELETED: Calendar event #$eventId deleted successfully.",
                    eventId = eventId
                )
            } else {
                CalendarResult(
                    status = CalendarStatus.CALENDAR_FAILED,
                    isSuccess = false,
                    message = "Event #$eventId was not found or already deleted."
                )
            }
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to delete event: ${e.message}"
            )
        }
    }

    /**
     * Query expanded recurrence instances in a time range using CalendarContract.Instances.
     */
    fun queryRecurrenceInstances(
        eventId: Long? = null,
        startMillis: Long,
        endMillis: Long
    ): List<CalendarInstanceInfo> {
        if (!hasReadPermission()) return emptyList()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.START_DAY,
            CalendarContract.Instances.END_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val selection = if (eventId != null) "${CalendarContract.Instances.EVENT_ID} = ?" else null
        val selectionArgs = if (eventId != null) arrayOf(eventId.toString()) else null
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        val instances = mutableListOf<CalendarInstanceInfo>()
        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "Untitled"
                    val begin = it.getLong(2)
                    val end = it.getLong(3)
                    val startDay = it.getInt(4)
                    val endDay = it.getInt(5)
                    val loc = it.getString(6)
                    val allDay = it.getInt(7) == 1

                    instances.add(
                        CalendarInstanceInfo(
                            eventId = id,
                            title = title,
                            beginTimeMillis = begin,
                            endTimeMillis = end,
                            startDay = startDay,
                            endDay = endDay,
                            location = loc,
                            allDay = allDay
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to query recurrence instances: ${e.message}", e)
        }
        return instances
    }

    /**
     * Modifies a recurring event occurrence with support for:
     * - SINGLE_INSTANCE: Inserts an exception event linked via ORIGINAL_ID and ORIGINAL_INSTANCE_TIME
     * - THIS_AND_FUTURE: Truncates the original series RRULE with UNTIL and creates a new series
     * - ENTIRE_SERIES: Updates the base event directly
     */
    fun modifyRecurringOccurrence(
        seriesEventId: Long,
        originalInstanceTimeMillis: Long,
        mode: RecurrenceOperationMode,
        newTitle: String? = null,
        newStartMillis: Long? = null,
        newEndMillis: Long? = null,
        newLocation: String? = null,
        newDescription: String? = null
    ): CalendarResult {
        if (!hasWritePermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission WRITE_CALENDAR is required."
            )
        }

        val master = readEvent(seriesEventId)
        if (!master.isSuccess) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Original recurring series #$seriesEventId not found."
            )
        }

        return try {
            when (mode) {
                RecurrenceOperationMode.ENTIRE_SERIES -> {
                    val updateRes = updateEvent(
                        eventId = seriesEventId,
                        title = newTitle,
                        description = newDescription,
                        location = newLocation,
                        startTimeMillis = newStartMillis,
                        endTimeMillis = newEndMillis
                    )
                    if (updateRes.isSuccess) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_SERIES_UPDATED,
                            isSuccess = true,
                            message = "CALENDAR_SERIES_UPDATED: Recurring series #$seriesEventId updated successfully.",
                            eventId = seriesEventId
                        )
                    } else updateRes
                }
                RecurrenceOperationMode.SINGLE_INSTANCE -> {
                    val calId = (master.data?.get("calendarId") as? Long) ?: getWritableCalendarId() ?: 1L
                    val tz = TimeZone.getDefault().id

                    val values = ContentValues().apply {
                        put(CalendarContract.Events.ORIGINAL_ID, seriesEventId)
                        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalInstanceTimeMillis)
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, newTitle ?: (master.data?.get("title") as? String ?: "Occurrence"))
                        put(CalendarContract.Events.DTSTART, newStartMillis ?: originalInstanceTimeMillis)
                        put(CalendarContract.Events.DTEND, newEndMillis ?: ((newStartMillis ?: originalInstanceTimeMillis) + 3600000L))
                        put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                        if (newDescription != null) put(CalendarContract.Events.DESCRIPTION, newDescription)
                        if (newLocation != null) put(CalendarContract.Events.EVENT_LOCATION, newLocation)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                    }

                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    val exceptionId = uri?.lastPathSegment?.toLongOrNull()

                    if (exceptionId != null && exceptionId > 0) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_INSTANCE_UPDATED,
                            isSuccess = true,
                            message = "CALENDAR_INSTANCE_UPDATED: Single recurrence instance on ${Date(originalInstanceTimeMillis)} updated as exception #$exceptionId.",
                            eventId = exceptionId,
                            data = mapOf(
                                "exceptionId" to exceptionId,
                                "seriesId" to seriesEventId,
                                "originalInstanceTime" to originalInstanceTimeMillis
                            )
                        )
                    } else {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_FAILED,
                            isSuccess = false,
                            message = "Failed to insert recurring exception event."
                        )
                    }
                }
                RecurrenceOperationMode.THIS_AND_FUTURE -> {
                    val existingRrule = (master.data?.get("recurrenceRule") as? String) ?: ""
                    if (existingRrule.isBlank()) {
                        return CalendarResult(
                            status = CalendarStatus.CALENDAR_OPERATION_UNSUPPORTED,
                            isSuccess = false,
                            message = "Event #$seriesEventId has no recurrence rule (RRULE)."
                        )
                    }

                    val truncatedRrule = truncateRruleUntil(existingRrule, originalInstanceTimeMillis - 1000)
                    updateEvent(eventId = seriesEventId, recurrenceRule = truncatedRrule)

                    val newStart = newStartMillis ?: originalInstanceTimeMillis
                    val newEnd = newEndMillis ?: (newStart + 3600000L)
                    val calId = (master.data?.get("calendarId") as? Long) ?: getWritableCalendarId() ?: 1L
                    val newTitleFinal = newTitle ?: (master.data?.get("title") as? String ?: "Recurring Event")

                    val createRes = createEvent(
                        selectedCalendarId = calId,
                        title = newTitleFinal,
                        description = newDescription ?: "",
                        location = newLocation ?: "",
                        startTimeMillis = newStart,
                        endTimeMillis = newEnd,
                        recurrenceRule = existingRrule
                    )

                    if (createRes.isSuccess) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_SERIES_UPDATED,
                            isSuccess = true,
                            message = "CALENDAR_SERIES_UPDATED: Modified all future occurrences starting from ${Date(originalInstanceTimeMillis)}. New series ID: ${createRes.eventId}.",
                            eventId = createRes.eventId
                        )
                    } else {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_FAILED,
                            isSuccess = false,
                            message = "Truncated original series but failed to create future series: ${createRes.message}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to modify recurrence: ${e.message}", e)
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to modify recurrence: ${e.message}"
            )
        }
    }

    /**
     * Deletes a recurring occurrence:
     * - SINGLE_INSTANCE: Creates an exception with STATUS_CANCELED
     * - THIS_AND_FUTURE: Truncates existing RRULE with UNTIL
     * - ENTIRE_SERIES: Deletes master event
     */
    fun deleteRecurringOccurrence(
        seriesEventId: Long,
        originalInstanceTimeMillis: Long,
        mode: RecurrenceOperationMode
    ): CalendarResult {
        if (!hasWritePermission()) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission WRITE_CALENDAR required."
            )
        }

        val master = readEvent(seriesEventId)
        if (!master.isSuccess) {
            return CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Recurring event #$seriesEventId not found."
            )
        }

        return try {
            when (mode) {
                RecurrenceOperationMode.ENTIRE_SERIES -> {
                    val delRes = deleteEvent(seriesEventId)
                    if (delRes.isSuccess) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_SERIES_DELETED,
                            isSuccess = true,
                            message = "CALENDAR_SERIES_DELETED: Entire recurring series #$seriesEventId deleted successfully.",
                            eventId = seriesEventId
                        )
                    } else delRes
                }
                RecurrenceOperationMode.SINGLE_INSTANCE -> {
                    val calId = (master.data?.get("calendarId") as? Long) ?: getWritableCalendarId() ?: 1L
                    val tz = TimeZone.getDefault().id
                    val title = (master.data?.get("title") as? String) ?: "Canceled occurrence"

                    val values = ContentValues().apply {
                        put(CalendarContract.Events.ORIGINAL_ID, seriesEventId)
                        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalInstanceTimeMillis)
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, originalInstanceTimeMillis)
                        put(CalendarContract.Events.DTEND, originalInstanceTimeMillis + 3600000L)
                        put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                        put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                    }

                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    val exceptionId = uri?.lastPathSegment?.toLongOrNull()

                    if (exceptionId != null && exceptionId > 0) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_INSTANCE_DELETED,
                            isSuccess = true,
                            message = "CALENDAR_INSTANCE_DELETED: Occurrence on ${Date(originalInstanceTimeMillis)} canceled.",
                            eventId = exceptionId
                        )
                    } else {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_FAILED,
                            isSuccess = false,
                            message = "Failed to mark recurrence instance as canceled."
                        )
                    }
                }
                RecurrenceOperationMode.THIS_AND_FUTURE -> {
                    val existingRrule = (master.data?.get("recurrenceRule") as? String) ?: ""
                    if (existingRrule.isBlank()) {
                        return CalendarResult(
                            status = CalendarStatus.CALENDAR_OPERATION_UNSUPPORTED,
                            isSuccess = false,
                            message = "Event #$seriesEventId has no recurrence rule (RRULE)."
                        )
                    }

                    val truncatedRrule = truncateRruleUntil(existingRrule, originalInstanceTimeMillis - 1000)
                    val updateRes = updateEvent(eventId = seriesEventId, recurrenceRule = truncatedRrule)
                    if (updateRes.isSuccess) {
                        CalendarResult(
                            status = CalendarStatus.CALENDAR_SERIES_UPDATED,
                            isSuccess = true,
                            message = "CALENDAR_SERIES_UPDATED: Recurring series terminated before ${Date(originalInstanceTimeMillis)}.",
                            eventId = seriesEventId
                        )
                    } else updateRes
                }
            }
        } catch (e: Exception) {
            CalendarResult(
                status = CalendarStatus.CALENDAR_FAILED,
                isSuccess = false,
                message = "Failed to delete recurrence: ${e.message}"
            )
        }
    }

    fun truncateRruleUntil(originalRrule: String, untilMillis: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val untilStr = sdf.format(Date(untilMillis))

        val parts = originalRrule.split(";").filterNot {
            it.startsWith("UNTIL=", ignoreCase = true) || it.startsWith("COUNT=", ignoreCase = true)
        }.toMutableList()

        parts.add("UNTIL=$untilStr")
        return parts.joinToString(";")
    }
}
