package com.elysium.vanguard.recordshield.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ============================================================================
 * SecureStorage — Hardware-Backed Encrypted Key-Value Store
 * ============================================================================
 *
 * Why EncryptedSharedPreferences over DataStore:
 *   EncryptedSharedPreferences uses Android Keystore + AES-256-GCM, meaning
 *   decryption keys are stored in hardware (TEE/StrongBox). Even if the
 *   device is rooted, the attacker cannot extract the master key.
 *
 * What's stored here:
 *   - Device API token (for authenticating with Vercel)
 *   - Device UUID
 *   - PIN hash (SHA-256 + salt, NOT the PIN itself)
 *   - Vercel API base URL
 *
 * Security Invariants:
 *   1. PIN is NEVER stored in plaintext — only salted hash
 *   2. API token is encrypted at rest by Android Keystore
 *   3. All reads/writes are synchronous (SharedPreferences API)
 *   4. Master key uses AES256_GCM with StrongBox preference
 * ============================================================================
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_FILE = "record_shield_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_IS_DEVICE_REGISTERED = "is_device_registered"
        private const val KEY_DEVICE_ALIAS = "device_alias"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true) // Hardware-backed when available
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ========================================================================
    // DEVICE CREDENTIALS
    // ========================================================================

    var deviceId: String?
        get() = encryptedPrefs.getString(KEY_DEVICE_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceToken: String?
        get() = encryptedPrefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = encryptedPrefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var isDeviceRegistered: Boolean
        get() = encryptedPrefs.getBoolean(KEY_IS_DEVICE_REGISTERED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_IS_DEVICE_REGISTERED, value).apply()

    var deviceAlias: String?
        get() = encryptedPrefs.getString(KEY_DEVICE_ALIAS, null)
        set(value) = encryptedPrefs.edit().putString(KEY_DEVICE_ALIAS, value).apply()

    var apiBaseUrl: String
        get() = encryptedPrefs.getString(KEY_API_BASE_URL, "https://your-project.vercel.app")
            ?: "https://your-project.vercel.app"
        set(value) = encryptedPrefs.edit().putString(KEY_API_BASE_URL, value).apply()

    // ========================================================================
    // PIN MANAGEMENT
    // ========================================================================

    /**
     * Set a new PIN by hashing it with a random salt.
     * The PIN is NEVER stored — only its hash.
     */
    fun setPin(pin: String) {
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        encryptedPrefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .apply()
        Log.i(TAG, "PIN set successfully (hash stored, not PIN)")
    }

    /**
     * Verify a PIN attempt against the stored hash.
     * Returns true if the PIN matches.
     */
    fun verifyPin(pinAttempt: String): Boolean {
        val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = encryptedPrefs.getString(KEY_PIN_SALT, null) ?: return false
        return hashPin(pinAttempt, salt) == storedHash
    }

    /**
     * Check if a PIN has been configured.
     */
    fun isPinSet(): Boolean {
        return encryptedPrefs.getString(KEY_PIN_HASH, null) != null
    }

    /**
     * Clear all secure storage (for device reset/unregister).
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        Log.w(TAG, "All secure storage cleared")
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private fun generateSalt(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPin(pin: String, salt: String): String {
        // Why SHA-256 with salt (not bcrypt): Android's EncryptedSharedPreferences
        // already encrypts the value at rest. The hash is a second layer to prevent
        // in-memory PIN extraction. For a 4-6 digit PIN, the hash + encryption
        // combo is sufficient.
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$salt:$pin".toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }
}
