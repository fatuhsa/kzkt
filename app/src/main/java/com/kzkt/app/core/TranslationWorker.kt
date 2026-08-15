package com.kzkt.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import com.kzkt.app.MainActivity
import com.kzkt.app.KzktApplication
import com.kzkt.app.core.providers.*
import com.google.gson.Gson
import com.kzkt.app.data.SettingsRepository
import com.kzkt.app.data.TranslationCacheRepository
import com.kzkt.app.util.PdfImporter
import com.kzkt.app.util.PdfExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class TranslationWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private lateinit var notificationManager: NotificationManager
    private lateinit var builder: NotificationCompat.Builder

    companion object {
        const val CHANNEL_ID = "translation_worker_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_FILES = "com.kzkt.app.extra.FILES"
        const val EXTRA_FILES_FILE = "com.kzkt.app.extra.FILES_FILE"
        const val EXTRA_RETRY = "com.kzkt.app.extra.RETRY"

        /** Peak PDF pages kept in memory per translation group (bounds RAM). */
        const val PDF_PAGE_GROUP_SIZE = 6

        fun startTranslation(context: Context, files: List<String>, retry: Boolean = false) {
            // WorkManager caps input data at ~10 KB when serialized, so a large folder
            // (dozens of long paths) blows past it with IllegalStateException. Instead of
            // inlining the list, persist it to a cache file and pass only that file path.
            val listFile = File(context.cacheDir, "translation_files_${System.currentTimeMillis()}.json")
            try {
                listFile.writeText(Gson().toJson(files))
            } catch (e: Exception) {
                // Disk failure — fall back to in-band, but ONLY if it fits the 10 KB limit.
                val inputDataSmall = try {
                    workDataOf(EXTRA_FILES to files.toTypedArray(), EXTRA_RETRY to retry)
                } catch (limit: IllegalStateException) {
                    // Still too big — surface a clear error instead of silently truncating.
                    TranslationProgressTracker.progressFlow.tryEmit(
                        TranslationProgressTracker.ProgressEvent.Error(
                            "The batch is too large to process at once. Select fewer files and try again."
                        )
                    )
                    return
                }
                val workRequestSmall = OneTimeWorkRequestBuilder<TranslationWorker>()
                    .setInputData(inputDataSmall)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "TranslationWorker",
                    ExistingWorkPolicy.REPLACE,
                    workRequestSmall
                )
                return
            }

            val inputData = workDataOf(
                EXTRA_FILES_FILE to listFile.absolutePath,
                EXTRA_RETRY to retry
            )
            val workRequest = OneTimeWorkRequestBuilder<TranslationWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "TranslationWorker",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancelTranslation(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("TranslationWorker")
            TranslationProgressTracker.isCancelled = true
        }
    }
    override suspend fun doWork(): Result {
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Sweep stale queue files left behind by cancelled/replaced runs.
        try {
            context.cacheDir.listFiles { f ->
                f.name.startsWith("translation_files_") &&
                    System.currentTimeMillis() - f.lastModified() > 24 * 60 * 60 * 1000L
            }?.forEach { it.delete() }
        } catch (_: Exception) {}

        // New path: file list persisted to a cache JSON (no 10 KB WorkManager limit).
        val filePath = inputData.getString(EXTRA_FILES_FILE)
        val files: List<String> = if (!filePath.isNullOrBlank()) {
            val f = File(filePath)
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                val parsed = Gson().fromJson<List<String>>(f.readText(), type)
                f.delete()
                parsed ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            // Legacy path (in-band array) for compatibility.
            inputData.getStringArray(EXTRA_FILES)?.toList() ?: emptyList()
        }
        if (files.isEmpty()) {
            return Result.failure()
        }
        val retry = inputData.getBoolean(EXTRA_RETRY, false)

        return startTask(files, retry)
    }

    private suspend fun startTask(files: List<String>, retry: Boolean = false): Result {
        TranslationProgressTracker.isCancelled = false
        if (!retry) TranslationProgressTracker.clearCache()

        // One shared scope for progress/log emission — the pipeline callbacks fire on
        // arbitrary dispatchers, and emitting into the flow must happen off the calling
        // thread. A single scope (instead of one CoroutineScope per message) keeps
        // allocations flat during long batches.
        val emitScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

        val notification = setupNotification()
        // Android 15+ (targetSdk 35+) requires an explicit foreground service type.
        // WorkManager forwards the ForegroundInfo type straight to
        // Service.startForeground(id, notification, type); the 2-arg constructor
        // defaults to 0 (none), which throws InvalidForegroundServiceTypeException
        // on Android 16. dataSync matches the manifest declaration + permission.
        setForeground(
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )

        return try {
                // Note: emitScope is cancelled in the finally below so no progress/log
                // coroutines linger after the worker finishes.
                // Initialize model if VM hasn't initialized it yet
                val yoloInstance = KzktApplication.yolo ?: run {
                    emitLog("[System] Loading YOLO model...")
                    val yolo = YoloOnnx(applicationContext)
                    yolo.initialize()
                    KzktApplication.yolo = yolo
                    yolo
                }

                val textRendererInstance = KzktApplication.textRenderer ?: run {
                    val renderer = TextRenderer(applicationContext)
                    KzktApplication.textRenderer = renderer
                    renderer
                }

                val settingsRepo = SettingsRepository(applicationContext)
                val s = settingsRepo.settingsFlow.first()

                val primaryProvider = createProvider(s)
                if (primaryProvider == null) {
                    emitError("LlmProvider meta not found or provider creation failed.")
                    return Result.failure()
                }

                val params = Config.TweakParams(
                    detection = Config.TweakParams.DetectionParams(
                        filterSfxMode = s.filterSfxMode,
                        padXRatio = s.padXRatio.toDouble(),
                        padYRatio = s.padYRatio.toDouble(),
                        minPad = s.minPad,
                    ),
                    render = Config.TweakParams.RenderParams(
                        useInpainting = s.useInpainting,
                        customFontPath = s.customFontPath,
                        renderTextColor = s.renderTextColor,
                        renderFontScale = s.renderFontScale.toDouble(),
                        jpegQuality = s.jpegQuality,
                    ),
                    engine = Config.TweakParams.EngineParams(
                        maxBubblesPerRequest = s.maxBubblesPerRequest,
                        minRequestDelay = s.minRequestDelay.toDouble(),
                        useLocalOcr = s.useLocalOcr,
                        enableDevLogs = s.enableDevLogs,
                        useImageUpscaler = s.useImageUpscaler,
                        translateSfx = s.translateSfx,
                    ),
                )

                val cacheRepo = TranslationCacheRepository(applicationContext)
                val glossaryRepo = com.kzkt.app.data.GlossaryRepository(applicationContext)
                // The repo loads its file asynchronously (off the main thread); wait for that
                // read so the glossary rules are present in the very first translation prompt.
                glossaryRepo.awaitInitialLoad()
                val glossary = glossaryRepo.glossary.value
                val fallbackProviders = createFallbackProviders(s, s.llmProvider)

                val pipeline = TranslationPipeline(
                    config = PipelineConfig(
                        yolo = yoloInstance,
                        textRenderer = textRendererInstance,
                        params = params,
                        targetLanguage = s.targetLanguage,
                        cacheRepo = cacheRepo,
                        glossary = glossary,
                        context = applicationContext,
                    ),
                    providerChain = ProviderChain(
                        provider = primaryProvider,
                        fallbackProviders = fallbackProviders,
                    ),
                    callbacks = PipelineCallbacks(
                        onProgress = { msg -> emitScope.launch { emitLog(msg) } },
                        onStepProgress = { percent, msg ->
                            emitScope.launch {
                                emitProgress(percent, 100)
                                updateNotificationProgress(msg, percent, 100)
                            }
                        },
                        isCancelled = { TranslationProgressTracker.isCancelled }
                    )
                )

                val cacheOutputFolder = java.io.File(applicationContext.cacheDir, "translated_outputs")
                cacheOutputFolder.mkdirs()
                val outputDir = cacheOutputFolder.absolutePath

                // One ID for the whole run: every page of this batch (folder,
                // multi-select or PDF) shares it, so the History reader can group
                // siblings even when the source file names differ. Timestamp-based
                // (collisions across runs are harmless — grouping only needs pages
                // of the SAME run to match, never different runs to differ).
                val batchId = System.currentTimeMillis().toString()

                var completed = 0
                val totalSteps = files.fold(0) { acc, f ->
                    acc + if (f.endsWith(".pdf", ignoreCase = true)) 3 else 1
                }

                // Fast retry: reuse the last cached detection results (skips YOLO for
                // already-detected pages). The cache is shared read-only across groups and
                // cleared once the whole job finishes.
                val retryCache = if (retry) TranslationProgressTracker.cachedPageData else null
                if (retryCache != null && retryCache.isNotEmpty()) {
                    emitLog("[Retry] Resuming from cached detection (${retryCache.size} pages)...")
                }

                emitProgress(0, totalSteps)

                for ((idx, path) in files.withIndex()) {
                    if (TranslationProgressTracker.isCancelled) break

                    val file = File(path)
                    val fileName = file.name

                    if (path.endsWith(".pdf", ignoreCase = true)) {
                        emitLog("[${idx + 1}/${files.size}] Opening PDF $fileName...")
                        updateNotificationProgress("Processing $fileName...", completed, totalSteps)
                        emitPageStatus(path, "processing")

                        val tempDir = java.io.File(applicationContext.cacheDir, "pdf_input")
                        val pages = PdfImporter.extractPdfToImages(file, tempDir)
                        if (pages.isEmpty()) {
                            emitLog("[!] Could not read PDF: $fileName")
                            emitPageStatus(path, "failed")
                            try {
                                com.kzkt.app.data.HistoryRepository(applicationContext).record(
                                    com.kzkt.app.data.HistoryEntry(
                                        timestamp = System.currentTimeMillis(),
                                        fileName = fileName,
                                        outputPath = "",
                                        pageCount = 0,
                                        provider = s.llmProvider,
                                        targetLanguage = s.targetLanguage,
                                        inputPath = path,
                                        status = "failed",
                                        batchId = batchId
                                    )
                                )
                            } catch (e: Exception) {
                                emitLog("[!] Failed to record failed history: ${e.message}")
                            }
                            completed += 3
                            emitProgress(completed, totalSteps)
                            continue
                        }

                        if (TranslationProgressTracker.isCancelled) break

                        completed++
                        emitProgress(completed, totalSteps)
                        updateNotificationProgress("Translating pages for $fileName...", completed, totalSteps)

                        // PAGE-GROUP CHUNKING: Split PDF pages into groups of 6 pages per group.
                        // Bounds peak memory usage to ~6 page bitmaps at a time instead of loading all 20-50+ page bitmaps into RAM simultaneously.
                        // DO NOT optimize this back into a single processImageBatch call!
                        val pageGroups = pages.chunked(PDF_PAGE_GROUP_SIZE)
                        val allTranslatedPages = mutableListOf<PipelineResult>()

                        for ((groupIdx, pageGroup) in pageGroups.withIndex()) {
                            if (TranslationProgressTracker.isCancelled) break

                            val groupPipeline = TranslationPipeline(
                                config = PipelineConfig(
                                    yolo = yoloInstance,
                                    textRenderer = textRendererInstance,
                                    params = params,
                                    targetLanguage = s.targetLanguage,
                                    cacheRepo = cacheRepo,
                                    context = applicationContext,
                                ),
                                providerChain = ProviderChain(
                                    provider = primaryProvider,
                                    fallbackProviders = fallbackProviders,
                                ),
                                callbacks = PipelineCallbacks(
                                    onProgress = { msg -> emitScope.launch { emitLog(msg) } },
                                    onStepProgress = { groupPercent, msg ->
                                        val overallPercent = ((groupIdx * 100f + groupPercent) / pageGroups.size).toInt().coerceIn(0, 100)
                                        emitScope.launch {
                                            emitProgress(completed, totalSteps)
                                            updateNotificationProgress("[$fileName - Group ${groupIdx + 1}/${pageGroups.size}] $msg", completed, totalSteps)
                                        }
                                    },
                                    isCancelled = { TranslationProgressTracker.isCancelled }
                                )
                            )

                            val groupResults = groupPipeline.processImageBatch(
                                imagePaths = pageGroup,
                                outputDir = outputDir,
                                cachedPages = retryCache,
                                pageOffset = groupIdx * PDF_PAGE_GROUP_SIZE,
                                totalBatchPages = pages.size
                            )
                            allTranslatedPages.addAll(groupResults)
                        }

                        val translatedList = allTranslatedPages.mapNotNull { it.outputPath }
                        if (TranslationProgressTracker.isCancelled) break

                        completed++
                        emitProgress(completed, totalSteps)
                        updateNotificationProgress("Reassembling PDF $fileName...", completed, totalSteps)

                        val outputPdf = java.io.File(outputDir, "translated_$fileName")
                        var pdfSaved = false
                        try {
                            PdfExporter.createPdfFromImages(translatedList, outputPdf)
                            if (outputPdf.exists()) {
                                val publicPath = com.kzkt.app.ui.FileUtils.saveToMediaStore(applicationContext, outputPdf.absolutePath)
                                if (publicPath != null) {
                                    pdfSaved = true
                                    emitLog("[+] PDF Reassembled and saved to public folder: $publicPath")
                                    emitResultPath(publicPath)
                                    // PDF results must be recorded in History too — the image
                                    // branch below already records, but the PDF branch did not,
                                    // so translated PDFs never appeared in the History tab.
                                    try {
                                        val historyRepo = com.kzkt.app.data.HistoryRepository(applicationContext)
                                        // Same as the image branch: a retry that finally
                                        // succeeded replaces the old "failed" entry.
                                        historyRepo.deleteByInputPath(path)
                                        historyRepo.record(
                                            com.kzkt.app.data.HistoryEntry(
                                                timestamp = System.currentTimeMillis(),
                                                fileName = fileName,
                                                outputPath = publicPath,
                                                pageCount = pages.size,
                                                provider = s.llmProvider,
                                                targetLanguage = s.targetLanguage,
                                                inputPath = path,
                                                status = "ok",
                                                batchId = batchId
                                            )
                                        )
                                    } catch (e: Exception) {
                                        emitLog("[!] Failed to record PDF history: ${e.message}")
                                    }
                                } else {
                                    emitLog("[!] Failed to save PDF to public downloads folder.")
                                }
                                outputPdf.delete()
                            } else {
                                emitLog("[!] Failed to reassemble PDF: File not found after creation")
                            }
                        } catch (e: Exception) {
                            emitLog("[!] Error reassembling PDF: ${e.message}")
                        }

                        emitPageStatus(path, if (pdfSaved) "done" else "failed")
                        if (!pdfSaved) {
                            try {
                                com.kzkt.app.data.HistoryRepository(applicationContext).record(
                                    com.kzkt.app.data.HistoryEntry(
                                        timestamp = System.currentTimeMillis(),
                                        fileName = fileName,
                                        outputPath = "",
                                        pageCount = pages.size,
                                        provider = s.llmProvider,
                                        targetLanguage = s.targetLanguage,
                                        inputPath = path,
                                        status = "failed",
                                        batchId = batchId
                                    )
                                )
                            } catch (e: Exception) {
                                emitLog("[!] Failed to record failed history: ${e.message}")
                            }
                        }

                        completed++
                        emitProgress(completed, totalSteps)
                    } else {
                        emitLog("[${idx + 1}/${files.size}] Translating image $fileName...")
                        updateNotificationProgress("Translating $fileName...", completed, totalSteps)
                        emitPageStatus(path, "processing")

                        val result = pipeline.processSingleImage(path, outputDir)
                        if (result.outputPath != null) {
                            emitPageStatus(path, "done")
                            val publicPath = com.kzkt.app.ui.FileUtils.saveToMediaStore(applicationContext, result.outputPath)
                            if (publicPath != null) {
                                emitLog("[+] Image translated and saved to public folder: $publicPath")
                                emitResultPath(publicPath)
                                // Follow the file into the public folder so the touch-up
                                // editor can resolve this page's bubbles from History/Main.
                                try {
                                    com.kzkt.app.data.EditMetadataRepository(applicationContext)
                                        .rekeyForOutput(result.outputPath, publicPath)
                                } catch (_: Exception) {}
                                try {
                                    val historyRepo = com.kzkt.app.data.HistoryRepository(applicationContext)
                                    // A retry that finally succeeded must not leave the old
                                    // "failed" entry behind — replace it with the success.
                                    historyRepo.deleteByInputPath(path)
                                    historyRepo.record(
                                        com.kzkt.app.data.HistoryEntry(
                                            timestamp = System.currentTimeMillis(),
                                            fileName = fileName,
                                            outputPath = publicPath,
                                            pageCount = 1,
                                            provider = s.llmProvider,
                                            targetLanguage = s.targetLanguage,
                                            inputPath = path,
                                            status = "ok",
                                            batchId = batchId
                                        )
                                    )
                                } catch (e: Exception) {
                                    emitLog("[!] Failed to record image history: ${e.message}")
                                }
                            } else {
                                emitLog("[!] Failed to save image to public folder.")
                            }
                            java.io.File(result.outputPath).delete()
                        } else {
                            emitLog("[!] Failed translation for $fileName")
                            emitPageStatus(path, "failed")
                            // Failed pages stay visible in History so they can be retried
                            // later (only the source input path is needed to re-run them).
                            try {
                                com.kzkt.app.data.HistoryRepository(applicationContext).record(
                                    com.kzkt.app.data.HistoryEntry(
                                        timestamp = System.currentTimeMillis(),
                                        fileName = fileName,
                                        outputPath = "",
                                        pageCount = 1,
                                        provider = s.llmProvider,
                                        targetLanguage = s.targetLanguage,
                                        inputPath = path,
                                        status = "failed",
                                        batchId = batchId
                                    )
                                )
                            } catch (e: Exception) {
                                emitLog("[!] Failed to record failed history: ${e.message}")
                            }
                        }

                        completed++
                        emitProgress(completed, totalSteps)
                    }
                }

                if (TranslationProgressTracker.isCancelled) {
                    emitLog("[Cancelled] Translation stopped by user.")
                    showFinalNotification("Translation Cancelled", "The operation was stopped by the user.")
                    Result.failure()
                } else {
                    // Job finished: the retry cache is no longer needed (all cached bitmaps
                    // were either rendered or skipped).
                    TranslationProgressTracker.clearCache()
                    emitLog("=== Done! All files processed. ===")
                    showFinalNotification("Translation Completed", "All files translated successfully!")
                    TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.Completed)
                    Result.success()
                }

            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException || TranslationProgressTracker.isCancelled) {
                    emitLog("[Cancelled] Translation stopped by user.")
                    showFinalNotification("Translation Cancelled", "The operation was stopped by the user.")
                    Result.failure()
                } else {
                    val msg = e.message ?: "Unknown error"
                    emitError("Execution error: $msg")
                    showFinalNotification("Translation Failed", "An error occurred: $msg")
                    Result.failure()
                }
            } finally {
                emitScope.cancel()
            }
    }

    private suspend fun emitLog(msg: String) {
        TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.Log(msg))
    }

    private suspend fun emitProgress(done: Int, total: Int) {
        TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.Progress(done, total))
    }

    private suspend fun emitResultPath(path: String) {
        TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.ResultPath(path))
    }

    private suspend fun emitPageStatus(path: String, state: String) {
        TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.PageStatus(path, state))
    }

    private suspend fun emitError(err: String) {
        TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.Error(err))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KZKT Translation Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time translation progress in background"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setupNotification(): android.app.Notification {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelPendingIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Translating manga...")
            .setContentText("Initializing...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

        return builder.build()
    }

    private fun updateNotificationProgress(msg: String, done: Int, total: Int) {
        val pct = if (total > 0) (done * 100 / total) else 0
        val notification = builder
            .setContentText(msg)
            .setSubText("$pct%")
            .setProgress(total, done, false)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFinalNotification(title: String, text: String) {
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()
        notificationManager.notify(1002, doneNotification)
    }

    private fun createProvider(s: SettingsRepository.Settings): LlmProvider? {
        if (Config.PROVIDER_REGISTRY[s.llmProvider] == null) return null
        return ProviderFactory.create(
            s.llmProvider, apiKeyFor(s, s.llmProvider), modelFor(s, s.llmProvider),
            s.getBaseUrl(s.llmProvider), s.customTimeoutSec
        )
    }

    private fun apiKeyFor(s: SettingsRepository.Settings, key: String): String = when (key) {
        "gemini" -> s.geminiApiKey
        "openai" -> s.openaiApiKey
        "openrouter" -> s.openrouterApiKey
        "zen" -> s.zenApiKey
        "opencodego" -> s.opencodegoApiKey
        "custom" -> s.customApiKey
        else -> ""
    }

    private fun modelFor(s: SettingsRepository.Settings, key: String): String = when (key) {
        "gemini" -> s.modelGemini
        "openai" -> s.modelOpenai
        "openrouter" -> s.modelOpenrouter
        "zen" -> s.modelZen
        "opencodego" -> s.modelOpencodego
        "custom" -> s.modelCustom
        else -> Config.PROVIDER_REGISTRY[key]?.defaultModel ?: ""
    }

    /**
     * Builds the fallback chain: same-provider alternate models first (in-provider
     * failover when the primary model hits a rate limit or errors), then the other
     * configured providers.
     */
    private fun createFallbackProviders(s: SettingsRepository.Settings, primaryKey: String): List<LlmProvider> {
        val fallbacks = mutableListOf<LlmProvider>()

        // In-provider failover: alternate models of the active provider, so a model
        // outage/rate limit falls back to another model before jumping providers.
        val primaryModel = modelFor(s, primaryKey)
        val meta = Config.PROVIDER_REGISTRY[primaryKey]
        val primaryKeyOk = meta?.requiresKey == false || apiKeyFor(s, primaryKey).isNotBlank()
        if (primaryKeyOk) {
            val altModels = (Config.PRESET_MODELS[primaryKey] ?: emptyList()).filter { it != primaryModel }
            for (alt in altModels) {
                ProviderFactory.create(primaryKey, apiKeyFor(s, primaryKey), alt, s.getBaseUrl(primaryKey), s.customTimeoutSec)
                    ?.let { fallbacks.add(it) }
            }
        }

        if (primaryKey != "gemini" && s.geminiApiKey.isNotBlank()) fallbacks.add(GeminiProvider(s.geminiApiKey, s.modelGemini, s.baseUrlGemini))
        if (primaryKey != "openai" && s.openaiApiKey.isNotBlank()) fallbacks.add(OpenAIProvider(s.openaiApiKey, s.modelOpenai, s.baseUrlOpenai))
        if (primaryKey != "openrouter" && s.openrouterApiKey.isNotBlank()) fallbacks.add(OpenRouterProvider(s.openrouterApiKey, s.modelOpenrouter, s.baseUrlOpenrouter))
        if (primaryKey != "zen" && s.zenApiKey.isNotBlank()) fallbacks.add(ZenProvider(s.zenApiKey, s.modelZen, s.baseUrlZen))
        if (primaryKey != "opencodego" && s.opencodegoApiKey.isNotBlank()) fallbacks.add(OpenCodeGoProvider(s.opencodegoApiKey, s.modelOpencodego, s.baseUrlOpencodego))
        // Custom only makes sense as a fallback when a base URL is actually configured.
        if (primaryKey != "custom" && s.customBaseUrl.isNotBlank()) {
            fallbacks.add(CustomProvider(s.customApiKey, s.modelCustom, s.customBaseUrl, s.customTimeoutSec))
        }
        return fallbacks
    }

}
