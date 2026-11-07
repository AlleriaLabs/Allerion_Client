package com.example.servermonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class InfrastructureSnapshot(
    val capabilities: JSONObject? = null,
    val processes: JSONObject? = null,
    val containers: JSONObject? = null,
    val services: JSONObject? = null,
    val devices: JSONObject? = null,
    val forecast: JSONObject? = null,
    val energyBreakdown: JSONObject? = null,
    val error: String? = null
)

@Composable
fun InfrastructurePanel(sv: Server) {
    val snapshot by produceState(InfrastructureSnapshot(), sv.url, sv.token) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                try {
                    val caps = ApiClient.getJson(sv.url, sv.token, "/api/capabilities", readTimeoutMs = 5000)
                    val features = caps.optJSONObject("features") ?: JSONObject()
                    fun optional(path: String): JSONObject? = runCatching {
                        ApiClient.getJson(sv.url, sv.token, path, connectTimeoutMs = 2500, readTimeoutMs = 5000)
                    }.getOrNull()
                    InfrastructureSnapshot(
                        capabilities = caps,
                        processes = if (features.optBoolean("processes")) optional("/api/processes?limit=12") else null,
                        containers = if (features.optBoolean("containers")) optional("/api/containers") else null,
                        services = if (features.optBoolean("services")) optional("/api/services") else null,
                        devices = if (features.optBoolean("network_inventory")) optional("/api/network/devices") else null,
                        forecast = if (features.optBoolean("energy_forecast")) optional("/api/energy/forecast?days=30") else null,
                        energyBreakdown = if (features.optBoolean("energy_breakdown")) optional("/api/energy/breakdown") else null
                    )
                } catch (error: Exception) {
                    InfrastructureSnapshot(error = friendlyError(error))
                }
            }
            delay(60_000)
        }
    }

    val caps = snapshot.capabilities?.optJSONObject("features")
    if (caps == null) {
        Panel {
            Label("Infrastructure intelligence")
            Text(snapshot.error ?: "Checking backend capabilities…", color = Mut, fontSize = 12.sp)
            Text("This section requires ServerMonitor API v2.", color = Dim, fontSize = 11.sp)
        }
        return
    }

    Panel {
        Label("Infrastructure intelligence")
        Text("Live data from the ServerMonitor backend. Optional collectors appear only when enabled and available.", color = Mut, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        DiagRow("API version", snapshot.capabilities?.optInt("api_version", 1)?.toString() ?: "Unknown", Green)
        DiagRow("Containers", if (caps.optBoolean("containers")) "Available" else "Not configured", if (caps.optBoolean("containers")) Green else Dim)
        DiagRow("Service checks", if (caps.optBoolean("services")) "Enabled" else "Not configured", if (caps.optBoolean("services")) Green else Dim)
        DiagRow("LAN inventory", if (caps.optBoolean("network_inventory")) "Enabled" else "Opt-in disabled", if (caps.optBoolean("network_inventory")) Green else Dim)
        DiagRow("Webhook", if (caps.optBoolean("webhooks")) "Configured" else "Not configured", if (caps.optBoolean("webhooks")) Green else Dim)
    }

    if (caps.optBoolean("processes")) {
        val rows = snapshot.processes?.optJSONArray("items")
        Panel {
            Label("Top processes")
            if (rows == null || rows.length() == 0) Text("Process data is not available yet.", color = Dim, fontSize = 12.sp)
            else for (i in 0 until rows.length()) {
                val p = rows.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.optString("name"), color = Txt, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("PID " + p.optInt("pid") + " · " + p.optString("user"), color = Dim, fontSize = 10.sp)
                    }
                    Text("%.1f%% · %d MB".format(p.optDouble("cpu_pct"), p.optLong("memory_bytes") / 1_000_000), color = Mut, fontSize = 11.sp)
                }
            }
        }
    }

    if (caps.optBoolean("containers")) {
        val rows = snapshot.containers?.optJSONArray("items")
        Panel {
            Label("Docker containers")
            if (rows == null || rows.length() == 0) Text("No containers found or Docker API is unavailable.", color = Dim, fontSize = 12.sp)
            else for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(item.optString("name"), color = Txt, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(item.optString("image"), color = Dim, fontSize = 10.sp, maxLines = 1)
                    }
                    Text(item.optString("state"), color = if (item.optString("state") == "running") Green else Amber, fontSize = 11.sp)
                }
            }
        }
    }

    if (caps.optBoolean("services")) {
        val rows = snapshot.services?.optJSONArray("items")
        Panel {
            Label("Service health")
            if (rows == null || rows.length() == 0) Text("No configured service checks.", color = Dim, fontSize = 12.sp)
            else for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                DiagRow(item.optString("name"), item.optString("status") + (item.optInt("latency_ms").let { if (it > 0) " · ${it} ms" else "" }),
                    if (item.optString("status") == "up") Green else Err)
            }
        }
    }

    if (caps.optBoolean("network_inventory")) {
        val rows = snapshot.devices?.optJSONArray("items")
        Panel {
            Label("LAN devices")
            Text("Passive neighbor discovery only. No unsolicited network sweep is performed.", color = Dim, fontSize = 11.sp)
            if (rows == null || rows.length() == 0) Text("No devices in the current ARP / neighbor cache.", color = Mut, fontSize = 12.sp)
            else for (i in 0 until minOf(rows.length(), 30)) {
                val item = rows.optJSONObject(i) ?: continue
                Text(item.optString("ip") + "   " + item.optString("mac"), color = Txt, fontSize = 12.sp)
            }
        }
    }

    if (snapshot.forecast?.optBoolean("available") == true) {
        val forecast = snapshot.forecast!!
        Panel {
            Label("Energy forecast")
            Text("%.2f kWh".format(forecast.optDouble("forecast_kwh")), color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Estimated next ${forecast.optInt("days")} days · based on ${forecast.optInt("observations")} recorded daily totals", color = Mut, fontSize = 11.sp)
            Text("This is a trend estimate, not a measured reading.", color = Dim, fontSize = 10.sp)
        }
    }

    val energyDays = snapshot.energyBreakdown?.optJSONArray("days")
    val latestEnergyDay = energyDays?.optJSONObject((energyDays.length() - 1).coerceAtLeast(0))
    val energySources = latestEnergyDay?.optJSONArray("sources")
    if (energySources != null && energySources.length() > 0) {
        Panel {
            Label("Energy source breakdown")
            Text(latestEnergyDay.optString("day"), color = Dim, fontSize = 10.sp)
            for (i in 0 until energySources.length()) {
                val source = energySources.optJSONObject(i) ?: continue
                DiagRow(source.optString("source"), "%.3f kWh".format(source.optDouble("kwh")), Mut)
            }
            Text("Older energy totals recorded before this backend update cannot be attributed retroactively.", color = Dim, fontSize = 10.sp)
        }
    }

    NetworkDiagnosticPanel(sv, caps.optBoolean("network_diagnostics"))
    WebhookTestPanel(sv, caps.optBoolean("webhooks"))
}

@Composable
private fun NetworkDiagnosticPanel(sv: Server, enabled: Boolean) {
    var target by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Panel {
        Label("LAN connection diagnostics")
        Text("Test TCP reachability to a private IPv4 address. Only ports 443, 80, 22 and 8080 are checked.", color = Mut, fontSize = 11.sp)
        OutlinedTextField(value = target, onValueChange = { target = it }, enabled = enabled && !busy,
            label = { Text("Private LAN IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            busy = true; error = null; result = null
            scope.launch {
                try { result = withContext(Dispatchers.IO) {
                    ApiClient.getJson(sv.url, sv.token, "/api/network/diagnostics?target=" + target.trim())
                } } catch (e: Exception) { error = friendlyError(e) }
                busy = false
            }
        }, enabled = enabled && !busy && target.isNotBlank()) { Text(if (busy) "Testing…" else "Run diagnostics") }
        error?.let { Text(it, color = Amber, fontSize = 11.sp) }
        result?.let { json ->
            Text("Target: " + json.optString("target"), color = Txt, fontSize = 12.sp)
            val probes = json.optJSONArray("probes")
            if (probes != null) for (i in 0 until probes.length()) {
                val p = probes.optJSONObject(i) ?: continue
                DiagRow("TCP ${p.optInt("port")}", p.optString("state") + (p.optInt("connect_ms").let { if (it > 0) " · ${it} ms" else "" }),
                    if (p.optString("state") == "open") Green else Dim)
            }
        }
        if (!enabled) Text("Diagnostics are unavailable on this backend version.", color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun WebhookTestPanel(sv: Server, enabled: Boolean) {
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Panel {
        Label("Webhook integration")
        Text("Webhook URL and secret are configured on the backend; the app never stores or displays the secret.", color = Mut, fontSize = 11.sp)
        OutlinedButton(onClick = {
            busy = true; status = null
            scope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        ApiClient.getJson(sv.url, sv.token, "/api/integrations/webhook/test", JSONObject())
                    }
                    status = if (response.optBoolean("ok")) "Test delivery succeeded." else "Webhook test failed."
                } catch (e: Exception) { status = friendlyError(e) }
                busy = false
            }
        }, enabled = enabled && !busy) { Text(if (busy) "Sending…" else "Send test webhook") }
        status?.let { Text(it, color = if (it.contains("succeeded")) Green else Amber, fontSize = 11.sp) }
        if (!enabled) Text("Configure an HTTPS webhook endpoint on the backend to enable testing.", color = Dim, fontSize = 11.sp)
    }
}
