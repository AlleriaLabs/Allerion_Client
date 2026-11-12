package com.example.servermonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object AlertScheduler {
    private const val WORK_NAME = "allerion_server_health_alerts"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putBoolean("alerts_enabled", enabled).apply()
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ServerAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun ensureScheduled(context: Context) {
        val enabled = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("alerts_enabled", false)
        if (enabled) setEnabled(context, true)
    }
}

class ServerAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!config.getBoolean("alerts_enabled", false)) return@withContext Result.success()
        val servers = try { SecureServerStore.load(config) } catch (_: Exception) { return@withContext Result.retry() }
        if (servers.isEmpty()) return@withContext Result.success()
        createChannel()
        val thresholds = AlertPreferences.load(applicationContext)
        val rules = AlertRulePreferences.load(applicationContext)
        servers.forEach { server ->
            val prefix = (server.url + "|" + server.name).hashCode().toString()
            try {
                val stats = fetch(server.url, server.token)
                if (rules.serverAvailability) {
                    updateAlert(prefix + "_offline", false, server.name, "Server connection restored.", "critical")
                    updateAlert(prefix + "_api_error", false, server.name, "Monitoring API recovered.")
                } else {
                    clearAlertSilently(prefix + "_offline")
                    clearAlertSilently(prefix + "_api_error")
                }
                val cpu = stats.optDouble("cpu", Double.NaN)
                val ram = stats.optDouble("ram", Double.NaN)
                val temp = stats.optDouble("temp", Double.NaN)
                if (rules.temperature) {
                    updateAlert(prefix + "_temp_sensor", temp.isNaN(), server.name,
                        "Temperature sensor data is unavailable. Temperature threshold alerts cannot run until the backend reports a sensor reading.",
                        "warning")
                    if (!temp.isNaN()) updateAlert(prefix + "_temp", temp >= thresholds.temperature, server.name,
                        "Temperature is ${temp.roundToInt()} °C (threshold ${thresholds.temperature.roundToInt()} °C).")
                } else {
                    clearAlertSilently(prefix + "_temp_sensor")
                    clearAlertSilently(prefix + "_temp")
                }
                if (rules.cpu && !cpu.isNaN()) updateAlert(prefix + "_cpu", cpu >= thresholds.cpu, server.name,
                    "CPU usage is ${cpu.roundToInt()}% (threshold ${thresholds.cpu.roundToInt()}%).")
                else if (!rules.cpu) clearAlertSilently(prefix + "_cpu")
                if (rules.memory && !ram.isNaN()) updateAlert(prefix + "_ram", ram >= thresholds.memory, server.name,
                    "Memory usage is ${ram.roundToInt()}% (threshold ${thresholds.memory.roundToInt()}%).")
                else if (!rules.memory) clearAlertSilently(prefix + "_ram")
                val disks = stats.optJSONArray("disks") ?: JSONArray()
                for (index in 0 until disks.length()) {
                    val disk = disks.optJSONObject(index) ?: JSONObject()
                    val mount = disk.optString("mount", "Disk " + (index + 1))
                    val identity = disk.optString("id").ifBlank { disk.optString("device").ifBlank { mount } }
                    val diskKey = prefix + "_disk_" + identity.hashCode()
                    val pct = disk.optDouble("pct", Double.NaN)
                    if (rules.disk && !pct.isNaN()) updateAlert(diskKey, pct >= thresholds.disk, server.name,
                        "$mount is ${pct.roundToInt()}% full (threshold ${thresholds.disk.roundToInt()}%).")
                    else if (!rules.disk) clearAlertSilently(diskKey)
                }

                // Optional inventories are isolated: a disabled or unavailable Docker/LAN
                // feature must never turn a healthy server into an offline alert.
                if (rules.containers) checkContainers(server, prefix)
                if (rules.newDevices) checkNewDevices(server, prefix)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Scheduled background DNS failures can be transient and do not prove
                // that a server is offline. Avoid raising a false critical alert.
                if (error.hasUnknownHostCause()) {
                    if (rules.serverAvailability) clearUnverifiedOfflineAlert(prefix + "_offline")
                } else {
                    val apiFailure = monitoringApiFailureMessage(error)
                    if (rules.serverAvailability) {
                        if (apiFailure != null) {
                            updateAlert(prefix + "_offline", false, server.name, "Monitoring server is reachable.")
                            updateAlert(prefix + "_api_error", true, server.name, apiFailure, "warning")
                        } else {
                            updateAlert(prefix + "_api_error", false, server.name, "Monitoring API recovered.")
                            val detail = error.message?.takeIf { it.isNotBlank() }
                            updateAlert(prefix + "_offline", true, server.name,
                                "Monitoring server is unreachable from the background check" +
                                    (detail?.let { ": $it" } ?: "."), "critical")
                        }
                    } else {
                        clearAlertSilently(prefix + "_offline")
                        clearAlertSilently(prefix + "_api_error")
                    }
                }
            }
        }
        Result.success()
    }

    private fun clearAlertSilently(key: String) {
        ActiveAlertStore.set(applicationContext, key, null)
        applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            .edit().putBoolean(key, false).apply()
    }

    private fun checkContainers(server: Server, prefix: String) {
        val inventory = try {
            ApiClient.getJson(server.url, server.token, "/api/containers", connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return // Docker may be unavailable or disabled; do not infer container failures.
        }
        val items = inventory.optJSONArray("items") ?: return
        val presentKeys = HashSet<String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }
            if (id.isBlank()) continue
            val name = item.optString("name").ifBlank { id }
            val state = item.optString("state", "unknown").lowercase()
            val status = item.optString("status").ifBlank { state }
            val key = prefix + "_container_" + id.hashCode()
            presentKeys.add(key)
            updateAlert(key, state != "running", server.name,
                "Container $name is $status.", if (state == "dead" || state == "exited") "critical" else "warning")
        }
        // A removed container is no longer a failure condition for this host.
        ActiveAlertStore.load(applicationContext).filter {
            it.key.startsWith(prefix + "_container_") && it.key !in presentKeys
        }.forEach { clearAlertSilently(it.key) }
    }

    private fun checkNewDevices(server: Server, prefix: String) {
        val inventory = try {
            ApiClient.getJson(server.url, server.token, "/api/network/devices", connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return // LAN discovery is optional and may be disabled on the backend.
        }
        val items = inventory.optJSONArray("items") ?: return
        val current = LinkedHashMap<String, String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val ip = item.optString("ip")
            val mac = item.optString("mac").lowercase()
            val identity = mac.ifBlank { ip }
            if (identity.isNotBlank()) current[identity] = buildString {
                append(ip.ifBlank { "Unknown IP" })
                if (mac.isNotBlank()) append(" · ").append(mac)
            }
        }
        val prefs = applicationContext.getSharedPreferences("alert_device_baseline", Context.MODE_PRIVATE)
        val storageKey = prefix
        val baseline = try {
            org.json.JSONArray(prefs.getString(storageKey, "[]") ?: "[]")
                .let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet() }
        } catch (_: Exception) { emptySet() }

        // First successful snapshot establishes a baseline; it is not a new-device event.
        if (!prefs.contains(storageKey)) {
            prefs.edit().putString(storageKey, org.json.JSONArray(current.keys.toList()).toString()).apply()
            return
        }
        val newlySeen = current.keys.filterNot { it in baseline }
        for (identity in newlySeen) {
            val detail = current[identity] ?: continue
            val message = "New LAN device detected: $detail"
            val now = System.currentTimeMillis()
            val eventKey = prefix + "_new_device_" + identity.hashCode()
            AlertHistoryStore.append(applicationContext, AlertEvent(now, server.name, "warning", message, false))
            postNotification(eventKey, server.name, message, false)
        }
        prefs.edit().putString(storageKey, org.json.JSONArray((baseline + current.keys).toList()).toString()).apply()
    }

    private fun Exception.hasUnknownHostCause(): Boolean {
        var current: Throwable? = this
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is java.net.UnknownHostException) return true
            current = current.cause
        }
        return false
    }

    /** Remove a DNS-only false-positive without recording a server recovery event. */
    private fun clearUnverifiedOfflineAlert(key: String) {
        val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(key, false)) return
        ActiveAlertStore.set(applicationContext, key, null)
        prefs.edit().putBoolean(key, false).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Server health alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Important health and availability alerts from ALLERION." })
        }
    }

    private fun updateAlert(
        key: String,
        active: Boolean,
        serverName: String,
        message: String,
        severity: String = "warning"
    ) {
        val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean(key, false)
        val now = System.currentTimeMillis()
        if (active) {
            ActiveAlertStore.set(applicationContext, key, ActiveAlert(
                key, now, serverName, severity, message
            ))
        } else {
            ActiveAlertStore.set(applicationContext, key, null)
        }
        if (active && !wasActive) {
            AlertHistoryStore.append(applicationContext, AlertEvent(
                now, serverName, severity, message, false
            ))
            postNotification(key, serverName, message, false)
        } else if (!active && wasActive) {
            val recovery = if (message.isBlank()) "Condition returned to normal." else message
            AlertHistoryStore.append(applicationContext, AlertEvent(
                now, serverName, "recovery", recovery, true
            ))
            postNotification(key + "_recovered", serverName, recovery, true)
        }
        prefs.edit().putBoolean(key, active).apply()
    }

    private fun postNotification(key: String, serverName: String, message: String, recovered: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(applicationContext, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo_mark)
            .setContentTitle(serverName + if (recovered) " · ALLERION recovered" else " · ALLERION alert")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(key.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "allerion_health_alerts"
    }
}
