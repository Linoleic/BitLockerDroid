package com.bitlockerdroid.usb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Serial BOT framing. A missing status never permits a new CBW or replayed write. */
internal class UsbBotTransport(
    private val transfer: (input: Boolean, bytes: ByteArray, length: Int, timeoutMs: Int) -> Int,
    private val clearInHalt: () -> Boolean,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private enum class Phase { CBW, DATA_IN, DATA_OUT, CSW }
    private var phase = Phase.CBW
    private var tag = 0
    private var opcode = 0
    private var expected = 0L
    private var remaining = 0L
    private var dataInCommand = false
    @Volatile private var failure: IOException? = null
    val healthy: Boolean get() = failure == null
    private val input = ByteArray(16 * 1024)
    private val output = ByteArray(16 * 1024)

    private fun fail(message: String): Nothing {
        val error = IOException("BOT $phase tag=$tag opcode=0x${opcode.toString(16)}: $message")
        failure = error
        log(error.message.orEmpty())
        throw error
    }

    private fun checkHealthy() {
        failure?.let { throw IOException("USB BOT channel failed; reconnect required", it) }
    }

    fun bulkOut(src: ByteBuffer): Int {
        checkHealthy()
        if (!src.hasRemaining()) return 0
        if (phase != Phase.CBW && phase != Phase.DATA_OUT) fail("new OUT before previous status")
        val cbw = phase == Phase.CBW
        if (cbw && src.remaining() != 31) fail("CBW must contain 31 bytes")
        val length = if (cbw) 31 else minOf(src.remaining().toLong(), remaining, output.size.toLong()).toInt()
        src.duplicate().get(output, 0, length)
        if (cbw) {
            val header = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
            if (header.int != 0x43425355) fail("invalid CBW signature")
            tag = header.int
            expected = header.int.toLong() and 0xffffffffL
            opcode = output[15].toInt() and 255
            dataInCommand = (output[12].toInt() and 128) != 0
        }
        val result = transfer(false, output, length, 5000)
        if (result <= 0 || result > length || (cbw && result != 31)) fail("OUT requested=$length result=$result")
        src.position(src.position() + result)
        if (cbw) {
            remaining = expected
            phase = if (remaining == 0L) Phase.CSW else if ((output[12].toInt() and 128) != 0) Phase.DATA_IN else Phase.DATA_OUT
        } else {
            remaining -= result
            if (remaining == 0L) phase = Phase.CSW
        }
        return result
    }

    fun bulkIn(dest: ByteBuffer): Int {
        checkHealthy()
        if (!dest.hasRemaining()) return 0
        if (phase != Phase.DATA_IN && phase != Phase.CSW) fail("unexpected IN")
        val status = phase == Phase.CSW
        if (status && dest.remaining() != 13) fail("CSW buffer must contain 13 bytes")
        val length = if (status) 13 else minOf(dest.remaining().toLong(), remaining, input.size.toLong()).toInt()
        val result = if (status) readStatus() else transfer(true, input, length, 5000)
        if (result <= 0 || result > length) fail("IN requested=$length result=$result")
        val header = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        // Failed vendor probes may return an early CSW instead of data.
        if (status || (result == 13 && header.getInt(0) == 0x53425355)) {
            if (result != 13 || header.int != 0x53425355 || header.int != tag) fail("invalid CSW framing/tag")
            val residue = header.int.toLong() and 0xffffffffL
            val commandStatus = header.get().toInt() and 255
            if (residue > expected || commandStatus !in 0..1 ||
                (commandStatus == 0 && (residue != remaining || (!dataInCommand && remaining != 0L)))) fail("invalid CSW status/residue")
            phase = Phase.CBW
        } else {
            remaining -= result
            // A short IN packet ends a variable-length response (e.g. REQUEST SENSE).
            // The following CSW must account for every missing byte through residue.
            if (remaining == 0L || result < length) phase = Phase.CSW
        }
        dest.put(input, 0, result)
        return result
    }

    private fun readStatus(): Int {
        val deadline = nanoTime() + 30_000_000_000L
        var clearedHalt = false
        // Bounded even if a backend reports an immediate timeout without waiting.
        repeat(7) {
            val leftMs = ((deadline - nanoTime() + 999_999L) / 1_000_000L).coerceAtMost(5000).toInt()
            if (leftMs <= 0) fail("CSW completion deadline exceeded")
            val result = transfer(true, input, 13, leftMs)
            when {
                result > 0 -> return result
                result == -110 -> log("BOT CSW timeout; waiting for original tag=$tag opcode=0x${opcode.toString(16)}")
                result == -32 && !clearedHalt -> {
                    // USB MSC BOT 6.7: clear Bulk-In halt, then receive the SAME CSW.
                    if (!clearInHalt()) fail("could not clear CSW IN halt")
                    clearedHalt = true
                    log("BOT CSW IN halt cleared; receiving original tag=$tag")
                }
                else -> fail("CSW receive result=$result")
            }
        }
        fail("CSW retry budget exhausted")
    }

    /** Only after an explicit BOT class reset followed by BOTH successful halt clears. */
    fun resetAfterBotRecovery() {
        phase = Phase.CBW
        remaining = 0
        expected = 0
        failure = null
    }
}
