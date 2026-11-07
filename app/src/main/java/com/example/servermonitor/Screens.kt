package com.example.servermonitor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

fun fmtClock(ts: Long): String =
    if (ts <= 0) "–" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts * 1000))

fun diskPct(j: JSONObject): Double? {
    val d = j.optJSONArray("disks") ?: return null
    var u = 0.0; var t = 0.0
    for (i in 0 until d.length()) { u += d.getJSONObject(i).optDouble("used_gb", 0.0); t += d.getJSONObject(i).optDouble("total_gb", 0.0) }
    return if (t > 0) u / t * 100 else null
}

fun netFmt(bps: Double): Pair<String, String> = if (bps >= 1e6) "%.1f".format(bps / 1e6) to "Mbps" else "%.0f".format(bps / 1e3) to "kbps"

@Composable
fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Mut, fontSize = 14.sp)
        Text(value, color = Txt, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppHeader(sv: Server, j: JSONObject?, err: String?, online: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    var spin by remember { mutableStateOf(0) }
    val rot by animateFloatAsState(spin * 360f, tween(700, easing = FastOutSlowInEasing), label = "spin")
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.ChevronL, "Back to servers", onClick = onBack)
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(sv.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ts = j?.optLong("ts") ?: 0L
            Text(when { j == null -> err ?: "Connecting…"; !online -> "Offline · last seen ${fmtClock(ts)}"; else -> "ALLERION · updated ${fmtClock(ts)}" },
                fontSize = 11.sp, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Status(online)
        IconBtn(Ic.Refresh, "Refresh", rot) { spin++; onRefresh() }
        IconBtn(Ic.Gear, "Settings", onClick = onSettings)
    }
}

fun healthWarnings(j: JSONObject): List<String> {
    val w = mutableListOf<String>()
    if (j.getDouble("cpu") >= 90) w += "CPU usage is high (%.0f%%)".format(j.getDouble("cpu"))
    if (j.getDouble("ram") >= 90) w += "Memory usage is high (%.0f%%)".format(j.getDouble("ram"))
    j.dbl("temp")?.let { if (it >= 85) w += "Temperature is high (%.0f °C)".format(it) }
    val d = j.optJSONArray("disks") ?: JSONArray()
    for (i in 0 until d.length()) { val o = d.getJSONObject(i); if (o.optDouble("pct", 0.0) >= 92) w += "${o.optString("mount")} is almost full" }
    j.optJSONObject("power")?.str("plug_error")?.let { if (it.isNotEmpty()) w += "Power plug: $it" }
    return w
}

@Composable
fun HealthCard(j: JSONObject, online: Boolean, modifier: Modifier = Modifier) {
    val warns = if (online) healthWarnings(j) else emptyList()
    val color = when { !online -> Err; warns.isNotEmpty() -> Amber; else -> Green }
    val title = when { !online -> "Server offline"; warns.isNotEmpty() -> "${warns.size} warning" + (if (warns.size > 1) "s" else ""); else -> "All systems normal" }
    val up = j.optLong("uptime_s")
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon24(Ic.Heart, color, 23.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Txt)
                Text(if (online) "Monitoring connected · up ${up / 86400}d ${up % 86400 / 3600}h" else "Cannot reach the monitoring service", fontSize = 12.sp, color = Mut)
            }
            PulseDot(color, online && warns.isEmpty())
        }
        warns.forEach { Spacer(Modifier.height(8.dp)); Text("•  $it", fontSize = 12.sp, color = Amber) }
    }
}

@Composable
fun OverviewScreen(sv: Server, j: JSONObject, online: Boolean, onOpen: (String) -> Unit) {
    val hist by rememberHistory(sv, "1h", 10_000)
    val h = hist
    fun hs(k: String): List<Double?> = h?.s?.get(k) ?: emptyList()
    val cpu = j.getDouble("cpu"); val ram = j.getDouble("ram")
    val temp = j.dbl("temp"); val gpu = j.dbl("gpu"); val disk = diskPct(j)
    val pw = j.optJSONObject("power"); val watts = pw?.dbl("watts")
    val (dv, du) = netFmt(j.getDouble("net_down_bps")); val (uv, uu) = netFmt(j.getDouble("net_up_bps"))
    var usedGb = 0.0; var totGb = 0.0
    j.optJSONArray("disks")?.let { for (i in 0 until it.length()) { usedGb += it.getJSONObject(i).optDouble("used_gb", 0.0); totGb += it.getJSONObject(i).optDouble("total_gb", 0.0) } }
    HealthCard(j, online, Modifier.staggerIn(0))
    Row(Modifier.fillMaxWidth().staggerIn(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(Ic.Cpu, "CPU", animNum(cpu), "%", "${j.optInt("cores")} cores", listOf(GSeries(hs("cpu"), Red)), 10.0, Modifier.weight(1f)) { onOpen("cpu") }
        MetricTile(Ic.Memory, "Memory", animNum(ram), "%", "${j.optDouble("ram_used_gb")} / ${j.optDouble("ram_total_gb")} GB",
            listOf(GSeries(hs("ram"), Blue)), 10.0, Modifier.weight(1f)) { onOpen("ram") }
    }
    Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(Ic.Thermo, "Temperature", animNum(temp), if (temp == null) "" else "°C", if (temp == null) "No sensor found" else null,
            listOf(GSeries(hs("temp"), Amber)), 4.0, Modifier.weight(1f)) { onOpen("temp") }
        MetricTile(Ic.Activity, "Network", dv, du, "↑ $uv $uu", listOf(GSeries(hs("down"), Green), GSeries(hs("up"), GreenLight, true)), 200_000.0,
            Modifier.weight(1f)) { onOpen("net") }
    }
    Row(Modifier.fillMaxWidth().staggerIn(3), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricTile(Ic.Storage, "Storage", animNum(disk), if (disk == null) "" else "%", "%.0f / %.0f GB".format(usedGb, totGb),
            listOf(GSeries(hs("disk"), Mut)), 2.0, Modifier.weight(1f)) { onOpen("disk") }
        MetricTile(Ic.Zap, "Power", animNum(watts), if (watts == null) "" else "W", if (watts == null) "No power source" else "%.2f kWh today".format(pw?.optDouble("today_kwh", 0.0) ?: 0.0),
            listOf(GSeries(hs("power"), Red)), 5.0, Modifier.weight(1f)) { onOpen("power") }
    }
    if (gpu != null) MetricTile(Ic.Chart, "GPU", animNum(gpu), "%", null, listOf(GSeries(hs("gpu"), Mut)), 10.0, Modifier.fillMaxWidth().staggerIn(4)) { onOpen("gpu") }
    Box(Modifier.staggerIn(5)) { InternetPanel(j, sv) }
}

@Composable
fun MetricDetail(sv: Server, kind: String, j: JSONObject, onBack: () -> Unit) {
    BackHandler { onBack() }
    var range by remember { mutableStateOf("1h") }
    val hist by rememberHistory(sv, range, 15_000)
    val h = hist
    val (title, icon, color) = when (kind) {
        "cpu" -> Triple("CPU", Ic.Cpu, Red); "ram" -> Triple("Memory", Ic.Memory, Blue); "temp" -> Triple("Temperature", Ic.Thermo, Amber)
        "net" -> Triple("Network", Ic.Activity, Green); "disk" -> Triple("Storage", Ic.Storage, Mut); "gpu" -> Triple("GPU", Ic.Chart, Mut)
        else -> Triple("Power", Ic.Zap, Red)
    }
    val key = when (kind) { "net" -> "down"; else -> kind }
    val unit = when (kind) { "temp" -> "°C"; "net" -> "Mbps"; "power" -> "W"; else -> "%" }
    val scale = if (kind == "net") 1e-6 else 1.0
    val minSpan = when (kind) { "temp" -> 4.0; "net" -> 0.2; "power" -> 5.0; "disk" -> 2.0; else -> 10.0 }
    val vals = (h?.s?.get(key) ?: emptyList()).map { it?.times(scale) }
    val ok = vals.filterNotNull()
    val pw = j.optJSONObject("power")
    val now: Double? = when (kind) {
        "cpu" -> j.getDouble("cpu"); "ram" -> j.getDouble("ram"); "temp" -> j.dbl("temp"); "gpu" -> j.dbl("gpu")
        "net" -> j.getDouble("net_down_bps") * 1e-6; "power" -> pw?.dbl("watts"); else -> diskPct(j)
    }
    val fmt = if (kind == "net") "%.2f" else "%.1f"
    fun f(v: Double?) = if (v == null) "–" else fmt.format(v) + (if (unit == "%") "%" else " $unit")
    val series = if (kind == "net") listOf(GSeries(vals, Green), GSeries((h?.s?.get("up") ?: emptyList()).map { it?.times(scale) }, GreenLight, true))
                 else listOf(GSeries(vals, color))
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back to dashboard", onClick = onBack)
            Icon24(icon, color, 20.dp); Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Txt)
        }
        Text(f(now), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Txt)
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("1h", "24h", "7d").forEach { r -> Chip(r, range == r) { range = r } } }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(Surf2)) {
                if (h == null) Text("Loading history…", color = Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                else if (ok.isEmpty()) Text("No readings recorded for this range yet.", color = Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
                else {
                    HistoryGraph(series, Modifier.matchParentSize(), minSpan, 0.95f, 0.22f, 2.dp)
                    Text(f(ok.max()), fontSize = 11.sp, color = Dim, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                    Text(f(ok.min()), fontSize = 11.sp, color = Dim, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
            if (h != null && h.t.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtT(h.t.first(), range), fontSize = 11.sp, color = Dim); Text(fmtT(h.t.last(), range), fontSize = 11.sp, color = Dim)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoChip("Average", f(if (ok.isEmpty()) null else ok.average()), Modifier.weight(1f))
            InfoChip("Min", f(ok.minOrNull()), Modifier.weight(1f))
            InfoChip("Max", f(ok.maxOrNull()), Modifier.weight(1f))
        }
        Panel {
            Label("Details"); Spacer(Modifier.height(6.dp))
            when (kind) {
                "cpu" -> { KV("Cores", "${j.optInt("cores")}"); j.optJSONArray("load")?.let { KV("Load average", "%.2f".format(it.optDouble(0))) } }
                "ram" -> { KV("Used", "${j.optDouble("ram_used_gb")} GB"); KV("Total", "${j.optDouble("ram_total_gb")} GB") }
                "temp" -> KV("Reading", if (now == null) "No sensor found" else "Hottest CPU sensor")
                "gpu" -> KV("Usage", f(now))
                "net" -> { KV("Download", rate(j.getDouble("net_down_bps") / 8)); KV("Upload", rate(j.getDouble("net_up_bps") / 8)); KV("IPv4", j.str("ip").ifEmpty { "-" }) }
                "disk" -> DiskPie(j.optJSONArray("disks") ?: JSONArray())
                else -> { KV("Source", pw?.str("source")?.ifEmpty { "No sensor" } ?: "-"); KV("Voltage", pw?.dbl("voltage")?.let { "%.1f V".format(it) } ?: "N/A")
                    KV("Today", "%.2f kWh".format(pw?.optDouble("today_kwh", 0.0) ?: 0.0)) }
            }
        }
    }
}

class ChartData(val vals: List<Double?>, val left: String, val right: String, val aLabel: String, val a: String, val bLabel: String, val b: String, val minSpan: Double)

fun chartFor(mode: String, today: java.time.LocalDate, e: Map<String, Double>?, h: History?): ChartData {
    val dm = java.time.format.DateTimeFormatter.ofPattern("d MMM")
    fun kwh(v: List<Double?>): Pair<String, String> { val ok = v.filterNotNull(); return if (ok.isEmpty()) "–" to "–" else "%.2f kWh".format(ok.sum()) to "%.2f kWh".format(ok.max()) }
    return when (mode) {
        "Day" -> { val v = h?.s?.get("power") ?: emptyList(); val ok = v.filterNotNull()
            ChartData(v, h?.t?.firstOrNull()?.let { fmtT(it, "24h") } ?: "", h?.t?.lastOrNull()?.let { fmtT(it, "24h") } ?: "",
                "Average", if (ok.isEmpty()) "–" else "%.0f W".format(ok.average()), "Peak", if (ok.isEmpty()) "–" else "%.0f W".format(ok.max()), 5.0) }
        "Week" -> { val days = (6 downTo 0).map { today.minusDays(it.toLong()) }; val v = days.map { e?.get(it.toString()) }; val (t, p) = kwh(v)
            ChartData(v, days.first().format(dm), days.last().format(dm), "Total", t, "Peak day", p, 0.05) }
        "Month" -> { val days = (1..today.dayOfMonth).map { today.withDayOfMonth(it) }; val v = days.map { e?.get(it.toString()) }; val (t, p) = kwh(v)
            ChartData(v, days.first().format(dm), days.last().format(dm), "Total", t, "Peak day", p, 0.05) }
        else -> { val v = (1..today.monthValue).map { m -> val pre = "%04d-%02d".format(today.year, m)
                val ks = e?.filterKeys { it.startsWith(pre) }; if (ks.isNullOrEmpty()) null else ks.values.sum() }; val (t, p) = kwh(v)
            val mn = { m: Int -> java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
            ChartData(v, mn(1), mn(today.monthValue), "Total", t, "Peak month", p, 0.5) }
    }
}

@Composable
fun EnergyPanel(sv: Server, pw: JSONObject) {
    val energy by rememberEnergy(sv)
    val hist by rememberHistory(sv, "24h", 30_000)
    var mode by remember { mutableStateOf("Month") }
    val mh = pw.optJSONArray("month_history")
    val today = try { java.time.LocalDate.parse(mh!!.getJSONObject(mh.length() - 1).getString("day")) } catch (e: Exception) { java.time.LocalDate.now() }
    val cd = chartFor(mode, today, energy, hist)
    val ok = cd.vals.filterNotNull()
    Panel {
        Label(if (mode == "Day") "Power · last 24 hours" else "Energy · $mode")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Day", "Week", "Month", "Year").forEach { m -> Chip(m, mode == m) { mode = m } } }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).background(Surf2)) {
            if (ok.isEmpty()) Text(if (energy == null && mode != "Day") "Loading…" else "No readings recorded for this period yet.", color = Dim, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            else HistoryGraph(listOf(GSeries(cd.vals, Red)), Modifier.matchParentSize(), cd.minSpan, 0.95f, 0.24f, 2.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(cd.left, fontSize = 11.sp, color = Dim); Text(cd.right, fontSize = 11.sp, color = Dim) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoChip(cd.aLabel, cd.a, Modifier.weight(1f)); InfoChip(cd.bLabel, cd.b, Modifier.weight(1f))
        }
        val since = pw.str("since")
        if (since.isNotEmpty() && mode != "Day") { Spacer(Modifier.height(8.dp)); Text("Energy tracked since $since", fontSize = 11.sp, color = Dim) }
    }
}

@Composable
fun DiagRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(10.dp))
        Text(label, color = Txt, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Mut, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SystemScreen(sv: Server, j: JSONObject, online: Boolean) {
    val up = j.optLong("uptime_s")
    Panel {
        Label("Server"); Spacer(Modifier.height(6.dp))
        KV("Host", j.str("host")); KV("System", j.str("os")); KV("Uptime", "${up / 86400}d ${up % 86400 / 3600}h ${up % 3600 / 60}m")
        KV("Address", j.str("ip").ifEmpty { "-" }); KV("CPU cores", "${j.optInt("cores")}"); KV("Memory", "${j.optDouble("ram_total_gb")} GB")
    }
    val pw = j.optJSONObject("power"); val sp = j.optJSONObject("speed") ?: JSONObject()
    val perr = pw?.str("plug_error") ?: ""
    Panel {
        Label("Diagnostics"); Spacer(Modifier.height(4.dp))
        DiagRow("Monitoring connection", if (online) "Connected" else "Offline", if (online) Green else Err)
        DiagRow("Temperature sensor", if (j.dbl("temp") != null) "Available" else "Not found", if (j.dbl("temp") != null) Green else Dim)
        DiagRow("GPU", if (j.dbl("gpu") != null) "Available" else "Not available", if (j.dbl("gpu") != null) Green else Dim)
        DiagRow("Power source", when { perr.isNotEmpty() -> "Error"; pw?.dbl("watts") != null -> pw.str("source"); else -> "None" },
            when { perr.isNotEmpty() -> Amber; pw?.dbl("watts") != null -> Green; else -> Dim })
        DiagRow("Speed test", when { sp.optBoolean("running") -> "Testing…"; sp.dbl("down_mbps") != null -> "Last ${fmtClock(sp.optLong("ts"))}"; sp.str("error").isNotEmpty() -> "Failed"; else -> "Waiting" },
            when { sp.dbl("down_mbps") != null -> Green; sp.str("error").isNotEmpty() -> Amber; else -> Dim })
    }
    SystemTab(j)
    InfrastructurePanel(sv)
}

@Composable
fun ActionRow(title: String, sub: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Txt); Text(sub, fontSize = 12.sp, color = Mut) }
            Text("›", fontSize = 26.sp, color = Red)
        }
    }
}

@Composable
fun SettingsScreen(sv: Server, onEdit: () -> Unit, onPower: () -> Unit) {
    val ctx = LocalContext.current
    ActionRow("Server connection", sv.name + " · " + sv.url.removePrefix("http://"), onEdit)
    ActionRow("Power source and electricity rate", "Choose your plug, price per kWh and currency", onPower)
    ReportExportPanel(sv)
    RefreshPanel()
    AboutPanel()
}

@Composable
fun Dashboard(sv: Server, onBack: () -> Unit, onEdit: () -> Unit) {
    var refreshKey by remember { mutableStateOf(0) }
    val r by rememberStats(sv, AppSettings.refreshMs, refreshKey)
    val j = r.json
    val online = j != null && r.error == null
    var setup by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<String?>(null) }
    if (setup) { PowerSetup(sv) { setup = false }; return }
    val d = detail
    if (d != null && j != null) { MetricDetail(sv, d, j) { detail = null }; return }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        AppHeader(sv, j, r.error, online, onBack, { refreshKey++ }, { tab = 3 })
        Box(Modifier.weight(1f)) {
            Crossfade(tab, animationSpec = tween(220), label = "tabs") { t ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (j == null && t != 3) { Text(r.error ?: "Connecting…", color = Amber, fontSize = 13.sp); return@Column }
                    when (t) {
                        0 -> OverviewScreen(sv, j!!, online) { detail = it }
                        1 -> PowerTab(sv, j!!.optJSONObject("power")) { setup = true }
                        2 -> SystemScreen(sv, j!!, online)
                        else -> SettingsScreen(sv, onEdit) { setup = true }
                    }
                }
            }
        }
        BottomBar(tab) { tab = it }
    }
}
