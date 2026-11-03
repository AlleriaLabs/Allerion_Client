package com.example.servermonitor

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

val Bg = Color(0xFF141414)
val Line = Color(0xFF383838)
val Red = Color(0xFFE50914)
val DeepRed = Color(0xFF8E0612)
val Txt = Color(0xFFFFFFFF)
val Mut = Color(0xFFB3B3B3)
val Track = Color(0xFF303030)
val Dim = Color(0xFF6D6D6E)
val Amber = Color(0xFFF5A524)
val Green = Color(0xFF46D369)

data class Server(val name: String, val url: String, val token: String)
data class StatsResult(val json: JSONObject? = null, val error: String? = null)

fun loadServers(p: SharedPreferences): List<Server> {
    val a = JSONArray(p.getString("servers", "[]"))
    val l = List(a.length()) { val o = a.getJSONObject(it); Server(o.getString("name"), o.getString("url"), o.getString("token")) }
    val old = p.getString("url", "") ?: ""
    return if (l.isEmpty() && old.isNotEmpty()) listOf(Server("My server", old, p.getString("token", "") ?: "")) else l
}

fun saveServers(p: SharedPreferences, l: List<Server>) {
    val a = JSONArray()
    l.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
    p.edit().putString("servers", a.toString()).apply()
}

fun fetch(base: String, token: String): JSONObject {
    val c = URL("$base/api/stats").openConnection() as HttpURLConnection
    c.connectTimeout = 3000; c.readTimeout = 3000
    if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
    if (c.responseCode == 401) throw Exception("Wrong token")
    val j = JSONObject(c.inputStream.bufferedReader().readText())
    if (!j.has("cpu")) throw Exception("Server is starting up")
    return j
}

fun rate(bytes: Double): String = when {
    bytes >= 1e6 -> "%.1f MB/s".format(bytes / 1e6)
    bytes >= 1e3 -> "%.1f kB/s".format(bytes / 1e3)
    else -> "%.0f B/s".format(bytes)
}

@Composable
fun rememberStats(sv: Server, every: Long): State<StatsResult> = produceState(StatsResult(), sv) {
    while (true) {
        value = try {
            StatsResult(withContext(Dispatchers.IO) { fetch(sv.url, sv.token) }, null)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { StatsResult(value.json, e.message ?: "Cannot reach the server") }
        delay(every)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Bg, onSurface = Txt)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(prefs) }
            }
        }
    }
}

@Composable
fun App(p: SharedPreferences) {
    var servers by remember { mutableStateOf(loadServers(p)) }
    var open by remember { mutableStateOf<Int?>(null) }
    var edit by remember { mutableStateOf<Int?>(null) } // -1 = new server
    fun commit(l: List<Server>) { servers = l; saveServers(p, l) }
    val e = edit
    val o = open
    BackHandler(enabled = e != null || o != null) { if (e != null) edit = null else open = null }
    when {
        e != null -> EditServer(servers.getOrNull(e),
            onSave = { sv -> commit(if (e < 0) servers + sv else servers.toMutableList().also { it[e] = sv }); edit = null },
            onDelete = { commit(servers.filterIndexed { i, _ -> i != e }); edit = null; open = null },
            onCancel = { edit = null })
        o != null && o in servers.indices -> Dashboard(servers[o], { open = null }, { edit = o })
        else -> Home(servers, { open = it }, { edit = it }, { edit = -1 })
    }
}

val Surf = Color(0xFF1B1B1B)
val Surf2 = Color(0xFF262626)

fun JSONObject.str(k: String): String = if (!has(k) || isNull(k)) "" else optString(k)
fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.then(modifier).fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(18.dp),
        content = content)
}

@Composable
fun Label(text: String) = Text(text.uppercase(), fontSize = 11.sp, color = Dim, fontWeight = FontWeight.Bold,
    letterSpacing = 1.1.sp)

@Composable
fun Bar(frac: Float, h: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h)).background(Track)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(DeepRed, Red))))
    }
}

@Composable
fun Gauge(pct: Double?, dia: Dp = 58.dp, stroke: Dp = 7.dp) {
    val sweep by animateFloatAsState(((pct ?: 0.0) / 100 * 360).toFloat().coerceIn(0f, 360f), tween(700), label = "gauge")
    Canvas(Modifier.size(dia)) {
        val w = stroke.toPx()
        val sz = Size(size.width - w, size.height - w)
        drawArc(Track, 0f, 360f, false, Offset(w / 2, w / 2), sz, style = Stroke(w))
        if (sweep > 0f) drawArc(Red, -90f, sweep, false, Offset(w / 2, w / 2), sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun StatTile(title: String, value: String, sub: String, pct: Double?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Label(title)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1)
                Text(sub, fontSize = 12.sp, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Gauge(pct)
        }
    }
}

@Composable
fun Status(online: Boolean) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Surf2).padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Green else Red))
        Spacer(Modifier.width(6.dp))
        Text(if (online) "Online" else "Offline", fontSize = 12.sp, color = Txt)
    }
}

@Composable
fun BrandBar() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_mark), "AlSent", Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Text("AlSent", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Txt, letterSpacing = 0.5.sp)
    }
}

@Composable
fun Home(servers: List<Server>, onOpen: (Int) -> Unit, onEdit: (Int) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
        BrandBar()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your servers", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Txt)
            Text(if (servers.isEmpty()) "No servers yet. Add the monitor running on your server." else "${servers.size} added",
                color = Mut, fontSize = 13.sp)
            servers.forEachIndexed { i, sv -> ServerCard(sv, { onOpen(i) }, { onEdit(i) }) }
            Button(onAdd, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Txt)) { Text("Add server", fontSize = 16.sp) }
        }
    }
}

@Composable
fun Mini(label: String, text: String, frac: Float, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Mut)
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Txt)
        Spacer(Modifier.height(4.dp))
        Bar(frac, 5.dp)
    }
}

@Composable
fun ServerCard(sv: Server, onOpen: () -> Unit, onEdit: () -> Unit) {
    val r by rememberStats(sv, 3000)
    val j = r.json
    Panel(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sv.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sv.url.removePrefix("http://"), fontSize = 12.sp, color = Dim)
            }
            Status(j != null && r.error == null)
            TextButton(onEdit) { Text("Edit", color = Red) }
        }
        Spacer(Modifier.height(10.dp))
        if (j == null) Text(r.error ?: "Connecting…", color = Amber, fontSize = 13.sp)
        else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val c = j.getDouble("cpu"); val m = j.getDouble("ram")
            Mini("CPU", "%.0f%%".format(c), (c / 100).toFloat(), Modifier.weight(1f))
            Mini("Memory", "%.0f%%".format(m), (m / 100).toFloat(), Modifier.weight(1f))
            val t = j.dbl("temp")
            Mini("Temp", if (t == null) "N/A" else "%.0f °C".format(t), ((t ?: 0.0) / 100).toFloat(), Modifier.weight(1f))
        }
    }
}

@Composable
fun EditServer(sv: Server?, onSave: (Server) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(sv?.name ?: "") }
    var addr by remember { mutableStateOf(sv?.url?.removePrefix("http://") ?: "") }
    var tok by remember { mutableStateOf(sv?.token ?: "") }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SERVER CONNECTION", fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text(if (sv == null) "Add server" else "Edit server", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Txt)
        Text("The address is shown when the monitor starts, for example 192.168.1.50:8080", color = Mut)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(addr, { addr = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tok, { tok = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({
            var clean = addr.trim().trimEnd('/')
            if (!clean.startsWith("http")) clean = "http://$clean"
            onSave(Server(name.trim().ifEmpty { "My server" }, clean, tok.trim()))
        }, Modifier.fillMaxWidth().height(52.dp), enabled = addr.isNotBlank(), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Txt)) { Text("Save") }
        if (sv != null) OutlinedButton(onDelete, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Remove server", color = Red) }
        TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancel", color = Mut) }
    }
}

@Composable
fun Legend(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Txt, modifier = Modifier.weight(1f))
        Text(value, color = Mut)
    }
}

fun httpJson(base: String, token: String, path: String, body: JSONObject? = null): JSONObject {
    val c = URL(base + path).openConnection() as HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 15000
    if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
    if (body != null) {
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    if (c.responseCode == 401) throw Exception("Wrong token")
    val st = if (c.responseCode in 200..299) c.inputStream else c.errorStream
    return JSONObject(st.bufferedReader().readText())
}

val PLUGS = listOf(
    "cpu" to "No plug (CPU estimate)",
    "tuya" to "Meegoo / Tuya / Smart Life plug",
    "tapo" to "TP-Link Tapo P110M",
    "shelly1" to "Shelly (older models)",
    "shelly2" to "Shelly Plus / Pro",
    "tasmota" to "Tasmota plug")

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) Txt else Mut, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Red else Track)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun PowerSetup(sv: Server, onClose: () -> Unit) {
    BackHandler { onClose() }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("cpu") }
    var host by remember { mutableStateOf("") }
    var tid by remember { mutableStateOf("") }
    var tkey by remember { mutableStateOf("") }
    var tver by remember { mutableStateOf("3.3") }
    var tdp by remember { mutableStateOf("19") }
    var tvdp by remember { mutableStateOf("20") }
    var tuser by remember { mutableStateOf("") }
    var tpass by remember { mutableStateOf("") }
    var hasPass by remember { mutableStateOf(false) }
    var tscale by remember { mutableStateOf("0.1") }
    var base by remember { mutableStateOf("0") }
    var price by remember { mutableStateOf("0") }
    var cur by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(sv) {
        try {
            val j = withContext(Dispatchers.IO) { httpJson(sv.url, sv.token, "/api/power/config") }
            source = j.optString("source", "cpu"); host = j.optString("host", "")
            tid = j.optString("tuya_id", ""); tuser = j.optString("tapo_user", ""); hasPass = j.optBoolean("has_pass", false); tver = j.optString("tuya_version", "3.3")
            tdp = j.optString("tuya_dp", "19"); tvdp = j.optString("tuya_voltage_dp", "20"); tscale = j.optDouble("tuya_scale", 0.1).toString()
            base = j.optDouble("base_watts", 0.0).toString(); price = j.optDouble("price", 0.0).toString()
            cur = j.optString("currency", ""); hasKey = j.optBoolean("has_key", false)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { msg = "Could not load settings: " + (e.message ?: "") }
    }
    fun body(): JSONObject = JSONObject().put("source", source).put("host", host.trim()).put("tuya_id", tid.trim())
        .put("tuya_key", tkey.trim()).put("tuya_version", tver).put("tuya_dp", tdp.trim()).put("tuya_voltage_dp", tvdp.trim()).put("tapo_user", tuser.trim()).put("tapo_pass", tpass)
        .put("tuya_scale", tscale.toDoubleOrNull() ?: 0.1).put("base_watts", base.toDoubleOrNull() ?: 0.0)
        .put("price", price.toDoubleOrNull() ?: 0.0).put("currency", cur.trim())
    fun call(path: String, done: (JSONObject) -> String) {
        busy = true; msg = null
        scope.launch {
            msg = try { done(withContext(Dispatchers.IO) { httpJson(sv.url, sv.token, path, body()) }) }
                  catch (e: CancellationException) { throw e }
                  catch (e: Exception) { e.message ?: "Failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClose) { Text("‹ Back", color = Red, fontSize = 16.sp) }
        Text("ENERGY CONFIGURATION", fontSize = 11.sp, color = Red, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text("Power source", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Txt)
        Text("Choose how ${sv.name} measures its power use.", color = Mut)
        PLUGS.forEach { (id, label) ->
            Panel(modifier = Modifier.border(if (source == id) 1.5.dp else 1.dp,
                if (source == id) Red.copy(alpha = 0.8f) else Line, RoundedCornerShape(22.dp)),
                onClick = { source = id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = source == id, onClick = { source = id },
                        colors = RadioButtonDefaults.colors(selectedColor = Red, unselectedColor = Dim))
                    Text(label, color = Txt, fontSize = 16.sp)
                }
            }
        }
        if (source != "cpu") OutlinedTextField(host, { host = it }, label = { Text("Plug IP address") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (source == "tapo") {
            OutlinedTextField(tuser, { tuser = it }, label = { Text("TP-Link account email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tpass, { tpass = it }, label = { Text(if (hasPass) "Password (saved, leave empty to keep)" else "TP-Link account password") },
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Use the same email and password as the Tapo app. Give the plug a fixed IP in your router. If the test fails, in the Tapo app open " +
                "Me > Tapo Lab > Third-Party Compatibility and turn it on.", color = Dim, fontSize = 12.sp)
        }
        if (source == "tuya") {
            OutlinedTextField(tid, { tid = it }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tkey, { tkey = it }, label = { Text(if (hasKey) "Local key (saved, leave empty to keep)" else "Local key") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Protocol version", color = Mut, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("3.3", "3.4", "3.5").forEach { v -> Chip(v, tver == v) { tver = v } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(tdp, { tdp = it }, label = { Text("Power data point") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(tscale, { tscale = it }, label = { Text("Scale") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(tvdp, { tvdp = it }, label = { Text("Voltage data point (usually 20)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Most Meegoo plugs report watts on data point 19 in tenths of a watt (scale 0.1). " +
                "Press Test to see what your plug sends. Find the Device ID and Local Key with: python -m tinytuya wizard", color = Dim, fontSize = 12.sp)
        }
        if (source == "cpu") OutlinedTextField(base, { base = it }, label = { Text("Base watts (board, drives, fans)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(price, { price = it }, label = { Text("Price per kWh") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(cur, { cur = it }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        msg?.let { Text(it, color = if (it.startsWith("Reading") || it.startsWith("Saved") || it.startsWith("Using")) Green else Amber, fontSize = 13.sp) }
        OutlinedButton({ call("/api/power/test") { j ->
            if (j.optBoolean("ok")) (if (j.isNull("watts")) j.optString("note") else "Reading: ${j.getDouble("watts")} W" + (if (j.isNull("voltage")) "" else ", ${j.getDouble("voltage")} V") + "\nData: ${j.optString("raw")}")
            else "Failed: " + j.optString("error") } },
            Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = RoundedCornerShape(14.dp)) {
            Text(if (busy) "Working…" else "Test connection", color = Txt) }
        Button({ call("/api/power/config") { j -> if (j.optBoolean("ok")) "Saved" else "Failed" }; },
            Modifier.fillMaxWidth().height(52.dp), enabled = !busy, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Txt)) { Text("Save") }
    }
}

@Composable
fun InfoChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surf2).padding(13.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, color = Dim, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ToggleMetric(labelA: String, valueA: String, labelB: String, valueB: String, modifier: Modifier = Modifier) {
    var alt by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.clip(shape).background(Surf).border(1.dp, if (alt) Red.copy(alpha = 0.7f) else Line, shape)
        .clickable { alt = !alt }.padding(16.dp)) {
        Label(if (alt) labelB else labelA)
        Spacer(Modifier.height(8.dp))
        Text(if (alt) valueB else valueA, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("Tap to switch", fontSize = 10.sp, color = Dim)
    }
}

@Composable
fun LineChart(values: List<Double>, days: Int) {
    val top = maxOf(0.05, (values.maxOrNull() ?: 0.0) * 1.15)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width; val h = size.height
        for (i in 0..3) drawLine(Line, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1.dp.toPx())
        if (values.isEmpty()) return@Canvas
        fun px(i: Int) = if (days <= 1) 0f else w * i / (days - 1f)
        fun py(v: Double) = h - (v / top * h).toFloat().coerceIn(0f, h)
        val line = Path(); val area = Path()
        values.forEachIndexed { i, v ->
            val x = px(i); val y = py(v)
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(px(values.size - 1), h); area.close()
        drawPath(area, Brush.verticalGradient(listOf(Red.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, Red, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val c = Offset(px(values.size - 1), py(values.last()))
        drawCircle(Txt, 6.dp.toPx(), c); drawCircle(Red, 3.5.dp.toPx(), c)
    }
}

@Composable
fun PowerTab(pw: JSONObject?, onSetup: () -> Unit) {
    if (pw == null) { Text("This server does not report power yet. Update the server program.", color = Amber); return }
    val watts = pw.dbl("watts")
    val volts = pw.dbl("voltage")
    val today = pw.optDouble("today_kwh", 0.0)
    val month = pw.optDouble("month_kwh", 0.0)
    val price = pw.optDouble("price", 0.0)
    val cur = pw.str("currency")
    val year = pw.dbl("year_kwh")
    fun cost(k: Double) = if (price > 0) cur + "%.2f".format(k * price) else "Set price"
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Power source")
                Text(pw.str("source").ifEmpty { "No sensor" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Txt)
            }
            Status(watts != null)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (watts == null) "N/A" else "%.0f".format(watts), fontSize = 54.sp, fontWeight = FontWeight.Bold, color = Txt)
            Text(" W", fontSize = 20.sp, color = Mut, modifier = Modifier.padding(bottom = 11.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoChip("Voltage", if (volts == null) "N/A" else "%.1f V".format(volts), Modifier.weight(1f))
            InfoChip("Today", "%.2f kWh".format(today), Modifier.weight(1f))
        }
        TextButton(onSetup, contentPadding = PaddingValues(0.dp)) { Text("Configure power source ›", color = Red, fontSize = 13.sp) }
        val perr = pw.str("plug_error")
        if (perr.isNotEmpty()) Text("Plug: $perr", fontSize = 12.sp, color = Amber)
        else if (watts == null) Text("No power reading found. Tap Configure to pick your plug.", fontSize = 12.sp, color = Dim)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleMetric("Current Month Usage", "%.2f kWh".format(month), "Current Month Cost", cost(month), Modifier.weight(1f))
        ToggleMetric("Current Year Usage", year?.let { "%.2f kWh".format(it) } ?: "Update server",
            "Current Year Cost", year?.let { cost(it) } ?: "Update server", Modifier.weight(1f))
    }
    val mh = pw.optJSONArray("month_history") ?: pw.optJSONArray("history") ?: JSONArray()
    val vals = List(mh.length()) { mh.getJSONObject(it).optDouble("kwh", 0.0) }
    val days = pw.optInt("days_in_month", vals.size).coerceAtLeast(vals.size).coerceAtLeast(1)
    val monthName = try {
        java.time.LocalDate.parse(mh.getJSONObject(0).getString("day")).month
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    } catch (e: Exception) { "This month" }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("Daily usage · $monthName")
            Text("Peak %.2f kWh".format(vals.maxOrNull() ?: 0.0), fontSize = 12.sp, color = Mut)
        }
        Spacer(Modifier.height(12.dp))
        LineChart(vals, days)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1, days / 3, 2 * days / 3, days).filter { it >= 1 }.distinct().forEach { Text("$it", fontSize = 10.sp, color = Dim) }
        }
        val since = pw.str("since")
        if (since.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Energy tracked since $since", fontSize = 11.sp, color = Dim) }
    }
}

@Composable
fun Speed3(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Mut)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1)
        Text(unit, fontSize = 11.sp, color = Dim)
    }
}

@Composable
fun InternetPanel(j: JSONObject, sv: Server) {
    val scope = rememberCoroutineScope()
    val sp = j.optJSONObject("speed") ?: JSONObject()
    fun num(k: String): String = sp.dbl(k)?.let { "%.0f".format(it) } ?: "–"
    val ping = sp.dbl("ping_ms") ?: j.dbl("ping_ms")
    val running = sp.optBoolean("running", false)
    Panel(onClick = {
        scope.launch {
            try { withContext(Dispatchers.IO) { httpJson(sv.url, sv.token, "/api/speedtest", JSONObject()) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Internet · Speedtest by Ookla")
            Text(if (running) "Testing…" else "Tap to test", fontSize = 12.sp, color = if (running) Red else Dim)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Speed3("Download", num("down_mbps"), "Mbps", Modifier.weight(1f))
            Speed3("Upload", num("up_mbps"), "Mbps", Modifier.weight(1f))
            Speed3("Ping", ping?.let { "%.0f".format(it) } ?: "–", "ms", Modifier.weight(1f))
        }
        val srv = sp.str("server")
        if (srv.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(srv, fontSize = 12.sp, color = Dim) }
        val err = sp.str("error")
        if (err.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(err, fontSize = 12.sp, color = Amber) }
    }
}

@Composable
fun TempTile(temp: Double?, modifier: Modifier) =
    StatTile("Temperature", if (temp == null) "N/A" else "%.0f °C".format(temp), if (temp == null) "No sensor" else "CPU", temp, modifier)

@Composable
fun OverviewTab(j: JSONObject, sv: Server, onPower: () -> Unit) {
    val cpu = j.getDouble("cpu"); val ram = j.getDouble("ram")
    val temp = j.dbl("temp"); val gpu = j.dbl("gpu")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("CPU", "%.0f%%".format(cpu), "${j.optInt("cores")} cores", cpu, Modifier.weight(1f))
        StatTile("Memory", "%.0f%%".format(ram), "${j.optDouble("ram_used_gb")} / ${j.optDouble("ram_total_gb")} GB", ram, Modifier.weight(1f))
    }
    if (gpu != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TempTile(temp, Modifier.weight(1f))
        StatTile("GPU", "%.0f%%".format(gpu), "Usage", gpu, Modifier.weight(1f))
    } else TempTile(temp, Modifier.fillMaxWidth())
    val pw = j.optJSONObject("power")
    Panel(onClick = onPower) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Power")
                val w = pw?.dbl("watts")
                Text(if (w == null) "N/A" else "%.0f W".format(w), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Txt)
                Text("Today %.2f kWh".format(pw?.optDouble("today_kwh", 0.0) ?: 0.0) +
                    (pw?.dbl("voltage")?.let { "  ·  %.0f V".format(it) } ?: ""), fontSize = 12.sp, color = Mut)
            }
            Text("›", fontSize = 28.sp, color = Red)
        }
    }
    InternetPanel(j, sv)
}

@Composable
fun DiskPie(d: JSONArray) {
    val cols = listOf(Red, Color(0xFFF5F5F1), Color(0xFFB20710), Color(0xFF8C8C8C), Color(0xFFFF6B6B), Color(0xFF5E5E5E))
    val names = List(d.length()) { d.getJSONObject(it).optString("mount", "?") }
    val used = List(d.length()) { d.getJSONObject(it).optDouble("used_gb", 0.0) }
    val tot = List(d.length()) { d.getJSONObject(it).optDouble("total_gb", 0.0) }
    val sumTotal = maxOf(tot.sum(), 0.001); val sumUsed = used.sum(); val free = maxOf(sumTotal - sumUsed, 0.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                var start = -90f
                (used + free).forEachIndexed { i, v ->
                    val sweep = (v / sumTotal * 360).toFloat()
                    if (sweep > 0.3f) {
                        drawArc(color = if (i < used.size) cols[i % cols.size] else Track, startAngle = start, sweepAngle = sweep,
                            useCenter = true, topLeft = Offset.Zero, size = size)
                        drawArc(color = Surf, startAngle = start, sweepAngle = sweep, useCenter = true, topLeft = Offset.Zero,
                            size = size, style = Stroke(2.dp.toPx()))
                    }
                    start += sweep
                }
                drawCircle(Surf, radius = size.minDimension * 0.27f, center = center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.0f%%".format(sumUsed / sumTotal * 100), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Txt)
                Text("used", fontSize = 11.sp, color = Dim)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            names.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(cols[i % cols.size]))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(n, color = Txt, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%.1f / %.1f GB".format(used[i], tot[i]), fontSize = 12.sp, color = Mut)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Track))
                Spacer(Modifier.width(8.dp))
                Text("Free %.1f GB".format(free), fontSize = 12.sp, color = Mut)
            }
        }
    }
}

@Composable
fun SystemTab(j: JSONObject) {
    Panel {
        Label("Storage")
        Spacer(Modifier.height(14.dp))
        DiskPie(j.optJSONArray("disks") ?: JSONArray())
    }
    Panel {
        Label("Network")
        Spacer(Modifier.height(8.dp))
        Legend(Red, "Download", rate(j.getDouble("net_down_bps") / 8))
        Legend(Txt, "Upload", rate(j.getDouble("net_up_bps") / 8))
        Legend(Dim, "IPv4", j.str("ip").ifEmpty { "-" })
    }
    val procs = j.optJSONArray("procs") ?: JSONArray()
    Panel {
        Label("Applications")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Name", color = Dim, modifier = Modifier.weight(2f), fontSize = 12.sp)
            Text("CPU", color = Dim, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, fontSize = 12.sp)
            Text("Memory", color = Dim, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End, fontSize = 12.sp)
        }
        for (i in 0 until procs.length()) {
            val o = procs.getJSONObject(i)
            val mem = o.getInt("mem_mb")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (i % 2 == 0) Surf2 else Color.Transparent).padding(horizontal = 6.dp, vertical = 7.dp)) {
                Text(o.getString("name"), color = Txt, modifier = Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.1f%%".format(o.getDouble("cpu")), color = Mut, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text(if (mem >= 1000) "%.1f GB".format(mem / 1000.0) else "$mem MB", color = Mut, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun Dashboard(sv: Server, onBack: () -> Unit, onEdit: () -> Unit) {
    val r by rememberStats(sv, 2000)
    val j = r.json
    var setup by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    if (setup) { PowerSetup(sv) { setup = false }; return }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹", color = Red, fontSize = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text(sv.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val up = j?.optLong("uptime_s") ?: 0L
                Text(if (j == null) "Connecting…" else "Up ${up / 86400}d ${up % 86400 / 3600}h  ·  ${j.str("host")}", color = Mut, fontSize = 12.sp)
            }
            Status(j != null && r.error == null)
            TextButton(onEdit) { Text("Edit", color = Red) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(Surf).padding(4.dp)) {
            listOf("Overview", "Power", "System").forEachIndexed { i, t ->
                val sel = tab == i
                Text(t, Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) Red else Color.Transparent)
                    .clickable { tab = i }.padding(vertical = 10.dp),
                    color = if (sel) Txt else Mut, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
            if (j == null) return@Column
            when (tab) {
                0 -> OverviewTab(j, sv) { tab = 1 }
                1 -> PowerTab(j.optJSONObject("power")) { setup = true }
                else -> SystemTab(j)
            }
        }
    }
}
