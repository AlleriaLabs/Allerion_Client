package com.example.servermonitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
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

    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        title = { Text("ALLERION update available") },
        text = {
            Column {
                Text("Version ${update.versionName} is available.")
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(update.notes.take(900), style = MaterialTheme.typography.bodySmall)
                }
                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(progress = progress / 100f)
                    Text("Downloading… $progress%")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
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
            }) { Text(if (busy) "Downloading…" else "Update") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onLater) { Text("Later") }
        }
    )
}
