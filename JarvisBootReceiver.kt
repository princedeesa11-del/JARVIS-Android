package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Reboot & Time Recovery Broadcast Receiver.
 * Reschedules all enabled alarms, reminders, and automation rules upon device restart,
 * app update, or system time/timezone shifts.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    private val tag = "JarvisBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            Log.i(tag, "System trigger detected ($action). Restoring all persistent scheduled alarms & automations...")
            JarvisAlarmScheduler.rescheduleAll(context)

            // Touch Guard reboot recovery
            try {
                val touchGuardPrefs = com.example.touchguard.TouchGuardPreferences.getInstance(context)
                if (touchGuardPrefs.isTouchGuardEnabled.value) {
                    Log.i(tag, "Restoring JARVIS Touch Guard service after device reboot...")
                    com.example.touchguard.TouchGuardService.start(context)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error restoring Touch Guard upon reboot: ${e.message}")
            }

            // WhatsApp Scheduled Messages recovery
            try {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val db = com.example.data.local.JarvisDatabase.getInstance(context)
                    val pending = db.whatsAppDao().getPendingScheduledMessages(Long.MAX_VALUE)
                    for (item in pending) {
                        com.example.whatsapp.service.WhatsAppSchedulerHelper.scheduleMessage(context, item)
                    }
                    Log.i(tag, "Restored ${pending.size} scheduled WhatsApp messages.")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error restoring WhatsApp scheduled messages: ${e.message}")
            }
        }
    }
}
