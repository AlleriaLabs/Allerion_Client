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
        AlertDiagnostics.record(applicationContext, "Running", "Background check started")
        if (!config.getBoolean("alerts_enabled", false)) {
            AlertDiagnostics.record(applicationContext, "Paused", "Smart Health Alerts are disabled")
            return@withContext Result.success()
        }
        val servers = try { SecureServerStore.load(config) } catch (error: Exception) {
            AlertDiagnostics.record(applicationContext, "Failed", "Could not read encrypted server list: ${error.message ?: error.javaClass.simpleName}")
            return@withContext Result.retry()
        }
        if (servers.isEmpty()) {
            AlertDiagnostics.record(applicationContext, "No servers", "Add a server to begin background monitoring")
            return@withContext Result.success()
        }
        createChannel()
        val thresholds = AlertPreferences.load(applicationContext)
        val rules = AlertRulePreferences.load(applicationContext)
        var failedChecks = 0
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
                if (rules.cpu) {
                    updateAlert(prefix + "_cpu_sensor", cpu.isNaN(), server.name,
                        "CPU usage data is unavailable. CPU threshold alerts are paused until a reading is reported.", "warning")
                    if (!cpu.isNaN()) updateAlert(prefix + "_cpu", cpu >= thresholds.cpu, server.name,
                        "CPU usage is ${cpu.roundToInt()}% (threshold ${thresholds.cpu.roundToInt()}%).")
                } else {
                    clearAlertSilently(prefix + "_cpu_sensor")
                    clearAlertSilently(prefix + "_cpu")
                }
                if (rules.memory) {
                    updateAlert(prefix + "_ram_sensor", ram.isNaN(), server.name,
                        "Memory usage data is unavailable. Memory threshold alerts are paused until a reading is reported.", "warning")
                    if (!ram.isNaN()) updateAlert(prefix + "_ram", ram >= thresholds.memory, server.name,
                        "Memory usage is ${ram.roundToInt()}% (threshold ${thresholds.memory.roundToInt()}%).")
                } else {
                    clearAlertSilently(prefix + "_ram_sensor")
                    clearAlertSilently(prefix + "_ram")
                }
                val disks = stats.optJSONArray("disks") ?: JSONArray()
                val presentDiskKeys = HashSet<String>()
                if (rules.disk) {
                    updateAlert(prefix + "_disk_sensor", disks.length() == 0, server.name,
                        "Disk inventory is unavailable. Disk usage thresholds cannot run until the backend reports mounted disks.", "warning")
                } else {
                    clearAlertSilently(prefix + "_disk_sensor")
                }
                for (index in 0 until disks.length()) {
                    val disk = disks.optJSONObject(index) ?: continue
                    val mount = disk.optString("mount", "Disk " + (index + 1))
                    val identity = diskAlertIdentity(disk.optString("id"), disk.optString("device"), mount)
                    val diskKey = prefix + "_disk_" + identity.hashCode()
                    presentDiskKeys.add(diskKey)
                    presentDiskKeys.add(diskKey + "_sensor")
                    val pct = disk.optDouble("pct", Double.NaN)
                    if (rules.disk) {
                        if (!pct.isNaN()) {
                            updateAlert(diskKey + "_sensor", false, server.name, "Disk usage data is available for $mount.")
                            updateAlert(diskKey, pct >= thresholds.disk, server.name,
                                "$mount is ${pct.roundToInt()}% full (threshold ${thresholds.disk.roundToInt()}%).")
                        } else updateAlert(diskKey + "_sensor", true, server.name,
                            "Disk usage data is unavailable for $mount.", "warning")
                    } else {
                        clearAlertSilently(diskKey)
                        clearAlertSilently(diskKey + "_sensor")
                    }
                }
                ActiveAlertStore.load(applicationContext).filter {
                    it.key.startsWith(prefix + "_disk_") && it.key !in presentDiskKeys
                }.forEach { clearAlertSilently(it.key) }
                // Optional inventories are isolated: a disabled or unavailable Docker/LAN
                // feature must never turn a healthy server into an offline alert.
                if (rules.containers) checkContainers(server, prefix)
                else clearActiveAlertsWithPrefix(prefix + "_container_")
                if (rules.newDevices) checkNewDevices(server, prefix)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failedChecks++
                // Scheduled background DNS failures can be transient and do not prove
                // that a server is offline. Avoid raising a false critical alert.
                if (error.hasUnknownHostCause()) {
                    if (rules.serverAvailability) clearUnverifiedOfflineAlert(prefix + "_offline")
                    else {
                        clearAlertSilently(prefix + "_offline")
                        clearAlertSilently(prefix + "_api_error")
                    }
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
        val summary = if (failedChecks == 0) "Checked ${servers.size} server(s); all checks completed" else "Checked ${servers.size} server(s); $failedChecks check(s) had errors"
        AlertDiagnostics.record(applicationContext, if (failedChecks == 0) "Success" else "Partial", summary)
        Result.success()
    }

    private fun clearAlertSilently(key: String) {
        ActiveAlertStore.set(applicationContext, key, null)
        applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            .edit().putBoolean(key, false).apply()
    }

    private fun clearActiveAlertsWithPrefix(prefix: String) {
        ActiveAlertStore.load(applicationContext).filter { it.key.startsWith(prefix) }
            .forEach { clearAlertSilently(it.key) }
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
            val exitCode = Regex("Exited \\((\\d+)\\)").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val failed = state == "dead" || state == "restarting" || (state == "exited" && exitCode != null && exitCode != 0)
            val key = prefix + "_container_" + id.hashCode()
            presentKeys.add(key)
            updateAlert(key, failed, server.name,
                if (failed) "Container $name failed: $status." else "Container $name is running normally.",
                if (state == "dead" || (exitCode != null && exitCode != 0)) "critical" else "warning")
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
            val identity = lanDeviceIdentity(mac, ip)
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
