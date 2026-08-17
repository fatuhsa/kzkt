package com.kzkt.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.kzkt.app.util.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "kzkt_history")

/** A finished translation record shown in the Riwayat tab. */
data class HistoryEntry(
    val timestamp: Long,
    val fileName: String,
    val outputPath: String,
    val pageCount: Int,
    val provider: String,
    val targetLanguage: String,
    // Original source path — enables retry-from-history of failed pages. Empty for
    // entries recorded before this field existed (Gson defaults keep them loadable).
    val inputPath: String = "",
    // "ok" = translated, "failed" = the page failed and is retryable from History.
    val status: String = "ok",
    // ID of the translation run this page belongs to. All pages of one batch
    // (folder / multi-select / PDF) share the same ID, so the reader can group
    // siblings even when the source file names differ. Empty for entries recorded
    // before this field existed (Gson defaults keep them loadable).
    val batchId: String = "",
)

/**
 * History persistence via DataStore Preferences.
 * Stores a JSON-encoded list of [HistoryEntry] in a single string key —
 * no Room, no schema migration, safe to add alongside [SettingsRepository].
 */
class HistoryRepository(
    private val context: Context,
) {
    private companion object {
        val KEY_ENTRIES = stringPreferencesKey("entries")
    }

    private val gson = Gson()

    val entriesFlow: Flow<List<HistoryEntry>> =
        context.historyDataStore.data.map { prefs ->
            val json = prefs[KEY_ENTRIES]
            if (json.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    val arr = gson.fromJson(json, Array<HistoryEntry>::class.java)
                    arr.toList()
                } catch (e: Exception) {
                    KLog.w("KZKT", "History: failed to parse stored entries — showing empty history (data may be corrupted): ${e.message}")
                    emptyList()
                }
            }
        }

    /** Newest first. Also keeps a monotonically increasing revision to bust any caching. */
    suspend fun record(entry: HistoryEntry) {
        context.historyDataStore.edit { prefs ->
            val json = prefs[KEY_ENTRIES]
            val existing =
                if (json.isNullOrBlank()) {
                    emptyList()
                } else {
                    try {
                        gson.fromJson(json, Array<HistoryEntry>::class.java).toList()
                    } catch (e: Exception) {
                        KLog.w("KZKT", "History: failed to parse stored entries before recording: ${e.message}")
                        emptyList()
                    }
                }
            val updated = (listOf(entry) + existing).sortedByDescending { it.timestamp }
            prefs[KEY_ENTRIES] = gson.toJson(updated)
        }
    }

    /** Remove every entry whose source input path matches (used after a retry succeeds). */
    suspend fun deleteByInputPath(inputPath: String) {
        if (inputPath.isBlank()) return
        context.historyDataStore.edit { prefs ->
            val json = prefs[KEY_ENTRIES]
            if (json.isNullOrBlank()) return@edit
            try {
                val updated =
                    gson
                        .fromJson(json, Array<HistoryEntry>::class.java)
                        .filterNot { it.inputPath == inputPath }
                prefs[KEY_ENTRIES] = gson.toJson(updated)
            } catch (e: Exception) {
                // corrupt entry list — leave as is
                KLog.w("KZKT", "History: failed to parse entries during deleteByInputPath — deletion skipped: ${e.message}")
            }
        }
    }

    suspend fun delete(timestamp: Long) {
        context.historyDataStore.edit { prefs ->
            val json = prefs[KEY_ENTRIES]
            if (json.isNullOrBlank()) return@edit
            try {
                val updated =
                    gson
                        .fromJson(json, Array<HistoryEntry>::class.java)
                        .filterNot { it.timestamp == timestamp }
                prefs[KEY_ENTRIES] = gson.toJson(updated)
            } catch (e: Exception) {
                // corrupt entry list — leave as is
                KLog.w("KZKT", "History: failed to parse entries during delete — deletion skipped: ${e.message}")
            }
        }
    }

    /**
     * Replace the whole list in a single write — used by the clear-all Undo so we
     * don't queue N sequential DataStore edits to restore a large history.
     */
    suspend fun restoreAll(entries: List<HistoryEntry>) {
        context.historyDataStore.edit { prefs ->
            prefs[KEY_ENTRIES] = gson.toJson(entries.sortedByDescending { it.timestamp })
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { prefs ->
            prefs[KEY_ENTRIES] = "[]"
        }
    }
}
