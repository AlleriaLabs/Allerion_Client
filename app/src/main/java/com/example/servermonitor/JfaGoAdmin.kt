package com.example.servermonitor

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Accept either the JFA-Go server root or its visible /accounts page URL. */
fun normalizeJfaGoBaseUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    require(value.isNotBlank()) { "Enter your JFA-Go server URL." }
    val parsed = URI(value)
    require((parsed.scheme == "https" || parsed.scheme == "http") && !parsed.host.isNullOrBlank()) {
        "Enter a full JFA-Go URL beginning with https:// or http://."
    }
    var path = parsed.path.orEmpty().trimEnd('/')
    if (path.equals("/accounts", ignoreCase = true) || path.endsWith("/accounts", ignoreCase = true)) {
        path = path.dropLast("/accounts".length).trimEnd('/')
    }
    return URI(parsed.scheme, parsed.userInfo, parsed.host, parsed.port, if (path.isBlank()) null else path, null, null).toASCIIString().trimEnd('/')
}
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
        val j = JSONObject(open(raw)); JfaConn(normalizeJfaGoBaseUrl(j.getString("url")), j.getString("username"), j.getString("password"))
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
        val u = URL(normalizeJfaGoBaseUrl(c.url) + path)
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
    private fun responseArray(raw: String, key: String): JSONArray {
        val text = raw.trim()
        if (text.startsWith("[")) return JSONArray(text)
        if (!text.startsWith("{")) {
            val kind = if (text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true)) "HTML page" else "plain-text response"
            throw IllegalStateException("JFA-Go $key endpoint returned a $kind instead of JSON. Check the server URL and API route.")
        }
        val root = JSONObject(text)
        val arr = root.optJSONArray(key) ?: root.optJSONArray("activity") ?: root.optJSONArray("items")
        if (arr != null) return arr
        throw IllegalStateException("JFA-Go returned JSON but the expected '$key' list was missing.")
    }
    fun users(c: JfaConn): List<JSONObject> {
        val auth = login(c)
        // Match Allerium: JFA-Go's normal user directory is GET /users and returns a JSON array.
        val raw = request(c, "/users", bearer = auth.token).first
        val arr = responseArray(raw, "users")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
    fun test(c: JfaConn) { require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter the JFA-Go administrator username and password." }; users(c) }
    fun setEnabled(c: JfaConn, id: String, enabled: Boolean) = setEnabled(c, listOf(id), enabled)
    fun setEnabled(c: JfaConn, ids: List<String>, enabled: Boolean) { require(ids.isNotEmpty()); val a = login(c); request(c, "/users/enable", "POST", JSONObject().put("users", JSONArray(ids)).put("enabled", enabled).put("notify", false), a.token) }
    fun deleteUser(c: JfaConn, id: String) = deleteUsers(c, listOf(id))
    fun deleteUsers(c: JfaConn, ids: List<String>) { require(ids.isNotEmpty()); val a = login(c); request(c, "/users", "DELETE", JSONObject().put("users", JSONArray(ids)).put("notify", false), a.token) }
    fun resetPassword(c: JfaConn, id: String): JSONObject { val a = login(c); return JSONObject(request(c, "/users/password-reset", "POST", JSONObject().put("users", JSONArray().put(id)), a.token).first) }
    fun setExpiry(c: JfaConn, id: String, days: Int) { require(days > 0) { "Days must be greater than zero." }; val a = login(c); request(c, "/users/extend", "POST", JSONObject().put("users", JSONArray().put(id)).put("months", 0).put("days", days).put("hours", 0).put("minutes", 0).put("notify", false), a.token) }
    fun createUser(c: JfaConn, name: String, password: String, email: String) { require(name.isNotBlank() && password.isNotBlank()) { "Username and password are required." }; val a = login(c); request(c, "/user", "POST", JSONObject().put("username", name.trim()).put("password", password).put("email", email.trim()), a.token) }
    fun invites(c: JfaConn): List<JSONObject> { val a = login(c); val raw = request(c, "/invites", bearer = a.token).first; val arr = responseArray(raw, "invites"); return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
    fun createInvite(c: JfaConn, days: Int, uses: Int) { val a = login(c); val body = JSONObject().put("days", days).put("hours", 0).put("minutes", 0).put("multiple-uses", uses > 1).put("no-limit", false).put("remaining-uses", uses); request(c, "/invites", "POST", body, a.token) }
    fun deleteInvite(c: JfaConn, code: String) { val a = login(c); request(c, "/invites", "DELETE", JSONObject().put("code", code), a.token) }
    fun activity(c: JfaConn): List<JSONObject> {
        var auth = login(c)
        val all = mutableListOf<JSONObject>()
        // JFA-Go uses zero-indexed pages and reports last_page in its response.
        for (page in 0 until 100) {
            val body = JSONObject().put("searchTerms", JSONArray()).put("queries", JSONArray())
                .put("limit", 100).put("page", page).put("sortByField", "time").put("ascending", false)
            val result = call(c, auth, "/activity", body)
            auth = result.second
            val root = JSONObject(result.first)
            val arr = root.optJSONArray("activities")
                ?: throw IllegalStateException("JFA-Go activity response did not include an activities list.")
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all.add(it) }
            if (root.optBoolean("last_page", arr.length() < 100) || arr.length() == 0) break
        }
        return all
    }
}

private fun copyText(ctx: Context, value: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("JFA-Go", value))
}
private fun shareText(ctx: Context, value: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(Intent.createChooser(intent, "Share"))
}
@Composable
private fun JfaCompactSurface(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line.copy(alpha = 0.82f), shape).padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
@Composable
private fun JfaMetric(value: String, label: String, icon: Ic, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(11.dp)).background(Surf2.copy(alpha = 0.66f))
        .border(1.dp, Line.copy(alpha = 0.65f), RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon24(icon, Red, 14.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp)
        }
        Text(value, color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun RowScope.JfaAction(label: String, icon: Ic, tint: Color = Red, enabled: Boolean = true, onClick: () -> Unit) {
    val actionTint = if (enabled) tint else Dim
    Column(
        Modifier.weight(1f)
            .clip(RoundedCornerShape(15.dp))
            .background(Surf2.copy(alpha = 0.88f))
            .border(1.dp, Line.copy(alpha = 0.95f), RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon24(icon, actionTint, 21.dp)
        Text(
            label,
            color = if (enabled) Txt else Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
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
    var inviteLoadError by remember { mutableStateOf<String?>(null) }
    var activityLoadError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("Users") }
    var refresh by remember { mutableIntStateOf(0) }
    var invites by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var dialog by remember { mutableStateOf("") }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var userSearch by remember { mutableStateOf("") }
    var userFilter by remember { mutableStateOf("All") }
    var userSort by remember { mutableStateOf("Name") }
    var userPage by remember { mutableIntStateOf(0) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inviteSearch by remember { mutableStateOf("") }
    var inviteFilter by remember { mutableStateOf("All") }
    var activitySearch by remember { mutableStateOf("") }
    var activityType by remember { mutableStateOf("All") }
    var activityDays by remember { mutableStateOf("30") }
    var activityPage by remember { mutableIntStateOf(0) }
    var detailUser by remember { mutableStateOf<JSONObject?>(null) }
    var resetLink by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }; var newPassword by remember { mutableStateOf("") }; var newEmail by remember { mutableStateOf("") }
    var expiryDays by remember { mutableStateOf("30") }; var inviteDays by remember { mutableStateOf("7") }; var inviteUses by remember { mutableStateOf("1") }
    LaunchedEffect(conn, refresh) {
        val c = conn ?: return@LaunchedEffect
        busy = true; error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val loadedUsers = JfaApi.users(c)
                val loadedActivity = runCatching { JfaApi.activity(c) }
                val loadedInvites = runCatching { JfaApi.invites(c) }
                Triple(loadedUsers, loadedActivity, loadedInvites)
            }
            users = result.first
            activity = result.second.getOrDefault(emptyList())
            invites = result.third.getOrDefault(emptyList())
            activityLoadError = result.second.exceptionOrNull()?.message
            inviteLoadError = result.third.exceptionOrNull()?.message
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load JFA-Go users." }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 15.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back", plain = true, onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("ALLERION · ACCOUNT MANAGEMENT", color = Red, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp)
                Text("Accounts", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
                Text("Registration & access control", color = Mut, fontSize = AppType.caption)
            }
            IconBtn(Ic.Gear, "Connection", plain = true, onClick = { edit = !edit })
            IconBtn(Ic.Refresh, "Refresh", plain = true, onClick = { refresh++ })
        }
        if (edit) Panel {
            Label("JFA-Go administrator")
            Text("Your JFA-Go server is the account source. Credentials are encrypted using Android Keystore.", color = Mut, fontSize = AppType.small)
            OutlinedTextField(url, { url = it; error = null }, label = { Text("JFA-Go server URL") }, placeholder = { Text("https://your-jfa-go-domain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Enter the server address only. If you paste the /accounts page URL, Allerion will remove that page path automatically.", color = Dim, fontSize = AppType.caption)
            OutlinedTextField(username, { username = it; error = null }, label = { Text("Admin username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Admin password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val candidate = JfaConn(normalizeJfaGoBaseUrl(url), username.trim(), password)
                busy = true; error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { JfaApi.test(candidate) }
                        JfaStore.save(ctx, candidate); conn = candidate; url = candidate.url; edit = false
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
            JfaCompactSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("CONNECTED SERVER", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp)
                        Text(conn!!.url.removePrefix("https://").removePrefix("http://"), color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("Account directory · Registration control", color = Mut, fontSize = AppType.caption)
                    }
                    Row(Modifier.clip(RoundedCornerShape(18.dp)).background((if (busy) Amber else Green).copy(alpha = 0.10f))
                        .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (busy) CircularProgressIndicator(Modifier.size(12.dp), color = Amber, strokeWidth = 1.5.dp)
                        else Icon24(Ic.Check, Green, 13.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(if (busy) "SYNCING" else "ONLINE", color = if (busy) Amber else Green, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    JfaMetric(users.size.toString(), "USERS", Ic.Server, Modifier.weight(1f))
                    JfaMetric(invites.size.toString(), "INVITES", Ic.Plus, Modifier.weight(1f))
                    JfaMetric(activity.size.toString(), "EVENTS", Ic.Activity, Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(13.dp))
                .border(1.dp, Line, RoundedCornerShape(13.dp)).padding(4.dp)) {
                listOf("Users", "Invites", "Activity").forEach { item ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (tab == item) Surf2 else Color.Transparent)
                        .clickable { tab = item }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(item, color = if (tab == item) Txt else Mut, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            actionMessage?.let { StateMessage(it, false) }
            if (busy && users.isEmpty()) Panel { Text("Loading JFA-Go data…", color = Mut) }
            else if (tab == "Users") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Registered users", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text(users.size.toString() + " accounts connected", color = Mut, fontSize = AppType.caption)
                    }
                    Button(onClick = { newName = ""; newPassword = ""; newEmail = ""; dialog = "create" }, enabled = !actionBusy) {
                        Icon24(Ic.Plus, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Add user")
                    }
                }
                OutlinedTextField(userSearch, { userSearch = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text("Search users") }, leadingIcon = { Icon24(Ic.Globe, Mut, 18.dp) })
                val now = System.currentTimeMillis()
                val filterOptions = listOf("All", "Active", "Disabled", "Expiring", "No expiry")
                val filteredUsersUnsorted = users.filter { u ->
                    val expiry = u.optLong("expiry")
                    val statusMatch = when (userFilter) {
                        "Active" -> !u.optBoolean("disabled")
                        "Disabled" -> u.optBoolean("disabled")
                        "Expiring" -> !u.optBoolean("disabled") && expiry > now && expiry <= now + 30L * 24 * 60 * 60 * 1000
                        "No expiry" -> expiry <= 0
                        else -> true
                    }
                    val queryMatch = userSearch.isBlank() || u.optString("name").contains(userSearch, true) ||
                        u.optString("email").contains(userSearch, true) || u.optString("label").contains(userSearch, true)
                    statusMatch && queryMatch
                }
                val filteredUsers = when (userSort) {
                    "Expiry" -> filteredUsersUnsorted.sortedBy { it.optLong("expiry").let { e -> if (e <= 0) Long.MAX_VALUE else e } }
                    "Newest" -> filteredUsersUnsorted.sortedByDescending { it.optLong("created_at", it.optLong("created")) }
                    "Status" -> filteredUsersUnsorted.sortedBy { it.optBoolean("disabled") }
                    else -> filteredUsersUnsorted.sortedBy { it.optString("name").lowercase() }
                }
                val pageSize = 20
                val pageCount = maxOf(1, (filteredUsers.size + pageSize - 1) / pageSize)
                val safeUserPage = userPage.coerceIn(0, pageCount - 1)
                val pageUsers = filteredUsers.drop(safeUserPage * pageSize).take(pageSize)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    filterOptions.forEach { option ->
                        val selectedFilter = userFilter == option
                        val count = when (option) {
                            "Active" -> users.count { !it.optBoolean("disabled") }
                            "Disabled" -> users.count { it.optBoolean("disabled") }
                            "Expiring" -> users.count { val e = it.optLong("expiry"); !it.optBoolean("disabled") && e > now && e <= now + 30L * 24 * 60 * 60 * 1000 }
                            "No expiry" -> users.count { it.optLong("expiry") <= 0 }
                            else -> users.size
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (selectedFilter) Red.copy(alpha = 0.14f) else Surf2.copy(alpha = 0.72f))
                                .border(1.dp, if (selectedFilter) Red.copy(alpha = 0.55f) else Line, RoundedCornerShape(18.dp))
                                .clickable { userFilter = option }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(option, color = if (selectedFilter) Red else Mut, fontSize = AppType.caption, fontWeight = if (selectedFilter) FontWeight.Bold else FontWeight.Medium)
                                Text(count.toString(), color = if (selectedFilter) Red else Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort", color = Dim, fontSize = AppType.caption)
                    listOf("Name", "Expiry", "Newest", "Status").forEach { sort ->
                        FilterChip(selected = userSort == sort, onClick = { userSort = sort; userPage = 0 }, label = { Text(sort) })
                    }
                }
                if (selectedIds.isNotEmpty()) {
                    JfaCompactSurface {
                        Text("${selectedIds.size} selected", color = Txt, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !actionBusy, onClick = { dialog = "bulk_disable" }) { Text("Disable") }
                            OutlinedButton(enabled = !actionBusy, onClick = { dialog = "bulk_enable" }) { Text("Enable") }
                            OutlinedButton(enabled = !actionBusy, onClick = { dialog = "bulk_delete" }) { Text("Delete", color = Color(0xFFFF6878)) }
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                        }
                    }
                }
                if (filteredUsers.isEmpty()) Panel { Text(if (users.isEmpty()) "No accounts returned by JFA-Go." else "No users match this search and filter.", color = Mut) }
                pageUsers.forEach { u ->
                    val disabled = u.optBoolean("disabled")
                    val expiry = u.optLong("expiry")
                    val exp = if (expiry <= 0) "No expiry" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expiry))
                    JfaCompactSurface {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = selectedIds.contains(u.optString("id")), onCheckedChange = { checked ->
                                val id = u.optString("id")
                                selectedIds = if (checked) selectedIds + id else selectedIds - id
                            })
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Surf2).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                                Text(u.optString("name").take(1).uppercase().ifBlank { "?" }, color = Red, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.optString("name", "Unknown user"), modifier = Modifier.clickable { detailUser = u }, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val detail = u.optString("label").ifBlank { u.optString("email") }
                                if (detail.isNotBlank()) Text(detail, color = Mut, fontSize = AppType.caption, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon24(Ic.Clock, Dim, 13.dp); Spacer(Modifier.width(4.dp)); Text(if (expiry <= 0) "No expiry" else "Expires " + exp, color = Dim, fontSize = AppType.caption) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Row(Modifier.clip(RoundedCornerShape(18.dp)).background((if (disabled) Amber else Green).copy(alpha = 0.11f)).padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon24(if (disabled) Ic.Ban else Ic.Check, if (disabled) Amber else Green, 13.dp)
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (disabled) "DISABLED" else "ACTIVE", color = if (disabled) Amber else Green, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                                }
                                if (u.optBoolean("admin")) Text("Jellyfin admin", color = Mut, fontSize = AppType.caption)
                                if (u.optBoolean("accounts_admin")) Text("JFA-Go admin", color = Red, fontSize = AppType.caption)
                            }
                        }
                        TextButton(onClick = { detailUser = u }) { Text("View account details", color = Mut) }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.7f)))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            JfaAction("Expiry", Ic.Clock) { selected = u; expiryDays = "30"; dialog = "expiry" }
                            JfaAction("Reset", Ic.Refresh) { selected = u; dialog = "reset" }
                            JfaAction(if (disabled) "Enable" else "Disable", if (disabled) Ic.Check else Ic.Ban, if (disabled) Green else Amber, !actionBusy) {
                                val id = u.optString("id"); actionBusy = true
                                scope.launch { try { withContext(Dispatchers.IO) { JfaApi.setEnabled(conn!!, id, u.optBoolean("disabled")) }; refresh++; actionMessage = "Account status updated." }
                                    catch (e: Exception) { actionMessage = e.message ?: "Account update failed." } finally { actionBusy = false } }
                            }
                            JfaAction("Delete", Ic.Trash, Color(0xFFFF6878), !actionBusy) { selected = u; dialog = "delete" }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Page ${safeUserPage + 1} of $pageCount", color = Mut, fontSize = AppType.caption)
                    Row {
                        TextButton(enabled = userPage > 0, onClick = { userPage = (userPage - 1).coerceAtLeast(0) }) { Text("Previous") }
                        TextButton(enabled = userPage < pageCount - 1, onClick = { userPage = (userPage + 1).coerceAtMost(pageCount - 1) }) { Text("Next") }
                    }
                }
            } else if (tab == "Invites") {
                Button(onClick = { inviteDays = "7"; inviteUses = "1"; dialog = "invite" }, modifier = Modifier.fillMaxWidth(), enabled = !actionBusy) { Text("Create invitation") }
                inviteLoadError?.let { StateMessage("Invitations could not be loaded: $it", true) }
                OutlinedTextField(inviteSearch, { inviteSearch = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search invitation code") })
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("All", "Active", "Expired", "Used").forEach { filter ->
                        FilterChip(selected = inviteFilter == filter, onClick = { inviteFilter = filter }, label = { Text(filter) })
                    }
                }
                val nowSeconds = System.currentTimeMillis() / 1000L
                val visibleInvites = invites.filter { inv ->
                    val code = inv.optString("code")
                    val remaining = inv.optInt("remaining-uses", -1)
                    val expiry = inv.optLong("expiry", inv.optLong("expires"))
                    val match = when (inviteFilter) {
                        "Used" -> remaining == 0
                        "Expired" -> expiry > 0 && expiry < nowSeconds
                        "Active" -> remaining != 0 && (expiry <= 0 || expiry >= nowSeconds)
                        else -> true
                    }
                    match && (inviteSearch.isBlank() || code.contains(inviteSearch, true) || inv.optString("label").contains(inviteSearch, true))
                }
                if (visibleInvites.isEmpty()) Panel { Text(if (invites.isEmpty()) "No invitations returned." else "No invitations match this filter.", color = Mut) }
                visibleInvites.forEach { inv ->
                    JfaCompactSurface {
                        Text(inv.optString("code", "Invitation"), color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text(inv.optString("label"), color = Mut, fontSize = AppType.small)
                        Text("Uses remaining: " + inv.optString("remaining-uses", "—"), color = Dim, fontSize = AppType.caption)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { copyText(ctx, inv.optString("code")) ; actionMessage = "Invitation code copied." }) { Text("Copy code") }
                            TextButton(onClick = { shareText(ctx, "JFA-Go invitation: " + inv.optString("code")) }) { Text("Share") }
                        }
                        TextButton(enabled = !actionBusy, onClick = { selected = inv; dialog = "delete_invite" }) { Text("Delete invitation", color = Color(0xFFFF6878)) }
                    }
                }
            } else {
                activityLoadError?.let { StateMessage("Activity could not be loaded: $it", true) }
                OutlinedTextField(activitySearch, { activitySearch = it; activityPage = 0 }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search activity") })
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("All", "User", "Invite", "Login", "Password").forEach { type ->
                        FilterChip(selected = activityType == type, onClick = { activityType = type; activityPage = 0 }, label = { Text(type) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Within days", color = Mut, fontSize = AppType.caption)
                    Spacer(Modifier.width(8.dp))
                    listOf("7", "30", "90", "365").forEach { days ->
                        FilterChip(selected = activityDays == days, onClick = { activityDays = days; activityPage = 0 }, label = { Text(days) })
                    }
                }
                val cutoff = System.currentTimeMillis() / 1000L - (activityDays.toLongOrNull() ?: 30L) * 86400L
                val filteredActivity = activity.filter { a ->
                    val type = a.optString("type")
                    val user = a.optString("username").ifBlank { a.optString("source_username") }
                    val time = a.optLong("time")
                    (activityType == "All" || type.contains(activityType, true)) &&
                        (time <= 0 || time >= cutoff) &&
                        (activitySearch.isBlank() || user.contains(activitySearch, true) || type.contains(activitySearch, true) || a.toString().contains(activitySearch, true))
                }
                val activityPageCount = maxOf(1, (filteredActivity.size + 19) / 20)
                val activityItems = filteredActivity.drop(activityPage * 20).take(20)
                if (activityItems.isEmpty()) Panel { Text("No activity entries match these filters.", color = Mut) }
                activityItems.forEach { a ->
                    val t = a.optLong("time")
                    val date = if (t > 0) java.text.SimpleDateFormat("d MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) else "—"
                    Panel {
                        Text(a.optString("type").replace('-', ' ').replace('_', ' ').ifBlank { "Activity" }, color = Txt, fontWeight = FontWeight.SemiBold)
                        Text(a.optString("username").ifBlank { a.optString("source_username") }, color = Mut, fontSize = AppType.small)
                        Text(date, color = Dim, fontSize = AppType.caption)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Page ${activityPage.coerceAtMost(activityPageCount - 1) + 1} of $activityPageCount", color = Mut, fontSize = AppType.caption)
                    Row {
                        TextButton(enabled = activityPage > 0, onClick = { activityPage-- }) { Text("Previous") }
                        TextButton(enabled = activityPage < activityPageCount - 1, onClick = { activityPage++ }) { Text("Next") }
                    }
                }
            }
        }
        Text("JFA-Go manages account registration and expiry. Jellyfin playback sessions and connected-device monitoring are separate.", color = Dim, fontSize = AppType.caption)
    }

    if (dialog == "create") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Create JFA-Go user", color = Txt) },
        text = { Column { OutlinedTextField(newName, { newName = it }, label = { Text("Username") }); OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation()); OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Email (optional)") }) } },
        confirmButton = { TextButton(enabled = !actionBusy && newName.isNotBlank() && newPassword.isNotBlank(), onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.createUser(conn!!, newName, newPassword, newEmail) }; dialog = ""; refresh++; actionMessage = "User created successfully." } catch (e: Exception) { actionMessage = e.message ?: "User creation failed." } finally { actionBusy = false } } }) { Text("Create", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "expiry") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Extend account expiry", color = Txt) },
        text = { OutlinedTextField(expiryDays, { expiryDays = it.filter(Char::isDigit) }, label = { Text("Additional days") }) },
        confirmButton = { TextButton(enabled = !actionBusy && (expiryDays.toIntOrNull() ?: 0) > 0, onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.setExpiry(conn!!, selected!!.getString("id"), expiryDays.toInt()) }; dialog = ""; refresh++; actionMessage = "Expiry extended." } catch (e: Exception) { actionMessage = e.message ?: "Expiry update failed." } finally { actionBusy = false } } }) { Text("Apply", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "bulk_enable" || dialog == "bulk_disable" || dialog == "bulk_delete") AlertDialog(
        onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text(if (dialog == "bulk_delete") "Delete selected accounts?" else "Change selected accounts?", color = Txt) },
        text = { Text(if (dialog == "bulk_delete") "Permanently delete ${selectedIds.size} selected accounts?" else "Apply this status change to ${selectedIds.size} selected accounts?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy && selectedIds.isNotEmpty(), onClick = {
            val mode = dialog; actionBusy = true
            scope.launch { try {
                withContext(Dispatchers.IO) {
                    if (mode == "bulk_delete") JfaApi.deleteUsers(conn!!, selectedIds.toList())
                    else JfaApi.setEnabled(conn!!, selectedIds.toList(), mode == "bulk_enable")
                }
                selectedIds = emptySet(); dialog = ""; refresh++; actionMessage = "Bulk action completed."
            } catch (e: Exception) { actionMessage = e.message ?: "Bulk action failed." }
            finally { actionBusy = false }
            }
        }) { Text(if (dialog == "bulk_delete") "Delete" else "Confirm", color = if (dialog == "bulk_delete") Color(0xFFFF6878) else Red) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "delete_invite") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Delete invitation?", color = Txt) },
        text = { Text("Delete invitation code ${selected?.optString("code").orEmpty()}?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch {
            try { withContext(Dispatchers.IO) { JfaApi.deleteInvite(conn!!, selected?.optString("code").orEmpty()) }; dialog = ""; refresh++; actionMessage = "Invitation deleted." }
            catch (e: Exception) { actionMessage = e.message ?: "Invitation deletion failed." } finally { actionBusy = false }
        } }) { Text("Delete", color = Color(0xFFFF6878)) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "reset") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Generate password reset", color = Txt) }, text = { Text("Generate a JFA-Go password reset link for " + selected?.optString("name").orEmpty() + "?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch { try { val result = withContext(Dispatchers.IO) { JfaApi.resetPassword(conn!!, selected!!.getString("id")) }; val link = result.optString("link").ifBlank { result.optString("url") }
                            resetLink = link
                            actionMessage = if (link.isBlank()) "JFA-Go accepted the reset request, but returned no link." else "Password reset link is ready."
                            dialog = "" } catch (e: Exception) { actionMessage = e.message ?: "Password reset failed." } finally { actionBusy = false } } }) { Text("Generate", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "delete") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Delete account?", color = Txt) }, text = { Text("Permanently delete " + selected?.optString("name").orEmpty() + " from JFA-Go?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.deleteUser(conn!!, selected!!.getString("id")) }; dialog = ""; refresh++; actionMessage = "Account deleted." } catch (e: Exception) { actionMessage = e.message ?: "Account deletion failed." } finally { actionBusy = false } } }) { Text("Delete", color = Color(0xFFFF6878)) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (detailUser != null) AlertDialog(onDismissRequest = { detailUser = null },
        title = { Text(detailUser?.optString("name", "Account") ?: "Account", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val u = detailUser!!
            listOf("Email" to u.optString("email"), "Label" to u.optString("label"), "ID" to u.optString("id"),
                "Expiry" to u.optLong("expiry").let { if (it <= 0) "No expiry" else java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) },
                "Status" to if (u.optBoolean("disabled")) "Disabled" else "Active",
                "Jellyfin admin" to u.optBoolean("admin").toString(), "JFA-Go admin" to u.optBoolean("accounts_admin").toString()).forEach { (k,v) ->
                if (v.isNotBlank()) Text("$k · $v", color = Mut)
            }
        } },
        confirmButton = { TextButton(onClick = { detailUser = null }) { Text("Close", color = Red) } })
    resetLink?.let { link ->
        AlertDialog(onDismissRequest = { resetLink = null }, title = { Text("Password reset link", color = Txt) },
            text = { Text(link, color = Mut) },
            confirmButton = { Row {
                TextButton(onClick = { copyText(ctx, link); actionMessage = "Reset link copied."; resetLink = null }) { Text("Copy") }
                TextButton(onClick = { shareText(ctx, link); resetLink = null }) { Text("Share") }
            } },
            dismissButton = { TextButton(onClick = { resetLink = null }) { Text("Close") } })
    }
    if (dialog == "invite") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Create invitation", color = Txt) },
        text = { Column { OutlinedTextField(inviteDays, { inviteDays = it.filter(Char::isDigit) }, label = { Text("Validity days") }); OutlinedTextField(inviteUses, { inviteUses = it.filter(Char::isDigit) }, label = { Text("Number of uses") }) } },
        confirmButton = { TextButton(enabled = !actionBusy && (inviteDays.toIntOrNull() ?: 0) > 0 && (inviteUses.toIntOrNull() ?: 0) > 0, onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.createInvite(conn!!, inviteDays.toInt(), inviteUses.toInt()) }; dialog = ""; refresh++; actionMessage = "Invitation created." } catch (e: Exception) { actionMessage = e.message ?: "Invitation creation failed." } finally { actionBusy = false } } }) { Text("Create", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })

}
