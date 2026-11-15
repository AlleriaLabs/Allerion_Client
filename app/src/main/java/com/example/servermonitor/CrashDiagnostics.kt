package com.example.servermonitor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(val timestamp: Long, val device: String, val android: String, val exception: String, val message: String, val stack: String)

object CrashReportStore {
    private const val PREFS = "crash_diagnostics"
    private const val KEY = "latest_report"
    private const val MAX_STACK = 48000

    fun save(context: Context, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = org.json.JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            .put("exception", error.javaClass.name)
            .put("message", error.message ?: "(no message)")
            .put("stack", writer.toString().take(MAX_STACK))
        // Synchronous commit is intentional: the process may terminate immediately after this handler.
        prefs.edit().putString(KEY, report.toString()).commit()
    }

    fun load(context: Context): CrashReport? = runCatching {
        val j = org.json.JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null)
        CrashReport(j.optLong("timestamp"), j.optString("device"), j.optString("android"),
            j.optString("exception"), j.optString("message"), j.optString("stack"))
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun exportText(context: Context): String {
        val r = load(context) ?: return "ALLERION CRASH DIAGNOSTICS\nNo crash report is stored."
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date(r.timestamp))
        return """ALLERION CRASH DIAGNOSTICS
Time: $date
Device: ${r.device}
Android: ${r.android}
Exception: ${r.exception}
Message: ${r.message}

STACK TRACE
${r.stack}
""".trim()
    }
}

class AllerionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReportStore.save(this, error) }
            previous?.uncaughtException(thread, error) ?: run { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}
