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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
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

data class JellyfinConnection(val name: String, val url: String, val apiKey: String)

object JellyfinCredentialStore {
    private const val PREFS = "jellyfin_admin"
    private const val VALUE = "connection_v1"
    private const val KEY_ALIAS = "allerion.jellyfin.credentials.v1"

    fun load(context: Context): JellyfinConnection? = runCatching {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null) ?: return null
        val json = JSONObject(decrypt(encrypted))
        JellyfinConnection(json.optString("name"), json.optString("url"), json.optString("apiKey"))
    }.getOrNull()

    fun save(context: Context, connection: JellyfinConnection) {
        val json = JSONObject().put("name", connection.name).put("url", connection.url).put("apiKey", connection.apiKey)
        val encrypted = encrypt(json.toString())
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VALUE, encrypted).commit()) {
            "Could not securely save the Jellyfin connection."
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val size = bytes[0].toInt() and 0xff
        require(size in 12..16 && bytes.size > size + 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + size)))
        return String(cipher.doFinal(bytes.copyOfRange(1 + size, bytes.size)), Charsets.UTF_8)
    }
}

object JellyfinApi {
    private fun request(connection: JellyfinConnection, method: String, path: String, body: JSONObject? = null): String {
        val base = connection.url.trim().trimEnd('/')
        val url = URL(base + if (path.startsWith("/")) path else "/$path")
        require(url.protocol == "http" || url.protocol == "https") { "Use an HTTP or HTTPS Jellyfin address." }
        val http = url.openConnection() as HttpURLConnection
        try {
            http.requestMethod = method
            http.connectTimeout = 6000
            http.readTimeout = 10000
            http.useCaches = false
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("X-Emby-Token", connection.apiKey)
            http.setRequestProperty("X-Emby-Authorization", "MediaBrowser Client=\"ALLERION\", Device=\"Android\", DeviceId=\"allerion-android\", Version=\"1.0\", Token=\"${connection.apiKey}\"")
            if (body != null) {
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json")
                http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = http.responseCode
            val stream = if (status in 200..299) http.inputStream else http.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("Message") }.getOrNull().orEmpty()
                throw IllegalStateException(if (detail.isBlank()) "Jellyfin returned HTTP $status." else "Jellyfin: $detail")
            }
            return response
        } finally {
            http.disconnect()
        }
    }

    fun test(connection: JellyfinConnection) {
        require(connection.apiKey.isNotBlank()) { "Enter a Jellyfin API key." }
        val base = connection.url.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) { "Enter a full Jellyfin URL, including http:// or https://." }
        request(connection, "GET", "/Users")
    }

    fun users(connection: JellyfinConnection): List<JSONObject> {
        val raw = request(connection, "GET", "/Users")
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    fun sessions(connection: JellyfinConnection): List<JSONObject> {
        val raw = request(connection, "GET", "/Sessions")
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    fun createUser(connection: JellyfinConnection, name: String, password: String) {
        request(connection, "POST", "/Users/New", JSONObject().put("Name", name).put("Password", password))
    }

    fun updatePolicy(connection: JellyfinConnection, userId: String, policy: JSONObject) {
        request(connection, "POST", "/Users/$userId/Policy", policy)
    }

    fun updateProfile(connection: JellyfinConnection, userId: String, user: JSONObject) {
        request(connection, "POST", "/Users/$userId", user)
    }

    fun changePassword(connection: JellyfinConnection, userId: String, newPassword: String) {
        request(connection, "POST", "/Users/$userId/Password",
            JSONObject().put("CurrentPw", "").put("NewPw", newPassword))
    }

    fun deleteUser(connection: JellyfinConnection, userId: String) {
        request(connection, "DELETE", "/Users/$userId")
    }
}

private data class JellyfinSessionRow(
    val userId: String,
    val userName: String,
    val device: String,
    val client: String,
    val remote: String
)

@Composable
fun JellyfinUsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf(JellyfinCredentialStore.load(context)) }
    var editingConnection by remember { mutableStateOf(connection == null) }
    var name by remember { mutableStateOf(connection?.name ?: "Jellyfin") }
    var url by remember { mutableStateOf(connection?.url ?: "") }
    var apiKey by remember { mutableStateOf(connection?.apiKey ?: "") }
    var testing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<JellyfinSessionRow>>(emptyList()) }
    var tab by remember { mutableStateOf("Users") }
    var selectedUser by remember { mutableStateOf<JSONObject?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    fun reload(conn: JellyfinConnection) {
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    JellyfinApi.users(conn) to JellyfinApi.sessions(conn)
                }
                users = result.first
                sessions = result.second.map { item ->
                    JellyfinSessionRow(
                        userId = item.optString("UserId"),
                        userName = item.optString("UserName", "Unknown user"),
                        device = item.optString("DeviceName", "Unknown device"),
                        client = item.optString("Client", "Unknown client"),
                        remote = item.optString("RemoteEndPoint", "")
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message ?: "Could not load Jellyfin users."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(connection, revision) { connection?.let(::reload) }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text("User management", color = Txt, fontSize = AppType.section, fontWeight = FontWeight.Bold)
                Text("Jellyfin accounts, access and active devices", color = Mut, fontSize = AppType.small)
            }
            TextButton(onClick = { editingConnection = !editingConnection }) { Text("Connection", color = Red) }
        }

        if (editingConnection) {
            Panel {
                Label("Jellyfin connection")
                Text("Connect directly to your Jellyfin server with an administrator API key. This is separate from the ALLERION monitoring token.",
                    color = Mut, fontSize = AppType.small)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it; error = null }, label = { Text("Jellyfin URL (e.g. http://192.168.1.10:8096)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it; error = null }, label = { Text("Administrator API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val candidate = JellyfinConnection(name.trim().ifBlank { "Jellyfin" }, url.trim().trimEnd('/'), apiKey.trim())
                        testing = true
                        scope.launch {
                            error = null
                            try {
                                withContext(Dispatchers.IO) { JellyfinApi.test(candidate) }
                                JellyfinCredentialStore.save(context, candidate)
                                connection = candidate
                                editingConnection = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                error = e.message ?: "Connection failed."
                            } finally { testing = false }
                        }
                    }, enabled = !testing && url.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text(if (testing) "Testing…" else "Test & save")
                    }
                    if (connection != null) OutlinedButton(onClick = {
                        JellyfinCredentialStore.clear(context)
                        connection = null
                        users = emptyList()
                        sessions = emptyList()
                        editingConnection = true
                    }) { Text("Disconnect") }
                }
            }
        }

        error?.let { StateMessage(it, true) }

        if (connection == null) {
            Panel {
                Label("No Jellyfin connection")
                Text("User management needs a separate Jellyfin administrator API key. You can add it when you have access to the server; the rest of Allerion remains usable without it.",
                    color = Mut, fontSize = AppType.small)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(connection?.name ?: "Jellyfin", color = Txt, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${users.size} users", color = Mut, fontSize = AppType.small)
                TextButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh", color = Red) }
            }
            Row(Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(14.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Users", "Devices").forEach { item ->
                    val selected = tab == item
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Surf2 else Color.Transparent).clickable { tab = item }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (item == "Devices") "Active devices" else item, color = if (selected) Txt else Mut, fontSize = AppType.small, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (loading && users.isEmpty()) {
                Panel { Text("Loading Jellyfin users…", color = Mut, fontSize = AppType.small) }
            } else if (tab == "Users") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Accounts")
                        Text("Manage access and account status", color = Mut, fontSize = AppType.small)
                    }
                    Button(onClick = { showCreate = true }) { Text("Add user") }
                }
                if (users.isEmpty() && !loading) Panel { Text("No Jellyfin users were returned.", color = Mut, fontSize = AppType.small) }
                users.forEach { user ->
                    val userId = user.optString("Id")
                    val policy = user.optJSONObject("Policy") ?: JSONObject()
                    val disabled = policy.optBoolean("IsDisabled", false)
                    Panel(Modifier.clickable { selectedUser = JSONObject(user.toString()) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(Surf2), contentAlignment = Alignment.Center) {
                                Text(user.optString("Name").take(1).uppercase().ifBlank { "?" }, color = Red, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.optString("Name", "Unnamed user"), color = Txt, fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    disabled -> "Disabled"
                                    policy.optBoolean("IsAdministrator", false) -> "Administrator"
                                    else -> "Standard account"
                                }, color = if (disabled) Amber else Mut, fontSize = AppType.caption)
                                Text("Last active: " + (user.optString("LastActivityDate").takeIf { it.isNotBlank() } ?: "Unknown"),
                                    color = Dim, fontSize = AppType.micro)
                            }
                            Icon24(Ic.ChevronD, Dim, 17.dp)
                        }
                    }
                }
            } else {
                Label("Currently connected")
                if (sessions.isEmpty()) Panel { Text("No active Jellyfin sessions.", color = Mut, fontSize = AppType.small) }
                sessions.forEach { session ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon24(Ic.Server, Red, 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.device, color = Txt, fontWeight = FontWeight.SemiBold)
                                Text("${session.userName} · ${session.client}", color = Mut, fontSize = AppType.small)
                                if (session.remote.isNotBlank()) Text(session.remote, color = Dim, fontSize = AppType.caption)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    selectedUser?.let { user ->
        JellyfinUserEditor(
            user = user,
            onDismiss = { selectedUser = null },
            onSaved = { selectedUser = null; revision++ },
            onDelete = { selectedUser = null; revision++ },
            connection = connection
        )
    }
    if (showCreate && connection != null) {
        CreateJellyfinUserDialog(
            onDismiss = { showCreate = false },
            onCreate = { username, password ->
                scope.launch {
                    loading = true
                    error = null
                    try {
                        withContext(Dispatchers.IO) { JellyfinApi.createUser(connection!!, username, password) }
                        showCreate = false
                        revision++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message ?: "Could not create the user." }
                    finally { loading = false }
                }
            }
        )
    }
}

@Composable
private fun CreateJellyfinUserDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Jellyfin user", color = Txt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Temporary password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(username.trim(), password) }, enabled = username.isNotBlank() && password.isNotBlank()) { Text("Create", color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mut) } },
        containerColor = Surf
    )
}

@Composable
private fun JellyfinUserEditor(
    user: JSONObject,
    connection: JellyfinConnection?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val userId = user.optString("Id")
    val policy = user.optJSONObject("Policy")?.let { JSONObject(it.toString()) } ?: JSONObject()
    var displayName by remember(userId) { mutableStateOf(user.optString("Name")) }
    var disabled by remember(userId) { mutableStateOf(policy.optBoolean("IsDisabled", false)) }
    var administrator by remember(userId) { mutableStateOf(policy.optBoolean("IsAdministrator", false)) }
    var remote by remember(userId) { mutableStateOf(policy.optBoolean("EnableRemoteAccess", true)) }
    var allLibraries by remember(userId) { mutableStateOf(policy.optBoolean("EnableAllFolders", true)) }
    var allDevices by remember(userId) { mutableStateOf(policy.optBoolean("EnableAllDevices", true)) }
    var mediaPlayback by remember(userId) { mutableStateOf(policy.optBoolean("EnableMediaPlayback", true)) }
    var password by remember(userId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage user", color = Txt) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Label("Account permissions")
                UserPermissionSwitch("Administrator", administrator) { administrator = it }
                UserPermissionSwitch("Disabled", disabled) { disabled = it }
                UserPermissionSwitch("Allow remote access", remote) { remote = it }
                UserPermissionSwitch("Access all libraries", allLibraries) { allLibraries = it }
                UserPermissionSwitch("Access from all devices", allDevices) { allDevices = it }
                UserPermissionSwitch("Allow media playback", mediaPlayback) { mediaPlayback = it }
                Label("Set new password")
                OutlinedTextField(password, { password = it }, label = { Text("Leave blank to keep current password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = Err, fontSize = AppType.small) }
                Text("Policy changes are sent to Jellyfin immediately when you save. Deleting an account is permanent.",
                    color = Dim, fontSize = AppType.caption)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val conn = connection ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val nextUser = JSONObject(user.toString()).put("Name", displayName.trim())
                            JellyfinApi.updateProfile(conn, userId, nextUser)
                            val nextPolicy = JSONObject(policy.toString())
                                .put("IsAdministrator", administrator)
                                .put("IsDisabled", disabled)
                                .put("EnableRemoteAccess", remote)
                                .put("EnableAllFolders", allLibraries)
                                .put("EnableAllDevices", allDevices)
                                .put("EnableMediaPlayback", mediaPlayback)
                            JellyfinApi.updatePolicy(conn, userId, nextPolicy)
                            if (password.isNotBlank()) JellyfinApi.changePassword(conn, userId, password)
                        }
                        onSaved()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message ?: "Could not save user changes." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save changes", color = Red) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete", color = Err) }
                TextButton(onClick = onDismiss) { Text("Close", color = Mut) }
            }
        },
        containerColor = Surf
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete Jellyfin user?", color = Txt) },
        text = { Text("This permanently deletes ${user.optString("Name")}. This cannot be undone.", color = Mut) },
        confirmButton = {
            TextButton(onClick = {
                val conn = connection ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { JellyfinApi.deleteUser(conn, userId) }
                        confirmDelete = false
                        onDelete()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message ?: "Could not delete user."; confirmDelete = false }
                    finally { busy = false }
                }
            }) { Text("Delete permanently", color = Err) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Mut) } },
        containerColor = Surf
    )
}

@Composable
private fun UserPermissionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Txt, fontSize = AppType.small, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
