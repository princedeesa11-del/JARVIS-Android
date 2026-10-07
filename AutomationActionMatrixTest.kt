package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.AutomationEngine
import com.example.data.local.JarvisDatabase
import com.example.data.local.entity.AutomationExecutionRecord
import com.example.data.local.entity.AutomationExecutionState
import com.example.data.local.entity.AutomationRule
import com.example.data.repository.JarvisRepository
import com.example.tools.ToolExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomationActionMatrixTest {

    private lateinit var context: Context
    private lateinit var database: JarvisDatabase
    private lateinit var repository: JarvisRepository
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var automationEngine: AutomationEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = JarvisRepository(database)
        toolExecutor = ToolExecutor(context, repository)
        automationEngine = AutomationEngine(context, repository, toolExecutor)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test execution state enum values completeness`() {
        val states = AutomationExecutionState.values()
        assertTrue(states.contains(AutomationExecutionState.SCHEDULED))
        assertTrue(states.contains(AutomationExecutionState.RUNNING))
        assertTrue(states.contains(AutomationExecutionState.SUCCESS))
        assertTrue(states.contains(AutomationExecutionState.FAILED))
        assertTrue(states.contains(AutomationExecutionState.CANCELLED))
        assertTrue(states.contains(AutomationExecutionState.PERMISSION_REQUIRED))
        assertTrue(states.contains(AutomationExecutionState.USER_ACTION_REQUIRED))
        assertTrue(states.contains(AutomationExecutionState.BLOCKED_BY_ANDROID))
    }

    @Test
    fun `test speak action execution succeeds and records history`() = runBlocking {
        val rule = AutomationRule(
            name = "Morning Greeting",
            enabled = true,
            trigger = "SCHEDULED_TIME",
            conditions = "08:00",
            actions = "speak",
            actionPayload = "Good morning, all systems nominal."
        )
        val ruleId = repository.saveAutomationRule(rule)
        val savedRule = rule.copy(id = ruleId)

        var notificationFired = false
        var spokenMessage: String? = null

        val resultState = automationEngine.executeRule(savedRule) { msg ->
            notificationFired = true
            spokenMessage = msg
        }

        assertEquals(AutomationExecutionState.SUCCESS, resultState)
        assertTrue(notificationFired)
        assertEquals("Good morning, all systems nominal.", spokenMessage)

        // Verify history persisted in database
        val history = repository.recentAutomationExecutions.first()
        assertTrue(history.isNotEmpty())
        val record = history.first()
        assertEquals(ruleId, record.automationId)
        assertEquals(AutomationExecutionState.SUCCESS.name, record.state)
        assertEquals("Execution succeeded", record.result)
        assertNull(record.error)
        assertNotNull(record.completedAt)
    }

    @Test
    fun `test notification action execution succeeds and records history`() = runBlocking {
        val rule = AutomationRule(
            name = "Water Break Reminder",
            enabled = true,
            trigger = "SCHEDULED_TIME",
            conditions = "14:00",
            actions = "send_notification",
            actionPayload = "Stay hydrated!"
        )
        val ruleId = repository.saveAutomationRule(rule)
        val savedRule = rule.copy(id = ruleId)

        val resultState = automationEngine.executeRule(savedRule)
        assertEquals(AutomationExecutionState.SUCCESS, resultState)

        val history = repository.getExecutionHistoryForAutomation(ruleId).first()
        assertEquals(1, history.size)
        assertEquals(AutomationExecutionState.SUCCESS.name, history[0].state)
    }

    @Test
    fun `test launch app action execution via tool executor`() = runBlocking {
        val rule = AutomationRule(
            name = "Open Settings Routine",
            enabled = true,
            trigger = "MANUAL",
            conditions = "",
            actions = "launch_app",
            actionPayload = "Settings"
        )
        val ruleId = repository.saveAutomationRule(rule)
        val savedRule = rule.copy(id = ruleId)

        val resultState = automationEngine.executeRule(savedRule)
        // Under Robolectric, launching Settings succeeds
        assertEquals(AutomationExecutionState.SUCCESS, resultState)
    }

    @Test
    fun `test open url action execution via tool executor`() = runBlocking {
        val rule = AutomationRule(
            name = "Open Docs",
            enabled = true,
            trigger = "MANUAL",
            conditions = "",
            actions = "open_url",
            actionPayload = "https://android.com"
        )
        val ruleId = repository.saveAutomationRule(rule)
        val savedRule = rule.copy(id = ruleId)

        val resultState = automationEngine.executeRule(savedRule)
        assertEquals(AutomationExecutionState.SUCCESS, resultState)
    }

    @Test
    fun `test automation execution record entity and dao direct persistence`() = runBlocking {
        val record = AutomationExecutionRecord(
            executionId = "exec_test_001",
            automationId = 42L,
            startedAt = 1000L,
            completedAt = 2000L,
            state = AutomationExecutionState.SUCCESS.name,
            result = "OK",
            error = null
        )

        repository.logAutomationExecution(record)
        val retrieved = repository.getExecutionHistoryForAutomation(42L).first()
        assertEquals(1, retrieved.size)
        assertEquals("exec_test_001", retrieved[0].executionId)
        assertEquals(42L, retrieved[0].automationId)
        assertEquals(AutomationExecutionState.SUCCESS.name, retrieved[0].state)
        assertEquals("OK", retrieved[0].result)

        // Update record
        val updated = record.copy(state = AutomationExecutionState.FAILED.name, error = "Network timeout")
        repository.updateAutomationExecution(updated)

        val retrievedUpdated = repository.getExecutionHistoryForAutomation(42L).first()
        assertEquals(AutomationExecutionState.FAILED.name, retrievedUpdated[0].state)
        assertEquals("Network timeout", retrievedUpdated[0].error)
    }
}
