package com.bitlockerdroid.usb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fd: protocol acknowledges completed block I/O, never queued work. */
internal class UsbBlockProtocol(
    private val sizeBytes: Long,
    private val read: (Long, ByteBuffer) -> Unit,
    private val write: (Long, ByteBuffer) -> Unit,
    private val onFailure: (Int, Long, Int, Exception) -> Unit
) {
    fun serve(input: InputStream, output: OutputStream, running: () -> Boolean) {
        val source = DataInputStream(input)
        val header = ByteArray(12)
        val status = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        var buffer = ByteBuffer.allocate(256 * 1024)
        var failed = false

        fun reply(value: Int) {
            status.clear()
            status.putInt(value)
            output.write(status.array(), 0, 4)
            output.flush()
        }

        reply(0x4249544c) // BITL handshake
        while (running()) {
            val command = source.read()
            when (command) {
                -1, 0 -> return
                1, 2 -> {
                    source.readFully(header)
                    val request = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val offset = request.long
                    val length = request.int
                    if (offset < 0 || length <= 0 || offset > sizeBytes || length.toLong() > sizeBytes - offset) {
                        throw IOException("Invalid USB block request: command=$command offset=$offset length=$length")
                    }
                    if (buffer.capacity() < length) buffer = ByteBuffer.allocate(length)
                    buffer.clear()
                    buffer.limit(length)
                    // Consume the complete frame even when rejecting a previously failed channel.
                    if (command == 2) source.readFully(buffer.array(), 0, length)
                    if (failed) {
                        reply(-5)
                        continue
                    }
                    try {
                        if (command == 1) read(offset, buffer) else write(offset, buffer)
                        if (buffer.hasRemaining()) throw EOFException("Incomplete USB block I/O")
                    } catch (error: Exception) {
                        failed = true
                        onFailure(command, offset, length, error)
                        reply(-5) // sticky EIO: no later read/write/sync can hide this failure
                        continue
                    }
                    reply(length)
                    if (command == 1) {
                        output.write(buffer.array(), 0, length)
                        output.flush()
                    }
                }
                // All writes have completed before the next request can be accepted.
                // This is a protocol barrier; filesystem/device flushing remains the caller's job.
                3 -> reply(if (failed) -5 else 0)
                4 -> {
                    if (failed) throw IOException("Size requested on failed USB block channel")
                    status.clear()
                    status.putLong(sizeBytes)
                    output.write(status.array(), 0, 8)
                    output.flush()
                }
                else -> throw IOException("Unknown USB block command $command")
            }
        }
    }
}
