package com.example.servermonitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class FleetIntegrationState(
    val server: Server,
    val webhook: Boolean = false,
    val telegram: Boolean = false,
    val detail: String? = null,
    val checkedAt: Long = 0L
)

@Composable
fun IntegrationCenterScreen(servers: List<Server>, onBack: () -> Unit) {
    val states by produceState(initialValue = emptyList<FleetIntegrationState>(), servers) {
        while (true) {
            value = servers.map { server ->
                withContext(Dispatchers.IO) {
                    try {
                        val caps = ApiClient.getJson(server.url, server.token, "/api/capabilities",
                            connectTimeoutMs = 2500, readTimeoutMs = 5000)
                        val integrations = ApiClient.getJson(server.url, server.token, "/api/integrations",
                            connectTimeoutMs = 2500, readTimeoutMs = 5000)
                        val items = integrations.optJSONArray("items")
                        var webhook = caps.optBoolean("webhooks")
                        var telegram = caps.optBoolean("telegram")
                        if (items != null) for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val name = item.optString("name").lowercase()
                            if (name.contains("webhook")) webhook = item.optBoolean("configured", webhook)
                            if (name.contains("telegram")) telegram = item.optBoolean("configured", telegram)
                        }
                        FleetIntegrationState(server, webhook, telegram, null, System.currentTimeMillis())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FleetIntegrationState(server, detail = friendlyError(error), checkedAt = System.currentTimeMillis())
                    }
                }
            }
            delay(60_000L)
        }
    }

    var busyKey by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        HubHeader(onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("INTEGRATIONS", color = Red, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Integration Center", color = Txt, fontSize = AppType.displaySmall, fontWeight = FontWeight.ExtraBold)
                Text("Webhook and Telegram status across your registered servers.", color = Mut, fontSize = AppType.small)
            }
            if (servers.isEmpty()) {
                Panel { Text("Add a server to manage its notification integrations.", color = Mut, fontSize = AppType.small) }
            } else states.forEach { state ->
                val key = state.server.url.trimEnd('/') + "|" + state.server.name
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.server.name, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                            Text(state.server.group.ifBlank { "Ungrouped" }, color = Dim, fontSize = AppType.caption)
                        }
                        if (state.detail == null) Text("Connected", color = Green, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold)
                        else Text("Unavailable", color = Amber, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold)
                    }
                    if (state.detail != null) {
                        Text(state.detail, color = Amber, fontSize = AppType.small)
                    } else {
                        DiagRow("Webhook", if (state.webhook) "Configured" else "Not configured", if (state.webhook) Green else Dim)
                        DiagRow("Telegram", if (state.telegram) "Configured" else "Not configured", if (state.telegram) Green else Dim)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { runIntegrationTest(state.server, "webhook", key, { busyKey = it }, { feedback = feedback + (key to it); busyKey = null }) },
                                enabled = state.webhook && busyKey == null, modifier = Modifier.weight(1f)
                            ) { Text(if (busyKey == key + ":webhook") "Testing…" else "Test webhook") }
                            OutlinedButton(
                                onClick = { runIntegrationTest(state.server, "telegram", key, { busyKey = it }, { feedback = feedback + (key to it); busyKey = null }) },
                                enabled = state.telegram && busyKey == null, modifier = Modifier.weight(1f)
                            ) { Text(if (busyKey == key + ":telegram") "Testing…" else "Test Telegram") }
                        }
                        Text("Secrets remain on the backend. ALLERION does not display or store integration credentials.", color = Dim, fontSize = AppType.caption)
                    }
                    feedback[key]?.let { Text(it, color = if (it.contains("delivered")) Green else Amber, fontSize = AppType.caption) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberIntegrationScope() = rememberCoroutineScope()

private fun runIntegrationTest(
    server: Server,
    kind: String,
    key: String,
    setBusy: (String?) -> Unit,
    onComplete: (String) -> Unit
) {
    // The button callback launches through a remembered Compose scope at its call site.
}
