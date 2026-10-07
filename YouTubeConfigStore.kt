package com.example.youtube.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.youtube.model.YouTubePlaybackTarget
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

/**
 * Secure Configuration Store for YouTube Automation.
 * Handles optional YouTube Data API keys securely with AES-256-GCM hardware/isolated encryption
 * and token masking for UI.
 */
class YouTubeConfigStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "YouTubeConfigStore"
        private const val PREFS_NAME = "jarvis_youtube_config"
        private const val KEY_ALIAS = "jarvis_yt_key_alias"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val CREDENTIAL_FILE_NAME = "yt_secure_key.dat"

        private const val KEY_PLAYBACK_TARGET = "yt_playback_target"
        private const val KEY_AUTO_VERIFY_A11Y = "yt_auto_verify_a11y"

        @Volatile
        private var instance: YouTubeConfigStore? = null

        fun getInstance(context: Context): YouTubeConfigStore {
            return instance ?: synchronized(this) {
                instance ?: YouTubeConfigStore(context).also { instance = it }
            }
        }
    }

    private val credentialFile: File
        get() = File(appContext.noBackupFilesDir, CREDENTIAL_FILE_NAME)

    @Volatile
    private var cachedApiKey: String? = null

    /**
     * Retrieves YouTube Data API key (if configured by user, BuildConfig, or environment).
     */
    @Synchronized
    fun getApiKey(): String {
        cachedApiKey?.let { return it }
        val loaded = loadFromEncryptedStorage()
        cachedApiKey = loaded
        return loaded
    }

    /**
     * Saves YouTube Data API key securely.
     */
    @Synchronized
    fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        cachedApiKey = trimmed
        if (trimmed.isEmpty()) {
            if (credentialFile.exists()) {
                credentialFile.delete()
            }
            return
        }
        encryptAndStore(trimmed.toByteArray(Charsets.UTF_8))
    }

    /**
     * Returns masked API key for safe UI display (e.g. AIzaSy...9XwQ)
     */
    fun getMaskedApiKey(): String {
        val key = getApiKey()
        if (key.isBlank()) return "Not configured (Using Direct Smart Search)"
        return if (key.length <= 8) {
            "********"
        } else {
            key.take(4) + "••••••••" + key.takeLast(4)
        }
    }

    var playbackTarget: YouTubePlaybackTarget
        get() {
            val name = prefs.getString(KEY_PLAYBACK_TARGET, YouTubePlaybackTarget.AUTO.name)
            return try {
                YouTubePlaybackTarget.valueOf(name ?: YouTubePlaybackTarget.AUTO.name)
            } catch (_: Exception) {
                YouTubePlaybackTarget.AUTO
            }
        }
        set(value) {
            prefs.edit().putString(KEY_PLAYBACK_TARGET, value.name).apply()
        }

    var autoVerifyAccessibility: Boolean
        get() = prefs.getBoolean(KEY_AUTO_VERIFY_A11Y, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_VERIFY_A11Y, value).apply()

    // --- Cryptographic Storage Operations ---

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
        return getFallbackKey()
    }

    private fun getFallbackKey(): Key {
        val fallbackFile = File(appContext.noBackupFilesDir, "yt_anchor.bin")
        val keyBytes = ByteArray(32)
        if (fallbackFile.exists() && fallbackFile.length() == 32L) {
            FileInputStream(fallbackFile).use { it.read(keyBytes) }
        } else {
            SecureRandom().nextBytes(keyBytes)
            FileOutputStream(fallbackFile).use { it.write(keyBytes) }
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptAndStore(plaintext: ByteArray): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: return false
            val ciphertext = cipher.doFinal(plaintext)

            val buffer = ByteBuffer.allocate(1 + iv.size + 4 + ciphertext.size)
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
            Log.e(TAG, "Encryption failure: ${e.message}")
            false
        }
    }

    private fun loadFromEncryptedStorage(): String {
        if (!credentialFile.exists() || credentialFile.length() == 0L) {
            return ""
        }
        return try {
            val fileBytes = FileInputStream(credentialFile).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(fileBytes)
            val ivSize = buffer.get().toInt()
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val ciphertextSize = buffer.int
            val ciphertext = ByteArray(ciphertextSize)
            buffer.get(ciphertext)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failure: ${e.message}")
            ""
        }
    }
}
