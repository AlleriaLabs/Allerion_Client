package com.example.servermonitor

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class ApiException(message: String, val statusCode: Int? = null) : IOException(message)

fun pollDelay(baseMs: Long, failures: Int): Long {
    val safeBase = baseMs.coerceIn(1_000L, 60_000L)
    if (failures <= 0) return safeBase
    val multiplier = 1L shl failures.coerceIn(1, 5)
    return (safeBase * multiplier).coerceAtMost(300_000L)
}

/** Reject missing credentials locally; the monitoring API requires header-only X-Token auth. */
fun validateApiToken(token: String) {
    if (token.isBlank()) {
        throw ApiException("API token is required. Edit this server connection and enter its X-Token.")
    }
}

/** One HTTP implementation for stats, history, energy, diagnostics and power APIs. */
object ApiClient {
    @Volatile var lastRequestDurationMs: Long = 0L
        private set

    fun getJson(
        base: String,
        token: String,
        path: String,
        body: JSONObject? = null,
        connectTimeoutMs: Int = 4_000,
        readTimeoutMs: Int = 15_000
    ): JSONObject {
        validateApiToken(token)
        val normalizedBase = base.trim().trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val url = try { URL(normalizedBase + normalizedPath) }
        catch (_: Exception) { throw ApiException("Invalid server address.") }
        require(url.protocol == "http" || url.protocol == "https") { "Only HTTP and HTTPS server URLs are supported." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("X-Token", token)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                throw ApiException("Wrong token or insufficient server permissions.", status)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = try { JSONObject(response).optString("error") } catch (_: Exception) { "" }
                val message = when (status) {
                    404 -> "The monitoring API endpoint was not found (404). Check server compatibility."
                    408, 504 -> "The monitoring server timed out."
                    in 500..599 -> "The monitoring server returned an error ($status)."
                    else -> "The monitoring server rejected the request ($status)."
                }
                throw ApiException(if (detail.isBlank()) message else "$message $detail", status)
            }
            if (response.isBlank()) throw ApiException("The monitoring server returned an empty response.", status)
            return try { JSONObject(response) } catch (_: Exception) {
                throw ApiException("The monitoring server returned invalid JSON.", status)
            }
        } catch (e: SocketTimeoutException) {
            throw ApiException("Connection timed out. Check the server and network.", null)
        } finally {
            lastRequestDurationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            connection.disconnect()
        }
    }
}
