package com.bitlockerdroid.provider

import com.bitlockerdroid.ntfs.VolumeWriter
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Copies a SAF stream without treating queued bytes or failed flushes as committed data. */
object DocumentWritePipeline {
    fun copy(
        input: InputStream,
        writer: VolumeWriter,
        path: String,
        startOffset: Long,
        truncate: Boolean,
        doubleBuffered: Boolean,
        abortInput: (Throwable) -> Unit = {},
        bufferSize: Int = 512 * 1024,
        beforeSync: () -> Unit = {}
    ): Long {
        require(bufferSize > 0)
        val endOffset = if (doubleBuffered) {
            copyBuffered(input, writer, path, startOffset, abortInput, bufferSize)
        } else {
            val buffer = ByteArray(bufferSize)
            var offset = startOffset
            while (true) {
                val count = readChunk(input, buffer)
                if (count < 0) break
                if (count == 0) continue
                writeFully(writer, path, offset, buffer, count)
                offset += count
            }
            offset
        }
        if (truncate && !writer.truncate(path, endOffset)) throw IOException("Could not finalize file size")
        beforeSync()
        if (!writer.sync()) throw IOException("Could not flush file data to the drive")
        return endOffset
    }

    internal fun writeFully(writer: VolumeWriter, path: String, offset: Long, data: ByteArray, count: Int) {
        var done = 0
        while (done < count) {
            val remaining = count - done
            val bytes = if (done == 0) data else data.copyOfRange(done, count)
            val written = writer.write(path, offset + done, bytes, remaining)
            if (written <= 0 || written > remaining) throw IOException("Drive could not complete the file write")
            done += written.toInt()
        }
    }

    private class Slot(val data: ByteArray, var count: Int = 0)

    private fun readChunk(input: InputStream, data: ByteArray): Int {
        var count = 0
        while (count < data.size) {
            val n = input.read(data, count, data.size - count)
            if (n < 0) break
            count += n
        }
        return if (count == 0) -1 else count
    }

    private fun copyBuffered(
        input: InputStream, writer: VolumeWriter, path: String, start: Long,
        abortInput: (Throwable) -> Unit, capacity: Int
    ): Long {
        val empty = ArrayBlockingQueue<Slot>(2)
        val ready = ArrayBlockingQueue<Slot>(2)
        repeat(2) { empty.add(Slot(ByteArray(capacity))) }
        val end = Slot(ByteArray(0), -1)
        val failure = AtomicReference<Throwable?>()
        var committedOffset = start
        fun fail(t: Throwable) {
            if (failure.compareAndSet(null, t)) {
                try { abortInput(t) } catch (_: Throwable) {}
            }
        }
        val consumer = Thread({
            try {
                while (failure.get() == null) {
                    val slot = ready.take()
                    if (slot === end) break
                    writeFully(writer, path, committedOffset, slot.data, slot.count)
                    committedOffset += slot.count
                    empty.put(slot)
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }, "saf-disk-writer").apply { isDaemon = true }
        consumer.start()
        try {
            while (failure.get() == null) {
                val slot = empty.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val count = readChunk(input, slot.data)
                if (count < 0) break
                if (count == 0) {
                    empty.put(slot)
                    continue
                }
                slot.count = count
                while (failure.get() == null && !ready.offer(slot, 100, TimeUnit.MILLISECONDS)) { }
            }
        } catch (t: Throwable) {
            fail(t)
        } finally {
            try {
                if (failure.get() == null) {
                    while (failure.get() == null && !ready.offer(end, 100, TimeUnit.MILLISECONDS)) { }
                }
            } catch (t: Throwable) {
                fail(t)
            }
            if (failure.get() != null) consumer.interrupt()
            // Keep the owning write ticket alive until all native writes really stop.
            // Eject has its own bounded wait and must not close this session on timeout.
            var interrupted = false
            while (consumer.isAlive) {
                try { consumer.join() } catch (e: InterruptedException) {
                    interrupted = true
                    fail(e)
                    consumer.interrupt()
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure.get()?.let { throw IOException("File transfer did not complete", it) }
        return committedOffset
    }
}
