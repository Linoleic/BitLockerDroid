package com.bitlockerdroid.provider

import com.bitlockerdroid.ntfs.VolumeWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DocumentWritePipelineTest {
    private class Writer : VolumeWriter {
        val bytes = ByteArrayOutputStream()
        var maxWrite = Int.MAX_VALUE
        var syncOk = true
        var truncateOk = true
        var truncated: Long? = null
        var syncCalls = 0
        override val isMounted = true
        override fun createFile(parentPath: String?, name: String, isDirectory: Boolean) = 1L
        override fun delete(path: String) = true
        override fun rename(oldPath: String, newPath: String) = true
        override fun write(path: String, offset: Long, data: ByteArray, count: Int): Long {
            if (maxWrite <= 0) return maxWrite.toLong()
            assertEquals(bytes.size().toLong(), offset)
            val n = minOf(count, maxWrite)
            bytes.write(data, 0, n)
            return n.toLong()
        }
        override fun truncate(path: String, newSize: Long): Boolean {
            truncated = newSize
            return truncateOk
        }
        override fun sync(): Boolean { syncCalls++; return syncOk }
        override fun close() {}
    }

    @Test fun shortWritesAreRetriedWithoutLosingOrDuplicatingBytes() {
        for (buffered in listOf(false, true)) {
            val data = ByteArray(4097) { (it % 251).toByte() }
            val writer = Writer().apply { maxWrite = 7 }
            val written = DocumentWritePipeline.copy(ByteArrayInputStream(data), writer, "/file", 0, true, buffered, bufferSize = 128)
            assertEquals(data.size.toLong(), written)
            assertArrayEquals(data, writer.bytes.toByteArray())
            assertEquals(data.size.toLong(), writer.truncated)
            assertEquals(1, writer.syncCalls)
        }
    }

    @Test fun zeroAndNegativeWritesAbortBothPipelinesBeforeTruncateOrSync() {
        for (buffered in listOf(false, true)) for (result in listOf(0, -1)) {
            val writer = Writer().apply { maxWrite = result }
            assertThrows(IOException::class.java) {
                DocumentWritePipeline.copy(ByteArrayInputStream(ByteArray(100)), writer, "/file", 0, true, buffered, bufferSize = 16)
            }
            assertNull(writer.truncated)
            assertEquals(0, writer.syncCalls)
        }
    }

    @Test fun syncAndTruncateFailuresAreReported() {
        for (buffered in listOf(false, true)) {
            assertThrows(IOException::class.java) {
                DocumentWritePipeline.copy(ByteArrayInputStream(byteArrayOf(1)), Writer().apply { syncOk = false }, "/file", 0, true, buffered)
            }
            val writer = Writer().apply { truncateOk = false }
            assertThrows(IOException::class.java) {
                DocumentWritePipeline.copy(ByteArrayInputStream(byteArrayOf(1)), writer, "/file", 0, true, buffered)
            }
            assertEquals(0, writer.syncCalls)
        }
    }

    @Test fun emptyOverwriteTruncatesButReadWriteModePreservesFileLength() {
        for (buffered in listOf(false, true)) {
            val overwrite = Writer()
            DocumentWritePipeline.copy(ByteArrayInputStream(byteArrayOf()), overwrite, "/file", 0, true, buffered)
            assertEquals(0L, overwrite.truncated)
            val readWrite = Writer()
            DocumentWritePipeline.copy(ByteArrayInputStream(byteArrayOf()), readWrite, "/file", 0, false, buffered)
            assertNull(readWrite.truncated)
        }
    }

    @Test(timeout = 3000) fun failedProducerStopsBufferedConsumerWithoutFinalizingPartialData() {
        val writer = Writer()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("source disconnected")
        }
        assertThrows(IOException::class.java) {
            DocumentWritePipeline.copy(broken, writer, "/file", 0, true, true)
        }
        assertNull(writer.truncated)
        assertEquals(0, writer.syncCalls)
    }

    @Test(timeout = 3000) fun diskFailureClosesTheSourceAndUnblocksTheProducer() {
        val released = CountDownLatch(1)
        val source = object : InputStream() {
            var first = true
            override fun read(): Int = error("bulk read expected")
            override fun read(data: ByteArray, off: Int, len: Int): Int {
                if (first) {
                    first = false
                    data.fill(1, off, off + len)
                    return len
                }
                if (!released.await(2, TimeUnit.SECONDS)) throw AssertionError("producer was not aborted")
                throw IOException("source closed by disk failure")
            }
        }
        val writer = Writer().apply { maxWrite = -1 }
        assertThrows(IOException::class.java) {
            DocumentWritePipeline.copy(source, writer, "/file", 0, true, true,
                abortInput = { released.countDown() }, bufferSize = 16)
        }
        assertEquals(0L, released.count)
        assertNull(writer.truncated)
        assertEquals(0, writer.syncCalls)
    }
}
