package com.bitlockerdroid.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict success check for a no-data USB Bulk-Only Transport command status wrapper. */
internal object BotCommandStatus {
    private const val CSW_LENGTH = 13
    private const val CSW_SIGNATURE = 0x53425355

    fun isSuccessful(csw: ByteArray, expectedTag: Int): Boolean {
        if (csw.size != CSW_LENGTH) return false
        val status = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (status.int != CSW_SIGNATURE) return false
        if (status.int != expectedTag) return false
        if (status.int != 0) return false // No residue is valid for a no-data command.
        return status.get().toInt() == 0
    }
}
