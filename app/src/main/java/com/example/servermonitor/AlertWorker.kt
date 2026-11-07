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
        servers.forEach { server ->
            val prefix = (server.url + "|" + server.name).hashCode().toString()
            try {
                val stats = fetch(server.url, server.token)
                updateAlert(prefix + "_offline", false, server.name, "Server connection restored.")
                val cpu = stats.optDouble("cpu", Double.NaN)
                val ram = stats.optDouble("ram", Double.NaN)
                val temp = stats.optDouble("temp", Double.NaN)
                if (!cpu.isNaN()) updateAlert(prefix + "_cpu", cpu >= 90.0, server.name, "CPU usage is " + "%.0f".format(cpu) + "% (threshold 90%).")
                if (!ram.isNaN()) updateAlert(prefix + "_ram", ram >= 90.0, server.name, "Memory usage is " + "%.0f".format(ram) + "% (threshold 90%).")
                if (!temp.isNaN()) updateAlert(prefix + "_temp", temp >= 85.0, server.name, "Temperature is " + "%.0f".format(temp) + " °C (threshold 85 °C).")
                val disks = stats.optJSONArray("disks") ?: JSONArray()
                for (index in 0 until disks.length()) {
                    val disk = disks.optJSONObject(index) ?: JSONObject()
                    val mount = disk.optString("mount", "Disk " + (index + 1))
                    val pct = disk.optDouble("pct", Double.NaN)
                    if (!pct.isNaN()) updateAlert(prefix + "_disk_" + index, pct >= 92.0, server.name,
                        mount + " is " + "%.0f".format(pct) + "% full (threshold 92%).")
                }
            } catch (error: Exception) {
                updateAlert(prefix + "_offline", true, server.name, "ALLERION cannot reach this monitoring server.")
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

    private fun updateAlert(key: String, active: Boolean, serverName: String, message: String) {
        val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean(key, false)
        if (active && !wasActive) postNotification(key, serverName, message)
        prefs.edit().putBoolean(key, active).apply()
    }

    private fun postNotification(key: String, serverName: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(applicationContext, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo_mark)
            .setContentTitle(serverName + " · ALLERION alert")
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
