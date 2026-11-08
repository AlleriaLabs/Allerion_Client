package com.example.servermonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertSettingsPanel() {
    val context = LocalContext.current
    val initial = remember { AlertPreferences.load(context) }
    var cpu by remember { mutableStateOf(initial.cpu) }
    var memory by remember { mutableStateOf(initial.memory) }
    var temperature by remember { mutableStateOf(initial.temperature) }
    var disk by remember { mutableStateOf(initial.disk) }

    Panel {
        Label("Alert thresholds")
        Text("Choose when ALLERION should mark a metric as critical. Changes apply to the next background check.", color = Mut, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        ThresholdSlider("CPU", cpu, "%", 50f..100f) {
            cpu = it
            AlertPreferences.save(context, AlertThresholds(cpu, memory, temperature, disk))
        }
        ThresholdSlider("Memory", memory, "%", 50f..100f) {
            memory = it
            AlertPreferences.save(context, AlertThresholds(cpu, memory, temperature, disk))
        }
        ThresholdSlider("Temperature", temperature, " °C", 40f..100f) {
            temperature = it
            AlertPreferences.save(context, AlertThresholds(cpu, memory, temperature, disk))
        }
        ThresholdSlider("Disk usage", disk, "%", 50f..100f) {
            disk = it
            AlertPreferences.save(context, AlertThresholds(cpu, memory, temperature, disk))
        }
        TextButton(onClick = {
            cpu = 90f; memory = 90f; temperature = 85f; disk = 92f
            AlertPreferences.save(context, AlertThresholds())
        }) { Text("Reset to defaults", color = Red) }
    }

    var activeAlerts by remember { mutableStateOf(ActiveAlertStore.load(context)) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Active alerts")
            TextButton(onClick = { activeAlerts = ActiveAlertStore.load(context) }) {
                Text("Refresh", color = Red)
            }
        }
        if (activeAlerts.isEmpty()) {
            Text("No active alerts. ALLERION will list server issues here when a threshold is crossed or a server becomes unreachable.",
                color = Dim, fontSize = 12.sp)
        } else {
            activeAlerts.forEach { alert ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        alert.severity.uppercase(Locale.getDefault()) + " · " + alert.server,
                        color = if (alert.severity == "critical") Err else Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(alert.message, color = Txt, fontSize = 12.sp)
                    Text(SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(alert.timestamp)),
                        color = Dim, fontSize = 10.sp)
                }
                Spacer(Modifier.height(9.dp))
            }
        }
    }

    var events by remember { mutableStateOf(AlertHistoryStore.load(context, 10)) }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Alert history")
            TextButton(onClick = { AlertHistoryStore.clear(context); events = emptyList() }) { Text("Clear", color = Mut) }
        }
        if (events.isEmpty()) {
            Text("No alert events recorded yet. New alerts and recovery events will appear here.", color = Dim, fontSize = 12.sp)
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                events.take(10).forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        (if (event.recovered) "RECOVERED · " else event.severity.uppercase(Locale.getDefault()) + " · ") + event.server,
                        color = if (event.recovered) Green else if (event.severity == "critical") Err else Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(event.message, color = Txt, fontSize = 12.sp)
                    Text(SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(event.timestamp)), color = Dim, fontSize = 10.sp)
                }
                Spacer(Modifier.height(9.dp))
                }
            }
        }
    }
}

@Composable
private fun ThresholdSlider(label: String, value: Float, unit: String, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Txt, fontSize = 13.sp)
            Text("%.0f".format(value) + unit, color = Txt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = 49)
    }
}
