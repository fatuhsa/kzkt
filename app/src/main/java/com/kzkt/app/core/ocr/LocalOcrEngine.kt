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
 */
object LocalOcrEngine {
    private const val TAG = "LocalOcrEngine"
    private const val OCR_TIMEOUT_SEC = 15L

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
}
