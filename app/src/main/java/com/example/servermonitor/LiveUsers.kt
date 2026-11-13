package com.example.servermonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private data class JfSessionConfig(val url: String, val key: String)
private data class JfSession(val user: String, val device: String, val client: String, val title: String, val detail: String, val state: String)

private object JfSessionStore {
    private const val PREF = "allerion_jellyfin_presence"
    private const val ALIAS = "allerion.jellyfin.presence.v1"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
    fun load(ctx: Context): JfSessionConfig? = runCatching {
        val raw = ctx.getSharedPreferences(PREF, 0).getString("config", null) ?: return null
        val b = Base64.decode(raw, Base64.NO_WRAP); val n = b[0].toInt() and 255
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b.copyOfRange(1, n + 1)))
        val j = JSONObject(String(c.doFinal(b.copyOfRange(n + 1, b.size)), Charsets.UTF_8))
        JfSessionConfig(j.getString("url"), j.getString("key"))
    }.getOrNull()
    fun save(ctx: Context, value: JfSessionConfig) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret())
        val bytes = byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(JSONObject().put("url", value.url).put("key", value.key).toString().toByteArray())
        check(ctx.getSharedPreferences(PREF, 0).edit().putString("config", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, 0).edit().remove("config").apply() }
}

private fun loadJellyfinSessions(config: JfSessionConfig): List<JfSession> {
    val base = config.url.trim().trimEnd('/')
    require(base.startsWith("https://") || base.startsWith("http://")) { "Enter a valid Jellyfin server URL." }
    require(config.key.isNotBlank()) { "Enter a Jellyfin API key." }
    val url = URL(base + "/Sessions")
    val http = url.openConnection() as HttpURLConnection
    http.connectTimeout = 8000; http.readTimeout = 10000
    http.setRequestProperty("Accept", "application/json")
    http.setRequestProperty("X-Emby-Token", config.key)
    http.setRequestProperty("X-MediaBrowser-Token", config.key)
    http.setRequestProperty("Authorization", "MediaBrowser Client=\"Allerion\", Device=\"Android\", DeviceId=\"allerion-android\", Version=\"1.0\", Token=\""+config.key+"\"")
    try {
        val code = http.responseCode
        if (code !in 200..299) {
            val stream = if (code >= 400) http.errorStream else null
            val body = stream?.bufferedReader()?.use { it.readText() }?.take(240)?.replace(Regex("[\\r\\n]+"), " ")
            val server = http.getHeaderField("Server")?.take(80)
            val detail = listOfNotNull(body?.takeIf { it.isNotBlank() }, server?.let { "Server: " + it }).joinToString(" · ")
            throw IllegalStateException(when (code) {
                401, 403 -> "Jellyfin authentication failed (HTTP " + code + "). " +
                    (detail.ifBlank { "Confirm the URL reaches Jellyfin directly and that the key is valid." })
                404 -> "Jellyfin Sessions endpoint not found (HTTP 404). Check the server URL. " + detail
                else -> "Jellyfin returned HTTP " + code + ". " + detail
            })
        }
        val array = JSONArray(http.inputStream.bufferedReader().use { it.readText() })
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val now = item.optJSONObject("NowPlayingItem")
            val play = item.optJSONObject("PlayState")
            val paused = play?.optBoolean("IsPaused", false) == true
            val state = if (now == null) "Online · idle" else if (paused) "Paused" else "Playing"
            val series = now?.optString("SeriesName").orEmpty()
            val season = now?.optInt("ParentIndexNumber", -1) ?: -1
            val episode = now?.optInt("IndexNumber", -1) ?: -1
            val detail = if (series.isNotBlank()) series + if (season >= 0 && episode >= 0) " · S" + season + " E" + episode else "" else ""
            JfSession(item.optString("UserName").ifBlank { "Unknown user" },
                item.optString("DeviceName").ifBlank { item.optString("Device").ifBlank { "Unknown device" } },
                item.optString("Client").ifBlank { "Jellyfin client" },
                now?.optString("Name").orEmpty(), detail, state)
        }
    } finally { http.disconnect() }
}

@Composable
fun LiveUsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(JfSessionStore.load(context)) }
    var url by remember { mutableStateOf(config?.url.orEmpty()) }
    var apiKey by remember { mutableStateOf(config?.key.orEmpty()) }
    var sessions by remember { mutableStateOf<List<JfSession>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(config == null) }

    suspend fun updateSessions() {
        val active = config ?: return
        busy = true
        try { sessions = withContext(Dispatchers.IO) { loadJellyfinSessions(active) }; error = null }
        catch (e: Exception) { error = e.message ?: "Unable to load Jellyfin sessions." }
        finally { busy = false }
    }
    LaunchedEffect(config, refresh) {
        if (config != null) while (true) { updateSessions(); delay(15000) }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModulePageHeader("JELLYFIN PRESENCE", "Live Users", "Online devices and current playback", onBack) {
            IconBtn(Ic.Gear, "Connection settings", plain = true, onClick = { editing = !editing })
            IconBtn(Ic.Refresh, "Refresh", plain = true, onClick = { refresh++ })
        }
        Panel {
            if (config != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresenceCount("ONLINE", sessions.size.toString(), Green, Modifier.weight(1f))
                PresenceCount("WATCHING", sessions.count { it.title.isNotBlank() && it.state == "Playing" }.toString(), Red, Modifier.weight(1f))
                PresenceCount("PAUSED", sessions.count { it.state == "Paused" }.toString(), Amber, Modifier.weight(1f))
            }
            if (config != null) Text("Auto-refresh · 15 seconds", color = Dim, fontSize = AppType.caption)
        }
        if (editing || config == null) Panel {
            Label("Jellyfin connection")
            Text("Use your Jellyfin server URL and an API key that can view sessions. This is separate from the Allerion monitoring token and JFA-Go login.", color = Mut, fontSize = AppType.small)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Jellyfin server URL") }, singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Jellyfin API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching {
                        val next = JfSessionConfig(url.trim().trimEnd('/'), apiKey.trim())
                        require(next.url.startsWith("https://") || next.url.startsWith("http://")) { "Enter a valid Jellyfin URL." }
                        require(next.key.isNotBlank()) { "Enter an API key." }
                        JfSessionStore.save(context, next); config = next; editing = false; refresh++
                    }.onFailure { error = it.message }
                }) { Text("Save connection") }
                if (config != null) TextButton(onClick = {
                    JfSessionStore.clear(context); config = null; sessions = emptyList(); url = ""; apiKey = ""; editing = true
                }) { Text("Disconnect", color = Err) }
            }
        }
        error?.let { Panel { Text(it, color = Amber, fontSize = AppType.small) } }
        if (config != null && sessions.isEmpty() && error == null && !busy) Panel {
            Text("No active Jellyfin sessions right now.", color = Mut, fontSize = AppType.small)
        }
        sessions.forEach { session ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (session.state == "Playing") Green else if (session.state == "Paused") Amber else Dim))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.user, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text(session.device + " · " + session.client, color = Mut, fontSize = AppType.caption, maxLines = 1)
                    }
                    Text(session.state.uppercase(), color = if (session.state == "Playing") Green else if (session.state == "Paused") Amber else Mut,
                        fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                }
                if (session.title.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(session.title, color = Txt, fontSize = AppType.body, fontWeight = FontWeight.SemiBold)
                    if (session.detail.isNotBlank()) Text(session.detail, color = Mut, fontSize = AppType.small)
                } else Text("Not currently playing", color = Dim, fontSize = AppType.small, modifier = Modifier.padding(start = 18.dp, top = 5.dp))
            }
        }
    }
}

@Composable
private fun PresenceCount(label: String, value: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.background(Surf2, RoundedCornerShape(13.dp)).padding(10.dp)) {
        Text(label, color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
        Text(value, color = tint, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
    }
}
