package com.example.servermonitor

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

val Bg = Color(0xFF141414)
val CardBg = Color(0xFF1F1F1F)
val Line = Color(0xFF2E2E2E)
val Red = Color(0xFFE50914)
val Txt = Color(0xFFFFFFFF)
val Mut = Color(0xFFB3B3B3)
val Track = Color(0xFF333333)
val Dim = Color(0xFF6D6D6E)
val Amber = Color(0xFFF5A524)

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

fun fetch(base: String, token: String): JSONObject {
    val c = URL("$base/api/stats").openConnection() as HttpURLConnection
    c.connectTimeout = 3000; c.readTimeout = 3000
    if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
    if (c.responseCode == 401) throw Exception("Wrong token")
    return JSONObject(c.inputStream.bufferedReader().readText())
}

fun rate(bytes: Double): String = when {
    bytes >= 1e6 -> "%.1f MB/s".format(bytes / 1e6)
    bytes >= 1e3 -> "%.1f kB/s".format(bytes / 1e3)
    else -> "%.0f B/s".format(bytes)
}

@Composable
fun App(p: SharedPreferences) {
    var url by remember { mutableStateOf(p.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(p.getString("token", "") ?: "") }
    var editing by remember { mutableStateOf(url.isEmpty()) }
    if (editing) Setup(url, token) { u, t ->
        var clean = u.trim().trimEnd('/')
        if (!clean.startsWith("http")) clean = "http://$clean"
        url = clean; token = t.trim()
        p.edit().putString("url", url).putString("token", token).apply()
        editing = false
    } else Dashboard(url, token) { editing = true }
}

@Composable
fun Setup(url0: String, token0: String, onSave: (String, String) -> Unit) {
    var u by remember { mutableStateOf(url0) }
    var t by remember { mutableStateOf(token0) }
    Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Connect to your server", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Txt)
        Text("Enter the monitor address, for example 192.168.1.50:8080", color = Mut)
        OutlinedTextField(u, { u = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(t, { t = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(u, t) }, enabled = u.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Txt)) { Text("Save and connect") }
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(CardBg).border(1.dp, Line, shape).padding(14.dp), content = content)
}

@Composable
fun Ring(title: String, pct: Double?, center: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 18.sp, color = Txt)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 11.dp.toPx()
                val sz = Size(size.width - w, size.height - w)
                drawArc(Track, 0f, 360f, false, Offset(w / 2, w / 2), sz, style = Stroke(w))
                if (pct != null) drawArc(Red, -90f, (pct / 100 * 360).toFloat().coerceIn(0f, 360f), false,
                    Offset(w / 2, w / 2), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            val multi = center.contains('\n')
            Text(center, color = if (multi) Mut else Txt, fontSize = if (multi) 11.sp else 17.sp,
                lineHeight = if (multi) 14.sp else 20.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Bar(frac: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(Red))
    }
}

@Composable
fun Legend(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Txt, modifier = Modifier.weight(1f))
        Text(value, color = Mut)
    }
}

@Composable
fun Divider() { Box(Modifier.width(1.dp).height(140.dp).background(Line)) }

@Composable
fun Dashboard(url: String, token: String, onSettings: () -> Unit) {
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url, token) {
        while (true) {
            try {
                val j = withContext(Dispatchers.IO) { fetch(url, token) }
                if (j.has("cpu")) { s = j; err = null }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { err = e.message ?: "Cannot reach the server" }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val j = s
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Overview", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Txt)
                val up = j?.optLong("uptime_s") ?: 0L
                Text(if (j == null) "Connecting…" else "${j.optString("host")}  ·  up ${up / 86400}d ${up % 86400 / 3600}h", color = Mut, fontSize = 13.sp)
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Red) }
        }
        err?.let { Text(it, color = Red) }
        if (j == null) return@Column

        val cpu = j.getDouble("cpu")
        val gpu = if (j.isNull("gpu")) null else j.optDouble("gpu")
        Panel { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Ring("CPU", cpu, "%.1f%%".format(cpu), Modifier.weight(1f)); Divider()
            Ring("GPU", gpu, if (gpu == null) "N/A" else "%.1f%%".format(gpu), Modifier.weight(1f)) } }

        val swapTotal = j.optDouble("swap_total_gb", 0.0)
        Panel { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Ring("Memory", j.getDouble("ram"), "Used\n${j.getDouble("ram_used_gb")} GB\n${j.getDouble("ram_total_gb")} GB\nTotal", Modifier.weight(1f)); Divider()
            Ring("Swap", if (swapTotal > 0) j.optDouble("swap") else null,
                if (swapTotal > 0) "Used\n${j.optDouble("swap_used_gb")} GB\n$swapTotal GB\nTotal" else "None", Modifier.weight(1f)) } }

        val temp = if (j.isNull("temp")) null else j.getDouble("temp")
        val sp = j.getJSONObject("speed")
        Panel { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Ring("Temp", temp, if (temp == null) "N/A" else "%.0f °C".format(temp), Modifier.weight(1f)); Divider()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Internet", fontSize = 18.sp, color = Txt)
                Spacer(Modifier.height(14.dp))
                Text(if (sp.isNull("down_mbps")) "Testing…" else "${sp.getDouble("down_mbps")} Mbps", fontSize = 20.sp, color = Txt)
                Text(if (j.isNull("ping_ms")) "Offline" else "${j.getDouble("ping_ms")} ms latency",
                    color = if (j.isNull("ping_ms")) Amber else Mut, fontSize = 13.sp)
            } } }

        Panel {
            Text("Disks", fontSize = 18.sp, color = Txt, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            val d = j.getJSONArray("disks")
            for (i in 0 until d.length()) {
                val o = d.getJSONObject(i)
                Spacer(Modifier.height(12.dp))
                Bar((o.getDouble("pct") / 100).toFloat())
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(o.getString("mount"), color = Mut, modifier = Modifier.weight(1f))
                    Text("${o.getDouble("used_gb")} / ${o.getDouble("total_gb")} GB", color = Mut)
                }
            }
        }

        Panel {
            Text("Networks", fontSize = 18.sp, color = Txt, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Legend(Red, "Download", rate(j.getDouble("net_down_bps") / 8))
            Legend(Txt, "Upload", rate(j.getDouble("net_up_bps") / 8))
            Legend(Dim, "IPv4", if (j.isNull("ip")) "-" else j.optString("ip"))
        }

        val procs = j.optJSONArray("procs") ?: JSONArray()
        Panel {
            Text("Applications", fontSize = 18.sp, color = Txt)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Name", color = Mut, modifier = Modifier.weight(2f), fontSize = 12.sp)
                Text("CPU", color = Mut, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End, fontSize = 12.sp)
                Text("Memory", color = Mut, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End, fontSize = 12.sp)
            }
            for (i in 0 until procs.length()) {
                val o = procs.getJSONObject(i)
                val mem = o.getInt("mem_mb")
                Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) Color(0xFF262626) else Color.Transparent).padding(horizontal = 4.dp, vertical = 7.dp)) {
                    Text(o.getString("name"), color = Txt, modifier = Modifier.weight(2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.1f%%".format(o.getDouble("cpu")), color = Mut, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                    Text(if (mem >= 1000) "%.1f GB".format(mem / 1000.0) else "$mem MB", color = Mut, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}
