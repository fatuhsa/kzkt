package com.kzkt.app.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Translation Memory: persistent local cache for crop translations based on crop hashes.
 * Allows 100% free & instant re-translation of identical speech bubbles.
 *
 * The file is only rewritten when [flush] is called (or every [AUTO_FLUSH_EVERY] new
 * entries) instead of on every single bubble, and the in-memory map is capped so the
 * cache cannot grow without bound.
 */
class TranslationCacheRepository(private val context: Context) {

    private val cacheFile: File
        get() = File(context.filesDir, "translation_cache.json")

    // Access-order LinkedHashMap with a size cap: least-recently-used entries are evicted.
    private val memoryCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_ENTRIES
    }

    @Volatile
    private var dirty = false

    private var pendingWrites = 0

    init {
        loadCache()
    }

    private companion object {
        const val MAX_ENTRIES = 2000
        const val AUTO_FLUSH_EVERY = 25
    }

    @Synchronized
    private fun loadCache() {
        if (!cacheFile.exists()) return
        try {
            val jsonStr = cacheFile.readText()
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                memoryCache[key] = json.getString(key)
            }
        } catch (e: Exception) {
            Log.w("KZKT", "Failed to load translation cache: ${e.message}")
        }
    }

    @Synchronized
    private fun saveCache() {
        if (!dirty) return
        try {
            val json = JSONObject()
            for ((k, v) in memoryCache) {
                json.put(k, v)
            }
            cacheFile.writeText(json.toString())
            dirty = false
            pendingWrites = 0
        } catch (e: Exception) {
            Log.w("KZKT", "Failed to save translation cache: ${e.message}")
        }
    }

    /**
     * Cheap perceptual-ish hash: downscale the crop to 32×32 and MD5 the pixels.
     * Avoids a full PNG re-compress of every crop for every bubble.
     */
    fun computeHash(bitmap: Bitmap): String {
        if (bitmap.isRecycled) return ""
        return try {
            // Always scale to exactly 32×32: reading a 32×32 window from a smaller bitmap
            // would throw and silently disable the cache for small crops.
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            scaled.recycle()
            val bytes = ByteArray(pixels.size * 4)
            var idx = 0
            for (p in pixels) {
                bytes[idx++] = (p shr 24).toByte()
                bytes[idx++] = (p shr 16).toByte()
                bytes[idx++] = (p shr 8).toByte()
                bytes[idx++] = p.toByte()
            }
            val md = MessageDigest.getInstance("MD5")
            md.digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    fun getTranslation(cropBitmap: Bitmap, targetLanguage: String): String? {
        val hash = computeHash(cropBitmap)
        if (hash.isBlank()) return null
        val key = "${hash}_${targetLanguage.lowercase()}"
        return memoryCache[key]
    }

    @Synchronized
    fun saveTranslation(cropBitmap: Bitmap, targetLanguage: String, translatedText: String) {
        if (translatedText.isBlank() || translatedText.uppercase() == "SKIP") return
        val hash = computeHash(cropBitmap)
        if (hash.isBlank()) return
        val key = "${hash}_${targetLanguage.lowercase()}"
        memoryCache[key] = translatedText
        dirty = true
        if (++pendingWrites >= AUTO_FLUSH_EVERY) saveCache()
    }

    /** Persist any pending in-memory changes to disk (call once after a batch finishes). */
    @Synchronized
    fun flush() {
        saveCache()
    }

    /** Clear all cached translations from memory and disk. */
    @Synchronized
    fun clear() {
        memoryCache.clear()
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        dirty = false
        pendingWrites = 0
    }
}
