package com.example.servermonitor

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val traffic by produceState<JSONObject?>(null, sv.url, sv.token, trafficEnabled) {
        if (!trafficEnabled) { value = null; return@produceState }
        while (true) {
            value = withContext(Dispatchers.IO) {
                try { ApiClient.getJson(sv.url, sv.token, "/api/network/traffic", connectTimeoutMs = 2500, readTimeoutMs = 5000) }
                catch (_: Exception) { null }
            }
            delay(5000)
        }
    }
    val intelligence by produceState<JSONObject?>(null, sv.url, sv.token, trafficEnabled) {
        if (!trafficEnabled) { value = null; return@produceState }
        while (true) {
            value = withContext(Dispatchers.IO) {
                try { ApiClient.getJson(sv.url, sv.token, "/api/network/intelligence/history", connectTimeoutMs = 2500, readTimeoutMs = 5000) }
                catch (_: Exception) { null }
            }
            delay(30000)
        }
    }
    fun rate(value: Double): String = when {
        value >= 1048576 -> "%.2f MB/s".format(value / 1048576.0)
        value >= 1024 -> "%.1f KB/s".format(value / 1024.0)
        else -> "%.0f B/s".format(value)
    }
    val rx = traffic?.optDouble("rx_bytes_per_sec", 0.0) ?: 0.0
    val tx = traffic?.optDouble("tx_bytes_per_sec", 0.0) ?: 0.0
    val nodes = topology?.optJSONArray("nodes")
    val nodeRows = if (nodes == null) emptyList() else (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }
    val gateway = topology?.optString("gateway")?.takeIf { !it.isNullOrBlank() && it != "null" }.orEmpty()
    val gatewayNode = nodeRows.firstOrNull { it.optString("ip") == gateway }
    val peerRows = nodeRows.filter { it.optString("ip") != gateway }
    val subnet = topology?.optString("subnet")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Subnet unavailable"
    val sourceIp = topology?.optString("source_ip")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "IP unavailable"
    val live = traffic?.optBoolean("available") == true

    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Network map")
                Text("Live infrastructure overview", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.clip(RoundedCornerShape(50)).background((if (live) Green else Amber).copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (live) Green else Amber, true, 5.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (live) "LIVE" else "LIMITED", color = if (live) Green else Amber, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            }
        }
        Text("Logical LAN view built from the default route and passive neighbor-cache observations.", color = Mut, fontSize = AppType.caption)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1B2029)).padding(11.dp)) {
                Text("OBSERVED PEERS", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                Text(peerRows.size.toString(), color = Txt, fontSize = AppType.title, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1B2029)).padding(11.dp)) {
                Text("LOGICAL LINKS", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                Text((topology?.optInt("link_count", 0) ?: 0).toString(), color = Green, fontSize = AppType.title, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1B2029)).padding(11.dp)) {
                Text("UNMAPPED", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                Text((topology?.optInt("unmapped_node_count", 0) ?: 0).toString(), color = Amber, fontSize = AppType.title, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("MONITORING HOST", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF202630))
            .border(1.dp, Red.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Red.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon24(Ic.Server, Red, 22.dp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("CasaOS Server", color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Bold)
                Text(sourceIp + " · " + (traffic?.optString("interface")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "interface detecting"),
                    color = Mut, fontSize = AppType.caption, maxLines = 1)
            }
            PulseDot(Green, true, 6.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text("DEFAULT GATEWAY", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF202630))
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Green.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon24(Ic.Globe, Green, 22.dp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(gatewayNode?.optString("hostname")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Router / Gateway",
                    color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Bold)
                Text(if (gateway.isBlank()) "Gateway not detected" else gateway + " · " + subnet, color = Mut, fontSize = AppType.caption, maxLines = 1)
            }
            Text(if (gateway.isBlank()) "UNKNOWN" else "DETECTED", color = if (gateway.isBlank()) Amber else Green,
                fontSize = AppType.micro, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.width(2.dp).height(17.dp).background(Green.copy(alpha = 0.65f)))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("OBSERVED LAN DEVICES", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(peerRows.size.toString() + " discovered", color = Mut, fontSize = AppType.caption)
        }
        if (peerRows.isEmpty()) {
            Text(if (!enabled) "Network inventory is not enabled on the server." else "No passive neighbor entries are available yet.",
                color = Mut, fontSize = AppType.small)
        } else {
            peerRows.take(16).forEach { node ->
                val ip = node.optString("ip").ifBlank { "IP unavailable" }
                val hostname = node.optString("hostname").takeIf { it.isNotBlank() && it != "null" } ?: "Unknown device"
                val state = node.optString("topology_status").ifBlank { node.optString("state", "observed") }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1B2029)).border(1.dp, Line.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Blue.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        Text("⌘", color = Blue, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hostname, color = Txt, fontSize = AppType.small, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(ip + " · " + node.optString("mac").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                            color = Mut, fontSize = AppType.micro, maxLines = 1)
                    }
                    Text(state.replace('_', ' ').uppercase(), color = if (state == "linked" || state == "gateway") Green else Amber,
                        fontSize = AppType.micro, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (peerRows.size > 16) Text("+ " + (peerRows.size - 16) + " more devices in inventory", color = Mut, fontSize = AppType.caption)
        }
        Spacer(Modifier.height(9.dp))
        Text(topology?.optString("note")?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: "Associations are inferred from the active subnet and passive neighbor cache. They do not represent verified cables, switch ports, or device-to-device paths.",
            color = Dim, fontSize = AppType.micro)
    }

    if (trafficEnabled) {
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Live network traffic")
                    Text(traffic?.optString("status")?.uppercase()?.takeIf { it != "NULL" } ?: "Waiting for capture",
                        color = if (live) Green else Amber, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                }
                Text("5s refresh", color = Mut, fontSize = AppType.micro)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("DOWNLOAD", color = Dim, fontSize = AppType.micro)
                    Text(rate(rx), color = Color(0xFF55D6C2), fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("UPLOAD", color = Dim, fontSize = AppType.micro)
                    Text(rate(tx), color = Color(0xFF9B8CFF), fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("PACKETS", color = Dim, fontSize = AppType.micro)
                    Text(traffic?.optLong("packet_count", 0L)?.toString() ?: "—", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text("Interface: " + (traffic?.optString("interface")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: "Detecting") +
                " · Active peers: " + (traffic?.optJSONArray("recent_peers")?.length() ?: 0), color = Mut, fontSize = AppType.caption)
            Text("Traffic is limited to packets visible on the CasaOS monitoring interface.", color = Dim, fontSize = AppType.micro)
        }
    }
    NetworkIntelligenceHistoryPanel(intelligence)
}

@Composable
private fun TopologyLightStat(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White)
        .border(1.dp, Color(0xFFE1E7F0), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 9.dp)) {
        Text(value, color = tint, fontSize = AppType.titleSmall, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color(0xFF64748B), fontSize = AppType.micro, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TopologyMapNode(title: String, address: String, detail: String, tint: Color, online: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(13.dp)).background(Color.White)
            .border(1.dp, tint.copy(alpha = 0.30f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.45.sp, maxLines = 1)
            Text(address, color = Color(0xFF172554), fontSize = AppType.caption, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, color = Color(0xFF64748B), fontSize = AppType.micro, maxLines = 1)
        }
        Text("●", color = if (online) Color(0xFF16A36A) else Color(0xFFD59A28), fontSize = AppType.caption)
    }
}

@Composable
private fun TopologyMapDevice(node: JSONObject, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) Color(0xFF6559D9) else Color(0xFF2587C7)
    val title = node.optString("hostname").takeIf { it.isNotBlank() && it != "null" } ?: node.optString("ip", "Unknown device")
    Row(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color(0xFFEDEBFF) else Color.White)
            .border(1.dp, tint.copy(alpha = if (selected) 0.65f else 0.20f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text("●", color = tint, fontSize = AppType.micro)
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFF172554), fontSize = AppType.caption, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(node.optString("ip", "Unknown IP"), color = Color(0xFF64748B), fontSize = AppType.micro, maxLines = 1)
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

@Composable
private fun NetworkIntelligenceHistoryPanel(intelligence: JSONObject?) {
    val samples = intelligence?.optJSONArray("samples")
    val devices = intelligence?.optJSONArray("devices")
    val alerts = intelligence?.optJSONArray("alerts")
    val sampleRows = if (samples == null) emptyList() else (0 until samples.length()).mapNotNull { samples.optJSONObject(it) }.takeLast(96)
    val deviceRows = if (devices == null) emptyList() else (0 until minOf(devices.length(), 250)).mapNotNull { devices.optJSONObject(it) }
    val alertRows = if (alerts == null) emptyList() else (0 until alerts.length()).mapNotNull { alerts.optJSONObject(it) }.takeLast(8).reversed()
    fun rateLabel(value: Double): String = when {
        value >= 1048576 -> "%.1f MB/s".format(value / 1048576.0)
        value >= 1024 -> "%.1f KB/s".format(value / 1024.0)
        else -> "%.0f B/s".format(value)
    }
    fun seenLabel(value: Long): String {
        if (value <= 0L) return "Not recorded"
        return try { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(value * 1000L)) }
        catch (_: Exception) { "Recorded" }
    }
    Panel {
        Label("Network activity history")
        Text("Seven-day metadata history · samples recorded about every 15 seconds while traffic capture is queried.", color = Mut, fontSize = AppType.caption)
        if (sampleRows.size < 2) Text("History is collecting. Keep the monitoring service running to build a useful trend.", color = Amber, fontSize = AppType.small)
        else {
            val maxRate = sampleRows.maxOf { maxOf(it.optDouble("rx_bytes_per_sec"), it.optDouble("tx_bytes_per_sec")) }.coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(132.dp).padding(top = 8.dp)) {
                val left = 4.dp.toPx()
                val right = size.width - 4.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                for (line in 0..3) {
                    val y = top + (bottom - top) * line / 3f
                    drawLine(Color(0xFF344252).copy(alpha = 0.55f), Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                fun drawSeries(key: String, color: Color) {
                    val values = sampleRows.map { it.optDouble(key).coerceAtLeast(0.0) }
                    for (i in 1 until values.size) {
                        val x1 = left + (right - left) * (i - 1) / (values.size - 1).toFloat()
                        val x2 = left + (right - left) * i / (values.size - 1).toFloat()
                        val y1 = bottom - (bottom - top) * (values[i - 1] / maxRate).toFloat()
                        val y2 = bottom - (bottom - top) * (values[i] / maxRate).toFloat()
                        drawLine(color, Offset(x1, y1), Offset(x2, y2), 2.dp.toPx())
                    }
                }
                drawSeries("rx_bytes_per_sec", Color(0xFF55D6C2))
                drawSeries("tx_bytes_per_sec", Color(0xFF9B8CFF))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("● Download", color = Color(0xFF55D6C2), fontSize = AppType.caption)
                Text("● Upload", color = Color(0xFF9B8CFF), fontSize = AppType.caption)
                Spacer(Modifier.weight(1f))
                Text(sampleRows.size.toString() + " samples", color = Dim, fontSize = AppType.caption)
            }
            val latest = sampleRows.last()
            DiagRow("Latest download", rateLabel(latest.optDouble("rx_bytes_per_sec")), Color(0xFF55D6C2))
            DiagRow("Latest upload", rateLabel(latest.optDouble("tx_bytes_per_sec")), Color(0xFF9B8CFF))
            DiagRow("Observed peers", latest.optInt("active_peers").toString(), Mut)
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Observed devices")
            Spacer(Modifier.weight(1f))
            Text(deviceRows.size.toString() + " tracked", color = Mut, fontSize = AppType.caption)
        }
        Text("Passive ARP / neighbor-cache sightings only. A device may disappear from the current cache while remaining in history.", color = Dim, fontSize = AppType.caption)
        if (deviceRows.isEmpty()) Text("No device sightings have been recorded yet.", color = Mut, fontSize = AppType.small)
        else deviceRows.take(40).forEach { device ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val title = device.optString("hostname").takeIf { it.isNotBlank() && it != "null" } ?: device.optString("ip", "Unknown device")
                        Text(title, color = Txt, fontSize = AppType.small, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(device.optString("ip", "IP unavailable") + " · " + device.optString("mac").takeIf { it.isNotBlank() && it != "null" }.orEmpty(), color = Mut, fontSize = AppType.caption, maxLines = 1)
                    }
                    val vendor = device.optString("vendor").takeIf { it.isNotBlank() && it != "null" } ?: "Vendor unknown"
                    Text(vendor, color = Blue, fontSize = AppType.caption, maxLines = 1)
                }
                Text("First seen " + seenLabel(device.optLong("first_seen")) + " · Last seen " + seenLabel(device.optLong("last_seen")), color = Dim, fontSize = AppType.micro)
            }
            DividerLine()
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Network anomalies")
            Spacer(Modifier.weight(1f))
            Text(if (alertRows.isEmpty()) "No notices" else alertRows.size.toString() + " recent", color = if (alertRows.isEmpty()) Green else Amber, fontSize = AppType.caption)
        }
        if (alertRows.isEmpty()) Text("No traffic-spike or new-device notices have been recorded. This is not a guarantee that the LAN is free of threats.", color = Mut, fontSize = AppType.small)
        else alertRows.forEach { alert ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(alert.optString("type").replace('_', ' ').uppercase(), color = Amber, fontSize = AppType.caption, fontWeight = FontWeight.Bold)
                Text(alert.optString("message", "Network activity notice"), color = Txt, fontSize = AppType.small)
                Text(seenLabel(alert.optLong("ts")) + " · " + alert.optString("severity", "notice"), color = Dim, fontSize = AppType.micro)
            }
        }
        Text(intelligence?.optString("note").orEmpty().ifBlank { "History is supplied by the backend and is limited to metadata visible to the CasaOS host." }, color = Dim, fontSize = AppType.micro)
    }
}

@Composable
private fun DividerLine() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3542)))
}
