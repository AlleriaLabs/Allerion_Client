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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FleetIntegrationState(
    val server: Server,
    val webhook: Boolean = false,
    val telegram: Boolean = false,
    val detail: String? = null
)

@Composable
fun IntegrationCenterScreen(servers: List<Server>, onBack: () -> Unit, onSettings: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val states by produceState(initialValue = emptyList<FleetIntegrationState>(), servers) {
        while (true) {
            val refreshed = mutableListOf<FleetIntegrationState>()
            for (server in servers) {
                val state = withContext(Dispatchers.IO) {
                    try {
                        val caps = ApiClient.getJson(server.url, server.token, "/api/capabilities",
                            connectTimeoutMs = 2500, readTimeoutMs = 5000)
                        val integrations = ApiClient.getJson(server.url, server.token, "/api/integrations",
                            connectTimeoutMs = 2500, readTimeoutMs = 5000)
                        val items = integrations.optJSONArray("items")
                        val features = caps.optJSONObject("features")
                        var webhook = features?.optBoolean("webhooks") ?: false
                        var telegram = features?.optBoolean("telegram") ?: false
                        if (items != null) for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val name = item.optString("name").lowercase()
                            if (name.contains("webhook")) webhook = item.optBoolean("configured", webhook)
                            if (name.contains("telegram")) telegram = item.optBoolean("configured", telegram)
                        }
                        FleetIntegrationState(server, webhook, telegram)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FleetIntegrationState(server, detail = friendlyError(error))
                    }
                }
                refreshed.add(state)
            }
            value = refreshed
            delay(60_000L)
        }
    }

    var busyKey by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        ModulePageHeader("CONNECTIVITY", "Integration Center", "Webhook and Telegram across your servers", onBack, onSettings = onSettings)
        Column(Modifier.fillMaxSize().padding(horizontal = 15.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Text(if (state.detail == null) "Connected" else "Unavailable",
                            color = if (state.detail == null) Green else Amber, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold)
                    }
                    if (state.detail != null) {
                        Text(state.detail, color = Amber, fontSize = AppType.small)
                    } else {
                        IntegrationStatusRow("Webhook", state.webhook)
                        IntegrationStatusRow("Telegram", state.telegram)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val actionKey = key + ":webhook"
                                    scope.launch {
                                        busyKey = actionKey
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                ApiClient.getJson(state.server.url, state.server.token, "/api/integrations/webhook/test",
                                                    org.json.JSONObject(), connectTimeoutMs = 4000, readTimeoutMs = 10000)
                                            }
                                            feedback = feedback + (key to if (response.optBoolean("ok")) "Webhook test delivered." else "Webhook test failed.")
                                        } catch (cancelled: CancellationException) { throw cancelled
                                        } catch (error: Exception) { feedback = feedback + (key to friendlyError(error)) }
                                        finally { busyKey = null }
                                    }
                                },
                                enabled = state.webhook && busyKey == null, modifier = Modifier.weight(1f)
                            ) { Text(if (busyKey == key + ":webhook") "Testing…" else "Test webhook") }
                            OutlinedButton(
                                onClick = {
                                    val actionKey = key + ":telegram"
                                    scope.launch {
                                        busyKey = actionKey
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                ApiClient.getJson(state.server.url, state.server.token, "/api/integrations/telegram/test",
                                                    org.json.JSONObject(), connectTimeoutMs = 4000, readTimeoutMs = 10000)
                                            }
                                            feedback = feedback + (key to if (response.optBoolean("ok")) "Telegram test delivered." else "Telegram test failed.")
                                        } catch (cancelled: CancellationException) { throw cancelled
                                        } catch (error: Exception) { feedback = feedback + (key to friendlyError(error)) }
                                        finally { busyKey = null }
                                    }
                                },
                                enabled = state.telegram && busyKey == null, modifier = Modifier.weight(1f)
                            ) { Text(if (busyKey == key + ":telegram") "Testing…" else "Test Telegram") }
                        }
                        Text("Secrets remain on the backend. ALLERION does not display or store integration credentials.",
                            color = Dim, fontSize = AppType.caption)
                    }
                    feedback[key]?.let { Text(it, color = if (it.contains("delivered")) Green else Amber, fontSize = AppType.caption) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntegrationStatusRow(label: String, configured: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Mut, fontSize = AppType.small, modifier = Modifier.weight(1f))
        Text(if (configured) "Configured" else "Not configured",
            color = if (configured) Green else Dim, fontSize = AppType.small, fontWeight = FontWeight.SemiBold)
    }
}
