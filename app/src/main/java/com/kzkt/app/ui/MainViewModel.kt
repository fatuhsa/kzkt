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

    // Cancel flag
    private var _cancelled = false

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
        translationProgress.value = 0f
        translationTotal.value = selectedFiles.size
        translationDone.value = 0

        com.kzkt.app.core.TranslationService.startTranslation(getApplication(), selectedFiles.toList())
    }

    fun retryTranslation() {
        if (translationActive.value || selectedFiles.isEmpty()) return

        translationActive.value = true
        translationLog.add("[System] Retrying from last cached step...")
        com.kzkt.app.core.TranslationService.startTranslation(getApplication(), selectedFiles.toList(), retry = true)
    }

    fun cancelTranslation() {
        com.kzkt.app.core.TranslationService.cancelTranslation(getApplication())
        translationActive.value = false
        canRetry.value = com.kzkt.app.core.TranslationProgressTracker.cachedPageData != null
    }

    fun addFiles(paths: List<String>) {
        selectedFiles.clear()
        selectedFiles.addAll(paths)
        canRetry.value = false
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

    /** Persist one finished file (image or assembled PDF) into the Riwayat tab. */
    private fun recordHistory(fileName: String, outputPath: String, pageCount: Int) {
        val s = settings.value
        val entry = HistoryEntry(
            timestamp = System.currentTimeMillis(),
            fileName = fileName,
            outputPath = outputPath,
            pageCount = pageCount,
            provider = s.llmProvider,
            targetLanguage = s.targetLanguage,
        )
        viewModelScope.launch(Dispatchers.IO) { historyRepo.record(entry) }
    }

    override fun onCleared() {
        super.onCleared()
        yolo?.close()
        com.kzkt.app.core.TranslationProgressTracker.clearCache()
    }

    // ── Custom provider model auto-detect ──

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
                val response = httpClient.newCall(requestBuilder.build()).execute()
                val body = response.body?.string() ?: ""

                val json = JsonParser.parseString(body).asJsonObject
                val models = mutableListOf<String>()
                val data = json.getAsJsonArray("data") ?: json.getAsJsonArray("models")
                if (data != null) {
                    for (elem in data) {
                        val obj = elem.asJsonObject
                        val id = obj.get("id")?.asString ?: obj.get("name")?.asString?.removePrefix("models/")
                        if (id != null) models.add(id)
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
