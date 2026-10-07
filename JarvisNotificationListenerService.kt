package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Legitimate NotificationListenerService for JARVIS assistant.
 * Provides notification summaries when explicitly requested by user.
 * Respects privacy: does not upload notifications to remote clouds, does not store sensitive tokens.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "JarvisNotificationListenerService connected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        try {
            com.example.automation.whatsapp.WhatsAppAutoReplyEngine.getInstance(applicationContext)
                .handleNotification(sbn, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification for WhatsApp auto-reply: ${e.message}", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Reads and filters active notifications into sanitized summaries.
     */
    fun getActiveNotificationSummaries(limit: Int = 8): List<NotificationSummary> {
        return try {
            val active = activeNotifications ?: return emptyList()
            active.mapNotNull { sbn ->
                val pkg = sbn.packageName
                // Ignore self notifications
                if (pkg == packageName) return@mapNotNull null

                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""

                // Filter out empty or sensitive noise
                if (title.isBlank() && text.isBlank()) return@mapNotNull null

                val appName = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }

                NotificationSummary(
                    key = sbn.key,
                    appName = appName,
                    packageName = pkg,
                    title = title.take(60),
                    snippet = text.take(120),
                    postTime = sbn.postTime
                )
            }.sortedByDescending { it.postTime }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Dismisses a specific notification by its key or matching title/appName.
     */
    fun dismissNotification(keyOrQuery: String): Boolean {
        return try {
            val active = activeNotifications ?: return false
            val target = active.find { it.key == keyOrQuery }
                ?: active.find { sbn ->
                    val title = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: ""
                    title.contains(keyOrQuery, ignoreCase = true)
                }
            if (target != null) {
                cancelNotification(target.key)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Dismisses all clearable notifications.
     */
    fun dismissAllNotifications(): Boolean {
        return try {
            cancelAllNotifications()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Replies to an active notification if it contains a valid RemoteInput reply action.
     */
    fun replyToNotification(keyOrQuery: String, replyText: String): Pair<Boolean, String> {
        val active = activeNotifications ?: return false to "No active notifications found."
        val target = active.find { it.key == keyOrQuery }
            ?: active.find { sbn ->
                val title = sbn.notification.extras.getCharSequence("android.title")?.toString() ?: ""
                title.contains(keyOrQuery, ignoreCase = true)
            } ?: return false to "Notification matching '$keyOrQuery' was not found."

        val actions = target.notification.actions ?: return false to "Notification does not contain interactive actions."
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                val intent = android.content.Intent()
                val bundle = android.os.Bundle().apply {
                    putCharSequence(ri.resultKey, replyText)
                }
                android.app.RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
                return try {
                    action.actionIntent.send(this, 0, intent)
                    true to "Replied to notification from '${target.packageName}' with: '$replyText'"
                } catch (e: Exception) {
                    false to "Failed to dispatch notification reply: ${e.message}"
                }
            }
        }
        return false to "No reply action (RemoteInput) available for this notification."
    }

    companion object {
        private const val TAG = "JarvisNotificationListener"

        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun hasNotificationAccess(context: android.content.Context): Boolean {
            return try {
                val enabledPackages = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                enabledPackages.contains(context.packageName)
            } catch (_: Exception) {
                false
            }
        }
    }
}

data class NotificationSummary(
    val key: String = "",
    val appName: String,
    val packageName: String,
    val title: String,
    val snippet: String,
    val postTime: Long
)
