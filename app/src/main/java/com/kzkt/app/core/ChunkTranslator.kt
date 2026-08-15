package com.kzkt.app.core

import android.graphics.Bitmap
import com.google.gson.Gson
import com.kzkt.app.core.ocr.LocalOcrEngine
import com.kzkt.app.core.providers.LlmProvider
import com.kzkt.app.data.TranslationCacheRepository
import com.kzkt.app.util.JsonUtils
import kotlinx.coroutines.CancellationException

/**
 * Translates bubble chunks through the provider chain (with rate limiting, JSON
 * parsing and translation-memory cache writes). Extracted from TranslationPipeline,
 * where the provider loop was duplicated 3x for vision chunks and 2x for OCR chunks.
 */
class ChunkTranslator(
    private val provider: LlmProvider,
    private val fallbackProviders: List<LlmProvider>,
    private val rateLimiter: RateLimiter,
    private val targetLanguage: String,
    private val cacheRepo: TranslationCacheRepository?,
    private val glossary: Map<String, String>,
    private val params: Config.TweakParams,
    private val onProgress: (String) -> Unit,
    private val isCancelled: () -> Boolean,
) {

    /**
     * Core provider-chain loop: sends [request] through the primary + fallback
     * providers with rate limiting, parses the JSON response, writes successful
     * translations to the translation memory, and reports failover / unparseable
     * output via [onProgress]. Shared by the vision and OCR paths so retry,
     * failover and parsing live in exactly one place — and are unit-testable
     * without Android (the request lambda is Bitmap-free).
     *
     * [logUnparseable] controls whether a non-JSON response is logged (vision
     * logs it, OCR path historically did not). [failoverMessage] formats the
     * per-provider failure line.
     *
     * Returns true when at least one provider produced a parseable translation.
     */
    suspend fun translateWithProviders(
        request: suspend (LlmProvider) -> String?,
        cropItems: List<MosaicBuilder.CropItem>,
        allTranslations: MutableMap<String, String>,
        logStart: (LlmProvider) -> String = { "  Translating with ${it.providerName}..." },
        onWait: ((String) -> Unit)? = null,
        logUnparseable: Boolean = true,
        failoverMessage: (LlmProvider, String) -> String = { p, msg ->
            "  [Failover] ${p.providerName} failed ($msg). Trying fallback provider..."
        },
    ): Boolean {
        val providersChain = listOf(provider) + fallbackProviders
        var succeeded = false
        for (prov in providersChain) {
            if (isCancelled()) break
            onProgress(logStart(prov))
            try {
                val result = rateLimiter.executeWithRetry(
                    apiCall = { request(prov) },
                    providerName = prov.providerName,
                    isCancelled = isCancelled,
                    onWait = { msg ->
                        onProgress(msg)
                        onWait?.invoke(msg)
                    }
                )
                if (result != null) {
                    val cleaned = JsonUtils.sanitizeJson(result)
                    val parsed = JsonUtils.parseTranslationMap(cleaned)
                    if (parsed.isNotEmpty()) {
                        allTranslations.putAll(parsed)
                        saveToCache(parsed, cropItems, prov)
                        succeeded = true
                        break
                    } else if (logUnparseable) {
                        onProgress("  [!] ${prov.providerName} returned unparseable output (raw: ${cleaned.take(80)}). Trying next provider...")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException || isCancelled()) {
                    throw e
                }
                onProgress(failoverMessage(prov, e.message ?: "Unknown error"))
            }
        }
        return succeeded
    }

    /**
     * Build a mosaic from [chunk], send it through the vision provider chain and
     * merge the parsed translations into [allTranslations]. Returns true when at
     * least one provider produced a parseable translation.
     *
     * [logStart] customizes the per-provider progress line (e.g. the OCR-fallback
     * wording); [onWait] receives the rate-limiter messages in addition to
     * [onProgress] (used by the batch path to mirror steps into the progress bar).
     */
    suspend fun translateVisionChunk(
        chunk: List<MosaicBuilder.CropItem>,
        cropItems: List<MosaicBuilder.CropItem>,
        allTranslations: MutableMap<String, String>,
        logStart: (LlmProvider) -> String = { "  Translating with ${it.providerName}..." },
        onWait: ((String) -> Unit)? = null,
    ): Boolean {
        val shrunk = MosaicBuilder.shrinkCropsIfTooTall(chunk, params.detection.maxTinggiMosaik, params.detection.jarakAntarPotongan)
        val mosaic = MosaicBuilder.buildMosaic(shrunk, params)
        val prompt = Constants.buildPrompt(targetLanguage, glossary, params.engine.translateSfx)
        return try {
            translateWithProviders(
                request = { prov -> prov.translateImage(mosaic, prompt) },
                cropItems = cropItems,
                allTranslations = allTranslations,
                logStart = logStart,
                onWait = onWait,
            )
        } finally {
            if (!mosaic.isRecycled) mosaic.recycle()
            for (item in shrunk) {
                if (item.bitmap != cropItems.firstOrNull { it.id == item.id }?.bitmap && !item.bitmap.isRecycled) {
                    item.bitmap.recycle()
                }
            }
        }
    }

    data class OcrResult(
        /** Bubble id → recognized raw text (empty map = nothing recognized). */
        val ocrMap: Map<String, String>,
        /** True when at least one provider translated this chunk successfully. */
        val translated: Boolean,
    )

    /**
     * Local OCR path: recognize text per bubble with ML Kit, then send the JSON
     * map through the text-only provider chain. [textPrompt] builds the prompt from
     * the recognized JSON (the single-image and batch prompts differ). Raw texts
     * are appended to [rawTexts]; translations to [allTranslations].
     */
    suspend fun translateOcrChunk(
        chunk: List<MosaicBuilder.CropItem>,
        cropItems: List<MosaicBuilder.CropItem>,
        allTranslations: MutableMap<String, String>,
        rawTexts: MutableMap<String, String>,
        textPrompt: (String) -> String,
        logStart: (LlmProvider) -> String = { "  Translating text with ${it.providerName}..." },
    ): OcrResult {
        val ocrMap = mutableMapOf<String, String>()
        for (item in chunk) {
            val recognized = LocalOcrEngine.recognizeText(item.bitmap)
            if (recognized.isNotBlank()) {
                ocrMap[item.id] = recognized
                if (params.engine.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> \"$recognized\"")
            } else {
                val ocrErr = LocalOcrEngine.lastError
                if (ocrErr != null) {
                    LocalOcrEngine.lastError = null
                    if (params.engine.enableDevLogs) onProgress("  [Local OCR] Bubble ${item.id} -> ML Kit error: $ocrErr")
                } else if (params.engine.enableDevLogs) {
                    onProgress("  [Local OCR] Bubble ${item.id} -> (No text recognized)")
                }
            }
        }
        if (ocrMap.isEmpty()) return OcrResult(ocrMap, translated = false)

        rawTexts.putAll(ocrMap)

        val textJson = Gson().toJson(ocrMap)
        val prompt = textPrompt(textJson)
        // Some text-only providers accept a 1×1 image as a no-op vision call.
        val dummyBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val translated = try {
            translateWithProviders(
                request = { prov -> prov.translateText(textJson, prompt) ?: prov.translateImage(dummyBmp, prompt) },
                cropItems = cropItems,
                allTranslations = allTranslations,
                logStart = logStart,
                logUnparseable = false,
                failoverMessage = { p, msg -> "  [Failover] ${p.providerName} failed: $msg. Trying fallback provider..." },
            )
        } finally {
            if (!dummyBmp.isRecycled) dummyBmp.recycle()
        }
        return OcrResult(ocrMap, translated)
    }

    /**
     * Translation-memory lookup: bubbles that are pixel-identical to a previously
     * translated crop are served from the local cache without burning an API call.
     * Returns (cachedTranslations, cropsToTranslate). Shared by the single-image
     * and batch paths, which previously duplicated this block.
     */
    fun filterCached(cropItems: List<MosaicBuilder.CropItem>): Pair<MutableMap<String, String>, MutableList<MosaicBuilder.CropItem>> {
        val cachedTranslations = mutableMapOf<String, String>()
        val toTranslate = mutableListOf<MosaicBuilder.CropItem>()
        if (cacheRepo == null) {
            toTranslate.addAll(cropItems)
            return cachedTranslations to toTranslate
        }
        for (crop in cropItems) {
            val cached = cacheRepo.getTranslation(crop.bitmap, targetLanguage, provider.providerName, provider.modelName)
            if (cached != null) {
                cachedTranslations[crop.id] = cached
            } else {
                toTranslate.add(crop)
            }
        }
        return cachedTranslations to toTranslate
    }

    private fun saveToCache(
        parsed: Map<String, String>,
        cropItems: List<MosaicBuilder.CropItem>,
        prov: LlmProvider,
    ) {
        if (cacheRepo == null) return
        for ((id, text) in parsed) {
            val item = cropItems.find { it.id == id }
            if (item != null) {
                cacheRepo.saveTranslation(item.bitmap, targetLanguage, text, prov.providerName, prov.modelName)
            }
        }
    }

    companion object {
        private val ID_KEY_REGEX = Regex("(\\d+)(?:_(\\d+))?|\\b(\\d+)\\b")

        /** Free-text region ids: `ft1`, `2_ft1`, ... (never collide with numeric bubble ids). */
        private val FT_KEY_REGEX = Regex("(\\d+_)?ft\\d+")

        /**
         * Normalize an LLM-returned JSON key to a stable bubble ID ("1", "1_2", …) or null if unmatched.
         * Handles int/string keys, leading underscores, prefixes like "ID 1", and trailing punctuation.
         */
        fun normalizeIdKey(key: String): String? {
            if (key.startsWith("_")) return key
            // Free-text ids pass through verbatim (their numeric part is not a page ref).
            if (FT_KEY_REGEX.matches(key)) return key
            val m = ID_KEY_REGEX.find(key) ?: return null
            val (a, b, c) = m.destructured
            val first = (a.ifEmpty { c }).toIntOrNull() ?: return null
            return if (b.isNotEmpty()) "${first}_${b.toIntOrNull() ?: b}" else first.toString()
        }

        /** True when the crop id belongs to a free-text region (`ft1`, `2_ft1`, ...). */
        fun isFreeTextId(id: String): Boolean {
            val last = id.substringAfterLast('_')
            return last.startsWith("ft") && last.drop(2).toIntOrNull() != null
        }
    }
}
