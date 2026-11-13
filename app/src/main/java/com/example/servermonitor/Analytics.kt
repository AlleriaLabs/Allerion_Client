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
import androidx.compose.ui.text.font.FontWeight
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
        loading = true
        error = null
        try {
            data = withContext(Dispatchers.IO) {
                ApiClient.getJson(server.url, server.token, "/api/jellyfin/analytics?period=$period", readTimeoutMs = 20000)
            }
            if (data?.optBoolean("configured") == false) error = data?.optString("message")
        } catch (e: Exception) {
            error = friendlyError(e)
        }
        loading = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth().fillMaxHeight()
                .align(Alignment.TopCenter).verticalScroll(rememberScrollState())
        ) {
            HubHeader(onSettings, onBack) {
                IconBtn(Ic.Refresh, "Refresh analytics", plain = true, onClick = { refresh++ })
            }

            // Keep the same content column, section rhythm and heading hierarchy as Server Management.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Column(
                    Modifier.padding(top = 10.dp, bottom = 3.dp).staggerIn(0),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("MEDIA SERVER", fontSize = AppType.micro, fontWeight = FontWeight.Bold,
                        color = Red, letterSpacing = 1.55.sp)
                    Text("Jellyfin Analytics", fontSize = AppType.title, fontWeight = FontWeight.Bold,
                        color = Txt, letterSpacing = (-0.25).sp)
                    Text("Understand viewing activity, popular titles and your most active viewers.",
                        fontSize = AppType.small, color = Mut)
                }

                // Mirrors the Connected servers section heading used by Server Management.
                Row(Modifier.fillMaxWidth().staggerIn(1), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Analytics overview", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text("Collected playback activity", color = Dim, fontSize = AppType.caption)
                    }
                    if (loading) Text("Updating", color = Dim, fontSize = AppType.caption)
                }

                Row(Modifier.fillMaxWidth().staggerIn(2), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SummaryStat("Samples", "${data?.optInt("sample_count") ?: 0}", Txt, Modifier.weight(1f))
                    SummaryStat("Movies", "${data?.optJSONArray("movies")?.length() ?: 0}", Red, Modifier.weight(1f))
                    SummaryStat("Viewers", "${data?.optJSONArray("users")?.length() ?: 0}", Green, Modifier.weight(1f))
                    if (wide) SummaryStat("Period", period.replaceFirstChar { it.uppercase() }, Mut, Modifier.weight(1.15f))
                }

                Row(Modifier.fillMaxWidth().staggerIn(3), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Viewing trends", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text("Unique online viewers over time", color = Dim, fontSize = AppType.caption)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("day", "week", "month", "year").forEach { p ->
                            Chip(p.replaceFirstChar { it.uppercase() }, period == p) { period = p }
                        }
                    }
                }

                Panel(Modifier.staggerIn(4)) {
                    if (loading && data == null) {
                        StateMessage("Loading Jellyfin analytics…", false)
                    } else if (error != null) {
                        StateMessage(error ?: "Unable to load analytics.", true)
                    } else {
                        val points = data?.optJSONArray("data_points")
                        val values = (0 until (points?.length() ?: 0)).map { points!!.optJSONObject(it).optInt("users") }
                        if (values.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No historical activity yet", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(5.dp))
                                Text("The graph starts filling after the analytics collector is configured and deployed.",
                                    color = Mut, fontSize = AppType.small)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("VIEWERS", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text("${values.maxOrNull() ?: 0} peak", color = Mut, fontSize = AppType.caption)
                            }
                            Spacer(Modifier.height(14.dp))
                            AnalyticsLine(values, Modifier.fillMaxWidth().height(174.dp))
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(points!!.optJSONObject(0).optString("day"), color = Dim, fontSize = AppType.caption)
                                Text(points.optJSONObject(points.length() - 1).optString("day"), color = Dim, fontSize = AppType.caption)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().staggerIn(5), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Playback rankings", color = Txt, fontSize = AppType.bodyStrong, fontWeight = FontWeight.SemiBold)
                        Text("What your users are watching", color = Dim, fontSize = AppType.caption)
                    }
                }

                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        MovieRankingPanel(data, Modifier.weight(1f).staggerIn(6))
                        ViewerRankingPanel(data, Modifier.weight(1f).staggerIn(7))
                    }
                } else {
                    MovieRankingPanel(data, Modifier.fillMaxWidth().staggerIn(6))
                    ViewerRankingPanel(data, Modifier.fillMaxWidth().staggerIn(7))
                }

                Panel(Modifier.staggerIn(8)) {
                    Text(data?.optString("note") ?: "Watch time is estimated from periodic Jellyfin session samples.",
                        color = Dim, fontSize = AppType.caption)
                    Spacer(Modifier.height(4.dp))
                    Text("Samples collected: ${data?.optInt("sample_count") ?: 0}",
                        color = Dim, fontSize = AppType.caption)
                }
                Spacer(Modifier.height(124.dp))
            }
        }
    }
}

@Composable
private fun MovieRankingPanel(data: JSONObject?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text("MOST WATCHED MOVIES", color = Txt, fontSize = AppType.micro,
            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text("Ranked by sampled plays and estimated watch time", color = Mut, fontSize = AppType.caption)
        Spacer(Modifier.height(8.dp))
        val movies = data?.optJSONArray("movies")
        if (movies == null || movies.length() == 0) {
            Text("No movie playback samples recorded yet.", color = Mut, fontSize = AppType.small)
        } else {
            for (i in 0 until minOf(movies.length(), 20)) {
                val movie = movies.optJSONObject(i)
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.65f)))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Surf2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Red, fontSize = AppType.small, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(movie.optString("title", "Unknown title"), color = Txt, fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("${movie.optInt("plays")} sampled plays", color = Mut, fontSize = AppType.caption)
                    }
                    Text("${movie.optDouble("watch_minutes").toInt()}m", color = Txt, fontSize = AppType.small,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ViewerRankingPanel(data: JSONObject?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text("TOP VIEWERS", color = Txt, fontSize = AppType.micro,
            fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text("Ranked by estimated watch time", color = Mut, fontSize = AppType.caption)
        Spacer(Modifier.height(8.dp))
        val users = data?.optJSONArray("users")
        if (users == null || users.length() == 0) {
            Text("No viewer activity has been sampled yet.", color = Mut, fontSize = AppType.small)
        } else {
            for (i in 0 until minOf(users.length(), 20)) {
                val user = users.optJSONObject(i)
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.65f)))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(Surf2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Red, fontSize = AppType.small, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.optString("name", "Unknown user"), color = Txt, fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${user.optInt("titles")} titles · ${user.optInt("plays")} plays",
                            color = Mut, fontSize = AppType.caption)
                    }
                    Text("%.1f h".format(user.optDouble("watch_minutes") / 60.0), color = Txt,
                        fontSize = AppType.small, fontWeight = FontWeight.SemiBold)
                }
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
        values.forEachIndexed { i, value ->
            val x = if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1)
            val y = size.height - (value / max) * (size.height - 12.dp.toPx()) - 6.dp.toPx()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Red, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        values.forEachIndexed { i, value ->
            val x = if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1)
            val y = size.height - (value / max) * (size.height - 12.dp.toPx()) - 6.dp.toPx()
            drawCircle(Red, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}
