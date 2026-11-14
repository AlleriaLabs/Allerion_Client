package com.example.servermonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Pure formatters shared by the network dashboard and unit tests. */
fun formatNetworkRate(bytesPerSecond: Double): String {
    val safe = bytesPerSecond.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    return when {
        safe >= 1_000_000.0 -> "%.2f MB/s".format(safe / 1_000_000.0)
        safe >= 1_000.0 -> "%.1f KB/s".format(safe / 1_000.0)
        else -> "%.0f B/s".format(safe)
    }
}

fun networkTrafficFresh(lastPacketAgeMs: Long?): Boolean =
    lastPacketAgeMs != null && lastPacketAgeMs in 0..5_000

private data class TrafficPoint(val rx: Double, val tx: Double)

@Composable
fun NetworkPerformancePanel(server: Server, enabled: Boolean) {
    var sample by remember(server.url, server.token) { mutableStateOf<JSONObject?>(null) }
    var failure by remember(server.url, server.token) { mutableStateOf<String?>(null) }
    var history by remember(server.url, server.token) { mutableStateOf<List<TrafficPoint>>(emptyList()) }

    LaunchedEffect(server.url, server.token, enabled) {
        if (!enabled) {
            sample = null
            failure = "Live capture is disabled on this backend."
            history = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            try {
                val next = withContext(Dispatchers.IO) {
                    ApiClient.getJson(server.url, server.token, "/api/network/traffic",
                        connectTimeoutMs = 2_000, readTimeoutMs = 2_500)
                }
                sample = next
                failure = next.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (next.optBoolean("available")) {
                    val point = TrafficPoint(
                        next.optDouble("rx_bytes_per_sec", 0.0).coerceAtLeast(0.0),
                        next.optDouble("tx_bytes_per_sec", 0.0).coerceAtLeast(0.0)
                    )
                    history = (history + point).takeLast(30)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = friendlyError(error)
            }
            delay(2_000)
        }
    }

    val current = sample
    val available = current?.optBoolean("available") == true
    val age = current?.let { if (it.isNull("last_packet_age_ms")) null else it.optLong("last_packet_age_ms") }
    val active = available && networkTrafficFresh(age)
    val rx = current?.optDouble("rx_bytes_per_sec", 0.0) ?: 0.0
    val tx = current?.optDouble("tx_bytes_per_sec", 0.0) ?: 0.0
    val peers = current?.optJSONArray("recent_peers")

    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Network performance")
                Text("Live interface activity", color = Txt, fontSize = AppType.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background((if (active) Green else Dim).copy(alpha = 0.13f))
                .padding(horizontal = 9.dp, vertical = 5.dp)) {
                Text(if (active) "LIVE" else if (available) "QUIET" else "UNAVAILABLE",
                    color = if (active) Green else Mut, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TrafficMetric("DOWNLOAD", formatNetworkRate(rx), Blue, Modifier.weight(1f))
            TrafficMetric("UPLOAD", formatNetworkRate(tx), Amber, Modifier.weight(1f))
            TrafficMetric("PEERS", (peers?.length() ?: 0).toString(), Green, Modifier.weight(0.72f))
        }
        Spacer(Modifier.height(14.dp))
        Text("RECENT THROUGHPUT · 60 SECONDS", color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(10.dp)).background(Surf2).padding(8.dp)) {
            val maxValue = (history.maxOfOrNull { maxOf(it.rx, it.tx) } ?: 0.0).coerceAtLeast(1.0)
            fun drawSeries(selector: (TrafficPoint) -> Double, color: Color) {
                if (history.size < 2) return
                val step = size.width / (history.size - 1).toFloat()
                val points = history.mapIndexed { index, point ->
                    Offset(index * step, size.height - (selector(point) / maxValue).toFloat() * size.height)
                }
                for (index in 0 until points.lastIndex) drawLine(color.copy(alpha = 0.9f), points[index], points[index + 1], 2.4f)
            }
            drawSeries({ it.rx }, Blue)
            drawSeries({ it.tx }, Amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Blue)); Spacer(Modifier.width(5.dp))
                Text("Download", color = Mut, fontSize = AppType.caption)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Amber)); Spacer(Modifier.width(5.dp))
                Text("Upload", color = Mut, fontSize = AppType.caption)
            }
        }
        Spacer(Modifier.height(10.dp))
        DiagRow("Interface", current?.optString("interface").orEmpty().ifBlank { "Unavailable" }, Mut)
        DiagRow("Packets observed", current?.optLong("packet_count")?.toString() ?: "—", Mut)
        DiagRow("Last packet", age?.let { if (it < 1_000) "Just now" else "${it / 1_000}s ago" } ?: "No recent packet", if (active) Green else Dim)
        if (failure != null) Text(failure!!, color = Amber, fontSize = AppType.caption)
        if (!enabled) Text("Enable ENABLE_LAN_DISCOVERY=1 and grant NET_RAW to the monitoring container to collect interface packet metadata.", color = Dim, fontSize = AppType.caption)
        Text("Scope: packets visible to the monitoring server's interface only. Switched traffic elsewhere on the LAN is not observable without gateway capture or a mirrored switch port. Packet payloads are never collected.",
            color = Dim, fontSize = AppType.caption)
        if (peers != null && peers.length() > 0) {
            Spacer(Modifier.height(8.dp))
            Label("Recent peers")
            for (index in 0 until minOf(peers.length(), 6)) {
                val peer = peers.optJSONObject(index) ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.optString("ip", "Unknown peer"), color = Txt, fontSize = AppType.small, fontWeight = FontWeight.Medium)
                        Text("${peer.optInt("packets")} packets · seen recently", color = Dim, fontSize = AppType.caption)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("↓ ${formatNetworkRate(peer.optDouble("rx_bytes", 0.0) / 3.0)}", color = Blue, fontSize = AppType.caption)
                        Text("↑ ${formatNetworkRate(peer.optDouble("tx_bytes", 0.0) / 3.0)}", color = Amber, fontSize = AppType.caption)
                    }
                }
            }
        } else {
            Text("Peer activity appears when packets are observed on this interface.", color = Dim, fontSize = AppType.caption)
        }
    }
}

@Composable
private fun TrafficMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Surf2).padding(10.dp)) {
        Text(label, color = Dim, fontSize = AppType.micro, fontWeight = FontWeight.Bold)
        Text(value, color = tint, fontSize = AppType.body, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
