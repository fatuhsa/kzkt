package com.kzkt.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.positionDataStore: DataStore<Preferences> by preferencesDataStore(name = "kzkt_reading")

/**
 * Last-read position per "book" (a chapter folder/sibling-page group), so the
 * reader can resume where the user left off instead of always opening page 1.
 *
 * Stored as one JSON blob (bookKey -> page index) under a single DataStore key —
 * small, no schema, and survives app restarts like the other preference stores.
 */
class ReadingPositionRepository(private val context: Context) {

    private companion object {
        val KEY_POSITIONS = stringPreferencesKey("positions")
    }

    private val gson = Gson()

    private suspend fun readAll(): Map<String, Int> {
        val json = context.positionDataStore.data.first()[KEY_POSITIONS]
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Int>>() {}.type
            gson.fromJson<Map<String, Int>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun get(bookKey: String): Int? = readAll()[bookKey]

    suspend fun save(bookKey: String, pageIndex: Int) {
        if (bookKey.isBlank()) return
        val updated = readAll().toMutableMap().apply {
            this[bookKey] = pageIndex.coerceAtLeast(0)
        }
        context.positionDataStore.edit { prefs ->
            prefs[KEY_POSITIONS] = gson.toJson(updated)
        }
    }
}
