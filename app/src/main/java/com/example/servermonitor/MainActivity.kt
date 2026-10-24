package com.example.servermonitor

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(prefs) }
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
    Column(Modifier.padding(24.dp).systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Connect to your server", fontSize = 22.sp)
        Text("Enter the address of the monitor, for example 192.168.1.50:8080")
        OutlinedTextField(u, { u = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(t, { t = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(u, t) }, enabled = u.isNotBlank()) { Text("Save and connect") }
    }
}

fun level(v: Double, warn: Double, bad: Double) =
    if (v >= bad) Color(0xFFEF7A6B) else if (v >= warn) Color(0xFFE8B04A) else Color(0xFF3FB8AA)

@Composable
fun Dashboard(url: String, token: String, onSettings: () -> Unit) {
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val cpu = remember { mutableStateListOf<Float>() }
    val ram = remember { mutableStateListOf<Float>() }
    val tmp = remember { mutableStateListOf<Float>() }
    val dn = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    fun add(l: MutableList<Float>, v: Double) { l.add(v.toFloat()); if (l.size > 60) l.removeAt(0) }

    LaunchedEffect(url, token) {
        while (true) {
            try {
                val j = withContext(Dispatchers.IO) { fetch(url, token) }
                if (j.has("cpu")) {
                    s = j; err = null
                    add(cpu, j.getDouble("cpu")); add(ram, j.getDouble("ram"))
                    add(tmp, if (j.isNull("temp")) 0.0 else j.getDouble("temp"))
                    add(dn, j.getDouble("net_down_bps") / 1e6); add(up, j.getDouble("net_up_bps") / 1e6)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { err = e.message ?: "Cannot reach the server" }
            delay(2000)
        }
    }

    Column(Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s?.optString("host") ?: "Server", fontSize = 22.sp)
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        err?.let { Text(it, color = Color(0xFFEF7A6B)) }
        val j = s ?: return@Column
        val up_s = j.getLong("uptime_s")
        Text("Up ${up_s / 86400}d ${up_s % 86400 / 3600}h ${up_s % 3600 / 60}m")
        val c = j.getDouble("cpu"); val r = j.getDouble("ram")
        Metric("Processor", "%.0f%%".format(c), "${j.getInt("cores")} cores", level(c, 70.0, 90.0), cpu, 100f)
        Metric("Memory", "%.0f%%".format(r), "${j.getDouble("ram_used_gb")} of ${j.getDouble("ram_total_gb")} GB", level(r, 75.0, 90.0), ram, 100f)
        if (j.isNull("temp")) Metric("Temperature", "n/a", "No sensor found", Color.Gray, tmp, 100f)
        else { val t = j.getDouble("temp"); Metric("Temperature", "%.0f °C".format(t), "", level(t, 70.0, 85.0), tmp, 100f) }
        Metric("Download now", "%.1f Mbps".format(j.getDouble("net_down_bps") / 1e6), "", Color(0xFF3FB8AA), dn, null)
        Metric("Upload now", "%.1f Mbps".format(j.getDouble("net_up_bps") / 1e6), "", Color(0xFF3FB8AA), up, null)
        val sp = j.getJSONObject("speed")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Internet")
                Text(if (sp.isNull("down_mbps")) "Speed test running…" else "${sp.getDouble("down_mbps")} Mbps download", fontSize = 24.sp)
                Text(if (j.isNull("ping_ms")) "Offline" else "${j.getDouble("ping_ms")} ms latency")
            }
        }
        val d = j.getJSONArray("disks")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Storage")
                for (i in 0 until d.length()) {
                    val o = d.getJSONObject(i)
                    Text("${o.getString("mount")}: ${o.getDouble("used_gb")} / ${o.getDouble("total_gb")} GB")
                    LinearProgressIndicator(progress = (o.getDouble("pct") / 100).toFloat(),
                        color = level(o.getDouble("pct"), 80.0, 92.0), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun Metric(title: String, value: String, note: String, color: Color, hist: List<Float>, max: Float?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title); Text(note) }
            Text(value, fontSize = 36.sp, color = color)
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                if (hist.size < 2) return@Canvas
                val m = max ?: maxOf(1f, hist.max())
                val path = Path()
                hist.forEachIndexed { i, v ->
                    val x = size.width * (60 - hist.size + i) / 59f
                    val y = size.height - (v / m).coerceIn(0f, 1f) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
        }
    }
}
