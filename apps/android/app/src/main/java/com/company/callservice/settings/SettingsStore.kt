package com.company.callservice.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val apiBaseUrl: String = "",
    val defaultCountryCallingCode: String = "82",
    val callerIdEnabled: Boolean = true,
)

class SettingsStore(context: Context) {
    companion object {
        private const val PREFERENCES = "directory_settings"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_COUNTRY_CODE = "country_calling_code"
        private const val KEY_CALLER_ID_ENABLED = "caller_id_enabled"
        private const val KEY_LAST_AUTO_SYNC_ATTEMPT = "last_auto_sync_attempt_epoch_millis"
    }

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): AppSettings = AppSettings(
        apiBaseUrl = preferences.getString(KEY_API_BASE_URL, "").orEmpty(),
        defaultCountryCallingCode = preferences.getString(KEY_COUNTRY_CODE, "82").orEmpty(),
        callerIdEnabled = preferences.getBoolean(KEY_CALLER_ID_ENABLED, true),
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString(KEY_API_BASE_URL, settings.apiBaseUrl.trim())
            .putString(KEY_COUNTRY_CODE, settings.defaultCountryCallingCode.trim().removePrefix("+"))
            .putBoolean(KEY_CALLER_ID_ENABLED, settings.callerIdEnabled)
            .apply()
    }

    fun lastAutoSyncAttemptEpochMillis(): Long =
        preferences.getLong(KEY_LAST_AUTO_SYNC_ATTEMPT, 0L)

    fun markAutoSyncAttempt(nowEpochMillis: Long) {
        preferences.edit().putLong(KEY_LAST_AUTO_SYNC_ATTEMPT, nowEpochMillis).apply()
    }
}

/** Stores the bearer token encrypted with a non-exportable Android Keystore AES key. */
class SecretStore(context: Context) {
    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "company_directory_bearer_token_v1"
        private const val PREFERENCES = "directory_secrets"
        private const val KEY_ENCRYPTED_TOKEN = "bearer_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    fun hasToken(): Boolean = readToken() != null

    fun readToken(): String? = synchronized(lock) {
        val encoded = preferences.getString(KEY_ENCRYPTED_TOKEN, null) ?: return@synchronized null
        try {
            val parts = encoded.split(':')
            if (parts.size != 3 || parts[0] != "v1") return@synchronized null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            BearerTokenPolicy.validateAndNormalize(
                String(cipher.doFinal(encrypted), Charsets.UTF_8),
            )
        } catch (_: Exception) {
            // Invalidated keys and corrupt values fail closed.
            preferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
            null
        }
    }

    fun saveToken(token: String) = synchronized(lock) {
        val cleanToken = BearerTokenPolicy.validateAndNormalize(token)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
        val value = listOf(
            "v1",
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(":")
        preferences.edit().putString(KEY_ENCRYPTED_TOKEN, value).apply()
    }

    fun clearToken() {
        synchronized(lock) {
            preferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
