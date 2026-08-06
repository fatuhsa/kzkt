package com.kzkt.app.core

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.core.providers.LlmProvider
import com.kzkt.app.ui.FileUtils
import com.kzkt.app.util.JsonUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * Main translation pipeline: detection → filter → mosaic → LLM → render → save.
 * Ported from the original Python translator
 */
class TranslationPipeline(
    private val yolo: YoloOnnx?,
    private val provider: LlmProvider,
    private val textRenderer: TextRenderer,
    private val params: TweakParams,
    private val rateLimiter: RateLimiter = RateLimiter((params.minRequestDelay * 1000).toLong()),
    private val targetLanguage: String = "Indonesian",
    private val cacheRepo: com.kzkt.app.data.TranslationCacheRepository? = null,
    private val fallbackProviders: List<LlmProvider> = emptyList(),
    private val context: Context? = null,
    private val onProgress: (String) -> Unit = {},
    private val onStepProgress: (Int, String) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
) {
    data class PipelineResult(
        val outputPath: String?,
        val bubblesFound: Int = 0,
        val bubblesTranslated: Int = 0,
        val failed: Boolean = false,
        val alreadyDone: Boolean = false,
        val originalBitmap: Bitmap? = null,
        val translations: Map<String, String> = emptyMap(),
        val coordinateMap: Map<String, IntArray> = emptyMap(),
    )

    data class PageData(
        val path: String,
        val pil: Bitmap,
        val draws: Canvas?,
        val imgWidth: Int,
        val imgHeight: Int,
        val crops: MutableList<Pair<String, Bitmap>>,
        val coordMap: MutableMap<String, IntArray>,
        val bubbleColors: MutableMap<String, Int> = mutableMapOf(),
        val alreadyDone: Boolean = false,
        val failed: Boolean = false,
        val borrowed: Boolean = false,
    )

    /**
     * Process a single manga page image.
     */
    suspend fun processSingleImage(inputPath: String, outputDir: String): PipelineResult {
        if (isCancelled()) return PipelineResult(null, failed = true)

        val imgFile = File(inputPath)
        if (!imgFile.exists()) return PipelineResult(null, failed = true)

        val bitmap = ImageProcessor.loadBitmap(inputPath) ?: return PipelineResult(null, failed = true)

        // Auto-split landscape
        val splitCount = ImageProcessor.shouldAutoSplit(bitmap)
        if (splitCount > 1) {
            return processLandscape(bitmap, inputPath, outputDir, splitCount)
        }

        return processBitmap(bitmap, inputPath, outputDir)
    }

    private suspend fun processLandscape(
        bitmap: Bitmap, inputPath: String, outputDir: String, splitCount: Int
    ): PipelineResult {
        onProgress("[Auto-Split] Wide image detected. Splitting into $splitCount parts...")
        val imgHeight = bitmap.height
        val splitWidth = bitmap.width / splitCount
        val results = mutableListOf<String>()

        for (i in 0 until splitCount) {
            if (isCancelled()) return PipelineResult(null, failed = true)

            val xEnd = bitmap.width - (i * splitWidth)
            val xStart = if (i == splitCount - 1) 0 else xEnd - splitWidth

            val partBitmap = Bitmap.createBitmap(bitmap, xStart, 0, xEnd - xStart, imgHeight)
            val partPath = File(
                outputDir,
                "${File(inputPath).nameWithoutExtension}_split${i + 1}.png"
            ).absolutePath
            saveBitmap(partBitmap, partPath)

            onProgress("  Translating Part ${i + 1}...")
            val result = processBitmap(partBitmap, partPath, outputDir)
            if (result.outputPath != null) results.add(result.outputPath)

            File(partPath).delete()
        }

        if (results.size == splitCount) {
            // Recombine right-to-left (manga order)
            val images = results.map { ImageProcessor.loadBitmap(it)!! }.reversed()
            val targetH = images.maxOf { it.height }
            val resized = images.map { bmp ->
                if (bmp.height != targetH) {
                    val scale = targetH.toDouble() / bmp.height
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), targetH, true)
                } else bmp
            }

            val totalW = resized.sumOf { it.width }
            val combined = Bitmap.createBitmap(totalW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            var x = 0
            for (img in resized) {
                canvas.drawBitmap(img, x.toFloat(), 0f, null)
                x += img.width
            }

            // Cleanup individual results
            results.forEach { File(it).delete() }

            val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
            saveBitmap(combined, outputPath)
            return PipelineResult(outputPath, bubblesFound = results.size)
        }

        return PipelineResult(null, failed = true)
    }

    /**
     * Normalize an LLM-returned JSON key to a stable bubble ID ("1", "1_2", …) or null if unmatched.
     * Handles int/string keys, leading underscores, prefixes like "ID 1", and trailing punctuation.
     */
    private fun normalizeIdKey(key: String): String? {
        if (key.startsWith("_")) return key
        val m = Regex("(\\d+)(?:_(\\d+))?|\\b(\\d+)\\b").find(key) ?: return null
        val (a, b, c) = m.destructured
        val first = (a.ifEmpty { c }).toIntOrNull() ?: return null
        return if (b.isNotEmpty()) "${first}_${b.toIntOrNull() ?: b}" else first.toString()
    }

    /**
     * Draw translations onto the canvas. Shared by single-image and batch rendering so
     * both cover the original text with a white/adaptive blurred patch (or a full patch for flat boxes).
     * Returns the number of bubbles actually rendered.
     */
    private fun renderTranslations(
        canvas: Canvas,
        translations: Map<String, String>,
        coordinateMap: Map<String, IntArray>,
        bubbleColors: Map<String, Int> = emptyMap(),
        imgWidth: Int,
        imgHeight: Int,
    ): Int {
        var count = 0
        
        // Pass 1: Draw all background patches first
        for ((num, text) in translations) {
            if (num !in coordinateMap || text.uppercase() == "SKIP" || text.isBlank()) continue

            val (x1, y1, x2, y2) = coordinateMap[num]!!
            val w = maxOf(1, x2 - x1)
            val h = maxOf(1, y2 - y1)
            val ratio = w.toDouble() / h
            val areaRatio = (w * h).toDouble() / maxOf(1, imgWidth * imgHeight)
            val bgColor = bubbleColors[num] ?: Color.WHITE

            if (ratio >= 3.2 && w >= imgWidth * 0.35) continue
            if (areaRatio >= 0.035 && ratio >= 2.8) continue

            val suspiciousFlat = ratio >= params.rasioBoxGepeng &&
                w >= imgWidth * params.lebarBoxGepengRatio &&
                h <= imgHeight * params.tinggiBoxGepengRatio

            if (!params.useInpainting && !(params.pakaiPatchUntukBoxGepeng && suspiciousFlat)) {
                // Background: blurred adaptive patch, drawn on a bubble-sized overlay
                val marginX = (w * params.maskMarginRatio).toInt()
                val marginY = (h * params.maskMarginRatio).toInt()
                val cornerRadius = maxOf(6, minOf(w, h) / 3)
                val blur = 6f

                val overlay = Bitmap.createBitmap(
                    (x2 - x1) + marginX * 2 + (blur * 2).toInt(),
                    (y2 - y1) + marginY * 2 + (blur * 2).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                overlay.eraseColor(Color.TRANSPARENT)
                val overlayCanvas = Canvas(overlay)

                val bgPaint = Paint().apply {
                    color = bgColor
                    isAntiAlias = true
                }
                val pad = blur
                overlayCanvas.drawRoundRect(
                    RectF(
                        pad + marginX, pad + marginY,
                        pad + marginX + (x2 - x1), pad + marginY + (y2 - y1)
                    ),
                    cornerRadius.toFloat(), cornerRadius.toFloat(), bgPaint
                )

                // Apply blur
                val blurPaint = Paint().apply {
                    maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawBitmap(overlay, x1 - marginX - pad, y1 - marginY - pad, blurPaint)
            }
        }

        // Pass 2: Render all translated texts on top
        for ((num, text) in translations) {
            if (num !in coordinateMap || text.uppercase() == "SKIP" || text.isBlank()) continue

            val (x1, y1, x2, y2) = coordinateMap[num]!!
            val w = maxOf(1, x2 - x1)
            val h = maxOf(1, y2 - y1)
            val ratio = w.toDouble() / h
            val areaRatio = (w * h).toDouble() / maxOf(1, imgWidth * imgHeight)
            val bgColor = bubbleColors[num] ?: Color.WHITE

            if (ratio >= 3.2 && w >= imgWidth * 0.35) continue
            if (areaRatio >= 0.035 && ratio >= 2.8) continue

            val suspiciousFlat = ratio >= params.rasioBoxGepeng &&
                w >= imgWidth * params.lebarBoxGepengRatio &&
                h <= imgHeight * params.tinggiBoxGepengRatio

            if (params.useInpainting) {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = false, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.customFontPath)
            } else if (params.pakaiPatchUntukBoxGepeng && suspiciousFlat) {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = true, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.customFontPath)
            } else {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = false, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.customFontPath)
            }
            count++
        }
        return count
    }

    private suspend fun processBitmap(
        bitmap: Bitmap, inputPath: String, outputDir: String
    ): PipelineResult {
        onProgress("Translating: ${File(inputPath).name}")

        val mat = ImageProcessor.bitmapToMat(bitmap)
        val imgHeight = mat.rows()
        val imgWidth = mat.cols()

        // ── YOLO Detection (3-stage cascade) ──
        val rawBoxes = mutableListOf<IntArray>()
        try {
            for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
                if (isCancelled()) return PipelineResult(null, failed = true)
                val detections = yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou) ?: continue
                for (d in detections) {
                    rawBoxes.add(intArrayOf(d.x1, d.y1, d.x2, d.y2))
                }
            }
        } finally {
            mat.release()
        }
        // ── Filtering ──
        var filtered = ImageProcessor.removeFalseGiants(rawBoxes)
        filtered = ImageProcessor.mergeOverlapping(filtered)
        filtered = ImageProcessor.removeNonsense(filtered, imgWidth, imgHeight)
        val sfxMat = ImageProcessor.bitmapToMat(bitmap)
        try {
            filtered = ImageProcessor.removeSfxAndImages(sfxMat, filtered, params)
        } finally {
            sfxMat.release()
        }

        if (params.enableDevLogs) {
            onProgress("  [YOLO Cascade] Raw detections: ${rawBoxes.size} -> Filtered: ${filtered.size} speech bubbles.")
        } else {
            onProgress("  Filtered to ${filtered.size} speech bubbles...")
        }

        if (filtered.isEmpty()) {
            onProgress("  No text bubbles found.")
            val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
            saveBitmap(bitmap, outputPath)
            return PipelineResult(outputPath)
        }

        // ── Crop extraction & background color detection ──
        val cropItems = mutableListOf<MosaicBuilder.CropItem>()
        val coordinateMap = mutableMapOf<String, IntArray>()
        val bubbleColors = mutableMapOf<String, Int>()

        val cropMatFull = ImageProcessor.bitmapToMat(bitmap)
        try {
            for ((order, box) in filtered.withIndex()) {
                val id = (order + 1).toString()
                val (x1, y1, x2, y2) = box
                val boxW = maxOf(1, x2 - x1)
                val boxH = maxOf(1, y2 - y1)

                val padX = maxOf(params.minPad, (boxW * params.padXRatio).toInt())
                val padY = maxOf(params.minPad, (boxH * params.padYRatio).toInt())

                val (cropX1, cropY1, cropX2, cropY2) = ImageProcessor.smartCropBounds(
                    box, filtered, imgWidth, imgHeight, padX, padY, params
                )

                // Detect background color (dark vs white)
                val bgColor = ImageProcessor.detectBubbleBackgroundColor(cropMatFull, box)
                bubbleColors[id] = bgColor

                val cropMat = cropMatFull.submat(org.opencv.core.Rect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1))
                val maskedMat = ImageProcessor.maskOutsideBubble(cropMat, cropX1, cropY1, x1, y1, x2, y2, params)
                cropMat.release()

                // Scale up
                val scale = params.skalaPotonganMosaik
                val cropBitmap = ImageProcessor.matToBitmap(maskedMat)
                maskedMat.release()
                val scaledBitmap = if (scale != 1.0) {
                    Bitmap.createScaledBitmap(
                        cropBitmap,
                        maxOf(1, (cropBitmap.width * scale).toInt()),
                        maxOf(1, (cropBitmap.height * scale).toInt()),
                        true
                    )
                } else cropBitmap

                cropItems.add(MosaicBuilder.CropItem(id, scaledBitmap))
                coordinateMap[id] = box
            }
        } finally {
            cropMatFull.release()
        }

        // ── Mosaic → LLM Translate ──
        val allTranslations = mutableMapOf<String, String>()
        val cropsToTranslate = mutableListOf<MosaicBuilder.CropItem>()

        if (cacheRepo != null) {
            for (crop in cropItems) {
                val cached = cacheRepo.getTranslation(crop.bitmap, targetLanguage)
                if (cached != null) {
                    allTranslations[crop.id] = cached
                } else {
                    cropsToTranslate.add(crop)
                }
            }
            if (allTranslations.isNotEmpty()) {
                onProgress("  [Cache Hit] Found ${allTranslations.size}/${cropItems.size} cached translations locally.")
            }
        } else {
            cropsToTranslate.addAll(cropItems)
        }

        if (cropsToTranslate.isNotEmpty()) {
            if (params.useLocalOcr) {
                onProgress("  [Local OCR Engine] Extracting text from ${cropsToTranslate.size} speech bubbles via Google ML Kit (${params.localOcrScript})...")
                val maxPerBatch = minOf(params.maxBubblesPerRequest, 6)
                val ocrCropItems = cropsToTranslate.map { MosaicBuilder.CropItem(it.id, it.bitmap) }
                val chunks = MosaicBuilder.chunkCrops(ocrCropItems, maxPerBatch)

                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    if (isCancelled()) break
                    if (chunks.size > 1) {
                        onProgress("  [Local OCR Chunk ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}")
                    }
                    val ocrMap = mutableMapOf<String, String>()
                    for (item in chunk) {
                        val recognized = com.kzkt.app.core.ocr.LocalOcrEngine.recognizeText(item.bitmap, params.localOcrScript)
                        if (recognized.isNotBlank()) {
                            ocrMap[item.id] = recognized
                            if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> \"$recognized\"")
                        } else {
                            if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> (No text recognized)")
                        }
                    }
                    if (ocrMap.isEmpty()) {
                        onProgress("  [Local OCR] No text recognized in chunk ${chunkIdx + 1}. Continuing...")
                        continue
                    }

                    val textJson = com.google.gson.Gson().toJson(ocrMap)
                    val textPrompt = "You are an expert comic text translator. Translate the text values in the following JSON map into $targetLanguage. Return ONLY a valid JSON map with exact matching keys.\n\nInput JSON:\n$textJson"
                    val providersChain = listOf(provider) + fallbackProviders
                    val dummyBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

                    for (prov in providersChain) {
                        if (isCancelled()) break
                        onProgress("  Translating text with ${prov.providerName}...")
                        try {
                            val result = rateLimiter.executeWithRetry(
                                apiCall = { prov.translateText(textJson, textPrompt) ?: prov.translateImage(dummyBmp, textPrompt) },
                                providerName = prov.providerName,
                                isCancelled = isCancelled,
                                onWait = { msg -> onProgress(msg) }
                            )
                            if (result != null) {
                                val cleaned = JsonUtils.sanitizeJson(result)
                                val parsed = JsonUtils.parseTranslationMap(cleaned)
                                if (parsed.isNotEmpty()) {
                                    allTranslations.putAll(parsed)
                                    if (cacheRepo != null) {
                                        for ((id, text) in parsed) {
                                            val item = ocrCropItems.find { it.id == id }
                                            if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text)
                                        }
                                    }
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException || isCancelled()) throw e
                        }
                    }
                    if (!dummyBmp.isRecycled) dummyBmp.recycle()
                }
            } else {
                val maxPerBatch = params.maxBubblesPerRequest
                val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, maxPerBatch)

                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    if (isCancelled()) return PipelineResult(null, failed = true)

                    if (chunks.size > 1) {
                        onProgress("  [Chunk ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}")
                    }

                    val shrunk = MosaicBuilder.shrinkCropsIfTooTall(chunk, params.maxTinggiMosaik, params.jarakAntarPotongan)
                    val mosaic = MosaicBuilder.buildMosaic(shrunk, params)

                    val prompt = Constants.buildPrompt(targetLanguage)
                    val providersChain = listOf(provider) + fallbackProviders

                    for (prov in providersChain) {
                        if (isCancelled()) break
                        onProgress("  Translating with ${prov.providerName}...")
                        try {
                            val result = rateLimiter.executeWithRetry(
                                apiCall = { prov.translateImage(mosaic, prompt) },
                                providerName = prov.providerName,
                                isCancelled = isCancelled,
                                onWait = { msg -> onProgress(msg) }
                            )

                            if (result != null) {
                                val cleaned = JsonUtils.sanitizeJson(result)
                                val parsed = JsonUtils.parseTranslationMap(cleaned)
                                if (parsed.isNotEmpty()) {
                                    allTranslations.putAll(parsed)
                                    if (cacheRepo != null) {
                                        for ((id, text) in parsed) {
                                            val item = cropItems.find { it.id == id }
                                            if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text)
                                        }
                                    }
                                    break
                                } else {
                                    onProgress("  [!] ${prov.providerName} returned unparseable output (raw: ${cleaned.take(80)}). Trying next provider...")
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException || isCancelled()) {
                                throw e
                            }
                            val msg = e.message ?: "Unknown error"
                            onProgress("  [Failover] ${prov.providerName} failed ($msg). Trying fallback provider...")
                        }
                    }
                }
            }
        }

        if (allTranslations.isEmpty()) {
            onProgress("  [!] Translation failed.")
            cacheRepo?.flush()
            return PipelineResult(null, failed = true)
        }

        // ── Render translations ──
        val normalizedTranslations = mutableMapOf<String, String>()
        for ((key, text) in allTranslations) {
            val id = normalizeIdKey(key)
            if (id != null) normalizedTranslations[id] = text
        }

        val workingMat = ImageProcessor.bitmapToMat(bitmap)
        val resultBitmap = try {
            if (params.useInpainting) {
                onProgress("  [OpenCV Inpainting] Erasing original text strokes (Parallel)...")
                val targets = normalizedTranslations.mapNotNull { (id, text) ->
                    if (text.uppercase() == "SKIP" || text.isBlank()) null
                    else coordinateMap[id]
                }
                coroutineScope {
                    targets.map { box ->
                        async(Dispatchers.Default) {
                            synchronized(workingMat) {
                                ImageProcessor.inpaintBubbleText(workingMat, box)
                            }
                        }
                    }.awaitAll()
                }
            }
            ImageProcessor.matToBitmap(workingMat)
        } finally {
            workingMat.release()
        }

        val canvas = Canvas(resultBitmap)

        val translatedCount = renderTranslations(
            canvas = canvas,
            translations = normalizedTranslations,
            coordinateMap = coordinateMap,
            bubbleColors = bubbleColors,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
        )

        val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
        saveBitmap(resultBitmap, outputPath)
        onProgress("  Done! ${translatedCount}/${cropItems.size} bubbles translated.")

        // Persist bubble data + original page so the touch-up editor works later
        // (e.g. when the reader is reopened from History).
        saveEditMetadata(outputPath, bitmap, normalizedTranslations, coordinateMap)

        cacheRepo?.flush()
        return PipelineResult(
            outputPath = outputPath,
            bubblesFound = cropItems.size,
            bubblesTranslated = translatedCount,
            originalBitmap = bitmap,
            translations = normalizedTranslations,
            coordinateMap = coordinateMap,
        )
    }

    /**
     * Batch process multiple images with multi-page batching.
     * Ported from process_image_batch() — stitches bubbles from multiple pages into combined requests.
     */
    suspend fun processImageBatch(
        imagePaths: List<String>,
        outputDir: String,
        cachedPages: List<PageData>? = null,
        pageOffset: Int = 0,
        totalBatchPages: Int = imagePaths.size,
    ): List<PipelineResult> {
        if (imagePaths.isEmpty()) return emptyList()

        // Path-indexed map of previously detected pages (fast retry support).
        // Pages whose bitmaps were recycled are filtered out — they cannot be reused.
        val cacheByPath: Map<String, PageData> = cachedPages
            ?.filter { !it.pil.isRecycled }
            ?.associateBy { it.path } ?: emptyMap()

        if (params.enableDevLogs) {
            onProgress(
                if (cacheByPath.isNotEmpty()) {
                    "[Multi-Page Batch] Reusing ${cacheByPath.size} cached pages where available (skipping YOLO)."
                } else {
                    "[Multi-Page Batch] Processing ${imagePaths.size} pages..."
                }
            )
        }

        val semaphore = kotlinx.coroutines.sync.Semaphore(3)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

        val pageDataList = coroutineScope {
            imagePaths.mapIndexed { idx, imgPath ->
                async(Dispatchers.Default) {
                    if (isCancelled()) {
                        val doneCount = completedCount.incrementAndGet()
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), null, 0, 0,
                            mutableListOf(), mutableMapOf(), failed = true)
                    }

                    val actualPageNum = pageOffset + idx + 1
                    val totalPages = if (totalBatchPages > 0) totalBatchPages else imagePaths.size

                    // Fast retry: reuse a previously detected page, skipping YOLO entirely.
                    // The reused page is marked borrowed so this run never recycles its
                    // bitmaps — they are still owned by the retry cache.
                    cacheByPath[imgPath]?.let { cached ->
                        val doneCount = completedCount.incrementAndGet()
                        val msg = if (params.enableDevLogs) {
                            "  [Page $actualPageNum/$totalPages] Reusing cached ${File(imgPath).name} (${cached.crops.size} bubbles)"
                        } else {
                            "  [Page $actualPageNum/$totalPages] Reusing cached page"
                        }
                        onProgress(msg)
                        onStepProgress((25f * doneCount / imagePaths.size).toInt().coerceIn(1, 25), msg)
                        return@async cached.copy(borrowed = true)
                    }

                    val expectedOutput = MosaicBuilder.makeOutputPath(imgPath, targetLanguage, outputDir)
                    if (File(expectedOutput).exists()) {
                        val doneCount = completedCount.incrementAndGet()
                        if (params.enableDevLogs) {
                            onProgress("  [Page $actualPageNum/$totalPages] Skipping ${File(imgPath).name} (Already translated).")
                        } else {
                            onProgress("  [Page $actualPageNum/$totalPages] Skipping (Already translated).")
                        }
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), null, 0, 0,
                            mutableListOf(), mutableMapOf(), alreadyDone = true)
                    }

                    val bitmap = ImageProcessor.loadBitmap(imgPath)
                    if (bitmap == null) {
                        val doneCount = completedCount.incrementAndGet()
                        onProgress("  [Page $actualPageNum/$totalPages] Failed to load image.")
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), null, 0, 0,
                            mutableListOf(), mutableMapOf(), failed = true)
                    }

                    val imgHeight = bitmap.height
                    val imgWidth = bitmap.width

                    val result = semaphore.withPermit {
                        if (isCancelled()) {
                            PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), null, 0, 0,
                                mutableListOf(), mutableMapOf(), failed = true)
                        } else {
                            // YOLO detection — 3-stage cascade (each stage: distinct conf/iou threshold)
                            val rawBoxes = mutableListOf<IntArray>()
                            for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
                                val detections = yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou) ?: continue
                                for (d in detections) rawBoxes.add(intArrayOf(d.x1, d.y1, d.x2, d.y2))
                            }
                            var filtered = ImageProcessor.removeFalseGiants(rawBoxes)
                            filtered = ImageProcessor.mergeOverlapping(filtered)
                            filtered = ImageProcessor.removeNonsense(filtered, imgWidth, imgHeight)
                            val sfxMat = ImageProcessor.bitmapToMat(bitmap)
                            try {
                                filtered = ImageProcessor.removeSfxAndImages(sfxMat, filtered, params)
                            } finally {
                                sfxMat.release()
                            }

                            val resultBmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val crops = mutableListOf<Pair<String, Bitmap>>()
                            val coordMap = mutableMapOf<String, IntArray>()
                            val bubbleColors = mutableMapOf<String, Int>()

                            val cropMatFull = ImageProcessor.bitmapToMat(bitmap)
                            try {
                                for ((order, box) in filtered.withIndex()) {
                                    val (x1, y1, x2, y2) = box
                                    val boxW = maxOf(1, x2 - x1)
                                    val boxH = maxOf(1, y2 - y1)
                                    val padX = maxOf(params.minPad, (boxW * params.padXRatio).toInt())
                                    val padY = maxOf(params.minPad, (boxH * params.padYRatio).toInt())
                                    val id = "${actualPageNum}_${order + 1}"

                                    val bgColor = ImageProcessor.detectBubbleBackgroundColor(cropMatFull, box)
                                    bubbleColors[id] = bgColor

                                    val (cropX1, cropY1, cropX2, cropY2) = ImageProcessor.smartCropBounds(
                                        box, filtered, imgWidth, imgHeight, padX, padY, params)
                                    val cropMat = cropMatFull.submat(org.opencv.core.Rect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1))
                                    val maskedMat = ImageProcessor.maskOutsideBubble(cropMat, cropX1, cropY1, x1, y1, x2, y2, params)
                                    cropMat.release()

                                    val scale = params.skalaPotonganMosaik
                                    val cropBitmap = ImageProcessor.matToBitmap(maskedMat)
                                    maskedMat.release()
                                    val scaled = if (scale != 1.0) {
                                        val s = Bitmap.createScaledBitmap(cropBitmap,
                                            maxOf(1, (cropBitmap.width * scale).toInt()),
                                            maxOf(1, (cropBitmap.height * scale).toInt()), true)
                                        if (s != cropBitmap && !cropBitmap.isRecycled) cropBitmap.recycle()
                                        s
                                    } else cropBitmap

                                    crops.add(id to scaled)
                                    coordMap[id] = box
                                }
                            } finally {
                                cropMatFull.release()
                            }

                            PageData(imgPath, resultBmp, Canvas(resultBmp), imgWidth, imgHeight,
                                crops.toMutableList(), coordMap, bubbleColors)
                        }
                    }

                    val doneCount = completedCount.incrementAndGet()
                    val phase1Percent = (25f * doneCount / imagePaths.size).toInt().coerceIn(1, 25)
                    val msg = if (!result.failed) {
                        if (params.enableDevLogs) {
                            "  [Page $actualPageNum/$totalPages] Processed ${File(imgPath).name} (Found ${result.crops.size} bubbles)"
                        } else {
                            "  [Page $actualPageNum/$totalPages] Processed (${result.crops.size} bubbles)"
                        }
                    } else {
                        "  [Page $actualPageNum/$totalPages] Failed ${File(imgPath).name}"
                    }
                    onProgress(msg)
                    onStepProgress(phase1Percent, msg)
                    result
                }
            }.awaitAll()
        }
        // Save detected pages for fast retry support — only when this run started from
        // scratch; retry runs consume the cache instead of replacing it.
        if (cachedPages == null) {
            TranslationProgressTracker.cachedPageData = pageDataList
        }

        // Phase 2: Collect all crops across pages and batch. Borrowed (retry-cache) pages
        // are INCLUDED so their bubbles get re-translated on retry; only their bitmaps must
        // never be recycled here because the retry cache still owns them.
        val allCrops = pageDataList.filter { !it.alreadyDone && !it.failed }
            .flatMap { it.crops }
        val borrowedBitmaps: Set<Bitmap> = pageDataList
            .filter { it.borrowed }
            .flatMap { it.crops }
            .map { it.second }
            .toHashSet()

        if (allCrops.isEmpty()) {
            return pageDataList.map { PipelineResult(MosaicBuilder.makeOutputPath(it.path, targetLanguage, outputDir),
                alreadyDone = it.alreadyDone, failed = it.failed) }
        }

        if (params.enableDevLogs) onProgress("  Total bubbles across all pages: ${allCrops.size}")

        // Phase 3: Chunk → Mosaic → LLM
        val cropItems = allCrops.map { MosaicBuilder.CropItem(it.first, it.second) }
        val allTranslations = mutableMapOf<String, String>()

        if (params.useLocalOcr) {
            if (params.enableDevLogs) {
                onProgress("  [Local OCR Engine] Extracting text from ${cropItems.size} bubbles via Google ML Kit (${params.localOcrScript})...")
            } else {
                onProgress("  [Local OCR] Extracting text from ${cropItems.size} bubbles...")
            }
            val maxPerBatch = minOf(params.maxBubblesPerRequest, 6)
            val chunks = MosaicBuilder.chunkCrops(cropItems, maxPerBatch)

            var consecutiveFailures = 0
            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) break
                if (params.enableDevLogs && chunks.size > 1) {
                    onProgress("  [Local OCR Batch ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}")
                }
                val ocrMap = mutableMapOf<String, String>()
                for (item in chunk) {
                    val recognized = com.kzkt.app.core.ocr.LocalOcrEngine.recognizeText(item.bitmap, params.localOcrScript)
                    if (recognized.isNotBlank()) {
                        ocrMap[item.id] = recognized
                        if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> \"$recognized\"")
                    } else {
                        if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> (No text recognized)")
                    }
                }
                if (ocrMap.isEmpty()) {
                    if (params.enableDevLogs) onProgress("  [Local OCR] No text recognized in batch ${chunkIdx + 1}. Continuing...")
                    continue
                }

                val textJson = com.google.gson.Gson().toJson(ocrMap)
                val textPrompt = """
                    You are a master comic & manga translator and editor.
                    The input JSON map contains text extracted via local OCR (which may contain typos, missing characters, OCR noise, or broken words).

                    INSTRUCTIONS:
                    1. Smart OCR Correction: Infer and auto-correct any OCR errors, misread characters, typos, or incomplete words using comic/manga dialogue context before translating.
                    2. Natural Translation: Translate the corrected meaning naturally into $targetLanguage while preserving tone, emotion, and nuance suitable for manga speech bubbles.
                    3. Strict Output Format: Return ONLY a valid JSON object mapping the exact input keys to their translated values. Do not wrap in markdown or add commentary.

                    Input JSON:
                    $textJson
                """.trimIndent()
                val providersChain = listOf(provider) + fallbackProviders
                val dummyBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                var batchSucceeded = false

                for (prov in providersChain) {
                    if (isCancelled()) break
                    onProgress("  Translating text with ${prov.providerName}...")
                    try {
                        val result = rateLimiter.executeWithRetry(
                            apiCall = { prov.translateText(textJson, textPrompt) ?: prov.translateImage(dummyBmp, textPrompt) },
                            providerName = prov.providerName,
                            isCancelled = isCancelled,
                            onWait = { msg -> onProgress(msg) }
                        )
                        if (result != null) {
                            val cleaned = JsonUtils.sanitizeJson(result)
                            val parsed = JsonUtils.parseTranslationMap(cleaned)
                            if (parsed.isNotEmpty()) {
                                allTranslations.putAll(parsed)
                                if (cacheRepo != null) {
                                    for ((id, text) in parsed) {
                                        val item = cropItems.find { it.id == id }
                                        if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text)
                                    }
                                }
                                batchSucceeded = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException || isCancelled()) throw e
                        onProgress("  [Failover] ${prov.providerName} failed: ${e.message}. Trying fallback provider...")
                    }
                }
                if (!dummyBmp.isRecycled) dummyBmp.recycle()

                if (!batchSucceeded) {
                    onProgress("  [!] Batch ${chunkIdx + 1}/${chunks.size} failed all providers (no response received; skipping these bubbles).")
                }
                kotlinx.coroutines.delay(500L)
            }
        } else {
            val chunks = MosaicBuilder.chunkCrops(cropItems, params.maxBubblesPerRequest)

            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) return emptyList()
                val batchPercent = (25 + (65f * (chunkIdx + 1) / chunks.size)).toInt().coerceIn(25, 90)
                val batchMsg = "  [Batch ${chunkIdx + 1}/${chunks.size}] ${chunk.size} bubbles..."
                onProgress(batchMsg)
                onStepProgress(batchPercent, batchMsg)

                val shrunk = MosaicBuilder.shrinkCropsIfTooTall(chunk, params.maxTinggiMosaik, params.jarakAntarPotongan)
                val mosaic = MosaicBuilder.buildMosaic(shrunk, params)
                val prompt = Constants.buildPrompt(targetLanguage)
                val providersChain = listOf(provider) + fallbackProviders
                var batchSucceeded = false

                try {
                    for (prov in providersChain) {
                        if (isCancelled()) break
                        try {
                            val result = rateLimiter.executeWithRetry(
                                apiCall = { prov.translateImage(mosaic, prompt) },
                                providerName = prov.providerName,
                                isCancelled = isCancelled,
                                onWait = { msg ->
                                    onProgress(msg)
                                    onStepProgress(batchPercent, msg)
                                }
                            )
                            if (result != null) {
                                val cleaned = JsonUtils.sanitizeJson(result)
                                val parsed = JsonUtils.parseTranslationMap(cleaned)
                                if (parsed.isNotEmpty()) {
                                    allTranslations.putAll(parsed)
                                    batchSucceeded = true
                                    break
                                } else {
                                    onProgress("  [!] ${prov.providerName} returned unparseable output (raw: ${cleaned.take(80)}). Trying next provider...")
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException || isCancelled()) {
                                throw e
                            }
                            onProgress("  [Failover] ${prov.providerName} failed: ${e.message}. Trying fallback provider...")
                        }
                    }
                    if (!batchSucceeded) {
                        val ids = chunk.joinToString(", ") { it.id }
                        onProgress("  [!] Batch ${chunkIdx + 1} failed all providers. Skipping bubbles: $ids")
                    }
                } finally {
                    if (!mosaic.isRecycled) mosaic.recycle()
                    for (item in shrunk) {
                        if (item.bitmap != cropItems.firstOrNull { it.id == item.id }?.bitmap && !item.bitmap.isRecycled) {
                            item.bitmap.recycle()
                        }
                    }
                }
            }
        }

        if (allTranslations.isEmpty()) {
            onProgress("  [!] All translation attempts failed for this group. Skipping rendering.")
            for ((_, bmp) in allCrops) {
                if (bmp in borrowedBitmaps) continue
                if (!bmp.isRecycled) bmp.recycle()
            }
            cacheRepo?.flush()
            return pageDataList.map { PipelineResult(null, failed = true) }
        }

        // Phase 4: Render per-page
        val results = mutableListOf<PipelineResult>()
        for ((pageIdx, page) in pageDataList.withIndex()) {
            val renderPercent = (90 + (10f * (pageIdx + 1) / pageDataList.size)).toInt().coerceIn(90, 100)
            val renderMsg = "  Rendering page ${pageIdx + 1}/${pageDataList.size}..."
            onStepProgress(renderPercent, renderMsg)

            if (page.alreadyDone) {
                results.add(PipelineResult(MosaicBuilder.makeOutputPath(page.path, targetLanguage, outputDir), alreadyDone = true))
                recyclePage(page)
                continue
            }
            if (page.failed) {
                results.add(PipelineResult(null, failed = true))
                recyclePage(page)
                continue
            }

            // Skip pages whose output already exists — avoid re-translating on re-runs
            val pageOutputPath = MosaicBuilder.makeOutputPath(page.path, targetLanguage, outputDir)
            if (File(pageOutputPath).exists()) {
                results.add(PipelineResult(pageOutputPath, alreadyDone = true))
                recyclePage(page)
                continue
            }

            val renderBitmap = if (params.useInpainting) {
                onProgress("  [OpenCV Inpainting] Erasing original text strokes for ${File(page.path).name}...")
                val workingMat = ImageProcessor.bitmapToMat(page.pil)
                try {
                    val targets = page.coordMap.mapNotNull { (id, box) ->
                        val text = allTranslations[id]
                        if (text != null && text.uppercase() != "SKIP" && text.isNotBlank()) box else null
                    }
                    coroutineScope {
                        targets.map { box ->
                            async(Dispatchers.Default) {
                                synchronized(workingMat) {
                                    ImageProcessor.inpaintBubbleText(workingMat, box)
                                }
                            }
                        }.awaitAll()
                    }
                    ImageProcessor.matToBitmap(workingMat)
                } finally {
                    workingMat.release()
                }
            } else {
                page.pil
            }

            val canvas = Canvas(renderBitmap)
            val translatedCount = renderTranslations(
                canvas = canvas,
                translations = allTranslations,
                coordinateMap = page.coordMap,
                bubbleColors = page.bubbleColors,
                imgWidth = page.imgWidth,
                imgHeight = page.imgHeight,
            )

            saveBitmap(renderBitmap, pageOutputPath)
            results.add(PipelineResult(pageOutputPath, bubblesFound = page.crops.size, bubblesTranslated = translatedCount))

            // Immediately recycle page full-res bitmap, render bitmap, and crop bitmaps for this page
            if (renderBitmap != page.pil && !renderBitmap.isRecycled) renderBitmap.recycle()
            recyclePage(page)
        }

        // Final safety cleanup for any remaining crop bitmaps across allCrops
        // (borrowed retry-cache bitmaps are skipped — the tracker still owns them)
        for ((_, bmp) in allCrops) {
            if (bmp in borrowedBitmaps) continue
            if (!bmp.isRecycled) bmp.recycle()
        }

        cacheRepo?.flush()
        return results
    }

    /**
     * Recycle a page's bitmaps unless it was borrowed from the retry cache
     * (whose bitmaps are still owned by [TranslationProgressTracker]).
     */
    private fun recyclePage(page: PageData) {
        if (page.borrowed) return
        for ((_, cropBmp) in page.crops) {
            if (!cropBmp.isRecycled) cropBmp.recycle()
        }
        if (!page.pil.isRecycled) page.pil.recycle()
    }

    /**
     * Persist bubble coordinates + translations + the original page bitmap so the
     * in-app touch-up editor can restore edit data later (e.g. from History).
     * Keyed by output file name, which is preserved when publishing to MediaStore.
     */
    private fun saveEditMetadata(
        outputPath: String,
        original: Bitmap,
        translations: Map<String, String>,
        coordinates: Map<String, IntArray>,
    ) {
        val ctx = context ?: return
        if (translations.isEmpty() || coordinates.isEmpty()) return
        try {
            com.kzkt.app.data.EditMetadataRepository(ctx)
                .saveForOutput(outputPath, original, translations, coordinates, targetLanguage)
        } catch (_: Exception) {
            // Best-effort — never fail a translation because metadata couldn't be saved.
        }
    }

    private fun saveBitmap(bitmap: Bitmap, path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            val ctx = context
            if (ctx != null) {
                val subDir = file.parentFile?.name ?: "KZKT"
                val uri = FileUtils.saveBitmapToMediaStore(ctx, bitmap, file.name, subDir)
                if (uri == null) {
                    val fallbackFile = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KZKT/${file.name}")
                    fallbackFile.parentFile?.mkdirs()
                    FileOutputStream(fallbackFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            }
        }
    }
}
