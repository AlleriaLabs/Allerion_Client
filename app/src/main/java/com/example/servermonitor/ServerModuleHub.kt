package com.example.servermonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class ServerModule(val id: String, val title: String, val description: String, val icon: Ic)

private val serverModules = listOf(
    ServerModule("server", "Server Management", "Health, resources, network and power", Ic.Server),
    ServerModule("live", "Live Users", "Current Jellyfin playback activity", Ic.Activity),
    ServerModule("users", "User Management", "Manage registered media users", Ic.Users),
    ServerModule("requests", "Allervue Requests", "Browse and track media requests", Ic.Chart),
    ServerModule("analytics", "Jellyfin Analysis", "Usage, library and viewing reports", Ic.Chart),
    ServerModule("adguard", "AdGuard Management", "DNS protection and query activity", Ic.Server)
)

private fun advertisedFeature(caps: JSONObject?, integrations: JSONObject?, id: String): Boolean {
    val featureKeys = when (id) {
        "live" -> listOf("live_users", "jellyfin_live_users")
        "users" -> listOf("user_management", "jellyfin_user_management")
        "requests" -> listOf("allervue_requests", "seerr_requests")
        "analytics" -> listOf("jellyfin_analysis", "jellyfin_analytics")
        "adguard" -> listOf("adguard", "adguard_management")
        else -> emptyList()
    }
    val features = caps?.optJSONObject("features")
    if (featureKeys.any { features?.optBoolean(it, false) == true }) return true
    val items = integrations?.optJSONArray("items") ?: return false
    val terms = when (id) {
        "live" -> listOf("live users", "jellyfin")
        "users" -> listOf("user management", "jellyfin users")
        "requests" -> listOf("allervue", "seerr")
        "analytics" -> listOf("jellyfin analysis", "jellyfin analytics")
        "adguard" -> listOf("adguard")
        else -> emptyList()
    }
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val name = item.optString("name").lowercase()
        if (terms.any { name.contains(it) } && item.optBoolean("configured", false)) return true
    }
    return false
}

@Composable
fun ServerModuleHub(server: Server, onBack: () -> Unit, onOpenModule: (String) -> Unit, onEdit: () -> Unit) {
    var loading by remember(server.url, server.token) { mutableStateOf(true) }
    var error by remember(server.url, server.token) { mutableStateOf<String?>(null) }
    var online by remember(server.url, server.token) { mutableStateOf(false) }
    var supported by remember(server.url, server.token) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(server.url, server.token) {
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val stats = ApiClient.getJson(server.url, server.token, "/api/stats", connectTimeoutMs = 3000, readTimeoutMs = 5000)
                if (!stats.has("cpu")) throw ApiException("Monitoring service did not return server statistics.")
                val caps = runCatching { ApiClient.getJson(server.url, server.token, "/api/capabilities", connectTimeoutMs = 2500, readTimeoutMs = 4000) }.getOrNull()
                val integrations = runCatching { ApiClient.getJson(server.url, server.token, "/api/integrations", connectTimeoutMs = 2500, readTimeoutMs = 4000) }.getOrNull()
                setOf("server") + serverModules.filter { it.id != "server" && advertisedFeature(caps, integrations, it.id) }.map { it.id }
            }
            supported = result
            online = true
        } catch (e: Exception) {
            online = false
            error = friendlyError(e)
            supported = emptySet()
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        HubHeader(onSettings = onEdit, onBack = onBack, showSettings = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SERVER WORKSPACE", color = Red, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(server.name, color = Txt, fontSize = AppType.displaySmall, fontWeight = FontWeight.ExtraBold)
                Text(server.url, color = Mut, fontSize = AppType.small, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Status(online)
                    Spacer(Modifier.width(9.dp))
                    Text(if (loading) "Checking available modules…" else if (online) "Only supported modules are shown" else "Server unavailable",
                        color = Mut, fontSize = AppType.caption)
                }
            }
            if (loading) {
                Panel { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Detecting server capabilities", color = Mut, fontSize = AppType.small) } }
            } else if (!online) {
                Panel {
                    Text("Could not detect this server", color = Txt, fontWeight = FontWeight.Bold)
                    Text(error ?: "Check the server connection and try again.", color = Mut, fontSize = AppType.small)
                    TextButton(onClick = { onEdit() }) { Text("Check server settings") }
                }
            } else {
                serverModules.filter { it.id in supported }.forEach { module ->
                    Panel(onClick = { onOpenModule(module.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(Surf2, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                Icon24(module.icon, Blue, 21.dp)
                            }
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(module.title, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                                Text(module.description, color = Mut, fontSize = AppType.small)
                            }
                            Icon24(Ic.ChevronD, Dim, 20.dp)
                        }
                    }
                }
                if (supported.size == 1) {
                    Panel {
                        Text("No other integrations detected", color = Txt, fontWeight = FontWeight.SemiBold)
                        Text("Connect a supported service to this server and expose its capability through the server API. Unsupported modules stay hidden.", color = Mut, fontSize = AppType.small)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
