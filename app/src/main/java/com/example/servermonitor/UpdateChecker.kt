package com.example.servermonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit


data class AllerionUpdate(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val notes: String
)

object UpdateChecker {
    private const val RELEASE_API =
        "https://api.github.com/repos/AlleriaLabs/Allerion_Client/releases/latest"
    private const val PREFS = "allerion_updates"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun check(context: Context): AllerionUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Allerion-Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val release = JSONObject(response.body?.string().orEmpty())
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
            val tag = release.optString("tag_name").trim()
            val version = tag.removePrefix("v").removePrefix("V")
            if (tag.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
            val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("dismissed_tag", null)
            if (dismissed == tag) return@withContext null

            val assets = release.optJSONArray("assets") ?: return@withContext null
            var apk: String? = null
            var checksum: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://")) continue
                when (name) {
                    "Allerion.apk" -> apk = url
                    "Allerion.apk.sha256" -> checksum = url
                }
            }
            val apkUrl = apk ?: return@withContext null
            val checksumUrl = checksum ?: return@withContext null
            AllerionUpdate(tag, version, apkUrl, checksumUrl, release.optString("body").trim())
        }
    }

    fun dismiss(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("dismissed_tag", tag).apply()
    }

    suspend fun downloadAndInstall(
        context: Context,
        update: AllerionUpdate,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            error("Allow Allerion to install updates, then tap Update again.")
        }

        val expected = http.newCall(Request.Builder().url(update.checksumUrl)
            .header("User-Agent", "Allerion-Android").build()).execute().use { response ->
            if (!response.isSuccessful) error("Could not retrieve the release checksum.")
            Regex("(?i)^([a-f0-9]{64})").find(response.body?.string().orEmpty())?.groupValues?.get(1)
                ?: error("The release checksum file is invalid.")
        }

        val apkFile = File(context.cacheDir, "Allerion-update.apk")
        http.newCall(Request.Builder().url(update.apkUrl)
            .header("User-Agent", "Allerion-Android").build()).execute().use { response ->
            if (!response.isSuccessful) error("APK download failed (HTTP ${response.code}).")
            val body = response.body ?: error("GitHub returned an empty APK.")
            val total = body.contentLength()
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
        }

        val actual = sha256(apkFile)
        if (!actual.equals(expected, ignoreCase = true)) {
            apkFile.delete()
            error("APK checksum verification failed. The downloaded file was deleted.")
        }
        if (!isCompatibleApk(context, apkFile)) {
            apkFile.delete()
            error("This APK is not compatible with this Allerion installation or has a different signing certificate. Your installed app was not changed.")
        }

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun isCompatibleApk(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (candidate.packageName != context.packageName) return false

        fun signerDigests(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            else info.signatures?.toList().orEmpty()
            return signatures.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.toSet()
        }
        val current = signerDigests(installed)
        current.isNotEmpty() && current == signerDigests(candidate)
    }.getOrDefault(false)

    private fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value)
            .mapNotNull { it.value.toLongOrNull() }.toList()
        val a = parts(candidate)
        val b = parts(installed)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0L }
            val right = b.getOrElse(i) { 0L }
            if (left != right) return left > right
        }
        return false
    }
}

@Composable
fun AllerionUpdateDialog(update: AllerionUpdate, onLater: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!busy) onLater() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF20253B), Color(0xFF121622), Color(0xFF10131D))
                    )
                )
                .border(1.dp, Color(0xFF59658A).copy(alpha = 0.55f), RoundedCornerShape(30.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF8B7CFF), Color(0xFF4BC7D8)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✦", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("ALLERION", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("SYSTEM UPDATE", color = Color(0xFF9DA9C8), fontSize = 9.sp, letterSpacing = 1.8.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF293D48))
                        .border(1.dp, Color(0xFF4BC7D8).copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text("NEW VERSION", color = Color(0xFF8DE8E8), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A better experience\njust arrived.", color = Color.White, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Text("Your Allerion update is ready to install.", color = Color(0xFFADB6CC), fontSize = 13.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF252C40))
                    .border(1.dp, Color(0xFF64739B).copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LATEST RELEASE", color = Color(0xFF9DA9C8), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version ${update.versionName}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                Text("↗", color = Color(0xFF79DCE0), fontSize = 30.sp, fontWeight = FontWeight.Light)
            }

            if (update.notes.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WHAT'S NEW", color = Color(0xFF9DA9C8), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        update.notes.take(900),
                        color = Color(0xFFD7DCEF),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        maxLines = 7,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (busy) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloading and verifying", color = Color(0xFFD7DCEF), fontSize = 12.sp)
                        Text("$progress%", color = Color(0xFF8DE8E8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
                        color = Color(0xFF65D4D8),
                        trackColor = Color(0xFF394158)
                    )
                    Text("The APK will be verified before Android opens the installer.", color = Color(0xFF9DA9C8), fontSize = 10.sp)
                }
            }

            error?.let {
                Text(it, color = Color(0xFFFF929D), fontSize = 11.sp, lineHeight = 16.sp)
            }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    progress = 0
                    scope.launch {
                        try {
                            UpdateChecker.downloadAndInstall(context, update) { progress = it }
                        } catch (e: Exception) {
                            error = e.message ?: "Update could not be installed."
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6D7CFF),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF394158),
                    disabledContentColor = Color(0xFF9DA9C8)
                )
            ) {
                Text(if (busy) "PREPARING UPDATE…" else "DOWNLOAD & INSTALL", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            androidx.compose.material3.TextButton(
                enabled = !busy,
                onClick = onLater,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Maybe later", color = Color(0xFFADB6CC), fontSize = 12.sp)
            }
        }
    }
}
