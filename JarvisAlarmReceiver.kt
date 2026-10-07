package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.local.JarvisDatabase
import com.example.data.local.entity.AutomationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisAlarmReceiver : BroadcastReceiver() {

    private val tag = "JarvisAlarmReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_REMINDER
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "JARVIS Alert"

        Log.i(tag, "Received alarm trigger: type=$type, id=$id, title=$title")

        when (type) {
            TYPE_REMINDER -> handleReminder(context, id, title)
            TYPE_AUTOMATION -> handleAutomation(context, intent, id, title)
        }
    }

    private fun handleReminder(context: Context, reminderId: Long, title: String) {
        val channelId = "jarvis_reminders_channel"
        createNotificationChannel(context, channelId, "JARVIS Reminders & Alerts")

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            reminderId.toInt(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("JARVIS Directive")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(reminderId.toInt(), notification)

        // Mark reminder as completed in Room
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = JarvisDatabase.getInstance(context)
                db.reminderDao().markCompleted(reminderId)
            } catch (e: Exception) {
                Log.e(tag, "Failed to mark reminder completed: ${e.message}")
            }
        }
    }

    private fun handleAutomation(context: Context, intent: Intent, ruleId: Long, name: String) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: "SEND_NOTIFICATION"
        val payload = intent.getStringExtra(EXTRA_ACTION_PAYLOAD) ?: ""
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "DAILY_SCHEDULE"
        val conditions = intent.getStringExtra(EXTRA_CONDITIONS) ?: ""

        // Asynchronously execute via AutomationEngine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = JarvisDatabase.getInstance(context)
                val repository = com.example.data.repository.JarvisRepository(db)
                val toolExecutor = com.example.tools.ToolExecutor(context, repository)
                val engine = com.example.automation.AutomationEngine(context, repository, toolExecutor)

                val existingRule = db.automationDao().getRuleById(ruleId)
                val ruleToRun = existingRule ?: AutomationRule(
                    id = ruleId,
                    name = name,
                    enabled = true,
                    trigger = trigger,
                    schedule = conditions,
                    action = action,
                    arguments = payload,
                    conditions = conditions,
                    actions = action,
                    actionPayload = payload
                )

                val resultState = engine.executeRule(ruleToRun)
                Log.i(tag, "Automation routine '$name' (#$ruleId) finished with state: $resultState")
            } catch (e: Exception) {
                Log.e(tag, "Failed to execute automation routine via engine: ${e.message}", e)
            }
        }
    }

    private fun createNotificationChannel(context: Context, channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "System notifications and alarms from JARVIS"
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_ACTION_PAYLOAD = "extra_action_payload"
        const val EXTRA_TRIGGER = "extra_trigger"
        const val EXTRA_CONDITIONS = "extra_conditions"

        const val TYPE_REMINDER = "REMINDER"
        const val TYPE_AUTOMATION = "AUTOMATION"
    }
}
