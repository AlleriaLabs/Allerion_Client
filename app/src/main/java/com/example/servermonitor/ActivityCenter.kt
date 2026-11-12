package com.example.servermonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun eventTime(timestamp: Long): String =
    SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun ActivityCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("All") }
    var confirmClear by remember { mutableStateOf(false) }
    val events = remember(revision) { AlertHistoryStore.load(context, 200) }
    val active = remember(revision) { ActiveAlertStore.load(context) }
    val visible = when (filter) {
        "Alerts" -> events.filter { !it.recovered }
        "Recovery" -> events.filter { it.recovered }
        else -> events
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Active conditions")
                    Text("${active.size} active", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) {
                    Text("Clear history", color = if (events.isNotEmpty()) Mut else Dim)
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (active.isEmpty()) Green else Amber))
            }
            if (active.isEmpty()) {
                Text("No active alerts. Your registered servers are currently clear of recorded alert conditions.",
                    color = Mut, fontSize = AppType.small)
            } else active.forEach { alert ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (alert.severity == "critical") Err else Amber))
                        Spacer(Modifier.width(8.dp))
                        Text(alert.server, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Text(eventTime(alert.timestamp), color = Dim, fontSize = AppType.micro)
                    }
                    Text(alert.message, color = Mut, fontSize = AppType.small, modifier = Modifier.padding(start = 15.dp, top = 3.dp))
                }
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("All", "Alerts", "Recovery").forEach { item ->
                    Chip(item, filter == item) { filter = item }
                }
            }
            Spacer(Modifier.height(8.dp))
            Label("Event history · newest first")
            if (visible.isEmpty()) {
                Text(if (events.isEmpty()) "No events recorded yet. Enable Smart health alerts in Settings to record server warnings and recoveries."
                    else "No events match this filter.", color = Mut, fontSize = AppType.small,
                    modifier = Modifier.padding(vertical = 12.dp))
            } else visible.forEachIndexed { index, event ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(
                        when {
                            event.recovered -> Green
                            event.severity == "critical" -> Err
                            else -> Amber
                        }
                    ))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(event.server, color = Txt, fontSize = AppType.bodyStrong,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(eventTime(event.timestamp), color = Dim, fontSize = AppType.micro)
                        }
                        Text(event.message, color = Mut, fontSize = AppType.small)
                        Text(if (event.recovered) "RECOVERED" else event.severity.uppercase(),
                            color = if (event.recovered) Green else if (event.severity == "critical") Err else Amber,
                            fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear activity history?", color = Txt) },
        text = { Text("This removes recorded alert and recovery events from this device. Active alerts are not changed.", color = Mut) },
        confirmButton = {
            TextButton(onClick = {
                AlertHistoryStore.clear(context)
                revision++
                confirmClear = false
            }) { Text("Clear history", color = Err) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Mut) } },
        containerColor = Surf
    )
}

@Composable
fun DashboardPreferencesPanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE) }
    var showCpu by remember { mutableStateOf(prefs.getBoolean("dashboard_show_cpu", true)) }
    var showMemory by remember { mutableStateOf(prefs.getBoolean("dashboard_show_memory", true)) }
    var showTemperature by remember { mutableStateOf(prefs.getBoolean("dashboard_show_temperature", true)) }
    var showStorage by remember { mutableStateOf(prefs.getBoolean("dashboard_show_storage", true)) }
    var showGpu by remember { mutableStateOf(prefs.getBoolean("dashboard_show_gpu", true)) }

    fun save(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    Panel {
        Label("Dashboard customization")
        Text("Choose which resource widgets appear on the Overview screen. Changes are saved on this device.",
            color = Mut, fontSize = AppType.small)
        Spacer(Modifier.height(8.dp))
        PreferenceSwitch("CPU", showCpu) { showCpu = it; save("dashboard_show_cpu", it) }
        PreferenceSwitch("Memory", showMemory) { showMemory = it; save("dashboard_show_memory", it) }
        PreferenceSwitch("Temperature", showTemperature) { showTemperature = it; save("dashboard_show_temperature", it) }
        PreferenceSwitch("Storage", showStorage) { showStorage = it; save("dashboard_show_storage", it) }
        PreferenceSwitch("GPU", showGpu) { showGpu = it; save("dashboard_show_gpu", it) }
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Txt, fontSize = AppType.body, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}


@Composable
fun MoreScreen(onActivity: () -> Unit, onSettings: () -> Unit, onConnection: () -> Unit, onDiagnostics: () -> Unit, onAppInfo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("TOOLS")
        MoreActionRow(Ic.Activity, "Activity center", "Review active alerts, recoveries and recent events", onActivity)
        MoreActionRow(Ic.Chart, "Crash diagnostics", "View and share the latest app crash report", onDiagnostics)
        MoreActionRow(Ic.Globe, "App info", "Version, device details and acknowledgements", onAppInfo)
        MoreActionRow(Ic.Gear, "Settings", "Dashboard preferences, reports and refresh behavior", onSettings)
        Spacer(Modifier.height(4.dp))
        Label("SERVER")
        MoreActionRow(Ic.Server, "Server connection", "Edit this server's address, token or name", onConnection)
    }
}

@Composable
private fun MoreActionRow(icon: Ic, title: String, subtitle: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Surf2), contentAlignment = Alignment.Center) {
                Icon24(icon, Red, 19.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Mut, fontSize = AppType.small, maxLines = 2)
            }
            Icon24(Ic.ChevronD, Dim, 18.dp, Modifier.graphicsLayer { rotationZ = -90f })
        }
    }
}


@Composable
fun CrashDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val report = remember(revision) { CrashReportStore.load(context) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Label("Automatic crash capture")
            Text("Uncaught app crashes are saved locally on this device before Android closes Allerion. No server credentials or tokens are included.",
                color = Mut, fontSize = AppType.small)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capture status", color = Mut, fontSize = AppType.body, modifier = Modifier.weight(1f))
                Text("Enabled", color = Green, fontSize = AppType.body, fontWeight = FontWeight.SemiBold)
            }
        }
        if (report == null) {
            Panel {
                Label("Latest report")
                Text("No crash report has been captured yet. If Allerion closes unexpectedly, reopen this page to inspect the saved report.",
                    color = Mut, fontSize = AppType.small)
            }
        } else {
            Panel {
                Label("Latest crash")
                Text(java.text.SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.getDefault()).format(Date(report.timestamp)),
                    color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                KV("Device", report.device)
                KV("Android", report.android)
                KV("Exception", report.exception.substringAfterLast('.'))
                Text(report.message, color = Amber, fontSize = AppType.small)
                Spacer(Modifier.height(8.dp))
                Text("Stack trace", color = Mut, fontSize = AppType.caption, fontWeight = FontWeight.Bold)
                Text(report.stack, color = Dim, fontSize = 10.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Allerion crash report")
                        putExtra(Intent.EXTRA_TEXT, CrashReportStore.exportText(context))
                    }
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                }) { Text("Share crash report") }
                TextButton(onClick = { CrashReportStore.clear(context); revision++ }) {
                    Text("Delete report", color = Err)
                }
            }
        }
    }
}
