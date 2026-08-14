package com.kzkt.app.core

import android.content.Context
import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.core.providers.LlmProvider
import com.kzkt.app.data.TranslationCacheRepository

/**
 * Static configuration for one translation run: model + renderer + tunables +
 * persistence. Grouped so [TranslationPipeline] stays a thin 3-arg constructor.
 */
data class PipelineConfig(
    val yolo: YoloOnnx?,
    val textRenderer: TextRenderer,
    val params: TweakParams,
    val targetLanguage: String = "Indonesian",
    val rateLimiter: RateLimiter = RateLimiter((params.engine.minRequestDelay * 1000).toLong()),
    val cacheRepo: TranslationCacheRepository? = null,
    val glossary: Map<String, String> = emptyMap(),
    val context: Context? = null,
)

/**
 * Primary LLM provider plus the failover chain used when the primary fails.
 */
data class ProviderChain(
    val provider: LlmProvider,
    val fallbackProviders: List<LlmProvider> = emptyList(),
)

/**
 * Progress / cancellation callbacks bridging the pipeline to the UI or worker.
 */
data class PipelineCallbacks(
    val onProgress: (String) -> Unit = {},
    val onStepProgress: (Int, String) -> Unit = { _, _ -> },
    val isCancelled: () -> Boolean = { false },
)
