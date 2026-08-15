package com.kzkt.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
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

/**
 * On-device local OCR engine powered by Google ML Kit.
 *
 * Uses a SINGLE recognizer — the Japanese model (gocrjapanese_and_latin) — for all
 * input. That model recognizes BOTH Japanese and Latin script, so one client covers
 * manga pages in either script with no script selector and no auto-detection pass.
 *
 * Why only one client: creating a SECOND ML Kit client type in the same process
 * (e.g. Latin client, then Japanese client) crashes with an NPE inside
 * com.google.mlkit.vision.text.internal (TextRecognition.getClient reads a null
 * component field). Keeping exactly one client for the app's lifetime avoids that
 * path entirely while the bundled japanese_and_latin model still reads English/Latin.
 *
 * The free-text detection functions below (recognizeTextRegions) reuse the SAME
 * single recognizer, so no second client type is ever created.
 */
object LocalOcrEngine {
    private const val TAG = "LocalOcrEngine"
    private const val OCR_TIMEOUT_SEC = 15L

    /** ML Kit text recognition is tuned for ~2048px inputs; larger scans are downscaled. */
    private const val MAX_DETECT_DIM = 2048

    /** Skip regions too small to be readable text (noise). */
    private const val MIN_REGION_W = 12
    private const val MIN_REGION_H = 8

    @Volatile
    private var recognizer: TextRecognizer? = null

    /**
     * Last ML Kit error message (cleared by the pipeline after reporting), so a
     * blank OCR result can be distinguished from a genuine "no text in this crop".
     */
    @Volatile
    var lastError: String? = null

    private fun getRecognizer(): TextRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { recognizer = it }
        }

    /**
     * [ocrScript] is accepted for API compatibility with the (removed) script
     * selector but is no longer used — the Japanese model handles both scripts.
     */
    suspend fun recognizeText(bitmap: Bitmap, ocrScript: String = "japanese"): String {
        return withContext(Dispatchers.IO) {
            runRecognition(bitmap, getRecognizer())
        }
    }

    private fun runRecognition(bitmap: Bitmap, recognizer: TextRecognizer): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            val visionText = Tasks.await(task, OCR_TIMEOUT_SEC, TimeUnit.SECONDS)
            lastError = null
            visionText.text.trim().replace("\r\n", " ").replace("\n", " ")
        } catch (e: Exception) {
            lastError = e.message
            Log.w(TAG, "ML Kit OCR failed or timed out: ${e.message}", e)
            ""
        }
    }

    /**
     * Full-page text-block detection for the free-text feature. Reuses the SAME
     * Japanese recognizer as [recognizeText] (creating a second ML Kit client type
     * in this process crashes — see the class doc). Returns text blocks with their
     * bounding boxes scaled back to the original bitmap coordinates.
     */
    suspend fun recognizeTextRegions(bitmap: Bitmap): List<TextRegion> =
        withContext(Dispatchers.IO) {
            runRegionRecognition(bitmap, getRecognizer())
        }

    private fun runRegionRecognition(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
    ): List<TextRegion> {
        return try {
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scale = minOf(1f, MAX_DETECT_DIM.toFloat() / maxDim)
            val detectBmp =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        maxOf(1, (bitmap.width * scale).toInt()),
                        maxOf(1, (bitmap.height * scale).toInt()),
                        true,
                    )
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
    }
}
