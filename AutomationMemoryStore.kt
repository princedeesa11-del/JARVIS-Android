package com.example.automation

import com.example.data.local.dao.AutomationDao
import com.example.data.local.entity.AutomationRule
import kotlinx.coroutines.flow.Flow

/**
 * Safe Persistent Automation Memory for JARVIS.
 * Stores reusable automation workflow patterns (e.g. "morning routine", "send work update", "open project")
 * without ever storing credentials, passwords, or OTPs.
 */
class AutomationMemoryStore(private val automationDao: AutomationDao) {

    fun getAllWorkflows(): Flow<List<AutomationRule>> {
        return automationDao.getAllRules()
    }

    suspend fun saveWorkflow(
        name: String,
        triggerPhrase: String,
        stepsJson: String,
        description: String = ""
    ): Long {
        val sanitizedSteps = sanitizeStepsJson(stepsJson)
        val rule = AutomationRule(
            name = name,
            trigger = "USER_COMMAND",
            schedule = triggerPhrase.trim().lowercase(),
            action = "run_multi_step_workflow",
            arguments = sanitizedSteps,
            conditions = description,
            actions = "UNIVERSAL_WORKFLOW",
            actionPayload = sanitizedSteps
        )
        return automationDao.insertRule(rule)
    }

    suspend fun findWorkflowForCommand(commandText: String): AutomationRule? {
        val clean = commandText.trim().lowercase()
        val all = automationDao.getEnabledRules()
        return all.firstOrNull { rule ->
            rule.trigger == "USER_COMMAND" &&
                    (rule.schedule.isNotBlank() && clean.contains(rule.schedule, ignoreCase = true) ||
                     clean.contains(rule.name.lowercase()))
        }
    }

    suspend fun deleteWorkflow(id: Long) {
        automationDao.deleteRuleById(id)
    }

    private fun sanitizeStepsJson(json: String): String {
        // Strip sensitive password or token references
        return json
            .replace(Regex("(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")"), "$1[REDACTED]$2")
            .replace(Regex("(?i)(\"otp\"\\s*:\\s*\")[^\"]*(\")"), "$1[REDACTED]$2")
            .replace(Regex("(?i)(\"pin\"\\s*:\\s*\")[^\"]*(\")"), "$1[REDACTED]$2")
            .replace(Regex("(?i)(\"token\"\\s*:\\s*\")[^\"]*(\")"), "$1[REDACTED]$2")
    }
}
