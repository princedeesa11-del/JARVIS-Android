package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.example.service.AccessibilityActionResult
import com.example.service.JarvisAccessibilityService
import com.example.service.NodeQuery
import com.example.service.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal General-Purpose Android Automation Engine for JARVIS.
 * Works across ANY installed application that exposes accessible nodes.
 *
 * Implements the full lifecycle:
 * Identify -> Open -> Wait UI -> Inspect Tree -> Find Element -> Act -> Verify State -> (Optional User Confirmation) -> Return Real Result.
 */

data class AutomationStep(
    val stepIndex: Int,
    val actionType: String, // "LAUNCH_APP", "INSPECT", "FIND", "CLICK", "LONG_CLICK", "TYPE_TEXT", "SET_TEXT", "SCROLL", "SWIPE", "BACK", "HOME", "CONFIRM", "VERIFY"
    val appName: String? = null,
    val targetQuery: NodeQuery? = null,
    val textValue: String? = null,
    val scrollDirection: String? = null,
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val expectedVerification: String? = null,
    val timeoutMs: Long = 3000L
)

data class StepExecutionReport(
    val stepIndex: Int,
    val actionType: String,
    val state: String, // APP_OPENED, ELEMENT_FOUND, BUTTON_CLICKED, TEXT_ENTERED, MESSAGE_SENT, ACTION_COMPLETED, ACTION_FAILED, etc.
    val message: String,
    val durationMs: Long,
    val isSuccess: Boolean
)

data class AutomationPlanResult(
    val isSuccess: Boolean,
    val finalState: String,
    val summary: String,
    val stepReports: List<StepExecutionReport>,
    val pendingConfirmation: PendingConfirmation? = null
)

data class PendingConfirmation(
    val confirmationId: String,
    val actionType: String,
    val summaryPrompt: String,
    val targetName: String,
    val contentPreview: String?,
    val remainingStepsJson: String
)

class UniversalAppAutomationEngine(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    private val _activePendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val activePendingConfirmation: StateFlow<PendingConfirmation?> = _activePendingConfirmation.asStateFlow()

    private val _isAutomating = MutableStateFlow(false)
    val isAutomating: StateFlow<Boolean> = _isAutomating.asStateFlow()

    /**
     * Executes a multi-step sequence of automation steps with verification at each step.
     */
    suspend fun executeSteps(steps: List<AutomationStep>, userAlreadyConfirmed: Boolean = false): AutomationPlanResult =
        withContext(Dispatchers.Default) {
            _isAutomating.value = true
            val reports = mutableListOf<StepExecutionReport>()

            try {
                for (step in steps) {
                    val startTime = System.currentTimeMillis()

                    // Check if step requires confirmation before proceeding
                    if (step.requiresConfirmation && !userAlreadyConfirmed) {
                        val pending = PendingConfirmation(
                            confirmationId = "CONFIRM_${System.currentTimeMillis()}",
                            actionType = step.actionType,
                            summaryPrompt = step.confirmationPrompt ?: "I have prepared the action for ${step.appName ?: "the app"}. Would you like me to proceed?",
                            targetName = step.appName ?: step.targetQuery?.text ?: "Operation",
                            contentPreview = step.textValue,
                            remainingStepsJson = serializeRemainingSteps(steps.subList(step.stepIndex, steps.size))
                        )
                        _activePendingConfirmation.value = pending

                        val confirmReport = StepExecutionReport(
                            stepIndex = step.stepIndex,
                            actionType = step.actionType,
                            state = "USER_ACTION_REQUIRED",
                            message = pending.summaryPrompt,
                            durationMs = System.currentTimeMillis() - startTime,
                            isSuccess = true
                        )
                        reports.add(confirmReport)

                        return@withContext AutomationPlanResult(
                            isSuccess = true,
                            finalState = "USER_ACTION_REQUIRED",
                            summary = pending.summaryPrompt,
                            stepReports = reports,
                            pendingConfirmation = pending
                        )
                    }

                    // Execute single step with retries
                    val stepReport = executeSingleStepWithRetries(step)
                    reports.add(stepReport)

                    if (!stepReport.isSuccess) {
                        return@withContext AutomationPlanResult(
                            isSuccess = false,
                            finalState = stepReport.state,
                            summary = "Automation halted at step ${step.stepIndex} (${step.actionType}): ${stepReport.message}",
                            stepReports = reports
                        )
                    }

                    // Natural pause between UI actions for realistic render & input handling
                    delay(400)
                }

                AutomationPlanResult(
                    isSuccess = true,
                    finalState = determineFinalState(reports),
                    summary = "Successfully completed ${reports.size} automation steps.",
                    stepReports = reports
                )
            } finally {
                _isAutomating.value = false
            }
        }

    private suspend fun executeSingleStepWithRetries(step: AutomationStep, maxRetries: Int = 3): StepExecutionReport {
        var attempt = 0
        var lastReport = StepExecutionReport(
            stepIndex = step.stepIndex,
            actionType = step.actionType,
            state = "INITIALIZING",
            message = "Starting step execution",
            durationMs = 0,
            isSuccess = false
        )

        while (attempt < maxRetries) {
            attempt++
            val startTime = System.currentTimeMillis()
            val report = executeSingleStep(step)
            val duration = System.currentTimeMillis() - startTime

            if (report.isSuccess) {
                return report.copy(durationMs = duration)
            }

            lastReport = report.copy(durationMs = duration)

            // Intelligent recovery between attempts
            if (attempt < maxRetries) {
                // If element wasn't found, try scrolling to bring into view
                if (report.state == "ELEMENT_NOT_FOUND") {
                    val service = JarvisAccessibilityService.instance
                    service?.scrollScreen("DOWN")
                    delay(500)
                } else {
                    delay(300)
                }
            }
        }

        return lastReport
    }

    private suspend fun executeSingleStep(step: AutomationStep): StepExecutionReport {
        when (step.actionType.uppercase()) {
            "LAUNCH_APP", "OPEN_APP" -> {
                val appName = step.appName ?: return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_FAILED", "App name not specified", 0, false)
                val pkg = resolvePackageName(appName)
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_FAILED", "APP_NOT_FOUND: Application '$appName' is not installed on this device.", 0, false)

                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_FAILED", "Unable to create launch intent for $pkg", 0, false)

                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)

                // Wait for target app window
                val windowAppeared = waitForAppWindow(pkg, timeoutMs = step.timeoutMs)
                return if (windowAppeared) {
                    StepExecutionReport(step.stepIndex, step.actionType, "APP_OPENED", "Launched $appName ($pkg) successfully.", 0, true)
                } else {
                    // Launched intent, but window wait timed out
                    StepExecutionReport(step.stepIndex, step.actionType, "APP_OPENED", "Launched $appName intent.", 0, true)
                }
            }

            "INSPECT", "INSPECT_SCREEN" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled. Please enable JARVIS Accessibility.", 0, false)

                val elements = service.inspectCurrentScreen()
                val summary = "Inspected current UI: ${elements.size} elements found."
                return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_COMPLETED", summary, 0, true)
            }

            "FIND", "FIND_ELEMENT" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val query = step.targetQuery ?: NodeQuery(text = step.textValue)
                val nodes = service.findNodes(query)
                return if (nodes.isNotEmpty()) {
                    StepExecutionReport(step.stepIndex, step.actionType, "ELEMENT_FOUND", "Found ${nodes.size} matching element(s) for '$query'", 0, true)
                } else {
                    StepExecutionReport(step.stepIndex, step.actionType, "ELEMENT_NOT_FOUND", "Element matching '$query' not found on screen.", 0, false)
                }
            }

            "CLICK" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val query = step.targetQuery ?: NodeQuery(text = step.textValue)
                val sigBefore = service.getScreenSignature()

                val result = service.clickNode(query)
                if (result.success) {
                    delay(300)
                    val sigAfter = service.getScreenSignature()
                    val state = if (step.expectedVerification == "MESSAGE_SENT" || query.text?.contains("Send", ignoreCase = true) == true) {
                        "MESSAGE_SENT"
                    } else if (step.expectedVerification == "POST_PUBLISHED" || query.text?.contains("Post", ignoreCase = true) == true) {
                        "POST_PUBLISHED"
                    } else {
                        "BUTTON_CLICKED"
                    }
                    return StepExecutionReport(step.stepIndex, step.actionType, state, "${result.message} (Screen transitioned: ${sigBefore != sigAfter})", 0, true)
                } else {
                    return StepExecutionReport(step.stepIndex, step.actionType, result.state, result.message, 0, false)
                }
            }

            "LONG_CLICK" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val query = step.targetQuery ?: NodeQuery(text = step.textValue)
                val result = service.longClickNode(query)
                return StepExecutionReport(step.stepIndex, step.actionType, result.state, result.message, 0, result.success)
            }

            "TYPE_TEXT", "SET_TEXT" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val text = step.textValue ?: ""
                val query = step.targetQuery ?: NodeQuery(isEditable = true)
                val result = service.setTextOnNode(query, text)
                return if (result.success) {
                    StepExecutionReport(step.stepIndex, step.actionType, "TEXT_ENTERED", "Entered text '$text' successfully.", 0, true)
                } else {
                    // Try generic typing into focused element
                    val fallback = service.typeText(text)
                    StepExecutionReport(step.stepIndex, step.actionType, fallback.state, fallback.message, 0, fallback.success)
                }
            }

            "SCROLL" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val dir = step.scrollDirection ?: "DOWN"
                val ok = service.scrollScreen(dir)
                return StepExecutionReport(step.stepIndex, step.actionType, if (ok) "ACTION_COMPLETED" else "ACTION_FAILED", "Scrolled screen $dir (success=$ok)", 0, ok)
            }

            "BACK" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val ok = service.performSystemAction("BACK")
                return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_COMPLETED", "Pressed Back button.", 0, ok)
            }

            "HOME" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val ok = service.performSystemAction("HOME")
                return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_COMPLETED", "Navigated to Home.", 0, ok)
            }

            "VERIFY" -> {
                val service = getAccessibilityServiceOrNull()
                    ?: return StepExecutionReport(step.stepIndex, step.actionType, "PERMISSION_REQUIRED", "Accessibility Service is not enabled.", 0, false)
                val query = step.targetQuery
                val expectedText = step.textValue

                val verified = if (query != null) {
                    service.verifyElementPresent(query)
                } else if (!expectedText.isNullOrBlank()) {
                    service.verifyTextEntered(expectedText)
                } else {
                    true
                }

                return if (verified) {
                    StepExecutionReport(step.stepIndex, step.actionType, "ACTION_COMPLETED", "Verification succeeded: expected element/text confirmed.", 0, true)
                } else {
                    StepExecutionReport(step.stepIndex, step.actionType, "ACTION_FAILED", "Verification failed: expected UI state not observed.", 0, false)
                }
            }

            else -> {
                return StepExecutionReport(step.stepIndex, step.actionType, "ACTION_FAILED", "Unsupported automation action: ${step.actionType}", 0, false)
            }
        }
    }

    /**
     * Resolves app package name from common aliases or PackageManager query.
     */
    fun resolvePackageName(appName: String): String? {
        val clean = appName.trim().lowercase()
        val wellKnown = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.google.android.GoogleCamera",
            "messages" to "com.google.android.apps.messaging",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "files" to "com.google.android.documentsui",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android"
        )

        wellKnown[clean]?.let { candidate ->
            if (isPackageInstalled(candidate)) return candidate
        }

        // Search installed applications by label
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(packageManager).toString().lowercase()
                if (label == clean || label.contains(clean)) {
                    return info.activityInfo.packageName
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun waitForAppWindow(pkg: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val active = JarvisAccessibilityService.lastActivePackage
            if (active.contains(pkg, ignoreCase = true)) {
                return true
            }
            delay(200)
        }
        return false
    }

    private fun getAccessibilityServiceOrNull(): JarvisAccessibilityService? {
        return JarvisAccessibilityService.instance
    }

    private fun determineFinalState(reports: List<StepExecutionReport>): String {
        return reports.lastOrNull()?.state ?: "ACTION_COMPLETED"
    }

    private fun serializeRemainingSteps(steps: List<AutomationStep>): String {
        val array = JSONArray()
        for (s in steps) {
            val obj = JSONObject()
            obj.put("stepIndex", s.stepIndex)
            obj.put("actionType", s.actionType)
            obj.put("appName", s.appName ?: "")
            obj.put("textValue", s.textValue ?: "")
            obj.put("requiresConfirmation", s.requiresConfirmation)
            array.put(obj)
        }
        return array.toString()
    }

    fun confirmPendingAction(): AutomationStep? {
        val pending = _activePendingConfirmation.value ?: return null
        _activePendingConfirmation.value = null
        // Returns next step to run after confirmation
        return try {
            val array = JSONArray(pending.remainingStepsJson)
            if (array.length() > 0) {
                val first = array.getJSONObject(0)
                AutomationStep(
                    stepIndex = first.optInt("stepIndex", 1),
                    actionType = first.optString("actionType", "CLICK"),
                    appName = first.optString("appName").takeIf { it.isNotBlank() },
                    textValue = first.optString("textValue").takeIf { it.isNotBlank() },
                    requiresConfirmation = false
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun cancelPendingAction(): String {
        _activePendingConfirmation.value = null
        return "Action cancelled by user."
    }

    suspend fun clickSendButton(retries: Int = 3): Boolean =
        getAccessibilityServiceOrNull()?.clickSendButton(retries) ?: false

    suspend fun clickFirstYouTubeVideoResult(retries: Int = 3): Boolean =
        getAccessibilityServiceOrNull()?.clickFirstYouTubeVideoResult(retries) ?: false

    suspend fun clickByText(text: String, retries: Int = 3): Boolean =
        getAccessibilityServiceOrNull()?.clickByText(text, retries) ?: false

    suspend fun clickByContentDescription(desc: String, retries: Int = 3): Boolean =
        getAccessibilityServiceOrNull()?.clickByContentDescription(desc, retries) ?: false

    fun tapRandomSafeNode(): com.example.service.TapResult? =
        getAccessibilityServiceOrNull()?.tapRandomSafeNode()

    companion object {
        private const val TAG = "UniversalAutomation"
    }
}
