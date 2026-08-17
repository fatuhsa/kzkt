package com.kzkt.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Logcat wrapper that is safe to call from code paths also executed by JVM unit
 * tests: android.util.Log is a stub there and throws "Method ... not mocked",
 * which would crash a passing test. Falls back to stderr so the message is
 * still visible when the code runs on the JVM (unit tests).
 *
 * Every message is also appended to an in-app ring buffer ([entries]) so the
 * user can inspect error handling / background failures from the Settings
 * "System Logs" viewer instead of only via adb logcat. The buffer is capped at
 * [MAX_BUFFER] entries and is never persisted.
 */
object KLog {
    const val MAX_BUFFER = 400

    /** One buffered log line with the level needed for UI coloring. */
    data class LogEntry(
        val text: String,
        val isError: Boolean,
        val isWarning: Boolean,
    )

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Drop all buffered entries (used by the viewer's clear button). */
    fun clear() {
        _entries.value = emptyList()
    }

    fun d(
        tag: String,
        msg: String,
    ) = log(Log.DEBUG, tag, msg, isError = false, isWarning = false)

    fun w(
        tag: String,
        msg: String,
    ) = log(Log.WARN, tag, msg, isError = false, isWarning = true)

    fun e(
        tag: String,
        msg: String,
    ) = log(Log.ERROR, tag, msg, isError = true, isWarning = false)

    private fun log(
        level: Int,
        tag: String,
        msg: String,
        isError: Boolean,
        isWarning: Boolean,
    ) {
        try {
            when (level) {
                Log.ERROR -> Log.e(tag, msg)
                Log.WARN -> Log.w(tag, msg)
                else -> Log.d(tag, msg)
            }
        } catch (_: Throwable) {
            // JVM unit test environment: android.util.Log throws "not mocked".
            System.err.println("[$tag] $msg")
        }
        _entries.update { (it + LogEntry(msg, isError, isWarning)).takeLast(MAX_BUFFER) }
    }
}
