package com.kzkt.app.core

/**
 * Pure decision logic for the multi-page batch translation path, extracted from
 * [TranslationPipeline.processImageBatch] so it can be unit-tested without
 * Android bitmaps or network calls. Every function here takes plain data and
 * returns plain data — no Android dependencies.
 *
 * These decisions were the source of several silent-skip bugs (batch ids not
 * echoed by the provider, free-text regions dropped when the model rewrote the
 * page prefix), so they are pinned down by tests.
 */
object BatchTranslationPlanner {
    /**
     * Result of normalizing raw LLM output keys. Page-prefixed ids always win;
     * the bare form is only a fallback for exactly this rewrite.
     */
    data class NormalizedTranslations(
        /** `"1_ft1" -> translation` — canonical, normalized ids. */
        val byId: Map<String, String>,
        /** `"ft1" -> translation` — bare free-text id fallback. */
        val bareFtLookup: Map<String, String>,
    )

    /**
     * Normalize every key returned by the LLM. Keys that cannot be parsed as a
     * crop id are dropped (they can never be looked up at render time).
     */
    fun normalizeTranslations(allTranslations: Map<String, String>): NormalizedTranslations {
        val byId = mutableMapOf<String, String>()
        val bareFtLookup = mutableMapOf<String, String>()
        for ((key, text) in allTranslations) {
            val id = ChunkTranslator.normalizeIdKey(key) ?: continue
            byId[id] = text
            if (ChunkTranslator.isFreeTextId(id)) {
                bareFtLookup.putIfAbsent(id.substringAfterLast('_'), text)
            }
        }
        return NormalizedTranslations(byId, bareFtLookup)
    }

    /**
     * Crops that were actually sent to the provider but have no usable
     * translation afterwards. Only ids that were part of the request can be
     * missing — bubbles ML Kit found no text in were never sent, so they must
     * not be treated as missing (that would push every no-text bubble through a
     * pointless vision round-trip).
     */
    fun missingEchoedIds(
        chunk: List<MosaicBuilder.CropItem>,
        sentIds: Set<String>,
        allTranslations: Map<String, String>,
    ): List<MosaicBuilder.CropItem> = chunk.filter { it.id in sentIds && !ChunkTranslator.hasTranslation(it.id, allTranslations) }

    /**
     * Resolve this page's translations from the normalized map. Every page
     * coordinate id is looked up directly; free-text ids additionally fall back
     * to the bare "ftN" form in case the model dropped the page prefix.
     */
    fun resolvePageTranslations(
        pageIds: Set<String>,
        normalized: NormalizedTranslations,
    ): Map<String, String> {
        val pageTranslations = mutableMapOf<String, String>()
        for (id in pageIds) {
            val text =
                if (ChunkTranslator.isFreeTextId(id)) {
                    normalized.byId[id] ?: normalized.bareFtLookup[id.substringAfterLast('_')]
                } else {
                    normalized.byId[id]
                }
            if (text != null) pageTranslations[id] = text
        }
        return pageTranslations
    }

    /**
     * (freeTextMatched, freeTextTotal) for a page, used by the dev-log "matched
     * X/Y free-text regions for render" line.
     */
    fun freeTextMatchCounts(
        pageIds: Set<String>,
        pageTranslations: Map<String, String>,
    ): Pair<Int, Int> {
        val total = pageIds.count { ChunkTranslator.isFreeTextId(it) }
        val matched = pageTranslations.keys.count { ChunkTranslator.isFreeTextId(it) }
        return matched to total
    }
}
