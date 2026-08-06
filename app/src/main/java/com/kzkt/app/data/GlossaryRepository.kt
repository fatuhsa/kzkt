package com.kzkt.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GlossaryRepository(private val context: Context) {

    private val glossaryFile: File
        get() = File(context.filesDir, "glossary.json")

    private val _glossary = MutableStateFlow<Map<String, String>>(emptyMap())
    val glossary: StateFlow<Map<String, String>> = _glossary

    init {
        loadGlossary()
    }

    @Synchronized
    private fun loadGlossary() {
        if (!glossaryFile.exists()) {
            _glossary.value = emptyMap()
            return
        }
        try {
            val jsonStr = glossaryFile.readText()
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
            _glossary.value = map
        } catch (e: Exception) {
            Log.w("KZKT", "Failed to load glossary: ${e.message}")
        }
    }

    @Synchronized
    private fun saveGlossary(map: Map<String, String>) {
        try {
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            glossaryFile.writeText(json.toString())
            _glossary.value = map
        } catch (e: Exception) {
            Log.w("KZKT", "Failed to save glossary: ${e.message}")
        }
    }

    fun addTerm(original: String, translation: String) {
        if (original.isBlank() || translation.isBlank()) return
        val current = _glossary.value.toMutableMap()
        current[original] = translation
        saveGlossary(current)
    }

    fun removeTerm(original: String) {
        val current = _glossary.value.toMutableMap()
        if (current.remove(original) != null) {
            saveGlossary(current)
        }
    }

    fun getTerm(original: String): String? {
        return _glossary.value[original]
    }
}
