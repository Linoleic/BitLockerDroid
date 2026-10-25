package com.bitlockerdroid.usb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class UsbBotTransportTest {
    private fun cbw(length: Int = 4, input: Boolean = false, tag: Int = 7) =
        ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355); putInt(tag); putInt(length); put(if (input) 0x80.toByte() else 0)
            put(0); put(10); put(0x2a); position(0)
        }
    private fun csw(tag: Int = 7, residue: Int = 0, status: Int = 0) =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(0x53425355)
            .putInt(tag).putInt(residue).put(status.toByte()).array()

    private inner class Backend {
        var now = 0L
        var clears = 0
        val outputs = mutableListOf<ByteArray>()
        val inputResults = ArrayDeque<Pair<Int, ByteArray>>()
        val timeouts = mutableListOf<Int>()
        var clearOk = true
        var outResult: Int? = null
        val bot = UsbBotTransport({ input, bytes, length, timeout ->
            if (!input) { outputs.add(bytes.copyOf(length)); outResult ?: length }
            else {
                timeouts.add(timeout)
                val (result, payload) = inputResults.removeFirst()
                if (result == -110) now += timeout * 1_000_000L
                if (result > 0) payload.copyInto(bytes, endIndex = result)
                result
            }
        }, { clears++; clearOk }, {}, { now })
        fun write() { bot.bulkOut(cbw()); bot.bulkOut(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))) }
    }

    @Test fun cswTimeoutWaitsForOriginalStatusWithoutReplayingWrite() {
        val b = Backend(); b.write()
        b.inputResults.add(-110 to byteArrayOf()); b.inputResults.add(13 to csw())
        val status = ByteBuffer.allocate(13)
        assertEquals(13, b.bot.bulkIn(status)); assertArrayEquals(csw(), status.array())
        assertEquals(listOf(31, 4), b.outputs.map { it.size })
        assertEquals(listOf(5000, 5000), b.timeouts)
        assertEquals(0, b.clears); assertTrue(b.bot.healthy)
        b.bot.bulkOut(cbw(0, tag = 8)); assertEquals(3, b.outputs.size)
    }

    @Test fun stalledCswClearsOnlyInHaltAndReceivesSameStatus() {
        val b = Backend(); b.write()
        b.inputResults.add(-32 to byteArrayOf()); b.inputResults.add(13 to csw())
        assertEquals(13, b.bot.bulkIn(ByteBuffer.allocate(13)))
        assertEquals(1, b.clears); assertEquals(2, b.outputs.size)
    }

    @Test fun deadlineFailureBlocksLibraryRetriesAndOtherPartitionCommands() {
        val b = Backend(); b.write()
        repeat(6) { b.inputResults.add(-110 to byteArrayOf()) }
        assertThrows(IOException::class.java) { b.bot.bulkIn(ByteBuffer.allocate(13)) }
        assertEquals(30_000_000_000L, b.now)
        repeat(3) { assertThrows(IOException::class.java) { b.bot.bulkOut(cbw()) } }
        assertEquals(2, b.outputs.size); assertFalse(b.bot.healthy)
    }

    @Test fun repeatedStallAndFailedHaltClearCannotReturnSuccess() {
        for (clearOk in listOf(false, true)) {
            val b = Backend(); b.clearOk = clearOk; b.write()
            b.inputResults.add(-32 to byteArrayOf()); b.inputResults.add(-32 to byteArrayOf())
            assertThrows(IOException::class.java) { b.bot.bulkIn(ByteBuffer.allocate(13)) }
            assertEquals(1, b.clears); assertFalse(b.bot.healthy)
        }
    }

    @Test fun dataFailureIsNotRetriedAndDoesNotAdvanceCallerBuffer() {
        val b = Backend(); b.bot.bulkOut(cbw()); b.outResult = -110
        val data = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        assertThrows(IOException::class.java) { b.bot.bulkOut(data) }
        assertEquals(0, data.position()); assertFalse(b.bot.healthy)
        assertThrows(IOException::class.java) { b.bot.bulkOut(data) }
        assertEquals(2, b.outputs.size)
    }

    @Test fun dataIsSplitAt16KiBWhileCswAndCommandRemainWhole() {
        val b = Backend(); b.bot.bulkOut(cbw(32768))
        val data = ByteBuffer.allocate(32768)
        assertEquals(16384, b.bot.bulkOut(data)); assertEquals(16384, b.bot.bulkOut(data))
        b.inputResults.add(13 to csw()); assertEquals(13, b.bot.bulkIn(ByteBuffer.allocate(13)))
        assertEquals(listOf(31, 16384, 16384), b.outputs.map { it.size })
    }

    @Test fun invalidStatusTagShortSuccessAndPhaseErrorFailClosed() {
        for (response in listOf(csw(tag = 8), csw(residue = 1), csw(status = 2), ByteArray(12))) {
            val b = Backend(); b.write(); b.inputResults.add(response.size to response)
            assertThrows(IOException::class.java) { b.bot.bulkIn(ByteBuffer.allocate(13)) }
            assertFalse(b.bot.healthy)
        }
    }

    @Test fun earlyFailedVendorProbeStatusPreservesSmartFraming() {
        val b = Backend(); b.bot.bulkOut(cbw(512, input = true))
        b.inputResults.add(13 to csw(residue = 512, status = 1))
        assertEquals(13, b.bot.bulkIn(ByteBuffer.allocate(512))); assertTrue(b.bot.healthy)
        b.bot.bulkOut(cbw(0, tag = 8))
        b.inputResults.add(13 to csw(tag = 8)); b.bot.bulkIn(ByteBuffer.allocate(13))
    }

    @Test fun newCommandWithoutCswCannotReachDevice() {
        val b = Backend(); b.write()
        assertThrows(IOException::class.java) { b.bot.bulkOut(cbw()) }
        assertEquals(2, b.outputs.size)
    }

    @Test fun shortInputRequiresExactResidueBeforeAnotherCommand() {
        for (residue in listOf(10, 9, 0)) {
            val b = Backend(); b.bot.bulkOut(cbw(18, input = true))
            b.inputResults.add(8 to ByteArray(8)); b.inputResults.add(13 to csw(residue = residue))
            assertEquals(8, b.bot.bulkIn(ByteBuffer.allocate(18)))
            if (residue == 10) {
                assertEquals(13, b.bot.bulkIn(ByteBuffer.allocate(13))); assertTrue(b.bot.healthy)
                b.bot.bulkOut(cbw(0, tag = 8))
            } else {
                assertThrows(IOException::class.java) { b.bot.bulkIn(ByteBuffer.allocate(13)) }
                assertFalse(b.bot.healthy)
            }
        }
    }
}
