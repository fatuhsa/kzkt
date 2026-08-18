package com.kzkt.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A free-text region detected by ML Kit on the full page: its axis-aligned box
 * (in original bitmap coordinates) plus the recognized text. Used by the
 * "translate free text" feature — everything outside YOLO speech bubbles.
 */
data class TextRegion(
    val box: IntArray, // [x1, y1, x2, y2]
    val text: String,
)

/** Which ML Kit on-device OCR model(s) to run. AUTO unions every model's output. */
enum class OcrScript(
    val key: String,
    val label: String,
) {
    ENGLISH("en", "English"),
    JAPANESE("jp", "Japanese + Latin"),
    KOREAN("kr", "Korean"),
    CHINESE("cn", "Chinese"),
    AUTO("auto", "Auto (all)"),
    ;

    companion object {
        /**
         * Resolve a stored script key ("jp", "en", ...) to an enum value. Legacy
         * full names ("japanese", "korean", ...) saved by older builds still resolve.
         */
        fun fromKey(raw: String): OcrScript = entries.firstOrNull { it.key == raw || it.name.lowercase() == raw } ?: JAPANESE
    }
}

/**
 * On-device local OCR engine powered by Google ML Kit.
 *
 * Holds one lazy recognizer per [OcrScript]. The Japanese model
 * (gocrjapanese_and_latin) recognizes BOTH Japanese and Latin script, so it stays
 * the default — English/Korean/Chinese models are opt-in via the Settings picker.
 *
 * IMPORTANT (verified in the field): ML Kit crashes with an NPE inside
 * com.google.mlkit.vision.text.internal when a SECOND client TYPE is created in
 * the same process (TextRecognition.getClient reads a null component field). This
 * engine therefore creates recognizers strictly lazily — a recognizer type is only
 * instantiated the first time it is actually used, so the app never creates a
 * second type unless the user explicitly enables Korean/Chinese/AUTO. If that NPE
 * still fires on some devices, the whole multi-script experiment can be reverted
 * by removing the non-Japanese branches (default stays Japanese-only).
 *
 * The free-text detection functions below (recognizeTextRegions) reuse the SAME
 * per-script recognizers as [recognizeText], so no extra client type is created.
 */
object LocalOcrEngine {
    private const val TAG = "LocalOcrEngine"
    private const val OCR_TIMEOUT_SEC = 15L

    /** ML Kit text recognition is tuned for ~2048px inputs; larger scans are downscaled. */
    private const val MAX_DETECT_DIM = 2048

    /** Skip regions too small to be readable text (noise). */
    private const val MIN_REGION_W = 12
    private const val MIN_REGION_H = 8

    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    /**
     * Last ML Kit error message (cleared by the pipeline after reporting), so a
     * blank OCR result can be distinguished from a genuine "no text in this crop".
     */
    @Volatile
    var lastError: String? = null

    private fun getRecognizer(script: OcrScript): TextRecognizer =
        synchronized(this) {
            recognizers[script] ?: createRecognizer(script).also { recognizers[script] = it }
        }

    private fun createRecognizer(script: OcrScript): TextRecognizer =
        when (script) {
            OcrScript.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.AUTO -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        }

    /**
     * Recognize the text inside a single crop (bubble / free-text region). With
     * [OcrScript.AUTO], tries every model in order and returns the first non-blank
     * result (Japanese first — it also covers Latin).
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        script: OcrScript = OcrScript.JAPANESE,
    ): String {
        return withContext(Dispatchers.IO) {
            val scripts = script.resolve()
            for (s in scripts) {
                val text = runRecognition(bitmap, getRecognizer(s))
                if (text.isNotBlank()) return@withContext text
            }
            ""
        }
    }

    private fun runRecognition(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
    ): String =
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            val visionText = Tasks.await(task, OCR_TIMEOUT_SEC, TimeUnit.SECONDS)
            lastError = null
            visionText.text
                .trim()
                .replace("\r\n", " ")
                .replace("\n", " ")
        } catch (e: Exception) {
            lastError = e.message
            Log.w(TAG, "ML Kit OCR failed or timed out: ${e.message}", e)
            ""
        }

    /**
     * Full-page text-block detection for the free-text feature. With
     * [OcrScript.AUTO], runs every model and unions the detected regions (the
     * caller's merge step collapses overlapping boxes). When [excludeBoxes] are
     * provided (e.g. YOLO speech bubbles), those regions are masked out so
     * ML Kit avoids scanning text that is already handled.
     */
    suspend fun recognizeTextRegions(
        bitmap: Bitmap,
        script: OcrScript = OcrScript.JAPANESE,
        excludeBoxes: List<IntArray> = emptyList(),
    ): List<TextRegion> =
        withContext(Dispatchers.IO) {
            val scripts = script.resolve()
            val all = mutableListOf<TextRegion>()
            for (s in scripts) {
                all.addAll(runRegionRecognition(bitmap, getRecognizer(s), excludeBoxes))
            }
            all
        }

    private fun runRegionRecognition(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
        excludeBoxes: List<IntArray> = emptyList(),
    ): List<TextRegion> =
        try {
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scale = minOf(1f, MAX_DETECT_DIM.toFloat() / maxDim)
            val targetW = maxOf(1, (bitmap.width * scale).toInt())
            val targetH = maxOf(1, (bitmap.height * scale).toInt())

            val detectBmp =
                if (excludeBoxes.isNotEmpty() || scale < 1f) {
                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                    val dstRect = android.graphics.Rect(0, 0, targetW, targetH)
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

                    if (excludeBoxes.isNotEmpty()) {
                        val maskPaint =
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                style = android.graphics.Paint.Style.FILL
                            }
                        for (b in excludeBoxes) {
                            val left = (b[0] * scale).toInt().coerceIn(0, targetW)
                            val top = (b[1] * scale).toInt().coerceIn(0, targetH)
                            val right = (b[2] * scale).toInt().coerceIn(0, targetW)
                            val bottom = (b[3] * scale).toInt().coerceIn(0, targetH)
                            if (right > left && bottom > top) {
                                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), maskPaint)
                            }
                        }
                    }
                    bmp
                } else {
                    bitmap
                }

            val image = InputImage.fromBitmap(detectBmp, 0)
            val task = recognizer.process(image)
            val visionText = Tasks.await(task, OCR_TIMEOUT_SEC, TimeUnit.SECONDS)
            lastError = null

            val inv = 1f / scale
            val regions = mutableListOf<TextRegion>()
            for (block in visionText.textBlocks) {
                val b = block.boundingBox ?: continue
                if (block.text.isBlank()) continue
                val w = b.width()
                val h = b.height()
                if (w < MIN_REGION_W || h < MIN_REGION_H) continue
                regions.add(
                    TextRegion(
                        box =
                            intArrayOf(
                                (b.left * inv).toInt(),
                                (b.top * inv).toInt(),
                                (b.right * inv).toInt(),
                                (b.bottom * inv).toInt(),
                            ),
                        text = block.text,
                    ),
                )
            }
            if (detectBmp !== bitmap && !detectBmp.isRecycled) detectBmp.recycle()
            regions
        } catch (e: Exception) {
            lastError = e.message
            Log.w(TAG, "ML Kit region detection failed or timed out: ${e.message}", e)
            emptyList()
        }

    /** Expand AUTO into the concrete model list to run, in priority order. */
    private fun OcrScript.resolve(): List<OcrScript> =
        when (this) {
            OcrScript.AUTO -> listOf(OcrScript.JAPANESE, OcrScript.ENGLISH, OcrScript.KOREAN, OcrScript.CHINESE)
            else -> listOf(this)
        }
}
