package com.example.servermonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Standalone Jellyfin analytics module.
 *
 * This page intentionally uses the same page shell as Server Management:
 * full-screen background -> centered 1100dp column -> HubHeader ->
 * horizontally inset section content -> section headings and Panel cards.
 */
@Composable
fun JellyfinAnalyticsScreen(
    server: Server,
    onBack: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    var period by remember { mutableStateOf("week") }
    var payload by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(server.url, server.token, period, refreshKey) {
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                ApiClient.getJson(
                    server.url,
                    server.token,
                    "/api/jellyfin/analytics?period=$period",
                    readTimeoutMs = 20000
                )
            }
            payload = result
            if (result.optBoolean("configured") == false) {
                error = result.optString("message").takeIf { it.isNotBlank() }
                    ?: "Jellyfin analytics is not configured on the server."
            }
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            loading = false
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding()
    ) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier
                .widthIn(max = 1100.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
        ) {
            // Identical brand/navigation row used by the Server Management page.
            HubHeader(
                onSettings = onSettings,
                onBack = onBack,
                trailing = {
                    IconBtn(
                        Ic.Refresh,
                        "Refresh analytics",
                        plain = true,
                        onClick = { refreshKey++ }
                    )
                }
            )

            // Recreated page content follows the same inset and spacing rhythm
            // as Home(serverManagementPage = true).
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Column(
                    Modifier.padding(top = 10.dp, bottom = 3.dp).staggerIn(0),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "MEDIA SERVER",
                        fontSize = AppType.micro,
                        fontWeight = FontWeight.Bold,
                        color = Red,
                        letterSpacing = 1.55.sp
                    )
                    Text(
                        "Jellyfin Analytics",
                        fontSize = AppType.title,
                        fontWeight = FontWeight.Bold,
                        color = Txt,
                        letterSpacing = (-0.25).sp
                    )
                    Text(
                        "Viewing activity, playback trends and audience rankings.",
                        fontSize = AppType.small,
                        color = Mut
                    )
                }

                // Overview section mirrors the Connected servers section in
                // Server Management, rather than introducing a separate screen shell.
                Row(
                    Modifier.fillMaxWidth().staggerIn(1),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Analytics overview",
                            color = Txt,
                            fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (loading) "Updating playback data…" else "Collected playback activity",
                            color = Dim,
                            fontSize = AppType.caption
                        )
                    }
                    if (error != null) {
                        Text("Needs attention", color = Amber, fontSize = AppType.caption)
                    } else if (payload != null) {
                        Text("Connected", color = Green, fontSize = AppType.caption)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().staggerIn(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryStat(
                        "Samples",
                        "${payload?.optInt("sample_count") ?: 0}",
                        Txt,
                        Modifier.weight(1f)
                    )
                    SummaryStat(
                        "Movies",
                        "${payload?.optJSONArray("movies")?.length() ?: 0}",
                        Red,
                        Modifier.weight(1f)
                    )
                    SummaryStat(
                        "Viewers",
                        "${payload?.optJSONArray("users")?.length() ?: 0}",
                        Green,
                        Modifier.weight(1f)
                    )
                    if (wide) {
                        SummaryStat(
                            "Range",
                            period.replaceFirstChar { it.uppercase() },
                            Mut,
                            Modifier.weight(1.2f)
                        )
                    }
                }

                // Trend section.
                Row(
                    Modifier.fillMaxWidth().staggerIn(3),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Viewer activity",
                            color = Txt,
                            fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Unique viewers sampled over time",
                            color = Dim,
                            fontSize = AppType.caption
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("day", "week", "month", "year").forEach { value ->
                            Chip(
                                value.replaceFirstChar { it.uppercase() },
                                period == value
                            ) { period = value }
                        }
                    }
                }

                Panel(Modifier.fillMaxWidth().staggerIn(4)) {
                    when {
                        loading && payload == null -> StateMessage("Loading Jellyfin analytics…", false)
                        error != null -> StateMessage(error ?: "Unable to load analytics.", true)
                        else -> {
                            val points = payload?.optJSONArray("data_points")
                            val samples = (0 until (points?.length() ?: 0)).mapNotNull { index ->
                                points?.optJSONObject(index)?.let {
                                    it.optString("day") to it.optInt("users")
                                }
                            }
                            if (samples.isEmpty()) {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "No historical activity yet",
                                        color = Txt,
                                        fontSize = AppType.bodyStrong,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        "Viewer history begins after the analytics collector is configured and deployed.",
                                        color = Mut,
                                        fontSize = AppType.small
                                    )
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "VIEWERS",
                                        color = Dim,
                                        fontSize = AppType.micro,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "${samples.maxOf { it.second }} peak",
                                        color = Mut,
                                        fontSize = AppType.caption
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                ViewerActivityGraph(
                                    samples.map { it.second },
                                    Modifier.fillMaxWidth().height(178.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(samples.first().first, color = Dim, fontSize = AppType.caption)
                                    Text(samples.last().first, color = Dim, fontSize = AppType.caption)
                                }
                            }
                        }
                    }
                }

                // Ranking section follows the same section-heading + Panel pattern
                // used for the fleet list in Server Management.
                Column(
                    Modifier.fillMaxWidth().staggerIn(5),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Playback rankings",
                        color = Txt,
                        fontSize = AppType.bodyStrong,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Most watched titles and most active viewers",
                        color = Dim,
                        fontSize = AppType.caption
                    )
                }

                if (wide) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        MovieRankingCard(payload, Modifier.weight(1f).staggerIn(6))
                        ViewerRankingCard(payload, Modifier.weight(1f).staggerIn(7))
                    }
                } else {
                    MovieRankingCard(payload, Modifier.fillMaxWidth().staggerIn(6))
                    ViewerRankingCard(payload, Modifier.fillMaxWidth().staggerIn(7))
                }

                Panel(Modifier.fillMaxWidth().staggerIn(8)) {
                    Text(
                        payload?.optString("note")?.takeIf { it.isNotBlank() }
                            ?: "Watch time is estimated from periodic Jellyfin session samples.",
                        color = Dim,
                        fontSize = AppType.caption
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Samples collected: ${payload?.optInt("sample_count") ?: 0}",
                        color = Dim,
                        fontSize = AppType.caption
                    )
                }
                Spacer(Modifier.height(124.dp))
            }
        }
    }
}

@Composable
private fun MovieRankingCard(data: JSONObject?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            "MOST WATCHED MOVIES",
            color = Txt,
            fontSize = AppType.micro,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
        Text(
            "Ranked by sampled plays and estimated watch time",
            color = Mut,
            fontSize = AppType.caption
        )
        Spacer(Modifier.height(8.dp))
        val movies = data?.optJSONArray("movies")
        if (movies == null || movies.length() == 0) {
            Text("No movie playback samples recorded yet.", color = Mut, fontSize = AppType.small)
        } else {
            for (index in 0 until minOf(movies.length(), 20)) {
                val movie = movies.optJSONObject(index) ?: continue
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.65f)))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RankingNumber(index + 1)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            movie.optString("title").ifBlank { "Unknown title" },
                            color = Txt,
                            fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2
                        )
                        Text(
                            "${movie.optInt("plays")} sampled plays",
                            color = Mut,
                            fontSize = AppType.caption
                        )
                    }
                    Text(
                        "${movie.optDouble("watch_minutes").toInt()}m",
                        color = Txt,
                        fontSize = AppType.small,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerRankingCard(data: JSONObject?, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(
            "TOP VIEWERS",
            color = Txt,
            fontSize = AppType.micro,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp
        )
        Text("Ranked by estimated watch time", color = Mut, fontSize = AppType.caption)
        Spacer(Modifier.height(8.dp))
        val users = data?.optJSONArray("users")
        if (users == null || users.length() == 0) {
            Text("No viewer activity has been sampled yet.", color = Mut, fontSize = AppType.small)
        } else {
            for (index in 0 until minOf(users.length(), 20)) {
                val user = users.optJSONObject(index) ?: continue
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.65f)))
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RankingNumber(index + 1)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user.optString("name").ifBlank { "Unknown user" },
                            color = Txt,
                            fontSize = AppType.bodyStrong,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            "${user.optInt("titles")} titles · ${user.optInt("plays")} plays",
                            color = Mut,
                            fontSize = AppType.caption
                        )
                    }
                    Text(
                        "%.1f h".format(user.optDouble("watch_minutes") / 60.0),
                        color = Txt,
                        fontSize = AppType.small,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingNumber(number: Int) {
    Box(
        Modifier.size(30.dp).background(Surf2, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            number.toString(),
            color = Red,
            fontSize = AppType.small,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ViewerActivityGraph(values: List<Int>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val maxValue = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f
            else size.width * index / (values.size - 1)
            val y = size.height - (value / maxValue) * (size.height - 12.dp.toPx()) - 6.dp.toPx()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Red,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
        values.forEachIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f
            else size.width * index / (values.size - 1)
            val y = size.height - (value / maxValue) * (size.height - 12.dp.toPx()) - 6.dp.toPx()
            drawCircle(
                Red,
                radius = 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
    }
}
