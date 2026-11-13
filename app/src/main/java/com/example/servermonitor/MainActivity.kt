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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// Allerion "Obsidian Signal" design system: cool graphite, soft white, focused crimson.
val Bg = Color(0xFF090B10)
val Line = Color(0xFF252A34)
val Red = Color(0xFFFF3548)
val DeepRed = Color(0xFF9E1B2A)
val Txt = Color(0xFFF3F5F8)
val Mut = Color(0xFFA0A8B5)
val Track = Color(0xFF252B35)
val Dim = Color(0xFF727D8D)
val Amber = Color(0xFFFFC46B)
val Green = Color(0xFF43D6A0)

data class Server(val name: String, val url: String, val token: String, val group: String = "Ungrouped")
data class StatsResult(val json: JSONObject? = null, val error: String? = null)

fun loadServers(p: SharedPreferences): List<Server> = SecureServerStore.load(p)

fun saveServers(p: SharedPreferences, l: List<Server>) = SecureServerStore.save(p, l)

fun fetch(base: String, token: String): JSONObject {
    val j = ApiClient.getJson(base, token, "/api/stats", connectTimeoutMs = 3000, readTimeoutMs = 5000)
    if (!j.has("cpu")) throw ApiException("Monitoring service is starting up.")
    return j
}

fun rate(bytes: Double): String = when {
    bytes >= 1e6 -> "%.1f MB/s".format(bytes / 1e6)
    bytes >= 1e3 -> "%.1f kB/s".format(bytes / 1e3)
    else -> "%.0f B/s".format(bytes)
}

@Composable
fun rememberStats(sv: Server, every: Long, refreshKey: Int = 0): State<StatsResult> {
    val lc = LocalLifecycleOwner.current.lifecycle          // no polling while the app is in the background
    return produceState(StatsResult(), sv, every, refreshKey) {
        var failures = 0
        while (true) {
            if (lc.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try {
                    val fresh = withContext(Dispatchers.IO) { fetch(sv.url, sv.token) }
                    value = StatsResult(fresh, null)
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures = (failures + 1).coerceAtMost(6)
                    value = StatsResult(value.json, friendlyError(e))
                }
            }
            delay(pollDelay(every, failures))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        AppSettings.refreshMs = prefs.getLong("refresh_ms", 2000L)
        AlertScheduler.ensureScheduled(this)
        val reduce = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(true) }
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, onPrimary = Color.White, primaryContainer = DeepRed.copy(alpha = 0.32f), onPrimaryContainer = Txt, secondary = Blue, onSecondary = Bg, background = Bg, onBackground = Txt, surface = Surf, onSurface = Txt, surfaceVariant = Surf2, onSurfaceVariant = Mut, outline = Line, outlineVariant = Line.copy(alpha = 0.72f), error = Err, onError = Color.White), shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp))) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Box { App(prefs); AnimatedVisibility(visible = showSplash, enter = EnterTransition.None, exit = fadeOut(tween(350))) { Splash { showSplash = false } } } }
            }
            }
        }
    }
}

@Composable
fun App(p: SharedPreferences) {
    val appContext = LocalContext.current
    var availableUpdate by remember { mutableStateOf<AllerionUpdate?>(null) }
    LaunchedEffect(Unit) {
        delay(3500)
        availableUpdate = runCatching { UpdateChecker.check(appContext) }.getOrNull()
    }
    val initialLoad = remember { runCatching { loadServers(p) } }
    var servers by remember { mutableStateOf(initialLoad.getOrDefault(emptyList())) }
    var storageError by remember { mutableStateOf(initialLoad.exceptionOrNull()?.message) }
    var open by remember { mutableStateOf<Int?>(null) }
    var edit by remember { mutableStateOf<Int?>(null) } // -1 = new server
    var expanded by remember { mutableStateOf<Int?>(null) }   // every server card starts collapsed
    var hubSettings by remember { mutableStateOf(false) }
    var showIntegrations by remember { mutableStateOf(false) }
    var showUsers by remember { mutableStateOf(false) }
    fun commit(l: List<Server>) {
        try {
            saveServers(p, l)
            servers = l
            storageError = null
        } catch (error: Exception) {
            storageError = error.message ?: "Could not securely save server settings."
        }
    }
    if (storageError != null) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text("Server settings need attention", color = Txt, fontSize = AppType.section, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("ALLERION could not unlock or save its encrypted server settings. Your saved credentials have not been replaced. Do not clear app data or reinstall until the Android Keystore issue is resolved.",
                color = Mut, fontSize = AppType.body)
            Spacer(Modifier.height(12.dp))
            Text(storageError ?: "Secure storage error", color = Amber, fontSize = AppType.small)
        }
        return
    }
    val e = edit
    val o = open
    BackHandler(enabled = e != null || o != null || hubSettings || showIntegrations || showUsers) { if (e != null) edit = null else if (o != null) open = null else if (showUsers) showUsers = false else if (showIntegrations) showIntegrations = false else hubSettings = false }
    when {
        e != null -> EditServer(servers.getOrNull(e),
            onSave = { sv -> commit(if (e < 0) servers + sv else servers.toMutableList().also { it[e] = sv }); edit = null },
            onDelete = { commit(servers.filterIndexed { i, _ -> i != e }); edit = null; open = null; expanded = null },
            onCancel = { edit = null })
        o != null && o in servers.indices -> Dashboard(servers[o], { open = null }, { edit = o })
        hubSettings -> HubSettings(onRestore = { restored -> commit(restored) }, onBack = { hubSettings = false })
        showIntegrations -> IntegrationCenterScreen(servers, onBack = { showIntegrations = false })
        showUsers -> JfaGoUsersScreen(onBack = { showUsers = false })
        else -> Home(servers, expanded, { expanded = it }, { open = it }, { edit = it },
            { i -> commit(servers.filterIndexed { k, _ -> k != i }); expanded = null }, { edit = -1 }, { hubSettings = true }, { showIntegrations = true }, { showUsers = true })
    }
    availableUpdate?.let { update ->
        AllerionUpdateDialog(update) {
            UpdateChecker.dismiss(appContext, update.tag)
            availableUpdate = null
        }
    }
}

val Surf = Color(0xFF121720)
val Surf2 = Color(0xFF1A2230)

fun JSONObject.str(k: String): String = if (!has(k) || isNull(k)) "" else optString(k)
fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(21.dp)
    Box(
        Modifier.then(modifier).fillMaxWidth().clip(shape).background(Surf)
            .border(1.dp, Line.copy(alpha = 0.78f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        // A quiet top-edge highlight gives the surface depth without washing out its content.
        Box(Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.075f), Color.Transparent))
        ))
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
fun Label(text: String) = Text(text.uppercase(), fontSize = AppType.caption, color = Dim, fontWeight = FontWeight.Bold,
    letterSpacing = 1.5.sp)

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
                Text(value, fontSize = AppType.displaySmall, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1)
                Text(sub, fontSize = AppType.small, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Gauge(pct)
        }
    }
}

@Composable
fun Status(online: Boolean) {
    val statusColor = if (online) Green else Err
    Row(Modifier.clip(RoundedCornerShape(50))
        .background(statusColor.copy(alpha = 0.09f))
        .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(50))
        .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PulseDot(statusColor, online)
        Spacer(Modifier.width(6.dp))
        Text(if (online) "ONLINE" else "OFFLINE", fontSize = AppType.micro, color = statusColor, fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp)
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

fun httpJson(base: String, token: String, path: String, body: JSONObject? = null): JSONObject =
    ApiClient.getJson(base, token, path, body)

val PLUGS = listOf(
    "cpu" to "No plug (CPU estimate)",
    "tuya" to "Meegoo / Tuya / Smart Life plug",
    "tapo" to "TP-Link Tapo P110M",
    "shelly1" to "Shelly (older models)",
    "shelly2" to "Shelly Plus / Pro",
    "tasmota" to "Tasmota plug")

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(text, color = if (selected) Txt else Mut, fontSize = AppType.body,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.clip(shape)
            .background(if (selected) Red.copy(alpha = 0.17f) else Surf2)
            .border(1.dp, if (selected) Red.copy(alpha = 0.42f) else Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 9.dp))
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
        TextButton(onClose) { Text("‹ Back", color = Red, fontSize = AppType.bodyStrong) }
        Text("ENERGY CONFIGURATION", fontSize = AppType.caption, color = Red, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text("Power source", fontSize = AppType.display, fontWeight = FontWeight.Bold, color = Txt)
        Text("Choose how ${sv.name} measures its power use.", color = Mut)
        PLUGS.forEach { (id, label) ->
            Panel(modifier = Modifier.border(if (source == id) 1.5.dp else 1.dp,
                if (source == id) Red.copy(alpha = 0.8f) else Line, RoundedCornerShape(22.dp)),
                onClick = { source = id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = source == id, onClick = { source = id },
                        colors = RadioButtonDefaults.colors(selectedColor = Red, unselectedColor = Dim))
                    Text(label, color = Txt, fontSize = AppType.bodyStrong)
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
                "Me > Tapo Lab > Third-Party Compatibility and turn it on.", color = Dim, fontSize = AppType.small)
        }
        if (source == "tuya") {
            OutlinedTextField(tid, { tid = it }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tkey, { tkey = it }, label = { Text(if (hasKey) "Local key (saved, leave empty to keep)" else "Local key") },
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Protocol version", color = Mut, fontSize = AppType.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("3.3", "3.4", "3.5").forEach { v -> Chip(v, tver == v) { tver = v } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(tdp, { tdp = it }, label = { Text("Power data point") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(tscale, { tscale = it }, label = { Text("Scale") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(tvdp, { tvdp = it }, label = { Text("Voltage data point (usually 20)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Most Meegoo plugs report watts on data point 19 in tenths of a watt (scale 0.1). " +
                "Press Test to see what your plug sends. Find the Device ID and Local Key with: python -m tinytuya wizard", color = Dim, fontSize = AppType.small)
        }
        if (source == "cpu") OutlinedTextField(base, { base = it }, label = { Text("Base watts (board, drives, fans)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(price, { price = it }, label = { Text("Price per kWh") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(cur, { cur = it }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        msg?.let { Text(it, color = if (it.startsWith("Reading") || it.startsWith("Saved") || it.startsWith("Using")) Green else Amber, fontSize = AppType.body) }
        OutlinedButton({ call("/api/power/test") { j ->
            if (j.optBoolean("ok")) (if (j.isNull("watts")) j.optString("note") else "Reading: ${j.getDouble("watts")} W" + (if (j.isNull("voltage")) "" else ", ${j.getDouble("voltage")} V") + "\nData: ${j.optString("raw")}")
            else "Failed: " + j.optString("error") } },
            Modifier.fillMaxWidth().height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)),
            enabled = !busy, shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Surf2, contentColor = Txt)) {
            Text(if (busy) "Working…" else "Test connection", color = Txt, fontWeight = FontWeight.Medium) }
        Button({ call("/api/power/config") { j -> if (j.optBoolean("ok")) "Saved" else "Failed" }; },
            Modifier.fillMaxWidth().height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)),
            enabled = !busy, shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Surf2, contentColor = Txt),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Text("Save", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun PowerStatTile(
    label: String, value: String, modifier: Modifier = Modifier,
    series: List<GSeries> = emptyList(), minSpan: Double = 5.0,
    alternate: Pair<String, String>? = null
) {
    var showAlternate by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.height(112.dp).clip(shape).background(Surf).border(1.dp, Line, shape)
        .then(if (alternate != null) Modifier.clickable { showAlternate = !showAlternate } else Modifier)) {
        if (series.any { s -> s.values.any { it != null } }) {
            HistoryGraph(series, Modifier.matchParentSize(), minSpan, lineAlpha = 0.48f, fillAlpha = 0.10f, stroke = 1.6.dp)
        }
        Box(Modifier.matchParentSize().background(
            Brush.horizontalGradient(0f to Surf.copy(alpha = 0.78f), 0.76f to Surf.copy(alpha = 0.42f), 1f to Color.Transparent)
        ))
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text((if (showAlternate) alternate?.first else label)?.uppercase().orEmpty(),
                fontSize = AppType.small, color = Mut, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            val displayValue = if (showAlternate) alternate?.second ?: value else value
            Text(displayValue,
                fontSize = when {
                    displayValue.startsWith("Php", ignoreCase = true) -> 17.sp
                    displayValue.length >= 9 -> 19.sp
                    displayValue.length >= 7 -> 22.sp
                    else -> 27.sp
                },
                fontWeight = FontWeight.Bold, color = Txt, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Clip)
        }
    }
}

@Composable
fun InfoChip(label: String, value: String, modifier: Modifier = Modifier, series: List<GSeries> = emptyList()) {
    PowerStatTile(label, value, modifier, series)
}

@Composable
fun ToggleMetric(
    labelA: String, valueA: String, labelB: String, valueB: String, modifier: Modifier = Modifier,
    series: List<GSeries> = emptyList()
) {
    PowerStatTile(labelA, valueA, modifier, series, alternate = labelB to valueB)
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
fun PowerTab(sv: Server, pw: JSONObject?, onSetup: () -> Unit) {
    if (pw == null) {
        Text("This server does not report power yet. Update the server program.", color = Amber)
        PowerInsightsPanel(sv)
        return
    }
    val watts = pw.dbl("watts")
    val volts = pw.dbl("voltage")
    val today = pw.optDouble("today_kwh", 0.0)
    val month = pw.optDouble("month_kwh", 0.0)
    val price = pw.optDouble("price", 0.0)
    val cur = pw.str("currency")
    val year = pw.dbl("year_kwh")
    val history by rememberHistory(sv, "24h", 30_000)
    val energy by rememberEnergy(sv)
    val todayHistory = history?.s?.get("power") ?: emptyList()
    val voltageHistory = history?.s?.get("voltage")?.takeIf { values -> values.any { it != null } } ?: todayHistory
    val todayDate = java.time.LocalDate.now()
    val monthPrefix = todayDate.toString().take(7)
    val monthEnergy = energy?.filterKeys { it.startsWith(monthPrefix) }?.toSortedMap()?.values?.toList()?.map { it as Double? } ?: emptyList()
    val yearEnergy = energy?.entries?.groupBy { it.key.take(7) }?.toSortedMap()?.values?.map { entries -> entries.sumOf { it.value } }?.map { it as Double? } ?: emptyList()
    fun cost(k: Double) = if (price > 0) cur + "%.2f".format(k * price) else "Set price"
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line, shape)) {
        // Keep the energy hero's ambient accent restrained and slow.
        AnimatedSurfaceBackdrop(accent = Red, modifier = Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Power source")
                    Text(pw.str("source").ifEmpty { "No sensor" }, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold, color = Txt)
                }
                Status(watts != null)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (watts == null) "N/A" else animNum(watts, "%.0f"), fontSize = AppType.hero, fontWeight = FontWeight.Bold, color = Txt)
                Text(" W", fontSize = AppType.titleSmall, color = Mut, modifier = Modifier.padding(bottom = 9.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoChip("Voltage", if (volts == null) "N/A" else "%.1f V".format(volts), Modifier.weight(1f),
                    listOf(GSeries(voltageHistory, Amber)))
                ToggleMetric("Today", "%.2f kWh".format(today), "Today cost", cost(today), Modifier.weight(1f),
                    listOf(GSeries(todayHistory, Red)))
            }
            TextButton(onSetup, contentPadding = PaddingValues(0.dp)) { Text("Configure power source ›", color = Red, fontSize = AppType.body) }
            val perr = pw.str("plug_error")
            if (perr.isNotEmpty()) Text("Plug: $perr", fontSize = AppType.small, color = Amber)
            else if (watts == null) Text("No power reading found. Tap Configure to pick your plug.", fontSize = AppType.small, color = Dim)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToggleMetric("Current Month Usage", "%.2f kWh".format(month), "Current Month Cost", cost(month), Modifier.weight(1f),
            listOf(GSeries(monthEnergy, Blue)))
        ToggleMetric("Current Year Usage", year?.let { "%.2f kWh".format(it) } ?: "Update server",
            "Current Year Cost", year?.let { cost(it) } ?: "Update server", Modifier.weight(1f),
            listOf(GSeries(yearEnergy, Green)))
    }
    EnergyPanel(sv, pw)
    PowerInsightsPanel(sv)
}

@Composable
fun Speed3(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = AppType.small, color = Mut)
        Text(value, fontSize = AppType.display, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1)
        Text(unit, fontSize = AppType.caption, color = Dim)
    }
}

@Composable
fun InternetPanel(j: JSONObject, sv: Server) {
    val scope = rememberCoroutineScope()
    val sp = j.optJSONObject("speed") ?: JSONObject()
    val history by rememberHistory(sv, "1h", 10_000)
    val downHistory = (history?.s?.get("down") ?: emptyList()).map { it?.times(1e-6) }
    val upHistory = (history?.s?.get("up") ?: emptyList()).map { it?.times(1e-6) }
    fun num(k: String): String = sp.dbl(k)?.let { "%.0f".format(it) } ?: "–"
    val ping = sp.dbl("ping_ms") ?: j.dbl("ping_ms")
    val running = sp.optBoolean("running", false)
    val tileShape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().clip(tileShape).background(Surf).border(1.dp, Line, tileShape)) {
        HistoryGraph(
            listOf(GSeries(downHistory, Blue), GSeries(upHistory, Amber, true)),
            Modifier.matchParentSize(), minSpan = 0.2, lineAlpha = 0.72f, fillAlpha = 0.18f, stroke = 2.dp
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Surf.copy(alpha = 0.93f), 0.82f to Color.Transparent)))
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Label("Internet · Speedtest by Ookla")
                Text("SERVER · SMART", color = Mut, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            }
            Text(if (running) when (sp.str("phase")) { "ping" -> "Testing latency"; "download" -> "Testing download"; "upload" -> "Testing upload"; else -> "Testing" } else "SPEEDTEST",
                fontSize = AppType.caption, color = if (running) Red else Dim, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { httpJson(sv.url, sv.token, "/api/speedtest", JSONObject()) } }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .border(1.dp, Line, RoundedCornerShape(15.dp)),
                enabled = !running,
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surf2, contentColor = Txt, disabledContainerColor = Surf2, disabledContentColor = Dim),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(if (running) "Testing…" else "Run speedtest", maxLines = 1, fontWeight = FontWeight.Bold, color = if (running) Mut else Txt)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (running) { Spacer(Modifier.height(8.dp)); Bar((sp.dbl("progress") ?: 0.0).toFloat(), 4.dp) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Speed3("Download", num("down_mbps"), "Mbps", Modifier.weight(1f))
            Speed3("Upload", num("up_mbps"), "Mbps", Modifier.weight(1f))
            Speed3("Ping", ping?.let { "%.0f".format(it) } ?: "–", "ms", Modifier.weight(1f))
        }
        val srv = sp.str("server")
        if (srv.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("Last result · $srv", fontSize = AppType.caption, color = Dim) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE BANDWIDTH · LAST HOUR", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            Text("↓ Download   ↑ Upload", color = Mut, fontSize = AppType.caption)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Blue)); Spacer(Modifier.width(5.dp))
                Text("Download", color = Blue, fontSize = AppType.caption)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Amber)); Spacer(Modifier.width(5.dp))
                Text("Upload", color = Amber, fontSize = AppType.caption)
            }
        }
        Text("Live network throughput recorded by the server, not speed-test samples.", color = Dim, fontSize = AppType.micro)
        }
    }
}

@Composable
fun DiskPie(d: JSONArray) {
    val entries = List(d.length()) { d.getJSONObject(it) }
    // One display item per physical disk. A disk can contain several mounted
    // partitions/volumes, but those are combined into a single capacity figure.
    val groups = entries.groupBy { o ->
        o.optString("disk_id").ifBlank {
            o.optString("disk").ifBlank { o.optString("device", "Unknown device") }
        }
    }
    data class DiskSummary(
        val key: String,
        val name: String,
        val connection: String,
        val external: Boolean,
        val mounted: Boolean,
        val usedGb: Double,
        val totalGb: Double,
        val freeGb: Double
    )
    val disks = groups.map { (key, rawEntries) ->
        // Mountinfo can expose the same filesystem through more than one mount
        // point. Count each block partition only once when rolling up a disk.
        val parts = rawEntries.distinctBy {
            it.optString("id").ifBlank { it.optString("device") + "|" + it.optString("mount") }
        }
        val mountedParts = parts.filter { it.optBoolean("mounted", true) }
        val first = parts.first()
        val totalGb = mountedParts.sumOf { it.optDouble("total_gb", 0.0).coerceAtLeast(0.0) }
        val usedGb = mountedParts.sumOf { it.optDouble("used_gb", 0.0).coerceAtLeast(0.0) }
        val freeGb = mountedParts.sumOf {
            it.optDouble("free_gb", (it.optDouble("total_gb", 0.0) - it.optDouble("used_gb", 0.0)).coerceAtLeast(0.0)).coerceAtLeast(0.0)
        }
        val connection = parts.firstNotNullOfOrNull { it.optString("connection").takeIf(String::isNotBlank) } ?: "Storage device"
        DiskSummary(
            key = key,
            name = first.optString("model").ifBlank { first.optString("disk").ifBlank { key } },
            connection = connection,
            external = parts.any { it.optBoolean("removable", false) || it.optString("connection") == "USB" },
            mounted = mountedParts.isNotEmpty(),
            usedGb = usedGb,
            totalGb = totalGb,
            freeGb = freeGb
        )
    }.sortedBy { it.name.lowercase() }

    val mountedDisks = disks.filter { it.mounted }
    val total = mountedDisks.sumOf { it.totalGb }
    val used = mountedDisks.sumOf { it.usedGb }
    val free = mountedDisks.sumOf { it.freeGb }
    val pct = if (total > 0.0) (used / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    val cols = listOf(Red, Color(0xFFF5F5F1), Color(0xFFB20710), Color(0xFF8C8C8C), Color(0xFFFF6B6B), Color(0xFF5E5E5E))

    if (disks.isEmpty()) {
        Text("No physical storage detected", color = Mut, fontSize = AppType.body)
        Spacer(Modifier.height(5.dp))
        Text("Connect and mount the drive on Linux. Allerion will list each physical disk automatically after the next refresh.",
            color = Dim, fontSize = AppType.small)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(color = Track, startAngle = -90f, sweepAngle = 360f, useCenter = true, topLeft = Offset.Zero, size = size)
                drawArc(color = Red, startAngle = -90f, sweepAngle = (pct / 100.0 * 360.0).toFloat(),
                    useCenter = true, topLeft = Offset.Zero, size = size)
                drawCircle(Surf, radius = size.minDimension * 0.27f, center = center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.0f%%".format(pct), fontSize = AppType.title, fontWeight = FontWeight.Bold, color = Txt)
                Text("in use", fontSize = AppType.caption, color = Dim)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${disks.size} physical disk${if (disks.size == 1) "" else "s"}",
                color = Txt, fontSize = AppType.body, fontWeight = FontWeight.SemiBold)
            if (mountedDisks.isEmpty()) {
                Text("No mounted storage", color = Amber, fontSize = AppType.body)
                Text("Detected disks are listed below", color = Mut, fontSize = AppType.small)
            } else {
                Text("%.1f GB used".format(used), color = Txt, fontSize = AppType.body)
                Text("%.1f GB mounted capacity".format(total), color = Mut, fontSize = AppType.small)
                Text("%.1f GB available".format(free), color = Mut, fontSize = AppType.small)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    disks.forEachIndexed { index, disk ->
        val usage = if (disk.totalGb > 0.0) (disk.usedGb / disk.totalGb * 100.0).coerceIn(0.0, 100.0) else 0.0
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(cols[index % cols.size]))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(disk.name, color = Txt, fontSize = AppType.body, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !disk.mounted -> "Detected · Not mounted"
                            disk.external -> "External drive · USB"
                            else -> if (disk.connection.equals("Internal", ignoreCase = true)) "Internal" else "Internal · ${disk.connection}"
                        },
                        color = Mut, fontSize = AppType.caption
                    )
                }
                Text(
                    if (disk.mounted) "%.1f GB".format(disk.totalGb) else "Detected",
                    color = Mut, fontSize = AppType.small, fontWeight = FontWeight.SemiBold
                )
            }
            if (disk.mounted) {
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Track)) {
                    Box(Modifier.fillMaxWidth((usage / 100.0).toFloat()).fillMaxHeight()
                        .clip(CircleShape).background(if (usage >= 92.0) Err else Red))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("%.1f GB used".format(disk.usedGb), color = Txt, fontSize = AppType.small)
                    Text("%.1f GB free".format(disk.freeGb), color = Mut, fontSize = AppType.small)
                }
            } else {
                Text("Mount this drive on Linux to track used and free space",
                    color = Dim, fontSize = AppType.caption)
            }
        }
        if (index != disks.lastIndex) Spacer(Modifier.height(15.dp))
    }
}

@Composable
fun SystemTab(j: JSONObject) {
    Panel {
        Label("Storage")
        Spacer(Modifier.height(14.dp))
        DiskPie(j.optJSONArray("disks") ?: JSONArray())
    }
    val procs = j.optJSONArray("procs") ?: JSONArray()
    var applicationsExpanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { applicationsExpanded = !applicationsExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("Applications")
            Spacer(Modifier.weight(1f))
            Text(if (applicationsExpanded) "−" else "+", color = Mut, fontSize = AppType.title, fontWeight = FontWeight.Medium)
        }
        if (applicationsExpanded) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Name", color = Dim, modifier = Modifier.weight(2f), fontSize = AppType.small)
            Text("CPU", color = Dim, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, fontSize = AppType.small)
            Text("Memory", color = Dim, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End, fontSize = AppType.small)
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
}
