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
        servers.forEach { server ->
            val prefix = (server.url + "|" + server.name).hashCode().toString()
            try {
                val stats = fetch(server.url, server.token)
                updateAlert(prefix + "_offline", false, server.name, "Server connection restored.", "critical")
                val cpu = stats.optDouble("cpu", Double.NaN)
                val ram = stats.optDouble("ram", Double.NaN)
                val temp = stats.optDouble("temp", Double.NaN)
                updateAlert(prefix + "_temp_sensor", temp.isNaN(), server.name,
                    "Temperature sensor data is unavailable. Temperature threshold alerts cannot run until the backend reports a sensor reading.",
                    "warning")
                if (!cpu.isNaN()) updateAlert(prefix + "_cpu", cpu >= thresholds.cpu, server.name,
                    "CPU usage is " + "%.0f".format(cpu) + "% (threshold %.0f%%).".format(thresholds.cpu))
                if (!ram.isNaN()) updateAlert(prefix + "_ram", ram >= thresholds.memory, server.name,
                    "Memory usage is " + "%.0f".format(ram) + "% (threshold %.0f%%).".format(thresholds.memory))
                if (!temp.isNaN()) updateAlert(prefix + "_temp", temp >= thresholds.temperature, server.name,
                    "Temperature is " + "%.0f".format(temp) + " °C (threshold %.0f °C).".format(thresholds.temperature))
                val disks = stats.optJSONArray("disks") ?: JSONArray()
                for (index in 0 until disks.length()) {
                    val disk = disks.optJSONObject(index) ?: JSONObject()
                    val mount = disk.optString("mount", "Disk " + (index + 1))
                    val pct = disk.optDouble("pct", Double.NaN)
                    if (!pct.isNaN()) updateAlert(prefix + "_disk_" + index, pct >= thresholds.disk, server.name,
                        mount + " is " + "%.0f".format(pct) + "% full (threshold %.0f%%).".format(thresholds.disk))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val apiFailure = monitoringApiFailureMessage(error)
                if (apiFailure != null) {
                    updateAlert(prefix + "_offline", false, server.name, "Monitoring server is reachable.")
                    updateAlert(prefix + "_api_error", true, server.name, apiFailure, "warning")
                } else {
                    updateAlert(prefix + "_api_error", false, server.name, "Monitoring API recovered.")
                    updateAlert(prefix + "_offline", true, server.name,
                        "ALLERION cannot reach this monitoring server.", "critical")
                }
            }
        }
        Result.success()
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
