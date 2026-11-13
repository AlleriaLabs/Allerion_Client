package com.example.servermonitor

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a shareable, on-device support report. It intentionally excludes server URLs,
 * API tokens, passwords, request headers and raw network payloads.
 */
object DiagnosticsReportBuilder {
    fun build(context: Context): String {
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val config = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val serverCount = runCatching { SecureServerStore.load(config).size }.getOrNull()
        val check = AlertDiagnostics.load(context)
        val thresholds = AlertPreferences.load(context)
        val rules = AlertRulePreferences.load(context)
        val active = ActiveAlertStore.load(context)
        val history = AlertHistoryStore.load(context, 50)
        val crash = CrashReportStore.load(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationsEnabled = notificationManager.areNotificationsEnabled()
        val enabledRules = buildList {
            if (rules.serverAvailability) add("Server availability")
            if (rules.cpu) add("CPU")
            if (rules.memory) add("Memory")
            if (rules.temperature) add("Temperature")
            if (rules.disk) add("Disk")
            if (rules.containers) add("Docker containers")
            if (rules.newDevices) add("New LAN devices")
        }
        val recoveredCount = history.count { it.recovered }
        val warningCount = history.count { !it.recovered && it.severity.equals("warning", true) }
        val criticalCount = history.count { !it.recovered && it.severity.equals("critical", true) }
        return buildString {
            appendLine("ALLERION · SUPPORT DIAGNOSTIC REPORT")
            appendLine("Generated: ${dateFormat.format(Date(now))}")
            appendLine()
            appendLine("APP & DEVICE")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("MONITORING CONFIGURATION")
            appendLine("Registered servers: ${serverCount?.toString() ?: "Unavailable (encrypted store could not be read)"}")
            appendLine("Smart health alerts: ${if (config.getBoolean("alerts_enabled", false)) "Enabled" else "Disabled"}")
            appendLine("Android notifications: ${if (notificationsEnabled) "Allowed" else "Blocked"}")
            appendLine("Enabled alert categories: ${enabledRules.joinToString().ifBlank { "None" }}")
            appendLine("Thresholds: CPU ${thresholds.cpu.toInt()}%, memory ${thresholds.memory.toInt()}%, temperature ${thresholds.temperature.toInt()} °C, disk ${thresholds.disk.toInt()}%")
            appendLine()
            appendLine("BACKGROUND CHECK")
            appendLine("Last check: ${if (check.timestamp > 0L) dateFormat.format(Date(check.timestamp)) else "Not run yet"}")
            appendLine("Last outcome: ${check.status}")
            appendLine("Detail: ${check.detail}")
            appendLine()
            appendLine("ALERT SUMMARY")
            appendLine("Currently active: ${active.size}")
            appendLine("Recent events included: ${history.size} (last 50 maximum)")
            appendLine("Critical events: $criticalCount · Warnings: $warningCount · Recoveries: $recoveredCount")
            if (active.isNotEmpty()) {
                appendLine()
                appendLine("ACTIVE CONDITIONS")
                active.forEach { item ->
                    appendLine("- [${item.severity.uppercase(Locale.getDefault())}] ${item.server}: ${item.message} (${dateFormat.format(Date(item.timestamp))})")
                }
            }
            appendLine()
            appendLine("RECENT ALERT / RECOVERY HISTORY")
            if (history.isEmpty()) appendLine("No locally recorded events.")
            else history.forEach { event ->
                val kind = if (event.recovered) "RECOVERED" else event.severity.uppercase(Locale.getDefault())
                appendLine("- ${dateFormat.format(Date(event.timestamp))} · $kind · ${event.server}: ${event.message}")
            }
            appendLine()
            appendLine("LATEST CRASH")
            if (crash == null) appendLine("No locally stored crash report.")
            else {
                appendLine("Time: ${dateFormat.format(Date(crash.timestamp))}")
                appendLine("Device: ${crash.device}")
                appendLine("Android: ${crash.android}")
                appendLine("Exception: ${crash.exception}")
                appendLine("Message: ${crash.message}")
                appendLine("Stack trace omitted from this summary report. Use Crash diagnostics to share the full crash report.")
            }
            appendLine()
            appendLine("DATA SCOPE & PRIVACY")
            appendLine("Generated entirely from data already stored on this Android device; no connection to CasaOS was required.")
            appendLine("This report does not include server URLs, API tokens, passwords, request headers or packet payloads.")
            appendLine("Alert messages and server display names may contain private infrastructure details. Review before sharing.")
            appendLine("Live server state and historical server metrics are not queried or inferred by this offline report.")
        }
    }
}
