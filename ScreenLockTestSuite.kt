package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenLockTestSuite {

    private lateinit var context: Context
    private lateinit var store: ScreenLockCredentialStore
    private lateinit var manager: ScreenLockManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ScreenLockCredentialStore.getInstance(context)
        manager = ScreenLockManager.getInstance(context)
        store.clearCredential()
        store.setWakeScreenEnabled(true)
    }

    // ==========================================
    // 1. PATTERN VALIDATOR TESTS
    // ==========================================

    @Test
    fun `PatternValidator rejects pattern with fewer than 4 dots`() {
        val shortPattern = listOf(0, 1, 2)
        val res = PatternValidator.validate(shortPattern)
        assertTrue(res is PatternValidator.Result.Invalid)
        assertEquals("Pattern must connect at least 4 dots.", (res as PatternValidator.Result.Invalid).reason)
    }

    @Test
    fun `PatternValidator rejects pattern with duplicate dots`() {
        val dupPattern = listOf(0, 1, 2, 0)
        val res = PatternValidator.validate(dupPattern)
        assertTrue(res is PatternValidator.Result.Invalid)
        assertTrue((res as PatternValidator.Result.Invalid).reason.contains("Duplicate dot"))
    }

    @Test
    fun `PatternValidator rejects invalid skipped intermediate dots`() {
        // 0 to 2 skips 1 without visiting 1
        val invalidSkip = listOf(0, 2, 5, 8)
        val res = PatternValidator.validate(invalidSkip)
        assertTrue(res is PatternValidator.Result.Invalid)
        assertTrue((res as PatternValidator.Result.Invalid).reason.contains("intermediate dot"))

        // 0 to 8 skips 4 without visiting 4
        val diagonalSkip = listOf(0, 8, 7, 6)
        val resDiag = PatternValidator.validate(diagonalSkip)
        assertTrue(resDiag is PatternValidator.Result.Invalid)
    }

    @Test
    fun `PatternValidator allows skipping intermediate dot if already visited`() {
        // 1 visited first, then 0 -> 2 is allowed
        val validPattern = listOf(1, 0, 2, 5)
        val res = PatternValidator.validate(validPattern)
        assertTrue("Pattern should be valid because 1 was already visited", res is PatternValidator.Result.Valid)

        // 4 visited first, then 0 -> 8 is allowed
        val validDiag = listOf(4, 0, 8, 5)
        val resDiag = PatternValidator.validate(validDiag)
        assertTrue("Pattern should be valid because 4 was already visited", resDiag is PatternValidator.Result.Valid)
    }

    @Test
    fun `PatternValidator accepts standard valid Android patterns`() {
        // L-shape: 0-3-6-7-8
        val lShape = listOf(0, 3, 6, 7, 8)
        assertTrue(PatternValidator.validate(lShape) is PatternValidator.Result.Valid)

        // Knight move: 0 to 5, 5 to 6, 6 to 1, 1 to 8
        val knightPattern = listOf(0, 5, 6, 1, 8)
        assertTrue(PatternValidator.validate(knightPattern) is PatternValidator.Result.Valid)
    }

    // ==========================================
    // 2. CREDENTIAL STORE TESTS
    // ==========================================

    @Test
    fun `CredentialStore saves and decrypts PIN internally`() {
        val testPin = "4826"
        val saved = store.savePin(testPin)
        assertTrue("PIN should save successfully", saved)

        val status = store.getStatus()
        assertTrue(status.configured)
        assertEquals(CredentialType.PIN, status.type)

        val decrypted = store.getDecryptedPinInternal()
        assertEquals(testPin, decrypted)
    }

    @Test
    fun `CredentialStore saves and decrypts Pattern internally`() {
        val testPattern = listOf(0, 1, 2, 5, 8)
        val saved = store.savePattern(testPattern)
        assertTrue("Pattern should save successfully", saved)

        val status = store.getStatus()
        assertTrue(status.configured)
        assertEquals(CredentialType.PATTERN, status.type)

        val decrypted = store.getDecryptedPatternInternal()
        assertEquals(testPattern, decrypted)
    }

    @Test
    fun `CredentialStore clearCredential securely removes data`() {
        store.savePin("1234")
        assertTrue(store.isConfigured())

        store.clearCredential()
        assertFalse(store.isConfigured())
        assertEquals(CredentialType.NONE, store.getCredentialType())
        assertNull(store.getDecryptedPinInternal())
        assertNull(store.getDecryptedPatternInternal())
    }

    @Test
    fun `CredentialStore rejects invalid PIN format`() {
        // Too short (< 4 digits)
        assertFalse(store.savePin("123"))
        // Non-digits
        assertFalse(store.savePin("12ab"))
        // Too long (> 16 digits)
        assertFalse(store.savePin("12345678901234567"))
    }

    @Test
    fun `CredentialStore ensures plaintext is never persisted in SharedPreferences`() {
        val secretPin = "987654"
        store.savePin(secretPin)

        val sharedPrefs = context.getSharedPreferences("screen_lock_prefs", Context.MODE_PRIVATE)
        val allPrefs = sharedPrefs.all
        for ((_, v) in allPrefs) {
            val strVal = v.toString()
            assertFalse("Plaintext PIN must not exist anywhere in SharedPreferences", strVal.contains(secretPin))
        }
    }

    // ==========================================
    // 3. SCREEN LOCK MANAGER TESTS
    // ==========================================

    @Test
    fun `ScreenLockManager default state is correct`() {
        assertTrue("Wake screen defaults to ON", manager.isWakeScreenEnabled.value)
        assertFalse("Automatic unlock defaults to OFF", manager.credentialStatus.value.unlockEnabled)
        assertEquals(CredentialType.NONE, manager.credentialStatus.value.type)
        assertFalse(manager.credentialStatus.value.configured)
    }

    @Test
    fun `ScreenLockManager cannot enable unlock without configured credential`() {
        store.clearCredential()
        val enabled = manager.setUnlockEnabled(true)
        assertFalse("Cannot enable unlock when no credential is saved", enabled)
        assertFalse(manager.credentialStatus.value.unlockEnabled)
    }

    @Test
    fun `ScreenLockManager allows enabling unlock once credential exists`() {
        store.savePin("5555")
        val enabled = manager.setUnlockEnabled(true)
        assertTrue("Can enable unlock when credential exists", enabled)
        assertTrue(manager.credentialStatus.value.unlockEnabled)

        // Disabling works
        manager.setUnlockEnabled(false)
        assertFalse(manager.credentialStatus.value.unlockEnabled)
    }

    @Test
    fun `ScreenLockManager wake screen toggle updates state`() {
        manager.setWakeScreenEnabled(false)
        assertFalse(manager.isWakeScreenEnabled.value)

        manager.setWakeScreenEnabled(true)
        assertTrue(manager.isWakeScreenEnabled.value)
    }

    @Test
    fun `ScreenLockManager diagnostic test safely fails if unconfigured`() {
        store.clearCredential()
        var testResult: ScreenLockTestResult? = null
        manager.executeUnlockTest(
            onStatusUpdate = {},
            onComplete = { testResult = it }
        )

        assertNotNull(testResult)
        assertTrue(testResult is ScreenLockTestResult.Failure)
        assertTrue((testResult as ScreenLockTestResult.Failure).reason.contains("Save a pattern or a PIN"))
    }
}
