package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * File-based logging tree for release builds. The original app planted Timber
 * only in DEBUG builds, which made the RUNBOOK's `adb logcat -s Timber:*`
 * debugging instructions useless on release. Release builds now log
 * INFO-and-above to rotating files under filesDir/logs/.
 *
 * D3 (blocking IO on the caller thread): Timber trees run synchronously on
 * the logging thread, and this app logs from the MAIN thread (Application
 * onCreate, activities, the service lifecycle). Every line used to cost a
 * stat + open + write + close on the caller. All disk work now runs on one
 * dedicated daemon writer thread (FIFO order preserved); the caller only
 * formats its arguments. Pending lines are lost on process death — an
 * accepted trade-off for log records.
 */
class FileLoggingTree(
    context: Context,
    private val minPriority: Int = Log.INFO,
    private val maxFileBytes: Long = 512 * 1024,
    private val maxFiles: Int = 3,
) : Timber.Tree() {

    private val dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }

    // SimpleDateFormat is not thread-safe: formatting happens INSIDE the
    // single writer thread, so no lock is needed around it.
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-file-log").apply { isDaemon = true }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < minPriority) return
        // Only cheap argument capture happens on the caller; formatting and
        // disk IO run on the writer thread (SimpleDateFormat is not
        // thread-safe — formatting inside the single writer avoids races).
        val capturedTag = tag ?: "Jarvis"
        val capturedPriority = priority
        writer.execute {
            try {
                val line = buildString {
                    append(stamp.format(Date()))
                    append(' ').append(priorityChar(capturedPriority))
                    append('/').append(capturedTag)
                    append(": ").append(message)
                    t?.let { append("\n").append(Log.getStackTraceString(it)) }
                    append('\n')
                }
                rotateIfNeeded()
                currentFile().appendText(line)
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
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
