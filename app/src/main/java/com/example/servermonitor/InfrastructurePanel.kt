package com.example.servermonitor

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.Alignment
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Network topology")
                Text("Gateway-to-endpoint map", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(Green.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("PASSIVE DISCOVERY", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        if (!enabled) {
            Spacer(Modifier.height(10.dp))
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
            val sourceIp = topology?.optString("source_ip").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val routeInterface = topology?.optString("interface").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val subnet = topology?.optString("subnet").orEmpty().takeIf { it.isNotBlank() && it != "null" }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopologyStat("LAN ENDPOINTS", linkedNodes.size.toString(), Blue, Modifier.weight(1f))
                GatewayTopologyStat(gateway != null, Modifier.weight(1f))
                TopologyStat("UNMAPPED", unmappedNodes.size.toString(), if (unmappedNodes.isEmpty()) Mut else Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            if (gateway != null && linkedNodes.isNotEmpty()) {
                // Full-width endpoint cards keep IP, MAC and source details readable on narrow phones.
                GatewayStatusNode(gateway, true)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label("DISCOVERED LAN ENDPOINTS")
                    Spacer(Modifier.weight(1f))
                    Text("${linkedNodes.size} CONNECTED", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                val rowStep = 82.dp
                val diagramHeight = rowStep * linkedNodes.size
                val reduceMotion = LocalReduceMotion.current
                val flow = rememberInfiniteTransition(label = "topology-network-flow")
                val packet by flow.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
                    label = "topology-network-packet"
                )
                Box(Modifier.fillMaxWidth().height(diagramHeight)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val trunkX = 11.dp.toPx()
                        val rowPx = rowStep.toPx()
                        val cardCenterOffset = 37.dp.toPx()
                        val stroke = 1.7.dp.toPx()
                        val line = Green.copy(alpha = 0.42f)
                        val lastY = (linkedNodes.size - 1) * rowPx + cardCenterOffset
                        drawLine(line, Offset(trunkX, cardCenterOffset), Offset(trunkX, lastY), stroke)
                        linkedNodes.indices.forEach { index ->
                            val y = index * rowPx + cardCenterOffset
                            drawLine(line, Offset(trunkX, y), Offset(size.width, y), stroke)
                            drawCircle(Green.copy(alpha = 0.75f), 2.5.dp.toPx(), Offset(trunkX, y))
                            drawCircle(Green.copy(alpha = 0.9f), 3.dp.toPx(), Offset(size.width - 2.dp.toPx(), y))
                        }
                        val packetY = if (reduceMotion) cardCenterOffset else
                            cardCenterOffset + (lastY - cardCenterOffset) * packet
                        drawCircle(Color(0xFFB8F5D8).copy(alpha = 0.95f), 4.dp.toPx(), Offset(trunkX, packetY))
                        drawCircle(Color(0xFFB8F5D8).copy(alpha = 0.18f), 8.dp.toPx(), Offset(trunkX, packetY))
                    }
                    Column(
                        Modifier.fillMaxSize().padding(start = 30.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        linkedNodes.forEach { node ->
                            Box(Modifier.fillMaxWidth().height(74.dp)) {
                                TopologyDevice(node, Green, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Animated links show inferred gateway-to-device associations from passive neighbor records. They do not represent verified physical cables or live traffic paths.",
                    color = Dim, fontSize = 10.sp
                )
            } else {
                GatewayStatusNode(gateway ?: "Not detected", gateway != null)
                Spacer(Modifier.height(10.dp))
                Text("No discovered LAN endpoints can currently be associated with this gateway.", color = Dim, fontSize = 11.sp)
            }

            if (unmappedNodes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Unmapped observations")
                    Spacer(Modifier.width(8.dp))
                    Text(unmappedNodes.size.toString(), color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(7.dp))
                unmappedNodes.forEach { node ->
                    TopologyDevice(node, Amber, Modifier.fillMaxWidth(), node.optString("topology_reason", "Insufficient passive network data"))
                    Spacer(Modifier.height(6.dp))
                }
            }
            val bridgeMacs = topology?.optJSONArray("unmapped_macs")
            val bridgeCount = topology?.optInt("bridge_mac_count", 0) ?: 0
            if (bridgeCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Additional bridge observations: $bridgeCount MAC addresses; ${bridgeMacs?.length() ?: 0} are not currently mapped to an IP address.",
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
            Spacer(Modifier.height(8.dp))
            Text("Host: ${sourceIp ?: "unknown"} · Interface: ${routeInterface ?: "unknown"} · Subnet: ${subnet ?: "unknown"}",
                color = Mut, fontSize = 10.sp)
            Text(topology?.optString("note").orEmpty().ifBlank {
                "Connections are inferred from the active gateway route and local subnet; physical links are not verified."
            }, color = Dim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GatewayTopologyStat(online: Boolean, modifier: Modifier) {
    val tint = if (online) Green else Dim
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2029))
        .border(1.dp, Color(0xFF303946), RoundedCornerShape(14.dp))) {
        AnimatedSurfaceBackdrop(tint, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon24(Ic.Globe, tint, 20.dp)
            Spacer(Modifier.height(3.dp))
            Text("GATEWAY", color = Dim, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TopologyStat(label: String, value: String, tint: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2029))
        .border(1.dp, Color(0xFF303946), RoundedCornerShape(14.dp))) {
        AnimatedSurfaceBackdrop(tint, Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(value, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Dim, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
        }
    }
}

@Composable
private fun GatewayStatusNode(address: String, online: Boolean) {
    val tint = if (online) Green else Dim
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF202630))
            .border(1.dp, tint.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon24(Ic.Globe, tint, 19.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("GATEWAY", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(address, color = Txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(if (online) "ONLINE" else "NOT FOUND", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopologyNode(title: String, detail: String, icon: Ic, tint: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF202630))
        .border(1.dp, tint.copy(alpha = 0.38f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center) { Icon24(icon, tint, 21.dp) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(detail, color = Txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, softWrap = true)
        }
        PulseDot(tint, true, 5.dp)
    }
}

@Composable
private fun TopologyConnector(tint: Color) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "topology-link")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "topology-packet")
    Canvas(Modifier.width(28.dp).height(24.dp)) {
        val x = size.width / 2f
        drawLine(tint.copy(alpha = 0.42f), Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        val y = if (reduceMotion) size.height * 0.5f else size.height * phase
        drawCircle(tint.copy(alpha = 0.9f), 3.dp.toPx(), Offset(x, y))
    }
}

@Composable
private fun TopologyBranch(count: Int) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "topology-branch")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "topology-flow")
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val center = size.width / 2f
        val y = 5.dp.toPx()
        val bottom = size.height
        val edge = if (count <= 1) center else 16.dp.toPx()
        val far = if (count <= 1) center else size.width - 16.dp.toPx()
        val stroke = 1.5.dp.toPx()
        drawLine(Green.copy(alpha = 0.48f), Offset(center, 0f), Offset(center, y), stroke)
        drawLine(Green.copy(alpha = 0.48f), Offset(edge, y), Offset(far, y), stroke)
        val points = minOf(count, 12)
        for (i in 0 until points) {
            val x = if (points <= 1) center else edge + i * (far - edge) / (points - 1)
            drawLine(Green.copy(alpha = 0.48f), Offset(x, y), Offset(x, bottom), stroke)
            drawCircle(Green, 3.dp.toPx(), Offset(x, bottom))
        }
        if (!reduceMotion) {
            val travelX = center + (far - edge) * (phase - 0.5f)
            drawCircle(Color(0xFFB8F5D8), 2.5.dp.toPx(), Offset(travelX, y))
        }
    }
}

@Composable
private fun TopologyDevice(node: JSONObject, tint: Color, modifier: Modifier, reason: String? = null) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "topology-device-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "topology-device-glow"
    )
    Column(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1B2029))
            .border(1.dp, tint.copy(alpha = if (reduceMotion) 0.28f else 0.18f + pulse * 0.16f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(tint, tint == Green, 4.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                node.optString("ip").ifBlank { "IP unavailable" },
                color = Txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false
            )
            Spacer(Modifier.weight(1f))
            Text(
                node.optString("state").ifBlank { if (tint == Green) "DISCOVERED" else "UNMAPPED" }.uppercase(),
                color = tint, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "MAC  " + node.optString("mac").ifBlank { "unavailable" },
            color = Mut, fontSize = 9.sp, maxLines = 1, softWrap = false
        )
        val source = node.optString("source").takeIf { it.isNotBlank() && it != "null" }
        val iface = node.optString("interface").takeIf { it.isNotBlank() && it != "null" }
        if (source != null || iface != null) {
            Text(
                listOfNotNull(source?.let { "Source: $it" }, iface?.let { "Interface: $it" }).joinToString("  ·  "),
                color = Dim, fontSize = 8.sp, maxLines = 1, softWrap = false
            )
        }
        if (!reason.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(reason, color = Dim, fontSize = 9.sp, maxLines = 2, softWrap = true)
        }
    }
}
