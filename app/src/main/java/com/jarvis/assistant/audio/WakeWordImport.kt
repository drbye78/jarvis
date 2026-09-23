package com.jarvis.assistant.audio

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The one definition of how a user-picked Picovoice `.ppn` file becomes the
 * private wake-word model the detector loads.
 *
 * This exists because the copy is the part of the import that can fail, and the
 * old inline version failed *silently and destructively*:
 *
 *  - `contentResolver.openInputStream(uri)?.use { … }` skipped the whole copy
 *    when the stream came back null, and the caller then persisted
 *    `customWakeWordPath` + `wakeWordModel = "custom_user"` anyway — pointing
 *    the detector at a missing or stale file with no error anywhere;
 *  - a `copyTo` `IOException` (disk full, provider error) escaped
 *    `onActivityResult` uncaught on the main thread.
 *
 * The contract here is that a non-[Outcome.Copied] result means **nothing was
 * installed**: the copy lands in a sibling `*.tmp` file, is verified to be a
 * non-empty file, and only then replaces the destination. So a failed import
 * can neither leave a truncated model behind nor destroy a previously working
 * one, and the caller has an explicit outcome to report and log.
 *
 * Pure JVM (no Android types) so the failure paths above are unit-testable —
 * `androidTest` is nightly-only and this screen has no other coverage.
 */
object WakeWordImport {

    /** The private file the imported model is installed as. */
    const val PPN_FILE_NAME = "user_wake.ppn"

    private const val PPN_EXTENSION = ".ppn"
    private const val TEMP_SUFFIX = ".tmp"

    /** What an [install] attempt did. */
    sealed interface Outcome {
        /** The model is installed at the destination. */
        data class Copied(val bytes: Long) : Outcome

        /** The picked URI could not be opened at all — nothing to copy. */
        data object NoSource : Outcome

        /**
         * The copy ran and failed. [reason] is deliberately content-free (an
         * exception class name or a short tag) so it is safe to log.
         */
        data class Failed(val reason: String) : Outcome

        /** Content-free label for logging. */
        fun describe(): String = when (this) {
            is Copied -> "copied"
            NoSource -> "no-source"
            is Failed -> "failed:$reason"
        }
    }

    /** Whether [name] is a `.ppn` file name (case-insensitive). */
    fun isPpnFileName(name: String?): Boolean =
        name != null && name.endsWith(PPN_EXTENSION, ignoreCase = true)

    /**
     * Copies [source] into [destination], replacing it only on success.
     *
     * [openOutput] is how the caller creates the write stream for a given file
     * (on Android: `openFileOutput(file.name, MODE_PRIVATE)`) — injected so the
     * failure paths are testable, and because the temp file must be created by
     * the same mechanism as the destination.
     */
    fun install(
        source: InputStream?,
        destination: File,
        openOutput: (File) -> OutputStream,
    ): Outcome {
        if (source == null) return Outcome.NoSource
        val temp = File(destination.parentFile, destination.name + TEMP_SUFFIX)
        return try {
            source.use { input ->
                openOutput(temp).use { output -> input.copyTo(output) }
            }
            val bytes = temp.length()
            if (!temp.isFile || bytes <= 0L) {
                // A zero-byte "copy" is the silent-skip failure mode, not a
                // valid model: .ppn files always carry a header.
                temp.delete()
                Outcome.Failed("empty")
            } else if (!temp.renameTo(destination)) {
                temp.delete()
                Outcome.Failed("rename")
            } else {
                Outcome.Copied(bytes)
            }
        } catch (e: IOException) {
            temp.delete()
            Outcome.Failed(e.javaClass.simpleName)
        } catch (e: SecurityException) {
            temp.delete()
            Outcome.Failed(e.javaClass.simpleName)
        }
    }
}
