package com.example.servermonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private data class JfaConn(val url: String, val username: String, val password: String)
private data class JfaAuth(val token: String, val refresh: String)

private object JfaStore {
    private const val PREF = "jfa_go_admin"
    private const val KEY = "connection"
    private const val ALIAS = "allerion.jfago.credentials.v1"
    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
    private fun seal(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret())
        return Base64.encodeToString(byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun open(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP); val n = b[0].toInt() and 255
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b.copyOfRange(1, n + 1)))
        return String(c.doFinal(b.copyOfRange(n + 1, b.size)))
    }
    fun load(ctx: Context): JfaConn? = runCatching {
        val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY, null) ?: return null
        val j = JSONObject(open(raw)); JfaConn(j.getString("url"), j.getString("username"), j.getString("password"))
    }.getOrNull()
    fun save(ctx: Context, c: JfaConn) {
        val j = JSONObject().put("url", c.url).put("username", c.username).put("password", c.password)
        check(ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, seal(j.toString())).commit())
    }
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, 0).edit().remove(KEY).apply() }
}

private object JfaApi {
    private fun request(c: JfaConn, path: String, method: String = "GET", body: JSONObject? = null,
                        bearer: String? = null, basic: Boolean = false, cookie: String? = null): Pair<String, String?> {
        val u = URL(c.url.trimEnd('/') + path)
        require(u.protocol == "https" || u.protocol == "http") { "Use a valid JFA-Go HTTP or HTTPS URL." }
        val h = u.openConnection() as HttpURLConnection
        try {
            h.requestMethod = method; h.connectTimeout = 10000; h.readTimeout = 15000
            h.setRequestProperty("Accept", "application/json")
            if (basic) h.setRequestProperty("Authorization", "Basic " + Base64.encodeToString((c.username + ":" + c.password).toByteArray(), Base64.NO_WRAP))
            bearer?.let { h.setRequestProperty("Authorization", "Bearer " + it) }
            cookie?.let { h.setRequestProperty("Cookie", "refresh=" + it) }
            if (body != null) { h.doOutput = true; h.setRequestProperty("Content-Type", "application/json"); h.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = h.responseCode
            val raw = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
                val html = raw.trimStart().startsWith("<")
                throw IllegalStateException("JFA-Go HTTP " + code + if (detail.isNotBlank()) ": " + detail else if (html) ". The endpoint returned HTML, not API JSON; check the base URL/path." else ".")
            }
            val refresh = h.getHeaderField("Set-Cookie")?.split(";")?.firstOrNull { it.startsWith("refresh=") }?.substringAfter("refresh=")
            return raw to refresh
        } finally { h.disconnect() }
    }
    private fun login(c: JfaConn): JfaAuth {
        val (raw, cookie) = request(c, "/token/login", basic = true)
        val token = JSONObject(raw).optString("token")
        require(token.isNotBlank()) { "JFA-Go did not return an administrator token." }
        return JfaAuth(token, cookie.orEmpty())
    }
    private fun call(c: JfaConn, a: JfaAuth, path: String, body: JSONObject): Pair<String, JfaAuth> {
        val first = runCatching { request(c, path, "POST", body, a.token) }
        if (first.isSuccess) return first.getOrThrow().first to a
        if (a.refresh.isBlank()) throw first.exceptionOrNull()!!
        val (refreshedRaw, nextCookie) = request(c, "/token/refresh", cookie = a.refresh)
        val next = JfaAuth(JSONObject(refreshedRaw).optString("token"), nextCookie ?: a.refresh)
        require(next.token.isNotBlank()) { "JFA-Go token refresh failed." }
        return request(c, path, "POST", body, next.token).first to next
    }
    private fun page(page: Int) = JSONObject().put("searchTerms", JSONArray()).put("queries", JSONArray())
        .put("limit", 100).put("page", page).put("sortByField", "username").put("ascending", true)
    fun users(c: JfaConn): List<JSONObject> {
        var auth = login(c); val out = mutableListOf<JSONObject>(); var page = 0
        while (page < 20) {
            val (raw, next) = call(c, auth, "/users", page(page)); auth = next
            val obj = JSONObject(raw); val arr = obj.optJSONArray("users")
                ?: throw IllegalStateException("JFA-Go /users response has no 'users' array.")
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(out::add)
            page++
            if (obj.optBoolean("last_page", true) || arr.length() == 0) break
        }
        return out
    }
    fun test(c: JfaConn) { require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter the JFA-Go administrator username and password." }; users(c) }
    fun activity(c: JfaConn): List<JSONObject> {
        val a = login(c)
        val body = JSONObject().put("searchTerms", JSONArray()).put("queries", JSONArray())
            .put("limit", 50).put("page", 0).put("sortByField", "time").put("ascending", false)
        val raw = call(c, a, "/activity", body).first
        val arr = JSONObject(raw).optJSONArray("activities") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
}

@Composable
fun JfaGoUsersScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var conn by remember { mutableStateOf(JfaStore.load(ctx)) }
    var edit by remember { mutableStateOf(conn == null) }
    var url by remember { mutableStateOf(conn?.url ?: "https://flixregister.alleriafilmotheque.fyi") }
    var username by remember { mutableStateOf(conn?.username ?: "") }
    var password by remember { mutableStateOf(conn?.password ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var activity by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tab by remember { mutableStateOf("Users") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(conn, refresh) {
        val c = conn ?: return@LaunchedEffect
        busy = true; error = null
        try {
            val result = withContext(Dispatchers.IO) { JfaApi.users(c) to runCatching { JfaApi.activity(c) }.getOrDefault(emptyList()) }
            users = result.first; activity = result.second
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load JFA-Go users." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState())
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back", onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text("User management", color = Txt, fontSize = AppType.section, fontWeight = FontWeight.Bold)
                Text("JFA-Go registration accounts", color = Mut, fontSize = AppType.small)
            }
            TextButton(onClick = { edit = !edit }) { Text("Connection", color = Red) }
        }
        if (edit) Panel {
            Label("JFA-Go administrator")
            Text("Your JFA-Go server is the account source. Credentials are encrypted using Android Keystore.", color = Mut, fontSize = AppType.small)
            OutlinedTextField(url, { url = it; error = null }, label = { Text("JFA-Go URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it; error = null }, label = { Text("Admin username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Admin password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val candidate = JfaConn(url.trim().trimEnd('/'), username.trim(), password)
                busy = true; error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { JfaApi.test(candidate) }
                        JfaStore.save(ctx, candidate); conn = candidate; edit = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "JFA-Go connection failed." }
                    finally { busy = false }
                }
            }, enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Connecting…" else "Test & save")
            }
            if (conn != null) TextButton(onClick = { JfaStore.clear(ctx); conn = null; users = emptyList(); activity = emptyList(); edit = true }) {
                Text("Disconnect", color = Mut)
            }
        }
        error?.let { StateMessage(it, true) }
        if (conn == null) Panel { Text("Connect your JFA-Go administrator account to view registered users.", color = Mut) }
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(conn!!.url.removePrefix("https://").removePrefix("http://"), color = Txt, fontWeight = FontWeight.SemiBold)
                    Text(users.size.toString() + " registered accounts", color = Mut, fontSize = AppType.small)
                }
                TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh", color = Red) }
            }
            Row(Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(14.dp)).padding(4.dp)) {
                listOf("Users", "Activity").forEach { item ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (tab == item) Surf2 else Color.Transparent)
                        .clickable { tab = item }.padding(10.dp), contentAlignment = Alignment.Center) {
                        Text(item, color = if (tab == item) Txt else Mut, fontSize = AppType.small, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (busy && users.isEmpty()) Panel { Text("Loading JFA-Go data…", color = Mut) }
            else if (tab == "Users") {
                if (users.isEmpty()) Panel { Text("No accounts returned by JFA-Go.", color = Mut) }
                users.forEach { u ->
                    val disabled = u.optBoolean("disabled")
                    val expiry = u.optLong("expiry")
                    val exp = if (expiry <= 0) "No expiry" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expiry))
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Surf2), contentAlignment = Alignment.Center) {
                                Text(u.optString("name").take(1).uppercase().ifBlank { "?" }, color = Red, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.optString("name", "Unknown user"), color = Txt, fontWeight = FontWeight.SemiBold)
                                Text(u.optString("label").ifBlank { u.optString("email") }, color = Mut, fontSize = AppType.small)
                                Text("Expiry · " + exp, color = Dim, fontSize = AppType.caption)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (disabled) "Disabled" else "Active", color = if (disabled) Amber else Green, fontSize = AppType.small)
                                if (u.optBoolean("admin")) Text("Jellyfin admin", color = Mut, fontSize = AppType.caption)
                                if (u.optBoolean("accounts_admin")) Text("JFA-Go admin", color = Red, fontSize = AppType.caption)
                            }
                        }
                    }
                }
            } else {
                if (activity.isEmpty()) Panel { Text("No activity entries returned.", color = Mut) }
                activity.forEach { a ->
                    val t = a.optLong("time")
                    val date = if (t > 0) java.text.SimpleDateFormat("d MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) else "—"
                    Panel {
                        Text(a.optString("type").replace('-', ' ').replace('_', ' ').ifBlank { "Activity" }, color = Txt, fontWeight = FontWeight.SemiBold)
                        Text(a.optString("username").ifBlank { a.optString("source_username") }, color = Mut, fontSize = AppType.small)
                        Text(date, color = Dim, fontSize = AppType.caption)
                    }
                }
            }
        }
        Text("JFA-Go manages account registration and expiry. Jellyfin playback sessions and connected-device monitoring are separate.", color = Dim, fontSize = AppType.caption)
    }
}
