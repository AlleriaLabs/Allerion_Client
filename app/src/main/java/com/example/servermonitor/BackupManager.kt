package com.example.servermonitor

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected portable backup. Server tokens are never exported as plaintext. */
object BackupManager {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun encrypt(servers: List<Server>, password: CharArray): String {
        require(password.size >= 8) { "Use a backup password with at least 8 characters." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val rows = JSONArray()
        servers.forEach { server ->
            rows.put(JSONObject()
                .put("name", server.name)
                .put("url", server.url)
                .put("token", server.token))
        }
        val plain = JSONObject()
            .put("format", "ALLERION_BACKUP")
            .put("version", 1)
            .put("created_at", System.currentTimeMillis())
            .put("servers", rows)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return JSONObject()
            .put("format", "ALLERION_ENCRYPTED_BACKUP")
            .put("version", 1)
            .put("iterations", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString(2)
    }

    fun decrypt(backup: String, password: CharArray): List<Server> {
        require(password.size >= 8) { "Backup password must contain at least 8 characters." }
        val envelope = JSONObject(backup)
        require(envelope.optString("format") == "ALLERION_ENCRYPTED_BACKUP") {
            "This is not an ALLERION encrypted backup."
        }
        require(envelope.optInt("version") == 1) { "This backup version is not supported." }
        val iterations = envelope.optInt("iterations", ITERATIONS)
        require(iterations in 100_000..1_000_000) { "Backup encryption settings are invalid." }
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val encrypted = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12 && encrypted.isNotEmpty()) { "Backup file is damaged." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        val payload = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        require(payload.optString("format") == "ALLERION_BACKUP" && payload.optInt("version") == 1) {
            "Backup contents are not supported."
        }
        val rows = payload.getJSONArray("servers")
        require(rows.length() <= 100) { "Backup contains too many server entries." }
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val name = row.optString("name").trim()
            val url = row.optString("url").trim()
            val token = row.optString("token")
            require(name.isNotBlank() && url.isNotBlank()) { "A server entry is missing its name or address." }
            val parsed = java.net.URL(url)
            require((parsed.protocol == "http" || parsed.protocol == "https") && parsed.host.isNotBlank()) {
                "A server address in the backup is invalid."
            }
            Server(name, url, token)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
