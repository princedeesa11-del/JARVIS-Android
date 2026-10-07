package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Real Android SMS and Messaging management helper.
 * Strictly adheres to Android telephony and permissions.
 * Never claims an SMS was sent unless direct SmsManager execution succeeds.
 */
class SmsManagerHelper(private val context: Context) {

    enum class SmsStatus {
        SMS_SENT,
        SMS_FAILED,
        SMS_PERMISSION_REQUIRED,
        SMS_DRAFT_ONLY,
        SMS_COMPOSER_OPENED
    }

    data class SmsResult(
        val status: SmsStatus,
        val isSuccess: Boolean,
        val message: String,
        val recipients: List<String>,
        val directSend: Boolean
    )

    fun getSmsCapability(): Map<String, Any> {
        val pm = context.packageManager
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simState = telephonyManager?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        val hasSim = simState == TelephonyManager.SIM_STATE_READY

        return mapOf(
            "hasTelephonyHardware" to hasTelephony,
            "hasSimCardReady" to hasSim,
            "hasSendSmsPermission" to hasSmsPermission,
            "canSendDirectSms" to (hasTelephony && hasSmsPermission && hasSim),
            "supportsComposerFallback" to true
        )
    }

    /**
     * Direct SMS dispatch using SmsManager with recipient and message validation.
     */
    fun sendDirectSms(recipientsInput: String, messageText: String): SmsResult {
        if (messageText.isBlank()) {
            return SmsResult(
                status = SmsStatus.SMS_FAILED,
                isSuccess = false,
                message = "Message text cannot be empty.",
                recipients = emptyList(),
                directSend = false
            )
        }

        val recipients = parseRecipients(recipientsInput)
        if (recipients.isEmpty()) {
            return SmsResult(
                status = SmsStatus.SMS_FAILED,
                isSuccess = false,
                message = "No valid recipient phone number provided.",
                recipients = emptyList(),
                directSend = false
            )
        }

        // Permission check
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return SmsResult(
                status = SmsStatus.SMS_PERMISSION_REQUIRED,
                isSuccess = false,
                message = "Permission SEND_SMS is not granted. Please grant permission in Settings or use SMS composer.",
                recipients = recipients,
                directSend = false
            )
        }

        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val failedRecipients = mutableListOf<String>()
        val succeededRecipients = mutableListOf<String>()

        for (number in recipients) {
            try {
                if (messageText.length > 160) {
                    val parts = smsManager.divideMessage(messageText)
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(number, null, messageText, null, null)
                }
                succeededRecipients.add(number)
            } catch (e: Exception) {
                failedRecipients.add("$number (${e.localizedMessage ?: "send error"})")
            }
        }

        return if (failedRecipients.isEmpty()) {
            SmsResult(
                status = SmsStatus.SMS_SENT,
                isSuccess = true,
                message = "SMS dispatched to: ${succeededRecipients.joinToString(", ")}.",
                recipients = succeededRecipients,
                directSend = true
            )
        } else if (succeededRecipients.isNotEmpty()) {
            SmsResult(
                status = SmsStatus.SMS_SENT,
                isSuccess = true,
                message = "SMS sent to ${succeededRecipients.joinToString(", ")}, but failed for ${failedRecipients.joinToString(", ")}.",
                recipients = succeededRecipients,
                directSend = true
            )
        } else {
            SmsResult(
                status = SmsStatus.SMS_FAILED,
                isSuccess = false,
                message = "Failed to dispatch SMS: ${failedRecipients.joinToString("; ")}.",
                recipients = failedRecipients,
                directSend = true
            )
        }
    }

    /**
     * Prepares SMS in the Android system composer.
     */
    fun prepareSmsInComposer(recipientsInput: String, messageText: String): SmsResult {
        val recipients = parseRecipients(recipientsInput)
        val cleanNumbers = recipients.joinToString(";")
        val uri = if (cleanNumbers.isNotBlank()) Uri.parse("smsto:$cleanNumbers") else Uri.parse("smsto:")

        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", messageText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(intent)
            SmsResult(
                status = SmsStatus.SMS_COMPOSER_OPENED,
                isSuccess = true,
                message = "SMS composer opened. User manual send dispatch is required.",
                recipients = recipients,
                directSend = false
            )
        } catch (e: Exception) {
            SmsResult(
                status = SmsStatus.SMS_FAILED,
                isSuccess = false,
                message = "Failed to launch SMS composer: ${e.message}",
                recipients = recipients,
                directSend = false
            )
        }
    }

    private fun parseRecipients(input: String): List<String> {
        val rawList = input.split(Regex("[,;\\n]+"))
        return rawList.map { it.trim().replace("[^0-9+]".toRegex(), "") }.filter { it.isNotBlank() }
    }
}
