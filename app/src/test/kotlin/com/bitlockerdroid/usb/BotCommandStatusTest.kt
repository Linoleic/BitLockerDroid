package com.bitlockerdroid.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandStatusTest {
    private fun csw(
        signature: Int = 0x53425355,
        tag: Int = 0x1001,
        residue: Int = 0,
        status: Int = 0
    ): ByteArray = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(signature).putInt(tag).putInt(residue).put(status.toByte()).array()

    @Test fun acceptsOnlyExactMatchingZeroResidueSuccessfulCsw() {
        assertTrue(BotCommandStatus.isSuccessful(csw(), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw().copyOf(12), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw().copyOf(14), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw(signature = 0x12345678), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw(tag = 0x1002), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw(residue = 1), 0x1001))
        assertFalse(BotCommandStatus.isSuccessful(csw(status = 1), 0x1001))
    }
}
