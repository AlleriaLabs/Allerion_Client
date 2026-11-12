package com.example.servermonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AlertCheckStatus(val timestamp: Long, val status: String, val detail: String)

/** Local observability for scheduled checks and a user-triggered notification smoke test. */
object AlertDiagnostics {
    private const val PREFS = "alert_diagnostics"

    fun record(context: Context, status: String, detail: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_check", System.currentTimeMillis())
            .putString("last_status", status)
            .putString("last_detail", detail.take(240))
            .apply()
    }

    fun load(context: Context): AlertCheckStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AlertCheckStatus(
            prefs.getLong("last_check", 0L),
            prefs.getString("last_status", "Not run yet") ?: "Not run yet",
            prefs.getString("last_detail", "Waiting for the first scheduled check") ?: "Waiting for the first scheduled check"
        )
    }

    fun requestTestNotification(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "Allow ALLERION notifications in Android settings before testing."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                "allerion_health_alerts", "Server health alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Important health and availability alerts from ALLERION." })
        }
        val notification = NotificationCompat.Builder(context, "allerion_health_alerts")
            .setSmallIcon(R.drawable.logo_mark)
            .setContentTitle("ALLERION · Test notification")
            .setContentText("Notifications are working on this device.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Notifications are working on this device. Your alert settings are ready."))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(0xA113, notification)
        return null
    }

    fun formatTimestamp(timestamp: Long): String =
        if (timestamp <= 0L) "Not run yet"
        else SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
