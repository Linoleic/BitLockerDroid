package com.bitlockerdroid.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class UsbBlockProtocolTest {
    private fun frame(command: Int, offset: Long = 0, length: Int = 4): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put(command.toByte()).putLong(offset).putInt(length).array()

    private fun statuses(output: ByteArrayOutputStream): List<Int> {
        val bytes = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (bytes.hasRemaining()) add(bytes.int) }
    }

    @Test fun writeAcknowledgementWaitsForBackendCompletion() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val ack = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val output = object : ByteArrayOutputStream() {
            override fun flush() { if (size() > 4) ack.countDown() }
        }
        val payload = byteArrayOf(1, 2, 3, 4)
        val protocol = UsbBlockProtocol(100, { _, _ -> fail("Unexpected read") }, { offset, buffer ->
            assertEquals(7L, offset)
            assertArrayEquals(payload, ByteArray(4).also { buffer.get(it) })
            entered.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
        }, { _, _, _, error -> throw error })
        val worker = Thread {
            try { protocol.serve(ByteArrayInputStream(frame(2, 7) + payload + byteArrayOf(3, 0)), output) { true } }
            catch (error: Throwable) { errors.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("Success ACK before write completion", ack.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finish.countDown()
            worker.join(3000)
        }
        assertFalse(worker.isAlive)
        errors.get()?.let { throw AssertionError(it) }
        assertEquals(listOf(0x4249544c, 4, 0), statuses(output))
    }

    @Test fun failedWriteRejectsLaterReadWriteAndRepeatedSyncWithoutReusingBackend() {
        var reads = 0
        var writes = 0
        var failures = 0
        val protocol = UsbBlockProtocol(100, { _, _ -> reads++ }, { _, _ ->
            writes++
            throw IOException("disk write failed")
        }, { command, offset, length, _ ->
            assertEquals(2, command); assertEquals(0L, offset); assertEquals(4, length)
            failures++
        })
        val output = ByteArrayOutputStream()
        val data = ByteArray(4)
        val requests = frame(2) + data + frame(1) + frame(2) + data + byteArrayOf(3, 3, 0)
        protocol.serve(ByteArrayInputStream(requests), output) { true }
        assertEquals(listOf(0x4249544c, -5, -5, -5, -5, -5), statuses(output))
        assertEquals(0, reads); assertEquals(1, writes); assertEquals(1, failures)
    }

    @Test fun shortReadIsAnErrorAndDoesNotReturnUninitializedPayload() {
        val output = ByteArrayOutputStream()
        UsbBlockProtocol(100, { _, buffer -> buffer.put(7) }, { _, _ -> }, { _, _, _, _ -> })
            .serve(ByteArrayInputStream(frame(1) + byteArrayOf(3, 0)), output) { true }
        assertEquals(listOf(0x4249544c, -5, -5), statuses(output))
    }

    @Test fun readAfterWriteReturnsActualBytesAndSizeFramingIsUnchanged() {
        val disk = ByteArray(100)
        val data = byteArrayOf(8, 6, 4, 2)
        val output = ByteArrayOutputStream()
        UsbBlockProtocol(100, { offset, buffer -> buffer.put(disk, offset.toInt(), buffer.remaining()) },
            { offset, buffer -> buffer.get(disk, offset.toInt(), buffer.remaining()) }, { _, _, _, error -> throw error })
            .serve(ByteArrayInputStream(frame(2, 5) + data + frame(1, 5) + byteArrayOf(4, 0)), output) { true }
        val result = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4249544c, result.int); assertEquals(4, result.int); assertEquals(4, result.int)
        assertArrayEquals(data, ByteArray(4).also { result.get(it) })
        assertEquals(100L, result.long); assertFalse(result.hasRemaining())
    }

    @Test fun invalidRangeAndTruncatedWriteCannotReachDisk() {
        for (request in listOf(frame(2, -1), frame(2, 98), frame(2, 0, 0), frame(2) + byteArrayOf(1, 2))) {
            val output = ByteArrayOutputStream()
            val protocol = UsbBlockProtocol(100, { _, _ -> fail() }, { _, _ -> fail() }, { _, _, _, _ -> fail() })
            assertThrows(IOException::class.java) { protocol.serve(ByteArrayInputStream(request), output) { true } }
            assertEquals(listOf(0x4249544c), statuses(output))
        }
    }
}
