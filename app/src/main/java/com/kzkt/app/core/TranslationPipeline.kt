package com.kzkt.app.core

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Environment
import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.core.providers.LlmProvider
import com.kzkt.app.ui.FileUtils
import com.kzkt.app.util.JsonUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
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
    private val glossary: Map<String, String> = emptyMap(),
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
        val rawTexts: Map<String, String>? = null,
        val styles: Map<String, BubbleMeta>? = null,
    )

    data class PageData(
        val path: String,
        val pil: Bitmap,
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

        var bitmap = ImageProcessor.loadBitmap(inputPath) ?: return PipelineResult(null, failed = true)
        
        if (params.useImageUpscaler) {
            val mat = ImageProcessor.bitmapToMat(bitmap)
            val enhanced = ImageProcessor.enhanceImage(mat)
            bitmap.recycle()
            bitmap = ImageProcessor.matToBitmap(enhanced)
            mat.release()
            enhanced.release()
            if (params.enableDevLogs) onProgress("  Image upscaled (resolution doubled).")
        }

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
        val partResults = mutableListOf<PipelineResult>()

        for (i in 0 until splitCount) {
            if (isCancelled()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return PipelineResult(null, failed = true)
            }

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
            if (result.outputPath != null) partResults.add(result)

            // Free the split part (and any copy the sub-pipeline retained for editing —
            // landscape results are recombined below, so the copies are no longer needed).
            result.originalBitmap?.takeIf { it !== partBitmap && !it.isRecycled }?.recycle()
            if (!partBitmap.isRecycled) partBitmap.recycle()

            File(partPath).delete()
        }

        if (partResults.size == splitCount) {
            val partPaths = partResults.mapNotNull { it.outputPath }
            // Recombine right-to-left (manga order): part 0 was the rightmost strip.
            val images = partPaths.mapNotNull { ImageProcessor.loadBitmap(it) }.reversed()
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

            // Cleanup: split-part files and every bitmap used to compose the final page.
            partPaths.forEach { File(it).delete() }
            for (img in resized) {
                if (img !in images && !img.isRecycled) img.recycle() // scaled copies only
            }
            for (img in images) if (!img.isRecycled) img.recycle()

            val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
            saveBitmap(combined, outputPath)

            // Merge each part's edit metadata into the combined page so the touch-up
            // editor works for auto-split (landscape) results too, not just single images.
            saveLandscapeEditMetadata(combined, outputPath, partResults, resized)

            if (!combined.isRecycled) combined.recycle()
            if (!bitmap.isRecycled) bitmap.recycle() // the wide input page is no longer needed
            return PipelineResult(outputPath, bubblesFound = partResults.size)
        }

        if (!bitmap.isRecycled) bitmap.recycle()
        return PipelineResult(null, failed = true)
    }

    /**
     * Merge per-part bubble metadata into the combined landscape page. Parts are laid out
     * left-to-right in the combined image (original part [i] sits at position splitCount-1-i),
     * so each part's bubble boxes are shifted by the cumulative width of everything to its
     * left. IDs are prefixed with the part index to avoid collisions across parts.
     */
    private fun saveLandscapeEditMetadata(
        combined: Bitmap,
        outputPath: String,
        partResults: List<PipelineResult>,
        resized: List<Bitmap>,
    ) {
        if (combined.isRecycled) return
        val ctx = context ?: return
        val splitCount = partResults.size
        val translations = mutableMapOf<String, String>()
        val coords = mutableMapOf<String, IntArray>()
        val rawTexts = mutableMapOf<String, String>()
        var accWidth = 0
        // Iterate parts right-to-left split order (part splitCount-1 is leftmost → offset 0).
        for (i in splitCount - 1 downTo 0) {
            val res = partResults[i]
            val offset = accWidth
            for ((id, text) in res.translations) {
                val newId = "${i}_$id"
                translations[newId] = text
                res.coordinateMap[id]?.let { box ->
                    coords[newId] = intArrayOf(box[0] + offset, box[1], box[2] + offset, box[3])
                }
            }
            for ((id, text) in res.rawTexts ?: emptyMap()) rawTexts["${i}_$id"] = text
            // resized[splitCount-1-i] is this part's strip in combined (left→right) order.
            accWidth += resized.getOrNull(splitCount - 1 - i)?.width ?: 0
        }
        if (translations.isEmpty() || coords.isEmpty()) return
        try {
            com.kzkt.app.data.EditMetadataRepository(ctx)
                .saveForOutput(outputPath, combined, translations, coords, targetLanguage, rawTexts.ifEmpty { null })
        } catch (_: Exception) {
            // Best-effort — never fail a translation because metadata couldn't be saved.
        }
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
        // The input tensor is identical across all 3 stages — preprocess once and reuse.
        val prepared = yolo?.prepareInput(bitmap)
        try {
            for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
                if (isCancelled()) return PipelineResult(null, failed = true)
                val detections = yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou, prepared = prepared) ?: continue
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
                val cached = cacheRepo.getTranslation(crop.bitmap, targetLanguage, provider.providerName, provider.modelName)
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

        val allRawTexts = mutableMapOf<String, String>()
        if (cropsToTranslate.isNotEmpty()) {
            if (params.useLocalOcr) {
                onProgress("  [Local OCR Engine] Extracting text from ${cropsToTranslate.size} speech bubbles via Google ML Kit (auto: Japanese + Latin)...")
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
                        val recognized = com.kzkt.app.core.ocr.LocalOcrEngine.recognizeText(item.bitmap)
                        if (recognized.isNotBlank()) {
                            ocrMap[item.id] = recognized
                            if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> \"$recognized\"")
                        } else {
                            val ocrErr = com.kzkt.app.core.ocr.LocalOcrEngine.lastError
                            if (ocrErr != null) {
                                com.kzkt.app.core.ocr.LocalOcrEngine.lastError = null
                                if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> ML Kit error: $ocrErr")
                            } else if (params.enableDevLogs) {
                                onProgress("  [Local OCR] Bubble ${item.id} -> (No text recognized)")
                            }
                        }
                    }
                    if (ocrMap.isEmpty()) {
                        // ML Kit found no text (stylized fonts / tiny crops / screentones):
                        // fall back to the vision LLM for this chunk instead of dropping it,
                        // which previously failed the entire page.
                        onProgress("  [Local OCR] No text recognized in chunk ${chunkIdx + 1} — falling back to vision for ${chunk.size} bubbles.")
                        translateChunkViaVision(chunk, allTranslations, cropItems)
                        continue
                    }
                    allRawTexts.putAll(ocrMap)

                    val textJson = com.google.gson.Gson().toJson(ocrMap)
                    val sfxInstruction = if (params.translateSfx) {
                        " Sound effects (SFX) like ドドド, バキ, ゴゴゴ MUST be translated into $targetLanguage onomatopoeia (never skip them)."
                    } else {
                        ""
                    }
                    val textPrompt = "You are an expert comic text translator. Translate the text values in the following JSON map into $targetLanguage.$sfxInstruction Return ONLY a valid JSON map with exact matching keys.\n\nInput JSON:\n$textJson"
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
                                            if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
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

                    val prompt = Constants.buildPrompt(targetLanguage, glossary, params.translateSfx)
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
                                            if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
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
        // Since we only have rawTexts if useLocalOcr is true, we pass allRawTexts if it's not empty
        val finalRawTexts = if (params.useLocalOcr) allRawTexts else emptyMap()
        saveEditMetadata(outputPath, bitmap, normalizedTranslations, coordinateMap, finalRawTexts)

        cacheRepo?.flush()
        return PipelineResult(
            outputPath = outputPath,
            bubblesFound = cropItems.size,
            bubblesTranslated = translatedCount,
            originalBitmap = bitmap,
            translations = normalizedTranslations,
            coordinateMap = coordinateMap,
            rawTexts = finalRawTexts,
        )
    }

    /**
     * Vision-LLM fallback for Local OCR mode: when ML Kit finds no text in a chunk
     * (stylized manga fonts, tiny crops, screentone backgrounds), send the chunk as
     * a mosaic to the image-capable provider chain instead of silently skipping
     * those bubbles — which previously made an entire page fail with
     * "No text recognized ... [!] Translation failed.".
     * Returns true when at least one provider produced a parseable translation.
     */
    private suspend fun translateChunkViaVision(
        chunk: List<MosaicBuilder.CropItem>,
        allTranslations: MutableMap<String, String>,
        cropItems: List<MosaicBuilder.CropItem>,
    ): Boolean {
        if (isCancelled()) return false
        val shrunk = MosaicBuilder.shrinkCropsIfTooTall(chunk, params.maxTinggiMosaik, params.jarakAntarPotongan)
        val mosaic = MosaicBuilder.buildMosaic(shrunk, params)
        val prompt = Constants.buildPrompt(targetLanguage, glossary, params.translateSfx)
        val providersChain = listOf(provider) + fallbackProviders
        var succeeded = false
        try {
            for (prov in providersChain) {
                if (isCancelled()) break
                onProgress("  [OCR Fallback] Translating ${chunk.size} bubbles via ${prov.providerName} vision...")
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
                                    if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
                                }
                            }
                            succeeded = true
                            break
                        } else {
                            onProgress("  [!] ${prov.providerName} returned unparseable output (raw: ${cleaned.take(80)}). Trying next provider...")
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || isCancelled()) {
                        throw e
                    }
                    onProgress("  [Failover] ${prov.providerName} failed (${e.message}). Trying fallback provider...")
                }
            }
        } finally {
            if (!mosaic.isRecycled) mosaic.recycle()
            for (item in shrunk) {
                if (item.bitmap != cropItems.firstOrNull { it.id == item.id }?.bitmap && !item.bitmap.isRecycled) {
                    item.bitmap.recycle()
                }
            }
        }
        return succeeded
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
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 0, 0,
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
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 0, 0,
                            mutableListOf(), mutableMapOf(), alreadyDone = true)
                    }

                    var bitmap = ImageProcessor.loadBitmap(imgPath)
                    
                    if (bitmap != null && params.useImageUpscaler) {
                        val mat = ImageProcessor.bitmapToMat(bitmap)
                        val enhanced = ImageProcessor.enhanceImage(mat)
                        bitmap.recycle()
                        bitmap = ImageProcessor.matToBitmap(enhanced)
                        mat.release()
                        enhanced.release()
                    }
                    if (bitmap == null) {
                        val doneCount = completedCount.incrementAndGet()
                        onProgress("  [Page $actualPageNum/$totalPages] Failed to load image.")
                        return@async PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 0, 0,
                            mutableListOf(), mutableMapOf(), failed = true)
                    }

                    val imgHeight = bitmap.height
                    val imgWidth = bitmap.width

                    val result = semaphore.withPermit {
                        if (isCancelled()) {
                            PageData(imgPath, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 0, 0,
                                mutableListOf(), mutableMapOf(), failed = true)
                        } else {
                            // YOLO detection — 3-stage cascade (each stage: distinct conf/iou threshold)
                            // The input tensor is identical across all 3 stages — preprocess once and reuse.
                            val prepared = yolo?.prepareInput(bitmap)
                            val rawBoxes = mutableListOf<IntArray>()
                            for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
                                val detections = yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou, prepared = prepared) ?: continue
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

                            PageData(imgPath, resultBmp, imgWidth, imgHeight,
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
        // Completed pages are recycled (and their outputs exist on disk, so retry skips
        // them); only the interrupted/failed group keeps live bitmaps here, which is
        // exactly the group a retry needs to resume without re-running YOLO.
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
        val batchRawTexts = mutableMapOf<String, String>()

        // Translation-memory lookup (same as the single-image path): bubbles that
        // are pixel-identical to a previously translated crop are served from the
        // local cache without burning an API call. This makes re-translating PDFs /
        // multi-page batches (and repeated bubbles across pages) effectively free.
        val cropsToTranslate = mutableListOf<MosaicBuilder.CropItem>()
        if (cacheRepo != null) {
            for (crop in cropItems) {
                val cached = cacheRepo.getTranslation(crop.bitmap, targetLanguage, provider.providerName, provider.modelName)
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

        if (params.useLocalOcr) {
            if (cropsToTranslate.isNotEmpty()) {
                if (params.enableDevLogs) {
                    onProgress("  [Local OCR Engine] Extracting text from ${cropsToTranslate.size} bubbles via Google ML Kit (auto: Japanese + Latin)...")
                } else {
                    onProgress("  [Local OCR] Extracting text from ${cropsToTranslate.size} bubbles...")
                }
            }
            val maxPerBatch = minOf(params.maxBubblesPerRequest, 6)
            val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, maxPerBatch)

            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) break
                if (params.enableDevLogs && chunks.size > 1) {
                    onProgress("  [Local OCR Batch ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}")
                }
                val ocrMap = mutableMapOf<String, String>()
                for (item in chunk) {
                    val recognized = com.kzkt.app.core.ocr.LocalOcrEngine.recognizeText(item.bitmap)
                    if (recognized.isNotBlank()) {
                        ocrMap[item.id] = recognized
                        if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> \"$recognized\"")
                    } else {
                        val ocrErr = com.kzkt.app.core.ocr.LocalOcrEngine.lastError
                        if (ocrErr != null) {
                            com.kzkt.app.core.ocr.LocalOcrEngine.lastError = null
                            if (params.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> ML Kit error: $ocrErr")
                        } else if (params.enableDevLogs) {
                            onProgress("  [Local OCR] Bubble ${item.id} -> (No text recognized)")
                        }
                    }
                }
                if (ocrMap.isEmpty()) {
                    onProgress("  [Local OCR] No text recognized in batch ${chunkIdx + 1} — falling back to vision for ${chunk.size} bubbles.")
                    translateChunkViaVision(chunk, allTranslations, cropItems)
                    continue
                }
                batchRawTexts.putAll(ocrMap)

                val textJson = com.google.gson.Gson().toJson(ocrMap)
                val textPrompt = """
                    You are a master comic & manga translator and editor.
                    The input JSON map contains text extracted via local OCR (which may contain typos, missing characters, OCR noise, or broken words).

                    INSTRUCTIONS:
                    1. Smart OCR Correction: Infer and auto-correct any OCR errors, misread characters, typos, or incomplete words using comic/manga dialogue context before translating.
                    2. Natural Translation: Translate the corrected meaning naturally into $targetLanguage while preserving tone, emotion, and nuance suitable for manga speech bubbles.
                    ${if (params.translateSfx) "3. Sound effects (SFX) like ドドド, バキ, ゴゴゴ MUST be translated into $targetLanguage onomatopoeia (never skip them)." else ""}
                    4. Strict Output Format: Return ONLY a valid JSON object mapping the exact input keys to their translated values. Do not wrap in markdown or add commentary.

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
                                        if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
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
            val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, params.maxBubblesPerRequest)

            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) return emptyList()
                val batchPercent = (25 + (65f * (chunkIdx + 1) / chunks.size)).toInt().coerceIn(25, 90)
                val batchMsg = "  [Batch ${chunkIdx + 1}/${chunks.size}] ${chunk.size} bubbles..."
                onProgress(batchMsg)
                onStepProgress(batchPercent, batchMsg)

                val shrunk = MosaicBuilder.shrinkCropsIfTooTall(chunk, params.maxTinggiMosaik, params.jarakAntarPotongan)
                val mosaic = MosaicBuilder.buildMosaic(shrunk, params)
                val prompt = Constants.buildPrompt(targetLanguage, glossary, params.translateSfx)
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
                                    if (cacheRepo != null) {
                                        for ((id, text) in parsed) {
                                            val item = cropItems.find { it.id == id }
                                            if (item != null) cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
                                        }
                                    }
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
            results.add(PipelineResult(pageOutputPath, bubblesFound = page.crops.size, bubblesTranslated = translatedCount, rawTexts = if (params.useLocalOcr) batchRawTexts else emptyMap()))

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
        rawTexts: Map<String, String> = emptyMap()
    ) {
        val ctx = context ?: return
        if (translations.isEmpty() || coordinates.isEmpty()) return
        try {
            com.kzkt.app.data.EditMetadataRepository(ctx)
                .saveForOutput(outputPath, original, translations, coordinates, targetLanguage, rawTexts.ifEmpty { null })
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
