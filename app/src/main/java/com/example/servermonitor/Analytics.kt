package com.example.servermonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun JellyfinAnalyticsScreen(server: Server, onBack: () -> Unit = {}, onSettings: () -> Unit = {}) {
    var period by remember { mutableStateOf("week") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(server.url, server.token, period, refresh) {
        loading = true; error = null
        try {
            data = withContext(Dispatchers.IO) {
                ApiClient.getJson(server.url, server.token, "/api/jellyfin/analytics?period=$period", readTimeoutMs = 20000)
            }
            if (data?.optBoolean("configured") == false) error = data?.optString("message")
        } catch (e: Exception) { error = friendlyError(e) }
        loading = false
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxHeight().fillMaxWidth()
                .align(Alignment.TopCenter).verticalScroll(rememberScrollState())
        ) {
            ModulePageHeader("MEDIA INTELLIGENCE", "Jellyfin Analytics", "Viewing trends and playback rankings", onBack, onSettings) {
                IconBtn(Ic.Refresh, "Refresh analytics", plain = true, onClick = { refresh++ })
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("Samples", (data?.optInt("sample_count") ?: 0).toString(), Txt, Modifier.weight(1f))
                    SummaryStat("Movies", (data?.optJSONArray("movies")?.length() ?: 0).toString(), Red, Modifier.weight(1f))
                    SummaryStat("Viewers", (data?.optJSONArray("users")?.length() ?: 0).toString(), Green, Modifier.weight(1f))
                }
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Label("VIEWER ACTIVITY")
                            Text("Unique online users over time", color = Mut, fontSize = AppType.small)
                        }
                        if (loading) Text("Updating", color = Dim, fontSize = AppType.caption)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("day","week","month","year").forEach { p ->
                            Chip(p.replaceFirstChar { it.uppercase() }, period == p) { period = p }
                        }
                    }
                    if (loading && data == null) Text("Loading analytics…", color = Mut, fontSize = AppType.small)
                    else if (error != null) Text(error!!, color = Amber, fontSize = AppType.small)
                    else {
                        val points = data?.optJSONArray("data_points")
                        val values = (0 until (points?.length() ?: 0)).map { points!!.optJSONObject(it).optInt("users") }
                        if (values.isEmpty()) Text("No historical samples yet. Once the backend is configured and deployed, this graph will populate automatically.", color = Mut, fontSize = AppType.small)
                        else {
                            AnalyticsLine(values, Modifier.fillMaxWidth().height(170.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(points!!.optJSONObject(0).optString("day"), color = Dim, fontSize = 10.sp)
                                Text(points.optJSONObject(points.length()-1).optString("day"), color = Dim, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Panel {
                    Label("MOST WATCHED MOVIES")
                    Text("Ranked by sampled plays and estimated watch time", color = Mut, fontSize = AppType.small)
                    val movies = data?.optJSONArray("movies")
                    if (movies == null || movies.length() == 0) Text("No movie playback samples recorded yet.", color = Mut, fontSize = AppType.small)
                    else for (i in 0 until movies.length()) {
                        val m=movies.optJSONObject(i)
                        Row(Modifier.fillMaxWidth().padding(vertical=5.dp), verticalAlignment=Alignment.CenterVertically) {
                            Text("${i+1}", color=Red, fontSize=AppType.bodyStrong)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.optString("title","Unknown title"), color=Txt, fontSize=AppType.bodyStrong)
                                Text("${m.optInt("plays")} sampled plays · ${m.optDouble("watch_minutes").toInt()} min", color=Mut, fontSize=AppType.caption)
                            }
                        }
                    }
                }
                Panel {
                    Label("TOP VIEWERS")
                    Text("Ranked by estimated watch time", color = Mut, fontSize = AppType.small)
                    val users = data?.optJSONArray("users")
                    if (users == null || users.length() == 0) Text("No viewer activity has been sampled yet.", color = Mut, fontSize = AppType.small)
                    else for (i in 0 until users.length()) {
                        val u=users.optJSONObject(i)
                        Row(Modifier.fillMaxWidth().padding(vertical=5.dp), verticalAlignment=Alignment.CenterVertically) {
                            Text("${i+1}", color=Red, fontSize=AppType.bodyStrong)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.optString("name","Unknown user"), color=Txt, fontSize=AppType.bodyStrong)
                                Text("${u.optInt("titles")} titles · ${u.optInt("plays")} plays", color=Mut, fontSize=AppType.caption)
                            }
                            Text("%.1f h".format(u.optDouble("watch_minutes")/60.0), color=Txt, fontSize=AppType.bodyStrong)
                        }
                    }
                }
                Panel {
                    Text(data?.optString("note") ?: "Watch time is estimated from periodic Jellyfin session samples.", color=Dim, fontSize=AppType.caption)
                    Text("Samples collected: ${data?.optInt("sample_count") ?: 0}", color=Dim, fontSize=AppType.caption)
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun AnalyticsLine(values: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = if (values.size == 1) size.width/2 else size.width*i/(values.size-1)
            val y = size.height - (v/max)*(size.height-12.dp.toPx()) - 6.dp.toPx()
            if (i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        drawPath(path, Red, style=Stroke(width=3.dp.toPx(), cap=StrokeCap.Round))
        values.forEachIndexed { i,v ->
            val x=if(values.size==1) size.width/2 else size.width*i/(values.size-1)
            val y=size.height-(v/max)*(size.height-12.dp.toPx())-6.dp.toPx()
            drawCircle(Red, radius=4.dp.toPx(), center=androidx.compose.ui.geometry.Offset(x,y))
        }
    }
}
