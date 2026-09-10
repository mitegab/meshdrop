package com.meshdrop.core.util

import android.util.Log

/**
 * Tiny logging helper: keeps a single tag prefix so `adb logcat -s MeshDrop:*` shows
 * everything that happened during a transfer.
 */
object MeshLog {

    private const val PREFIX = "MeshDrop"

    var enabled: Boolean = true

    fun v(tag: String, message: String) = log(Log.VERBOSE, tag, message, null)

    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Log.ERROR, tag, message, throwable)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        val fullTag = if (tag.length > 20) "$PREFIX.${tag.take(20)}" else "$PREFIX.$tag"
        // android.util.Log is a stub on the JVM and throws when a unit test touches it.
        // Log output is never the point of a test, so a missing log sink stays silent here
        // instead of failing the domain code that happens to talk.
        runCatching {
            when (priority) {
                Log.VERBOSE -> Log.v(fullTag, message)
                Log.DEBUG -> Log.d(fullTag, message)
                Log.INFO -> Log.i(fullTag, message)
                Log.WARN -> if (throwable != null) Log.w(fullTag, message, throwable) else Log.w(fullTag, message)
                Log.ERROR -> if (throwable != null) Log.e(fullTag, message, throwable) else Log.e(fullTag, message)
            }
        }
    }
}
