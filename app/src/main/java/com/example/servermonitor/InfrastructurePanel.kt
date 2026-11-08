package com.example.servermonitor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class InfrastructureSnapshot(
    val capabilities: JSONObject? = null,
    val processes: JSONObject? = null,
    val containers: JSONObject? = null,
    val containerStats: List<JSONObject> = emptyList(),
    val services: JSONObject? = null,
    val devices: JSONObject? = null,
    val topology: JSONObject? = null,
    val integrations: JSONObject? = null,
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
                    fun optional(path: String): JSONObject? = try {
                        ApiClient.getJson(sv.url, sv.token, path, connectTimeoutMs = 2500, readTimeoutMs = 5000)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val containerData = if (features.optBoolean("containers")) optional("/api/containers") else null
                    val containerRows = containerData?.optJSONArray("items")
                    val statsRows = if (containerRows != null) (0 until minOf(containerRows.length(), 8)).mapNotNull { index ->
                        val item = containerRows.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optString("id")
                        if (item.optString("state") != "running" || id.isBlank()) return@mapNotNull null
                        optional("/api/containers/$id/stats")
                    } else emptyList()
                    InfrastructureSnapshot(
                        capabilities = caps,
                        processes = if (features.optBoolean("processes")) optional("/api/processes?limit=12") else null,
                        containers = containerData,
                        containerStats = statsRows,
                        services = if (features.optBoolean("services")) optional("/api/services") else null,
                        devices = if (features.optBoolean("network_inventory")) optional("/api/network/devices") else null,
                        topology = if (features.optBoolean("network_topology")) optional("/api/network/topology") else null,
                        integrations = optional("/api/integrations"),
                        forecast = if (features.optBoolean("energy_forecast")) optional("/api/energy/forecast?days=30") else null,
                        energyBreakdown = if (features.optBoolean("energy_breakdown")) optional("/api/energy/breakdown") else null
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
        DiagRow("Telegram", if (caps.optBoolean("telegram")) "Configured" else "Not configured", if (caps.optBoolean("telegram")) Green else Dim)
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
                        val containerId = item.optString("id")
                        val usage = snapshot.containerStats.firstOrNull { it.optString("id") == containerId }
                        if (usage != null) {
                            val cpu = if (usage.isNull("cpu_pct")) "CPU n/a" else "CPU %.1f%%".format(usage.optDouble("cpu_pct"))
                            val memory = if (usage.isNull("memory_pct")) "RAM n/a" else "RAM %.1f%%".format(usage.optDouble("memory_pct"))
                            Text("$cpu · $memory", color = Mut, fontSize = 10.sp)
                        } else if (item.optString("state") == "running") {
                            Text("Resource stats unavailable", color = Dim, fontSize = 10.sp)
                        }
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
    val latestEnergyDay = if (energyDays != null && energyDays.length() > 0) energyDays.optJSONObject(energyDays.length() - 1) else null
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

    NetworkTopologyPanel(snapshot.topology, caps.optBoolean("network_topology"))
    NetworkDiagnosticPanel(sv, caps.optBoolean("network_diagnostics"))
    IntegrationTestPanel(sv, snapshot.integrations, caps.optBoolean("webhooks"), caps.optBoolean("telegram"))
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
                } } catch (e: CancellationException) { throw e
                } catch (e: Exception) { error = friendlyError(e) }
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
private fun IntegrationTestPanel(sv: Server, integrationStatus: JSONObject?, webhookEnabled: Boolean, telegramEnabled: Boolean) {
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Panel {
        Label("Notification integrations")
        Text("Webhook URL, webhook secret and Telegram bot credentials are configured on the backend; the app never stores or displays these secrets.", color = Mut, fontSize = 11.sp)
        DiagRow("Webhook destination", if (webhookEnabled) "Configured" else "Not configured", if (webhookEnabled) Green else Dim)
        DiagRow("Telegram bot", if (telegramEnabled) "Configured" else "Not configured", if (telegramEnabled) Green else Dim)
        val integrationItems = integrationStatus?.optJSONArray("items")
        if (integrationItems != null) {
            for (i in 0 until integrationItems.length()) {
                val item = integrationItems.optJSONObject(i) ?: continue
                val configured = item.optBoolean("configured")
                DiagRow(item.optString("name").replaceFirstChar { it.uppercase() }, if (configured) "Ready" else "Not configured", if (configured) Green else Dim)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                busy = true; status = null
                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { ApiClient.getJson(sv.url, sv.token, "/api/integrations/webhook/test", JSONObject()) }
                        status = if (response.optBoolean("ok")) "Webhook test delivered." else "Webhook test failed."
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { status = friendlyError(e) }
                    busy = false
                }
            }, enabled = webhookEnabled && !busy, modifier = Modifier.weight(1f)) { Text("Test webhook") }
            OutlinedButton(onClick = {
                busy = true; status = null
                scope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { ApiClient.getJson(sv.url, sv.token, "/api/integrations/telegram/test", JSONObject()) }
                        status = if (response.optBoolean("ok")) "Telegram test delivered." else "Telegram test failed."
                    } catch (e: Exception) { status = friendlyError(e) }
                    busy = false
                }
            }, enabled = telegramEnabled && !busy, modifier = Modifier.weight(1f)) { Text("Test Telegram") }
        }
        status?.let { Text(it, color = if (it.contains("delivered")) Green else Amber, fontSize = 11.sp) }
        if (!webhookEnabled && !telegramEnabled) Text("Configure an HTTPS webhook or Telegram bot on the backend to enable testing.", color = Dim, fontSize = 11.sp)
    }
}


@Composable
private fun NetworkTopologyPanel(topology: JSONObject?, enabled: Boolean) {
    Panel {
        Label("Network topology")
        if (!enabled) {
            Text("Enable LAN discovery on the monitoring backend to view its passive network inventory.", color = Dim, fontSize = 11.sp)
        } else {
            val nodes = topology?.optJSONArray("nodes")
            val links = topology?.optJSONArray("links")
            val nodeItems = if (nodes == null) emptyList() else
                (0 until minOf(nodes.length(), 40)).mapNotNull { nodes.optJSONObject(it) }
            val linkItems = if (links == null) emptyList() else
                (0 until minOf(links.length(), 40)).mapNotNull { links.optJSONObject(it) }
            val linkedIps = linkItems.map { it.optString("target") }.toSet()
            val linkedNodes = nodeItems.filter { it.optString("ip") in linkedIps }
            val unmappedNodes = nodeItems.filter { it.optString("topology_status") == "unmapped" }
            val gateway = topology?.optString("gateway").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val routeInterface = topology?.optString("interface").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val subnet = topology?.optString("subnet").orEmpty().takeIf { it.isNotBlank() && it != "null" }

            Text("${nodeItems.size} devices · ${linkItems.size} logical connections", color = Txt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (gateway != null || routeInterface != null || subnet != null) {
                Text("Gateway: ${gateway ?: "not detected"}  ·  ${routeInterface ?: "interface unknown"}  ·  ${subnet ?: "subnet unknown"}",
                    color = Mut, fontSize = 10.sp)
            }

            Spacer(Modifier.height(10.dp))
            Text("ALLERION SERVER", color = Mut, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                val centerX = size.width / 2f
                val serverY = 12.dp.toPx()
                val gatewayY = 64.dp.toPx()
                val deviceY = 132.dp.toPx()
                val red = Color(0xFFE50914)
                val green = Color(0xFF45C486)
                val muted = Color(0xFF777980)
                val line = Color(0xFF45464D)
                val server = Offset(centerX, serverY)
                val router = Offset(centerX, gatewayY)
                drawLine(line, server, router, strokeWidth = 2.dp.toPx())
                drawCircle(red, radius = 8.dp.toPx(), center = server)
                drawCircle(green, radius = 10.dp.toPx(), center = router)
                val count = linkedNodes.size
                linkedNodes.forEachIndexed { index, _ ->
                    val x = if (count <= 1) centerX else
                        18.dp.toPx() + index * (size.width - 36.dp.toPx()) / (count - 1).toFloat()
                    val device = Offset(x, deviceY)
                    drawLine(line, router, device, strokeWidth = 1.7.dp.toPx())
                    drawCircle(green, radius = 6.dp.toPx(), center = device)
                    drawCircle(Color(0xFF101113), radius = 2.dp.toPx(), center = device)
                }
                if (count == 0) {
                    drawCircle(muted, radius = 5.dp.toPx(), center = Offset(centerX, deviceY))
                }
            }
            Text("ROUTER / GATEWAY  ·  ${gateway ?: "not detected"}", color = Green, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            if (linkedNodes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Label("Connected devices")
                linkedNodes.forEach { node ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(node.optString("ip"), color = Txt, fontSize = 11.sp)
                        Text(node.optString("mac").ifBlank { "MAC unavailable" }, color = Mut, fontSize = 10.sp)
                    }
                }
                Text("Gateway paths are inferred from same-subnet neighbor records, not verified physical links.",
                    color = Dim, fontSize = 10.sp)
            }

            if (unmappedNodes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Label("Unmapped devices · ${unmappedNodes.size}")
                unmappedNodes.forEach { node ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(node.optString("ip"), color = Txt, fontSize = 11.sp)
                            Text(node.optString("mac").ifBlank { "No MAC" }, color = Mut, fontSize = 10.sp)
                        }
                        Text(node.optString("topology_reason", "Insufficient passive network data"),
                            color = Dim, fontSize = 10.sp)
                    }
                }
            }

            val bridgeMacs = topology?.optJSONArray("unmapped_macs")
            val bridgeCount = topology?.optInt("bridge_mac_count", 0) ?: 0
            if (bridgeCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Additional passive bridge observations: $bridgeCount MAC addresses; ${bridgeMacs?.length() ?: 0} do not currently map to an IP address.",
                    color = Mut, fontSize = 10.sp)
                if (bridgeMacs != null && bridgeMacs.length() > 0) {
                    for (i in 0 until minOf(bridgeMacs.length(), 8)) {
                        val entry = bridgeMacs.optJSONObject(i) ?: continue
                        Text(entry.optString("mac") + "  ·  " +
                            (entry.optJSONArray("interfaces")?.let { arr ->
                                (0 until arr.length()).joinToString { arr.optString(it) }
                            } ?: "bridge interface unknown"), color = Dim, fontSize = 10.sp)
                    }
                }
            }

            Text(topology?.optString("note").orEmpty().ifBlank {
                "Connections are inferred from the active gateway route and local subnet; physical links are not verified."
            }, color = Dim, fontSize = 10.sp)
        }
    }
}
