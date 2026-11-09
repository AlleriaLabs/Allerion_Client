package com.example.servermonitor

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

private object InfrastructureSnapshotCache {
    private data class Entry(val snapshot: InfrastructureSnapshot, val savedAt: Long)
    private val entries = mutableMapOf<String, Entry>()
    private fun key(server: Server) = server.url.trimEnd('/') + "#" + server.token.hashCode()
    fun get(server: Server): InfrastructureSnapshot? = synchronized(entries) { entries[key(server)]?.snapshot }
    fun isFresh(server: Server, maxAgeMs: Long = 30_000L): Boolean = synchronized(entries) {
        val entry = entries[key(server)] ?: return@synchronized false
        System.currentTimeMillis() - entry.savedAt < maxAgeMs
    }
    fun put(server: Server, snapshot: InfrastructureSnapshot) = synchronized(entries) {
        entries[key(server)] = Entry(snapshot, System.currentTimeMillis())
        if (entries.size > 12) entries.entries.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
    }
}

@Composable
private fun CollapsibleSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(title)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "−" else "+", color = Red, fontSize = AppType.section, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InfrastructurePanel(sv: Server, networkOnly: Boolean = false, powerOnly: Boolean = false) {
    var processesExpanded by remember { mutableStateOf(false) }
    var containersExpanded by remember { mutableStateOf(false) }
    var lanDevicesExpanded by remember { mutableStateOf(false) }
    val snapshot by produceState(InfrastructureSnapshotCache.get(sv) ?: InfrastructureSnapshot(), sv.url, sv.token) {
        while (true) {
            if (InfrastructureSnapshotCache.isFresh(sv)) {
                delay(30_000)
                continue
            }
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val caps = ApiClient.getJson(sv.url, sv.token, "/api/capabilities", connectTimeoutMs = 2500, readTimeoutMs = 5000)
                    val features = caps.optJSONObject("features") ?: JSONObject()
                    suspend fun optional(path: String): JSONObject? = try {
                        ApiClient.getJson(sv.url, sv.token, path, connectTimeoutMs = 2500, readTimeoutMs = 5000)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    coroutineScope {
                        val containersJob = async { if (features.optBoolean("containers")) optional("/api/containers") else null }
                        val processesJob = async { if (features.optBoolean("processes")) optional("/api/processes?limit=12") else null }
                        val servicesJob = async { if (features.optBoolean("services")) optional("/api/services") else null }
                        val devicesJob = async { if (features.optBoolean("network_inventory")) optional("/api/network/devices") else null }
                        val topologyJob = async { if (features.optBoolean("network_topology")) optional("/api/network/topology") else null }
                        val integrationsJob = async { optional("/api/integrations") }
                        val forecastJob = async { if (features.optBoolean("energy_forecast")) optional("/api/energy/forecast?days=30") else null }
                        val energyJob = async { if (features.optBoolean("energy_breakdown")) optional("/api/energy/breakdown") else null }
                        val containerData = containersJob.await()
                        val containerRows = containerData?.optJSONArray("items")
                        val statsRows = if (containerRows != null) coroutineScope {
                            (0 until minOf(containerRows.length(), 8)).mapNotNull { index ->
                                val item = containerRows.optJSONObject(index) ?: return@mapNotNull null
                                val id = item.optString("id")
                                if (item.optString("state") != "running" || id.isBlank()) null
                                else async { optional("/api/containers/$id/stats") }
                            }.mapNotNull { it.await() }
                        } else emptyList()
                        InfrastructureSnapshot(
                            capabilities = caps,
                            processes = processesJob.await(),
                            containers = containerData,
                            containerStats = statsRows,
                            services = servicesJob.await(),
                            devices = devicesJob.await(),
                            topology = topologyJob.await(),
                            integrations = integrationsJob.await(),
                            forecast = forecastJob.await(),
                            energyBreakdown = energyJob.await()
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    InfrastructureSnapshot(error = friendlyError(error))
                }
            }
            value = loaded
            if (loaded.capabilities != null) InfrastructureSnapshotCache.put(sv, loaded)
            delay(60_000)
        }
    }

    val caps = snapshot.capabilities?.optJSONObject("features")
    if (caps == null) {
        Panel {
            Label(when { networkOnly -> "Network intelligence"; powerOnly -> "Power intelligence"; else -> "Infrastructure intelligence" })
            Text(snapshot.error ?: "Checking backend capabilities…", color = Mut, fontSize = AppType.small)
            Text("This section requires ServerMonitor API v2.", color = Dim, fontSize = AppType.caption)
        }
        return
    }

    Panel {
        Label(when { networkOnly -> "Network capabilities"; powerOnly -> "Power capabilities"; else -> "Infrastructure intelligence" })
        Text("Live data from the ServerMonitor backend. Optional collectors appear only when enabled and available.", color = Mut, fontSize = AppType.body)
        Spacer(Modifier.height(8.dp))
        DiagRow("API version", snapshot.capabilities?.optInt("api_version", 1)?.toString() ?: "Unknown", Green)
        if (networkOnly) {
            DiagRow("LAN inventory", if (caps.optBoolean("network_inventory")) "Enabled" else "Opt-in disabled", if (caps.optBoolean("network_inventory")) Green else Dim)
            DiagRow("Network topology", if (caps.optBoolean("network_topology")) "Available" else "Not configured", if (caps.optBoolean("network_topology")) Green else Dim)
            DiagRow("Live packet capture", if (caps.optBoolean("network_traffic")) "Enabled" else "Not configured", if (caps.optBoolean("network_traffic")) Green else Dim)
            DiagRow("LAN diagnostics", if (caps.optBoolean("network_diagnostics")) "Available" else "Not configured", if (caps.optBoolean("network_diagnostics")) Green else Dim)
        } else if (!powerOnly) {
            DiagRow("Containers", if (caps.optBoolean("containers")) "Available" else "Not configured", if (caps.optBoolean("containers")) Green else Dim)
            DiagRow("Service checks", if (caps.optBoolean("services")) "Enabled" else "Not configured", if (caps.optBoolean("services")) Green else Dim)
            DiagRow("Webhook", if (caps.optBoolean("webhooks")) "Configured" else "Not configured", if (caps.optBoolean("webhooks")) Green else Dim)
            DiagRow("Telegram", if (caps.optBoolean("telegram")) "Configured" else "Not configured", if (caps.optBoolean("telegram")) Green else Dim)
        }
    }

    if (!networkOnly && !powerOnly && caps.optBoolean("processes")) {
        val rows = snapshot.processes?.optJSONArray("items")
        Panel {
            CollapsibleSectionHeader("Top processes", processesExpanded) { processesExpanded = !processesExpanded }
            if (processesExpanded) {
            if (rows == null || rows.length() == 0) Text("Process data is not available yet.", color = Dim, fontSize = AppType.small)
            else for (i in 0 until rows.length()) {
                val p = rows.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.optString("name"), color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("PID " + p.optInt("pid") + " · " + p.optString("user"), color = Dim, fontSize = AppType.caption)
                    }
                    Text("%.1f%% · %d MB".format(p.optDouble("cpu_pct"), p.optLong("memory_bytes") / 1_000_000), color = Mut, fontSize = AppType.caption)
                }
            }
            }
        }
    }

    if (!networkOnly && !powerOnly && caps.optBoolean("containers")) {
        val rows = snapshot.containers?.optJSONArray("items")
        Panel {
            CollapsibleSectionHeader("Docker containers", containersExpanded) { containersExpanded = !containersExpanded }
            if (containersExpanded) {
            if (rows == null || rows.length() == 0) Text("No containers found or Docker API is unavailable.", color = Dim, fontSize = AppType.small)
            else for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(item.optString("name"), color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Medium)
                        Text(item.optString("image"), color = Dim, fontSize = AppType.caption, maxLines = 1)
                        val containerId = item.optString("id")
                        val usage = snapshot.containerStats.firstOrNull { it.optString("id") == containerId }
                        if (usage != null) {
                            val cpu = if (usage.isNull("cpu_pct")) "CPU n/a" else "CPU %.1f%%".format(usage.optDouble("cpu_pct"))
                            val memory = if (usage.isNull("memory_pct")) "RAM n/a" else "RAM %.1f%%".format(usage.optDouble("memory_pct"))
                            Text("$cpu · $memory", color = Mut, fontSize = AppType.caption)
                        } else if (item.optString("state") == "running") {
                            Text("Resource stats unavailable", color = Dim, fontSize = AppType.caption)
                        }
                    }
                    Text(item.optString("state"), color = if (item.optString("state") == "running") Green else Amber, fontSize = AppType.caption)
                }
            }
            }
        }
    }

    if (networkOnly && caps.optBoolean("services")) {
        val rows = snapshot.services?.optJSONArray("items")
        Panel {
            Label("Service health")
            if (rows == null || rows.length() == 0) Text("No configured service checks.", color = Dim, fontSize = AppType.small)
            else for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                DiagRow(item.optString("name"), item.optString("status") + (item.optInt("latency_ms").let { if (it > 0) " · ${it} ms" else "" }),
                    if (item.optString("status") == "up") Green else Err)
            }
        }
    }

    if (networkOnly && caps.optBoolean("network_inventory")) {
        val rows = snapshot.devices?.optJSONArray("items")
        Panel {
            CollapsibleSectionHeader("LAN devices", lanDevicesExpanded) { lanDevicesExpanded = !lanDevicesExpanded }
            if (lanDevicesExpanded) {
            Text("Passive neighbor discovery only. No unsolicited network sweep is performed.", color = Dim, fontSize = AppType.caption)
            if (rows == null || rows.length() == 0) Text("No devices in the current ARP / neighbor cache.", color = Mut, fontSize = AppType.small)
            else for (i in 0 until minOf(rows.length(), 30)) {
                val item = rows.optJSONObject(i) ?: continue
                Text(item.optString("ip") + "   " + item.optString("mac"), color = Txt, fontSize = AppType.small)
            }
            }
        }
    }

    if (powerOnly && snapshot.forecast?.optBoolean("available") == true) {
        val forecast = snapshot.forecast!!
        Panel {
            Label("Energy forecast")
            Text("%.2f kWh".format(forecast.optDouble("forecast_kwh")), color = Txt, fontSize = AppType.section, fontWeight = FontWeight.Bold)
            Text("Estimated next ${forecast.optInt("days")} days · based on ${forecast.optInt("observations")} recorded daily totals", color = Mut, fontSize = AppType.caption)
            Text("This is a trend estimate, not a measured reading.", color = Dim, fontSize = AppType.caption)
        }
    }

    if (powerOnly) {
    val energyDays = snapshot.energyBreakdown?.optJSONArray("days")
    val latestEnergyDay = if (energyDays != null && energyDays.length() > 0) energyDays.optJSONObject(energyDays.length() - 1) else null
    val energySources = latestEnergyDay?.optJSONArray("sources")
    if (energySources != null && energySources.length() > 0) {
        Panel {
            Label("Energy source breakdown")
            Text(latestEnergyDay.optString("day"), color = Dim, fontSize = AppType.caption)
            for (i in 0 until energySources.length()) {
                val source = energySources.optJSONObject(i) ?: continue
                DiagRow(source.optString("source"), "%.3f kWh".format(source.optDouble("kwh")), Mut)
            }
            Text("Older energy totals recorded before this backend update cannot be attributed retroactively.", color = Dim, fontSize = AppType.caption)
        }
    }
    }

    if (networkOnly) {
        NetworkTopologyPanel(sv, snapshot.topology, caps.optBoolean("network_topology"), caps.optBoolean("network_traffic"))
        NetworkDiagnosticPanel(sv, caps.optBoolean("network_diagnostics"))
    } else if (!powerOnly) {
        IntegrationTestPanel(sv, snapshot.integrations, caps.optBoolean("webhooks"), caps.optBoolean("telegram"))
    }
}

@Composable
fun PowerInsightsPanel(sv: Server) {
    InfrastructurePanel(sv, powerOnly = true)
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
        Text("Test TCP reachability to a private IPv4 address. Only ports 443, 80, 22 and 8080 are checked.", color = Mut, fontSize = AppType.caption)
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
        error?.let { Text(it, color = Amber, fontSize = AppType.caption) }
        result?.let { json ->
            Text("Target: " + json.optString("target"), color = Txt, fontSize = AppType.small)
            val probes = json.optJSONArray("probes")
            if (probes != null) for (i in 0 until probes.length()) {
                val p = probes.optJSONObject(i) ?: continue
                DiagRow("TCP ${p.optInt("port")}", p.optString("state") + (p.optInt("connect_ms").let { if (it > 0) " · ${it} ms" else "" }),
                    if (p.optString("state") == "open") Green else Dim)
            }
        }
        if (!enabled) Text("Diagnostics are unavailable on this backend version.", color = Dim, fontSize = AppType.caption)
    }
}

@Composable
private fun IntegrationTestPanel(sv: Server, integrationStatus: JSONObject?, webhookEnabled: Boolean, telegramEnabled: Boolean) {
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Panel {
        Label("Notification integrations")
        Text("Webhook URL, webhook secret and Telegram bot credentials are configured on the backend; the app never stores or displays these secrets.", color = Mut, fontSize = AppType.caption)
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
        status?.let { Text(it, color = if (it.contains("delivered")) Green else Amber, fontSize = AppType.caption) }
        if (!webhookEnabled && !telegramEnabled) Text("Configure an HTTPS webhook or Telegram bot on the backend to enable testing.", color = Dim, fontSize = AppType.caption)
    }
}


@Composable
private fun NetworkTopologyPanel(sv: Server, topology: JSONObject?, enabled: Boolean, trafficEnabled: Boolean) {
    var unmappedExpanded by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<JSONObject?>(null) }
    var topologyZoom by remember { mutableStateOf(1f) }
    val liveTraffic by produceState<JSONObject?>(null, sv.url, sv.token, trafficEnabled) {
        if (!trafficEnabled) value = null
        else while (true) {
            value = withContext(Dispatchers.IO) {
                try { ApiClient.getJson(sv.url, sv.token, "/api/network/traffic", connectTimeoutMs = 1500, readTimeoutMs = 1500) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            delay(1000)
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Network topology")
                Text("Live network map", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(Green.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("PASSIVE DISCOVERY", color = Green, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        if (!enabled) {
            Spacer(Modifier.height(10.dp))
            Text("Enable LAN discovery on the monitoring backend to view its passive network inventory.", color = Dim, fontSize = AppType.caption)
        } else {
            val nodes = topology?.optJSONArray("nodes")
            val links = topology?.optJSONArray("links")
            val nodeItems = if (nodes == null) emptyList() else (0 until minOf(nodes.length(), 40)).mapNotNull { nodes.optJSONObject(it) }
            val linkItems = if (links == null) emptyList() else (0 until minOf(links.length(), 40)).mapNotNull { links.optJSONObject(it) }
            val linkedIps = linkItems.map { it.optString("target") }.toSet()
            val linkedNodes = nodeItems.filter { it.optString("ip") in linkedIps }
            val unmappedNodes = nodeItems.filter { it.optString("topology_status") == "unmapped" }
            val gateway = topology?.optString("gateway").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val sourceIp = topology?.optString("source_ip").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val routeInterface = topology?.optString("interface").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val subnet = topology?.optString("subnet").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val trafficAvailable = liveTraffic?.optBoolean("available") == true
            val rxRate = liveTraffic?.optDouble("rx_bytes_per_sec") ?: 0.0
            val txRate = liveTraffic?.optDouble("tx_bytes_per_sec") ?: 0.0
            fun rateLabel(bytes: Double): String = if (bytes >= 1_000_000) "%.1f MB/s".format(bytes / 1_000_000.0) else "%.0f KB/s".format(bytes / 1_000.0)

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopologyStat("ENDPOINTS", linkedNodes.size.toString(), Blue, Modifier.weight(1f))
                GatewayTopologyStat(gateway != null, Modifier.weight(1f))
                TopologyStat("UNMAPPED", unmappedNodes.size.toString(), if (unmappedNodes.isEmpty()) Mut else Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFF19242A)).border(1.dp, Green.copy(alpha = 0.22f), RoundedCornerShape(13.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (trafficAvailable) Green else Dim, trafficAvailable, 6.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("LIVE INTERFACE TRAFFIC", color = if (trafficAvailable) Green else Mut, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    Text(if (trafficAvailable) "↓ " + rateLabel(rxRate) + "   ↑ " + rateLabel(txRate) else "Capture unavailable or waiting for data", color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.Medium)
                }
                Text(if (trafficAvailable) "LIVE" else "IDLE", color = if (trafficAvailable) Green else Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NETWORK MAP", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Text("Select a device to inspect it", color = Mut, fontSize = AppType.caption)
                }
                Text("−", modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { topologyZoom = (topologyZoom - 0.1f).coerceAtLeast(0.75f) }.padding(horizontal = 10.dp, vertical = 4.dp), color = Txt, fontSize = AppType.title)
                Text("${(topologyZoom * 100).toInt()}%", color = Mut, fontSize = AppType.caption)
                Text("+", modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { topologyZoom = (topologyZoom + 0.1f).coerceAtMost(1.2f) }.padding(horizontal = 10.dp, vertical = 4.dp), color = Txt, fontSize = AppType.title)
            }
            Spacer(Modifier.height(8.dp))
            if (gateway != null || sourceIp != null || linkedNodes.isNotEmpty()) {
                val rows = linkedNodes.chunked(2)
                val mapHeight = 190.dp + (rows.size * 104f).dp
                val activePeer = liveTraffic?.optString("active_peer").orEmpty()
                val activeIndex = linkedNodes.indexOfFirst { it.optString("ip") == activePeer }
                val packetAge = liveTraffic?.optLong("last_packet_age_ms", Long.MAX_VALUE) ?: Long.MAX_VALUE
                val packetCount = liveTraffic?.optLong("packet_count") ?: 0L
                val packetProgress = remember { Animatable(0f) }
                val reduceMotion = LocalReduceMotion.current
                LaunchedEffect(packetCount, activeIndex) {
                    if (packetCount > 0L && activeIndex >= 0) {
                        packetProgress.snapTo(0f)
                        packetProgress.animateTo(1f, tween(520, easing = LinearEasing))
                    }
                }
                Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF141B23)).border(1.dp, Color(0xFF2B3945), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectTransformGestures { _, _, zoomChange, _ -> topologyZoom = (topologyZoom * zoomChange).coerceIn(0.75f, 1.2f) } }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val topY = 48.dp.toPx()
                        val hostY = 137.dp.toPx()
                        val branchY = 177.dp.toPx()
                        val rowStart = 226.dp.toPx()
                        val rowGap = 104.dp.toPx()
                        val stroke = 1.6.dp.toPx()
                        val link = Green.copy(alpha = 0.48f)
                        // Fine map grid.
                        var gx = 0f
                        while (gx < size.width) { drawLine(Color(0xFF344454).copy(alpha = 0.16f), Offset(gx, 0f), Offset(gx, size.height), 0.6.dp.toPx()); gx += 24.dp.toPx() }
                        var gy = 0f
                        while (gy < size.height) { drawLine(Color(0xFF344454).copy(alpha = 0.16f), Offset(0f, gy), Offset(size.width, gy), 0.6.dp.toPx()); gy += 24.dp.toPx() }
                        drawLine(link, Offset(centerX, topY), Offset(centerX, hostY - 24.dp.toPx()), stroke)
                        drawLine(link, Offset(centerX, hostY + 24.dp.toPx()), Offset(centerX, branchY), stroke)
                        if (rows.isNotEmpty()) {
                            val leftX = size.width * 0.25f
                            val rightX = size.width * 0.75f
                            drawLine(link, Offset(leftX, branchY), Offset(rightX, branchY), stroke)
                            rows.forEachIndexed { row, pair ->
                                val y = rowStart + row * rowGap
                                val firstX = if (pair.size == 1) centerX else leftX
                                drawLine(link, Offset(firstX, branchY), Offset(firstX, y), stroke)
                                if (pair.size > 1) drawLine(link, Offset(rightX, branchY), Offset(rightX, y), stroke)
                                drawCircle(Green.copy(alpha = 0.8f), 3.dp.toPx(), Offset(firstX, y))
                                if (pair.size > 1) drawCircle(Green.copy(alpha = 0.8f), 3.dp.toPx(), Offset(rightX, y))
                            }
                            if (trafficAvailable && packetAge <= 1500L && activeIndex >= 0) {
                                val row = activeIndex / 2
                                val x = if (linkedNodes.size % 2 == 1 && activeIndex == linkedNodes.lastIndex) centerX else if (activeIndex % 2 == 0) leftX else rightX
                                val targetY = rowStart + row * rowGap
                                val y = if (reduceMotion) targetY else branchY + (targetY - branchY) * packetProgress.value
                                drawCircle(Color(0xFFB8F5D8), 4.dp.toPx(), Offset(x, y))
                                drawCircle(Color(0xFFB8F5D8).copy(alpha = 0.2f), 9.dp.toPx(), Offset(x, y))
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize().graphicsLayer { scaleX = topologyZoom; scaleY = topologyZoom }) {
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            TopologyMapNode("GATEWAY", gateway ?: "Not detected", "ROUTER", Green, true, Modifier.width(190.dp))
                        }
                        Spacer(Modifier.height(34.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            TopologyMapNode("MONITORING HOST", sourceIp ?: "Host address unavailable", routeInterface ?: "SERVER", Blue, true, Modifier.width(220.dp))
                        }
                        Spacer(Modifier.height(77.dp))
                        rows.forEachIndexed { row, pair ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { node ->
                                    val isSelected = selectedNode?.optString("ip") == node.optString("ip")
                                    Box(Modifier.weight(1f)) {
                                        TopologyMapDevice(node, isSelected, Modifier.fillMaxWidth()) { selectedNode = node }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(Green, true, 5.dp); Spacer(Modifier.width(5.dp)); Text("Discovered", color = Mut, fontSize = AppType.micro) }
                    Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(Blue, true, 5.dp); Spacer(Modifier.width(5.dp)); Text("Monitoring host", color = Mut, fontSize = AppType.micro) }
                    Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(Amber, false, 5.dp); Spacer(Modifier.width(5.dp)); Text("Unmapped", color = Mut, fontSize = AppType.micro) }
                }
                selectedNode?.let { node ->
                    Spacer(Modifier.height(10.dp))
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label("Selected device")
                                Text(node.optString("ip", "Unknown IP"), color = Txt, fontSize = AppType.section, fontWeight = FontWeight.Bold)
                            }
                            Text("CLOSE", Modifier.clip(RoundedCornerShape(8.dp)).clickable { selectedNode = null }.padding(8.dp), color = Mut, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        DiagRow("Status", node.optString("state", "Discovered"), Green)
                        DiagRow("MAC address", node.optString("mac", "Unavailable"), Mut)
                        DiagRow("Source", node.optString("source", "Passive neighbor cache"), Mut)
                        DiagRow("Interface", node.optString("interface", routeInterface ?: "Unknown"), Mut)
                        node.optString("hostname").takeIf { it.isNotBlank() && it != "null" }?.let { DiagRow("Hostname", it, Txt) }
                        node.optString("topology_reason").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, color = Dim, fontSize = AppType.caption) }
                    }
                }
            } else {
                GatewayStatusNode(gateway ?: "Not detected", gateway != null)
                Spacer(Modifier.height(10.dp))
                Text("No discovered LAN endpoints can currently be associated with this gateway.", color = Dim, fontSize = AppType.caption)
            }
            if (unmappedNodes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { unmappedExpanded = !unmappedExpanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Unmapped observations")
                    Spacer(Modifier.width(8.dp))
                    Text(unmappedNodes.size.toString(), color = Amber, fontSize = AppType.caption, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (unmappedExpanded) "−" else "+", color = Mut, fontSize = AppType.title)
                }
                if (unmappedExpanded) {
                    Spacer(Modifier.height(7.dp))
                    unmappedNodes.forEach { node ->
                        TopologyDevice(node, Amber, Modifier.fillMaxWidth(), node.optString("topology_reason", "Insufficient passive network data"))
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            val bridgeMacs = topology?.optJSONArray("unmapped_macs")
            val bridgeCount = topology?.optInt("bridge_mac_count", 0) ?: 0
            if (bridgeCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Additional bridge observations: $bridgeCount MAC addresses; ${bridgeMacs?.length() ?: 0} are not currently mapped to an IP address.", color = Mut, fontSize = AppType.caption)
                if (bridgeMacs != null) for (i in 0 until minOf(bridgeMacs.length(), 8)) {
                    val entry = bridgeMacs.optJSONObject(i) ?: continue
                    Text(entry.optString("mac") + "  ·  " + (entry.optJSONArray("interfaces")?.let { arr -> (0 until arr.length()).joinToString { arr.optString(it) } } ?: "bridge interface unknown"), color = Dim, fontSize = AppType.caption)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Host: ${sourceIp ?: "unknown"} · Interface: ${routeInterface ?: "unknown"} · Subnet: ${subnet ?: "unknown"}", color = Mut, fontSize = AppType.caption)
            Text(topology?.optString("note").orEmpty().ifBlank { "Connections are inferred from the active gateway route and local subnet; physical links are not verified." }, color = Dim, fontSize = AppType.caption)
        }
    }
}

@Composable
private fun TopologyMapNode(title: String, address: String, detail: String, tint: Color, online: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(13.dp)).background(Color(0xFF202B34)).border(1.dp, tint.copy(alpha = 0.38f), RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            PulseDot(tint, online, 7.dp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp)
            Text(address, color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(detail, color = Mut, fontSize = AppType.micro, maxLines = 1)
        }
    }
}

@Composable
private fun TopologyMapDevice(node: JSONObject, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Blue else Green
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) Blue.copy(alpha = 0.12f) else Color(0xFF1B242C))
        .border(1.dp, tint.copy(alpha = if (selected) 0.75f else 0.28f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(27.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            PulseDot(tint, true, 6.dp)
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(node.optString("ip", "Unknown IP"), color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(node.optString("state", "DISCOVERED").uppercase(), color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun GatewayTopologyStat(online: Boolean, modifier: Modifier) {
    val tint = if (online) Green else Dim
    Box(modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2029))
        .border(1.dp, Color(0xFF303946), RoundedCornerShape(14.dp))) {
        AnimatedSurfaceBackdrop(tint, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon24(Ic.Globe, tint, 20.dp)
            Spacer(Modifier.height(3.dp))
            Text("GATEWAY", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TopologyStat(label: String, value: String, tint: Color, modifier: Modifier) {
    Box(modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2029))
        .border(1.dp, Color(0xFF303946), RoundedCornerShape(14.dp))) {
        AnimatedSurfaceBackdrop(tint, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(value, color = tint, fontSize = AppType.title, fontWeight = FontWeight.Bold)
            Text(label, color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
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
            Canvas(Modifier.size(22.dp)) {
                val c = tint
                val w = size.width
                val h = size.height
                drawLine(c, Offset(w * 0.22f, h * 0.24f), Offset(w * 0.22f, h * 0.08f), 1.5.dp.toPx())
                drawLine(c, Offset(w * 0.78f, h * 0.24f), Offset(w * 0.78f, h * 0.08f), 1.5.dp.toPx())
                drawRoundRect(c, topLeft = Offset(w * 0.08f, h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
                drawCircle(c, 1.5.dp.toPx(), Offset(w * 0.28f, h * 0.52f))
                drawCircle(c, 1.5.dp.toPx(), Offset(w * 0.43f, h * 0.52f))
                drawLine(c, Offset(w * 0.2f, h * 0.86f), Offset(w * 0.8f, h * 0.86f), 1.5.dp.toPx())
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("GATEWAY", color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(address, color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(if (online) "ONLINE" else "NOT FOUND", color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
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
            Text(title, color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Text(detail, color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold, maxLines = 2, softWrap = true)
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
            Canvas(Modifier.size(30.dp)) {
                val c = tint
                val w = size.width
                val h = size.height
                drawRoundRect(c.copy(alpha = 0.18f), topLeft = Offset(w * 0.08f, h * 0.10f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(c, topLeft = Offset(w * 0.08f, h * 0.10f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                drawLine(c, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.86f), 1.5.dp.toPx())
                drawLine(c, Offset(w * 0.28f, h * 0.88f), Offset(w * 0.72f, h * 0.88f), 1.5.dp.toPx())
                drawCircle(c, 1.4.dp.toPx(), Offset(w * 0.5f, h * 0.42f))
            }
            Spacer(Modifier.width(8.dp))
            PulseDot(tint, tint == Green, 4.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                node.optString("ip").ifBlank { "IP unavailable" },
                color = Txt, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false
            )
            Spacer(Modifier.weight(1f))
            Text(
                node.optString("state").ifBlank { if (tint == Green) "DISCOVERED" else "UNMAPPED" }.uppercase(),
                color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "MAC  " + node.optString("mac").ifBlank { "unavailable" },
            color = Mut, fontSize = AppType.micro, maxLines = 1, softWrap = false
        )
        val source = node.optString("source").takeIf { it.isNotBlank() && it != "null" }
        val iface = node.optString("interface").takeIf { it.isNotBlank() && it != "null" }
        if (source != null || iface != null) {
            Text(
                listOfNotNull(source?.let { "Source: $it" }, iface?.let { "Interface: $it" }).joinToString("  ·  "),
                color = Dim, fontSize = AppType.micro, maxLines = 1, softWrap = false
            )
        }
        if (!reason.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(reason, color = Dim, fontSize = AppType.micro, maxLines = 2, softWrap = true)
        }
    }
}
