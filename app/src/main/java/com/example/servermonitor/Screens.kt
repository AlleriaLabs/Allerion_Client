package com.example.servermonitor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
    for (i in 0 until d.length()) { val o = d.getJSONObject(i); if (o.optBoolean("mounted", true)) { u += o.optDouble("used_gb", 0.0); t += o.optDouble("total_gb", 0.0) } }
    return if (t > 0) u / t * 100 else null
}

fun netFmt(bps: Double): Pair<String, String> = if (bps >= 1e6) "%.1f".format(bps / 1e6) to "Mbps" else "%.0f".format(bps / 1e3) to "kbps"

@Composable
fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Mut, fontSize = AppType.body)
        Text(value, color = Txt, fontSize = AppType.body, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppHeader(sv: Server, j: JSONObject?, err: String?, online: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, onActivity: () -> Unit) {
    var spin by remember { mutableStateOf(0) }
    val rot by animateFloatAsState(spin * 360f, tween(700, easing = FastOutSlowInEasing), label = "spin")
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 9.dp, top = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.ChevronL, "Back to servers", plain = true, onClick = onBack)
        Image(painterResource(R.drawable.logo_mark), null, Modifier.size(29.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(sv.name, fontSize = AppType.titleSmall, fontWeight = FontWeight.ExtraBold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ts = j?.optLong("ts") ?: 0L
            Text(when { j == null -> err ?: "Connecting…"; !online -> "Offline · last seen ${fmtClock(ts)}"; else -> "ALLERION · updated ${fmtClock(ts)}" },
                fontSize = AppType.caption, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Status(online)
        IconBtn(Ic.Refresh, "Refresh", rot, plain = true) { spin++; onRefresh() }
        IconBtn(Ic.Bell, "Activity center", plain = true, onClick = onActivity)
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
    val status = when {
        !online -> "OFFLINE"
        warns.isNotEmpty() -> "${warns.size} WARNING" + if (warns.size > 1) "S" else ""
        else -> "ONLINE"
    }
    val uptimeSeconds = j.optLong("uptime_s").coerceAtLeast(0L)
    val uptime = "${uptimeSeconds / 86400}d ${uptimeSeconds % 86400 / 3600}h"

    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon24(Ic.Globe, color, 19.dp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("GATEWAY", fontSize = AppType.micro, fontWeight = FontWeight.Bold,
                    color = Mut, letterSpacing = 1.sp)
                Text(status, fontSize = AppType.body, fontWeight = FontWeight.Bold, color = color)
                Text(
                    if (online) "Uptime · $uptime" else "Cannot reach the monitoring service",
                    fontSize = AppType.caption, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            PulseDot(color, online && warns.isEmpty())
        }
        warns.forEach {
            Spacer(Modifier.height(6.dp))
            Text("•  $it", fontSize = AppType.small, color = Amber)
        }
    }
}

@Composable
fun OverviewScreen(sv: Server, j: JSONObject, online: Boolean, onOpen: (String) -> Unit) {
    val hist by rememberHistory(sv, "1h", 10_000)
    val h = hist
    fun hs(k: String): List<Double?> = h?.s?.get(k) ?: emptyList()
    val cpu = j.getDouble("cpu"); val ram = j.getDouble("ram")
    val temp = j.dbl("temp"); val gpu = j.dbl("gpu"); val disk = diskPct(j)
    val snapshotTs = j.optLong("ts")
    val initialUptime = j.optLong("uptime_s").coerceAtLeast(0L)
    var liveUptime by remember(snapshotTs, initialUptime) { mutableStateOf(initialUptime) }
    LaunchedEffect(snapshotTs, initialUptime) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            liveUptime += 1L
        }
    }
    var usedGb = 0.0; var totGb = 0.0
    j.optJSONArray("disks")?.let { for (i in 0 until it.length()) { val disk = it.getJSONObject(i); if (disk.optBoolean("mounted", true)) { usedGb += disk.optDouble("used_gb", 0.0); totGb += disk.optDouble("total_gb", 0.0) } } }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Label("Server details"); Text(j.str("host").ifEmpty { sv.name }, color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Status(online)
        }
        Spacer(Modifier.height(8.dp))
        KV("Host", j.str("host"))
        KV("System", j.str("os"))
        KV("Uptime", "${liveUptime / 86400}d ${liveUptime % 86400 / 3600}h ${liveUptime % 3600 / 60}m ${liveUptime % 60}s")
        KV("Address", j.str("ip").ifEmpty { "-" })
        KV("CPU cores", "${j.optInt("cores")}")
        KV("Memory", "${j.optDouble("ram_total_gb")} GB")
    }
    val dashboardPrefs = LocalContext.current.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
    val showCpu = remember { mutableStateOf(dashboardPrefs.getBoolean("dashboard_show_cpu", true)) }
    val showMemory = remember { mutableStateOf(dashboardPrefs.getBoolean("dashboard_show_memory", true)) }
    val showTemperature = remember { mutableStateOf(dashboardPrefs.getBoolean("dashboard_show_temperature", true)) }
    val showStorage = remember { mutableStateOf(dashboardPrefs.getBoolean("dashboard_show_storage", true)) }
    val showGpu = remember { mutableStateOf(dashboardPrefs.getBoolean("dashboard_show_gpu", true)) }
    Row(Modifier.fillMaxWidth().staggerIn(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showCpu.value) MetricTile(Ic.Cpu, "CPU", animNum(cpu), "%", "${j.optInt("cores")} cores", listOf(GSeries(hs("cpu"), Red)), 10.0, Modifier.weight(1f)) { onOpen("cpu") }
        if (showMemory.value) MetricTile(Ic.Memory, "Memory", animNum(ram), "%", "${j.optDouble("ram_used_gb")} / ${j.optDouble("ram_total_gb")} GB",
            listOf(GSeries(hs("ram"), Blue)), 10.0, Modifier.weight(1f)) { onOpen("ram") }
    }
    Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTemperature.value) MetricTile(Ic.Thermo, "Temperature", animNum(temp), if (temp == null) "" else "°C", if (temp == null) "No sensor found" else null,
            listOf(GSeries(hs("temp"), Amber)), 4.0, Modifier.weight(1f), centerValue = true) { onOpen("temp") }
        if (showStorage.value) MetricTile(Ic.Storage, "Storage", animNum(disk), if (disk == null) "" else "%", "%.0f / %.0f GB".format(usedGb, totGb),
            listOf(GSeries(hs("disk"), Mut)), 2.0, Modifier.weight(1f)) { onOpen("disk") }
    }
    if (gpu != null && showGpu.value) MetricTile(Ic.Chart, "GPU", animNum(gpu), "%", null, listOf(GSeries(hs("gpu"), Mut)), 10.0, Modifier.fillMaxWidth().staggerIn(4)) { onOpen("gpu") }
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
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back to dashboard", onClick = onBack)
            Icon24(icon, color, 20.dp); Spacer(Modifier.width(8.dp))
            Text(title, fontSize = AppType.section, fontWeight = FontWeight.Bold, color = Txt)
        }
        Text(f(now), fontSize = AppType.hero, fontWeight = FontWeight.Bold, color = Txt)
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("1h", "24h", "7d").forEach { r -> Chip(r, range == r) { range = r } } }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(Surf2)) {
                if (h == null) Text("Loading history…", color = Dim, fontSize = AppType.body, modifier = Modifier.align(Alignment.Center))
                else if (ok.isEmpty()) Text("No readings recorded for this range yet.", color = Dim, fontSize = AppType.body, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
                else {
                    HistoryGraph(series, Modifier.matchParentSize(), minSpan, 0.95f, 0.22f, 2.dp)
                    Text(f(ok.max()), fontSize = AppType.caption, color = Dim, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                    Text(f(ok.min()), fontSize = AppType.caption, color = Dim, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
            if (h != null && h.t.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtT(h.t.first(), range), fontSize = AppType.caption, color = Dim); Text(fmtT(h.t.last(), range), fontSize = AppType.caption, color = Dim)
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
private fun EnergyValueChart(cd: ChartData, mode: String, loading: Boolean) {
    val values = cd.vals.filterNotNull()
    val unit = if (mode == "Day") "W" else "kWh"
    val latest = cd.vals.lastOrNull { it != null }
    var low = values.minOrNull() ?: 0.0
    var high = values.maxOrNull() ?: 0.0
    if (high - low < cd.minSpan) {
        val mid = (high + low) / 2.0
        low = mid - cd.minSpan / 2.0
        high = mid + cd.minSpan / 2.0
    }
    val pad = (high - low) * 0.14
    low -= pad
    high += pad
    fun axis(v: Double): String = if (mode == "Day") "%.0f".format(v) else "%.2f".format(v)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == "Day") "POWER DRAW · W" else "ENERGY USE · KWH",
                color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text(latest?.let { "LATEST  ${axis(it)} $unit" } ?: if (loading) "LOADING" else "NO READINGS",
                color = if (latest != null) Red else Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(164.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    for (i in 0..2) {
                        val y = size.height * i / 2f
                        drawLine(Line.copy(alpha = 0.72f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    for (i in 0..4) {
                        val x = size.width * i / 4f
                        drawLine(Line.copy(alpha = 0.32f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
                if (values.isEmpty()) {
                    Text(if (loading) "Loading recorded values…" else "No measured readings for this period",
                        color = Dim, fontSize = AppType.caption, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp))
                } else {
                    HistoryGraph(listOf(GSeries(cd.vals, Red)), Modifier.matchParentSize(),
                        cd.minSpan, 0.98f, 0.20f, 2.2.dp)
                    val valid = cd.vals.mapIndexedNotNull { index, value -> value?.let { index to it } }
                    val valueLabels = if (valid.size <= 5) valid else
                        listOf(valid.first(), valid[valid.size / 4], valid[valid.size / 2],
                            valid[(valid.size * 3) / 4], valid.last()).distinct()
                    valueLabels.forEach { (index, value) ->
                        val xFraction = if (cd.vals.size <= 1) 0f else index.toFloat() / (cd.vals.size - 1)
                        val yFraction = ((high - value) / (high - low)).toFloat().coerceIn(0f, 1f)
                        Text(axis(value), color = Txt, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.offset(
                                x = (maxWidth * xFraction - 16.dp).coerceIn(0.dp, (maxWidth - 32.dp).coerceAtLeast(0.dp)),
                                y = (maxHeight * yFraction - 10.dp).coerceIn(0.dp, (maxHeight - 20.dp).coerceAtLeast(0.dp))
                            ).clip(RoundedCornerShape(5.dp)).background(Bg.copy(alpha = 0.88f))
                                .padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
            }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(cd.left, color = Dim, fontSize = AppType.caption)
            Text(if (mode == "Day") "NOW" else "LATEST", color = Dim, fontSize = AppType.caption)
            Text(cd.right, color = Dim, fontSize = AppType.caption)
        }
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(if (mode == "Day") "Power · last 24 hours" else "Energy · $mode")
                    Text(if (mode == "Day") "Live power history" else "Measured consumption over time",
                        color = Dim, fontSize = AppType.caption)
                }
                Text("${ok.size} POINTS", color = Mut, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Day", "Week", "Month", "Year").forEach { m -> Chip(m, mode == m) { mode = m } }
            }
            EnergyValueChart(cd, mode, energy == null && mode != "Day")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(cd.aLabel, cd.a, Modifier.weight(1f))
                InfoChip(cd.bLabel, cd.b, Modifier.weight(1f))
            }
            val since = pw.str("since")
            if (since.isNotEmpty() && mode != "Day") {
                Text("Energy tracked since $since", fontSize = AppType.caption, color = Dim)
            }
        }
    }
}

@Composable
fun DiagRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(10.dp))
        Text(label, color = Txt, fontSize = AppType.body, modifier = Modifier.weight(1f))
        Text(value, color = Mut, fontSize = AppType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SystemScreen(sv: Server, j: JSONObject, online: Boolean) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Label("System diagnostics"); Spacer(Modifier.weight(1f)); Status(online) }
        Spacer(Modifier.height(5.dp))
        DiagRow("Monitoring connection", if (online) "Connected" else "Offline", if (online) Green else Err)
        DiagRow("Temperature sensor", if (j.dbl("temp") != null) "Available" else "Not found", if (j.dbl("temp") != null) Green else Dim)
        DiagRow("GPU", if (j.dbl("gpu") != null) "Available" else "Not available", if (j.dbl("gpu") != null) Green else Dim)
    }
    SystemTab(j)
    InfrastructurePanel(sv)
}

@Composable
fun NetworkScreen(sv: Server, j: JSONObject, onOpen: (String) -> Unit) {
    val (downValue, downUnit) = netFmt(j.getDouble("net_down_bps"))
    val (upValue, upUnit) = netFmt(j.getDouble("net_up_bps"))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Label("Network status"); Spacer(Modifier.weight(1f)); Status(true) }
        Spacer(Modifier.height(8.dp))
        DiagRow("IPv4 address", j.str("ip").ifEmpty { "Not reported" }, Green)
        DiagRow("Download", "$downValue $downUnit", Blue)
        DiagRow("Upload", "$upValue $upUnit", Amber)
        TextButton(onClick = { onOpen("net") }) { Text("View traffic details", color = Red) }
    }
    InternetPanel(j, sv)
    InfrastructurePanel(sv, networkOnly = true)
}

@Composable
fun ActionRow(title: String, sub: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Surf2), contentAlignment = Alignment.Center) { Icon24(Ic.Gear, Red, 18.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold, color = Txt); Text(sub, fontSize = AppType.small, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("›", fontSize = AppType.section, color = Red)
        }
    }
}

@Composable
fun SettingsScreen(sv: Server, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    ActionRow("Server connection", sv.name + " · " + sv.url.removePrefix("http://"), onEdit)
    DashboardPreferencesPanel()
    ReportExportPanel(sv)
    RefreshPanel()
    AboutPanel()
}

@Composable
private fun DashboardSectionHeader(tab: Int, server: String, morePage: String) {
    val (eyebrow, title, subtitle) = when (tab) {
        0 -> Triple("SERVER OVERVIEW", "Infrastructure", "Live health and resource utilization for $server")
        1 -> Triple("NETWORK INTELLIGENCE", "Network", "Traffic, connected devices and diagnostics")
        2 -> Triple("ENERGY MONITORING", "Power", "Consumption, cost and energy insights")
        3 -> Triple("SERVER MANAGEMENT", "System", "Processes, containers and system diagnostics")
        else -> when (morePage) {
            "activity" -> Triple("SYSTEM ACTIVITY", "Activity", "Server alerts, events and recovery history")
            "settings" -> Triple("PREFERENCES", "Settings", "Manage your Allerion experience")
            "connection" -> Triple("SERVER CONFIGURATION", "Connection", "Manage this server's connection details")
            "diagnostics" -> Triple("APP DIAGNOSTICS", "Crash reports", "Inspect and share locally captured crash details")
            else -> Triple("WORKSPACE", "More", "Tools and configuration")
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp).staggerIn(0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, color = Red, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Text(title, color = Txt, fontSize = AppType.displaySmall, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Text(subtitle, color = Mut, fontSize = AppType.small, lineHeight = 17.sp)
    }
}

@Composable
fun Dashboard(sv: Server, onBack: () -> Unit, onEdit: () -> Unit) {
    var refreshKey by remember { mutableStateOf(0) }
    val r by rememberStats(sv, AppSettings.refreshMs, refreshKey)
    val j = r.json
    val online = j != null && r.error == null
    var setup by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var morePage by remember { mutableStateOf("menu") }
    var detail by remember { mutableStateOf<String?>(null) }
    if (setup) { PowerSetup(sv) { setup = false }; return }
    val d = detail
    if (d != null && j != null) { MetricDetail(sv, d, j) { detail = null }; return }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        AppHeader(sv, j, r.error, online, onBack, { refreshKey++ }, {
            tab = 4
            morePage = "activity"
        })
        Box(Modifier.weight(1f)) {
            Crossfade("$tab:$morePage", animationSpec = tween(220), label = "tabs") { targetState ->
                key(targetState) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DashboardSectionHeader(tab, sv.name, morePage)
                    when (tab) {
                        0 -> if (j == null) Panel { Label("Connecting to server"); Text(r.error ?: "Establishing a secure monitoring connection…", color = Amber, fontSize = AppType.body) }
                             else OverviewScreen(sv, j, online) { detail = it }
                        1 -> if (j == null) Panel { Label("Connecting to server"); Text(r.error ?: "Establishing a secure monitoring connection…", color = Amber, fontSize = AppType.body) }
                             else NetworkScreen(sv, j) { detail = it }
                        2 -> if (j == null) Panel { Label("Connecting to server"); Text(r.error ?: "Establishing a secure monitoring connection…", color = Amber, fontSize = AppType.body) }
                             else PowerTab(sv, j.optJSONObject("power")) { setup = true }
                        3 -> if (j == null) Panel { Label("Connecting to server"); Text(r.error ?: "Establishing a secure monitoring connection…", color = Amber, fontSize = AppType.body) }
                             else SystemScreen(sv, j, online)
                        else -> when (morePage) {
                            "activity" -> ActivityCenterScreen()
                            "settings" -> SettingsScreen(sv, onEdit)
                            "diagnostics" -> CrashDiagnosticsScreen()
                            "connection" -> {
                                Panel {
                                    Label("Server connection")
                                    Text("Update the address, access token or display name for this server.", color = Mut, fontSize = AppType.small)
                                    ActionRow("Edit server connection", sv.name + " · " + sv.url.removePrefix("http://").removePrefix("https://"), onEdit)
                                }
                            }
                            else -> MoreScreen(
                                onActivity = { morePage = "activity" },
                                onSettings = { morePage = "settings" },
                                onConnection = { morePage = "connection" },
                                onDiagnostics = { morePage = "diagnostics" }
                            )
                        }
                    }
                }
                }
            }
        }
        BottomBar(tab) {
            tab = it
            if (it == 4) morePage = "menu"
        }
    }
}
