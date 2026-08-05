package com.kzkt.app.core.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * On-device local OCR engine powered by Google ML Kit.
 * Supports Japanese (Hiragana, Katakana, Kanji) and Latin (English, Spanish, etc.) scripts.
 */
object LocalOcrEngine {
    private const val TAG = "LocalOcrEngine"

    @Volatile
    private var latinRecognizer: TextRecognizer? = null

    @Volatile
    private var japaneseRecognizer: TextRecognizer? = null

    private fun getRecognizer(ocrScript: String): TextRecognizer {
        val script = ocrScript.lowercase()
        return if (script.contains("japanese") || script.contains("jp") || script.contains("jepang")) {
            japaneseRecognizer ?: synchronized(this) {
                japaneseRecognizer ?: TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { japaneseRecognizer = it }
            }
        } else {
            latinRecognizer ?: synchronized(this) {
                latinRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { latinRecognizer = it }
            }
        }
    }

    suspend fun recognizeText(bitmap: Bitmap, ocrScript: String = "japanese"): String {
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = getRecognizer(ocrScript)
                val task = recognizer.process(image)
                val visionText = Tasks.await(task, 15, TimeUnit.SECONDS)
                visionText.text.trim().replace("\r\n", " ").replace("\n", " ")
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit OCR failed or timed out (${ocrScript}): ${e.message}", e)
                ""
            }
        }
    }
}
