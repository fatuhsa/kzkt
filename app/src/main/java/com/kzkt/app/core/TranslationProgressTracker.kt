package com.kzkt.app.core

import kotlinx.coroutines.flow.MutableSharedFlow

object TranslationProgressTracker {
    val progressFlow = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 128)

    @Volatile
    var isCancelled: Boolean = false

    @Volatile
    var cachedPageData: List<com.kzkt.app.core.TranslationPipeline.PageData>? = null

    fun clearCache() {
        cachedPageData?.forEach { page ->
            try {
                if (!page.pil.isRecycled) page.pil.recycle()
                page.crops.forEach { (_, bmp) ->
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } catch (_: Exception) {}
        }
        cachedPageData = null
    }

    sealed class ProgressEvent {
        data class Log(val message: String) : ProgressEvent()
        data class Progress(val done: Int, val total: Int) : ProgressEvent()
        data class ResultPath(val path: String) : ProgressEvent()
        object Completed : ProgressEvent()
        data class Error(val error: String) : ProgressEvent()
    }
}
