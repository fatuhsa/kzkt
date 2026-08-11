package com.kzkt.app.ui

import android.app.Application
import com.kzkt.app.KzktApplication
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kzkt.app.core.Config
import com.kzkt.app.core.TextRenderer
import com.kzkt.app.core.TranslationPipeline
import com.kzkt.app.core.YoloOnnx
import com.kzkt.app.data.HistoryEntry
import com.kzkt.app.data.HistoryRepository
import com.kzkt.app.data.SettingsRepository
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settingsRepo = SettingsRepository(application)
    val historyRepo = HistoryRepository(application)
    val glossaryRepo = com.kzkt.app.data.GlossaryRepository(application)

    // Observable state — all writes happen on the Main thread (see [post]).
    val settings = mutableStateOf(SettingsRepository.Settings())

    // History entries — hoisted from the cold repo flow into an in-memory
    // StateFlow so each History-tab open replays cached state instead of
    // re-parsing the full JSON from DataStore (fixes tab-open frame drop).
    val historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    // File processing
    val selectedFiles = mutableStateListOf<String>()
    val translationLog = mutableStateListOf<String>()
    val translationActive = mutableStateOf(false)
    val translationProgress = mutableStateOf(0f)
    val translationTotal = mutableStateOf(0)
    val translationDone = mutableStateOf(0)
    val canRetry = mutableStateOf(false)
    // Per-file batch status: path -> "processing" / "done" / "failed" (feature 1.35.0).
    val pageStatus = mutableStateMapOf<String, String>()

    // Result
    val resultPaths = mutableStateListOf<String>()
    val currentPreviewPath = mutableStateOf<String?>(null)
    val lastResultForEditing = mutableStateOf<TranslationPipeline.PipelineResult?>(null)
    val showInteractiveEditor = mutableStateOf(false)

    // YOLO model state
    val yoloReady = mutableStateOf(false)
    val yoloError = mutableStateOf<String?>(null)

    // Provider model state
    val providerModels = mutableStateMapOf<String, List<String>>()
    val modelsLoading = mutableStateOf(false)

    // Provider health-check state ("Test API Key")
    data class ProviderTestState(val loading: Boolean = false, val ok: Boolean? = null, val message: String = "")
    val providerTestState = mutableStateOf<ProviderTestState?>(null)

    // ── Self-update state (GitHub Releases) ──
    data class UpdateUiState(
        val checking: Boolean = false,
        val info: com.kzkt.app.core.UpdateManager.UpdateInfo? = null,
        val downloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val downloadSpeedBps: Long = 0L,
        val error: String? = null,
        val upToDate: Boolean = false,
    )
    val updateState = mutableStateOf(UpdateUiState())
    private var autoCheckDone = false
    // Guards against concurrent checks. Can't rely on `updateState.checking` for
    // this because background checks never set it (that flag only shows the
    // dialog for manual checks). Volatile: written on the IO thread (finally)
    // and read on the Main thread — same pattern as UpdateManager.updateChannelReady.
    @Volatile
    private var checkInProgress = false

    private var yolo: YoloOnnx? = null
    private var textRenderer: TextRenderer? = null

    private var pipelineLaunched = false

    /**
     * Compose snapshot state is not thread-safe: it must only be written on the
     * Main thread. [post] marshals every state write from a background thread
     * onto the Main dispatcher (this was a source of UI jank — F1).
     */
    private fun post(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    init {
        // Load settings
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                settings.value = s
            }
        }
        // Auto-check for updates once per app launch (only if the toggle is on).
        viewModelScope.launch {
            val autoCheck = settingsRepo.settingsFlow.first().autoCheckUpdates
            if (autoCheck && !autoCheckDone) {
                autoCheckDone = true
                checkForUpdate(manual = false)
            }
        }
        // Mirror the foreground-service download into the update dialog state so
        // progress (percent + size + speed) keeps updating while the app is open.
        viewModelScope.launch {
            com.kzkt.app.core.UpdateDownloadTracker.events.collect { event ->
                when (event) {
                    is com.kzkt.app.core.UpdateDownloadTracker.Event.Progress -> {
                        val s = updateState.value
                        updateState.value = s.copy(
                            downloading = true,
                            downloadProgress = event.progress.fraction,
                            downloadedBytes = event.progress.downloadedBytes,
                            totalBytes = event.progress.totalBytes,
                            downloadSpeedBps = event.progress.speedBytesPerSec,
                        )
                    }
                    com.kzkt.app.core.UpdateDownloadTracker.Event.Completed -> updateState.value = UpdateUiState()
                    com.kzkt.app.core.UpdateDownloadTracker.Event.Cancelled -> updateState.value = UpdateUiState()
                    is com.kzkt.app.core.UpdateDownloadTracker.Event.Failed -> updateState.value = updateState.value.copy(
                        downloading = false,
                        error = event.message,
                    )
                }
            }
        }
        // Hoist history into memory: parse the JSON once, then replay on every
        // History-tab open instead of re-reading DataStore + re-parsing (F3).
        viewModelScope.launch {
            historyRepo.entriesFlow.collect { historyEntries.value = it }
        }
        // Observe background service progress flow. Events are coalesced into
        // short batches (max ~30 Hz) and applied to the UI in a single Main-thread
        // pass instead of launching one coroutine per event — bursts of log/progress
        // events used to flood the main queue and drop frames while the user
        // scrolled History/Settings during an active background translation.
        viewModelScope.launch {
            val pendingLogs = mutableListOf<String>()
            val pendingStatus = mutableMapOf<String, String>()
            var pendingProgress: com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Progress? = null
            // Keep a list (not a single latest value): a multi-file batch can emit
            // several ResultPath events within one coalesce window, and dropping all
            // but the last would silently remove finished files from the UI list.
            val pendingResults = mutableListOf<com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.ResultPath>()
            var pendingCompleted = false
            var pendingError: String? = null
            var flushJob: kotlinx.coroutines.Job? = null

            fun scheduleFlush() {
                if (flushJob?.isActive == true) return
                flushJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(33) // coalesce window (~30 Hz max UI updates)
                    val logs = pendingLogs.toList()
                    val progress = pendingProgress
                    val results = pendingResults.toList()
                    val completed = pendingCompleted
                    val error = pendingError
                    pendingLogs.clear()
                    pendingProgress = null
                    pendingResults.clear()
                    pendingCompleted = false
                    pendingError = null
                    // Restore the touch-up editor data (bubbles + translations) for the
                    // latest finished file — decoded off the main thread, then handed to
                    // the UI so the reader's pencil button works for fresh results too.
                    val latestResult = results.lastOrNull()
                    val editMeta = latestResult?.let { r ->
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.kzkt.app.data.EditMetadataRepository(getApplication()).loadForOutput(r.path)
                        }
                    }
                    post {
                        if (logs.isNotEmpty()) translationLog.addAll(logs)
                        if (pendingStatus.isNotEmpty()) pageStatus.putAll(pendingStatus)
                        if (progress != null) {
                            translationProgress.value = progress.done.toFloat() / maxOf(1, progress.total)
                            translationDone.value = progress.done
                            translationTotal.value = progress.total
                            translationActive.value = progress.done < progress.total
                        }
                        for (result in results) {
                            if (!resultPaths.contains(result.path)) {
                                resultPaths.add(result.path)
                            }
                            currentPreviewPath.value = result.path
                        }
                        if (editMeta != null && latestResult != null) {
                            lastResultForEditing.value = TranslationPipeline.PipelineResult(
                                outputPath = latestResult.path,
                                originalBitmap = editMeta.originalBitmap,
                                translations = editMeta.translations,
                                coordinateMap = editMeta.coordinateMap,
                            )
                        }
                        if (completed) {
                            translationActive.value = false
                            canRetry.value = false
                        }
                        if (error != null) {
                            translationLog.add("[!] Error: $error")
                            translationActive.value = false
                            canRetry.value = com.kzkt.app.core.TranslationProgressTracker.cachedPageData != null
                        }
                    }
                }
            }

            com.kzkt.app.core.TranslationProgressTracker.progressFlow.collect { event ->
                when (event) {
                    is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Log -> pendingLogs.add(event.message)
                    is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Progress -> pendingProgress = event
                    is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.ResultPath -> pendingResults.add(event)
                    is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.PageStatus -> pendingStatus[event.path] = event.state
                    com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Completed -> pendingCompleted = true
                    is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Error -> pendingError = event.error
                }
                scheduleFlush()
            }
        }
    }

    fun initialize(context: android.content.Context) {
        if (pipelineLaunched) return
        pipelineLaunched = true

        viewModelScope.launch(Dispatchers.IO) {
            val yInstance = KzktApplication.yolo ?: YoloOnnx(context).also {
                val ok = it.initialize()
                if (ok) {
                    KzktApplication.yolo = it
                }
            }
            val rInstance = KzktApplication.textRenderer ?: TextRenderer(context).also {
                KzktApplication.textRenderer = it
            }
            yolo = yInstance
            textRenderer = rInstance

            post {
                if (KzktApplication.yolo != null) {
                    yoloReady.value = true
                    translationLog.add("YOLO model loaded successfully")
                } else {
                    yoloError.value = "Failed to load YOLO model"
                    translationLog.add("[!] Failed to load YOLO model")
                }
            }
        }
    }

    fun startTranslation() {
        if (translationActive.value || selectedFiles.isEmpty()) return

        translationActive.value = true
        canRetry.value = false
        com.kzkt.app.core.TranslationProgressTracker.clearCache()
        translationLog.clear()
        resultPaths.clear()
        pageStatus.clear()
        translationProgress.value = 0f
        translationTotal.value = selectedFiles.size
        translationDone.value = 0
        // Reset stale UI state from a previous run so the old preview/editor never
        // lingers while the new batch is starting.
        currentPreviewPath.value = null
        lastResultForEditing.value = null
        showInteractiveEditor.value = false

        com.kzkt.app.core.TranslationWorker.startTranslation(getApplication(), selectedFiles.toList())
    }

    /**
     * Re-run ONLY the files that failed in the last batch (status "failed"),
     * keeping the successfully translated ones untouched.
     */
    fun retryFailedPages() {
        val failed = selectedFiles.filter { pageStatus[it] == "failed" }
        if (failed.isEmpty() || translationActive.value) return

        translationActive.value = true
        canRetry.value = false
        com.kzkt.app.core.TranslationProgressTracker.clearCache()
        translationLog.clear()
        resultPaths.clear()
        pageStatus.clear()
        translationProgress.value = 0f
        translationTotal.value = failed.size
        translationDone.value = 0
        currentPreviewPath.value = null
        lastResultForEditing.value = null
        showInteractiveEditor.value = false
        translationLog.add("[System] Retrying ${failed.size} failed page(s)...")

        com.kzkt.app.core.TranslationWorker.startTranslation(getApplication(), failed)
    }

    /**
     * Retry a single failed History entry: re-enqueues its original source file.
     * The worker replaces the "failed" entry with a success entry when it lands.
     */
    fun retryHistoryEntry(entry: HistoryEntry) {
        if (translationActive.value || entry.inputPath.isBlank()) return
        val input = java.io.File(entry.inputPath)
        if (!input.exists()) {
            translationLog.add("[!] Cannot retry — source file is gone: ${input.name}")
            return
        }
        translationActive.value = true
        canRetry.value = false
        com.kzkt.app.core.TranslationProgressTracker.clearCache()
        translationLog.clear()
        resultPaths.clear()
        pageStatus.clear()
        translationProgress.value = 0f
        translationTotal.value = 1
        translationDone.value = 0
        currentPreviewPath.value = null
        lastResultForEditing.value = null
        showInteractiveEditor.value = false
        translationLog.add("[System] Retrying \"${entry.fileName}\" from History...")

        com.kzkt.app.core.TranslationWorker.startTranslation(getApplication(), listOf(entry.inputPath))
    }

    fun retryTranslation() {
        if (translationActive.value || selectedFiles.isEmpty()) return

        translationActive.value = true
        translationLog.add("[System] Retrying from last cached step...")
        com.kzkt.app.core.TranslationWorker.startTranslation(getApplication(), selectedFiles.toList(), retry = true)
    }

    fun cancelTranslation() {
        com.kzkt.app.core.TranslationWorker.cancelTranslation(getApplication())
        translationActive.value = false
        canRetry.value = com.kzkt.app.core.TranslationProgressTracker.cachedPageData != null
    }

    fun addFiles(paths: List<String>) {
        selectedFiles.clear()
        selectedFiles.addAll(paths)
        canRetry.value = false
        pageStatus.clear()
        com.kzkt.app.core.TranslationProgressTracker.clearCache()
    }

    /** Remove one entry from the Riwayat tab (and its persisted edit metadata). */
    fun deleteHistoryEntry(timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            historyEntries.value.find { it.timestamp == timestamp }?.let { entry ->
                com.kzkt.app.data.EditMetadataRepository(getApplication()).deleteForOutput(entry.outputPath)
            }
            historyRepo.delete(timestamp)
        }
    }

    /** Re-add a previously deleted entry (Snackbar Undo). */
    fun restoreHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) { historyRepo.record(entry) }
    }

    /** Clear the entire Riwayat tab (and its persisted edit metadata). */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyEntries.value.forEach { entry ->
                com.kzkt.app.data.EditMetadataRepository(getApplication()).deleteForOutput(entry.outputPath)
            }
            historyRepo.clear()
        }
    }

    /** Re-add a batch of entries (Clear-all Undo) in a single write. */
    fun restoreHistoryEntries(entries: List<HistoryEntry>) {
        viewModelScope.launch(Dispatchers.IO) { historyRepo.restoreAll(entries) }
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT close the shared YOLO session here (same reasoning as below) and do NOT
        // recycle the retry-cache bitmaps via clearCache(): the background TranslationWorker
        // may still be running (it lives in a foreground service that survives the activity),
        // and recycling bitmaps it is actively using would crash it with
        // "Canvas: trying to use a recycled bitmap". The worker clears the cache itself when
        // it finishes, and a new run clears it on start.
    }

    // ── Custom provider model auto-detect ──

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Health check: send one tiny text request through the provider to validate the
     * API key + model + base URL before a real batch. Reports the result in
     * [providerTestState] (shown inline in Settings).
     */
    fun testProviderConnection(providerKey: String, baseUrl: String, apiKey: String, model: String) {
        if (providerTestState.value?.loading == true) return
        providerTestState.value = ProviderTestState(loading = true)

        viewModelScope.launch(Dispatchers.IO) {
            val provider = com.kzkt.app.core.providers.ProviderFactory.create(
                providerKey, apiKey, model, baseUrl, settings.value.customTimeoutSec
            )
            if (provider == null) {
                post {
                    providerTestState.value = ProviderTestState(ok = false, message = "Unknown provider: $providerKey")
                }
                return@launch
            }
            try {
                val started = System.currentTimeMillis()
                val reply = provider.translateText("{}", "Reply with exactly: OK")
                val elapsed = System.currentTimeMillis() - started
                val ok = reply != null && reply.contains("OK", ignoreCase = true)
                post {
                    providerTestState.value = ProviderTestState(
                        ok = ok,
                        message = if (ok) "Connection OK (${elapsed}ms)" else "Provider answered, but unexpected reply."
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                post {
                    providerTestState.value = ProviderTestState(ok = false, message = "Failed: $msg")
                }
            }
        }
    }

    // ── Self-update ──

    /**
     * Check GitHub Releases for a newer version. When [manual] is true the dialog
     * stays open with "already up to date" / error state so the user sees
     * feedback; automatic checks silently no-op when nothing is new.
     */
    fun checkForUpdate(manual: Boolean) {
        if (checkInProgress || updateState.value.downloading) return
        checkInProgress = true

        // Only a manual check (Settings → Check for Updates) shows the spinner
        // dialog. The launch-time auto-check runs fully in the background and
        // never pops up unless an update is actually available.
        updateState.value = if (manual) UpdateUiState(checking = true) else UpdateUiState()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = com.kzkt.app.core.UpdateManager.checkForUpdate()) {
                    is com.kzkt.app.core.UpdateManager.CheckResult.Available -> {
                        // Pop-up — the only case a background check surfaces UI.
                        post { updateState.value = UpdateUiState(info = result.info) }
                    }
                    com.kzkt.app.core.UpdateManager.CheckResult.UpToDate -> {
                        post {
                            updateState.value = if (manual) UpdateUiState(upToDate = true) else UpdateUiState()
                        }
                    }
                    is com.kzkt.app.core.UpdateManager.CheckResult.Failed -> {
                        post {
                            updateState.value = if (manual) {
                                UpdateUiState(error = "Could not check for updates: ${result.message}")
                            } else {
                                UpdateUiState() // background failures stay silent
                            }
                        }
                    }
                }
            } finally {
                checkInProgress = false
            }
        }
    }

    /**
     * Start the update download. The actual download runs in a foreground service
     * ([UpdateDownloadService]) so it survives backgrounding and process death;
     * progress is mirrored back here through [UpdateDownloadTracker].
     */
    fun downloadAndInstallUpdate() {
        val info = updateState.value.info ?: return
        updateState.value = updateState.value.copy(downloading = true, downloadProgress = 0f)
        com.kzkt.app.core.UpdateDownloadService.start(getApplication(), info)
    }

    /** Dismiss the update dialog and clear transient state. */
    fun dismissUpdateDialog() {
        updateState.value = UpdateUiState()
    }

    fun fetchModelsForProvider(providerKey: String, baseUrl: String, apiKey: String) {
        val meta = Config.PROVIDER_REGISTRY[providerKey]
        val rawUrl = if (baseUrl.isNotBlank()) baseUrl else (meta?.defaultBaseUrl ?: "")
        if (rawUrl.isBlank()) {
            translationLog.add("[!] Base URL is empty for $providerKey")
            return
        }

        modelsLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var normalized = rawUrl.trimEnd('/')
                if (normalized.endsWith("/chat/completions")) normalized = normalized.removeSuffix("/chat/completions")
                if (normalized.endsWith("/v1")) normalized = normalized.removeSuffix("/v1")
                val endpoint = if (providerKey == "gemini") {
                    val base = if (normalized.endsWith("/v1beta")) normalized else "$normalized/v1beta"
                    "$base/models"
                } else {
                    "$normalized/v1/models"
                }

                val requestBuilder = Request.Builder().url(endpoint)
                if (apiKey.isNotBlank()) {
                    if (providerKey == "gemini") {
                        requestBuilder.header("x-goog-api-key", apiKey)
                    } else {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                }
                val models = mutableListOf<String>()
                // use{} closes the response so the pooled connection is released promptly.
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    val json = JsonParser.parseString(body).asJsonObject
                    val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("models")
                    if (data != null) {
                        for (elem in data) {
                            val obj = elem.asJsonObject
                            val id = obj.get("id")?.asString ?: obj.get("name")?.asString?.removePrefix("models/")
                            if (id != null) models.add(id)
                        }
                    }
                }

                post {
                    if (models.isNotEmpty()) {
                        val sorted = models.sorted()
                        providerModels[providerKey] = sorted
                        translationLog.add("Found ${models.size} models for ${meta?.displayName ?: providerKey}")
                    } else {
                        translationLog.add("[!] No models found at $endpoint")
                    }
                    modelsLoading.value = false
                }
            } catch (e: Exception) {
                post {
                    translationLog.add("[!] Failed to fetch models for $providerKey: ${e.message}")
                    modelsLoading.value = false
                }
            }
        }
    }

}
