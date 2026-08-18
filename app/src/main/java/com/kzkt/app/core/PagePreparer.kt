package com.kzkt.app.core

import android.graphics.Bitmap
import com.kzkt.app.core.ocr.LocalOcrEngine
import com.kzkt.app.core.ocr.OcrScript

/**
 * Page-level detection + cropping: runs the 3-stage YOLO cascade, filters the
 * boxes, and produces the bubble crops used for translation.
 *
 * Extracted from TranslationPipeline because the single-image and batch paths
 * previously duplicated this ~120-line block twice.
 */
class PagePreparer(
    private val yolo: YoloOnnx?,
    private val params: Config.TweakParams,
) {
    /**
     * 3-stage YOLO cascade (each stage: distinct conf/iou threshold) followed by
     * the box filters (false giants, overlaps, nonsense, SFX/images).
     *
     * The input tensor is identical across all 3 stages — preprocess once and reuse.
     * [isCancelled] is checked between stages so a user cancel stops detection promptly.
     */
    fun detectBubbles(
        bitmap: Bitmap,
        isCancelled: () -> Boolean = { false },
    ): List<IntArray> {
        val prepared = yolo?.prepareInput(bitmap)
        val rawBoxes = mutableListOf<IntArray>()
        for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
            if (isCancelled()) break
            val detections =
                yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou, prepared = prepared)
                    ?: continue
            for (d in detections) {
                rawBoxes.add(intArrayOf(d.x1, d.y1, d.x2, d.y2))
            }
        }

        var filtered = ImageProcessor.removeFalseGiants(rawBoxes)
        filtered = ImageProcessor.mergeOverlapping(filtered)
        filtered = ImageProcessor.removeNonsense(filtered, bitmap.width, bitmap.height)
        val sfxMat = ImageProcessor.bitmapToMat(bitmap)
        try {
            filtered = ImageProcessor.removeSfxAndImages(sfxMat, filtered, params)
        } finally {
            sfxMat.release()
        }
        return filtered
    }

    /**
     * ML Kit full-page text detection for the "translate free text" feature.
     * Returns text-block boxes that are NOT inside (or heavily overlapping) a
     * YOLO speech bubble — those are already handled by the bubble path. Runs
     * only when [Config.TweakParams.EngineParams.translateFreeText] is enabled.
     */
    suspend fun detectFreeText(
        bitmap: Bitmap,
        bubbleBoxes: List<IntArray>,
        isCancelled: () -> Boolean = { false },
    ): List<IntArray> {
        if (isCancelled()) return emptyList()
        val script = OcrScript.fromKey(params.engine.ocrScript)
        val regions = LocalOcrEngine.recognizeTextRegions(bitmap, script, excludeBoxes = bubbleBoxes)
        if (isCancelled()) return emptyList()

        val filtered =
            regions.mapNotNull { region ->
                val box = region.box
                val cx = (box[0] + box[2]) / 2
                val cy = (box[1] + box[3]) / 2

                // Region center inside a bubble → that text is the bubble dialogue.
                val insideBubble = bubbleBoxes.any { b -> cx >= b[0] && cx <= b[2] && cy >= b[1] && cy <= b[3] }
                if (insideBubble) return@mapNotNull null

                // Heavy overlap with a bubble (e.g. caption box touching a bubble).
                val overlapsBubble = bubbleBoxes.any { b -> ImageProcessor.rectIou(box, b) >= 0.3 }
                if (overlapsBubble) return@mapNotNull null

                box
            }
        if (filtered.isEmpty()) return emptyList()

        // Merge text blocks that are close together so adjacent text (a caption
        // box's lines, nearby SFX) becomes ONE region instead of many small boxes.
        return ImageProcessor.mergeNearbyTextBoxes(filtered)
    }

    /**
     * Crop each free-text box with light padding (no bubble masking — these are
     * rectangular text regions). IDs are `idPrefix + "ft" + (idStart + order + 1)`,
     * e.g. `ft1` for single pages, `ft1`/`ft2`... globally unique across a batch
     * (the caller advances [idStart] per page) — never colliding with the numeric
     * bubble ids (`1`, `1_1`, ...). Bare `ftN` ids are used everywhere because
     * vision LLMs reliably echo them back, while page-prefixed ids like `2_ft1`
     * are frequently mangled or dropped in batch responses.
     */
    fun cropFreeText(
        bitmap: Bitmap,
        boxes: List<IntArray>,
        idPrefix: String = "",
        idStart: Int = 0,
    ): CropResult {
        val crops = mutableListOf<Pair<String, Bitmap>>()
        val coordMap = mutableMapOf<String, IntArray>()
        val bgColors = mutableMapOf<String, Int>()

        val cropMatFull = ImageProcessor.bitmapToMat(bitmap)
        try {
            for ((order, box) in boxes.withIndex()) {
                val (x1, y1, x2, y2) = box
                val pad = maxOf(6, ((x2 - x1) * 0.06).toInt())
                val cropX1 = maxOf(0, x1 - pad)
                val cropY1 = maxOf(0, y1 - pad)
                val cropX2 = minOf(bitmap.width, x2 + pad)
                val cropY2 = minOf(bitmap.height, y2 + pad)
                if (cropX2 - cropX1 <= 0 || cropY2 - cropY1 <= 0) continue

                val id = "${idPrefix}ft${idStart + order + 1}"
                val isCleanStyle = params.render.renderStyle.equals("clean", ignoreCase = true)
                bgColors[id] =
                    if (isCleanStyle) {
                        // Median is robust to stray strokes at the ring — the flat erase
                        // fill blends with the real page background in clean style.
                        ImageRegion.sampleRegionBackgroundColorMedian(cropMatFull, box)
                    } else {
                        ImageRegion.sampleRegionBackgroundColor(cropMatFull, box)
                    }

                val cropMat = cropMatFull.submat(org.opencv.core.Rect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1))
                val scale = params.detection.skalaPotonganMosaik
                val cropBitmap = ImageProcessor.matToBitmap(cropMat)
                cropMat.release()

                val scaledBitmap =
                    if (scale != 1.0) {
                        Bitmap.createScaledBitmap(
                            cropBitmap,
                            maxOf(1, (cropBitmap.width * scale).toInt()),
                            maxOf(1, (cropBitmap.height * scale).toInt()),
                            true,
                        )
                    } else {
                        cropBitmap
                    }
                if (scaledBitmap !== cropBitmap && !cropBitmap.isRecycled) cropBitmap.recycle()

                crops.add(id to scaledBitmap)
                coordMap[id] = box
            }
        } finally {
            cropMatFull.release()
        }
        return CropResult(crops, coordMap, bgColors)
    }

    data class CropResult(
        val crops: MutableList<Pair<String, Bitmap>>,
        val coordMap: MutableMap<String, IntArray>,
        val bubbleColors: MutableMap<String, Int>,
    )

    /**
     * Crop each detected box (with padding + outside-bubble masking) and scale it
     * up for the mosaic. IDs are `idPrefix + (order+1)` — empty prefix for
     * single-image pages, `"<page>_"` for batch pages.
     */
    fun cropBubbles(
        bitmap: Bitmap,
        boxes: List<IntArray>,
        idPrefix: String = "",
    ): CropResult {
        val imgWidth = bitmap.width
        val imgHeight = bitmap.height
        val crops = mutableListOf<Pair<String, Bitmap>>()
        val coordMap = mutableMapOf<String, IntArray>()
        val bubbleColors = mutableMapOf<String, Int>()

        val cropMatFull = ImageProcessor.bitmapToMat(bitmap)
        try {
            for ((order, box) in boxes.withIndex()) {
                val (x1, y1, x2, y2) = box
                val boxW = maxOf(1, x2 - x1)
                val boxH = maxOf(1, y2 - y1)

                val padX = maxOf(params.detection.minPad, (boxW * params.detection.padXRatio).toInt())
                val padY = maxOf(params.detection.minPad, (boxH * params.detection.padYRatio).toInt())

                val id = "$idPrefix${order + 1}"

                // Detect background color (dark vs white)
                val bgColor = ImageRegion.detectBubbleBackgroundColor(cropMatFull, box)
                bubbleColors[id] = bgColor

                val (cropX1, cropY1, cropX2, cropY2) =
                    ImageRegion.smartCropBounds(
                        box,
                        boxes,
                        imgWidth,
                        imgHeight,
                        padX,
                        padY,
                        params,
                    )

                val cropMat = cropMatFull.submat(org.opencv.core.Rect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1))
                val maskedMat = ImageRegion.maskOutsideBubble(cropMat, cropX1, cropY1, x1, y1, x2, y2, params)
                val scale = params.detection.skalaPotonganMosaik
                val cropBitmap = ImageProcessor.matToBitmap(maskedMat)
                if (maskedMat !== cropMat) maskedMat.release()
                cropMat.release()

                val scaledBitmap =
                    if (scale != 1.0) {
                        Bitmap.createScaledBitmap(
                            cropBitmap,
                            maxOf(1, (cropBitmap.width * scale).toInt()),
                            maxOf(1, (cropBitmap.height * scale).toInt()),
                            true,
                        )
                    } else {
                        cropBitmap
                    }
                // Free the unscaled crop copy explicitly (GC would reclaim it eventually,
                // but explicit recycle keeps the transient spike flat on bubble-heavy pages).
                if (scaledBitmap !== cropBitmap && !cropBitmap.isRecycled) cropBitmap.recycle()

                crops.add(id to scaledBitmap)
                coordMap[id] = box
            }
        } finally {
            cropMatFull.release()
        }
        return CropResult(crops, coordMap, bubbleColors)
    }
}
