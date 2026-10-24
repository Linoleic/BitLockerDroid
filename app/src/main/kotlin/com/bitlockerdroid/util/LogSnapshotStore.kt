package com.bitlockerdroid.util

import java.io.File
import java.io.RandomAccessFile

data class LogSnapshot(val application: String?, val crash: String?, val notes: List<String> = emptyList())

/** Shares one short lock for application log append, trim, read and clear. */
internal class LogSnapshotStore(private val directory: File, private val maxBytes: Int = 512 * 1024) {
    private val monitor = Any()
    val applicationFile get() = File(directory, "bitlocker.log")

    fun append(line: String) = synchronized(monitor) {
        directory.mkdirs()
        var bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) bytes = "[Oversized log entry omitted]\n".toByteArray(Charsets.UTF_8)
        if (applicationFile.length() + bytes.size > maxBytes) {
            val keep = minOf(maxBytes / 2, (maxBytes - bytes.size).coerceAtLeast(0))
            applicationFile.writeText(readTail(applicationFile, keep).first.orEmpty(), Charsets.UTF_8)
        }
        applicationFile.appendBytes(bytes)
    }

    fun readApplication(): String? = synchronized(monitor) { readTail(applicationFile, maxBytes).first }

    fun clear() = synchronized(monitor) {
        if (applicationFile.exists()) applicationFile.writeText("", Charsets.UTF_8)
    }

    fun snapshot(): LogSnapshot = synchronized(monitor) {
        val notes = mutableListOf<String>()
        fun read(file: File, limit: Int): String? = try {
            val (text, truncated) = readTail(file, limit)
            if (truncated) notes += "${file.name}: only the most recent complete lines within $limit bytes are included."
            text
        } catch (e: Exception) {
            notes += "${file.name}: unavailable (${e.javaClass.simpleName})."
            null
        }
        val app = read(applicationFile, maxBytes)
        // The raw crash handler deliberately bypasses this store. Bound the read to
        // the length observed at open; never make crash handling wait on a log lock.
        val crash = read(File(directory, "crash.txt"), 256 * 1024)
        LogSnapshot(app, crash, notes)
    }

    private fun readTail(file: File, limit: Int): Pair<String?, Boolean> {
        if (!file.exists()) return null to false
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - limit).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((length - start).toInt())
            input.readFully(bytes)
            // Drop the leading partial line, including any split UTF-8 code point.
            val first = if (start > 0) bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it + 1 } else 0
            return String(bytes, first, bytes.size - first, Charsets.UTF_8) to (start > 0)
        }
    }
}
