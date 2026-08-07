package com.kzkt.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Full local-data backup/restore: settings (DataStore), glossary, history and the
 * translation cache, all packed into a single JSON file that can be shared to
 * another device (e.g. via KDE Connect) and restored there.
 */
object BackupManager {

    const val BACKUP_VERSION = 1

    data class BackupResult(val ok: Boolean, val message: String)

    /**
     * Build the backup JSON from the current repositories. Returns the JSON string
     * (already wrapped in a versioned envelope).
     */
    suspend fun buildBackup(
        context: Context,
        settingsJson: String,
        glossary: Map<String, String>,
        history: List<HistoryEntry>,
    ): String {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("settings", JSONObject(settingsJson))
        root.put("glossary", JSONObject(glossary))

        val historyArr = org.json.JSONArray()
        history.forEach { historyArr.put(JSONObject(com.google.gson.Gson().toJson(it))) }
        root.put("history", historyArr)

        // Translation cache (translation memory) — optional, wrapped so a corrupt
        // cache never breaks the rest of the backup.
        try {
            val cacheFile = File(context.filesDir, "translation_cache.json")
            if (cacheFile.exists()) {
                root.put("cache", JSONObject(cacheFile.readText()))
            }
        } catch (_: Exception) {
        }

        return root.toString()
    }

    /**
     * Parse a backup file and apply it: history, glossary, then settings (so the
     * restored provider/model/keys are live for the UI), then the translation cache.
     */
    suspend fun applyBackup(
        context: Context,
        backupJson: String,
        settingsRepo: SettingsRepository,
        glossaryRepo: GlossaryRepository,
        historyRepo: HistoryRepository,
    ): BackupResult {
        return try {
            val root = JSONObject(backupJson)
            if (root.optInt("version", -1) != BACKUP_VERSION) {
                return BackupResult(false, "Unsupported backup version: ${root.optInt("version", -1)}")
            }

            // History
            if (root.has("history")) {
                val arr = root.getJSONArray("history")
                val entries = mutableListOf<HistoryEntry>()
                for (i in 0 until arr.length()) {
                    try {
                        val entry = com.google.gson.Gson().fromJson(arr.get(i).toString(), HistoryEntry::class.java)
                        entries.add(entry)
                    } catch (_: Exception) {}
                }
                historyRepo.restoreAll(entries)
            }

            // Glossary
            if (root.has("glossary")) {
                val g = root.getJSONObject("glossary")
                val map = mutableMapOf<String, String>()
                val keys = g.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = g.getString(k)
                }
                glossaryRepo.replaceAll(map)
            }

            // Settings (last — restores provider/model/keys used by the UI)
            if (root.has("settings")) {
                settingsRepo.importAllJson(root.getJSONObject("settings").toString())
            }

            // Translation cache
            if (root.has("cache")) {
                try {
                    File(context.filesDir, "translation_cache.json").writeText(root.getJSONObject("cache").toString())
                } catch (_: Exception) {}
            }

            BackupResult(true, "Backup restored successfully")
        } catch (e: Exception) {
            BackupResult(false, "Import failed: ${e.message}")
        }
    }

    /** Write a backup string to a cache file (later shared / moved to MediaStore). */
    fun writeToCache(context: Context, json: String): File {
        val dir = File(context.cacheDir, "backups")
        dir.mkdirs()
        val file = File(dir, "kzkt_backup_${System.currentTimeMillis()}.json")
        file.writeText(json)
        return file
    }

    /** Read a backup string from any content URI. */
    fun readFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
