package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.Key
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CredentialType {
    NONE,
    PIN,
    PATTERN
}

data class ScreenLockCredentialStatus(
    val type: CredentialType,
    val configured: Boolean,
    val unlockEnabled: Boolean
)

data class ScreenLockFineTuning(
    val keypadDetectTimeoutMs: Long = 3000L,
    val screenWakeDelayMs: Long = 600L,
    val keyPressDelayMs: Long = 200L,
    val verifyDismissDelayMs: Long = 1000L
)

/**
 * Secure credential storage for JARVIS Screen Lock.
 *
 * Security Mandates:
 * - Uses AES-256-GCM authenticated encryption.
 * - Hardware-backed AndroidKeyStore when available.
 * - Stored in context.noBackupFilesDir to prevent cloud or device-transfer leaks.
 * - Plaintext is never persisted, never logged, never exposed to AI models or network.
 * - Decrypted data is retrieved only transiently in internal memory for immediate authorized unlock.
 */
class ScreenLockCredentialStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val credentialFile: File
        get() = File(context.noBackupFilesDir, CREDENTIAL_FILE_NAME)

    private val _statusFlow = MutableStateFlow(loadCurrentStatus())
    val statusFlow: StateFlow<ScreenLockCredentialStatus> = _statusFlow.asStateFlow()

    private val _wakeScreenFlow = MutableStateFlow(
        prefs.getBoolean(KEY_WAKE_SCREEN_ENABLED, DEFAULT_WAKE_SCREEN)
    )
    val wakeScreenFlow: StateFlow<Boolean> = _wakeScreenFlow.asStateFlow()

    private val _fineTuningFlow = MutableStateFlow(loadFineTuning())
    val fineTuningFlow: StateFlow<ScreenLockFineTuning> = _fineTuningFlow.asStateFlow()

    init {
        refreshStatus()
    }

    private fun loadCurrentStatus(): ScreenLockCredentialStatus {
        val typeStr = prefs.getString(KEY_CREDENTIAL_TYPE, CredentialType.NONE.name)
        val type = try {
            CredentialType.valueOf(typeStr ?: CredentialType.NONE.name)
        } catch (_: Exception) {
            CredentialType.NONE
        }

        val hasFile = credentialFile.exists() && credentialFile.length() > 0
        val isConfigured = hasFile && type != CredentialType.NONE
        val isUnlockEnabled = prefs.getBoolean(KEY_UNLOCK_ENABLED, false) && isConfigured

        return ScreenLockCredentialStatus(
            type = if (isConfigured) type else CredentialType.NONE,
            configured = isConfigured,
            unlockEnabled = isUnlockEnabled
        )
    }

    fun refreshStatus() {
        _statusFlow.value = loadCurrentStatus()
        _wakeScreenFlow.value = prefs.getBoolean(KEY_WAKE_SCREEN_ENABLED, DEFAULT_WAKE_SCREEN)
        _fineTuningFlow.value = loadFineTuning()
    }

    fun getStatus(): ScreenLockCredentialStatus = _statusFlow.value

    fun isConfigured(): Boolean = _statusFlow.value.configured

    fun getCredentialType(): CredentialType = _statusFlow.value.type

    fun isUnlockEnabled(): Boolean = _statusFlow.value.unlockEnabled

    fun setUnlockEnabled(enabled: Boolean): Boolean {
        if (enabled && !isConfigured()) {
            return false
        }
        prefs.edit().putBoolean(KEY_UNLOCK_ENABLED, enabled).apply()
        refreshStatus()
        return true
    }

    fun isWakeScreenEnabled(): Boolean =
        prefs.getBoolean(KEY_WAKE_SCREEN_ENABLED, DEFAULT_WAKE_SCREEN)

    fun setWakeScreenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_SCREEN_ENABLED, enabled).apply()
        _wakeScreenFlow.value = enabled
    }

    fun getFineTuning(): ScreenLockFineTuning = _fineTuningFlow.value

    fun updateFineTuning(fineTuning: ScreenLockFineTuning) {
        prefs.edit()
            .putLong(KEY_DETECT_TIMEOUT_MS, fineTuning.keypadDetectTimeoutMs.coerceIn(1000L, 8000L))
            .putLong(KEY_WAKE_DELAY_MS, fineTuning.screenWakeDelayMs.coerceIn(200L, 3000L))
            .putLong(KEY_KEY_DELAY_MS, fineTuning.keyPressDelayMs.coerceIn(80L, 800L))
            .putLong(KEY_VERIFY_DELAY_MS, fineTuning.verifyDismissDelayMs.coerceIn(400L, 4000L))
            .apply()
        _fineTuningFlow.value = fineTuning
    }

    private fun loadFineTuning(): ScreenLockFineTuning {
        return ScreenLockFineTuning(
            keypadDetectTimeoutMs = prefs.getLong(KEY_DETECT_TIMEOUT_MS, 3000L),
            screenWakeDelayMs = prefs.getLong(KEY_WAKE_DELAY_MS, 600L),
            keyPressDelayMs = prefs.getLong(KEY_KEY_DELAY_MS, 200L),
            verifyDismissDelayMs = prefs.getLong(KEY_VERIFY_DELAY_MS, 1000L)
        )
    }

    /**
     * Saves a PIN credential using AES-GCM encryption.
     * Overwrites any prior credential.
     */
    @Synchronized
    fun savePin(pin: String): Boolean {
        if (pin.length < 4 || pin.length > 16 || !pin.all { it.isDigit() }) {
            return false
        }

        return try {
            val plaintext = pin.toByteArray(Charsets.UTF_8)
            val success = encryptAndStore(TYPE_TAG_PIN, plaintext)
            if (success) {
                prefs.edit()
                    .putString(KEY_CREDENTIAL_TYPE, CredentialType.PIN.name)
                    .apply()
                refreshStatus()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to secure PIN credential: ${e.message}")
            false
        }
    }

    /**
     * Saves a Pattern credential using AES-GCM encryption.
     * Pattern is represented as a list of dot indices 0..8.
     */
    @Synchronized
    fun savePattern(pattern: List<Int>): Boolean {
        val validation = PatternValidator.validate(pattern)
        if (validation !is PatternValidator.Result.Valid) {
            return false
        }

        return try {
            val bytes = ByteArray(pattern.size) { i -> pattern[i].toByte() }
            val success = encryptAndStore(TYPE_TAG_PATTERN, bytes)
            if (success) {
                prefs.edit()
                    .putString(KEY_CREDENTIAL_TYPE, CredentialType.PATTERN.name)
                    .apply()
                refreshStatus()
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to secure Pattern credential: ${e.message}")
            false
        }
    }

    /**
     * Securely clears all credential material and resets settings.
     */
    @Synchronized
    fun clearCredential(): Boolean {
        return try {
            if (credentialFile.exists()) {
                // Overwrite with zeroes before deleting
                try {
                    val len = credentialFile.length()
                    if (len > 0) {
                        FileOutputStream(credentialFile).use { fos ->
                            fos.write(ByteArray(len.toInt()))
                            fos.flush()
                        }
                    }
                } catch (_: Exception) {}
                credentialFile.delete()
            }
            prefs.edit()
                .putString(KEY_CREDENTIAL_TYPE, CredentialType.NONE.name)
                .putBoolean(KEY_UNLOCK_ENABLED, false)
                .apply()
            refreshStatus()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear credential: ${e.message}")
            false
        }
    }

    /**
     * Internal retrieval of decrypted PIN for immediate authorized unlock.
     * NEVER expose this method to external classes, AI, or logs.
     */
    @Synchronized
    internal fun getDecryptedPinInternal(): String? {
        val status = _statusFlow.value
        if (!status.configured || status.type != CredentialType.PIN) return null

        val data = decryptStoredData() ?: return null
        if (data.first != TYPE_TAG_PIN) return null

        return try {
            String(data.second, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Internal retrieval of decrypted Pattern for immediate authorized unlock.
     * NEVER expose this method to external classes, AI, or logs.
     */
    @Synchronized
    internal fun getDecryptedPatternInternal(): List<Int>? {
        val status = _statusFlow.value
        if (!status.configured || status.type != CredentialType.PATTERN) return null

        val data = decryptStoredData() ?: return null
        if (data.first != TYPE_TAG_PATTERN) return null

        return try {
            data.second.map { it.toInt() }
        } catch (_: Exception) {
            null
        }
    }

    // --- Private Cryptographic Operations ---

    private fun getOrCreateKey(): Key {
        val hasAndroidKeyStore = Security.getProvider(KEYSTORE_PROVIDER) != null
        if (hasAndroidKeyStore) {
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGen = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        KEYSTORE_PROVIDER
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    keyGen.init(spec)
                    keyGen.generateKey()
                }
                return keyStore.getKey(KEY_ALIAS, null)
            } catch (e: Exception) {
                Log.w(TAG, "AndroidKeyStore initialization error, fallback to app-isolated key: ${e.message}")
            }
        }

        // Isolated fallback key for environments where AndroidKeyStore provider is absent (e.g. unit tests)
        return getFallbackKey()
    }

    private fun getFallbackKey(): Key {
        val fallbackFile = File(context.noBackupFilesDir, "sk_anchor.bin")
        val keyBytes = ByteArray(32)
        if (fallbackFile.exists() && fallbackFile.length() == 32L) {
            FileInputStream(fallbackFile).use { it.read(keyBytes) }
        } else {
            SecureRandom().nextBytes(keyBytes)
            FileOutputStream(fallbackFile).use { it.write(keyBytes) }
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptAndStore(typeTag: Byte, plaintext: ByteArray): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return false
            val ciphertext = cipher.doFinal(plaintext)

            // Binary layout:
            // [1 byte: typeTag] [1 byte: ivLen] [ivLen bytes: IV] [4 bytes: cipherLen] [cipherLen bytes: ciphertext]
            val buffer = ByteBuffer.allocate(1 + 1 + iv.size + 4 + ciphertext.size)
            buffer.put(typeTag)
            buffer.put(iv.size.toByte())
            buffer.put(iv)
            buffer.putInt(ciphertext.size)
            buffer.put(ciphertext)

            FileOutputStream(credentialFile).use { fos ->
                fos.write(buffer.array())
                fos.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encryption error: ${e.message}")
            false
        }
    }

    private fun decryptStoredData(): Pair<Byte, ByteArray>? {
        if (!credentialFile.exists() || credentialFile.length() < 18) {
            return null
        }

        return try {
            val fileBytes = FileInputStream(credentialFile).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(fileBytes)

            val typeTag = buffer.get()
            val ivLen = buffer.get().toInt() and 0xFF
            if (ivLen <= 0 || ivLen > 32 || buffer.remaining() < ivLen + 4) return null

            val iv = ByteArray(ivLen)
            buffer.get(iv)

            val cipherLen = buffer.getInt()
            if (cipherLen <= 0 || buffer.remaining() < cipherLen) return null

            val ciphertext = ByteArray(cipherLen)
            buffer.get(ciphertext)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plaintext = cipher.doFinal(ciphertext)

            Pair(typeTag, plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ScreenLockStore"
        private const val PREFS_NAME = "screen_lock_prefs"
        private const val CREDENTIAL_FILE_NAME = "jarvis_screen_lock.bin"
        private const val KEY_ALIAS = "JarvisScreenLockKey"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        const val TYPE_TAG_PIN: Byte = 1
        const val TYPE_TAG_PATTERN: Byte = 2

        const val KEY_WAKE_SCREEN_ENABLED = "screen_lock_wake_enabled"
        const val KEY_UNLOCK_ENABLED = "screen_lock_unlock_enabled"
        const val KEY_CREDENTIAL_TYPE = "screen_lock_credential_type"
        const val KEY_KEY_DELAY_MS = "screen_lock_key_delay_ms"
        const val KEY_WAKE_DELAY_MS = "screen_lock_wake_delay_ms"
        const val KEY_VERIFY_DELAY_MS = "screen_lock_verify_delay_ms"
        const val KEY_DETECT_TIMEOUT_MS = "screen_lock_detect_timeout_ms"

        const val DEFAULT_WAKE_SCREEN = true

        @Volatile
        private var instance: ScreenLockCredentialStore? = null

        fun getInstance(context: Context): ScreenLockCredentialStore {
            return instance ?: synchronized(this) {
                instance ?: ScreenLockCredentialStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
