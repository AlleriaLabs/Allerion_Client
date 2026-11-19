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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

private const val SEERR_BASE = "https://request.alleriafilmotheque.fyi"

private data class SeerrMovie(val id: Int, val title: String, val year: String, val status: String, val availability: String, val date: String)

private object SeerrSession {
    private const val PREF = "allerion_seerr"
    private const val ALIAS = "allerion.seerr.session.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
    fun save(ctx: Context, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val data = byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(value.toByteArray())
        check(ctx.getSharedPreferences(PREF, 0).edit().putString("cookie", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
    fun load(ctx: Context): String = runCatching {
        val raw = ctx.getSharedPreferences(PREF, 0).getString("cookie", null) ?: return ""
        val b = Base64.decode(raw, Base64.NO_WRAP); val n = b[0].toInt() and 255
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(1, n + 1)))
        String(c.doFinal(b.copyOfRange(n + 1, b.size)))
    }.getOrDefault("")
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, 0).edit().remove("cookie").apply() }
}

private object SeerrRequestsApi {
    private fun call(path: String, method: String = "GET", body: JSONObject? = null, cookie: String = ""): Pair<String, List<String>> {
        val c = URL(SEERR_BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 25000; c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Origin", SEERR_BASE); c.setRequestProperty("Referer", SEERR_BASE + "/")
            if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
                throw IllegalStateException("Seerr HTTP " + code + if (detail.isBlank()) "." else ": " + detail)
            }
            if (raw.trimStart().startsWith("<")) throw IllegalStateException("Seerr returned HTML instead of API data. Check its public URL.")
            val cookies = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value ?: emptyList() }
            return raw to cookies
        } finally { c.disconnect() }
    }

    fun login(user: String, pass: String): Pair<String, String> {
        require(user.isNotBlank() && pass.isNotBlank()) { "Enter your Jellyfin username and password." }
        val result = call("/api/v1/auth/jellyfin", "POST", JSONObject().put("username", user.trim()).put("password", pass))
        val cookie = result.second.map { it.substringBefore(';') }.firstOrNull { it.substringBefore('=').equals("connect.sid", true) }
            ?: throw IllegalStateException("Seerr did not return a session cookie. Check Jellyfin sign-in is enabled in Seerr.")
        val me = JSONObject(call("/api/v1/auth/me", cookie = cookie).first)
        return cookie to me.optString("displayName").ifBlank { me.optString("username") }.ifBlank { user }
    }

    fun movies(cookie: String): List<SeerrMovie> {
        val root = JSONObject(call("/api/v1/request?take=100&skip=0&sort=added", cookie = cookie).first)
        val list = root.optJSONArray("results") ?: root.optJSONArray("requests") ?: JSONArray()
        val out = mutableListOf<SeerrMovie>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val media = item.optJSONObject("media") ?: item
            if (!media.optString("mediaType").equals("movie", true)) continue
            val title = media.optString("title").ifBlank { media.optString("name") }.ifBlank { item.optString("title") }
            if (title.isBlank()) continue
            val date = media.optString("releaseDate")
            val year = date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
            val status = when (item.optInt("status")) { 1 -> "Pending"; 2 -> "Approved"; 3 -> "Declined"; 4 -> "Failed"; 5 -> "Completed"; else -> "Requested" }
            val availability = when (media.optInt("status")) { 4 -> "Partially available"; 5 -> "Available"; 6 -> "Unavailable"; else -> if (media.optBoolean("available")) "Available" else "Not available yet" }
            out += SeerrMovie(item.optInt("id", i), title, year, status, availability, item.optString("createdAt").take(10))
        }
        return out.sortedByDescending { it.id }
    }
}

@Composable
fun SeerrRequestsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cookie by remember { mutableStateOf(SeerrSession.load(ctx)) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var movies by remember { mutableStateOf<List<SeerrMovie>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var updated by remember { mutableStateOf("") }

    fun refresh() {
        if (cookie.isBlank() || loading) return
        scope.launch {
            loading = true; error = ""
            try {
                movies = withContext(Dispatchers.IO) { SeerrRequestsApi.movies(cookie) }
                updated = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            } catch (e: Exception) {
                error = e.message ?: "Could not load requests."
                if (error.contains("401") || error.contains("403")) { SeerrSession.clear(ctx); cookie = ""; movies = emptyList() }
            } finally { loading = false }
        }
    }
    LaunchedEffect(cookie) { if (cookie.isNotBlank()) refresh() }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        ModulePageHeader("MEDIA REQUESTS", "Seerr Requests", "Your requested movies and their current status", onBack,
            trailing = { if (cookie.isNotBlank()) TextButton(onClick = { refresh() }, enabled = !loading) { Text("Refresh", color = Red) } })
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cookie.isBlank()) {
                Panel {
                    Text("Sign in with Jellyfin", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                    Text("Use your own Jellyfin account to view the Seerr requests associated with it. Your password is used only for sign-in; the session is stored encrypted on this device.",
                        color = Mut, fontSize = AppType.small)
                    OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Jellyfin username") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Jellyfin password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Button(onClick = {
                        scope.launch {
                            loading = true; error = ""
                            try {
                                val result = withContext(Dispatchers.IO) { SeerrRequestsApi.login(username, password) }
                                SeerrSession.save(ctx, result.first); cookie = result.first; account = result.second; password = ""
                            } catch (e: Exception) { error = e.message ?: "Seerr sign-in failed." }
                            finally { loading = false }
                        }
                    }, enabled = !loading && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (loading) "Signing in…" else "Sign in to Seerr")
                    }
                    if (error.isNotBlank()) Text(error, color = Err, fontSize = AppType.small)
                    Text("request.alleriafilmotheque.fyi", color = Dim, fontSize = AppType.caption)
                }
            } else {
                Panel {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connected to Seerr", color = Green, fontSize = AppType.caption, fontWeight = FontWeight.Bold)
                            Text(if (account.isBlank()) "Session saved securely" else "Signed in as " + account, color = Mut, fontSize = AppType.small)
                            Text(if (updated.isBlank()) "Loading requests…" else "Updated " + updated, color = Dim, fontSize = AppType.caption)
                        }
                        TextButton(onClick = { SeerrSession.clear(ctx); cookie = ""; movies = emptyList(); account = ""; error = "" }) {
                            Text("Sign out", color = Red)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("Movies", movies.size.toString(), Txt, Modifier.weight(1f))
                    SummaryStat("Pending", movies.count { it.status == "Pending" }.toString(), Amber, Modifier.weight(1f))
                    SummaryStat("Available", movies.count { it.availability == "Available" }.toString(), Green, Modifier.weight(1f))
                }
                if (error.isNotBlank()) Panel { Text(error, color = Amber, fontSize = AppType.small) }
                if (loading && movies.isEmpty()) Panel { Text("Loading your movie requests…", color = Mut, fontSize = AppType.small) }
                else if (movies.isEmpty() && error.isBlank()) Panel {
                    Text("No movie requests yet", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                    Text("Movies you request through Seerr will appear here.", color = Mut, fontSize = AppType.small)
                }
                else movies.forEach { movie ->
                    Panel {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(movie.title + if (movie.year.isBlank()) "" else " (" + movie.year + ")",
                                    color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                                Text("Requested " + movie.date.ifBlank { "date unavailable" }, color = Dim, fontSize = AppType.caption)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(movie.status, color = when (movie.status) { "Approved", "Completed" -> Green; "Pending" -> Amber; "Declined", "Failed" -> Err; else -> Mut },
                                    fontSize = AppType.caption, fontWeight = FontWeight.Bold)
                                Text(movie.availability, color = if (movie.availability == "Available") Green else Dim, fontSize = AppType.micro)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
