package com.example.servermonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class ServerModule(val id: String, val title: String, val description: String, val icon: Ic, val tint: Color)
private val serverModules = listOf(
    ServerModule("server", "Server Management", "Live health, resources, network and power", Ic.Server, Color(0xFF28D7E5)),
    ServerModule("live", "Live Users", "Connected devices and current playback", Ic.Activity, Color(0xFF54D6B0)),
    ServerModule("users", "User Management", "Accounts, invitations and access controls", Ic.Heart, Color(0xFF9A8CFF)),
    ServerModule("requests", "Allervue Requests", "Movie requests, status and availability", Ic.Download, Color(0xFFFFC477)),
    ServerModule("analytics", "Jellyfin Analysis", "Viewing trends, library and usage reports", Ic.Chart, Color(0xFF8FB8FF)),
    ServerModule("adguard", "AdGuard Management", "DNS protection, clients and filtering", Ic.Plug, Color(0xFF7ED7C5))
)

private fun advertisedFeature(caps: JSONObject?, integrations: JSONObject?, id: String): Boolean {
    val keys = when (id) {
        "live" -> listOf("live_users", "jellyfin_live_users")
        "users" -> listOf("user_management", "jellyfin_user_management", "jfago")
        "requests" -> listOf("allervue_requests", "seerr_requests", "jellyseerr")
        "analytics" -> listOf("jellyfin_analysis", "jellyfin_analytics", "allerflux")
        "adguard" -> listOf("adguard", "adguard_management")
        else -> emptyList()
    }
    val features = caps?.optJSONObject("features") ?: caps
    if (keys.any { key -> features?.optBoolean(key, false) == true }) return true
    val items = integrations?.optJSONArray("items") ?: integrations?.optJSONArray("integrations") ?: JSONArray()
    val terms = when (id) {
        "live" -> listOf("live users", "jellyfin")
        "users" -> listOf("user management", "jfa-go", "jfago")
        "requests" -> listOf("allervue", "seerr", "jellyseerr")
        "analytics" -> listOf("allerflux", "jellyfin analysis", "jellyfin analytics")
        "adguard" -> listOf("adguard")
        else -> emptyList()
    }
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val name = (item.optString("name") + " " + item.optString("id") + " " + item.optString("type")).lowercase()
        val configured = item.optBoolean("configured", false) || item.optBoolean("enabled", false) || item.optString("status").equals("connected", true)
        if (configured && terms.any { name.contains(it) }) return true
    }
    return false
}

private fun metricText(json: JSONObject, vararg keys: String): String {
    for (key in keys) {
        if (json.has(key) && !json.isNull(key)) {
            val value = json.opt(key)
            if (value is Number) return "%.1f".format(value.toDouble()) + "%"
            if (value is String && value.isNotBlank()) return value
        }
    }
    return "—"
}

@Composable
fun ServerModuleHub(server: Server, onBack: () -> Unit, onOpenModule: (String) -> Unit, onEdit: () -> Unit) {
    var loading by remember(server.url, server.token) { mutableStateOf(true) }
    var error by remember(server.url, server.token) { mutableStateOf<String?>(null) }
    var stats by remember(server.url, server.token) { mutableStateOf<JSONObject?>(null) }
    var supported by remember(server.url, server.token) { mutableStateOf(setOf<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(server.url, server.token, refresh) {
        loading = true; error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val live = ApiClient.getJson(server.url, server.token, "/api/stats", connectTimeoutMs = 4000, readTimeoutMs = 6000)
                if (!live.has("cpu")) throw ApiException("Monitoring service did not return valid server statistics.")
                val caps = runCatching { ApiClient.getJson(server.url, server.token, "/api/capabilities", connectTimeoutMs = 2500, readTimeoutMs = 4000) }.getOrNull()
                val integrations = runCatching { ApiClient.getJson(server.url, server.token, "/api/integrations", connectTimeoutMs = 2500, readTimeoutMs = 4000) }.getOrNull()
                Triple(live, caps, integrations)
            }
            stats = result.first
            supported = setOf("server") + serverModules.filter { it.id != "server" && advertisedFeature(result.second, result.third, it.id) }.map { it.id }
        } catch (e: Exception) { stats = null; supported = emptySet(); error = friendlyError(e) }
        finally { loading = false }
    }
    val online = stats != null
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        HubHeader(onSettings = onEdit, onBack = onBack, showSettings = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SERVER WORKSPACE", color = Red, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    Text(server.name, color = Txt, fontSize = AppType.displaySmall, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.url, color = Mut, fontSize = AppType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(Ic.Refresh, "Refresh server status", onClick = { refresh++ })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Status(online)
                Text(if (loading) "Checking services…" else if (online) "Connected · ${supported.size} modules available" else "Connection unavailable", color = Mut, fontSize = AppType.caption)
            }
            if (loading && stats == null) Panel { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Connecting to ${server.name}", color = Mut, fontSize = AppType.small) } }
            error?.let { Panel { Text("Server connection failed", color = Txt, fontWeight = FontWeight.Bold); Text(it, color = Mut, fontSize = AppType.small); TextButton(onClick = onEdit) { Text("Review connection settings") } } }
            stats?.let { live ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceMetric("CPU", metricText(live, "cpu"), Ic.Cpu, Modifier.weight(1f))
                    WorkspaceMetric("MEMORY", metricText(live, "ram", "memory"), Ic.Memory, Modifier.weight(1f))
                    WorkspaceMetric("TEMP", metricText(live, "temp", "temperature"), Ic.Thermo, Modifier.weight(1f))
                }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("SYSTEM STATUS", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp); Text(live.optString("hostname", live.optString("host", server.name)), color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold) }
                        PulseDot(Green, true, 7.dp); Text("HEALTHY", color = Green, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { Text("Uptime  " + live.optString("uptime", live.optString("uptime_human", "Monitoring active")), color = Mut, fontSize = AppType.caption); Text("Cores  " + live.optString("cores", "—"), color = Mut, fontSize = AppType.caption) }
                }
            }
            Text("AVAILABLE MODULES", color = Dim, fontSize = AppType.caption, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            if (!loading && online) {
                serverModules.filter { it.id in supported }.forEachIndexed { index, module ->
                    Panel(Modifier.staggerIn(index), onClick = { onOpenModule(module.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).background(module.tint.copy(alpha = 0.12f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon24(module.icon, module.tint, 23.dp) }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(module.title, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold); Text(module.description, color = Mut, fontSize = AppType.small) }
                            Icon24(Ic.ChevronD, Dim, 19.dp, Modifier.graphicsLayer { rotationZ = -90f })
                        }
                    }
                }
                if (supported.size == 1) Panel { Text("No additional integrations detected", color = Txt, fontWeight = FontWeight.SemiBold); Text("Connect Jellyfin, JFA-Go, Allervue Requests, Allerflux or AdGuard to this server. Modules appear only when the server reports that integration as configured.", color = Mut, fontSize = AppType.small) }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun WorkspaceMetric(label: String, value: String, icon: Ic, modifier: Modifier) {
    Panel(modifier) { Icon24(icon, Red, 18.dp); Spacer(Modifier.height(8.dp)); Text(label, color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text(value, color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1) }
}