package com.example.servermonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AdGuardConfig(val url: String, val username: String, val password: String)
private data class AdGuardSnapshot(val enabled: Boolean, val version: String, val queries: Long, val blocked: Long, val clients: Int)

internal object AdGuardConfigStore {
    private const val PREF = "allerion_adguard"
    private const val ALIAS = "allerion.adguard.credentials.v1"
    fun isConfigured(context: Context, serverUrl: String): Boolean = load(context, serverUrl) != null
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    private fun prefKey(serverUrl: String) = "server_" + serverUrl.trim().trimEnd('/').lowercase().hashCode().toUInt().toString(16)
    internal fun load(context: Context, serverUrl: String): AdGuardConfig? = runCatching {
        val raw = context.getSharedPreferences(PREF, 0).getString(prefKey(serverUrl), null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val ivSize = bytes[0].toInt() and 255
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(1, ivSize + 1)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(ivSize + 1, bytes.size)), Charsets.UTF_8))
        AdGuardConfig(json.getString("url"), json.getString("username"), json.getString("password"))
    }.getOrNull()
    internal fun save(context: Context, serverUrl: String, config: AdGuardConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val json = JSONObject().put("url", config.url).put("username", config.username).put("password", config.password)
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(json.toString().toByteArray())
        check(context.getSharedPreferences(PREF, 0).edit().putString(prefKey(serverUrl), Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
    internal fun clear(context: Context, serverUrl: String) {
        context.getSharedPreferences(PREF, 0).edit().remove(prefKey(serverUrl)).apply()
    }
}

private object AdGuardApi {
    private fun call(config: AdGuardConfig, path: String, method: String = "GET"): JSONObject {
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) { "Enter the AdGuard Home URL including http:// or https://." }
        val connection = URL(config.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 9000
            val credentials = Base64.encodeToString((config.username + ":" + config.password).toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $credentials")
            connection.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{}".toByteArray()) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("AdGuard Home returned HTTP $code. Check its URL and administrator credentials.")
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
    fun snapshot(config: AdGuardConfig): AdGuardSnapshot {
        val status = call(config, "/control/status")
        val stats = runCatching { call(config, "/control/stats") }.getOrDefault(JSONObject())
        val clients = runCatching { call(config, "/control/clients") }.getOrDefault(JSONObject())
        return AdGuardSnapshot(
            status.optBoolean("protection_enabled", false),
            status.optString("version", "AdGuard Home"),
            stats.optLong("num_dns_queries"),
            stats.optLong("num_blocked_filtering"),
            clients.optJSONArray("clients")?.length() ?: 0
        )
    }
    fun setProtection(config: AdGuardConfig, enabled: Boolean): AdGuardSnapshot {
        call(config, if (enabled) "/control/protection/enable" else "/control/protection/disable", "POST")
        return snapshot(config)
    }
}

@Composable
fun AdGuardManagementScreen(server: Server, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember(server.url) { mutableStateOf(AdGuardConfigStore.load(context, server.url)) }
    var discoveredUrl by remember(server.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(server.url, server.token) {
        discoveredUrl = withContext(Dispatchers.IO) {
            runCatching {
                val integrations = ApiClient.getJson(server.url, server.token, "/api/integrations", connectTimeoutMs = 3500, readTimeoutMs = 5000)
                val items = integrations.optJSONArray("items") ?: integrations.optJSONArray("integrations")
                var webPort: Int? = null
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (!(item.optString("name").equals("adguard", true) ||
                          item.optString("id").equals("adguard", true))) continue
                    val containers = item.optJSONArray("containers")
                    val first = containers?.optJSONObject(0)
                    webPort = first?.optInt("web_port")?.takeIf { it > 0 }
                    break
                }
                val base = URI(server.url)
                val scheme = if (base.scheme == "https") "https" else "http"
                val host = base.host ?: return@runCatching null
                val port = webPort ?: 3000
                "$scheme://$host:$port"
            }.getOrNull()
        }
    }
    var url by remember(config, discoveredUrl) { mutableStateOf(config?.url ?: discoveredUrl.orEmpty()) }
    var username by remember(config) { mutableStateOf(config?.username.orEmpty()) }
    var password by remember(config) { mutableStateOf(config?.password.orEmpty()) }
    var snapshot by remember(server.url) { mutableStateOf<AdGuardSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(config == null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(config, discoveredUrl, revision) {
        val active = config ?: discoveredUrl?.let { AdGuardConfig(it, "", "") } ?: return@LaunchedEffect
        busy = true
        try {
            snapshot = withContext(Dispatchers.IO) { AdGuardApi.snapshot(active) }
            error = null
            if (config == null) editing = false
        } catch (e: Exception) {
            error = e.message ?: "Unable to connect to AdGuard Home."
            if (config == null) editing = true
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        ModulePageHeader("DNS PROTECTION", "AdGuard Management", server.name, onBack, { editing = !editing }) {
            IconBtn(Ic.Refresh, "Refresh", onClick = { revision++ })
        }
        if (editing || (config == null && snapshot == null)) {
            Panel {
                Text("AdGuard Home connection", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                Text("Credentials are encrypted on this device and stored separately for each Allerion server.", color = Mut, fontSize = AppType.small)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("AdGuard Home URL") }, singleLine = true)
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Administrator username") }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Administrator password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = {
                    val next = AdGuardConfig(url.trim().trimEnd('/'), username.trim(), password)
                    busy = true
                    error = null
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { AdGuardApi.snapshot(next) } }
                        result.onSuccess {
                            AdGuardConfigStore.save(context, server.url, next)
                            config = next
                            snapshot = it
                            editing = false
                        }.onFailure { error = it.message ?: "Connection failed." }
                        busy = false
                    }
                }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Connecting…" else "Test and save connection")
                }
            }
        }
        error?.let { Panel { Text(it, color = Amber, fontSize = AppType.small) } }
        snapshot?.let { data ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("DNS PROTECTION", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                        Text(if (data.enabled) "Protection active" else "Protection paused", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Status(data.enabled)
                }
                Button(onClick = {
                    val active = config ?: discoveredUrl?.let { AdGuardConfig(it, "", "") } ?: return@Button
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { AdGuardApi.setProtection(active, !data.enabled) } }
                        result.onSuccess { snapshot = it; error = null }.onFailure { error = it.message ?: "Action failed." }
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (data.enabled) "Pause protection" else "Enable protection")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryStat("DNS queries", data.queries.toString(), Txt, Modifier.weight(1f))
                SummaryStat("Blocked", data.blocked.toString(), Green, Modifier.weight(1f))
                SummaryStat("Clients", data.clients.toString(), Blue, Modifier.weight(1f))
            }
            Panel {
                Text("AdGuard Home " + data.version, color = Mut, fontSize = AppType.small)
                Text("DNS statistics and protection toggle are available here. Filtering rules and client settings remain managed in AdGuard Home.", color = Dim, fontSize = AppType.caption)
            }
            TextButton(onClick = {
                AdGuardConfigStore.clear(context, server.url)
                config = null; snapshot = null; url = ""; username = ""; password = ""; editing = true
            }) { Text("Disconnect this server integration", color = Err) }
        }
    }
}
