package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Real Android Telephony and Complete Call Lifecycle State Machine.
 * Tracks: DIALER_OPENED, CALL_INITIATED, RINGING, ACTIVE, DISCONNECTED, MISSED, FAILED,
 * PERMISSION_REQUIRED, USER_ACTION_REQUIRED.
 * Uses TelephonyCallback on API 31+ and PhoneStateListener on older versions.
 * Never claims "Call connected" without OS telephony confirmation.
 */
class TelephonyHelper(private val context: Context) {

    private val tag = "TelephonyHelper"

    enum class CallState {
        DIALER_OPENED,
        CALL_INITIATED,
        RINGING,
        ACTIVE,
        DISCONNECTED,
        MISSED,
        FAILED,
        PERMISSION_REQUIRED,
        USER_ACTION_REQUIRED
    }

    enum class CallLogStatus {
        CALL_LOG_SUCCESS,
        CALL_LOG_EMPTY,
        CALL_LOG_PERMISSION_REQUIRED,
        CALL_LOG_PERMISSION_DENIED,
        CALL_LOG_UNAVAILABLE,
        CALL_LOG_FAILED
    }

    data class CallLogEntry(
        val number: String,
        val cachedName: String?,
        val date: Long,
        val durationSeconds: Long,
        val type: String
    )

    data class CallLogResult(
        val status: CallLogStatus,
        val isSuccess: Boolean,
        val message: String,
        val entries: List<CallLogEntry> = emptyList(),
        val requiresSettings: Boolean = false
    )

    data class CallSession(
        var currentState: CallState = CallState.DISCONNECTED,
        var number: String = "",
        var callStartTime: Long? = null,
        var callEndTime: Long? = null,
        var durationSeconds: Long = 0,
        var disconnectReason: String? = null
    )

    data class CallResult(
        val state: CallState,
        val isSuccess: Boolean,
        val message: String,
        val number: String,
        val session: CallSession? = null
    )

    companion object {
        val activeSession = CallSession()
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    init {
        registerCallStateListener()
    }

    private fun registerCallStateListener() {
        val tm = telephonyManager ?: return
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneState) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallStateChange(state)
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                tm.listen(object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChange(state)
                    }
                }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            Log.w(tag, "SecurityException registering TelephonyCallback: ${e.message}")
        }
    }

    private fun handleCallStateChange(rawState: Int) {
        val now = System.currentTimeMillis()
        when (rawState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                activeSession.currentState = CallState.RINGING
                Log.i(tag, "Call State Transition -> RINGING")
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                activeSession.currentState = CallState.ACTIVE
                activeSession.callStartTime = now
                Log.i(tag, "Call State Transition -> ACTIVE")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val wasRinging = activeSession.currentState == CallState.RINGING
                val wasActive = activeSession.currentState == CallState.ACTIVE

                activeSession.callEndTime = now
                if (wasActive && activeSession.callStartTime != null) {
                    activeSession.durationSeconds = (now - activeSession.callStartTime!!) / 1000
                    activeSession.currentState = CallState.DISCONNECTED
                    activeSession.disconnectReason = "NORMAL_CLEARING"
                } else if (wasRinging) {
                    activeSession.currentState = CallState.MISSED
                    activeSession.disconnectReason = "NO_ANSWER_OR_REJECTED"
                } else {
                    activeSession.currentState = CallState.DISCONNECTED
                }
                Log.i(tag, "Call State Transition -> ${activeSession.currentState}")
            }
        }
    }

    fun getCallState(): Map<String, Any> {
        val tm = telephonyManager ?: return mapOf(
            "state" to "TELEPHONY_UNAVAILABLE",
            "message" to "Telephony service unavailable on this hardware."
        )

        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val rawState = if (hasReadPhoneState) {
            @Suppress("DEPRECATION")
            when (tm.callState) {
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING.name
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE.name
                TelephonyManager.CALL_STATE_IDLE -> CallState.DISCONNECTED.name
                else -> "IDLE"
            }
        } else {
            "PERMISSION_REQUIRED_FOR_REALTIME_STATE"
        }

        val duration = if (activeSession.currentState == CallState.ACTIVE && activeSession.callStartTime != null) {
            (System.currentTimeMillis() - activeSession.callStartTime!!) / 1000
        } else {
            activeSession.durationSeconds
        }

        return mapOf(
            "callState" to (if (activeSession.currentState != CallState.DISCONNECTED) activeSession.currentState.name else rawState),
            "trackedState" to activeSession.currentState.name,
            "hasReadPhoneStatePermission" to hasReadPhoneState,
            "simStateReady" to (tm.simState == TelephonyManager.SIM_STATE_READY),
            "networkOperatorName" to (tm.networkOperatorName ?: "Unknown"),
            "callStartTime" to (activeSession.callStartTime ?: 0L),
            "callEndTime" to (activeSession.callEndTime ?: 0L),
            "durationSeconds" to duration,
            "disconnectState" to (activeSession.disconnectReason ?: "NONE")
        )
    }

    fun dialOrCall(number: String, directCallRequested: Boolean = false): CallResult {
        val cleanNumber = number.replace("[^0-9+]".toRegex(), "")
        if (cleanNumber.isBlank()) {
            return CallResult(
                state = CallState.FAILED,
                isSuccess = false,
                message = "Invalid phone number provided.",
                number = number
            )
        }

        activeSession.number = cleanNumber
        activeSession.callStartTime = null
        activeSession.callEndTime = null
        activeSession.durationSeconds = 0

        val hasCallPhonePermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (directCallRequested && hasCallPhonePermission) {
            return try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
                activeSession.currentState = CallState.CALL_INITIATED
                CallResult(
                    state = CallState.CALL_INITIATED,
                    isSuccess = true,
                    message = "CALL_INITIATED: Call request dispatched to telecom subsystem for $cleanNumber. (Note: Awaiting network carrier pickup before connection).",
                    number = cleanNumber,
                    session = activeSession
                )
            } catch (e: Exception) {
                Log.w(tag, "ACTION_CALL failed, falling back to dialer: ${e.message}")
                launchDialer(cleanNumber)
            }
        } else {
            return launchDialer(cleanNumber)
        }
    }

    private fun launchDialer(number: String): CallResult {
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            activeSession.currentState = CallState.DIALER_OPENED
            CallResult(
                state = CallState.DIALER_OPENED,
                isSuccess = true,
                message = "DIALER_OPENED: Phone dialer opened with number $number. Press Call to initiate.",
                number = number,
                session = activeSession
            )
        } catch (e: Exception) {
            CallResult(
                state = CallState.FAILED,
                isSuccess = false,
                message = "Failed to launch phone dialer: ${e.message}",
                number = number
            )
        }
    }

    /**
     * Reads call history with full state machine and error handling conforming to MASTER REQUIREMENT 1.
     * Returns explicit states: CALL_LOG_SUCCESS, CALL_LOG_EMPTY, CALL_LOG_PERMISSION_REQUIRED,
     * CALL_LOG_PERMISSION_DENIED, CALL_LOG_UNAVAILABLE, CALL_LOG_FAILED.
     */
    fun getCallHistory(limit: Int = 10, isPermanentlyDenied: Boolean = false): CallLogResult {
        val hasLogPerm = try {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        if (!hasLogPerm) {
            return if (isPermanentlyDenied) {
                CallLogResult(
                    status = CallLogStatus.CALL_LOG_PERMISSION_DENIED,
                    isSuccess = false,
                    message = "READ_CALL_LOG permission was permanently denied. Please open Android App Settings to enable Call Log access.",
                    requiresSettings = true
                )
            } else {
                CallLogResult(
                    status = CallLogStatus.CALL_LOG_PERMISSION_REQUIRED,
                    isSuccess = false,
                    message = "READ_CALL_LOG runtime permission is required to access call history.",
                    requiresSettings = false
                )
            }
        }

        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            ) ?: return CallLogResult(
                status = CallLogStatus.CALL_LOG_UNAVAILABLE,
                isSuccess = false,
                message = "CallLog provider is unavailable on this device."
            )

            cursor.use {
                val numCol = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameCol = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateCol = it.getColumnIndex(CallLog.Calls.DATE)
                val durCol = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeCol = it.getColumnIndex(CallLog.Calls.TYPE)

                while (it.moveToNext()) {
                    val num = if (numCol != -1) it.getString(numCol) ?: "Unknown" else "Unknown"
                    val name = if (nameCol != -1) it.getString(nameCol) else null
                    val date = if (dateCol != -1) it.getLong(dateCol) else 0L
                    val dur = if (durCol != -1) it.getLong(durCol) else 0L
                    val rawType = if (typeCol != -1) it.getInt(typeCol) else -1
                    val typeStr = when (rawType) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
                        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
                        else -> "OTHER"
                    }
                    entries.add(
                        CallLogEntry(
                            number = num,
                            cachedName = name,
                            date = date,
                            durationSeconds = dur,
                            type = typeStr
                        )
                    )
                }
            }

            return if (entries.isEmpty()) {
                CallLogResult(
                    status = CallLogStatus.CALL_LOG_EMPTY,
                    isSuccess = true,
                    message = "No call log entries found on device.",
                    entries = emptyList()
                )
            } else {
                CallLogResult(
                    status = CallLogStatus.CALL_LOG_SUCCESS,
                    isSuccess = true,
                    message = "Retrieved ${entries.size} call log entries.",
                    entries = entries
                )
            }
        } catch (se: SecurityException) {
            Log.e(tag, "SecurityException querying CallLog: ${se.message}", se)
            return CallLogResult(
                status = CallLogStatus.CALL_LOG_PERMISSION_DENIED,
                isSuccess = false,
                message = "Access to call log was denied by Android security manager: ${se.message}"
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to query CallLog: ${e.message}", e)
            return CallLogResult(
                status = CallLogStatus.CALL_LOG_FAILED,
                isSuccess = false,
                message = "Failed to query call log: ${e.message}"
            )
        }
    }

    /**
     * Reads recent call logs if READ_CALL_LOG permission is granted.
     */
    fun getRecentCallLog(limit: Int = 10): List<Map<String, Any>> {
        val result = getCallHistory(limit)
        if (!result.isSuccess || result.status != CallLogStatus.CALL_LOG_SUCCESS) {
            return listOf(
                mapOf(
                    "status" to result.status.name,
                    "message" to result.message
                )
            )
        }
        return result.entries.map { entry ->
            mapOf(
                "number" to entry.number,
                "cachedName" to (entry.cachedName ?: ""),
                "date" to entry.date,
                "durationSeconds" to entry.durationSeconds,
                "type" to entry.type
            )
        }
    }
}
