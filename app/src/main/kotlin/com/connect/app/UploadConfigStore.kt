package com.connect.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class UploadConfig(
    val endpoint: String = "",
    val token: String = "",
) {
    val configured: Boolean
        get() = endpoint.isNotBlank() && token.isNotBlank()
}

object UploadConfigStore {
    private const val PREFERENCES_NAME = "connect_upload"
    private const val ENDPOINT_KEY = "endpoint"
    private const val TOKEN_KEY = "encrypted_token"
    private const val DEVICE_ID_KEY = "device_id"
    private const val KEY_ALIAS = "connect_upload_token"
    private const val GCM_IV_BYTES = 12

    fun load(context: Context): UploadConfig = runCatching {
        val preferences = preferences(context)
        UploadConfig(
            endpoint = preferences.getString(ENDPOINT_KEY, "").orEmpty(),
            token = preferences.getString(TOKEN_KEY, null)?.let(::decrypt).orEmpty(),
        )
    }.getOrDefault(UploadConfig())

    fun save(context: Context, endpoint: String, token: String): Result<UploadConfig> = runCatching {
        val normalizedEndpoint = endpoint.trim()
        val normalizedToken = token.trim()
        if (normalizedEndpoint.isEmpty() && normalizedToken.isEmpty()) {
            preferences(context).edit(commit = true) {
                remove(ENDPOINT_KEY)
                remove(TOKEN_KEY)
            }
            return@runCatching UploadConfig()
        }
        require(normalizedEndpoint.isNotEmpty() && normalizedToken.isNotEmpty()) {
            "Endpoint and authorization token are both required"
        }
        val uri = URI(normalizedEndpoint)
        require(
            uri.scheme in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null
        ) {
            "Endpoint must be an absolute HTTP or HTTPS URL"
        }
        val config = UploadConfig(normalizedEndpoint, normalizedToken)
        preferences(context).edit(commit = true) {
            putString(ENDPOINT_KEY, config.endpoint)
            putString(TOKEN_KEY, encrypt(config.token))
        }
        config
    }

    fun deviceID(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences.edit(commit = true) { putString(DEVICE_ID_KEY, it) }
        }
    }

    private fun preferences(context: Context) = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val encoded = Base64.decode(value, Base64.NO_WRAP)
        require(encoded.size > GCM_IV_BYTES) { "Encrypted token is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, encoded.copyOfRange(0, GCM_IV_BYTES)),
        )
        return cipher.doFinal(encoded.copyOfRange(GCM_IV_BYTES, encoded.size))
            .toString(Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }
}
