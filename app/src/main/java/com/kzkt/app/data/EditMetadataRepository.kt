package com.kzkt.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * Persists the data needed by the in-app touch-up editor (original page bitmap,
 * bubble coordinates, and translations) so editing keeps working after the
 * translation session ends — e.g. when the reader is reopened from History.
 *
 * Entries are keyed by the OUTPUT FILE NAME (a short hash of it), because the
 * file name is preserved when the translated file is published to MediaStore,
 * while the full path changes (cache dir → /Download/KZKT). Saving and loading
 * both derive the key from the path they have, so they always agree.
 */
class EditMetadataRepository(private val context: Context) {

    data class EditMeta(
        val originalBitmap: Bitmap,
        val translations: Map<String, String>,
        val coordinateMap: Map<String, IntArray>,
        val targetLanguage: String,
    )

    private val metaDir: File
        get() = File(context.filesDir, "edit_meta").apply { mkdirs() }

    fun saveForOutput(
        outputPath: String,
        original: Bitmap,
        translations: Map<String, String>,
        coordinateMap: Map<String, IntArray>,
        targetLanguage: String,
    ) {
        if (translations.isEmpty() || coordinateMap.isEmpty()) return
        try {
            val key = keyFor(outputPath)
            File(metaDir, "$key.png").outputStream().use { out ->
                original.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val translationsJson = JsonObject()
            translations.forEach { (k, v) -> translationsJson.addProperty(k, v) }

            val coordsJson = JsonObject()
            coordinateMap.forEach { (k, box) ->
                val arr = JsonArray()
                box.forEach { arr.add(it) }
                coordsJson.add(k, arr)
            }

            val root = JsonObject().apply {
                addProperty("targetLanguage", targetLanguage)
                add("translations", translationsJson)
                add("coordinateMap", coordsJson)
            }
            File(metaDir, "$key.json").writeText(root.toString())
        } catch (_: Exception) {
            // Best-effort: metadata is a convenience, never fail translation over it.
        }
    }

    /**
     * Move the sidecar files saved under [fromPath]'s key to [toPath]'s key.
     * Used after MediaStore publishes the output — the public path can differ from
     * the cache path (and MediaStore may rename "name.png" → "name (1).png" on
     * collisions), so the metadata must follow the path the reader will use.
     */
    fun rekeyForOutput(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        try {
            val fromKey = keyFor(fromPath)
            val toKey = keyFor(toPath)
            if (fromKey == toKey) return
            val fromPng = File(metaDir, "$fromKey.png")
            val fromJson = File(metaDir, "$fromKey.json")
            if (!fromPng.exists() && !fromJson.exists()) return
            if (fromPng.exists()) {
                File(metaDir, "$toKey.png").writeBytes(fromPng.readBytes())
                fromPng.delete()
            }
            if (fromJson.exists()) {
                File(metaDir, "$toKey.json").writeText(fromJson.readText())
                fromJson.delete()
            }
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /** Remove the sidecar files for an output (called when its history entry is deleted). */
    fun deleteForOutput(outputPath: String) {
        try {
            val key = keyFor(outputPath)
            File(metaDir, "$key.png").delete()
            File(metaDir, "$key.json").delete()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    fun loadForOutput(outputPath: String): EditMeta? {
        return try {
            val key = keyFor(outputPath)
            val origFile = File(metaDir, "$key.png")
            val jsonFile = File(metaDir, "$key.json")
            if (!origFile.exists() || !jsonFile.exists()) return null

            val bitmap = BitmapFactory.decodeFile(origFile.absolutePath) ?: return null

            val root = JsonParser.parseString(jsonFile.readText()).asJsonObject
            val translations = mutableMapOf<String, String>()
            root.getAsJsonObject("translations").entrySet().forEach { (k, v) ->
                translations[k] = v.asString
            }
            val coords = mutableMapOf<String, IntArray>()
            root.getAsJsonObject("coordinateMap").entrySet().forEach { (k, v) ->
                coords[k] = v.asJsonArray.map { it.asInt }.toIntArray()
            }
            EditMeta(
                originalBitmap = bitmap,
                translations = translations,
                coordinateMap = coords,
                targetLanguage = root.get("targetLanguage")?.asString ?: "Indonesian",
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun keyFor(outputPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(File(outputPath).name.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    }
}
