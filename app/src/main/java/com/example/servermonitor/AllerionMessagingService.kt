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
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/** Registers this Android installation with every configured Allerion server. */
object FcmDeviceRegistration {
    fun isAvailable(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun refresh(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> registerWithServers(context.applicationContext, token) }
        }
    }

    fun registerWithServers(context: Context, token: String) {
        if (token.isBlank()) return
        Thread {
            val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val servers = runCatching { SecureServerStore.load(prefs) }.getOrDefault(emptyList())
            servers.forEach { server ->
                runCatching {
                    ApiClient.getJson(
                        server.url, server.token, "/api/notifications/fcm/register",
                        JSONObject().put("token", token),
                        connectTimeoutMs = 5_000, readTimeoutMs = 8_000
                    )
                }
            }
        }.start()
    }
}

class AllerionMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmDeviceRegistration.registerWithServers(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"] ?: "ALLERION alert"
        val body = message.notification?.body
            ?: message.data["body"] ?: "A monitored server reported an event."
        showNotification(title, body, message.data["type"] ?: "alert")
    }

    private fun showNotification(title: String, body: String, type: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "ALLERION alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Server health, availability and recovery alerts." })
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, (type + title + body).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify((type + title + body).hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "allerion_alerts"
    }
}
