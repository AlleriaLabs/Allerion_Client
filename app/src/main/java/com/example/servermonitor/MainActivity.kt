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

val Bg = Color(0xFF101113)
val Line = Color(0xFF303136)
val Red = Color(0xFFE50914)
val DeepRed = Color(0xFF9B0710)
val Txt = Color(0xFFF5F5F5)
val Mut = Color(0xFF929398)
val Track = Color(0xFF2A2B30)
val Dim = Color(0xFF676970)
val Amber = Color(0xFFFFB547)
val Green = Color(0xFF35C98A)

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
            MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Bg, surface = Bg, onSurface = Txt, outline = Color(0xFF3A3B41), onSurfaceVariant = Mut, surfaceVariant = Surf)) {
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
            Text("Server settings need attention", color = Txt, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("ALLERION could not unlock or save its encrypted server settings. Your saved credentials have not been replaced. Do not clear app data or reinstall until the Android Keystore issue is resolved.",
                color = Mut, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text(storageError ?: "Secure storage error", color = Amber, fontSize = 12.sp)
        }
        return
    }
    val e = edit
    val o = open
    BackHandler(enabled = e != null || o != null || hubSettings) { if (e != null) edit = null else if (o != null) open = null else hubSettings = false }
    when {
        e != null -> EditServer(servers.getOrNull(e),
            onSave = { sv -> commit(if (e < 0) servers + sv else servers.toMutableList().also { it[e] = sv }); edit = null },
            onDelete = { commit(servers.filterIndexed { i, _ -> i != e }); edit = null; open = null; expanded = null },
            onCancel = { edit = null })
        o != null && o in servers.indices -> Dashboard(servers[o], { open = null }, { edit = o })
        hubSettings -> HubSettings(onRestore = { restored -> commit(restored) }, onBack = { hubSettings = false })
        else -> Home(servers, expanded, { expanded = it }, { open = it }, { edit = it },
            { i -> commit(servers.filterIndexed { k, _ -> k != i }); expanded = null }, { edit = -1 }, { hubSettings = true })
    }
    availableUpdate?.let { update ->
        AllerionUpdateDialog(update) {
            UpdateChecker.dismiss(appContext, update.tag)
            availableUpdate = null
        }
    }
}

val Surf = Color(0xFF1B1C20)
val Surf2 = Color(0xFF24252A)

fun JSONObject.str(k: String): String = if (!has(k) || isNull(k)) "" else optString(k)
fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.then(modifier).fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        AnimatedSurfaceBackdrop(Red, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
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
        PulseDot(if (online) Green else Err, online)
        Spacer(Modifier.width(4.dp))
        Text(if (online) "Online" else "Offline", fontSize = 12.sp, color = Txt)
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
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.height(124.dp).clip(shape).background(Surf).border(1.dp, Line, shape)
        .then(if (alternate != null) Modifier.clickable { showAlternate = !showAlternate } else Modifier)) {
        if (series.any { s -> s.values.any { it != null } }) {
            HistoryGraph(series, Modifier.matchParentSize(), minSpan, lineAlpha = 0.48f, fillAlpha = 0.10f, stroke = 1.6.dp)
        }
        Box(Modifier.matchParentSize().background(
            Brush.horizontalGradient(0f to Surf.copy(alpha = 0.78f), 0.76f to Surf.copy(alpha = 0.42f), 1f to Color.Transparent)
        ))
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text((if (showAlternate) alternate?.first else label)?.uppercase().orEmpty(),
                fontSize = 12.sp, color = Mut, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            val displayValue = if (showAlternate) alternate?.second ?: value else value
            Text(displayValue,
                fontSize = if (displayValue.length >= 8) 24.sp else 30.sp,
                fontWeight = FontWeight.Bold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (pw == null) { Text("This server does not report power yet. Update the server program.", color = Amber); return }
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
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Surf).border(1.dp, Line, shape)) {
        // Match the Energy section's subtle red ambient accent without restoring its live graph.
        AnimatedSurfaceBackdrop(accent = Red, modifier = Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
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
                InfoChip("Voltage", if (volts == null) "N/A" else "%.1f V".format(volts), Modifier.weight(1f),
                    listOf(GSeries(voltageHistory, Amber)))
                ToggleMetric("Today", "%.2f kWh".format(today), "Today cost", cost(today), Modifier.weight(1f),
                    listOf(GSeries(todayHistory, Red)))
            }
            TextButton(onSetup, contentPadding = PaddingValues(0.dp)) { Text("Configure power source ›", color = Red, fontSize = 13.sp) }
            val perr = pw.str("plug_error")
            if (perr.isNotEmpty()) Text("Plug: $perr", fontSize = 12.sp, color = Amber)
            else if (watts == null) Text("No power reading found. Tap Configure to pick your plug.", fontSize = 12.sp, color = Dim)
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
                Text("SERVER · SMART", color = Mut, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (running) when (sp.str("phase")) { "ping" -> "Testing latency"; "download" -> "Testing download"; "upload" -> "Testing upload"; else -> "Testing" } else "SPEEDTEST",
                fontSize = 10.sp, color = if (running) Red else Dim, fontWeight = FontWeight.Bold)
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
                Text(if (running) "Testing…" else "Run speedtest", maxLines = 1, fontWeight = FontWeight.SemiBold, color = if (running) Mut else Txt)
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
        if (srv.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("Last result · $srv", fontSize = 11.sp, color = Dim) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE BANDWIDTH · LAST HOUR", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("↓ Download   ↑ Upload", color = Mut, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Blue)); Spacer(Modifier.width(5.dp))
                Text("Download", color = Blue, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Amber)); Spacer(Modifier.width(5.dp))
                Text("Upload", color = Amber, fontSize = 10.sp)
            }
        }
        Text("Live network throughput recorded by the server, not speed-test samples.", color = Dim, fontSize = 9.sp)
        }
    }
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
    var applicationsExpanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { applicationsExpanded = !applicationsExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("Applications")
            Spacer(Modifier.weight(1f))
            Text(if (applicationsExpanded) "−" else "+", color = Mut, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        if (applicationsExpanded) {
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
}
