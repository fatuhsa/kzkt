package com.kzkt.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class GlossaryRepository(
    private val context: Context,
) {
    // All disk I/O runs on this scope so constructing the repo (MainViewModel field,
    // UI thread) and add/remove taps never block the main thread.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val glossaryFile: File
        get() = File(context.filesDir, "glossary.json")

    private val _glossary = MutableStateFlow<Map<String, String>>(emptyMap())
    val glossary: StateFlow<Map<String, String>> = _glossary

    // Lets consumers that need the value synchronously (TranslationWorker) wait
    // until the asynchronous initial load has finished.
    private val initialLoad = CompletableDeferred<Unit>()

    // Serializes read-modify-write of the glossary so two rapid mutations (or one that
    // happens before the initial load completes) can never drop or clobber terms.
    private val mutationMutex = Mutex()

    init {
        ioScope.launch {
            try {
                loadGlossary()
            } finally {
                initialLoad.complete(Unit)
            }
        }
    }

    /** Suspends until the initial file read has completed (used by the worker, which reads synchronously). */
    suspend fun awaitInitialLoad() {
        initialLoad.await()
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

    /** Write-only; state updates happen at the call sites so the UI reflects them immediately. */
    @Synchronized
    private fun persist(map: Map<String, String>) {
        try {
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            glossaryFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w("KZKT", "Failed to save glossary: ${e.message}")
        }
    }

    fun addTerm(
        original: String,
        translation: String,
    ) {
        if (original.isBlank() || translation.isBlank()) return
        ioScope.launch {
            mutationMutex.withLock {
                // Wait for the initial load so a term added before it finished can never
                // overwrite the existing file contents with just the new term.
                initialLoad.await()
                val current = _glossary.value.toMutableMap()
                current[original] = translation
                _glossary.value = current
                persist(current)
            }
        }
    }

    fun removeTerm(original: String) {
        ioScope.launch {
            mutationMutex.withLock {
                initialLoad.await()
                val current = _glossary.value.toMutableMap()
                if (current.remove(original) != null) {
                    _glossary.value = current
                    persist(current)
                }
            }
        }
    }

    /** Replace the whole glossary in one write (used by backup restore). */
    fun replaceAll(map: Map<String, String>) {
        val copy = map.toMutableMap()
        ioScope.launch {
            mutationMutex.withLock {
                initialLoad.await()
                _glossary.value = copy
                persist(copy)
            }
        }
    }
}
