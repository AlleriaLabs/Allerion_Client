package com.example.servermonitor

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores server credentials encrypted with an AES-256 key held by Android Keystore.
 * Legacy plaintext preferences are migrated on first read and removed only after
 * the encrypted value has been committed successfully.
 */
object SecureServerStore {
    private const val KEY_ALIAS = "allerion.server.credentials.v1"
    private const val SECURE_KEY = "servers_secure_v1"

    fun load(prefs: SharedPreferences): List<Server> {
        val encrypted = prefs.getString(SECURE_KEY, null)
        if (!encrypted.isNullOrBlank()) return decode(decrypt(encrypted))

        val legacy = prefs.getString("servers", "[]") ?: "[]"
        val array = JSONArray(legacy)
        val servers = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Server(item.optString("name"), item.optString("url"), item.optString("token"), item.optString("group", "Ungrouped"))
        }
        val oldUrl = prefs.getString("url", "").orEmpty()
        val migrated = if (servers.isEmpty() && oldUrl.isNotBlank()) {
            listOf(Server("My server", oldUrl, prefs.getString("token", "").orEmpty()))
        } else servers

        if (migrated.isNotEmpty() || prefs.contains("servers") || prefs.contains("url")) {
            save(prefs, migrated)
        }
        return migrated
    }

    fun save(prefs: SharedPreferences, servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(JSONObject()
                .put("name", server.name)
                .put("url", server.url)
                .put("token", server.token)
                .put("group", server.group))
        }
        val encrypted = encrypt(array.toString())
        val committed = prefs.edit()
            .putString(SECURE_KEY, encrypted)
            .remove("servers")
            .remove("url")
            .remove("token")
            .commit()
        check(committed) { "Could not securely save server credentials." }
    }

    private fun decode(json: String): List<Server> {
        val array = JSONArray(json)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Server(item.optString("name"), item.optString("url"), item.optString("token"), item.optString("group", "Ungrouped"))
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(iv.size.toByte()) + iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.isNotEmpty()) { "Encrypted server settings are empty." }
        val ivLength = payload[0].toInt() and 0xff
        require(ivLength in 12..16 && payload.size > 1 + ivLength) { "Encrypted server settings are invalid." }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
