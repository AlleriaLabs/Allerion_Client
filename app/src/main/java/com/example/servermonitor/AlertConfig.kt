package com.example.servermonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AlertThresholds(
    val cpu: Float = 90f,
    val memory: Float = 90f,
    val temperature: Float = 85f,
    val disk: Float = 92f
)

data class AlertEvent(
    val timestamp: Long,
    val server: String,
    val severity: String,
    val message: String,
    val recovered: Boolean
)

/**
 * Returns a user-facing API/configuration failure when the server answered or local
 * credentials are missing. A null result means the request likely failed at transport level.
 */
fun monitoringApiFailureMessage(error: Throwable): String? {
    val apiError = error as? ApiException ?: return null
    if (apiError.message?.contains("API token is required", ignoreCase = true) == true) {
        return "The API token is missing. Enter the X-Token in server settings."
    }
    val status = apiError.statusCode ?: return (apiError.message?.takeIf { it.isNotBlank() }
        ?: "The monitoring API returned an application error.")
    return when (status) {
        401, 403 -> "The monitoring server rejected the API token or permissions."
        else -> "The monitoring server responded with HTTP $status, but monitoring data is unavailable."
    }
}

object AlertPreferences {
    private const val PREFS = "cfg"

    fun load(context: Context): AlertThresholds {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AlertThresholds(
            cpu = p.getFloat("alert_cpu_threshold", 90f).coerceIn(50f, 100f),
            memory = p.getFloat("alert_memory_threshold", 90f).coerceIn(50f, 100f),
            temperature = p.getFloat("alert_temp_threshold", 85f).coerceIn(40f, 100f),
            disk = p.getFloat("alert_disk_threshold", 92f).coerceIn(50f, 100f)
        )
    }

    fun save(context: Context, thresholds: AlertThresholds) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("alert_cpu_threshold", thresholds.cpu.coerceIn(50f, 100f))
            .putFloat("alert_memory_threshold", thresholds.memory.coerceIn(50f, 100f))
            .putFloat("alert_temp_threshold", thresholds.temperature.coerceIn(40f, 100f))
            .putFloat("alert_disk_threshold", thresholds.disk.coerceIn(50f, 100f))
            .apply()
    }
}

object AlertHistoryStore {
    private const val PREFS = "alert_history"
    private const val KEY = "events"
    private const val MAX_EVENTS = 200

    @Synchronized
    fun append(context: Context, event: AlertEvent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val next = JSONArray()
        val start = (old.length() - MAX_EVENTS + 1).coerceAtLeast(0)
        for (i in start until old.length()) next.put(old.optJSONObject(i) ?: JSONObject())
        next.put(JSONObject()
            .put("timestamp", event.timestamp)
            .put("server", event.server)
            .put("severity", event.severity)
            .put("message", event.message)
            .put("recovered", event.recovered))
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    fun load(context: Context, limit: Int = 30): List<AlertEvent> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .takeLast(limit.coerceIn(1, MAX_EVENTS))
            .map {
                AlertEvent(
                    timestamp = it.optLong("timestamp"),
                    server = it.optString("server", "Server"),
                    severity = it.optString("severity", "warning"),
                    message = it.optString("message"),
                    recovered = it.optBoolean("recovered", false)
                )
            }.reversed()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}


data class ActiveAlert(
    val key: String,
    val timestamp: Long,
    val server: String,
    val severity: String,
    val message: String
)

/** Persistent snapshot of currently active conditions, separate from the append-only event history. */
object ActiveAlertStore {
    private const val PREFS = "active_alerts"
    private const val KEY = "items"

    @Synchronized
    fun set(context: Context, alertKey: String, alert: ActiveAlert?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        if (alert == null) current.remove(alertKey) else current.put(alertKey, JSONObject()
            .put("key", alert.key)
            .put("timestamp", alert.timestamp)
            .put("server", alert.server)
            .put("severity", alert.severity)
            .put("message", alert.message))
        prefs.edit().putString(KEY, current.toString()).apply()
    }

    @Synchronized
    fun load(context: Context): List<ActiveAlert> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}")
        val current = runCatching { JSONObject(raw ?: "{}") }.getOrElse { JSONObject() }
        return current.keys().asSequence().mapNotNull { key ->
            val item = current.optJSONObject(key) ?: return@mapNotNull null
            ActiveAlert(
                key = item.optString("key", key),
                timestamp = item.optLong("timestamp"),
                server = item.optString("server", "Server"),
                severity = item.optString("severity", "warning"),
                message = item.optString("message")
            )
        }.sortedByDescending { it.timestamp }.toList()
    }
}
