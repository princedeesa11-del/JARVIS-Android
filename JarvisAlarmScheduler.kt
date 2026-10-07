package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.local.JarvisDatabase
import com.example.data.local.entity.AutomationRule
import com.example.data.local.entity.ReminderItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * Real Android persistent scheduler using AlarmManager.
 * Supports: one-time, daily, weekly, monthly, yearly, interval schedules,
 * timezone/DST shifts, cancellation, and reboot / time-change recovery.
 */
object JarvisAlarmScheduler {

    private const val TAG = "JarvisAlarmScheduler"
    private const val REQUEST_CODE_REMINDER_OFFSET = 100_000
    private const val REQUEST_CODE_AUTOMATION_OFFSET = 200_000

    fun scheduleReminder(context: Context, reminder: ReminderItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (reminder.triggerTimeMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "Reminder #${reminder.id} is in the past. Skipping.")
            return
        }

        val intent = Intent(context, JarvisAlarmReceiver::class.java).apply {
            putExtra(JarvisAlarmReceiver.EXTRA_TYPE, JarvisAlarmReceiver.TYPE_REMINDER)
            putExtra(JarvisAlarmReceiver.EXTRA_ID, reminder.id)
            putExtra(JarvisAlarmReceiver.EXTRA_TITLE, reminder.title)
        }

        val requestCode = (REQUEST_CODE_REMINDER_OFFSET + reminder.id).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        setAlarmSafely(alarmManager, reminder.triggerTimeMillis, pendingIntent)
        Log.i(TAG, "Scheduled reminder #${reminder.id} for ${reminder.triggerTimeMillis}")
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, JarvisAlarmReceiver::class.java)
        val requestCode = (REQUEST_CODE_REMINDER_OFFSET + reminderId).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled reminder alarm #$reminderId")
        }
    }

    fun scheduleAutomationRule(context: Context, rule: AutomationRule) {
        if (!rule.enabled) {
            cancelAutomationRule(context, rule.id)
            return
        }

        // Cancel previous alarm to avoid duplicates
        cancelAutomationRule(context, rule.id)

        val nextTrigger = calculateNextTriggerMillis(rule) ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, JarvisAlarmReceiver::class.java).apply {
            putExtra(JarvisAlarmReceiver.EXTRA_TYPE, JarvisAlarmReceiver.TYPE_AUTOMATION)
            putExtra(JarvisAlarmReceiver.EXTRA_ID, rule.id)
            putExtra(JarvisAlarmReceiver.EXTRA_TITLE, rule.name)
            putExtra(JarvisAlarmReceiver.EXTRA_ACTION, rule.actionType)
            putExtra(JarvisAlarmReceiver.EXTRA_ACTION_PAYLOAD, rule.actionData)
            putExtra(JarvisAlarmReceiver.EXTRA_TRIGGER, rule.triggerType)
            putExtra(JarvisAlarmReceiver.EXTRA_CONDITIONS, rule.triggerValue)
        }

        val requestCode = (REQUEST_CODE_AUTOMATION_OFFSET + rule.id).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        setAlarmSafely(alarmManager, nextTrigger, pendingIntent)
        Log.i(TAG, "Scheduled automation rule '${rule.name}' (#${rule.id}) for $nextTrigger")

        // Persist nextRunAt
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = JarvisDatabase.getInstance(context)
                db.automationDao().updateRule(rule.copy(nextRunAt = nextTrigger, updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.w(TAG, "Could not update nextRunAt for rule #${rule.id}: ${e.message}")
            }
        }
    }

    fun cancelAutomationRule(context: Context, ruleId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, JarvisAlarmReceiver::class.java)
        val requestCode = (REQUEST_CODE_AUTOMATION_OFFSET + ruleId).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled automation rule alarm #$ruleId")
        }
    }

    /**
     * Reschedules all enabled automations and active reminders.
     * Called on reboot, time-change, timezone change, and package upgrade.
     */
    fun rescheduleAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = JarvisDatabase.getInstance(context)
                val enabledRules = db.automationDao().getEnabledRules()
                for (rule in enabledRules) {
                    scheduleAutomationRule(context, rule)
                }
                Log.i(TAG, "Successfully restored ${enabledRules.size} automation rules.")

                val now = System.currentTimeMillis()
                val activeReminders = db.reminderDao().getActiveReminders(now)
                for (reminder in activeReminders) {
                    scheduleReminder(context, reminder)
                }
                Log.i(TAG, "Successfully restored ${activeReminders.size} active reminders.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule routines: ${e.message}", e)
            }
        }
    }

    /**
     * Calculates the exact epoch milliseconds for the next trigger across:
     * - "DAILY", "TIME_DAILY", "DAILY_SCHEDULE"
     * - "WEEKLY", "WEEKLY_SCHEDULE"
     * - "MONTHLY", "MONTHLY_SCHEDULE"
     * - "YEARLY", "YEARLY_SCHEDULE"
     * - "INTERVAL"
     * - "ONE_TIME", "SCHEDULE"
     */
    fun calculateNextTriggerMillis(rule: AutomationRule, fromMillis: Long = System.currentTimeMillis()): Long? {
        val tz = TimeZone.getDefault()
        val scheduleStr = rule.triggerValue.trim()
        val triggerUpper = rule.triggerType.trim().uppercase()

        when {
            triggerUpper == "TIME_DAILY" || triggerUpper == "DAILY_SCHEDULE" || triggerUpper == "DAILY" -> {
                val (hour, minute) = parseHourMinute(scheduleStr)
                val targetCal = Calendar.getInstance(tz).apply {
                    timeInMillis = fromMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.timeInMillis <= fromMillis) {
                    targetCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return targetCal.timeInMillis
            }

            triggerUpper == "WEEKLY" || triggerUpper == "WEEKLY_SCHEDULE" -> {
                val (dayOfWeek, hour, minute) = parseWeeklyCondition(scheduleStr)
                val targetCal = Calendar.getInstance(tz).apply {
                    timeInMillis = fromMillis
                    set(Calendar.DAY_OF_WEEK, dayOfWeek)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.timeInMillis <= fromMillis) {
                    targetCal.add(Calendar.WEEK_OF_YEAR, 1)
                }
                return targetCal.timeInMillis
            }

            triggerUpper == "MONTHLY" || triggerUpper == "MONTHLY_SCHEDULE" -> {
                // Format: "15 09:00" -> day 15 at 09:00
                val parts = scheduleStr.split("\\s+".toRegex())
                val dayOfMonth = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 31) ?: 1
                val (h, m) = parseHourMinute(parts.getOrNull(1) ?: "09:00")
                val targetCal = Calendar.getInstance(tz).apply {
                    timeInMillis = fromMillis
                    set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, getActualMaximum(Calendar.DAY_OF_MONTH)))
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.timeInMillis <= fromMillis) {
                    targetCal.add(Calendar.MONTH, 1)
                    targetCal.set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, targetCal.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
                return targetCal.timeInMillis
            }

            triggerUpper == "YEARLY" || triggerUpper == "YEARLY_SCHEDULE" -> {
                // Format: "01-15 09:00" -> Jan 15 at 09:00
                val parts = scheduleStr.split("\\s+".toRegex())
                val datePart = parts.getOrNull(0) ?: "01-01"
                val (h, m) = parseHourMinute(parts.getOrNull(1) ?: "09:00")
                val dateTokens = datePart.split("-")
                val month = (dateTokens.getOrNull(0)?.toIntOrNull() ?: 1) - 1
                val day = dateTokens.getOrNull(1)?.toIntOrNull() ?: 1

                val targetCal = Calendar.getInstance(tz).apply {
                    timeInMillis = fromMillis
                    set(Calendar.MONTH, month.coerceIn(0, 11))
                    set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.timeInMillis <= fromMillis) {
                    targetCal.add(Calendar.YEAR, 1)
                }
                return targetCal.timeInMillis
            }

            triggerUpper.startsWith("INTERVAL") || scheduleStr.uppercase().startsWith("INTERVAL") -> {
                val digits = Regex("\\d+").find(if (triggerUpper.startsWith("INTERVAL")) triggerUpper else scheduleStr)?.value?.toLongOrNull() ?: 60L
                val intervalMillis = digits * 60 * 1000
                val last = rule.lastTriggeredAt ?: fromMillis
                var next = last + intervalMillis
                while (next <= fromMillis) {
                    next += intervalMillis
                }
                return next
            }

            triggerUpper == "ONE_TIME" || triggerUpper == "SCHEDULE" -> {
                val parsed = scheduleStr.toLongOrNull()
                return if (parsed != null) {
                    if (parsed > fromMillis) {
                        parsed
                    } else if (parsed in 1..10080) { // minutes offset
                        fromMillis + parsed * 60 * 1000
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            else -> return null
        }
    }

    fun parseHourMinute(conditions: String): Pair<Int, Int> {
        val trimmed = conditions.trim()
        if (trimmed.isBlank()) return Pair(9, 0)
        return if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
            val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            Pair(h, m)
        } else {
            val h = trimmed.toIntOrNull()?.coerceIn(0, 23) ?: 9
            Pair(h, 0)
        }
    }

    private fun parseWeeklyCondition(conditions: String): Triple<Int, Int, Int> {
        val parts = conditions.trim().split("\\s+".toRegex())
        val dayStr = parts.getOrNull(0)?.uppercase() ?: "MON"
        val timeStr = parts.getOrNull(1) ?: "09:00"

        val dayOfWeek = when {
            dayStr.startsWith("SUN") -> Calendar.SUNDAY
            dayStr.startsWith("MON") -> Calendar.MONDAY
            dayStr.startsWith("TUE") -> Calendar.TUESDAY
            dayStr.startsWith("WED") -> Calendar.WEDNESDAY
            dayStr.startsWith("THU") -> Calendar.THURSDAY
            dayStr.startsWith("FRI") -> Calendar.FRIDAY
            dayStr.startsWith("SAT") -> Calendar.SATURDAY
            else -> Calendar.MONDAY
        }

        val (h, m) = parseHourMinute(timeStr)
        return Triple(dayOfWeek, h, m)
    }

    private fun setAlarmSafely(alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission not granted, falling back to setAndAllowWhileIdle: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
    }
}
