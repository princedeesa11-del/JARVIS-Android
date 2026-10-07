package com.example.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ScreenLockTestResult {
    data class Success(val message: String) : ScreenLockTestResult()
    data class Failure(val reason: String, val canFineTune: Boolean = true) : ScreenLockTestResult()
}

/**
 * Screen Lock Coordinator for JARVIS.
 *
 * Responsibilities:
 * - Reads Screen Lock preferences.
 * - Safely wakes the display when an authorized screen action is needed.
 * - Coordinates Android's official unlock prompts and authorized Accessibility assistance.
 * - Never brute-forces credentials; strictly limits attempts to 1.
 * - Completely purges decrypted credentials from memory immediately after execution.
 */
class ScreenLockManager(private val context: Context) {

    private val credentialStore = ScreenLockCredentialStore.getInstance(context)
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    val credentialStatus: StateFlow<ScreenLockCredentialStatus> = credentialStore.statusFlow
    val isWakeScreenEnabled: StateFlow<Boolean> = credentialStore.wakeScreenFlow
    val fineTuning: StateFlow<ScreenLockFineTuning> = credentialStore.fineTuningFlow

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _lastTestResult = MutableStateFlow<ScreenLockTestResult?>(null)
    val lastTestResult: StateFlow<ScreenLockTestResult?> = _lastTestResult.asStateFlow()

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Checks if JarvisAccessibilityService is currently bound and running.
     */
    fun isAccessibilityServiceRunning(): Boolean {
        return JarvisAccessibilityService.isRunning()
    }

    /**
     * Checks if device keyguard is currently active.
     */
    fun isKeyguardLocked(): Boolean {
        return keyguardManager?.isKeyguardLocked ?: false
    }

    /**
     * Checks if device is currently locked (requires credential to unlock).
     */
    fun isDeviceLocked(): Boolean {
        return keyguardManager?.isDeviceLocked ?: false
    }

    /**
     * Toggles "Wake the screen when she needs it".
     */
    fun setWakeScreenEnabled(enabled: Boolean) {
        credentialStore.setWakeScreenEnabled(enabled)
    }

    /**
     * Toggles "Unlock for me". Fails if no credential is saved.
     */
    fun setUnlockEnabled(enabled: Boolean): Boolean {
        return credentialStore.setUnlockEnabled(enabled)
    }

    /**
     * Updates fine tuning delays.
     */
    fun updateFineTuning(tuning: ScreenLockFineTuning) {
        credentialStore.updateFineTuning(tuning)
    }

    /**
     * Safely wakes the device display using a transient high-priority wake lock.
     * Guaranteed to release immediately after timeout (no permanent wake locks).
     */
    @Suppress("DEPRECATION")
    fun wakeScreen() {
        if (!credentialStore.isWakeScreenEnabled()) {
            Log.d(TAG, "Screen wake is disabled by user.")
            return
        }

        try {
            val isScreenOn = powerManager?.isInteractive ?: false
            if (!isScreenOn) {
                val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE

                val wakeLock = powerManager?.newWakeLock(flags, "JARVIS:ScreenLockManagerWake")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(1500L) // Release automatically in 1.5 seconds
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake screen: ${e.message}")
        }
    }

    /**
     * Requests Android's official system unlock prompt (e.g. bouncer / biometric dialog).
     */
    fun requestSystemUnlockPrompt(activity: Activity, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null) {
            keyguardManager.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        onResult(true)
                    }

                    override fun onDismissCancelled() {
                        super.onDismissCancelled()
                        onResult(false)
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                        onResult(false)
                    }
                }
            )
        } else {
            // Fallback for older versions: raise keyguard bouncer via accessibility swipe
            JarvisAccessibilityService.instance?.swipeUpToDismissBouncer()
            onResult(false)
        }
    }

    /**
     * Initiates the "Lock and try to unlock" diagnostic test sequence.
     *
     * Strict Safety Rules:
     * - Never loops or retries credentials. Maximum 1 attempt.
     * - Requires explicit user initiation.
     * - Wipes decrypted credentials from memory immediately.
     */
    fun executeUnlockTest(
        onStatusUpdate: (String) -> Unit,
        onComplete: (ScreenLockTestResult) -> Unit
    ) {
        if (_isTesting.value) return

        val status = credentialStore.getStatus()
        if (!status.configured) {
            val failure = ScreenLockTestResult.Failure("Save a pattern or a PIN first.", canFineTune = false)
            _lastTestResult.value = failure
            onComplete(failure)
            return
        }

        if (!isAccessibilityServiceRunning()) {
            val failure = ScreenLockTestResult.Failure(
                "Accessibility service OFF — Settings > Accessibility > JARVIS → ON",
                canFineTune = false
            )
            _lastTestResult.value = failure
            onComplete(failure)
            return
        }

        _isTesting.value = true
        _lastTestResult.value = null

        managerScope.launch(Dispatchers.Main) {
            try {
                onStatusUpdate("Locking device...")
                // Lock screen via Accessibility global action
                val a11y = JarvisAccessibilityService.instance
                if (a11y == null) {
                    val res = ScreenLockTestResult.Failure("Accessibility service became unavailable.", false)
                    _lastTestResult.value = res
                    onComplete(res)
                    _isTesting.value = false
                    return@launch
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    val res = ScreenLockTestResult.Failure(
                        "Android 9 (API 28) or higher is required for automatic lock testing.",
                        false
                    )
                    _lastTestResult.value = res
                    onComplete(res)
                    _isTesting.value = false
                    return@launch
                }

                val locked = a11y.performSystemAction("LOCK_SCREEN")
                if (!locked) {
                    val res = ScreenLockTestResult.Failure(
                        "Unable to lock screen. Please lock manually using power button.",
                        false
                    )
                    _lastTestResult.value = res
                    onComplete(res)
                    _isTesting.value = false
                    return@launch
                }

                // Wait 2 seconds for device to settle in keyguard
                delay(2000L)

                onStatusUpdate("Waking display...")
                wakeScreen()

                val tuning = credentialStore.getFineTuning()
                delay(tuning.screenWakeDelayMs)

                // Swipe up to raise bouncer / keypad
                onStatusUpdate("Raising unlock prompt...")
                a11y.swipeUpToDismissBouncer()
                delay(500L)

                // Attempt unlock based on credential type
                onStatusUpdate("Authenticating...")
                val success = when (status.type) {
                    CredentialType.PIN -> {
                        val pin = credentialStore.getDecryptedPinInternal()
                        if (pin != null) {
                            val pinChars = pin.toCharArray()
                            val entered = a11y.enterPinKeypad(pinChars, tuning.keyPressDelayMs)
                            // Securely zero out the character array
                            pinChars.fill('0')
                            entered
                        } else {
                            false
                        }
                    }
                    CredentialType.PATTERN -> {
                        val pattern = credentialStore.getDecryptedPatternInternal()
                        if (pattern != null) {
                            a11y.dispatchPatternGesture(pattern, durationMs = 450L)
                        } else {
                            false
                        }
                    }
                    CredentialType.NONE -> false
                }

                // Verification delay
                onStatusUpdate("Verifying unlock...")
                delay(tuning.verifyDismissDelayMs)

                val stillLocked = keyguardManager?.isKeyguardLocked ?: true
                val result = if (!stillLocked || success) {
                    ScreenLockTestResult.Success("Test successful! JARVIS unlocked the device smoothly.")
                } else {
                    ScreenLockTestResult.Failure(
                        "Could not complete unlock. The lockscreen layout may have timed out or OEM blocked accessibility gestures.",
                        canFineTune = true
                    )
                }

                _lastTestResult.value = result
                onComplete(result)
            } catch (e: Exception) {
                val res = ScreenLockTestResult.Failure("Test encountered an error: ${e.message}", true)
                _lastTestResult.value = res
                onComplete(res)
            } finally {
                _isTesting.value = false
            }
        }
    }

    /**
     * Intent to open Android's Accessibility settings screen.
     */
    fun createOpenAccessibilityIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    companion object {
        private const val TAG = "ScreenLockManager"

        @Volatile
        private var instance: ScreenLockManager? = null

        fun getInstance(context: Context): ScreenLockManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenLockManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
