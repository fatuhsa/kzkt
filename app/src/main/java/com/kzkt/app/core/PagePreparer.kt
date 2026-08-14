package com.kzkt.app.core

import android.graphics.Bitmap

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
    fun detectBubbles(bitmap: Bitmap, isCancelled: () -> Boolean = { false }): List<IntArray> {
        val prepared = yolo?.prepareInput(bitmap)
        val rawBoxes = mutableListOf<IntArray>()
        for ((conf, iou) in Constants.YOLO_PREDICTION_STAGES) {
            if (isCancelled()) break
            val detections = yolo?.predict(bitmap, confThreshold = conf, iouThreshold = iou, prepared = prepared)
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
                val bgColor = ImageProcessor.detectBubbleBackgroundColor(cropMatFull, box)
                bubbleColors[id] = bgColor

                val (cropX1, cropY1, cropX2, cropY2) = ImageProcessor.smartCropBounds(
                    box, boxes, imgWidth, imgHeight, padX, padY, params
                )

                val cropMat = cropMatFull.submat(org.opencv.core.Rect(cropX1, cropY1, cropX2 - cropX1, cropY2 - cropY1))
                val maskedMat = ImageProcessor.maskOutsideBubble(cropMat, cropX1, cropY1, x1, y1, x2, y2, params)
                cropMat.release()

                // Scale up
                val scale = params.detection.skalaPotonganMosaik
                val cropBitmap = ImageProcessor.matToBitmap(maskedMat)
                // maskOutsideBubble returns `crop` itself when maskAreaLuarBox is off —
                // releasing the same Mat twice would corrupt its refcount.
                if (maskedMat !== cropMat) maskedMat.release()
                val scaledBitmap = if (scale != 1.0) {
                    Bitmap.createScaledBitmap(
                        cropBitmap,
                        maxOf(1, (cropBitmap.width * scale).toInt()),
                        maxOf(1, (cropBitmap.height * scale).toInt()),
                        true
                    )
                } else cropBitmap
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
