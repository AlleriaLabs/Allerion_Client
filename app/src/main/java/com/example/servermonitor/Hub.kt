package com.example.servermonitor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

const val TAGLINE = "Monitor. Understand. Control."

@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp) = Image(painterResource(R.drawable.logo_mark), "ALLERION logo", Modifier.size(size))

@Composable
fun Splash(onDone: () -> Unit) {
    val rm = LocalReduceMotion.current
    val a = remember { Animatable(if (rm) 1f else 0f) }
    LaunchedEffect(Unit) { if (!rm) a.animateTo(1f, tween(650, easing = FastOutSlowInEasing)); delay(if (rm) 250L else 650L); onDone() }
    Box(Modifier.fillMaxSize().background(Bg).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { alpha = a.value; scaleX = 0.92f + 0.08f * a.value; scaleY = 0.92f + 0.08f * a.value },
            horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(96.dp)
            Spacer(Modifier.height(22.dp))
            Text("ALLERION", fontSize = AppType.displaySmall, fontWeight = FontWeight.Bold, color = Txt, letterSpacing = 6.sp)
            Spacer(Modifier.height(6.dp))
            Text("ALLERIA LABS", fontSize = AppType.caption, color = Dim, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(18.dp))
            Text(TAGLINE, fontSize = AppType.body, color = Mut)
        }
    }
}

@Composable
fun HubHeader(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ALLERION", fontSize = AppType.title, fontWeight = FontWeight.Bold, color = Txt, letterSpacing = 2.sp)
            Text("ALLERIA LABS", fontSize = AppType.micro, color = Dim, fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp)
        }
        IconBtn(Ic.Gear, "Settings", onClick = onSettings)
    }
}

@Composable
fun SummaryStat(label: String, value: String, color: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(17.dp)
    Box(modifier.clip(shape).background(Surf).border(1.dp, Line.copy(alpha = 0.82f), shape)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 13.dp)) {
            Text(value, fontSize = AppType.title, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
            Text(label, fontSize = AppType.caption, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StatusLabel(code: Int) {
    val (c, t) = when (code) { 1 -> Green to "Online"; 2 -> Err to "Offline"; else -> Amber to "Connecting" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(c, code == 1, 7.dp)
        Text(t, fontSize = AppType.small, color = c, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StateMessage(text: String, warn: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surf2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (warn) Amber else Dim)); Spacer(Modifier.width(10.dp))
        Text(text, fontSize = AppType.body, color = Mut)
    }
}

@Composable
fun ActionIcon(ic: Ic, desc: String, tint: Color = Mut, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).tap(onClick), contentAlignment = Alignment.Center) {
        Icon24(ic, tint, 20.dp, desc = desc)
    }
}

@Composable
fun ExpandedBody(sv: Server, j: JSONObject?, err: String?, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (j == null) StateMessage(err ?: "Connecting to the monitoring service…", err != null)
        else {
            if (err != null) StateMessage("$err Showing the last readings received.", true)
            val up = j.optLong("uptime_s")
            Text("Updated ${fmtClock(j.optLong("ts"))}  ·  Up ${up / 86400}d ${up % 86400 / 3600}h", fontSize = AppType.small, color = Dim)
            Text("API response · ${ApiClient.lastRequestDurationMs} ms", fontSize = AppType.caption, color = Dim)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(15.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Txt)
            ) {
                Icon24(Ic.Dash, Mut, 18.dp); Spacer(Modifier.width(8.dp))
                Text("Open dashboard", fontWeight = FontWeight.SemiBold)
            }
            ActionIcon(Ic.Pencil, "Edit server", onClick = onEdit)
            ActionIcon(Ic.Trash, "Delete server", Err, onDelete)
        }
    }
}

@Composable
fun ServerCard(sv: Server, statusKey: String, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onEdit: () -> Unit,
               onDelete: () -> Unit, onStatus: (Int) -> Unit, modifier: Modifier = Modifier, lastSeen: Long? = null) {
    val r by rememberStats(sv, if (expanded) 3_000L else 10_000L)       // collapsed cards only need status, so they poll slowly
    val j = r.json
    val code = when { j != null && r.error == null -> 1; r.error != null -> 2; else -> 0 }
    LaunchedEffect(statusKey, code, j) { onStatus(code) }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, tween(260, easing = FastOutSlowInEasing), label = "chevron")
    var confirm by remember { mutableStateOf(false) }
    Panel(modifier) {
        Row(Modifier.fillMaxWidth().tap(onToggle), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Surf2), contentAlignment = Alignment.Center) { Icon24(Ic.Server, Mut, 20.dp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(sv.name, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold, color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sv.url.removePrefix("http://").removePrefix("https://"), fontSize = AppType.small, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sv.group.uppercase(), fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Red, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lastSeen?.let { FleetPresenceStore.formatAge(it) } ?: "Awaiting first successful check", fontSize = AppType.micro, color = if (FleetPresenceStore.isStale(lastSeen)) Amber else Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                j?.let { stats ->
                    val metrics = listOfNotNull(
                        stats.optDouble("cpu", Double.NaN).takeIf { value -> !value.isNaN() }?.let { value -> "CPU ${value.roundToInt()}%" },
                        stats.optDouble("ram", Double.NaN).takeIf { value -> !value.isNaN() }?.let { value -> "RAM ${value.roundToInt()}%" },
                        stats.optDouble("temp", Double.NaN).takeIf { value -> !value.isNaN() }?.let { value -> "${value.roundToInt()}°C" }
                    )
                    if (metrics.isNotEmpty()) Text(metrics.joinToString("  ·  "), fontSize = AppType.micro, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            StatusLabel(code)
            Spacer(Modifier.width(6.dp))
            Icon24(Ic.ChevronD, Mut, 20.dp, Modifier.graphicsLayer { rotationZ = rot }, if (expanded) "Collapse" else "Expand")
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(190, easing = FastOutSlowInEasing)) + fadeOut(tween(140))) {
            ExpandedBody(sv, j, r.error, onOpen, onEdit) { confirm = true }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Delete", color = Err) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = Mut) } },
        title = { Text("Delete ${sv.name}?") },
        text = { Text("This removes the server from ALLERION. The server itself and its recorded history are not affected.") },
        containerColor = Surf2, titleContentColor = Txt, textContentColor = Mut)
}

@Composable
fun AddFab(onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Surf2)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).tap(onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon24(Ic.Plus, Txt, 18.dp, desc = "Add server")
        Spacer(Modifier.width(8.dp))
        Text("Add server", fontSize = AppType.body, fontWeight = FontWeight.SemiBold, color = Txt)
    }
}

@Composable
fun HubModuleCard(icon: Ic, title: String, subtitle: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit, live: Boolean = false) {
    Panel(modifier = modifier.heightIn(min = 124.dp).tap(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon24(icon, accent, 19.dp)
            }
            Spacer(Modifier.weight(1f))
            if (live) PulseDot(accent, true, 7.dp)
            else Icon24(Ic.ChevronD, Dim, 16.dp, Modifier.graphicsLayer { rotationZ = -90f })
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Mut, fontSize = AppType.caption, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Home(servers: List<Server>, expanded: Int?, onExpand: (Int?) -> Unit, onOpen: (Int) -> Unit, onEdit: (Int) -> Unit,
         onDelete: (Int) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onIntegrations: () -> Unit, onUsers: () -> Unit,
         onLiveUsers: () -> Unit, onAnalytics: () -> Unit, onActivity: () -> Unit,
         onServerManagement: () -> Unit, serverManagementPage: Boolean = false, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val status = remember { mutableStateMapOf<String, Int>() }
    val groupPreferences = remember(context) {
        context.getSharedPreferences("fleet_preferences", android.content.Context.MODE_PRIVATE)
    }
    var selectedGroup by remember(groupPreferences) {
        mutableStateOf(groupPreferences.getString("selected_group", "All groups") ?: "All groups")
    }
    var groupMenuExpanded by remember { mutableStateOf(false) }
    fun keyOf(i: Int, sv: Server) = "$i|${sv.url}"
    val groups = servers.map { it.group.trim().ifEmpty { "Ungrouped" } }.distinct().sorted()
    LaunchedEffect(selectedGroup) {
        groupPreferences.edit().putString("selected_group", selectedGroup).apply()
    }
    LaunchedEffect(groups) {
        if (selectedGroup != "All groups" && selectedGroup !in groups) selectedGroup = "All groups"
    }
    val visibleServers = servers.mapIndexed { index, server -> index to server }
        .filter { (_, server) -> selectedGroup == "All groups" || server.group.trim().ifEmpty { "Ungrouped" } == selectedGroup }
    val codes = servers.mapIndexed { i, sv -> status[keyOf(i, sv)] ?: 0 }
    val online = codes.count { it == 1 }; val offline = codes.count { it == 2 }
    val availability = if (online + offline == 0) "–" else "${(online * 100f / (online + offline)).roundToInt()}%"
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        val wideLayout = maxWidth >= 720.dp
        Column(Modifier.widthIn(max = 1100.dp).fillMaxHeight().fillMaxWidth().align(Alignment.TopCenter).verticalScroll(rememberScrollState())) {
            HubHeader(onSettings)
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Column(Modifier.padding(top = 10.dp, bottom = 3.dp).staggerIn(0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (serverManagementPage) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconBtn(Ic.ChevronL, "Back to control center", plain = true, onClick = onBack)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("INFRASTRUCTURE", fontSize = AppType.micro, fontWeight = FontWeight.Bold, color = Red, letterSpacing = 1.55.sp)
                                Text("Server Management", fontSize = AppType.title, fontWeight = FontWeight.Bold, color = Txt, letterSpacing = (-0.25).sp)
                            }
                            IconBtn(Ic.Plus, "Add server", plain = true, onClick = onAdd)
                        }
                        Text("Monitor and manage your connected servers.", fontSize = AppType.small, color = Mut)
                    } else {
                        Text("ALLERIA LABS · INFRASTRUCTURE", fontSize = AppType.micro, fontWeight = FontWeight.Bold, color = Red, letterSpacing = 1.55.sp)
                        Text("Control Center", fontSize = AppType.displaySmall, fontWeight = FontWeight.ExtraBold, color = Txt, letterSpacing = (-0.5).sp)
                        Text("Your infrastructure, connected and in view.", fontSize = AppType.small, color = Mut)
                    }
                }
                if (!serverManagementPage) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubModuleCard(Ic.Heart, "User management", "Accounts, invitations and access", Color(0xFFFF8294), Modifier.weight(1f).staggerIn(1), onUsers)
                    HubModuleCard(Ic.Plug, "Integration center", "Jellyfin, alerts and services", Color(0xFFD6B4FF), Modifier.weight(1f).staggerIn(2), onIntegrations)
                }
                if (!serverManagementPage) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubModuleCard(Ic.Server, "Server management", "Fleet, network and diagnostics", Color(0xFF77C7FF), Modifier.weight(1f).staggerIn(3), onServerManagement)
                    HubModuleCard(Ic.Chart, "Jellyfin analytics", "Viewing trends and rankings", Color(0xFFB7A2FF), Modifier.weight(1f).staggerIn(4), onAnalytics)
                }
                if (!serverManagementPage) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubModuleCard(Ic.Activity, "Live users", "Who's online and watching", Color(0xFF62E0B0), Modifier.weight(1f).staggerIn(5), onLiveUsers, live = true)
                    HubModuleCard(Ic.Bell, "Activity & alerts", "Events, incidents and recovery", Color(0xFFFFC477), Modifier.weight(1f).staggerIn(6), onActivity)
                }
                AnimatedVisibility(visible = serverManagementPage, enter = fadeIn(tween(240)) + androidx.compose.animation.expandVertically(tween(260)),
                    exit = fadeOut(tween(150)) + androidx.compose.animation.shrinkVertically(tween(200))) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Connected servers", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                            }
                        }
                if (servers.isEmpty()) {
                    Panel(Modifier.staggerIn(1)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Surf2), contentAlignment = Alignment.Center) { Icon24(Ic.Server, Mut, 26.dp) }
                            Spacer(Modifier.height(14.dp))
                            Text("No servers yet", fontSize = AppType.titleSmall, fontWeight = FontWeight.SemiBold, color = Txt)
                            Spacer(Modifier.height(4.dp))
                            Text("Add the ALLERION monitoring service running on your home server to start watching it.", fontSize = AppType.body, color = Mut, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().staggerIn(1), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SummaryStat("Servers", "${servers.size}", Txt, Modifier.weight(1f))
                        SummaryStat("Online", "$online", Green, Modifier.weight(1f))
                        SummaryStat("Offline", "$offline", if (offline > 0) Err else Txt, Modifier.weight(1f))
                        SummaryStat("Availability", availability, Txt, Modifier.weight(1.25f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Fleet view", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                            Text("${visibleServers.size} of ${servers.size} servers", color = Dim, fontSize = AppType.caption)
                        }
                        Box {
                            OutlinedButton(onClick = { groupMenuExpanded = true }, shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Txt)) {
                                Text(selectedGroup, color = Txt, fontSize = AppType.small)
                                Spacer(Modifier.width(6.dp))
                                Icon24(Ic.ChevronD, Mut, 16.dp)
                            }
                            DropdownMenu(expanded = groupMenuExpanded, onDismissRequest = { groupMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("All groups") },
                                    onClick = { selectedGroup = "All groups"; groupMenuExpanded = false }
                                )
                                groups.forEach { group ->
                                    DropdownMenuItem(
                                        text = { Text(group) },
                                        onClick = { selectedGroup = group; groupMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    if (visibleServers.isEmpty()) {
                        Panel {
                            Text("No servers in this group", color = Mut, fontSize = AppType.body)
                        }
                    } else if (wideLayout) {
                        visibleServers.chunked(2).forEachIndexed { rowIndex, pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEachIndexed { columnIndex, entry ->
                                    val (i, sv) = entry
                                    key(keyOf(i, sv)) {
                                        ServerCard(sv, keyOf(i, sv), expanded == i, { onExpand(if (expanded == i) null else i) }, { onOpen(i) }, { onEdit(i) }, { onDelete(i) },
                                            { status[keyOf(i, sv)] = it; if (it == 1) FleetPresenceStore.recordSuccess(context, sv) }, Modifier.weight(1f).staggerIn(rowIndex * 2 + columnIndex + 2), FleetPresenceStore.lastSeen(context, sv))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        visibleServers.forEach { (i, sv) ->
                            key(keyOf(i, sv)) {
                                ServerCard(sv, keyOf(i, sv), expanded == i, { onExpand(if (expanded == i) null else i) }, { onOpen(i) }, { onEdit(i) }, { onDelete(i) },
                                    { status[keyOf(i, sv)] = it; if (it == 1) FleetPresenceStore.recordSuccess(context, sv) }, Modifier.staggerIn(i + 2), FleetPresenceStore.lastSeen(context, sv))
                            }
                        }
                    }
                }
                    }
                }
                Spacer(Modifier.height(124.dp))
            }
        }
        if (!serverManagementPage) { /* Add Server is available inside Server Management only. */ }
    }
}

@Composable
fun RefreshPanel() {
    val ctx = LocalContext.current
    Panel {
        Label("Refresh interval"); Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10, 30).forEach { s ->
                Chip("${s}s", AppSettings.refreshMs == s * 1000L) {
                    AppSettings.refreshMs = s * 1000L
                    ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit().putLong("refresh_ms", s * 1000L).apply()
                }
            }
        }
        Spacer(Modifier.height(8.dp)); Text("How often live readings update. Graph history is recorded by the server regardless.", fontSize = AppType.small, color = Dim)
    }
}

@Composable
fun AboutPanel() {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(54.dp); Spacer(Modifier.width(14.dp))
            Column {
                Text("ALLERION", fontSize = AppType.title, fontWeight = FontWeight.Bold, color = Txt, letterSpacing = 2.sp)
                Text("by ALLERIA LABS", fontSize = AppType.small, color = Mut)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(TAGLINE, fontSize = AppType.body, color = Mut)
        Spacer(Modifier.height(8.dp))
        KV("Category", "Home & Server Monitoring")
        KV("Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        KV("History and energy totals", "Stored on your server")
    }
}

@Composable
fun ReportExportPanel(sv: Server) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val report = pendingReport ?: error("Report data is unavailable.")
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report) }
                    ?: error("Could not create the CSV file.")
                status = "Seven-day CSV report saved successfully."
            } catch (error: Exception) {
                status = "CSV export failed: " + (error.message ?: "Unable to write file.")
            }
        }
        busy = false
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) {
            try {
                val pdf = pendingPdf ?: error("PDF report data is unavailable.")
                context.contentResolver.openOutputStream(uri)?.use { it.write(pdf) }
                    ?: error("Could not create the PDF file.")
                status = "Seven-day PDF report saved successfully."
            } catch (error: Exception) {
                status = "PDF export failed: " + (error.message ?: "Unable to write file.")
            }
        }
        busy = false
    }

    fun prepareReport(asPdf: Boolean) {
        busy = true
        status = null
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) { buildMonitoringReport(sv) }
                val safeName = sv.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifBlank { "server" }
                if (asPdf) {
                    pendingPdf = withContext(Dispatchers.IO) { PdfReport.render("ALLERION · 7-day report", report) }
                    pdfLauncher.launch("ALLERION-" + safeName + "-7d.pdf")
                    status = "Choose where to save the PDF report."
                } else {
                    pendingReport = report
                    csvLauncher.launch("ALLERION-" + safeName + "-7d.csv")
                    status = "Choose where to save the CSV report."
                }
            } catch (error: Exception) {
                status = friendlyError(error)
                busy = false
            }
        }
    }

    Panel {
        Label("Monitoring report")
        Spacer(Modifier.height(6.dp))
        Text("Export seven days of timestamped CPU, memory, temperature, network, power, GPU and storage readings, plus daily energy totals when available.", color = Mut, fontSize = AppType.small)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { prepareReport(false) }, Modifier.weight(1f).height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)), enabled = !busy,
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surf2, contentColor = Txt),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text(if (busy) "Preparing…" else "Export CSV")
            }
            OutlinedButton(onClick = { prepareReport(true) }, Modifier.weight(1f).height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)), enabled = !busy,
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Surf2, contentColor = Mut),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("Export PDF", color = Txt)
            }
        }
        status?.let { Text(it, color = if (it.contains("saved successfully")) Green else Amber, fontSize = AppType.small) }
    }
}

@Composable
fun HubSettings(onRestore: (List<Server>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var backupPassword by remember { mutableStateOf("") }
    var backupAction by remember { mutableStateOf<String?>(null) }
    var backupPayload by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<List<Server>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var alertsEnabled by remember {
        mutableStateOf(context.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).getBoolean("alerts_enabled", false))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            alertsEnabled = true
            AlertScheduler.setEnabled(context, true)
        } else {
            alertsEnabled = false
            AlertScheduler.setEnabled(context, false)
            message = "Notifications are not enabled. Allow ALLERION notifications in Android settings to receive alerts."
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            try {
                val payload = backupPayload ?: error("Backup data is unavailable.")
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    ?: error("Could not create the backup file.")
                message = "Encrypted backup saved successfully."
            } catch (error: Exception) {
                message = "Backup failed: " + (error.message ?: "Unable to write file.")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                backupPayload = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected file.")
                backupAction = "restore"
            } catch (error: Exception) {
                message = "Could not open backup: " + (error.message ?: "Unknown error.")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back to servers", onClick = onBack)
            Text("Settings", fontSize = AppType.section, fontWeight = FontWeight.Bold, color = Txt)
        }
        RefreshPanel()
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Smart health alerts")
                    Spacer(Modifier.height(4.dp))
                    Text("Check registered servers every 15 minutes. Choose alert categories below for availability, resource thresholds, Docker container failures and newly detected LAN devices.", color = Mut, fontSize = AppType.small)
                }
                Switch(checked = alertsEnabled, onCheckedChange = { enabled ->
                    if (!enabled) {
                        alertsEnabled = false
                        AlertScheduler.setEnabled(context, false)
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        alertsEnabled = true
                        AlertScheduler.setEnabled(context, true)
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("Alerts are transition-based and include recovery notifications. Customize thresholds and review recent events below.", color = Dim, fontSize = AppType.caption)
        }
        AlertSettingsPanel()
        Panel {
            Label("Server backup and restore")
            Spacer(Modifier.height(6.dp))
            Text("Create a password-protected backup of your server names, addresses and access tokens. The backup can be restored on another ALLERION installation.", color = Mut, fontSize = AppType.body)
            Spacer(Modifier.height(12.dp))
            Button({ backupPassword = ""; backupAction = "export" }, Modifier.fillMaxWidth().height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surf2, contentColor = Txt),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("Create encrypted backup")
            }
            OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                Modifier.fillMaxWidth().height(48.dp).border(1.dp, Line, RoundedCornerShape(15.dp)), shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Surf2, contentColor = Mut),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Text("Restore from backup", color = Txt)
            }
            Text("Keep the backup password safe. It cannot be recovered if lost. Exported backup files contain encrypted credentials.", color = Dim, fontSize = AppType.caption)
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = if (it.startsWith("Encrypted")) Green else Amber, fontSize = AppType.small) }
        }
    }

    if (backupAction != null) {
        AlertDialog(
            onDismissRequest = { backupAction = null; backupPassword = "" },
            title = { Text(if (backupAction == "export") "Protect your backup" else "Unlock backup", color = Txt) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (backupAction == "export") "Set a password with at least 8 characters. You will need it to restore this backup." else "Enter the password used when this backup was created.", color = Mut)
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (backupAction == "export") {
                            val servers = loadServers(context.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE))
                            backupPayload = BackupManager.encrypt(servers, backupPassword.toCharArray())
                            backupAction = null
                            exportLauncher.launch("ALLERION-backup.json")
                        } else {
                            val restored = BackupManager.decrypt(backupPayload ?: error("Backup file is missing."), backupPassword.toCharArray())
                            pendingRestore = restored
                            backupAction = null
                        }
                        backupPassword = ""
                    } catch (error: Exception) {
                        message = error.message ?: "Backup operation failed."
                    }
                }) { Text(if (backupAction == "export") "Continue" else "Unlock", color = Red) }
            },
            dismissButton = { TextButton(onClick = { backupAction = null; backupPassword = "" }) { Text("Cancel", color = Mut) } },
            containerColor = Surf2,
            titleContentColor = Txt,
            textContentColor = Mut
        )
    }

    pendingRestore?.let { restored ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Replace saved servers?", color = Txt) },
            text = { Text("This backup contains " + restored.size + " server(s). Restoring will replace the current server list on this device. This does not change or delete anything on the monitoring servers.", color = Mut) },
            confirmButton = {
                TextButton(onClick = {
                    onRestore(restored)
                    pendingRestore = null
                    message = "Server list restored. Reopen a server to reconnect."
                }) { Text("Replace servers", color = Red) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel", color = Mut) } },
            containerColor = Surf2,
            titleContentColor = Txt,
            textContentColor = Mut
        )
    }
}

@Composable
fun EditServer(sv: Server?, onSave: (Server) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(sv?.name ?: "") }
    var addr by remember { mutableStateOf(sv?.url?.removePrefix("http://") ?: "") }
    var tok by remember { mutableStateOf(sv?.token ?: "") }
    var group by remember { mutableStateOf(sv?.group ?: "Ungrouped") }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun clean(): String { var c = addr.trim().trimEnd('/'); if (!c.startsWith("http")) c = "http://$c"; return c }
    fun valid(): Boolean = try { java.net.URL(clean()).host.isNotEmpty() } catch (e: Exception) { false }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ChevronL, "Back", onClick = onCancel)
            Text(if (sv == null) "Add server" else "Edit server", fontSize = AppType.section, fontWeight = FontWeight.Bold, color = Txt)
        }
        Text("Use the address shown when the monitoring service starts, for example 192.168.1.50:8080.", color = Mut, fontSize = AppType.body)
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(group, { group = it }, label = { Text("Group / location (e.g. Home, NAS, Office)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(addr, { addr = it; test = null }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(tok, { tok = it; test = null }, label = { Text("Token") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            }
        }
        test?.let { (ok, msg) -> StateMessage(msg, !ok) }
        OutlinedButton({
            if (!valid()) { test = false to "Invalid server address."; return@OutlinedButton }
            testing = true; test = null
            scope.launch {
                test = try { val j = withContext(Dispatchers.IO) { fetch(clean(), tok.trim()) }; true to "Connected to ${j.str("host").ifEmpty { "server" }}." }
                       catch (e: CancellationException) { throw e } catch (e: Exception) { false to friendlyError(e) }
                testing = false
            }
        }, Modifier.fillMaxWidth().height(50.dp), enabled = addr.isNotBlank() && !testing, shape = RoundedCornerShape(14.dp)) {
            Text(if (testing) "Testing…" else "Test connection", color = Txt)
        }
        Button({
            if (!valid()) test = false to "Invalid server address."
            else onSave(Server(name.trim().ifEmpty { "My server" }, clean(), tok.trim(), group.trim().ifEmpty { "Ungrouped" }))
        }, Modifier.fillMaxWidth().height(52.dp), enabled = addr.isNotBlank(), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Txt)) { Text("Save", fontWeight = FontWeight.SemiBold) }
        if (sv != null) OutlinedButton(onDelete, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Remove server", color = Err) }
        TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancel", color = Mut) }
    }
}
