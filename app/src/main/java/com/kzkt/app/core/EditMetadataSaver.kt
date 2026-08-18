package com.kzkt.app.core

import android.content.Context
import android.graphics.Bitmap
import com.kzkt.app.util.KLog
import java.io.File

/**
 * Persists bubble coordinates + translations + the original page bitmap so the
 * in-app touch-up editor can restore edit data later (e.g. from History).
 * Extracted from TranslationPipeline.
 */
class EditMetadataSaver(
    private val context: Context?,
    private val targetLanguage: String,
) {
    /**
     * Keyed by output file name, which is preserved when publishing to MediaStore.
     */
    fun save(
        outputPath: String,
        original: Bitmap,
        translations: Map<String, String>,
        coordinates: Map<String, IntArray>,
        rawTexts: Map<String, String> = emptyMap(),
    ) {
        val ctx = context ?: return
        if (translations.isEmpty() || coordinates.isEmpty()) return
        try {
            com.kzkt.app.data
                .EditMetadataRepository(ctx)
                .saveForOutput(outputPath, original, translations, coordinates, targetLanguage, rawTexts.ifEmpty { null })
        } catch (e: Exception) {
            // Best-effort — never fail a translation because metadata couldn't be saved.
            KLog.w("KZKT", "Edit metadata: save skipped for ${File(outputPath).name}: ${e.message}")
        }
    }

    /**
     * Merge per-part bubble metadata into the combined landscape page. Parts are laid out
     * left-to-right in the combined image (original part [i] sits at position splitCount-1-i),
     * so each part's bubble boxes are shifted by the cumulative width of everything to its
     * left. IDs are prefixed with the part index to avoid collisions across parts.
     */
    fun saveLandscape(
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
            com.kzkt.app.data
                .EditMetadataRepository(ctx)
                .saveForOutput(outputPath, combined, translations, coords, targetLanguage, rawTexts.ifEmpty { null })
        } catch (e: Exception) {
            // Best-effort — never fail a translation because metadata couldn't be saved.
            KLog.w("KZKT", "Edit metadata: landscape save skipped for ${File(outputPath).name}: ${e.message}")
        }
    }
}
