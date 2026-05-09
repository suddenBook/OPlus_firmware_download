package com.desmond.ofd.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted user preferences for the downloader. Currently:
 *  - [threadCount] — concurrent connections; 0 = auto, 1..32 explicit.
 */
class DownloadPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)

    private val _threadCount = MutableStateFlow(prefs.getInt(KEY_THREAD_COUNT, AUTO))
    val threadCount: StateFlow<Int> = _threadCount.asStateFlow()

    fun setThreadCount(value: Int) {
        val clamped = value.coerceIn(AUTO, MAX)
        prefs.edit().putInt(KEY_THREAD_COUNT, clamped).apply()
        _threadCount.value = clamped
    }

    companion object {
        const val AUTO = 0
        const val MIN = 1
        const val MAX = 32
        private const val KEY_THREAD_COUNT = "thread_count"
    }
}
