package com.kzkt.app.ui

import android.app.Application
import com.kzkt.app.KzktApplication
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kzkt.app.core.Config
import com.kzkt.app.core.ImageProcessor
import com.kzkt.app.core.RateLimiter
import com.kzkt.app.core.TextRenderer
import com.kzkt.app.core.TranslationPipeline
import com.kzkt.app.core.YoloOnnx
import com.kzkt.app.core.providers.*
import com.kzkt.app.data.HistoryEntry
import com.kzkt.app.data.HistoryRepository
import com.kzkt.app.data.SettingsRepository
import com.kzkt.app.util.PdfExporter
import com.kzkt.app.util.PdfImporter
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

    // Custom provider model list (from /v1/models)
    val customModels = mutableStateListOf<String>()
    val customModelsLoading = mutableStateOf(false)

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
        // Observe background service progress flow
        viewModelScope.launch {
            com.kzkt.app.core.TranslationProgressTracker.progressFlow.collect { event ->
                post {
                    when (event) {
                        is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Log -> {
                            translationLog.add(event.message)
                        }
                        is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Progress -> {
                            translationProgress.value = event.done.toFloat() / maxOf(1, event.total)
                            translationDone.value = event.done
                            translationTotal.value = event.total
                            translationActive.value = event.done < event.total
                        }
                        is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.ResultPath -> {
                            if (!resultPaths.contains(event.path)) {
                                resultPaths.add(event.path)
                            }
                            currentPreviewPath.value = event.path
                        }
                        is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Completed -> {
                            translationActive.value = false
                            canRetry.value = false
                        }
                        is com.kzkt.app.core.TranslationProgressTracker.ProgressEvent.Error -> {
                            translationLog.add("[!] Error: ${event.error}")
                            translationActive.value = false
                            canRetry.value = com.kzkt.app.core.TranslationProgressTracker.cachedPageData != null
                        }
                    }
                }
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

    fun createProvider(): LlmProvider? {
        val s = settings.value
        val meta = Config.PROVIDER_REGISTRY[s.llmProvider] ?: return null

        val apiKey = when (s.llmProvider) {
            "gemini" -> s.geminiApiKey
            "openai" -> s.openaiApiKey
            "openrouter" -> s.openrouterApiKey
            "zen" -> s.zenApiKey
            "opencodego" -> s.opencodegoApiKey
            "custom" -> s.customApiKey
            else -> ""
        }
        val modelName = when (s.llmProvider) {
            "gemini" -> s.modelGemini
            "openai" -> s.modelOpenai
            "openrouter" -> s.modelOpenrouter
            "zen" -> s.modelZen
            "opencodego" -> s.modelOpencodego
            "custom" -> s.modelCustom
            else -> meta.defaultModel
        }

        return when (s.llmProvider) {
            "gemini" -> GeminiProvider(apiKey, modelName)
            "openai" -> OpenAIProvider(apiKey, modelName)
            "openrouter" -> OpenRouterProvider(apiKey, modelName)
            "zen" -> ZenProvider(apiKey, modelName)
            "opencodego" -> OpenCodeGoProvider(apiKey, modelName)
            "custom" -> CustomProvider(apiKey, modelName, s.customBaseUrl, s.customTimeoutSec)
            else -> null
        }
    }

    fun createFallbackProviders(primaryKey: String): List<LlmProvider> {
        val s = settings.value
        val fallbacks = mutableListOf<LlmProvider>()
        if (primaryKey != "gemini" && s.geminiApiKey.isNotBlank()) fallbacks.add(GeminiProvider(s.geminiApiKey, s.modelGemini))
        if (primaryKey != "openai" && s.openaiApiKey.isNotBlank()) fallbacks.add(OpenAIProvider(s.openaiApiKey, s.modelOpenai))
        if (primaryKey != "openrouter" && s.openrouterApiKey.isNotBlank()) fallbacks.add(OpenRouterProvider(s.openrouterApiKey, s.modelOpenrouter))
        if (primaryKey != "zen" && s.zenApiKey.isNotBlank()) fallbacks.add(ZenProvider(s.zenApiKey, s.modelZen))
        if (primaryKey != "opencodego" && s.opencodegoApiKey.isNotBlank()) fallbacks.add(OpenCodeGoProvider(s.opencodegoApiKey, s.modelOpencodego))
        return fallbacks
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
        com.kzkt.app.core.TranslationService.startTranslation(getApplication(), selectedFiles.toList())
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

    fun addLog(msg: String) {
        translationLog.add(msg)
    }

    /** Remove one entry from the Riwayat tab. */
    fun deleteHistoryEntry(timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) { historyRepo.delete(timestamp) }
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

    fun fetchCustomModels(baseUrl: String, apiKey: String) {
        if (baseUrl.isBlank()) return
        customModelsLoading.value = true
        customModels.clear()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Normalize: strip /chat/completions and /v1, then add /v1/models
                var normalized = baseUrl.trimEnd('/')
                if (normalized.endsWith("/chat/completions")) normalized = normalized.removeSuffix("/chat/completions")
                if (normalized.endsWith("/v1")) normalized = normalized.removeSuffix("/v1")
                val endpoint = "$normalized/v1/models"
                val request = Request.Builder().url(endpoint)
                if (apiKey.isNotBlank()) request.header("Authorization", "Bearer $apiKey")
                val response = httpClient.newCall(request.build()).execute()
                val body = response.body?.string() ?: ""

                val json = JsonParser.parseString(body).asJsonObject
                val data = json.getAsJsonArray("data")
                val models = mutableListOf<String>()
                if (data != null) {
                    for (elem in data) {
                        val id = elem.asJsonObject.get("id")?.asString
                        if (id != null) models.add(id)
                    }
                }
                // Fallback: some providers use "models" key
                if (models.isEmpty()) {
                    val modelsArr = json.getAsJsonArray("models")
                    if (modelsArr != null) {
                        for (elem in modelsArr) {
                            val id = elem.asJsonObject.get("id")?.asString
                            if (id != null) models.add(id)
                        }
                    }
                }

                post {
                    if (models.isNotEmpty()) {
                        customModels.addAll(models.sorted())
                        translationLog.add("Found ${models.size} models from custom provider")
                    } else {
                        translationLog.add("[!] No models found at $baseUrl/v1/models")
                    }
                    customModelsLoading.value = false
                }
            } catch (e: Exception) {
                post {
                    translationLog.add("[!] Failed to fetch models: ${e.message}")
                    customModelsLoading.value = false
                }
            }
        }
    }
}
