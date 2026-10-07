package com.example.whatsapp.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
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

data class WhatsAppSecureCredentials(
    val phoneNumberId: String = "",
    val businessAccountId: String = "",
    val accessToken: String = "",
    val verifyToken: String = WhatsAppConfigStore.DEFAULT_VERIFY_TOKEN,
    val webhookPublicUrl: String = ""
)

/**
 * Secure credential storage for WhatsApp Meta Cloud API tokens.
 *
 * Security Mandates:
 * - Uses AES-256-GCM authenticated encryption.
 * - Hardware-backed AndroidKeyStore when available (with isolated fallback for JVM testing).
 * - Stored in context.noBackupFilesDir to prevent cloud or device-transfer leaks.
 * - Plaintext is never persisted in plaintext SharedPreferences, never logged, never returned in API responses.
 * - Sensitive tokens are masked in the UI (e.g. "************ABCD").
 */
class WhatsAppSecureCredentialStore(private val context: Context) {

    private val credentialFile: File
        get() = File(context.noBackupFilesDir, CREDENTIAL_FILE_NAME)

    @Volatile
    private var cachedCredentials: WhatsAppSecureCredentials? = null

    init {
        // Automatically migrate legacy plaintext credentials if present
        migrateLegacySharedPreferences()
    }

    @Synchronized
    fun getCredentials(): WhatsAppSecureCredentials {
        cachedCredentials?.let { return it }
        val loaded = loadFromEncryptedStorage()
        cachedCredentials = loaded
        return loaded
    }

    @Synchronized
    fun saveCredentials(credentials: WhatsAppSecureCredentials): Boolean {
        return try {
            val json = JSONObject().apply {
                put("phone_id", credentials.phoneNumberId.trim())
                put("waba_id", credentials.businessAccountId.trim())
                put("access_token", credentials.accessToken.trim())
                put("verify_token", credentials.verifyToken.trim())
                put("public_url", credentials.webhookPublicUrl.trim())
            }
            val plaintext = json.toString().toByteArray(Charsets.UTF_8)
            val success = encryptAndStore(plaintext)
            if (success) {
                cachedCredentials = credentials
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and store WhatsApp credentials: ${e.message}")
            false
        }
    }

    @Synchronized
    fun clearCredentials(): Boolean {
        return try {
            if (credentialFile.exists()) {
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
            cachedCredentials = WhatsAppSecureCredentials()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear credentials: ${e.message}")
            false
        }
    }

    private fun loadFromEncryptedStorage(): WhatsAppSecureCredentials {
        if (!credentialFile.exists() || credentialFile.length() < 18) {
            return WhatsAppSecureCredentials()
        }
        val decryptedBytes = decryptStoredData() ?: return WhatsAppSecureCredentials()
        return try {
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            val json = JSONObject(jsonString)
            WhatsAppSecureCredentials(
                phoneNumberId = json.optString("phone_id", ""),
                businessAccountId = json.optString("waba_id", ""),
                accessToken = json.optString("access_token", ""),
                verifyToken = json.optString("verify_token", WhatsAppConfigStore.DEFAULT_VERIFY_TOKEN),
                webhookPublicUrl = json.optString("public_url", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decrypted credentials: ${e.message}")
            WhatsAppSecureCredentials()
        }
    }

    private fun migrateLegacySharedPreferences() {
        try {
            val legacyPrefs = context.getSharedPreferences("jarvis_whatsapp_cloud_prefs", Context.MODE_PRIVATE)
            val legacyToken = legacyPrefs.getString("key_wa_access_token", "") ?: ""
            val legacyPhoneId = legacyPrefs.getString("key_wa_phone_id", "") ?: ""
            val legacyWabaId = legacyPrefs.getString("key_wa_waba_id", "") ?: ""
            val legacyVerify = legacyPrefs.getString("key_wa_verify_token", "") ?: ""
            val legacyUrl = legacyPrefs.getString("key_wa_webhook_public_url", "") ?: ""

            if (legacyToken.isNotBlank() || legacyPhoneId.isNotBlank()) {
                if (!credentialFile.exists()) {
                    saveCredentials(
                        WhatsAppSecureCredentials(
                            phoneNumberId = legacyPhoneId,
                            businessAccountId = legacyWabaId,
                            accessToken = legacyToken,
                            verifyToken = legacyVerify.ifBlank { WhatsAppConfigStore.DEFAULT_VERIFY_TOKEN },
                            webhookPublicUrl = legacyUrl
                        )
                    )
                }
                // Scrub sensitive values from legacy plaintext SharedPreferences
                legacyPrefs.edit()
                    .remove("key_wa_access_token")
                    .remove("key_wa_verify_token")
                    .apply()
                Log.i(TAG, "Scrubbed legacy plaintext credentials and secured in encrypted storage.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration check failed: ${e.message}")
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
        return getFallbackKey()
    }

    private fun getFallbackKey(): Key {
        val fallbackFile = File(context.noBackupFilesDir, "wa_anchor.bin")
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
            Log.e(TAG, "Encryption error: ${e.message}")
            false
        }
    }

    private fun decryptStoredData(): ByteArray? {
        if (!credentialFile.exists() || credentialFile.length() < 18) return null
        return try {
            val fileBytes = FileInputStream(credentialFile).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(fileBytes)

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
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WASecureStore"
        private const val CREDENTIAL_FILE_NAME = "jarvis_wa_credentials.enc"
        private const val KEY_ALIAS = "JarvisWhatsAppCloudKey"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        /**
         * Masks sensitive tokens for safe display in UI or logs.
         * e.g., "EAABw1234567890XYZ" -> "••••••••••••0XYZ"
         */
        fun maskToken(token: String): String {
            if (token.isBlank()) return "Not configured"
            return if (token.length <= 8) {
                "••••••••"
            } else {
                "••••••••••••" + token.takeLast(4)
            }
        }

        @Volatile
        private var instance: WhatsAppSecureCredentialStore? = null

        fun getInstance(context: Context): WhatsAppSecureCredentialStore {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppSecureCredentialStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
