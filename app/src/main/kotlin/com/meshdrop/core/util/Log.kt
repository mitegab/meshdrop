package com.meshdrop.core.util

/** Portable logging; Android routes stderr to Logcat and the CLI keeps stdout machine-readable. */
object MeshLog {
    @Volatile var enabled = true
    var sink: (String, String, Throwable?) -> Unit = { tag, message, error ->
        System.err.println("MeshDrop.$tag: $message${error?.let { ": ${it.message}" }.orEmpty()}")
    }
    fun v(tag: String, message: String) = Unit
    fun d(tag: String, message: String) = Unit
    fun i(tag: String, message: String) = log(tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(tag, message, throwable)
    private fun log(tag: String, message: String, error: Throwable?) { if (enabled) runCatching { sink(tag, message, error) } }
}
