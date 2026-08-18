package com.kzkt.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.core.ocr.LocalOcrEngine
import com.kzkt.app.core.providers.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main translation pipeline: detection → filter → mosaic → LLM → render → save.
 */
class TranslationPipeline(
    private val config: PipelineConfig,
    private val providerChain: ProviderChain,
    private val callbacks: PipelineCallbacks,
) {
    // Flat aliases so the long body keeps referring to simple names (zero behavior change).
    private val yolo: YoloOnnx? = config.yolo
    private val provider: LlmProvider = providerChain.provider
    private val textRenderer: TextRenderer = config.textRenderer
    private val params: TweakParams = config.params
    private val rateLimiter: RateLimiter = config.rateLimiter
    private val targetLanguage: String = config.targetLanguage
    private val cacheRepo: com.kzkt.app.data.TranslationCacheRepository? = config.cacheRepo
    private val glossary: Map<String, String> = config.glossary
    private val fallbackProviders: List<LlmProvider> = providerChain.fallbackProviders
    private val context: Context? = config.context
    private val onProgress: (String) -> Unit = callbacks.onProgress
    private val onStepProgress: (Int, String) -> Unit = callbacks.onStepProgress
    private val isCancelled: () -> Boolean = callbacks.isCancelled

    /**
     * Process a single manga page image.
     */
    suspend fun processSingleImage(
        inputPath: String,
        outputDir: String,
    ): PipelineResult {
        if (isCancelled()) return PipelineResult(null, failed = true)

        val imgFile = File(inputPath)
        if (!imgFile.exists()) return PipelineResult(null, failed = true)

        var bitmap = ImageProcessor.loadBitmap(inputPath) ?: return PipelineResult(null, failed = true)

        if (params.engine.useImageUpscaler) {
            val upscaled = ImageInpainting.upscaleBitmap(bitmap)
            bitmap.recycle()
            bitmap = upscaled
            if (params.engine.enableDevLogs) onProgress("  Image upscaled (resolution doubled).")
        }

        // Auto-split landscape
        val splitCount = ImageProcessor.shouldAutoSplit(bitmap)
        if (splitCount > 1) {
            return processLandscape(bitmap, inputPath, outputDir, splitCount)
        }

        return processBitmap(bitmap, inputPath, outputDir)
    }

    private suspend fun processLandscape(
        bitmap: Bitmap,
        inputPath: String,
        outputDir: String,
        splitCount: Int,
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
            val partPath =
                File(
                    outputDir,
                    "${File(inputPath).nameWithoutExtension}_split${i + 1}.png",
                ).absolutePath
            imageSaver.save(partBitmap, partPath)

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
            val resized =
                images.map { bmp ->
                    if (bmp.height != targetH) {
                        val scale = targetH.toDouble() / bmp.height
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), targetH, true)
                    } else {
                        bmp
                    }
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
            imageSaver.save(combined, outputPath)

            // Merge each part's edit metadata into the combined page so the touch-up
            // editor works for auto-split (landscape) results too, not just single images.
            editMetadataSaver.saveLandscape(combined, outputPath, partResults, resized)

            if (!combined.isRecycled) combined.recycle()
            if (!bitmap.isRecycled) bitmap.recycle() // the wide input page is no longer needed
            return PipelineResult(outputPath, bubblesFound = partResults.size)
        }

        if (!bitmap.isRecycled) bitmap.recycle()
        return PipelineResult(null, failed = true)
    }

    /**
     * Shared renderer for the translated-text pass (single-image + batch paths).
     */
    private val resultRenderer by lazy {
        ResultRenderer(textRenderer, params, targetLanguage)
    }

    /**
     * Persists touch-up editor metadata (bubbles + translations + original page).
     */
    private val editMetadataSaver by lazy {
        EditMetadataSaver(context, targetLanguage)
    }

    /**
     * Saves translated pages with extension-matched encoding.
     */
    private val imageSaver by lazy {
        ImageSaver(context, params.render.jpegQuality)
    }

    /**
     * YOLO detection + bubble cropping, shared by the single and batch paths.
     */
    private val pagePreparer by lazy {
        PagePreparer(yolo, params)
    }

    /**
     * Chunk → provider-chain translation (vision + OCR), shared by all paths.
     */
    private val chunkTranslator by lazy {
        ChunkTranslator(
            provider = provider,
            fallbackProviders = fallbackProviders,
            rateLimiter = rateLimiter,
            targetLanguage = targetLanguage,
            cacheRepo = cacheRepo,
            glossary = glossary,
            params = params,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private suspend fun processBitmap(
        bitmap: Bitmap,
        inputPath: String,
        outputDir: String,
    ): PipelineResult {
        onProgress("Translating: ${File(inputPath).name}")

        val imgHeight = bitmap.height
        val imgWidth = bitmap.width

        // ── YOLO Detection (3-stage cascade) + box filtering ──
        val filtered = pagePreparer.detectBubbles(bitmap, isCancelled)
        if (isCancelled()) return PipelineResult(null, failed = true)

        if (params.engine.enableDevLogs) {
            onProgress("  [YOLO Cascade] Filtered to ${filtered.size} speech bubbles.")
        } else {
            onProgress("  Filtered to ${filtered.size} speech bubbles...")
        }

        // ── Free-text detection (outside bubbles, ML Kit) ──
        val freeTextBoxes =
            if (params.engine.translateFreeText) {
                pagePreparer.detectFreeText(bitmap, filtered, isCancelled)
            } else {
                emptyList()
            }
        if (isCancelled()) return PipelineResult(null, failed = true)
        if (params.engine.enableDevLogs && freeTextBoxes.isNotEmpty()) {
            onProgress("  [Free Text] ML Kit found ${freeTextBoxes.size} text regions outside bubbles.")
        }

        if (filtered.isEmpty() && freeTextBoxes.isEmpty()) {
            onProgress("  No text bubbles found.")
            val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
            imageSaver.save(bitmap, outputPath)
            return PipelineResult(outputPath)
        }

        // ── Crop extraction & background color detection ──
        val cropResult = pagePreparer.cropBubbles(bitmap, filtered, idPrefix = "")
        val ftResult =
            if (freeTextBoxes.isNotEmpty()) {
                pagePreparer.cropFreeText(bitmap, freeTextBoxes, idPrefix = "")
            } else {
                PagePreparer.CropResult(mutableListOf(), mutableMapOf(), mutableMapOf())
            }
        val cropItems = (cropResult.crops + ftResult.crops).map { MosaicBuilder.CropItem(it.first, it.second) }.toMutableList()
        val coordinateMap = (cropResult.coordMap + ftResult.coordMap).toMutableMap()
        val bubbleColors = (cropResult.bubbleColors + ftResult.bubbleColors).toMutableMap()

        // ── Mosaic → LLM Translate ──
        val allTranslations = mutableMapOf<String, String>()
        val (cachedHit, cropsToTranslate) = chunkTranslator.filterCached(cropItems)
        allTranslations.putAll(cachedHit)
        if (allTranslations.isNotEmpty()) {
            onProgress("  [Cache Hit] Found ${allTranslations.size}/${cropItems.size} cached translations locally.")
        }

        val allRawTexts = mutableMapOf<String, String>()
        if (cropsToTranslate.isNotEmpty()) {
            if (params.engine.useLocalOcr) {
                onProgress(
                    "  [Local OCR Engine] Extracting text from ${cropsToTranslate.size} speech bubbles via Google ML Kit (auto: Japanese + Latin)...",
                )
                val maxPerBatch = minOf(params.engine.maxBubblesPerRequest, Constants.OCR_MAX_BATCH_SIZE)
                val ocrCropItems = cropsToTranslate.map { MosaicBuilder.CropItem(it.id, it.bitmap) }
                val chunks = MosaicBuilder.chunkCrops(ocrCropItems, maxPerBatch)

                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    if (isCancelled()) break
                    if (chunks.size > 1) {
                        onProgress(
                            "  [Local OCR Chunk ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}",
                        )
                    }
                    val ocrResult =
                        chunkTranslator.translateOcrChunk(
                            chunk = chunk,
                            cropItems = ocrCropItems,
                            allTranslations = allTranslations,
                            rawTexts = allRawTexts,
                            textPrompt = { textJson ->
                                val sfxInstruction =
                                    if (params.engine.translateSfx) {
                                        " Sound effects (SFX) like ドドド, バキ, ゴゴゴ MUST be translated into $targetLanguage onomatopoeia (never skip them)."
                                    } else {
                                        ""
                                    }
                                "You are an expert comic text translator. Translate the text values in the following JSON map into $targetLanguage.$sfxInstruction Return ONLY a valid JSON map with exact matching keys.\n\nInput JSON:\n$textJson"
                            },
                        )
                    if (ocrResult.ocrMap.isEmpty()) {
                        // ML Kit found no text (stylized fonts / tiny crops / screentones):
                        // fall back to the vision LLM for this chunk instead of dropping it,
                        // which previously failed the entire page.
                        onProgress(
                            "  [Local OCR] No text recognized in chunk ${chunkIdx + 1} — falling back to vision for ${chunk.size} bubbles.",
                        )
                        translateChunkViaVision(chunk, allTranslations, cropItems)
                        continue
                    }
                }
            } else {
                val maxPerBatch = params.engine.maxBubblesPerRequest
                val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, maxPerBatch)

                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    if (isCancelled()) return PipelineResult(null, failed = true)

                    if (chunks.size > 1) {
                        onProgress("  [Chunk ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}")
                    }

                    translateChunkWithCoverageRetry(
                        chunk = chunk,
                        cropItems = cropItems,
                        allTranslations = allTranslations,
                        chunkLabel = "Chunk ${chunkIdx + 1}/${chunks.size}",
                    )
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
            val id = ChunkTranslator.normalizeIdKey(key)
            if (id != null) normalizedTranslations[id] = text
        }

        val resultBitmap =
            if (params.render.useInpainting) {
                onProgress("  [OpenCV Inpainting] Erasing original text strokes (Parallel)...")
                val workingMat = ImageProcessor.bitmapToMat(bitmap)
                try {
                    ImageInpainting.inpaintTranslated(workingMat, normalizedTranslations, coordinateMap)
                    ImageProcessor.matToBitmap(workingMat)
                } finally {
                    workingMat.release()
                }
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }

        val canvas = Canvas(resultBitmap)

        val freeTextIds = coordinateMap.keys.filter { ChunkTranslator.isFreeTextId(it) }.toSet()
        val translatedCount =
            resultRenderer.render(
                canvas = canvas,
                translations = normalizedTranslations,
                coordinateMap = coordinateMap,
                bubbleColors = bubbleColors,
                imgWidth = imgWidth,
                imgHeight = imgHeight,
                freeTextIds = freeTextIds,
            )

        val outputPath = MosaicBuilder.makeOutputPath(inputPath, targetLanguage, outputDir)
        imageSaver.save(resultBitmap, outputPath)
        val ftCount = ftResult.crops.size
        onProgress(
            "  Done! $translatedCount/${cropItems.size} regions translated " +
                "(${cropResult.crops.size} bubbles, $ftCount free text).",
        )

        // Persist bubble data + original page so the touch-up editor works later
        // (e.g. when the reader is reopened from History).
        // Since we only have rawTexts if useLocalOcr is true, we pass allRawTexts if it's not empty
        val finalRawTexts = if (params.engine.useLocalOcr) allRawTexts else emptyMap()
        editMetadataSaver.save(outputPath, bitmap, normalizedTranslations, coordinateMap, finalRawTexts)

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
        // The fallback path must never abort the whole run: a provider error while
        // building/sending the mosaic would otherwise bubble up to the worker and
        // force a manual retry. Catch it here, log it, and move on — the remaining
        // bubbles in later chunks still get translated.
        return try {
            chunkTranslator.translateVisionChunk(
                chunk = chunk,
                cropItems = cropItems,
                allTranslations = allTranslations,
                logStart = { "  [OCR Fallback] Translating ${chunk.size} bubbles via ${it.providerName} vision..." },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress("  [!] Vision fallback failed (${e.message ?: "Unknown error"}) — skipping those bubbles.")
            false
        }
    }

    /**
     * Translate [chunk] via vision and verify every sent bubble came back with a
     * usable translation. A provider can return unparseable output (or a "repaired"
     * JSON whose keys no longer match the sent ids), which would otherwise render
     * as "Done! 0/N regions translated" with no error — the page gets saved with
     * the original text. Missing ids are retried once, split into two smaller
     * sub-chunks: smaller mosaics are far less likely to be truncated/unparseable
     * than one large 11-bubble image.
     */
    private suspend fun translateChunkWithCoverageRetry(
        chunk: List<MosaicBuilder.CropItem>,
        cropItems: List<MosaicBuilder.CropItem>,
        allTranslations: MutableMap<String, String>,
        chunkLabel: String,
        onWait: ((String) -> Unit)? = null,
    ) {
        if (chunk.isEmpty() || isCancelled()) return
        chunkTranslator.translateVisionChunk(chunk, cropItems, allTranslations, onWait = onWait)
        val missing = chunk.filter { !ChunkTranslator.hasTranslation(it.id, allTranslations) }
        if (missing.isEmpty()) return

        val missingIds = missing.joinToString(", ") { it.id }
        onProgress(
            "  [!] $chunkLabel: ${missing.size}/${chunk.size} bubbles missing from provider response (ids: $missingIds). Retrying in smaller chunks...",
        )
        for (half in missing.chunked((missing.size + 1) / 2)) {
            if (isCancelled()) break
            chunkTranslator.translateVisionChunk(half, cropItems, allTranslations, onWait = onWait)
        }
        val stillMissing = missing.filter { !ChunkTranslator.hasTranslation(it.id, allTranslations) }
        if (stillMissing.isNotEmpty()) {
            val suffix = if (params.engine.enableDevLogs) " (${stillMissing.joinToString { it.id }})" else ""
            onProgress("  [!] $chunkLabel: ${stillMissing.size}/${chunk.size} bubbles still missing after retry$suffix.")
        }
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
        val cacheByPath: Map<String, PageData> =
            cachedPages
                ?.filter { !it.pil.isRecycled }
                ?.associateBy { it.path } ?: emptyMap()

        if (params.engine.enableDevLogs) {
            onProgress(
                if (cacheByPath.isNotEmpty()) {
                    "[Multi-Page Batch] Reusing ${cacheByPath.size} cached pages where available (skipping YOLO)."
                } else {
                    "[Multi-Page Batch] Processing ${imagePaths.size} pages..."
                },
            )
        }

        val semaphore = kotlinx.coroutines.sync.Semaphore(Constants.MAX_CONCURRENT_PAGE_LOADS)
        val completedCount = AtomicInteger(0)
        // Globally unique free-text id counter across the whole batch. Bare `ftN`
        // ids (no page prefix) are echoed reliably by vision LLMs — page-prefixed
        // ids like `2_ft1` were dropped from batch responses, silently skipping
        // free-text translation while single images worked fine.
        val ftIdCounter = AtomicInteger(0)

        val pageDataList =
            coroutineScope {
                imagePaths
                    .mapIndexed { idx, imgPath ->
                        async(Dispatchers.Default) {
                            if (isCancelled()) {
                                val doneCount = completedCount.incrementAndGet()
                                return@async PageData(
                                    imgPath,
                                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                                    0,
                                    0,
                                    mutableListOf(),
                                    mutableMapOf(),
                                    failed = true,
                                )
                            }

                            val actualPageNum = pageOffset + idx + 1
                            val totalPages = if (totalBatchPages > 0) totalBatchPages else imagePaths.size

                            // Fast retry: reuse a previously detected page, skipping YOLO entirely.
                            // The reused page is marked borrowed so this run never recycles its
                            // bitmaps — they are still owned by the retry cache.
                            cacheByPath[imgPath]?.let { cached ->
                                val doneCount = completedCount.incrementAndGet()
                                val msg =
                                    if (params.engine.enableDevLogs) {
                                        "  [Page $actualPageNum/$totalPages] Reusing cached ${File(
                                            imgPath,
                                        ).name} (${cached.crops.size} bubbles)"
                                    } else {
                                        "  [Page $actualPageNum/$totalPages] Reusing cached page"
                                    }
                                onProgress(msg)
                                onStepProgress(
                                    (Constants.PROGRESS_DETECTION_END.toFloat() * doneCount / imagePaths.size).toInt().coerceIn(
                                        1,
                                        Constants.PROGRESS_DETECTION_END,
                                    ),
                                    msg,
                                )
                                return@async cached.copy(borrowed = true)
                            }

                            val expectedOutput = MosaicBuilder.makeOutputPath(imgPath, targetLanguage, outputDir)
                            if (File(expectedOutput).exists()) {
                                val doneCount = completedCount.incrementAndGet()
                                if (params.engine.enableDevLogs) {
                                    onProgress("  [Page $actualPageNum/$totalPages] Skipping ${File(imgPath).name} (Already translated).")
                                } else {
                                    onProgress("  [Page $actualPageNum/$totalPages] Skipping (Already translated).")
                                }
                                return@async PageData(
                                    imgPath,
                                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                                    0,
                                    0,
                                    mutableListOf(),
                                    mutableMapOf(),
                                    alreadyDone = true,
                                )
                            }

                            var bitmap = ImageProcessor.loadBitmap(imgPath)

                            if (bitmap != null && params.engine.useImageUpscaler) {
                                val upscaled = ImageInpainting.upscaleBitmap(bitmap)
                                bitmap.recycle()
                                bitmap = upscaled
                            }
                            if (bitmap == null) {
                                val doneCount = completedCount.incrementAndGet()
                                onProgress("  [Page $actualPageNum/$totalPages] Failed to load image.")
                                return@async PageData(
                                    imgPath,
                                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                                    0,
                                    0,
                                    mutableListOf(),
                                    mutableMapOf(),
                                    failed = true,
                                )
                            }

                            val imgHeight = bitmap.height
                            val imgWidth = bitmap.width

                            val result =
                                semaphore.withPermit {
                                    if (isCancelled()) {
                                        PageData(
                                            imgPath,
                                            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                                            0,
                                            0,
                                            mutableListOf(),
                                            mutableMapOf(),
                                            failed = true,
                                        )
                                    } else {
                                        val filtered = pagePreparer.detectBubbles(bitmap)

                                        // Use the loaded page bitmap directly as the render surface — the
                                        // full-res copy was redundant (rendering draws on page.pil when not
                                        // inpainting, and inpainting builds a fresh bitmap anyway). Saves
                                        // ~23-93 MB per page × 3 concurrent pages in batch mode.
                                        val cropResult = pagePreparer.cropBubbles(bitmap, filtered, idPrefix = "${actualPageNum}_")

                                        val ftResult =
                                            if (params.engine.translateFreeText) {
                                                val ftBoxes = pagePreparer.detectFreeText(bitmap, filtered)
                                                if (ftBoxes.isNotEmpty()) {
                                                    pagePreparer.cropFreeText(
                                                        bitmap,
                                                        ftBoxes,
                                                        idPrefix = "",
                                                        idStart = ftIdCounter.getAndAdd(ftBoxes.size),
                                                    )
                                                } else {
                                                    PagePreparer.CropResult(mutableListOf(), mutableMapOf(), mutableMapOf())
                                                }
                                            } else {
                                                PagePreparer.CropResult(mutableListOf(), mutableMapOf(), mutableMapOf())
                                            }

                                        val crops = (cropResult.crops + ftResult.crops).toMutableList()
                                        val coordMap = (cropResult.coordMap + ftResult.coordMap).toMutableMap()
                                        val colors = (cropResult.bubbleColors + ftResult.bubbleColors).toMutableMap()

                                        PageData(imgPath, bitmap, imgWidth, imgHeight, crops, coordMap, colors)
                                    }
                                }

                            val doneCount = completedCount.incrementAndGet()
                            val phase1Percent =
                                (Constants.PROGRESS_DETECTION_END.toFloat() * doneCount / imagePaths.size).toInt().coerceIn(
                                    1,
                                    Constants.PROGRESS_DETECTION_END,
                                )
                            val msg =
                                if (!result.failed) {
                                    if (params.engine.enableDevLogs) {
                                        "  [Page $actualPageNum/$totalPages] Processed ${File(
                                            imgPath,
                                        ).name} (Found ${result.crops.size} bubbles)"
                                    } else {
                                        "  [Page $actualPageNum/$totalPages] Processed (${result.crops.size} bubbles)"
                                    }
                                } else {
                                    "  [Page $actualPageNum/$totalPages] Failed ${File(imgPath).name}"
                                }
                            onProgress(msg)
                            onStepProgress(phase1Percent, msg)
                            if (params.engine.enableDevLogs && params.engine.translateFreeText) {
                                val ftCount = result.coordMap.keys.count { ChunkTranslator.isFreeTextId(it) }
                                if (ftCount > 0) {
                                    onProgress("  [Free Text] Page $actualPageNum/$totalPages: detected $ftCount text regions.")
                                } else {
                                    val ocrErr = LocalOcrEngine.lastError
                                    val ocrDetail = if (ocrErr != null) "error: $ocrErr" else "found none"
                                    onProgress("  [Free Text] Page $actualPageNum/$totalPages: no text regions (ML Kit $ocrDetail).")
                                }
                            }
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
        val allCrops =
            pageDataList
                .filter { !it.alreadyDone && !it.failed }
                .flatMap { it.crops }
        val borrowedBitmaps: Set<Bitmap> =
            pageDataList
                .filter { it.borrowed }
                .flatMap { it.crops }
                .map { it.second }
                .toHashSet()

        if (allCrops.isEmpty()) {
            return pageDataList.map {
                PipelineResult(
                    MosaicBuilder.makeOutputPath(it.path, targetLanguage, outputDir),
                    alreadyDone = it.alreadyDone,
                    failed = it.failed,
                )
            }
        }

        if (params.engine.enableDevLogs) onProgress("  Total bubbles across all pages: ${allCrops.size}")

        // Phase 3: Chunk → Mosaic → LLM
        val cropItems = allCrops.map { MosaicBuilder.CropItem(it.first, it.second) }
        val allTranslations = mutableMapOf<String, String>()
        val batchRawTexts = mutableMapOf<String, String>()

        // Translation-memory lookup (shared with the single-image path): bubbles
        // that are pixel-identical to a previously translated crop are served from
        // the local cache without burning an API call. This makes re-translating
        // PDFs / multi-page batches (and repeated bubbles across pages) free.
        val (cachedHit, cropsToTranslate) = chunkTranslator.filterCached(cropItems)
        allTranslations.putAll(cachedHit)
        if (allTranslations.isNotEmpty()) {
            onProgress("  [Cache Hit] Found ${allTranslations.size}/${cropItems.size} cached translations locally.")
        }

        if (params.engine.useLocalOcr) {
            if (cropsToTranslate.isNotEmpty()) {
                if (params.engine.enableDevLogs) {
                    onProgress(
                        "  [Local OCR Engine] Extracting text from ${cropsToTranslate.size} bubbles via Google ML Kit (auto: Japanese + Latin)...",
                    )
                } else {
                    onProgress("  [Local OCR] Extracting text from ${cropsToTranslate.size} bubbles...")
                }
            }
            val maxPerBatch = minOf(params.engine.maxBubblesPerRequest, 12)
            val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, maxPerBatch)

            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) break
                if (params.engine.enableDevLogs && chunks.size > 1) {
                    onProgress(
                        "  [Local OCR Batch ${chunkIdx + 1}/${chunks.size}] Processing bubbles ${chunk.first().id}..${chunk.last().id}",
                    )
                }
                val ocrResult =
                    chunkTranslator.translateOcrChunk(
                        chunk = chunk,
                        cropItems = cropItems,
                        allTranslations = allTranslations,
                        rawTexts = batchRawTexts,
                        textPrompt = { textJson ->
                            """
                            You are a master comic & manga translator and editor.
                            The input JSON map contains text extracted via local OCR (which may contain typos, missing characters, OCR noise, or broken words).

                            INSTRUCTIONS:
                            1. Smart OCR Correction: Infer and auto-correct any OCR errors, misread characters, typos, or incomplete words using comic/manga dialogue context before translating.
                            2. Natural Translation: Translate the corrected meaning naturally into $targetLanguage while preserving tone, emotion, and nuance suitable for manga speech bubbles.
                            ${if (params.engine.translateSfx) "3. Sound effects (SFX) like ドドド, バキ, ゴゴゴ MUST be translated into $targetLanguage onomatopoeia (never skip them)." else ""}
                            4. Strict Output Format: Return ONLY a valid JSON object mapping the exact input keys to their translated values. Do not wrap in markdown or add commentary.

                            Input JSON:
                            $textJson
                            """.trimIndent()
                        },
                    )
                if (ocrResult.ocrMap.isEmpty()) {
                    onProgress(
                        "  [Local OCR] No text recognized in batch ${chunkIdx + 1} — falling back to vision for ${chunk.size} bubbles.",
                    )
                    translateChunkViaVision(chunk, allTranslations, cropItems)
                    continue
                }

                if (!ocrResult.translated) {
                    onProgress(
                        "  [!] Batch ${chunkIdx + 1}/${chunks.size} failed all providers (no response received; skipping these bubbles).",
                    )
                } else {
                    // A provider may return valid JSON whose keys do not correspond to
                    // the sent crop ids (e.g. it drops the page prefix or rewrites the
                    // ids). Those translations can never be looked up at render time,
                    // so the batch would silently vanish — detect it here and re-run
                    // just the missing bubbles through the vision (mosaic) path.
                    // Only ids that were actually sent to the LLM can be missing:
                    // bubbles ML Kit found no text in were never part of the request,
                    // so the provider cannot echo them — treating them as missing
                    // would push every no-text bubble through a pointless vision
                    // round-trip (and re-render the original script when the vision
                    // model fails to translate it).
                    val sentIds = ocrResult.ocrMap.keys.toHashSet()
                    val missing = BatchTranslationPlanner.missingEchoedIds(chunk, sentIds, allTranslations)
                    if (missing.isNotEmpty()) {
                        if (params.engine.enableDevLogs) {
                            val ids = missing.joinToString(", ") { it.id }
                            val detail = "ids not echoed by provider (missing: $ids) — falling back to vision."
                            onProgress("  [!] Batch ${chunkIdx + 1}/${chunks.size}: ${missing.size}/${chunk.size} $detail")
                        } else {
                            val summary = "ids not echoed by provider — falling back to vision."
                            onProgress("  [!] Batch ${chunkIdx + 1}/${chunks.size}: ${missing.size}/${chunk.size} $summary")
                        }
                        translateChunkViaVision(missing, allTranslations, cropItems)
                    } else if (params.engine.enableDevLogs) {
                        onProgress("  [Local OCR] Batch ${chunkIdx + 1}/${chunks.size}: all ${chunk.size} ids echoed correctly.")
                    }
                }
            }
        } else {
            val chunks = MosaicBuilder.chunkCrops(cropsToTranslate, params.engine.maxBubblesPerRequest)

            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (isCancelled()) return emptyList()
                val batchPercent =
                    (
                        Constants.PROGRESS_DETECTION_END +
                            ((Constants.PROGRESS_TRANSLATE_END - Constants.PROGRESS_DETECTION_END).toFloat() * (chunkIdx + 1) / chunks.size)
                    ).toInt()
                        .coerceIn(Constants.PROGRESS_DETECTION_END, Constants.PROGRESS_TRANSLATE_END)
                val batchMsg = "  [Batch ${chunkIdx + 1}/${chunks.size}] ${chunk.size} bubbles..."
                onProgress(batchMsg)
                onStepProgress(batchPercent, batchMsg)

                translateChunkWithCoverageRetry(
                    chunk = chunk,
                    cropItems = cropItems,
                    allTranslations = allTranslations,
                    chunkLabel = "Batch ${chunkIdx + 1}/${chunks.size}",
                    onWait = { msg -> onStepProgress(batchPercent, msg) },
                )
            }
        }

        if (params.engine.enableDevLogs && params.engine.translateFreeText) {
            val ftKeys = allTranslations.keys.filter { ChunkTranslator.isFreeTextId(it) }
            val ftKeyText = if (ftKeys.isEmpty()) "NONE" else ftKeys.joinToString(", ")
            onProgress("  [Free Text] Translated keys: $ftKeyText (total ${allTranslations.size})")
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
        // Mirror the single-image path: normalize every LLM-returned key before
        // rendering. Batch previously passed raw allTranslations keys, so a vision
        // model that rewrote an id (e.g. "1_ft1" -> "ft1" or "1 ft1") silently
        // dropped that free-text region at render — bubbles usually survived
        // because plain numeric ids are echoed verbatim.
        // "ft1" -> translation for ids the model returned WITHOUT the page prefix
        // ("1_ft1" -> "ft1"). Page-prefixed ids always win; the bare form is only
        // a fallback for exactly this rewrite.
        val normalized = BatchTranslationPlanner.normalizeTranslations(allTranslations)
        val normalizedAll = normalized.byId
        val bareFtLookup = normalized.bareFtLookup

        val results = mutableListOf<PipelineResult>()
        for ((pageIdx, page) in pageDataList.withIndex()) {
            results.add(
                renderBatchPage(
                    page = page,
                    pageIdx = pageIdx,
                    pageCount = pageDataList.size,
                    normalized = normalized,
                    outputDir = outputDir,
                    batchRawTexts = batchRawTexts,
                ),
            )
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
     * Render one page of a multi-page batch: resolve its translations, draw the
     * translated text (with optional inpainting), save the output, and recycle
     * the page's bitmaps. Returns the [PipelineResult] for this page.
     */
    private suspend fun renderBatchPage(
        page: PageData,
        pageIdx: Int,
        pageCount: Int,
        normalized: BatchTranslationPlanner.NormalizedTranslations,
        outputDir: String,
        batchRawTexts: Map<String, String>,
    ): PipelineResult {
        val renderPercent =
            (
                Constants.PROGRESS_TRANSLATE_END +
                    ((Constants.PROGRESS_RENDER_END - Constants.PROGRESS_TRANSLATE_END).toFloat() * (pageIdx + 1) / pageCount)
            ).toInt()
                .coerceIn(Constants.PROGRESS_TRANSLATE_END, Constants.PROGRESS_RENDER_END)
        val renderMsg = "  Rendering page ${pageIdx + 1}/$pageCount..."
        onStepProgress(renderPercent, renderMsg)

        if (page.alreadyDone) {
            val alreadyPath = MosaicBuilder.makeOutputPath(page.path, targetLanguage, outputDir)
            recyclePage(page)
            return PipelineResult(alreadyPath, alreadyDone = true)
        }
        if (page.failed) {
            recyclePage(page)
            return PipelineResult(null, failed = true)
        }

        // Skip pages whose output already exists — avoid re-translating on re-runs
        val pageOutputPath = MosaicBuilder.makeOutputPath(page.path, targetLanguage, outputDir)
        if (File(pageOutputPath).exists()) {
            recyclePage(page)
            return PipelineResult(pageOutputPath, alreadyDone = true)
        }

        // Resolve this page's translations from the normalized map. Every page
        // coordinate id is looked up directly; free-text ids additionally fall
        // back to the bare "ftN" form in case the model dropped the page prefix.
        val pageTranslations = BatchTranslationPlanner.resolvePageTranslations(page.coordMap.keys, normalized)
        if (params.engine.enableDevLogs && params.engine.translateFreeText) {
            val (ftMatched, ftInPage) = BatchTranslationPlanner.freeTextMatchCounts(page.coordMap.keys, pageTranslations)
            if (ftInPage > 0) {
                onProgress("  [Free Text] Page ${pageIdx + 1}: matched $ftMatched/$ftInPage free-text regions for render.")
            }
        }
        if (params.engine.enableDevLogs && page.coordMap.isNotEmpty()) {
            val matchedAll = pageTranslations.size
            val totalAll = page.coordMap.size
            if (matchedAll == 0) {
                onProgress("  [!] Page ${pageIdx + 1}: 0/$totalAll regions matched — page will be saved WITHOUT translations.")
            } else {
                onProgress("  [Render] Page ${pageIdx + 1}: matched $matchedAll/$totalAll regions.")
            }
        }

        val renderBitmap =
            if (params.render.useInpainting) {
                onProgress("  [OpenCV Inpainting] Erasing original text strokes for ${File(page.path).name}...")
                val workingMat = ImageProcessor.bitmapToMat(page.pil)
                try {
                    ImageInpainting.inpaintTranslated(workingMat, pageTranslations, page.coordMap)
                    ImageProcessor.matToBitmap(workingMat)
                } finally {
                    workingMat.release()
                }
            } else {
                // page.pil is an immutable decodeFile() bitmap; the Canvas below needs a
                // mutable surface (regression from ed0031b4 which dropped the render copy).
                page.pil.copy(Bitmap.Config.ARGB_8888, true)
            }

        val canvas = Canvas(renderBitmap)
        val freeTextIds =
            page.coordMap.keys
                .filter { ChunkTranslator.isFreeTextId(it) }
                .toSet()
        val translatedCount =
            resultRenderer.render(
                canvas = canvas,
                translations = pageTranslations,
                coordinateMap = page.coordMap,
                bubbleColors = page.bubbleColors,
                imgWidth = page.imgWidth,
                imgHeight = page.imgHeight,
                freeTextIds = freeTextIds,
            )

        imageSaver.save(renderBitmap, pageOutputPath)

        val finalRawTexts = if (params.engine.useLocalOcr) batchRawTexts else emptyMap()
        editMetadataSaver.save(pageOutputPath, page.pil, pageTranslations, page.coordMap, finalRawTexts)

        // Immediately recycle page full-res bitmap, render bitmap, and crop bitmaps for this page
        if (renderBitmap != page.pil && !renderBitmap.isRecycled) renderBitmap.recycle()
        recyclePage(page)
        return PipelineResult(
            pageOutputPath,
            bubblesFound = page.crops.size,
            bubblesTranslated = translatedCount,
            rawTexts = if (params.engine.useLocalOcr) batchRawTexts else emptyMap(),
        )
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
}
