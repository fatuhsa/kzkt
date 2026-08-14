package com.kzkt.app.core

import android.graphics.Bitmap

/**
 * Result of translating a single image / page (or the whole batch, one per page).
 * Extracted from TranslationPipeline so the pipeline, worker and UI share one type.
 */
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

/**
 * A single page after YOLO detection + cropping, ready for translation.
 * Also the unit stored in [TranslationProgressTracker.cachedPageData] for fast retry.
 */
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
