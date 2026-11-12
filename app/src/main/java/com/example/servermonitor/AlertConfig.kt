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
    val apiError = error as? ApiException
    if (apiError != null) {
        if (apiError.message?.contains("API token is required", ignoreCase = true) == true) {
            return "The API token is missing. Enter the X-Token in server settings."
        }
        val status = apiError.statusCode
        if (status == null) {
            val message = apiError.message.orEmpty()
            if (message.contains("timed out", ignoreCase = true) ||
                message.contains("network", ignoreCase = true)) return null
            return message.ifBlank { "The monitoring API failed before returning a response." }
        }
        return when (status) {
            401, 403 -> "The monitoring server rejected the API token or permissions."
            else -> "The monitoring server responded with HTTP $status, but monitoring data is unavailable."
        }
    }
    var cause: Throwable? = error
    repeat(6) {
        when (cause) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.net.NoRouteToHostException,
            is java.net.SocketException,
            is java.io.EOFException,
            is java.io.IOException -> return null
        }
        cause = cause?.cause
    }
    return "Background monitoring check failed: " +
        (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
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

data class AlertRules(
    val serverAvailability: Boolean = true,
    val cpu: Boolean = true,
    val memory: Boolean = true,
    val temperature: Boolean = true,
    val disk: Boolean = true,
    val containers: Boolean = false,
    val newDevices: Boolean = false
)

/** Per-category alert controls. Defaults preserve existing health-alert behavior. */
object AlertRulePreferences {
    private const val PREFS = "cfg"

    fun load(context: Context): AlertRules {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AlertRules(
            serverAvailability = p.getBoolean("rule_server_availability", true),
            cpu = p.getBoolean("rule_cpu", true),
            memory = p.getBoolean("rule_memory", true),
            temperature = p.getBoolean("rule_temperature", true),
            disk = p.getBoolean("rule_disk", true),
            containers = p.getBoolean("rule_containers", false),
            newDevices = p.getBoolean("rule_new_devices", false)
        )
    }

    fun save(context: Context, rules: AlertRules) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("rule_server_availability", rules.serverAvailability)
            .putBoolean("rule_cpu", rules.cpu)
            .putBoolean("rule_memory", rules.memory)
            .putBoolean("rule_temperature", rules.temperature)
            .putBoolean("rule_disk", rules.disk)
            .putBoolean("rule_containers", rules.containers)
            .putBoolean("rule_new_devices", rules.newDevices)
            .apply()
    }
}


/** Stable identifiers keep disk alert state attached to the same mount across inventory ordering changes. */
fun diskAlertIdentity(id: String, device: String, mount: String): String =
    id.ifBlank { device.ifBlank { mount } }

/** Prefer a hardware MAC address; fall back to IP only when the backend has no MAC. */
fun lanDeviceIdentity(mac: String, ip: String): String =
    mac.trim().lowercase().ifBlank { ip.trim() }
