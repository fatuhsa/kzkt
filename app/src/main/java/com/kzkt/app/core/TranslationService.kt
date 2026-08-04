package com.kzkt.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kzkt.app.MainActivity
import com.kzkt.app.KzktApplication
import com.kzkt.app.core.providers.*
import com.kzkt.app.data.SettingsRepository
import com.kzkt.app.data.TranslationCacheRepository
import com.kzkt.app.util.PdfImporter
import com.kzkt.app.util.PdfExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class TranslationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var translationJob: Job? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var builder: NotificationCompat.Builder

    companion object {
        const val CHANNEL_ID = "translation_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.kzkt.app.action.START"
        const val ACTION_CANCEL = "com.kzkt.app.action.CANCEL"
        const val EXTRA_FILES = "com.kzkt.app.extra.FILES"

        fun startTranslation(context: Context, files: List<String>) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_FILES, ArrayList(files))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelTranslation(context: Context) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelTask()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val files = intent.getStringArrayListExtra(EXTRA_FILES)
            if (files.isNullOrEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            startTask(files)
        }

        return START_STICKY
    }

    private fun startTask(files: List<String>) {
        TranslationProgressTracker.isCancelled = false
        setupNotification()

        val oldJob = translationJob
        translationJob = serviceScope.launch {
            oldJob?.cancelAndJoin()
            try {
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
                    return@launch
                }

                val params = Config.TweakParams(
                    maxBubblesPerRequest = s.maxBubblesPerRequest,
                    minRequestDelay = s.minRequestDelay.toDouble(),
                    filterSfxMode = s.filterSfxMode,
                    padXRatio = s.padXRatio.toDouble(),
                    padYRatio = s.padYRatio.toDouble(),
                    minPad = s.minPad,
                    customFontPath = s.customFontPath,
                    useInpainting = s.useInpainting,
                    useLocalOcr = s.useLocalOcr,
                    localOcrScript = s.localOcrScript,
                )

                val cacheRepo = TranslationCacheRepository(applicationContext)
                val fallbackProviders = createFallbackProviders(s, s.llmProvider)

                val pipeline = TranslationPipeline(
                    yolo = yoloInstance,
                    provider = primaryProvider,
                    textRenderer = textRendererInstance,
                    params = params,
                    targetLanguage = s.targetLanguage,
                    cacheRepo = cacheRepo,
                    fallbackProviders = fallbackProviders,
                    context = applicationContext,
                    onProgress = { msg ->
                        serviceScope.launch { emitLog(msg) }
                    },
                    onStepProgress = { percent, msg ->
                        serviceScope.launch {
                            emitProgress(percent, 100)
                            updateNotificationProgress(msg, percent, 100)
                        }
                    },
                    isCancelled = { TranslationProgressTracker.isCancelled }
                )

                val cacheOutputFolder = File(cacheDir, "translated_outputs")
                cacheOutputFolder.mkdirs()
                val outputDir = cacheOutputFolder.absolutePath

                var completed = 0
                val totalSteps = files.fold(0) { acc, f ->
                    acc + if (f.endsWith(".pdf", ignoreCase = true)) 3 else 1
                }

                emitProgress(0, totalSteps)

                for ((idx, path) in files.withIndex()) {
                    if (TranslationProgressTracker.isCancelled) break

                    val file = File(path)
                    val fileName = file.name

                    if (path.endsWith(".pdf", ignoreCase = true)) {
                        emitLog("[${idx + 1}/${files.size}] Opening PDF $fileName...")
                        updateNotificationProgress("Processing $fileName...", completed, totalSteps)

                        val tempDir = File(cacheDir, "pdf_input")
                        val pages = PdfImporter.extractPdfToImages(file, tempDir)
                        if (pages.isEmpty()) {
                            emitLog("[!] Could not read PDF: $fileName")
                            completed += 3
                            emitProgress(completed, totalSteps)
                            continue
                        }

                        if (TranslationProgressTracker.isCancelled) break

                        completed++
                        emitProgress(completed, totalSteps)
                        updateNotificationProgress("Translating pages for $fileName...", completed, totalSteps)

                        val translatedPages = pipeline.processImageBatch(pages, outputDir, TranslationProgressTracker.cachedPageData)
                        val translatedList = translatedPages.mapNotNull { it.outputPath }
                        if (TranslationProgressTracker.isCancelled) break

                        completed++
                        emitProgress(completed, totalSteps)
                        updateNotificationProgress("Reassembling PDF $fileName...", completed, totalSteps)

                        val outputPdf = File(outputDir, "translated_$fileName")
                        try {
                            PdfExporter.createPdfFromImages(translatedList, outputPdf)
                            if (outputPdf.exists()) {
                                val publicPath = com.kzkt.app.ui.FileUtils.saveToMediaStore(applicationContext, outputPdf.absolutePath)
                                if (publicPath != null) {
                                    emitLog("[+] PDF Reassembled and saved to public folder: $publicPath")
                                    emitResultPath(publicPath)
                                    try {
                                        val historyRepo = com.kzkt.app.data.HistoryRepository(applicationContext)
                                        historyRepo.record(
                                            com.kzkt.app.data.HistoryEntry(
                                                timestamp = System.currentTimeMillis(),
                                                fileName = fileName,
                                                outputPath = publicPath,
                                                pageCount = pages.size,
                                                provider = s.llmProvider,
                                                targetLanguage = s.targetLanguage
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

                        completed++
                        emitProgress(completed, totalSteps)
                    } else {
                        emitLog("[${idx + 1}/${files.size}] Translating image $fileName...")
                        updateNotificationProgress("Translating $fileName...", completed, totalSteps)

                        val result = pipeline.processSingleImage(path, outputDir)
                        if (result.outputPath != null) {
                            val publicPath = com.kzkt.app.ui.FileUtils.saveToMediaStore(applicationContext, result.outputPath)
                            if (publicPath != null) {
                                emitLog("[+] Image translated and saved to public folder: $publicPath")
                                emitResultPath(publicPath)
                                try {
                                    val historyRepo = com.kzkt.app.data.HistoryRepository(applicationContext)
                                    historyRepo.record(
                                        com.kzkt.app.data.HistoryEntry(
                                            timestamp = System.currentTimeMillis(),
                                            fileName = fileName,
                                            outputPath = publicPath,
                                            pageCount = 1,
                                            provider = s.llmProvider,
                                            targetLanguage = s.targetLanguage
                                        )
                                    )
                                } catch (e: Exception) {
                                    emitLog("[!] Failed to record image history: ${e.message}")
                                }
                            } else {
                                emitLog("[!] Failed to save image to public folder.")
                            }
                            File(result.outputPath).delete()
                        } else {
                            emitLog("[!] Failed translation for $fileName")
                        }

                        completed++
                        emitProgress(completed, totalSteps)
                    }
                }

                if (TranslationProgressTracker.isCancelled) {
                    emitLog("[Cancelled] Translation stopped by user.")
                    showFinalNotification("Translation Cancelled", "The operation was stopped by the user.")
                } else {
                    emitLog("=== Done! All files processed. ===")
                    showFinalNotification("Translation Completed", "All files translated successfully!")
                    TranslationProgressTracker.progressFlow.emit(TranslationProgressTracker.ProgressEvent.Completed)
                }

            } catch (e: Throwable) {
                if (e is CancellationException || TranslationProgressTracker.isCancelled) {
                    emitLog("[Cancelled] Translation stopped by user.")
                    showFinalNotification("Translation Cancelled", "The operation was stopped by the user.")
                } else {
                    val msg = e.message ?: "Unknown error"
                    emitError("Execution error: $msg")
                    showFinalNotification("Translation Failed", "An error occurred: $msg")
                }
            } finally {
                ServiceCompat.stopForeground(this@TranslationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelTask() {
        TranslationProgressTracker.isCancelled = true
        translationJob?.cancel()
        serviceScope.launch {
            emitLog("[System] Cancelling active background task...")
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun setupNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, TranslationService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Translating komik...")
            .setContentText("Initializing...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneNotification = NotificationCompat.Builder(this, CHANNEL_ID)
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
            "custom" -> CustomProvider(apiKey, modelName, s.customBaseUrl)
            else -> null
        }
    }

    private fun createFallbackProviders(s: SettingsRepository.Settings, primaryKey: String): List<LlmProvider> {
        val fallbacks = mutableListOf<LlmProvider>()
        if (primaryKey != "gemini" && s.geminiApiKey.isNotBlank()) fallbacks.add(GeminiProvider(s.geminiApiKey, s.modelGemini))
        if (primaryKey != "openai" && s.openaiApiKey.isNotBlank()) fallbacks.add(OpenAIProvider(s.openaiApiKey, s.modelOpenai))
        if (primaryKey != "openrouter" && s.openrouterApiKey.isNotBlank()) fallbacks.add(OpenRouterProvider(s.openrouterApiKey, s.modelOpenrouter))
        if (primaryKey != "zen" && s.zenApiKey.isNotBlank()) fallbacks.add(ZenProvider(s.zenApiKey, s.modelZen))
        if (primaryKey != "opencodego" && s.opencodegoApiKey.isNotBlank()) fallbacks.add(OpenCodeGoProvider(s.opencodegoApiKey, s.modelOpencodego))
        return fallbacks
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
