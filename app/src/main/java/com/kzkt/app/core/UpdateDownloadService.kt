package com.kzkt.app.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * Mirrors the in-flight update download (percent + size + speed) to the UI so
 * the update dialog keeps ticking while the app is open. The service emits into
 * this flow; MainViewModel collects it and updates its updateState.
 */
object UpdateDownloadTracker {

    sealed interface Event {
        data class Progress(val progress: UpdateManager.DownloadProgress) : Event
        data object Completed : Event
        data object Cancelled : Event
        data class Failed(val message: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}

/**
 * Foreground service that downloads the update APK in the background.
 *
 * Why a foreground service instead of a ViewModel coroutine: a ~110 MB download
 * from GitHub's CDN takes several minutes, and Android kills backgrounded
 * processes under memory pressure — the old approach aborted the download as
 * soon as the user left the app. An active foreground service keeps the process
 * alive, shows progress in the notification shade, and can hand the finished
 * APK to the system installer (activity starts are exempt from background
 * launch restrictions while a foreground service is running).
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    @Volatile
    private var currentCall: Call? = null

    companion object {
        const val ACTION_START = "com.kzkt.app.action.START_UPDATE_DOWNLOAD"
        const val ACTION_CANCEL = "com.kzkt.app.action.CANCEL_UPDATE_DOWNLOAD"

        private const val EXTRA_VERSION = "version"
        private const val EXTRA_APK_NAME = "apkName"
        private const val EXTRA_APK_URL = "apkUrl"
        private const val EXTRA_APK_SIZE = "apkSize"
        private const val EXTRA_NOTES = "notes"
        private const val EXTRA_PUBLISHED = "publishedAt"

        fun start(context: Context, info: UpdateManager.UpdateInfo) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VERSION, info.version)
                putExtra(EXTRA_APK_NAME, info.apkFileName)
                putExtra(EXTRA_APK_URL, info.apkUrl)
                putExtra(EXTRA_APK_SIZE, info.apkSizeBytes)
                putExtra(EXTRA_NOTES, info.releaseNotes)
                putExtra(EXTRA_PUBLISHED, info.publishedAt)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                // Interrupt the blocking socket read so the download coroutine
                // unwinds promptly instead of finishing the whole file.
                currentCall?.cancel()
                UpdateDownloadTracker.emit(UpdateDownloadTracker.Event.Cancelled)
                stopSelf()
            }
            ACTION_START -> {
                val info = intent.toUpdateInfo() ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Android 14+ requires an explicit foreground service type; dataSync
                // matches the manifest declaration + FOREGROUND_SERVICE_DATA_SYNC.
                ServiceCompat.startForeground(
                    this,
                    UpdateManager.UPDATE_NOTIFICATION_ID,
                    UpdateManager.buildDownloadNotification(this, info, null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                scope.launch { runDownload(info) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(info: UpdateManager.UpdateInfo) {
        try {
            val file = UpdateManager.downloadApkWithRetry(
                this, info,
                onProgress = { p ->
                    UpdateManager.updateDownloadNotification(this, info, p)
                    UpdateDownloadTracker.emit(UpdateDownloadTracker.Event.Progress(p))
                },
                isCancelled = { cancelled },
                onCallCreated = { currentCall = it },
            )
            UpdateManager.cancelDownloadNotification(this)
            UpdateDownloadTracker.emit(UpdateDownloadTracker.Event.Completed)
            UpdateManager.installApk(this, file)
            stopSelf()
        } catch (e: CancellationException) {
            // User cancel already emitted its event (or the process is being torn
            // down) — just clean up the notification, no error dialog.
            UpdateManager.cancelDownloadNotification(this)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            UpdateManager.cancelDownloadNotification(this)
            UpdateDownloadTracker.emit(UpdateDownloadTracker.Event.Failed("Download failed: $msg"))
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun Intent.toUpdateInfo(): UpdateManager.UpdateInfo? {
        val version = getStringExtra(EXTRA_VERSION) ?: return null
        val apkUrl = getStringExtra(EXTRA_APK_URL) ?: return null
        return UpdateManager.UpdateInfo(
            version = version,
            apkFileName = getStringExtra(EXTRA_APK_NAME) ?: "KZKT-$version.apk",
            apkUrl = apkUrl,
            apkSizeBytes = getLongExtra(EXTRA_APK_SIZE, 0L),
            releaseNotes = getStringExtra(EXTRA_NOTES) ?: "",
            publishedAt = getStringExtra(EXTRA_PUBLISHED) ?: "",
        )
    }
}
