package com.example.whatsapp.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whatsapp.client.WhatsAppApiResult
import com.example.whatsapp.client.WhatsAppCloudApiClient
import com.example.whatsapp.config.WhatsAppConfigStore
import com.example.whatsapp.data.WhatsAppAuditLogEntity
import com.example.whatsapp.data.WhatsAppDao
import com.example.whatsapp.data.WhatsAppMessageEntity
import com.example.whatsapp.data.WhatsAppScheduledMessageEntity
import com.example.whatsapp.model.WhatsAppMessageDirection
import com.example.whatsapp.model.WhatsAppMessageStatus
import com.example.whatsapp.model.WhatsAppRepeatInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Android AlarmManager-backed WhatsApp scheduler.
 * Executes scheduled messages at exact timestamps without relying on UI/browser timers.
 */
object WhatsAppSchedulerHelper {

    private const val TAG = "WhatsAppSchedulerHelper"
    private const val ACTION_SEND_SCHEDULED_WHATSAPP = "com.example.whatsapp.ACTION_SEND_SCHEDULED"
    private const val EXTRA_SCHEDULED_ID = "extra_scheduled_id"
    private const val REQUEST_CODE_OFFSET = 500_000

    fun scheduleMessage(context: Context, item: WhatsAppScheduledMessageEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (item.scheduledTimeMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "Scheduled time is in the past. Executing immediately.")
            triggerScheduledExecution(context, item.id)
            return
        }

        val intent = Intent(context, WhatsAppScheduledBroadcastReceiver::class.java).apply {
            action = ACTION_SEND_SCHEDULED_WHATSAPP
            putExtra(EXTRA_SCHEDULED_ID, item.id)
        }

        val requestCode = (REQUEST_CODE_OFFSET + item.id).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.scheduledTimeMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, item.scheduledTimeMillis, pendingIntent)
            }
            Log.i(TAG, "Scheduled WhatsApp message #${item.id} to ${item.recipientNumber} for ${item.scheduledTimeMillis}")
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, item.scheduledTimeMillis, pendingIntent)
            Log.w(TAG, "Exact alarm permission fallback: ${e.message}")
        }
    }

    fun cancelSchedule(context: Context, id: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WhatsAppScheduledBroadcastReceiver::class.java).apply {
            action = ACTION_SEND_SCHEDULED_WHATSAPP
            putExtra(EXTRA_SCHEDULED_ID, id)
        }
        val requestCode = (REQUEST_CODE_OFFSET + id).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Cancelled scheduled WhatsApp message #$id")
    }

    fun triggerScheduledExecution(context: Context, scheduledId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = com.example.data.local.JarvisDatabase.getInstance(context)
            val dao = db.whatsAppDao()
            val configStore = WhatsAppConfigStore.getInstance(context)
            val apiClient = WhatsAppCloudApiClient(configStore)

            val item = dao.getScheduledMessageById(scheduledId) ?: return@launch
            if (!item.enabled) {
                Log.d(TAG, "Scheduled message #$scheduledId is disabled; skipping.")
                return@launch
            }

            if (!configStore.isScheduledMessagesEnabled.value) {
                Log.d(TAG, "Scheduled messages are globally disabled; skipping execution.")
                return@launch
            }

            // Prevent duplicate sends for one-time messages
            if (item.repeatInterval == WhatsAppRepeatInterval.ONCE.name &&
                item.deliveryStatus == WhatsAppMessageStatus.SENT.name
            ) {
                Log.d(TAG, "Scheduled message #$scheduledId was already delivered; skipping duplicate.")
                return@launch
            }

            Log.i(TAG, "Executing scheduled WhatsApp message #$scheduledId to ${item.recipientNumber}...")

            // Send via official API
            val result = apiClient.sendTextMessage(item.recipientNumber, item.messageText)

            when (result) {
                is WhatsAppApiResult.Success -> {
                    // Record in message history
                    val messageEntity = WhatsAppMessageEntity(
                        whatsappMessageId = result.data.messageId,
                        senderOrRecipientNumber = item.recipientNumber,
                        contactName = item.recipientName,
                        text = item.messageText,
                        direction = WhatsAppMessageDirection.OUTGOING.name,
                        status = WhatsAppMessageStatus.SENT.name,
                        isAiReply = false,
                        timestamp = System.currentTimeMillis()
                    )
                    dao.insertMessage(messageEntity)

                    // Update or reschedule
                    if (item.repeatInterval == WhatsAppRepeatInterval.ONCE.name) {
                        dao.updateScheduledMessage(
                            item.copy(
                                deliveryStatus = WhatsAppMessageStatus.SENT.name,
                                lastRunTimestamp = System.currentTimeMillis()
                            )
                        )
                    } else {
                        val nextTime = calculateNextTriggerTime(item.scheduledTimeMillis, item.repeatInterval)
                        dao.updateScheduledMessage(
                            item.copy(
                                scheduledTimeMillis = nextTime,
                                lastRunTimestamp = System.currentTimeMillis()
                            )
                        )
                        scheduleMessage(context, item.copy(scheduledTimeMillis = nextTime))
                    }

                    dao.insertAuditLog(
                        WhatsAppAuditLogEntity(
                            actionType = "SCHEDULED_MESSAGE_SENT",
                            details = "Delivered scheduled message to ${item.recipientNumber}",
                            targetNumber = item.recipientNumber,
                            status = "SUCCESS"
                        )
                    )
                }
                is WhatsAppApiResult.Error -> {
                    Log.e(TAG, "Scheduled dispatch failed: ${result.message} - ${result.details}")
                    dao.updateScheduledMessage(
                        item.copy(
                            deliveryStatus = WhatsAppMessageStatus.FAILED.name,
                            errorMessage = result.details
                        )
                    )
                    dao.insertAuditLog(
                        WhatsAppAuditLogEntity(
                            actionType = "SCHEDULED_MESSAGE_FAILED",
                            details = "${result.message}: ${result.details}",
                            targetNumber = item.recipientNumber,
                            status = "FAILED"
                        )
                    )
                }
            }
        }
    }

    private fun calculateNextTriggerTime(previous: Long, interval: String): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = previous }
        when (interval) {
            WhatsAppRepeatInterval.DAILY.name -> cal.add(Calendar.DAY_OF_YEAR, 1)
            WhatsAppRepeatInterval.WEEKLY.name -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            WhatsAppRepeatInterval.MONTHLY.name -> cal.add(Calendar.MONTH, 1)
            else -> cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}

/**
 * BroadcastReceiver for scheduled WhatsApp messages.
 */
class WhatsAppScheduledBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("extra_scheduled_id", -1L)
        if (id != -1L) {
            WhatsAppSchedulerHelper.triggerScheduledExecution(context, id)
        }
    }
}
