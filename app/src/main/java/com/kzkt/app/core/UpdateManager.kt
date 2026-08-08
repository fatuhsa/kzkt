package com.kzkt.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.kzkt.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update via GitHub Releases.
 *
 * Flow: check [checkForUpdate] → if a newer release exists pick the APK that
 * matches the device ABI (fallback: universal) → download with progress →
 * [installApk] hands it to the system installer via FileProvider.
 *
 * The repo is public, so the releases/latest endpoint needs no auth and is free.
 */
object UpdateManager {

    private const val REPO = "kouzen-neo/kzkt"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Metadata about the newest available release (only when it is newer). */
    data class UpdateInfo(
        val version: String,          // "1.25.2" (tag without leading 'v')
        val apkFileName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseNotes: String,
        val publishedAt: String,
    )

    /** Result of a version check — distinguishes "no update" from "check failed". */
    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Query the GitHub "latest release" endpoint and compare its tag with the
     * currently installed version. Never throws — network/parse failures are
     * reported as [CheckResult.Failed] so the UI can tell them apart from
     * "already up to date".
     */
    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KZKT-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Failed("GitHub responded with HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext CheckResult.Failed("Empty response from GitHub")
                val json = JSONObject(body)

                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank()) return@withContext CheckResult.Failed("Release has no version tag")

                val current = parseVersion(BuildConfig.VERSION_NAME)
                val remote = parseVersion(tag)
                if (compareVersions(current, remote) >= 0) return@withContext CheckResult.UpToDate

                val apk = pickApkAsset(json.optJSONArray("assets"), tag)
                    ?: return@withContext CheckResult.Failed("No APK asset found in the release")

                CheckResult.Available(
                    UpdateInfo(
                        version = tag,
                        apkFileName = apk.optString("name", "KZKT-$tag.apk"),
                        apkUrl = apk.optString("browser_download_url"),
                        apkSizeBytes = apk.optLong("size", 0L),
                        releaseNotes = json.optString("body").trim().take(1500),
                        publishedAt = json.optString("published_at"),
                    )
                )
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Could not reach GitHub")
        }
    }

    /**
     * Pick the APK that matches this device's primary ABI, preferring the
     * ABI-specific build over the universal one. Asset names produced by the CI
     * follow the pattern: KZKT-<abi>-<version>.apk / KZKT-<version>.apk.
     */
    private fun pickApkAsset(assetsJson: org.json.JSONArray?, version: String): JSONObject? {
        if (assetsJson == null) return null
        val assets = (0 until assetsJson.length()).mapNotNull { i -> assetsJson.optJSONObject(i) }
        if (assets.isEmpty()) return null

        // Exact version match first — a stale release can carry multiple assets.
        val versioned = assets.filter { it.optString("name").contains("-$version.apk") }
        val pool = versioned.ifEmpty { assets }

        val primaryAbi = primaryAbi()
        val exact = pool.firstOrNull { asset ->
            val name = asset.optString("name")
            primaryAbi != null && name.contains("-$primaryAbi-")
        }
        if (exact != null) return exact

        // Fall back to the universal build (no ABI segment in the name).
        val abis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        return pool.firstOrNull { asset ->
            val name = asset.optString("name")
            name.endsWith(".apk") && abis.none { name.contains("-$it-") }
        }
    }

    /** The first supported ABI in priority order (same order the CI splits use). */
    private fun primaryAbi(): String? {
        val priority = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return Build.SUPPORTED_ABIS.firstOrNull { it in priority }
    }

    /** Download the update APK to cacheDir, reporting 0f..1f progress. */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(info.apkUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            val file = File(context.cacheDir, "kzkt-update-${System.currentTimeMillis()}.apk")

            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // Throttle progress callbacks to ~1% granularity so we don't
                        // flood the main thread with thousands of updates.
                        if (total > 0) {
                            val pct = downloaded * 100 / total
                            if (pct > lastReported) {
                                lastReported = pct
                                onProgress(downloaded.toFloat() / total)
                            }
                        }
                    }
                }
            }
            onProgress(1f)
            file
        }
    }

    // ── Download-progress notification ────────────────────────────────
    private const val UPDATE_CHANNEL_ID = "kzkt_update_download"
    private const val UPDATE_NOTIFICATION_ID = 2001

    private fun notifManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Created at most once per process — progress ticks (~100/s) must not
    // hammer createNotificationChannel (a binder call) on every update.
    @Volatile
    private var updateChannelReady = false

    private fun ensureUpdateChannel(context: Context) {
        if (updateChannelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "KZKT Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows update download progress" }
            notifManager(context).createNotificationChannel(channel)
        }
        updateChannelReady = true
    }

    /**
     * Show (or update) the download-progress notification for the in-flight
     * update. No-ops silently when notifications are disabled/not granted.
     */
    fun showDownloadNotification(context: Context, info: UpdateInfo, progress: Float = 0f) {
        if (!notifEnabled(context)) return
        ensureUpdateChannel(context)
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading KZKT ${info.version}…")
            .setContentText(info.apkFileName)
            .setSubText("$pct%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .build()
        notifManager(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    /** Alias with clearer intent at call sites that are mid-download. */
    fun updateDownloadNotification(context: Context, info: UpdateInfo, progress: Float) =
        showDownloadNotification(context, info, progress)

    /** Remove the download notification (after install is handed to the system). */
    fun cancelDownloadNotification(context: Context) {
        notifManager(context).cancel(UPDATE_NOTIFICATION_ID)
    }

    /** Open the system installer for the downloaded APK via FileProvider. */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Parse "1.25.2" / "v1.25.2" / "1.25.1.22" into comparable numeric segments. */
    private fun parseVersion(version: String): List<Int> =
        version.trim().trimStart('v')
            .split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }
            .take(4)
            .ifEmpty { listOf(0) }

    /** Compare two parsed versions: <0 if a older, 0 equal, >0 if a newer. */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
