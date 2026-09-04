package com.haraldmue.velin.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KeyStoreProvider = "AndroidKeyStore"
private const val KeyAlias = "velin_device_credentials_v1"
private const val Transformation = "AES/GCM/NoPadding"
private const val AuthenticationTagBits = 128

class AndroidKeyStoreCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("velin_credentials", Context.MODE_PRIVATE)

    override fun load(): DeviceCredentials? {
        val encodedCiphertext = preferences.getString(CiphertextKey, null) ?: return null
        val encodedIv = preferences.getString(IvKey, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadKey() ?: return null,
                GCMParameterSpec(AuthenticationTagBits, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            decodeCredentials(plaintext.decodeToString())
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun save(credentials: DeviceCredentials) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(encodeCredentials(credentials).encodeToByteArray())
        check(
            preferences.edit()
                .putString(IvKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CiphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist device credentials." }
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeyStoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
        return keyStore.getKey(KeyAlias, null) as? SecretKey
    }

    private fun encodeCredentials(credentials: DeviceCredentials): String = JSONObject()
        .put("server_url", credentials.serverUrl)
        .put("device_id", credentials.deviceId)
        .put("token", credentials.token)
        .put("server_name", credentials.serverName)
        .put("server_version", credentials.serverVersion)
        .toString()

    private fun decodeCredentials(value: String): DeviceCredentials {
        val json = JSONObject(value)
        return DeviceCredentials(
            serverUrl = json.getString("server_url"),
            deviceId = json.getString("device_id"),
            token = json.getString("token"),
            serverName = json.getString("server_name"),
            serverVersion = json.getString("server_version"),
        )
    }

    private companion object {
        const val IvKey = "iv"
        const val CiphertextKey = "ciphertext"
    }
}
