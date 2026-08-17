package com.kzkt.app.core

import com.kzkt.app.util.KLog
import kotlinx.coroutines.flow.MutableSharedFlow

object TranslationProgressTracker {
    val progressFlow = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 128)

    @Volatile
    var isCancelled: Boolean = false

    @Volatile
    var cachedPageData: List<PageData>? = null

    fun clearCache() {
        cachedPageData?.forEach { page ->
            try {
                if (!page.pil.isRecycled) page.pil.recycle()
                page.crops.forEach { (_, bmp) ->
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (e: Exception) {
                KLog.w("KZKT", "Failed to recycle cached page bitmaps: ${e.message}")
            }
        }
        cachedPageData = null
    }

    sealed class ProgressEvent {
        data class Log(
            val message: String,
        ) : ProgressEvent()

        data class Progress(
            val done: Int,
            val total: Int,
        ) : ProgressEvent()

        data class ResultPath(
            val path: String,
        ) : ProgressEvent()

        /** Per-file batch status: [state] is "processing", "done", or "failed". */
        data class PageStatus(
            val path: String,
            val state: String,
        ) : ProgressEvent()

        object Completed : ProgressEvent()

        data class Error(
            val error: String,
        ) : ProgressEvent()
    }
}
