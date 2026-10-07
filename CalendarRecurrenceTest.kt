package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tools.CalendarHelper
import com.example.tools.CalendarHelper.CalendarStatus
import com.example.tools.CalendarHelper.RecurrenceOperationMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarRecurrenceTest {

    private lateinit var context: Context
    private lateinit var calendarHelper: CalendarHelper

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        calendarHelper = CalendarHelper(context)
    }

    @Test
    fun `test calendar status enum completeness`() {
        val statuses = CalendarStatus.values()
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_CREATED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_UPDATED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_DELETED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_FAILED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_PERMISSION_REQUIRED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_USER_ACTION_REQUIRED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_INSTANCE_UPDATED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_INSTANCE_DELETED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_SERIES_UPDATED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_SERIES_DELETED))
        assertTrue(statuses.contains(CalendarStatus.CALENDAR_OPERATION_UNSUPPORTED))
    }

    @Test
    fun `test recurrence operation mode enum values`() {
        val modes = RecurrenceOperationMode.values()
        assertTrue(modes.contains(RecurrenceOperationMode.SINGLE_INSTANCE))
        assertTrue(modes.contains(RecurrenceOperationMode.THIS_AND_FUTURE))
        assertTrue(modes.contains(RecurrenceOperationMode.ENTIRE_SERIES))
    }

    @Test
    fun `test formatRecurrenceRule syntax generation`() {
        val daily = calendarHelper.formatRecurrenceRule("DAILY")
        assertEquals("FREQ=DAILY", daily)

        val weekly = calendarHelper.formatRecurrenceRule(
            frequency = "WEEKLY",
            interval = 2,
            byDay = listOf("MO", "WE", "FR")
        )
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR", weekly)

        val withCount = calendarHelper.formatRecurrenceRule(
            frequency = "MONTHLY",
            count = 10
        )
        assertEquals("FREQ=MONTHLY;COUNT=10", withCount)

        val withUntil = calendarHelper.formatRecurrenceRule(
            frequency = "YEARLY",
            untilDateUtc = "20261231T235959Z"
        )
        assertEquals("FREQ=YEARLY;UNTIL=20261231T235959Z", withUntil)
    }

    @Test
    fun `test truncateRruleUntil correctly adds or modifies UNTIL clause`() {
        val originalRrule = "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,FR"
        val untilMillis = 1773000000000L // specific timestamp
        val truncated = calendarHelper.truncateRruleUntil(originalRrule, untilMillis)

        assertTrue(truncated.startsWith("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,FR;UNTIL="))
        assertTrue(truncated.endsWith("Z"))

        // Overwrite existing UNTIL
        val rruleWithOldUntil = "FREQ=DAILY;UNTIL=20250101T000000Z"
        val overwritten = calendarHelper.truncateRruleUntil(rruleWithOldUntil, untilMillis)
        assertFalse(overwritten.contains("20250101T000000Z"))
        assertTrue(overwritten.contains("UNTIL="))
    }

    @Test
    fun `test permission check failure returns CALENDAR_PERMISSION_REQUIRED`() {
        // In clean test environment without granted permissions
        val res = calendarHelper.createEvent(
            title = "Unauthorized Event",
            startTimeMillis = System.currentTimeMillis(),
            endTimeMillis = System.currentTimeMillis() + 3600000L
        )
        assertEquals(CalendarStatus.CALENDAR_PERMISSION_REQUIRED, res.status)
        assertFalse(res.isSuccess)
    }

    @Test
    fun `test modify recurring occurrence requires write permission`() {
        val res = calendarHelper.modifyRecurringOccurrence(
            seriesEventId = 123L,
            originalInstanceTimeMillis = System.currentTimeMillis(),
            mode = RecurrenceOperationMode.SINGLE_INSTANCE,
            newTitle = "Rescheduled Single Meeting"
        )
        assertEquals(CalendarStatus.CALENDAR_PERMISSION_REQUIRED, res.status)
        assertFalse(res.isSuccess)
    }

    @Test
    fun `test delete recurring occurrence requires write permission`() {
        val res = calendarHelper.deleteRecurringOccurrence(
            seriesEventId = 123L,
            originalInstanceTimeMillis = System.currentTimeMillis(),
            mode = RecurrenceOperationMode.THIS_AND_FUTURE
        )
        assertEquals(CalendarStatus.CALENDAR_PERMISSION_REQUIRED, res.status)
        assertFalse(res.isSuccess)
    }
}
