package com.example.servermonitor

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.ByteArrayOutputStream

fun buildMonitoringReport(sv: Server): String {
    val stats = httpJson(sv.url, sv.token, "/api/stats")
    val history = parseHistory(httpJson(sv.url, sv.token, "/api/history?range=7d"))
    val energy = runCatching {
        httpJson(sv.url, sv.token, "/api/energy").optJSONArray("days")
    }.getOrNull()
    fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    val out = StringBuilder()
    out.append("ALLERION monitoring report\n")
    out.append("Server,").append(csv(sv.name)).append("\n")
    out.append("Address,").append(csv(sv.url)).append("\n")
    out.append("Generated,").append(csv(java.time.Instant.now().toString())).append("\n")
    out.append("Current CPU %,").append(stats.optDouble("cpu")).append("\n")
    out.append("Current memory %,").append(stats.optDouble("ram")).append("\n")
    out.append("Current uptime seconds,").append(stats.optLong("uptime_s")).append("\n\n")
    out.append("Timestamp,CPU %,Memory %,Temperature C,Download Bps,Upload Bps,Power W,GPU %,Disk %\n")
    for (index in history.t.indices) {
        fun value(key: String): String = history.s[key]?.getOrNull(index)?.toString() ?: ""
        val timestamp = java.time.Instant.ofEpochSecond(history.t[index]).toString()
        out.append(csv(timestamp)).append(",")
            .append(value("cpu")).append(",").append(value("ram")).append(",")
            .append(value("temp")).append(",").append(value("down")).append(",")
            .append(value("up")).append(",").append(value("power")).append(",")
            .append(value("gpu")).append(",").append(value("disk")).append("\n")
    }
    out.append("\nDaily energy totals\nDay,kWh\n")
    if (energy != null) {
        for (index in 0 until energy.length()) {
            val day = energy.optJSONObject(index) ?: continue
            out.append(csv(day.optString("day"))).append(",")
                .append(day.optDouble("kwh", Double.NaN).takeUnless { it.isNaN() } ?: "").append("\n")
        }
    }
    return out.toString()
}

object PdfReport {
    fun render(title: String, content: String): ByteArray {
        val document = PdfDocument()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(35, 38, 43)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(190, 25, 42)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pageWidth = 595
        val pageHeight = 842
        val left = 36f
        val maxWidth = pageWidth - 72f
        val lineHeight = 14f
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun startPage() {
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val canvas = page!!.canvas
            canvas.drawText(title, left, 42f, heading)
            canvas.drawText("ALLERIA LABS  ·  Generated " + java.time.Instant.now().toString(), left, 60f, body)
            y = 88f
        }
        fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
        }
        fun drawLine(line: String) {
            val canvas = page!!.canvas
            val words = line.split(Regex("(?<=,)|(?<= )"))
            var current = ""
            for (word in words) {
                val candidate = current + word
                if (current.isNotEmpty() && body.measureText(candidate) > maxWidth) {
                    canvas.drawText(current, left, y, body)
                    y += lineHeight
                    current = word.trimStart()
                    if (y > pageHeight - 42f) { finishPage(); startPage() }
                } else current = candidate
            }
            if (current.isNotEmpty()) {
                page!!.canvas.drawText(current.take(180), left, y, body)
                y += lineHeight
            } else y += lineHeight
            if (y > pageHeight - 42f) { finishPage(); startPage() }
        }

        try {
            startPage()
            content.lineSequence().forEach { drawLine(it) }
            finishPage()
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }
}
