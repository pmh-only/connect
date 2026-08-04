package codes.pmh.connect

import android.content.Context
import androidx.core.content.edit

object CrashLogStore {
    private const val PREFERENCES_NAME = "connect_crash_log"
    private const val LAST_CRASH_KEY = "last_crash"
    private const val MAX_CRASH_LENGTH = 32_000

    fun record(context: Context, thread: Thread, error: Throwable) {
        val report = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine("Time: ${System.currentTimeMillis()}")
            append(error.stackTraceToString())
        }.take(MAX_CRASH_LENGTH)
        runCatching {
            preferences(context).edit(commit = true) { putString(LAST_CRASH_KEY, report) }
        }
    }

    fun read(context: Context): String? = runCatching {
        preferences(context).getString(LAST_CRASH_KEY, null)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { preferences(context).edit(commit = true) { remove(LAST_CRASH_KEY) } }
    }

    private fun preferences(context: Context) = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
