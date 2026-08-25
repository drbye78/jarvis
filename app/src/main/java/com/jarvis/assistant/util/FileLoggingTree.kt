package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based logging tree for release builds. The original app planted Timber
 * only in DEBUG builds, which made the RUNBOOK's `adb logcat -s Timber:*`
 * debugging instructions useless on release. Release builds now log
 * INFO-and-above to rotating files under filesDir/logs/.
 */
class FileLoggingTree(
    context: Context,
    private val minPriority: Int = Log.INFO,
    private val maxFileBytes: Long = 512 * 1024,
    private val maxFiles: Int = 3,
) : Timber.Tree() {

    private val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < minPriority) return
        try {
            rotateIfNeeded()
            val file = currentFile()
            val line = buildString {
                append(stamp.format(Date()))
                append(' ').append(priorityChar(priority))
                append('/').append(tag ?: "Jarvis")
                append(": ").append(message)
                t?.let { append("\n").append(android.util.Log.getStackTraceString(it)) }
                append('\n')
            }
            file.appendText(line)
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    private fun currentFile(): File = File(dir, "jarvis.log")

    private fun rotateIfNeeded() {
        val file = currentFile()
        if (!file.exists() || file.length() < maxFileBytes) return
        // jarvis.log -> jarvis.1.log -> jarvis.2.log (drop oldest)
        for (i in maxFiles - 2 downTo 1) {
            val from = File(dir, "jarvis.$i.log")
            val to = File(dir, "jarvis.${i + 1}.log")
            if (from.exists()) from.renameTo(to)
        }
        file.renameTo(File(dir, "jarvis.1.log"))
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
