package com.example.servermonitor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import org.json.JSONObject

val Err = Color(0xFFF04452)
val Blue = Color(0xFF648BFF)
val GreenLight = Color(0xFF9BE5C4)

// Shared type scale tuned for compact mobile cards and readable supporting text.
object AppType {
    val micro = 10.sp
    val caption = 11.sp
    val small = 12.sp
    val body = 14.sp
    val bodyStrong = 16.sp
    val titleSmall = 18.sp
    val title = 20.sp
    val section = 24.sp
    val displaySmall = 28.sp
    val display = 30.sp
    val hero = 42.sp
    val heroLarge = 48.sp
}

val LocalReduceMotion = compositionLocalOf { false }

object AppSettings { var refreshMs by mutableLongStateOf(2000L) }

// ---------------------------------------------------------------- icons (one consistent 24-grid, round stroke set)
enum class Ic { Dash, Cpu, Memory, Storage, Thermo, Activity, Download, Upload, Zap, Plug, Heart, Server, Globe, Clock, Bell, Gear, Refresh, Chart, ChevronL, ChevronD, Plus, Pencil, Trash }

@Composable
fun Icon24(kind: Ic, tint: Color, sz: Dp = 20.dp, modifier: Modifier = Modifier, desc: String? = null) {
    val m = if (desc != null) modifier.semantics { contentDescription = desc } else modifier
    Canvas(m.size(sz)) {
        val u = this.size.width / 24f
        val sw = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(tint, p(a, b), p(c, d), 1.8f * u, StrokeCap.Round)
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float = 2.5f) =
            drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = sw)
        fun poly(vararg v: Float, close: Boolean = false) {
            val pa = Path(); pa.moveTo(v[0] * u, v[1] * u)
            var i = 2; while (i < v.size) { pa.lineTo(v[i] * u, v[i + 1] * u); i += 2 }
            if (close) pa.close(); drawPath(pa, tint, style = sw)
        }
        fun dot(x: Float, y: Float) = drawCircle(tint, 1.1f * u, p(x, y))
        when (kind) {
            Ic.Dash -> { rr(3f, 3f, 7f, 9f); rr(14f, 3f, 7f, 5f); rr(14f, 12f, 7f, 9f); rr(3f, 16f, 7f, 5f) }
            Ic.Cpu -> { rr(6f, 6f, 12f, 12f); rr(9.5f, 9.5f, 5f, 5f, 1f)
                for (x in listOf(9f, 15f)) { line(x, 2f, x, 6f); line(x, 18f, x, 22f) }
                for (y in listOf(9f, 15f)) { line(2f, y, 6f, y); line(18f, y, 22f, y) } }
            Ic.Memory -> { rr(2f, 7f, 20f, 10f); for (x in listOf(7f, 12f, 17f)) line(x, 11f, x, 13f)
                for (x in listOf(6f, 10f, 14f, 18f)) line(x, 17f, x, 20f) }
            Ic.Storage -> { rr(3f, 5f, 18f, 14f, 3f); line(3f, 14f, 21f, 14f); dot(7.5f, 16.7f) }
            Ic.Thermo -> { rr(10f, 3f, 4f, 13f, 2f); drawCircle(tint, 3.6f * u, p(12f, 17.4f), style = sw); line(12f, 9f, 12f, 16f) }
            Ic.Activity -> poly(2f, 12f, 6f, 12f, 9f, 4f, 15f, 20f, 18f, 12f, 22f, 12f)
            Ic.Download -> { line(12f, 3f, 12f, 15f); poly(7f, 10f, 12f, 15f, 17f, 10f); line(4f, 20f, 20f, 20f) }
            Ic.Upload -> { line(12f, 16f, 12f, 4f); poly(7f, 9f, 12f, 4f, 17f, 9f); line(4f, 20f, 20f, 20f) }
            Ic.Zap -> poly(13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 20f, 10f, 13f, 10f, close = true)
            Ic.Plug -> { line(9f, 3f, 9f, 8f); line(15f, 3f, 15f, 8f); rr(6f, 8f, 12f, 6f, 2f); line(12f, 14f, 12f, 21f) }
            Ic.Heart -> { val h = Path(); h.moveTo(12f * u, 20f * u)
                h.cubicTo(5f * u, 15f * u, 3f * u, 11f * u, 3f * u, 8.2f * u); h.cubicTo(3f * u, 5.5f * u, 5f * u, 4f * u, 7.2f * u, 4f * u)
                h.cubicTo(9.2f * u, 4f * u, 11f * u, 5.2f * u, 12f * u, 7f * u); h.cubicTo(13f * u, 5.2f * u, 14.8f * u, 4f * u, 16.8f * u, 4f * u)
                h.cubicTo(19f * u, 4f * u, 21f * u, 5.5f * u, 21f * u, 8.2f * u); h.cubicTo(21f * u, 11f * u, 19f * u, 15f * u, 12f * u, 20f * u)
                h.close(); drawPath(h, tint, style = sw) }
            Ic.Server -> { rr(3f, 4f, 18f, 7f); rr(3f, 13f, 18f, 7f); dot(7f, 7.5f); dot(7f, 16.5f) }
            Ic.Globe -> { drawCircle(tint, 9f * u, p(12f, 12f), style = sw); line(3f, 12f, 21f, 12f)
                drawOval(tint, p(8f, 3f), Size(8f * u, 18f * u), style = sw) }
            Ic.Clock -> { drawCircle(tint, 9f * u, p(12f, 12f), style = sw); poly(12f, 7f, 12f, 12f, 15.5f, 14f) }
            Ic.Bell -> { val b = Path(); b.moveTo(4f * u, 18f * u); b.lineTo(6f * u, 16f * u); b.lineTo(6f * u, 10f * u)
                b.arcTo(Rect(6f * u, 4f * u, 18f * u, 16f * u), 180f, 180f, false); b.lineTo(18f * u, 16f * u); b.lineTo(20f * u, 18f * u); b.close()
                drawPath(b, tint, style = sw); line(10f, 21f, 14f, 21f) }
            Ic.Gear -> { drawCircle(tint, 2.6f * u, p(12f, 12f), style = sw); drawCircle(tint, 6.2f * u, p(12f, 12f), style = sw)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
                    line(12f + 7.6f * c, 12f + 7.6f * s, 12f + 10f * c, 12f + 10f * s) } }
            Ic.Refresh -> { drawArc(tint, -50f, 290f, false, p(4f, 4f), Size(16f * u, 16f * u), style = sw); poly(7f, 2.5f, 7f, 6.5f, 11f, 6.5f) }
            Ic.Chart -> { poly(3f, 3f, 3f, 21f, 21f, 21f); poly(7f, 15f, 11f, 10f, 14f, 13f, 20f, 6f) }
            Ic.ChevronL -> poly(15f, 5f, 8f, 12f, 15f, 19f)
            Ic.ChevronD -> poly(5f, 9f, 12f, 16f, 19f, 9f)
            Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.Pencil -> { poly(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, close = true); line(13.5f, 6.5f, 17.5f, 10.5f) }
            Ic.Trash -> { line(4f, 7f, 20f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
        }
    }
}

// ---------------------------------------------------------------- ambient premium tile surface
@Composable
fun AnimatedSurfaceBackdrop(accent: Color = Red, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "surface-ambient")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Reverse), label = "surface-drift")
    Canvas(modifier) {
        val p = if (reduceMotion) 0.42f else phase
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Color(0xFF20232A), Color(0xFF191B20), Color(0xFF17191E)),
            start = Offset(0f, 0f), end = Offset(w, h)))
        val glowCenter = Offset(w * (0.82f - 0.12f * p), h * (0.12f + 0.12f * p))
        val glowRadius = maxOf(w, h) * 0.78f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.13f), accent.copy(alpha = 0.035f), Color.Transparent),
            center = glowCenter, radius = glowRadius), glowRadius, glowCenter)
        val coolCenter = Offset(w * (0.08f + 0.10f * p), h * 0.92f)
        val coolRadius = maxOf(w, h) * 0.62f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF648BFF).copy(alpha = 0.045f), Color.Transparent),
            center = coolCenter, radius = coolRadius), coolRadius, coolCenter)
        if (!reduceMotion) {
            val x = w * (0.12f + 0.76f * phase)
            drawLine(Color.White.copy(alpha = 0.025f), Offset(x, 0f), Offset(x - h * 0.35f, h), 1.dp.toPx())
        }
    }
}

// ---------------------------------------------------------------- motion helpers
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, tween(110), label = "press")
    return this.scale(sc).clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val rm = LocalReduceMotion.current
    var go by remember { mutableStateOf(rm) }
    LaunchedEffect(Unit) { if (!go) { delay(index * 55L); go = true } }
    val a by animateFloatAsState(if (go) 1f else 0f, if (rm) snap() else tween(360, easing = FastOutSlowInEasing), label = "in")
    return this.graphicsLayer { alpha = a; translationY = (1f - a) * 18.dp.toPx() }
}

@Composable
fun animNum(v: Double?, fmt: String = "%.0f"): String {
    val rm = LocalReduceMotion.current
    val a by animateFloatAsState((v ?: 0.0).toFloat(), if (rm) snap() else tween(500), label = "num")
    return if (v == null) "N/A" else fmt.format(a)
}

@Composable
fun PulseDot(color: Color, pulse: Boolean, dia: Dp = 8.dp) {
    val rm = LocalReduceMotion.current
    val c by animateColorAsState(color, tween(400), label = "status")
    val p by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "p")
    Box(Modifier.size(dia * 2.2f), contentAlignment = Alignment.Center) {
        if (pulse && !rm) Canvas(Modifier.fillMaxSize()) { drawCircle(c.copy(alpha = 0.35f * (1f - p)), dia.toPx() / 2 * (1f + 1.1f * p)) }
        Box(Modifier.size(dia).clip(CircleShape).background(c))
    }
}

@Composable
fun IconBtn(ic: Ic, desc: String, rotate: Float = 0f, plain: Boolean = false, onClick: () -> Unit) {
    val buttonModifier = if (plain) Modifier.size(44.dp).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    ) else Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Surf2)
        .border(1.dp, Line, RoundedCornerShape(15.dp)).clickable(onClick = onClick)
    Box(buttonModifier, contentAlignment = Alignment.Center) {
        Icon24(ic, Mut, 20.dp, Modifier.graphicsLayer { rotationZ = rotate }, desc)
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Ic.Dash to "Overview", Ic.Globe to "Network", Ic.Zap to "Power", Ic.Server to "System", Ic.Gear to "Settings")
    Column(Modifier.fillMaxWidth().background(Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.8f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp)) {
            items.forEachIndexed { i, (ic, label) ->
                val selected = tab == i
                val tint by animateColorAsState(if (selected) Red else Dim, tween(220), label = "nav-icon")
                Column(Modifier.weight(1f).tap { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(54.dp).height(32.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Red.copy(alpha = 0.13f) else Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Icon24(ic, tint, 21.dp, desc = label)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(label, fontSize = AppType.micro, color = if (selected) Txt else Dim,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- real history models + fetchers
class History(val t: List<Long>, val s: Map<String, List<Double?>>, val intervalS: Int)

fun parseHistory(j: JSONObject): History {
    val ta = j.optJSONArray("t") ?: throw ApiException("History response is missing timestamps.")
    val timestamps = List(ta.length()) { ta.optLong(it) }
    val m = HashMap<String, List<Double?>>()
    for (k in listOf("cpu", "ram", "temp", "down", "up", "power", "voltage", "gpu", "disk")) {
        val a = j.optJSONArray(k) ?: continue
        // Keep every series aligned to the timestamp axis. Missing or invalid values become gaps.
        m[k] = List(timestamps.size) { index ->
            if (index >= a.length() || a.isNull(index)) null
            else a.optDouble(index).takeUnless { it.isNaN() || it.isInfinite() }
        }
    }
    return History(timestamps, m, j.optInt("interval", 10).coerceAtLeast(1))
}

fun friendlyError(e: Exception): String = when (e) {
    is ApiException -> e.message ?: "Monitoring API request failed."
    is java.net.UnknownHostException -> "Server address not found. Check DNS or the hostname."
    is java.net.ConnectException, is java.net.NoRouteToHostException -> "Server unreachable. Check that the monitoring service is running."
    is javax.net.ssl.SSLException -> "Secure connection failed. Check the HTTPS certificate and address."
    is java.net.MalformedURLException, is IllegalArgumentException -> e.message ?: "Invalid server address."
    is org.json.JSONException -> "Monitoring service returned invalid data."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Cannot reach the server."
}

@Composable
fun rememberHistory(sv: Server, range: String, every: Long): State<History?> {
    val lc = LocalLifecycleOwner.current.lifecycle          // no polling while the app is in the background
    return produceState<History?>(null, sv, range, every) {
        var failures = 0
        while (true) {
            if (lc.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try {
                    value = parseHistory(withContext(Dispatchers.IO) {
                        httpJson(sv.url, sv.token, "/api/history?range=$range")
                    })
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures = (failures + 1).coerceAtMost(6)
                }
            }
            delay(pollDelay(every, failures))
        }
    }
}

@Composable
fun rememberEnergy(sv: Server): State<Map<String, Double>?> {
    val lc = LocalLifecycleOwner.current.lifecycle
    return produceState<Map<String, Double>?>(null, sv) {
        var failures = 0
        while (true) {
            if (lc.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try {
                    val a = withContext(Dispatchers.IO) { httpJson(sv.url, sv.token, "/api/energy") }
                        .optJSONArray("days") ?: throw ApiException("Energy response is missing daily readings.")
                    value = (0 until a.length()).mapNotNull { index ->
                        val item = a.optJSONObject(index) ?: return@mapNotNull null
                        val day = item.optString("day")
                        val kwh = item.optDouble("kwh", Double.NaN)
                        if (day.isBlank() || kwh.isNaN() || kwh.isInfinite()) null else day to kwh
                    }.toMap()
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures = (failures + 1).coerceAtMost(6)
                }
            }
            delay(pollDelay(60_000L, failures))
        }
    }
}

fun fmtT(t: Long, range: String): String =
    java.text.SimpleDateFormat(if (range == "7d") "d MMM" else "HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t * 1000))

// ---------------------------------------------------------------- full-bleed history graph
class GSeries(val values: List<Double?>, val color: Color, val dashed: Boolean = false)

fun morphAt(a: List<List<Double?>>, b: List<List<Double?>>, t: Float): List<List<Double?>> = b.mapIndexed { i, tb ->
    val ta = a.getOrNull(i)
    if (ta == null || t >= 1f || ta.isEmpty()) tb
    else { val shift = tb.size - ta.size
        tb.mapIndexed { k, vb -> val va = ta.getOrNull(k - shift); if (va == null || vb == null) vb else va + (vb - va) * t } }
}

@Composable
fun rememberMorph(target: List<List<Double?>>): List<List<Double?>> {
    val rm = LocalReduceMotion.current
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = morphAt(from, to, p.value); to = target
        if (rm) p.snapTo(1f) else { p.snapTo(0f); p.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    }
    return morphAt(from, to, p.value)
}

@Composable
fun HistoryGraph(series: List<GSeries>, modifier: Modifier = Modifier, minSpan: Double = 10.0,
                 lineAlpha: Float = 0.75f, fillAlpha: Float = 0.18f, stroke: Dp = 1.6.dp) {
    val rm = LocalReduceMotion.current
    val shown = rememberMorph(series.map { it.values })
    val hasData = shown.any { l -> l.any { it != null } }
    val intro = remember { Animatable(if (rm) 1f else 0f) }
    LaunchedEffect(hasData) { if (hasData && intro.value < 1f) intro.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val all = shown.flatMap { it }.filterNotNull()
        if (all.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        var lo = all.min(); var hi = all.max()
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2 }
        val pad = (hi - lo) * 0.14; lo -= pad; hi += pad
        val n = shown.maxOf { it.size }
        fun x(i: Int) = if (n <= 1) w else w * i / (n - 1f)
        fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
        clipRect(right = w * intro.value) {
            shown.forEachIndexed { si, vals ->
                val color = series[si].color
                val segs = mutableListOf<List<Offset>>(); var cur = mutableListOf<Offset>()
                vals.forEachIndexed { i, v ->
                    if (v == null) { if (cur.isNotEmpty()) { segs.add(cur); cur = mutableListOf() } } else cur.add(Offset(x(i), y(v)))
                }
                if (cur.isNotEmpty()) segs.add(cur)
                segs.forEach { pts ->
                    if (pts.size < 2) { drawCircle(color.copy(alpha = lineAlpha), 3.dp.toPx(), pts[0]); return@forEach }
                    fun curve(pa: Path) { for (k in 1 until pts.size) { val a = pts[k - 1]; val b = pts[k]; val mx = (a.x + b.x) / 2
                        pa.cubicTo(mx, a.y, mx, b.y, b.x, b.y) } }
                    val line = Path().apply { moveTo(pts[0].x, pts[0].y); curve(this) }
                    if (si == 0) {
                        val area = Path().apply { moveTo(pts[0].x, h); lineTo(pts[0].x, pts[0].y); curve(this); lineTo(pts.last().x, h); close() }
                        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = fillAlpha), Color.Transparent)))
                    }
                    drawPath(line, color.copy(alpha = lineAlpha), style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (series[si].dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
                }
                val last = vals.lastIndex
                if (si == 0 && last >= 0 && vals[last] != null) drawCircle(color, 3.2.dp.toPx(), Offset(x(last), y(vals[last]!!)))
            }
        }
    }
}

// ---------------------------------------------------------------- metric tile: graph is the card background
@Composable
fun MetricTile(icon: Ic, name: String, value: String, unit: String, sub: String?, series: List<GSeries>, minSpan: Double,
               modifier: Modifier = Modifier, centerContent: Boolean = false, centerValue: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val accent = when (icon) {
        Ic.Cpu -> Red
        Ic.Memory -> Blue
        Ic.Thermo -> Amber
        Ic.Storage -> GreenLight
        Ic.Activity -> Green
        Ic.Zap -> Red
        else -> Blue
    }
    Box(modifier.tap(onClick).height(128.dp).clip(shape).background(Surf)
        .border(1.dp, Line.copy(alpha = 0.82f), shape)
        .semantics(mergeDescendants = true) { contentDescription = "$name $value $unit" }) {
        HistoryGraph(series, Modifier.matchParentSize(), minSpan, lineAlpha = 0.30f, fillAlpha = 0.07f, stroke = 1.4.dp)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Surf.copy(alpha = 0.88f), 0.68f to Surf.copy(alpha = 0.42f), 1f to Color.Transparent
        )))
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center) {
                    Icon24(icon, accent, 15.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = AppType.small, color = Mut, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.align(Alignment.CenterStart).padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, fontSize = AppType.displaySmall, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1)
                if (unit.isNotEmpty()) Text(" $unit", fontSize = AppType.body, color = Mut)
            }
            if (sub != null) {
                Text(sub, modifier = Modifier.align(Alignment.BottomStart),
                    fontSize = AppType.caption, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
