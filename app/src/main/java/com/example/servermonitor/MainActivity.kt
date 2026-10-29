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
val Line = Color(0xFF2E2E2E)
val Red = Color(0xFFE50914)
val DeepRed = Color(0xFF8E0612)
val Txt = Color(0xFFFFFFFF)
val Mut = Color(0xFFB3B3B3)
val Track = Color(0xFF333333)
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

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.then(modifier).fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF262626), Color(0xFF191919))))
            .border(1.dp, Line, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content)
}

@Composable
fun Label(text: String) = Text(text, fontSize = 14.sp, color = Mut, fontWeight = FontWeight.Medium)

@Composable
fun Bar(frac: Float, h: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h)).background(Track)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(DeepRed, Red))))
    }
}

@Composable
fun Ring(title: String, pct: Double?, center: String) {
    val sweep by animateFloatAsState(((pct ?: 0.0) / 100 * 360).toFloat().coerceIn(0f, 360f), tween(700), label = "ring")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Label(title)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 10.dp.toPx(); val g = 16.dp.toPx(); val inset = g / 2
                val sz = Size(size.width - g, size.height - g)
                drawArc(Track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (sweep > 0f) {
                    drawArc(Red.copy(alpha = 0.22f), -90f, sweep, false, Offset(inset, inset), sz, style = Stroke(g, cap = StrokeCap.Round))
                    drawArc(Red, -90f, sweep, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            val multi = center.contains('\n')
            Text(center, color = if (multi) Mut else Txt, fontSize = if (multi) 11.sp else 18.sp,
                fontWeight = if (multi) FontWeight.Normal else FontWeight.SemiBold,
                lineHeight = if (multi) 14.sp else 22.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Status(online: Boolean) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF262626)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Green else Red))
        Spacer(Modifier.width(6.dp))
        Text(if (online) "Online" else "Offline", fontSize = 12.sp, color = Txt)
    }
}

@Composable
fun Banner() {
    Box(Modifier.fillMaxWidth()) {
        Image(painterResource(R.drawable.banner), "Alleria Home Server Monitoring",
            Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Bg))))
    }
}

@Composable
fun Home(servers: List<Server>, onOpen: (Int) -> Unit, onEdit: (Int) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
        Banner()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your servers", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Txt)
            if (servers.isEmpty()) Text("No servers yet. Add the address of the monitor running on your server.", color = Mut)
            servers.forEachIndexed { i, sv -> ServerCard(sv, { onOpen(i) }, { onEdit(i) }) }
            Button(onAdd, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
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
            val t = if (j.isNull("temp")) null else j.getDouble("temp")
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
        Text(if (sv == null) "Add server" else "Edit server", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Txt)
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

@Composable
fun PowerPanel(pw: JSONObject) {
    val watts = if (pw.isNull("watts")) null else pw.getDouble("watts")
    val today = pw.optDouble("today_kwh", 0.0)
    val price = pw.optDouble("price", 0.0)
    val cur = pw.optString("currency", "")
    val hist = pw.optJSONArray("history") ?: JSONArray()
    var maxK = 0.001
    for (i in 0 until hist.length()) maxK = maxOf(maxK, hist.getJSONObject(i).getDouble("kwh"))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label("Power")
                Text(if (watts == null) "N/A" else "%.0f W".format(watts), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                Text(pw.optString("source", "").takeIf { it != "null" && it.isNotEmpty() } ?: "No sensor", fontSize = 12.sp, color = Dim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("Today")
                Text("%.2f kWh".format(today), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                if (price > 0) Text("$cur%.2f".format(today * price), fontSize = 13.sp, color = Mut)
                Text("Month %.1f kWh".format(pw.optDouble("month_kwh", 0.0)), fontSize = 12.sp, color = Dim)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for (i in 0 until hist.length()) {
                val o = hist.getJSONObject(i)
                val last = i == hist.length() - 1
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text("%.2f".format(o.getDouble("kwh")), fontSize = 9.sp, color = Dim, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().height(maxOf(3f, (o.getDouble("kwh") / maxK * 46).toFloat()).dp)
                        .clip(RoundedCornerShape(4.dp)).background(if (last) Red else DeepRed))
                    Spacer(Modifier.height(3.dp))
                    Text(o.getString("day").substring(8), fontSize = 10.sp, color = Mut)
                }
            }
        }
        if (watts == null) Text("No power reading found on this server. See the setup notes.", fontSize = 12.sp, color = Dim)
    }
}

@Composable
fun Dashboard(sv: Server, onBack: () -> Unit, onEdit: () -> Unit) {
    val r by rememberStats(sv, 2000)
    val j = r.json
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onBack) { Text("‹ Servers", color = Red, fontSize = 16.sp) }
            TextButton(onEdit) { Text("Edit", color = Red) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sv.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Txt)
                val up = j?.optLong("uptime_s") ?: 0L
                Text(if (j == null) "Connecting…" else "Up ${up / 86400}d ${up % 86400 / 3600}h  ·  ${j.optString("host")}", color = Mut, fontSize = 13.sp)
            }
            Status(j != null && r.error == null)
        }
        r.error?.let { Text(it, color = Amber) }
        if (j == null) return@Column

        val cpu = j.getDouble("cpu")
        val gpu = if (j.isNull("gpu")) null else j.optDouble("gpu")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Ring("CPU", cpu, "%.1f%%".format(cpu)) }
            Panel(Modifier.weight(1f)) { Ring("GPU", gpu, if (gpu == null) "N/A" else "%.1f%%".format(gpu)) }
        }
        val swapTotal = j.optDouble("swap_total_gb", 0.0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Ring("Memory", j.getDouble("ram"), "Used\n${j.getDouble("ram_used_gb")} GB\n${j.getDouble("ram_total_gb")} GB\nTotal") }
            Panel(Modifier.weight(1f)) { Ring("Swap", if (swapTotal > 0) j.optDouble("swap") else null,
                if (swapTotal > 0) "Used\n${j.optDouble("swap_used_gb")} GB\n$swapTotal GB\nTotal" else "None") }
        }
        val temp = if (j.isNull("temp")) null else j.getDouble("temp")
        val sp = j.getJSONObject("speed")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Ring("Temperature", temp, if (temp == null) "N/A" else "%.0f °C".format(temp)) }
            Panel(Modifier.weight(1f)) {
                Label("Internet")
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (sp.isNull("down_mbps")) "…" else "%.0f".format(sp.getDouble("down_mbps")), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                        Text("Mbps down", fontSize = 12.sp, color = Mut)
                        Spacer(Modifier.height(6.dp))
                        Text(if (j.isNull("ping_ms")) "Offline" else "${j.getDouble("ping_ms")} ms", fontSize = 13.sp,
                            color = if (j.isNull("ping_ms")) Amber else Mut)
                    }
                }
            }
        }
        j.optJSONObject("power")?.let { PowerPanel(it) }
        Panel {
            Label("Disks")
            val d = j.getJSONArray("disks")
            for (i in 0 until d.length()) {
                val o = d.getJSONObject(i)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text(o.getString("mount"), color = Txt, modifier = Modifier.weight(1f))
                    Text("${o.getDouble("used_gb")} / ${o.getDouble("total_gb")} GB", color = Mut)
                }
                Bar((o.getDouble("pct") / 100).toFloat())
            }
        }
        Panel {
            Label("Network")
            Spacer(Modifier.height(8.dp))
            Legend(Red, "Download", rate(j.getDouble("net_down_bps") / 8))
            Legend(Txt, "Upload", rate(j.getDouble("net_up_bps") / 8))
            Legend(Dim, "IPv4", if (j.isNull("ip")) "-" else j.optString("ip"))
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
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (i % 2 == 0) Color(0xFF2A2A2A) else Color.Transparent).padding(horizontal = 6.dp, vertical = 7.dp)) {
                    Text(o.getString("name"), color = Txt, modifier = Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.1f%%".format(o.getDouble("cpu")), color = Mut, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                    Text(if (mem >= 1000) "%.1f GB".format(mem / 1000.0) else "$mem MB", color = Mut, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}
