package com.bitlockerdroid.usb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ScsiCacheSyncTest {
    private fun sense(key: Int = 5, asc: Int = 0x20) = ByteArray(18).apply {
        this[0] = 0x70; this[2] = key.toByte(); this[7] = 10; this[12] = asc.toByte()
    }
    private fun page(wce: Boolean = false) = ByteArray(24).apply {
        this[0] = 23; this[4] = 8; this[5] = 18; this[6] = if (wce) 4 else 0
    }
    private data class Reply(val data: ByteArray = byteArrayOf(), val status: Int = 0, val residueAdjustment: Int = 0)
    private class Device(private val respond: (Int, Int) -> Reply) {
        val opcodes = mutableListOf<Int>()
        private val inputs = ArrayDeque<ByteArray>()
        val transport = UsbBotTransport({ input, bytes, size, _ ->
            if (input) {
                val reply = inputs.removeFirst(); check(reply.size <= size)
                reply.copyInto(bytes); reply.size
            } else {
                check(size == 31)
                val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                check(h.int == 0x43425355); val tag = h.int; val length = h.int
                val opcode = bytes[15].toInt() and 255; opcodes.add(opcode)
                val reply = respond(opcode, length)
                if (reply.data.isNotEmpty()) inputs.add(reply.data)
                inputs.add(ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x53425355).putInt(tag).putInt(length - reply.data.size + reply.residueAdjustment)
                    .put(reply.status.toByte()).array())
                size
            }
        }, { true }, {})
        fun sync() = ScsiCacheSync(transport::bulkOut, transport::bulkIn, {}).synchronize()
    }

    @Test fun supportedSyncDoesNotProbeOrAlterCachingPolicy() {
        val d = Device { op, length -> assertEquals(0x35, op); assertEquals(0, length); Reply() }
        assertTrue(d.sync()); assertEquals(listOf(0x35), d.opcodes)
    }

    @Test fun explicitUnsupportedSyncAndCurrentWriteThroughPageCanComplete() {
        val d = Device { op, length -> when (op) {
            0x35 -> Reply(status = 1)
            3 -> Reply(sense())
            0x1a -> Reply(page().copyOf(length))
            else -> error("Unexpected opcode $op")
        } }
        assertTrue(d.sync()); assertTrue(d.transport.healthy)
        assertEquals(listOf(0x35, 3, 0x1a, 0x1a), d.opcodes)
    }

    @Test fun enabledOrMalformedCachingPageCannotAuthorizeSkippingFlush() {
        val invalid = listOf(page(true), page().copyOf(23), page().also { it[4] = 0x48 },
            page().also { it[5] = 1 }, page().also { it[3] = 30 }, page().also { it[0] = 24 })
        invalid.forEach { assertFalse(ScsiCacheSync.writeCacheDisabled(it, false)) }
        val d = Device { op, length -> when (op) {
            0x35 -> Reply(status = 1)
            3 -> Reply(sense())
            0x1a -> Reply(page(true).copyOf(length))
            else -> error("Unexpected command")
        } }
        assertFalse(d.sync())
    }

    @Test fun mediaHardwareDeferredOrMalformedSenseCannotAuthorizeFallback() {
        val invalid = listOf(sense(3), sense(4), sense(5, 0x26), sense().copyOf(13),
            sense().also { it[0] = 0x71 }, sense().also { it[7] = 20 }, sense().also { it[13] = 1 })
        invalid.forEach { s ->
            assertFalse(ScsiCacheSync.unsupported(s))
            val d = Device { op, _ -> when (op) { 0x35 -> Reply(status = 1); 3 -> Reply(s); else -> error("Unsafe fallback") } }
            assertFalse(d.sync()); assertEquals(listOf(0x35, 3), d.opcodes)
        }
    }

    @Test fun shortDescriptorSenseHasValidatedResidueAndSupportsFallback() {
        val descriptor = byteArrayOf(0x72, 5, 0x20, 0, 0, 0, 0, 0)
        val d = Device { op, length -> when (op) {
            0x35 -> Reply(status = 1); 3 -> Reply(descriptor)
            0x1a -> Reply(page().copyOf(length)); else -> error("Unexpected command")
        } }
        assertTrue(d.sync()); assertTrue(d.transport.healthy)
    }

    @Test fun badResidueCannotBeMistakenForUnsupportedCommand() {
        val d = Device { op, _ -> if (op == 0x35) Reply(status = 1) else Reply(sense(), residueAdjustment = 1) }
        assertThrows(IOException::class.java) { d.sync() }
        assertEquals(listOf(0x35, 3), d.opcodes); assertFalse(d.transport.healthy)
    }

    @Test fun modeSenseTenOnlyFollowsExplicitUnsupportedSix() {
        val ten = ByteArray(28).apply { this[1] = 26; this[8] = 8; this[9] = 18 }
        val d = Device { op, length -> when (op) {
            0x35 -> Reply(status = 1)
            0x1a -> Reply(ByteArray(length), status = 1)
            3 -> Reply(sense())
            0x5a -> Reply(ten.copyOf(length))
            else -> error("Unexpected command")
        } }
        assertTrue(d.sync()); assertEquals(listOf(0x35, 3, 0x1a, 3, 0x5a, 0x5a), d.opcodes)
        assertFalse(ScsiCacheSync.writeCacheDisabled(ten.copyOf(27), true))
    }

    @Test fun unavailableCachingPageAndTransferErrorsRemainFailures() {
        val d = Device { op, length -> when (op) {
            0x35 -> Reply(status = 1)
            0x1a, 0x5a -> Reply(ByteArray(length), status = 1)
            3 -> Reply(sense())
            else -> error("Unexpected command")
        } }
        assertThrows(IOException::class.java) { d.sync() }
        val broken = ScsiCacheSync({ throw IOException("USB detached") }, { error("No status") }, {})
        assertThrows(IOException::class.java) { broken.synchronize() }
    }
}
