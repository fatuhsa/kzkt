package com.kzkt.app.core

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws translated bubble text onto a page canvas. Extracted from TranslationPipeline
 * so the single-image and batch paths share exactly one renderer.
 */
class ResultRenderer(
    private val textRenderer: TextRenderer,
    private val params: Config.TweakParams,
    private val targetLanguage: String,
) {

    /**
     * Draw translations onto the canvas. Covers the original text with a
     * white/adaptive blurred patch (or a full patch for flat boxes).
     * Returns the number of bubbles actually rendered.
     */
    fun render(
        canvas: Canvas,
        translations: Map<String, String>,
        coordinateMap: Map<String, IntArray>,
        bubbleColors: Map<String, Int> = emptyMap(),
        imgWidth: Int,
        imgHeight: Int,
        freeTextIds: Set<String> = emptySet(),
    ): Int {
        // User-facing render settings: forced text colour ("auto"/"white"/"black")
        // and a global font-size multiplier, applied to every bubble.
        val textColorHex = when (params.render.renderTextColor.lowercase()) {
            "white" -> "#FFFFFF"
            "black" -> "#000000"
            else -> null
        }
        val fontScale = params.render.renderFontScale.toFloat()
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
            val isFreeText = num in freeTextIds

            // The "suspicious flat" skips exist to avoid re-rendering wide SFX/image
            // boxes as bubbles; free-text regions must always be erased + rendered.
            if (!isFreeText && ratio >= 3.2 && w >= imgWidth * 0.35) continue
            if (!isFreeText && areaRatio >= 0.035 && ratio >= 2.8) continue

            val suspiciousFlat = ratio >= params.detection.rasioBoxGepeng &&
                w >= imgWidth * params.detection.lebarBoxGepengRatio &&
                h <= imgHeight * params.detection.tinggiBoxGepengRatio

            if (isFreeText) {
                // Erase the original text with a solid fill of the sampled page
                // background (inpainting already erased it when enabled, so no fill).
                if (!params.render.useInpainting) {
                    val erasePaint =
                        Paint().apply {
                            color = bgColor
                            isAntiAlias = false
                        }
                    val pad = 2f
                    canvas.drawRect(x1 - pad, y1 - pad, x2 + pad, y2 + pad, erasePaint)
                }
            } else if (!params.render.useInpainting && !(params.detection.pakaiPatchUntukBoxGepeng && suspiciousFlat)) {
                // Background: blurred adaptive patch, drawn on a bubble-sized overlay
                val marginX = (w * params.detection.maskMarginRatio).toInt()
                val marginY = (h * params.detection.maskMarginRatio).toInt()
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
            val isFreeText = num in freeTextIds

            if (!isFreeText && ratio >= 3.2 && w >= imgWidth * 0.35) continue
            if (!isFreeText && areaRatio >= 0.035 && ratio >= 2.8) continue

            val suspiciousFlat = ratio >= params.detection.rasioBoxGepeng &&
                w >= imgWidth * params.detection.lebarBoxGepengRatio &&
                h <= imgHeight * params.detection.tinggiBoxGepengRatio

            if (params.render.useInpainting) {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = false, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.render.customFontPath, fontScale = fontScale, textColorHex = textColorHex)
            } else if (params.detection.pakaiPatchUntukBoxGepeng && suspiciousFlat) {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = true, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.render.customFontPath, fontScale = fontScale, textColorHex = textColorHex)
            } else {
                textRenderer.renderTextInBubble(canvas, coordinateMap[num]!!, text,
                    backgroundPatch = false, targetLanguage = targetLanguage, bgColor = bgColor,
                    customFontPath = params.render.customFontPath, fontScale = fontScale, textColorHex = textColorHex)
            }
            count++
        }
        return count
    }
}
