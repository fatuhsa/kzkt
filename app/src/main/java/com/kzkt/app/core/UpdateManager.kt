package com.kzkt.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.kzkt.app.BuildConfig
import com.kzkt.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Download progress with live throughput, so the UI can show speed + ETA
     * instead of a percent that barely moves on slow CDNs.
     */
    data class DownloadProgress(
        val fraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
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

    // Separate client for the ~110 MB payload: the short 15s read timeout above
    // aborts mid-download on flaky networks (any stall >15s = restart from 0).
    // Downloads tolerate much longer gaps, especially combined with resume.
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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

    /**
     * Download the update APK to cacheDir with resume + live speed reporting.
     *
     * Resume: the partial file is kept under a stable name (kzkt-update-<version>.apk)
     * across attempts. If it already exists, the server is asked for the remaining
     * bytes via `Range`; a 206 response continues where we left off, a 200 means the
     * server ignored the range and we restart from scratch. On failure the partial
     * file is left in place so the next attempt resumes instead of restarting.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
        onCallCreated: (Call) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // Clear partials from older versions (they can never be resumed) but keep
        // ours — it may already hold most of the file.
        sweepStalePartials(context, info.version)

        val file = File(context.cacheDir, "kzkt-update-${info.version}.apk")
        val existing = if (file.exists()) file.length() else 0L

        // Fast path: the file is already complete (e.g. a previous attempt finished
        // downloading but was interrupted before install).
        if (existing > 0 && info.apkSizeBytes > 0 && existing >= info.apkSizeBytes) {
            onProgress(DownloadProgress(1f, existing, existing, 0L))
            return@withContext file
        }

        val requestBuilder = Request.Builder().url(info.apkUrl)
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")

        val call = downloadClient.newCall(requestBuilder.build())
        onCallCreated(call)

        val startNanos = System.nanoTime()
        var lastReportNanos = startNanos
        var lastReportedPct = -1

        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }
            val resume = response.code == 206
            val body = response.body ?: throw IOException("Empty response body")
            val remaining = body.contentLength()      // 206: bytes left; 200: full size
            // `base` = bytes already on disk before this session — 0 when the
            // server ignored our Range and restarted the download from scratch
            // (the partial was wiped below), existing when we truly resume.
            val base = if (resume) existing else 0L
            val total = if (remaining > 0) base + remaining else info.apkSizeBytes

            if (!resume && existing > 0) file.delete() // server restarted us — wipe partial

            FileOutputStream(file, resume).use { output ->
                val buffer = ByteArray(64 * 1024)
                val input = body.byteStream()
                var session = 0L
                while (true) {
                    if (isCancelled()) throw CancellationException("Download cancelled")
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    session += read

                    val downloadedTotal = base + session
                    val fraction = if (total > 0) (downloadedTotal.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val now = System.nanoTime()
                    val elapsedSec = (now - startNanos) / 1_000_000_000.0
                    val speed = if (elapsedSec > 0.5) (session / elapsedSec).toLong() else 0L
                    val pct = (fraction * 100).toInt()

                    // Report on percent change OR every ~500ms — on a slow CDN a single
                    // percent can take seconds, so the speed/ETA text keeps refreshing
                    // even while the percent bar is stationary.
                    if (pct > lastReportedPct || now - lastReportNanos > 500_000_000L) {
                        lastReportedPct = pct
                        lastReportNanos = now
                        onProgress(DownloadProgress(fraction, downloadedTotal, total, speed))
                    }
                }
            }
            if (total > 0) onProgress(DownloadProgress(1f, total, total, 0L))
            file
        }
    }

    private fun sweepStalePartials(context: Context, currentVersion: String) {
        try {
            context.cacheDir.listFiles { f ->
                f.name.startsWith("kzkt-update-") && f.name != "kzkt-update-$currentVersion.apk"
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * [downloadApk] with automatic retry + backoff; every attempt resumes the
     * partial file, so an interrupted download continues instead of restarting.
     */
    suspend fun downloadApkWithRetry(
        context: Context,
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean = { false },
        onCallCreated: (Call) -> Unit = {},
    ): File {
        var lastError: Exception? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return downloadApk(context, info, onProgress, isCancelled, onCallCreated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (isCancelled()) throw CancellationException("Download cancelled")
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MS * (attempt + 1)) // 2s, then 4s
                }
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    // ── Download-progress notification ────────────────────────────────
    private const val UPDATE_CHANNEL_ID = "kzkt_update_download"
    const val UPDATE_NOTIFICATION_ID = 2001

    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 2000L

    private fun notifManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notifEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Created at most once per process — progress ticks (~2/s) must not
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

    /** Build the download notification — also used by the service for startForeground. */
    fun buildDownloadNotification(context: Context, info: UpdateInfo, progress: DownloadProgress?): Notification {
        ensureUpdateChannel(context)
        val pct = if (progress != null) (progress.fraction.coerceIn(0f, 1f) * 100).toInt() else 0
        val contentText = if (progress != null) {
            val doneMb = progress.downloadedBytes / 1048576f
            val totalMb = maxOf(1, progress.totalBytes) / 1048576f
            val speed = if (progress.speedBytesPerSec > 0) " · ${formatMbps(progress.speedBytesPerSec)}" else ""
            "%.1f / %.1f MB%s".format(doneMb, totalMb, speed)
        } else {
            info.apkFileName
        }
        val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(context, UpdateDownloadService::class.java).apply {
            action = UpdateDownloadService.ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            context, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading KZKT ${info.version}…")
            .setContentText(contentText)
            .setSubText("$pct%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun formatMbps(bytesPerSec: Long): String =
        "%.1f".format(bytesPerSec / 1048576f) + " MB/s"

    /** Show (or update) the download-progress notification. No-ops when notifications are disabled. */
    fun showDownloadNotification(context: Context, info: UpdateInfo, progress: DownloadProgress? = null) {
        if (!notifEnabled(context)) return
        notifManager(context).notify(UPDATE_NOTIFICATION_ID, buildDownloadNotification(context, info, progress))
    }

    /** Alias with clearer intent at call sites that are mid-download. */
    fun updateDownloadNotification(context: Context, info: UpdateInfo, progress: DownloadProgress) =
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
