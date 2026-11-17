package com.example.servermonitor

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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

/** Accept either the Allerion server root or its visible /accounts page URL. */
fun normalizeJfaGoBaseUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    require(value.isNotBlank()) { "Enter your Allerion server URL." }
    val parsed = URI(value)
    require((parsed.scheme == "https" || parsed.scheme == "http") && !parsed.host.isNullOrBlank()) {
        "Enter a full Allerion URL beginning with https:// or http://."
    }
    var path = parsed.path.orEmpty().trimEnd('/')
    if (path.equals("/accounts", ignoreCase = true) || path.endsWith("/accounts", ignoreCase = true)) {
        path = path.dropLast("/accounts".length).trimEnd('/')
    }
    return URI(parsed.scheme, parsed.userInfo, parsed.host, parsed.port, if (path.isBlank()) null else path, null, null).toASCIIString().trimEnd('/')
}
private data class JfaConn(val url: String, val username: String, val password: String, val serverName: String = "My Server")
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
        val j = JSONObject(open(raw)); JfaConn(normalizeJfaGoBaseUrl(j.getString("url")), j.getString("username"), j.getString("password"), j.optString("server_name").ifBlank { "My Server" })
    }.getOrNull()
    fun save(ctx: Context, c: JfaConn) {
        val j = JSONObject().put("url", c.url).put("username", c.username).put("password", c.password).put("server_name", c.serverName)
        check(ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, seal(j.toString())).commit())
    }
    fun clear(ctx: Context) { ctx.getSharedPreferences(PREF, 0).edit().remove(KEY).apply() }
}

private object JfaApi {
    private fun request(c: JfaConn, path: String, method: String = "GET", body: JSONObject? = null,
                        bearer: String? = null, basic: Boolean = false, cookie: String? = null): Pair<String, String?> {
        val u = URL(normalizeJfaGoBaseUrl(c.url) + path)
        require(u.protocol == "https" || u.protocol == "http") { "Use a valid Allerion HTTP or HTTPS URL." }
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
                throw IllegalStateException("Allerion HTTP " + code + if (detail.isNotBlank()) ": " + detail else if (html) ". The endpoint returned HTML, not API JSON; check the base URL/path." else ".")
            }
            val refresh = h.getHeaderField("Set-Cookie")?.split(";")?.firstOrNull { it.startsWith("refresh=") }?.substringAfter("refresh=")
            return raw to refresh
        } finally { h.disconnect() }
    }
    private fun login(c: JfaConn): JfaAuth {
        val (raw, cookie) = request(c, "/token/login", basic = true)
        val token = JSONObject(raw).optString("token")
        require(token.isNotBlank()) { "Allerion did not return an administrator token." }
        return JfaAuth(token, cookie.orEmpty())
    }
    private fun call(c: JfaConn, a: JfaAuth, path: String, body: JSONObject): Pair<String, JfaAuth> {
        val first = runCatching { request(c, path, "POST", body, a.token) }
        if (first.isSuccess) return first.getOrThrow().first to a
        if (a.refresh.isBlank()) throw first.exceptionOrNull()!!
        val (refreshedRaw, nextCookie) = request(c, "/token/refresh", cookie = a.refresh)
        val next = JfaAuth(JSONObject(refreshedRaw).optString("token"), nextCookie ?: a.refresh)
        require(next.token.isNotBlank()) { "Allerion token refresh failed." }
        return request(c, path, "POST", body, next.token).first to next
    }
    private fun responseArray(raw: String, key: String): JSONArray {
        val text = raw.trim()
        if (text.startsWith("[")) return JSONArray(text)
        if (!text.startsWith("{")) {
            val kind = if (text.startsWith("<!DOCTYPE", true) || text.startsWith("<html", true)) "HTML page" else "plain-text response"
            throw IllegalStateException("Allerion $key endpoint returned a $kind instead of JSON. Check the server URL and API route.")
        }
        val root = JSONObject(text)
        val candidates = listOf(key, "items", "results", "data", "response", "list", "invite")
        fun findList(value: JSONObject, depth: Int = 0): JSONArray? {
            if (depth > 4) return null
            for (candidate in candidates) {
                val child = value.opt(candidate)
                when (child) {
                    is JSONArray -> return child
                    is JSONObject -> {
                        findList(child, depth + 1)?.let { return it }
                        // An empty object is a valid empty collection on servers that
                        // serialize maps instead of arrays (e.g. {"invites": {}}).
                        if (child.length() == 0) return JSONArray()
                    }
                    is String -> {
                        val encoded = child.trim()
                        if (encoded.startsWith("[")) return runCatching { JSONArray(encoded) }.getOrNull()
                        if (encoded.startsWith("{")) {
                            val nested = runCatching { JSONObject(encoded) }.getOrNull()
                            if (nested != null) findList(nested, depth + 1)?.let { return it }
                        }
                    }
                }
            }
            // Some compatible servers serialize collections as keyed maps:
            // {"INVITE_CODE": { ...invite fields... }}.
            val values = value.keys().asSequence().mapNotNull { name ->
                value.optJSONObject(name)?.let { item ->
                    if (item.has("code")) item else JSONObject(item.toString()).put("code", name)
                }
            }.toList()
            if (values.isNotEmpty()) return JSONArray().apply { values.forEach { put(it) } }
            return null
        }
        findList(root)?.let { return it }
        val keys = root.keys().asSequence().toList().sorted().joinToString(", ").ifBlank { "(none)" }
        throw IllegalStateException("Allerion returned JSON for '$key' but no compatible list was found (fields: $keys).")
    }
    fun users(c: JfaConn): List<JSONObject> {
        val auth = login(c)
        // Match Allerium: Allerion's normal user directory is GET /users and returns a JSON array.
        val raw = request(c, "/users", bearer = auth.token).first
        val arr = responseArray(raw, "users")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
    fun test(c: JfaConn) { require(c.username.isNotBlank() && c.password.isNotBlank()) { "Enter the Allerion administrator username and password." }; users(c) }
    fun setEnabled(c: JfaConn, id: String, enabled: Boolean) = setEnabled(c, listOf(id), enabled)
    fun setEnabled(c: JfaConn, ids: List<String>, enabled: Boolean, notify: Boolean = false, reason: String = "") { require(ids.isNotEmpty()); val a = login(c); request(c, "/users/enable", "POST", JSONObject().put("users", JSONArray(ids)).put("enabled", enabled).put("notify", notify).put("reason", reason), a.token) }
    fun deleteUser(c: JfaConn, id: String) = deleteUsers(c, listOf(id))
    fun deleteUsers(c: JfaConn, ids: List<String>, notify: Boolean = false, reason: String = "") { require(ids.isNotEmpty()); val a = login(c); request(c, "/users", "DELETE", JSONObject().put("users", JSONArray(ids)).put("notify", notify).put("reason", reason), a.token) }
    fun resetPassword(c: JfaConn, id: String): JSONObject { val a = login(c); return JSONObject(request(c, "/users/password-reset", "POST", JSONObject().put("users", JSONArray().put(id)), a.token).first) }
    fun setExpiry(c: JfaConn, id: String, days: Int) { require(days > 0) { "Days must be greater than zero." }; val a = login(c); request(c, "/users/extend", "POST", JSONObject().put("users", JSONArray().put(id)).put("months", 0).put("days", days).put("hours", 0).put("minutes", 0).put("notify", false), a.token) }
    fun setExpiry(c: JfaConn, id: String, months: Int, days: Int, hours: Int, minutes: Int, notify: Boolean, reason: String) {
        require(months >= 0 && days >= 0 && hours >= 0 && minutes >= 0 && (months + days + hours + minutes) > 0) { "Enter a positive expiry duration." }
        val a = login(c)
        request(c, "/users/extend", "POST", JSONObject().put("users", JSONArray().put(id))
            .put("months", months).put("days", days).put("hours", hours).put("minutes", minutes)
            .put("notify", notify).put("reason", reason).put("try_extend_from_previous_expiry", true), a.token)
    }
    fun removeExpiry(c: JfaConn, id: String) {
        val a = login(c)
        request(c, "/users/" + java.net.URLEncoder.encode(id, "UTF-8") + "/expiry", "DELETE", bearer = a.token)
    }
    fun updateEmail(c: JfaConn, id: String, email: String) {
        val a = login(c); request(c, "/users/emails", "POST", JSONObject().put(id, email), a.token)
    }
    fun updateLabel(c: JfaConn, id: String, label: String) {
        val a = login(c); request(c, "/users/labels", "POST", JSONObject().put(id, label), a.token)
    }
    fun setAccountsAdmin(c: JfaConn, id: String, enabled: Boolean) {
        val a = login(c); request(c, "/users/accounts-admin", "POST", JSONObject().put(id, enabled), a.token)
    }
    fun expiryAutomation(c: JfaConn): JSONObject {
        val a = login(c)
        val root = JSONObject(request(c, "/config", bearer = a.token).first)
        val sections = root.optJSONArray("sections") ?: JSONArray()
        var settings = JSONObject()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            if (section.optString("section") != "user_expiry") continue
            val entries = section.optJSONArray("settings") ?: JSONArray()
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val key = entry.optString("setting")
                if (key.isBlank()) continue
                val value = entry.opt("value")
                settings.put(key, if (value == null || value == JSONObject.NULL) "" else if (value is JSONArray) {
                    (0 until value.length()).map { value.optString(it) }.joinToString("|")
                } else value.toString())
            }
            break
        }
        return settings
    }
    fun saveExpiryAutomation(c: JfaConn, behaviour: String, deleteAfterDays: String, sendEmail: Boolean, reminders: String) {
        val section = JSONObject()
            .put("behaviour", behaviour)
            .put("delete_expired_after_days", deleteAfterDays)
            .put("send_email", sendEmail.toString())
            .put("send_reminder_n_days_before", reminders)
        val a = login(c)
        request(c, "/config", "POST", JSONObject().put("user_expiry", section), a.token)
    }
    fun profiles(c: JfaConn): List<String> {
        val a = login(c); val raw = request(c, "/profiles/names", bearer = a.token).first
        val arr = JSONObject(raw).optJSONArray("profiles") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { value -> value.isNotBlank() } }
    }
    fun applyProfile(c: JfaConn, id: String, profile: String) {
        require(profile.isNotBlank()) { "Choose a profile." }
        val a = login(c)
        val body = JSONObject().put("from", "profile").put("profile", profile)
            .put("apply_to", JSONArray().put(id)).put("configuration", true)
            .put("homescreen", false).put("ombi", false).put("jellyseerr", false)
        request(c, "/users/settings", "POST", body, a.token)
    }
    fun createUser(c: JfaConn, name: String, password: String, email: String, profile: String = "") { require(name.isNotBlank() && password.isNotBlank()) { "Username and password are required." }; val a = login(c); val body = JSONObject().put("username", name.trim()).put("password", password).put("email", email.trim()); if (profile.isNotBlank()) body.put("profile", profile); request(c, "/user", "POST", body, a.token) }
    fun invites(c: JfaConn): List<JSONObject> { val a = login(c); val raw = request(c, "/invites", bearer = a.token).first; val arr = responseArray(raw, "invites"); return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
    fun createInvite(c: JfaConn, days: Int, uses: Int) { createInvite(c, days, 0, 0, 0, uses, false, "", "", "", false, 0, 0, 0, 0) }
    fun createInvite(c: JfaConn, months: Int, days: Int, hours: Int, minutes: Int, uses: Int, unlimited: Boolean,
        label: String, userLabel: String, profile: String, userExpiry: Boolean, userMonths: Int, userDays: Int, userHours: Int, userMinutes: Int) {
        require(months >= 0 && days >= 0 && hours >= 0 && minutes >= 0 && (months + days + hours + minutes) > 0) { "Set a positive invitation validity period." }
        require(unlimited || uses > 0) { "Set at least one allowed use." }
        val a = login(c)
        val body = JSONObject().put("months", months).put("days", days).put("hours", hours).put("minutes", minutes)
            .put("multiple-uses", unlimited || uses > 1).put("no-limit", unlimited).put("remaining-uses", if (unlimited) 0 else uses)
            .put("label", label.trim()).put("user_label", userLabel.trim()).put("profile", profile)
            .put("user-expiry", userExpiry).put("user-months", userMonths).put("user-days", userDays)
            .put("user-hours", userHours).put("user-minutes", userMinutes)
        request(c, "/invites", "POST", body, a.token)
    }
    fun editInvite(c: JfaConn, code: String, label: String, userLabel: String, profile: String, notifyCreation: Boolean, notifyExpiry: Boolean) {
        val a = login(c)
        request(c, "/invites/edit", "PATCH", JSONObject().put("code", code).put("label", label).put("user_label", userLabel)
            .put("profile", profile).put("notify_creation", notifyCreation).put("notify_expiry", notifyExpiry), a.token)
    }
    fun sendInvite(c: JfaConn, code: String, address: String) {
        require(address.isNotBlank()) { "Enter an email address or supported contact." }
        val a = login(c)
        request(c, "/invites/send", "POST", JSONObject().put("invite", code).put("send-to", address.trim()), a.token)
    }
    fun deleteInvite(c: JfaConn, code: String) { val a = login(c); request(c, "/invites", "DELETE", JSONObject().put("code", code), a.token) }
    fun registrationConfig(c: JfaConn): JSONObject {
        val a = login(c)
        return JSONObject(request(c, "/config", bearer = a.token).first)
    }
    fun saveRegistrationConfig(c: JfaConn, values: Map<String, Map<String, String>>) {
        val a = login(c)
        val body = JSONObject()
        values.forEach { (section, settings) ->
            val sectionBody = JSONObject()
            settings.forEach { (key, value) -> sectionBody.put(key, value) }
            body.put(section, sectionBody)
        }
        request(c, "/config", "POST", body, a.token)
    }
    fun activity(c: JfaConn): List<JSONObject> {
        var auth = login(c)
        val all = mutableListOf<JSONObject>()
        // Allerion uses zero-indexed pages and reports last_page in its response.
        for (page in 0 until 100) {
            val body = JSONObject().put("searchTerms", JSONArray()).put("queries", JSONArray())
                .put("limit", 100).put("page", page).put("sortByField", "time").put("ascending", false)
            val result = call(c, auth, "/activity", body)
            auth = result.second
            val root = JSONObject(result.first)
            val arr = root.optJSONArray("activities")
                ?: throw IllegalStateException("Allerion activity response did not include an activities list.")
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all.add(it) }
            if (root.optBoolean("last_page", arr.length() < 100) || arr.length() == 0) break
        }
        return all
    }
}

private fun copyText(ctx: Context, value: String) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Allerion", value))
}
private fun shareText(ctx: Context, value: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(Intent.createChooser(intent, "Share"))
}
@Composable
private fun JfaCompactSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line.copy(alpha = 0.82f), shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
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
    Box(
        Modifier.size(width = 38.dp, height = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(actionTint.copy(alpha = if (enabled) 0.09f else 0.035f))
            .border(1.dp, actionTint.copy(alpha = if (enabled) 0.24f else 0.10f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon24(icon, actionTint, 16.dp, desc = label)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JfaGoUsersScreen(onBack: () -> Unit, onSettings: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var conn by remember { mutableStateOf(JfaStore.load(ctx)) }
    var edit by remember { mutableStateOf(conn == null) }
    var url by remember { mutableStateOf(conn?.url ?: "https://register.alleriafilmotheque.fyi") }
    var serverName by remember { mutableStateOf(conn?.serverName ?: "Home Server") }
    var username by remember { mutableStateOf(conn?.username ?: "") }
    var password by remember { mutableStateOf(conn?.password ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var profileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var expiryAutoSettings by remember { mutableStateOf<JSONObject?>(null) }
    var expiryBehaviour by remember { mutableStateOf("disable_user") }; var expiryAutoDeleteDays by remember { mutableStateOf("0") }
    var expirySendEmail by remember { mutableStateOf(true) }; var expiryReminderDays by remember { mutableStateOf("7") }
    var expiryBehaviourMenu by remember { mutableStateOf(false) }
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
    var sortMenuExpanded by remember { mutableStateOf(false) }
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
    var newName by remember { mutableStateOf("") }; var newPassword by remember { mutableStateOf("") }; var newEmail by remember { mutableStateOf("") }; var newProfile by remember { mutableStateOf("") }
    var editEmail by remember { mutableStateOf("") }; var editLabel by remember { mutableStateOf("") }
    var editingUser by remember { mutableStateOf<JSONObject?>(null) }
    var editAccountsAdmin by remember { mutableStateOf(false) }; var selectedProfile by remember { mutableStateOf("") }
    var profileMenu by remember { mutableStateOf(false) }
    var expiryMonths by remember { mutableStateOf("0") }; var expiryDays by remember { mutableStateOf("30") }
    var expiryHours by remember { mutableStateOf("0") }; var expiryMinutes by remember { mutableStateOf("0") }
    var expiryNotify by remember { mutableStateOf(false) }; var expiryReason by remember { mutableStateOf("") }
    var actionNotify by remember { mutableStateOf(false) }; var actionReason by remember { mutableStateOf("") }
    var inviteDays by remember { mutableStateOf("7") }; var inviteUses by remember { mutableStateOf("1") }
    var inviteMonths by remember { mutableStateOf("0") }; var inviteHours by remember { mutableStateOf("0") }; var inviteMinutes by remember { mutableStateOf("0") }
    var inviteUnlimited by remember { mutableStateOf(false) }; var inviteLabel by remember { mutableStateOf("") }; var inviteUserLabel by remember { mutableStateOf("") }
    var inviteProfile by remember { mutableStateOf("") }; var inviteUserExpiry by remember { mutableStateOf(false) }
    var inviteUserMonths by remember { mutableStateOf("0") }; var inviteUserDays by remember { mutableStateOf("30") }
    var inviteUserHours by remember { mutableStateOf("0") }; var inviteUserMinutes by remember { mutableStateOf("0") }
    var inviteNotifyCreation by remember { mutableStateOf(true) }; var inviteNotifyExpiry by remember { mutableStateOf(true) }
    var inviteSendAddress by remember { mutableStateOf("") }
    var registrationConfig by remember { mutableStateOf<JSONObject?>(null) }
    var registrationValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var registrationSaving by remember { mutableStateOf(false) }
    val registrationSettingKeys = mapOf(
        "ui" to setOf("help_message", "success_message"),
        "email" to setOf("collect", "required", "require_unique", "no_username"),
        "email_confirmation" to setOf("enabled", "subject"),
        "captcha" to setOf("enabled", "recaptcha", "recaptcha_site_key", "recaptcha_secret_key", "recaptcha_hostname"),
        "password_validation" to setOf("enabled", "min_length", "upper", "lower", "number", "special"),
        "invite_emails" to setOf("enabled", "subject"),
        "discord" to setOf("show_on_reg"),
        "telegram" to setOf("show_on_reg"),
        "matrix" to setOf("show_on_reg")
    )
    val registrationSections = registrationSettingKeys.keys.toList()

    LaunchedEffect(conn, refresh) {
        val c = conn ?: return@LaunchedEffect
        busy = true; error = null
        try {
            val result = withContext(Dispatchers.IO) {
                val loadedUsers = JfaApi.users(c)
                val loadedActivity = runCatching { JfaApi.activity(c) }
                val loadedInvites = runCatching { JfaApi.invites(c) }
                val loadedProfiles = runCatching { JfaApi.profiles(c) }
                val loadedExpiryAutomation = runCatching { JfaApi.expiryAutomation(c) }
                val loadedRegistrationConfig = runCatching { JfaApi.registrationConfig(c) }
                Pair(Triple(loadedUsers, loadedActivity, Triple(loadedInvites, loadedProfiles, loadedExpiryAutomation)), loadedRegistrationConfig)
            }
            users = result.first.first
            activity = result.first.second.getOrDefault(emptyList())
            invites = result.first.third.first.getOrDefault(emptyList())
            profileNames = result.first.third.second.getOrDefault(emptyList())
            expiryAutoSettings = result.first.third.third.getOrNull()
            registrationConfig = result.second.getOrNull()
            registrationValues = buildMap {
                val root = result.second.getOrNull()
                val sections = root?.optJSONArray("sections") ?: JSONArray()
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    val sectionName = section.optString("section")
                    if (sectionName !in registrationSections) continue
                    val settings = section.optJSONArray("settings") ?: continue
                    for (j in 0 until settings.length()) {
                        val item = settings.optJSONObject(j) ?: continue
                        val key = item.optString("setting")
                        if (key.isBlank() || key !in registrationSettingKeys[sectionName].orEmpty()) continue
                        val raw = item.opt("value")
                        put("$sectionName.$key", if (raw == null || raw == JSONObject.NULL) "" else raw.toString())
                    }
                }
            }
            activityLoadError = result.first.second.exceptionOrNull()?.message
            inviteLoadError = result.first.third.first.exceptionOrNull()?.message
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Unable to load Allerion users." }
        finally { busy = false }
    }
    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
      Column(
        Modifier.widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight()
            .align(Alignment.TopCenter).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
      ) {
        ModulePageHeader("USER MANAGEMENT", "User Management", "Accounts, invitations and access control", onBack, { edit = !edit }) {
            IconBtn(Ic.Refresh, "Refresh", plain = true, onClick = { refresh++ })
        }
        if (edit) Panel {
            Text("Connect a server", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold)
            Text("Choose a display name and connect your account directory. Login details are encrypted on this device.", color = Mut, fontSize = AppType.small)
            OutlinedTextField(serverName, { serverName = it; error = null }, label = { Text("Server name") }, placeholder = { Text("e.g. Home Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; error = null }, label = { Text("Server address") }, placeholder = { Text("https://server.example.com:port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Enter the full address, including port if required. The friendly name is what Allerion will show in the app.", color = Dim, fontSize = AppType.caption)
            OutlinedTextField(username, { username = it; error = null }, label = { Text("Admin username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it; error = null }, label = { Text("Admin password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val candidate = JfaConn(normalizeJfaGoBaseUrl(url), username.trim(), password, serverName.trim().ifBlank { "My Server" })
                busy = true; error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { JfaApi.test(candidate) }
                        JfaStore.save(ctx, candidate); conn = candidate; url = candidate.url; serverName = candidate.serverName; edit = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Allerion connection failed." }
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
        if (conn == null) Panel { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text("Your account workspace starts here", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold); Text("Connect a server to manage accounts, invitations and access in one place.", color = Mut) } }
        else {
            JfaCompactSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("SERVER WORKSPACE", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.0.sp)
                        Text(conn!!.serverName, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(conn!!.url.removePrefix("https://").removePrefix("http://"), color = Dim, fontSize = AppType.caption, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                OutlinedButton(onClick = {
                    val cfg = expiryAutoSettings ?: JSONObject()
                    expiryBehaviour = cfg.optString("behaviour", "disable_user")
                    expiryAutoDeleteDays = cfg.optString("delete_expired_after_days", "0")
                    expirySendEmail = cfg.optString("send_email", "true").toBoolean()
                    expiryReminderDays = cfg.optString("send_reminder_n_days_before", "7").replace("|", ", ")
                    dialog = "expiry_automation"
                }, modifier = Modifier.fillMaxWidth()) { Text("Expiry automation & reminders") }
            }
            Row(Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(13.dp))
                .border(1.dp, Line, RoundedCornerShape(13.dp)).padding(4.dp)) {
                listOf("Users", "Invites", "Registration", "Activity").forEach { item ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (tab == item) Surf2 else Color.Transparent)
                        .clickable { tab = item }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(item, color = if (tab == item) Txt else Mut, fontSize = AppType.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            actionMessage?.let { StateMessage(it, false) }
            if (busy && users.isEmpty()) Panel { Text("Loading Allerion data…", color = Mut) }
            else if (tab == "Users") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Accounts", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold)
                        Text("Manage people, permissions and account status", color = Mut, fontSize = AppType.caption)
                    }
                    Button(onClick = { newName = ""; newPassword = ""; newEmail = ""; newProfile = ""; dialog = "create" }, enabled = !actionBusy) {
                        Icon24(Ic.Plus, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Add user")
                    }
                }
                OutlinedTextField(userSearch, { userSearch = it; userPage = 0 }, modifier = Modifier.fillMaxWidth(),
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
                                .clickable { userFilter = option; userPage = 0 }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(option, color = if (selectedFilter) Red else Mut, fontSize = AppType.caption, fontWeight = if (selectedFilter) FontWeight.Bold else FontWeight.Medium)
                                Text(count.toString(), color = if (selectedFilter) Red else Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { sortMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Icon24(Ic.Chart, Red, 15.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("Sort: $userSort", color = Txt, fontSize = AppType.caption)
                            Spacer(Modifier.width(3.dp))
                            Icon24(Ic.ChevronD, Mut, 14.dp)
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            listOf("Name", "Expiry", "Newest", "Status").forEach { sort ->
                                DropdownMenuItem(text = { Text(sort) }, onClick = {
                                    userSort = sort; userPage = 0; sortMenuExpanded = false
                                })
                            }
                        }
                    }
                }
                if (selectedIds.isNotEmpty()) {
                    JfaCompactSurface {
                        Text("${selectedIds.size} selected", color = Txt, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(38.dp),
                                enabled = !actionBusy,
                                onClick = { actionNotify = false; actionReason = ""; dialog = "bulk_disable" },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber)
                            ) {
                                Icon24(Ic.Ban, Amber, 14.dp)
                                Spacer(Modifier.width(5.dp))
                                Text("Disable", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(38.dp),
                                enabled = !actionBusy,
                                onClick = { actionNotify = false; actionReason = ""; dialog = "bulk_enable" },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Green)
                            ) {
                                Icon24(Ic.Check, Green, 14.dp)
                                Spacer(Modifier.width(5.dp))
                                Text("Enable", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f).height(38.dp),
                                enabled = !actionBusy,
                                onClick = { actionNotify = false; actionReason = ""; dialog = "bulk_delete" },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6878))
                            ) {
                                Icon24(Ic.Trash, Color(0xFFFF6878), 14.dp)
                                Spacer(Modifier.width(5.dp))
                                Text("Delete", fontSize = 11.sp, maxLines = 1)
                            }
                            TextButton(
                                onClick = { selectedIds = emptySet() },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                            ) { Text("Clear", fontSize = 11.sp, maxLines = 1) }
                        }
                    }
                }
                if (filteredUsers.isEmpty()) Panel { Text(if (users.isEmpty()) "No accounts returned by Allerion." else "No users match this search and filter.", color = Mut) }
                pageUsers.forEach { u ->
                    val disabled = u.optBoolean("disabled")
                    val expiry = u.optLong("expiry")
                    val exp = if (expiry <= 0) "No expiry" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expiry))
                    val userId = u.optString("id")
                    val isSelected = selectedIds.contains(userId)
                    JfaCompactSurface(
                        modifier = Modifier.border(
                            if (isSelected) 1.5.dp else 0.dp,
                            if (isSelected) Red.copy(alpha = 0.85f) else Color.Transparent,
                            RoundedCornerShape(15.dp)
                        ).combinedClickable(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    selectedIds = if (isSelected) selectedIds - userId else selectedIds + userId
                                } else detailUser = u
                            },
                            onLongClick = {
                                selectedIds = if (isSelected) selectedIds - userId else selectedIds + userId
                            }
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(if (isSelected) Red.copy(alpha = 0.14f) else Surf2)
                                .border(1.dp, if (isSelected) Red.copy(alpha = 0.7f) else Line, CircleShape), contentAlignment = Alignment.Center) {
                                Text(u.optString("name").take(1).uppercase().ifBlank { "?" }, color = Red, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.optString("name", "Unknown user"), color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold,
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
                                if (u.optBoolean("accounts_admin")) Text("Allerion admin", color = Red, fontSize = AppType.caption)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.55f)))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            JfaAction("Extend expiry", Ic.Clock) { selected = u; expiryMonths = "0"; expiryDays = "30"; expiryHours = "0"; expiryMinutes = "0"; expiryNotify = false; expiryReason = ""; dialog = "expiry" }
                            Spacer(Modifier.width(6.dp))
                            JfaAction("Reset password", Ic.Refresh) { selected = u; dialog = "reset" }
                            Spacer(Modifier.width(6.dp))
                            JfaAction(if (disabled) "Enable account" else "Disable account", if (disabled) Ic.Check else Ic.Ban, if (disabled) Green else Amber, !actionBusy) {
                                selected = u; actionNotify = false; actionReason = ""; dialog = "status"
                            }
                            Spacer(Modifier.width(6.dp))
                            JfaAction("Delete account", Ic.Trash, Color(0xFFFF6878), !actionBusy) { selected = u; actionNotify = false; actionReason = ""; dialog = "delete" }
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
                val now = System.currentTimeMillis() / 1000L
                val activeCount = invites.count { (it.optBoolean("no_limit") || it.optInt("remaining_uses", 1) > 0) && (it.optLong("valid_till") <= 0 || it.optLong("valid_till") >= now) }
                val usedCount = invites.count { (it.optJSONObject("used_by")?.length() ?: 0) > 0 }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    JfaMetric(activeCount.toString(), "ACTIVE", Ic.Check, Modifier.weight(1f))
                    JfaMetric(usedCount.toString(), "USED", Ic.Activity, Modifier.weight(1f))
                    JfaMetric((invites.size - activeCount).coerceAtLeast(0).toString(), "INACTIVE", Ic.Clock, Modifier.weight(1f))
                }
                Button(onClick = { inviteMonths = "0"; inviteDays = "7"; inviteHours = "0"; inviteMinutes = "0"; inviteUses = "1"; inviteUnlimited = false; inviteLabel = ""; inviteUserLabel = ""; inviteProfile = ""; inviteUserExpiry = false; inviteUserMonths = "0"; inviteUserDays = "30"; inviteUserHours = "0"; inviteUserMinutes = "0"; dialog = "invite" }, modifier = Modifier.fillMaxWidth(), enabled = !actionBusy) { Text("Create invitation") }
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
                    val remaining = inv.optInt("remaining_uses", -1)
                    val expiry = inv.optLong("valid_till", inv.optLong("expiry", inv.optLong("expires")))
                    val match = when (inviteFilter) {
                        "Used" -> (inv.optJSONObject("used_by")?.length() ?: 0) > 0
                        "Expired" -> expiry > 0 && expiry < nowSeconds
                        "Active" -> (inv.optBoolean("no_limit") || remaining > 0) && (expiry <= 0 || expiry >= nowSeconds)
                        else -> true
                    }
                    match && (inviteSearch.isBlank() || code.contains(inviteSearch, true) || inv.optString("label").contains(inviteSearch, true))
                }
                if (visibleInvites.isEmpty()) Panel { Text(if (invites.isEmpty()) "No invitations returned." else "No invitations match this filter.", color = Mut) }
                visibleInvites.forEach { inv ->
                    JfaCompactSurface {
                        Text(inv.optString("label").ifBlank { "Invitation" }, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text("Code · " + inv.optString("code", "—"), color = Mut, fontSize = AppType.caption)
                        val noLimit = inv.optBoolean("no_limit")
                        val remaining = inv.optInt("remaining_uses", 1)
                        val used = inv.optJSONObject("used_by")?.length() ?: 0
                        val validTill = inv.optLong("valid_till")
                        Text((if (noLimit) "Unlimited uses" else "$remaining uses remaining") + " · $used registrations", color = Dim, fontSize = AppType.caption)
                        inv.optJSONObject("used_by")?.let { registrations ->
                            registrations.keys().asSequence().toList().sortedByDescending { registrations.optLong(it) }.take(5).forEach { registeredUser ->
                                val registeredAt = registrations.optLong(registeredUser)
                                val registeredDate = if (registeredAt > 0L) java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(registeredAt * 1000L)) else "Date unavailable"
                                Text("Registered by $registeredUser · $registeredDate", color = Mut, fontSize = AppType.caption)
                            }
                            if (registrations.length() > 5) Text("+ ${registrations.length() - 5} more registrations", color = Dim, fontSize = AppType.caption)
                        }
                        Text("Expires · " + if (validTill > 0) java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(validTill * 1000L)) else "—", color = Dim, fontSize = AppType.caption)
                        if (inv.optString("profile").isNotBlank()) Text("Profile · " + inv.optString("profile"), color = Mut, fontSize = AppType.caption)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { copyText(ctx, inv.optString("code")); actionMessage = "Invitation code copied." }) { Text("Copy") }
                            TextButton(onClick = { shareText(ctx, "Allerion invitation: " + inv.optString("code")) }) { Text("Share") }
                            TextButton(onClick = { selected = inv; inviteLabel = inv.optString("label"); inviteUserLabel = inv.optString("user_label"); inviteProfile = inv.optString("profile"); inviteNotifyCreation = inv.optBoolean("notify_creation", true); inviteNotifyExpiry = inv.optBoolean("notify_expiry", true); inviteSendAddress = ""; dialog = "edit_invite" }) { Text("Manage") }
                        }
                        TextButton(enabled = !actionBusy, onClick = { selected = inv; dialog = "delete_invite" }) { Text("Revoke invitation", color = Color(0xFFFF6878)) }
                    }
                }
            } else if (tab == "Registration") {
                Text("Registration controls", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold)
                Text("Configure signup fields, email verification, CAPTCHA and password rules. Signups remain invitation-based: the server requires a valid invitation code. Settings are read from and saved to the connected server. Changes marked as requiring restart need a server restart before taking effect.", color = Mut, fontSize = AppType.caption)
                if (registrationConfig == null) {
                    Panel { Text("Registration settings are unavailable from this server. Check its version and administrator permissions.", color = Amber) }
                } else {
                    val sectionNames = registrationSections
                    sectionNames.forEach { sectionName ->
                        val section = (registrationConfig?.optJSONArray("sections") ?: JSONArray()).let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("section") == sectionName }
                        }
                        if (section != null) {
                            val meta = section.optJSONObject("meta") ?: JSONObject()
                            val settings = section.optJSONArray("settings") ?: JSONArray()
                            JfaCompactSurface {
                                Text(meta.optString("name").ifBlank { sectionName.replace('_', ' ').replaceFirstChar { it.uppercase() } }, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                                if (meta.optString("description").isNotBlank()) Text(meta.optString("description"), color = Mut, fontSize = AppType.caption)
                                for (i in 0 until settings.length()) {
                                    val item = settings.optJSONObject(i) ?: continue
                                    val key = item.optString("setting")
                                    val type = item.optString("type")
                                    if (key.isBlank() || type == "note" || key !in registrationSettingKeys[sectionName].orEmpty()) continue
                                    val stateKey = "$sectionName.$key"
                                    val label = item.optString("name").ifBlank { key }
                                    val description = item.optString("description")
                                    val current = registrationValues[stateKey] ?: item.opt("value")?.toString().orEmpty()
                                    if (type == "bool") {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = current.equals("true", true), onCheckedChange = { checked ->
                                                registrationValues = registrationValues + (stateKey to checked.toString())
                                            })
                                            Column(Modifier.weight(1f)) {
                                                Text(label, color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Medium)
                                                if (description.isNotBlank()) Text(description, color = Mut, fontSize = AppType.caption)
                                            }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = current,
                                            onValueChange = { value -> registrationValues = registrationValues + (stateKey to value) },
                                            label = { Text(label) },
                                            supportingText = if (description.isNotBlank()) ({ Text(description) }) else null,
                                            singleLine = true,
                                            visualTransformation = if (key.contains("secret", true) || key.contains("password", true)) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (item.optBoolean("requires_restart")) Text("Server restart required", color = Amber, fontSize = AppType.micro)
                                }
                            }
                        }
                    }
                    Button(
                        enabled = !registrationSaving && registrationValues.isNotEmpty(),
                        onClick = {
                            registrationSaving = true
                            scope.launch {
                                try {
                                    val payload = registrationValues.entries
                                        .filter { entry -> registrationSections.any { entry.key.startsWith("$it.") } }
                                        .groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.') to it.value })
                                        .mapValues { (_, entries) -> entries.toMap() }
                                    withContext(Dispatchers.IO) { JfaApi.saveRegistrationConfig(conn!!, payload) }
                                    refresh++
                                    actionMessage = "Registration settings saved to the server."
                                } catch (e: Exception) {
                                    actionMessage = e.message ?: "Registration settings could not be saved."
                                } finally { registrationSaving = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (registrationSaving) "Saving…" else "Save registration settings") }
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
        Text("Allerion manages account registration and expiry. Jellyfin playback sessions and connected-device monitoring are separate.", color = Dim, fontSize = AppType.caption)
      }
    }

    if (dialog == "create") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Create Allerion user", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(newName, { newName = it }, label = { Text("Username") })
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Email (optional)") })
            Text("Jellyfin permission profile (optional)", color = Mut, fontSize = AppType.caption)
            Box {
                OutlinedButton(onClick = { profileMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (newProfile.isBlank()) "Use Allerion default profile" else newProfile) }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    DropdownMenuItem(text = { Text("Server default profile") }, onClick = { newProfile = ""; profileMenu = false })
                    profileNames.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { newProfile = p; profileMenu = false }) }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !actionBusy && newName.isNotBlank() && newPassword.isNotBlank(), onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.createUser(conn!!, newName, newPassword, newEmail, newProfile) }; dialog = ""; refresh++; actionMessage = "User created successfully." } catch (e: Exception) { actionMessage = e.message ?: "User creation failed." } finally { actionBusy = false } } }) { Text("Create", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "expiry") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Account expiry", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Extend from the current expiry, or create one if the account has none.", color = Mut, fontSize = AppType.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(expiryMonths, { expiryMonths = it.filter(Char::isDigit) }, label = { Text("Months") }, modifier = Modifier.weight(1f))
                OutlinedTextField(expiryDays, { expiryDays = it.filter(Char::isDigit) }, label = { Text("Days") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(expiryHours, { expiryHours = it.filter(Char::isDigit) }, label = { Text("Hours") }, modifier = Modifier.weight(1f))
                OutlinedTextField(expiryMinutes, { expiryMinutes = it.filter(Char::isDigit) }, label = { Text("Minutes") }, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(expiryNotify, { expiryNotify = it }); Text("Notify user", color = Mut) }
            OutlinedTextField(expiryReason, { expiryReason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !actionBusy && ((expiryMonths.toIntOrNull() ?: 0) + (expiryDays.toIntOrNull() ?: 0) + (expiryHours.toIntOrNull() ?: 0) + (expiryMinutes.toIntOrNull() ?: 0) > 0), onClick = {
            actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.setExpiry(conn!!, selected!!.getString("id"), expiryMonths.toIntOrNull() ?: 0, expiryDays.toIntOrNull() ?: 0, expiryHours.toIntOrNull() ?: 0, expiryMinutes.toIntOrNull() ?: 0, expiryNotify, expiryReason) }; dialog = ""; refresh++; actionMessage = "Expiry updated." } catch (e: Exception) { actionMessage = e.message ?: "Expiry update failed." } finally { actionBusy = false } }
        }) { Text("Apply", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "bulk_enable" || dialog == "bulk_disable" || dialog == "bulk_delete") AlertDialog(
        onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text(if (dialog == "bulk_delete") "Delete selected accounts?" else "Change selected accounts?", color = Txt) },
        text = { Column {
            Text(if (dialog == "bulk_delete") "Permanently delete ${selectedIds.size} selected accounts?" else "Apply this status change to ${selectedIds.size} selected accounts?", color = Mut)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(actionNotify, { actionNotify = it }); Text("Notify affected users", color = Mut) }
            OutlinedTextField(actionReason, { actionReason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !actionBusy && selectedIds.isNotEmpty(), onClick = {
            val mode = dialog; actionBusy = true
            scope.launch { try {
                withContext(Dispatchers.IO) {
                    if (mode == "bulk_delete") JfaApi.deleteUsers(conn!!, selectedIds.toList(), actionNotify, actionReason)
                    else JfaApi.setEnabled(conn!!, selectedIds.toList(), mode == "bulk_enable", actionNotify, actionReason)
                }
                selectedIds = emptySet(); dialog = ""; refresh++; actionMessage = "Bulk action completed."
            } catch (e: Exception) { actionMessage = e.message ?: "Bulk action failed." }
            finally { actionBusy = false }
            }
        }) { Text(if (dialog == "bulk_delete") "Delete" else "Confirm", color = if (dialog == "bulk_delete") Color(0xFFFF6878) else Red) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "expiry_automation") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Automatic account expiry", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("These are Allerion server-wide expiry rules. They apply to accounts with an expiry set.", color = Mut, fontSize = AppType.caption)
            Box {
                OutlinedButton(onClick = { expiryBehaviourMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expiryBehaviour == "delete_user") "Delete account on expiry" else "Disable account on expiry", color = Txt)
                }
                DropdownMenu(expanded = expiryBehaviourMenu, onDismissRequest = { expiryBehaviourMenu = false }) {
                    DropdownMenuItem(text = { Text("Disable account on expiry") }, onClick = { expiryBehaviour = "disable_user"; expiryBehaviourMenu = false })
                    DropdownMenuItem(text = { Text("Delete account on expiry") }, onClick = { expiryBehaviour = "delete_user"; expiryBehaviourMenu = false })
                }
            }
            if (expiryBehaviour == "disable_user") OutlinedTextField(expiryAutoDeleteDays, { expiryAutoDeleteDays = it.filter(Char::isDigit) },
                label = { Text("Delete disabled account after days (0 = never)") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(expirySendEmail, { expirySendEmail = it }); Text("Email users when their account expires", color = Mut) }
            OutlinedTextField(expiryReminderDays, { expiryReminderDays = it }, label = { Text("Reminder days before expiry") },
                supportingText = { Text("Separate multiple values with commas, e.g. 7, 3, 1") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = {
            actionBusy = true
            val reminders = expiryReminderDays.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("|")
            scope.launch { try {
                withContext(Dispatchers.IO) { JfaApi.saveExpiryAutomation(conn!!, expiryBehaviour, if (expiryBehaviour == "disable_user") expiryAutoDeleteDays.ifBlank { "0" } else "0", expirySendEmail, reminders) }
                expiryAutoSettings = withContext(Dispatchers.IO) { JfaApi.expiryAutomation(conn!!) }
                dialog = ""; actionMessage = "Allerion expiry automation updated."
            } catch (e: Exception) { actionMessage = e.message ?: "Could not update expiry automation." }
            finally { actionBusy = false } }
        }) { Text("Save rules", color = Red) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "edit") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Edit user details", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Update the contact email and account label.", color = Mut, fontSize = AppType.caption)
            OutlinedTextField(editEmail, { editEmail = it }, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(editLabel, { editLabel = it }, label = { Text("User label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        } },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = {
            actionBusy = true; val u = editingUser ?: return@TextButton
            scope.launch { try {
                withContext(Dispatchers.IO) {
                    JfaApi.updateEmail(conn!!, u.getString("id"), editEmail.trim())
                    JfaApi.updateLabel(conn!!, u.getString("id"), editLabel.trim())
                }
                dialog = ""; refresh++; actionMessage = "User details updated."
            } catch (e: Exception) { actionMessage = e.message ?: "User details could not be updated." }
            finally { actionBusy = false } }
        }) { Text("Save", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "access") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Account access & profile", color = Txt) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(editAccountsAdmin, { editAccountsAdmin = it }); Text("Server administrator access", color = Mut) }
            Text("Apply a saved profile to update Jellyfin policy, including administrator and library permissions.", color = Mut, fontSize = AppType.caption)
            Box {
                OutlinedButton(onClick = { profileMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedProfile.isBlank()) "Choose profile" else selectedProfile, color = Txt)
                }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    profileNames.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { selectedProfile = p; profileMenu = false }) }
                }
            }
            if (profileNames.isEmpty()) Text("No profiles were returned by Allerion.", color = Amber, fontSize = AppType.caption)
        } },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = {
            actionBusy = true; val u = editingUser ?: return@TextButton
            scope.launch { try {
                withContext(Dispatchers.IO) {
                    JfaApi.setAccountsAdmin(conn!!, u.getString("id"), editAccountsAdmin)
                    if (selectedProfile.isNotBlank()) JfaApi.applyProfile(conn!!, u.getString("id"), selectedProfile)
                }
                dialog = ""; refresh++; actionMessage = "Access settings updated."
            } catch (e: Exception) { actionMessage = e.message ?: "Access settings could not be updated." }
            finally { actionBusy = false } }
        }) { Text("Apply", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "status") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Change account status", color = Txt) },
        text = { Column {
            Text("Set " + selected?.optString("name").orEmpty() + " to " + if (selected?.optBoolean("disabled") == true) "active" else "disabled" + "?", color = Mut)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(actionNotify, { actionNotify = it }); Text("Notify user", color = Mut) }
            OutlinedTextField(actionReason, { actionReason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch { try {
            withContext(Dispatchers.IO) { JfaApi.setEnabled(conn!!, listOf(selected!!.getString("id")), selected!!.optBoolean("disabled"), actionNotify, actionReason) }
            dialog = ""; refresh++; actionMessage = "Account status updated."
        } catch (e: Exception) { actionMessage = e.message ?: "Account update failed." } finally { actionBusy = false } } }) { Text("Confirm", color = Red) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "invite") AlertDialog(
        onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Create invitation", color = Txt) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("INVITATION VALIDITY", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(inviteMonths, { inviteMonths = it.filter(Char::isDigit) }, label = { Text("Months") }, modifier = Modifier.weight(1f))
                OutlinedTextField(inviteDays, { inviteDays = it.filter(Char::isDigit) }, label = { Text("Days") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(inviteHours, { inviteHours = it.filter(Char::isDigit) }, label = { Text("Hours") }, modifier = Modifier.weight(1f))
                OutlinedTextField(inviteMinutes, { inviteMinutes = it.filter(Char::isDigit) }, label = { Text("Minutes") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(inviteLabel, { inviteLabel = it }, label = { Text("Internal invitation label") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(inviteUserLabel, { inviteUserLabel = it }, label = { Text("Label applied to new accounts") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(inviteUnlimited, { inviteUnlimited = it }); Text("Unlimited uses", color = Mut) }
            if (!inviteUnlimited) OutlinedTextField(inviteUses, { inviteUses = it.filter(Char::isDigit) }, label = { Text("Allowed uses") }, modifier = Modifier.fillMaxWidth())
            Text("ACCOUNT PROFILE", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { profileMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (inviteProfile.isBlank()) "Server default profile" else inviteProfile) }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    DropdownMenuItem(text = { Text("Server default profile") }, onClick = { inviteProfile = ""; profileMenu = false })
                    profileNames.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { inviteProfile = p; profileMenu = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(inviteUserExpiry, { inviteUserExpiry = it }); Text("Set expiry on created accounts", color = Mut) }
            if (inviteUserExpiry) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedTextField(inviteUserMonths, { inviteUserMonths = it.filter(Char::isDigit) }, label = { Text("Months") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(inviteUserDays, { inviteUserDays = it.filter(Char::isDigit) }, label = { Text("Days") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedTextField(inviteUserHours, { inviteUserHours = it.filter(Char::isDigit) }, label = { Text("Hours") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(inviteUserMinutes, { inviteUserMinutes = it.filter(Char::isDigit) }, label = { Text("Minutes") }, modifier = Modifier.weight(1f))
                }
            }
        } },
        confirmButton = { TextButton(enabled = !actionBusy && ((inviteMonths.toIntOrNull() ?: 0) + (inviteDays.toIntOrNull() ?: 0) + (inviteHours.toIntOrNull() ?: 0) + (inviteMinutes.toIntOrNull() ?: 0) > 0) && (inviteUnlimited || (inviteUses.toIntOrNull() ?: 0) > 0) && (!inviteUserExpiry || ((inviteUserMonths.toIntOrNull() ?: 0) + (inviteUserDays.toIntOrNull() ?: 0) + (inviteUserHours.toIntOrNull() ?: 0) + (inviteUserMinutes.toIntOrNull() ?: 0) > 0)), onClick = {
            actionBusy = true
            scope.launch { try {
                withContext(Dispatchers.IO) { JfaApi.createInvite(conn!!, inviteMonths.toIntOrNull() ?: 0, inviteDays.toIntOrNull() ?: 0, inviteHours.toIntOrNull() ?: 0, inviteMinutes.toIntOrNull() ?: 0, inviteUses.toIntOrNull() ?: 1, inviteUnlimited, inviteLabel, inviteUserLabel, inviteProfile, inviteUserExpiry, inviteUserMonths.toIntOrNull() ?: 0, inviteUserDays.toIntOrNull() ?: 0, inviteUserHours.toIntOrNull() ?: 0, inviteUserMinutes.toIntOrNull() ?: 0) }
                dialog = ""; refresh++; actionMessage = "Invitation created."
            } catch (e: Exception) { actionMessage = e.message ?: "Invitation creation failed." } finally { actionBusy = false } }
        }) { Text("Create", color = Red) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "edit_invite") AlertDialog(
        onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Manage invitation", color = Txt) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Code · " + selected?.optString("code").orEmpty(), color = Dim, fontSize = AppType.caption)
            OutlinedTextField(inviteLabel, { inviteLabel = it }, label = { Text("Invitation label") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(inviteUserLabel, { inviteUserLabel = it }, label = { Text("New account label") }, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { profileMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(if (inviteProfile.isBlank()) "Server default profile" else inviteProfile) }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    DropdownMenuItem(text = { Text("Server default profile") }, onClick = { inviteProfile = ""; profileMenu = false })
                    profileNames.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { inviteProfile = p; profileMenu = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(inviteNotifyCreation, { inviteNotifyCreation = it }); Text("Notify me when used", color = Mut) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(inviteNotifyExpiry, { inviteNotifyExpiry = it }); Text("Notify me when expired", color = Mut) }
            Divider(color = Line)
            OutlinedTextField(inviteSendAddress, { inviteSendAddress = it }, label = { Text("Send to email / Discord") }, modifier = Modifier.fillMaxWidth())
            Text("Sending requires invitation messaging to be enabled on the server.", color = Dim, fontSize = AppType.caption)
        } },
        confirmButton = { Row {
            TextButton(enabled = !actionBusy, onClick = {
                actionBusy = true
                scope.launch { try {
                    withContext(Dispatchers.IO) { JfaApi.editInvite(conn!!, selected!!.getString("code"), inviteLabel, inviteUserLabel, inviteProfile, inviteNotifyCreation, inviteNotifyExpiry) }
                    if (inviteSendAddress.isNotBlank()) withContext(Dispatchers.IO) { JfaApi.sendInvite(conn!!, selected!!.getString("code"), inviteSendAddress) }
                    dialog = ""; refresh++; actionMessage = "Invitation updated."
                } catch (e: Exception) { actionMessage = e.message ?: "Invitation update failed." } finally { actionBusy = false } }
            }) { Text("Save & send", color = Red) }
        } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "delete_invite") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" },
        title = { Text("Delete invitation?", color = Txt) },
        text = { Text("Delete invitation code ${selected?.optString("code").orEmpty()}?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch {
            try { withContext(Dispatchers.IO) { JfaApi.deleteInvite(conn!!, selected?.optString("code").orEmpty()) }; dialog = ""; refresh++; actionMessage = "Invitation deleted." }
            catch (e: Exception) { actionMessage = e.message ?: "Invitation deletion failed." } finally { actionBusy = false }
        } }) { Text("Delete", color = Color(0xFFFF6878)) } },
        dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "reset") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Generate password reset", color = Txt) }, text = { Text("Generate a Allerion password reset link for " + selected?.optString("name").orEmpty() + "?", color = Mut) },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch { try { val result = withContext(Dispatchers.IO) { JfaApi.resetPassword(conn!!, selected!!.getString("id")) }; val link = result.optString("link").ifBlank { result.optString("url") }
                            resetLink = link
                            actionMessage = if (link.isBlank()) "Allerion accepted the reset request, but returned no link." else "Password reset link is ready."
                            dialog = "" } catch (e: Exception) { actionMessage = e.message ?: "Password reset failed." } finally { actionBusy = false } } }) { Text("Generate", color = Red) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (dialog == "delete") AlertDialog(onDismissRequest = { if (!actionBusy) dialog = "" }, title = { Text("Delete account?", color = Txt) },
        text = { Column {
            Text("Permanently delete " + selected?.optString("name").orEmpty() + " from Allerion?", color = Mut)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(actionNotify, { actionNotify = it }); Text("Notify user", color = Mut) }
            OutlinedTextField(actionReason, { actionReason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !actionBusy, onClick = { actionBusy = true; scope.launch { try { withContext(Dispatchers.IO) { JfaApi.deleteUsers(conn!!, listOf(selected!!.getString("id")), actionNotify, actionReason) }; dialog = ""; refresh++; actionMessage = "Account deleted." } catch (e: Exception) { actionMessage = e.message ?: "Account deletion failed." } finally { actionBusy = false } } }) { Text("Delete", color = Color(0xFFFF6878)) } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
    if (detailUser != null) {
        val u = detailUser!!
        val displayName = u.optString("name").ifBlank { "Account" }
        val expiryValue = u.optLong("expiry", 0L)
        val expiryText = if (expiryValue <= 0L) "No expiry" else
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(expiryValue))
        val active = !u.optBoolean("disabled")
        val jellyfinAdmin = u.optBoolean("admin")
        val allerionAdmin = u.optBoolean("accounts_admin")
        AlertDialog(
            onDismissRequest = { detailUser = null },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Red.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(displayName.take(1).uppercase(), color = Red, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(displayName, color = Txt, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text("ACCOUNT DETAILS", color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = (if (active) Color(0xFF35C98A) else Color(0xFFFF6878)).copy(alpha = 0.13f)
                        ) {
                            Text(if (active) "ACTIVE" else "DISABLED",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = if (active) Color(0xFF35C98A) else Color(0xFFFF6878),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Surf2) {
                            Text(if (jellyfinAdmin) "Jellyfin admin" else "Standard access",
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                color = Mut, fontSize = 11.sp)
                        }
                        if (allerionAdmin) Surface(shape = RoundedCornerShape(8.dp), color = Red.copy(alpha = 0.12f)) {
                            Text("Allerion admin", modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                color = Red, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
                        if (value.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(label.uppercase(), color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                Text(value, color = if (emphasize) Txt else Mut, fontSize = 13.sp,
                                    fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = Surf2.copy(alpha = 0.65f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailRow("Expiry", expiryText, true)
                            if (u.optString("email").isNotBlank()) DetailRow("Email", u.optString("email"))
                            if (u.optString("label").isNotBlank()) DetailRow("Account label", u.optString("label"))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("ACCOUNT ID", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Text(u.optString("id"), color = Dim, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(modifier = Modifier.weight(1f), onClick = {
                            editingUser = u; editEmail = u.optString("email"); editLabel = u.optString("label")
                            detailUser = null; dialog = "edit"
                        }) { Text("Edit details", color = Red, maxLines = 1) }
                        TextButton(modifier = Modifier.weight(1f), onClick = {
                            editingUser = u; editAccountsAdmin = u.optBoolean("accounts_admin")
                            selectedProfile = ""; detailUser = null; dialog = "access"
                        }) { Text("Access & profile", color = Red, maxLines = 1) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(modifier = Modifier.weight(1f), enabled = expiryValue > 0L, onClick = {
                            detailUser = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { JfaApi.removeExpiry(conn!!, u.getString("id")) }
                                    refresh++; actionMessage = "Account expiry removed."
                                } catch (e: Exception) { actionMessage = e.message ?: "Could not remove expiry." }
                            }
                        }) { Text("Remove expiry", color = if (expiryValue > 0L) Amber else Dim, maxLines = 1) }
                        TextButton(modifier = Modifier.weight(1f), onClick = { detailUser = null }) {
                            Text("Close", color = Mut, maxLines = 1)
                        }
                    }
                }
            }
        )
    }
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
