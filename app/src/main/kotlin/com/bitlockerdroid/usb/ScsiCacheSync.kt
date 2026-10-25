package com.bitlockerdroid.usb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Used only under the device lock, after all volume workers have closed. */
internal class ScsiCacheSync(
    private val bulkOut: (ByteBuffer) -> Int,
    private val bulkIn: (ByteBuffer) -> Int,
    private val log: (String) -> Unit
) {
    private var nextTag = 0x1200
    private data class Response(val status: Int, val data: ByteArray)

    fun synchronize(): Boolean {
        val sync = execute(byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0)
        if (sync.status == 0) return true
        if (!unsupported(requestSense())) {
            log("SCSI cache sync failed; sense does not confirm an unsupported command")
            return false
        }
        val page = modeSense(false) ?: modeSense(true) ?: return false
        val disabled = writeCacheDisabled(page.first, page.second)
        log(if (disabled) "SCSI cache sync unsupported; current caching page confirms WCE=0"
            else "SCSI cache sync unsupported; write cache is enabled or unconfirmed")
        return disabled
    }

    private fun requestSense(): ByteArray = execute(byteArrayOf(3, 0, 0, 0, 18, 0), 18)
        .takeIf { it.status == 0 }?.data ?: byteArrayOf()

    private fun modeSense(ten: Boolean): Pair<ByteArray, Boolean>? {
        fun command(length: Int): Response = execute(if (ten)
            byteArrayOf(0x5a, 8, 8, 0, 0, 0, 0, (length ushr 8).toByte(), length.toByte(), 0)
        else byteArrayOf(0x1a, 8, 8, 0, length.toByte(), 0), length)
        val headerSize = if (ten) 8 else 4
        val header = command(headerSize)
        if (header.status != 0) {
            // Only an explicit unsupported response allows trying the other format.
            if (!ten && unsupported(requestSense())) return null
            throw IOException("SCSI MODE SENSE failed; caching state is unconfirmed")
        }
        if (header.data.size != headerSize) throw IOException("Truncated MODE SENSE header")
        val length = if (ten) (u(header.data[0]) shl 8) + u(header.data[1]) + 2 else u(header.data[0]) + 1
        if (length !in (headerSize + 3)..255) throw IOException("Invalid MODE SENSE length")
        val page = command(length)
        if (page.status != 0 || page.data.size != length) throw IOException("Incomplete caching page")
        return page.data to ten
    }

    private fun execute(cdb: ByteArray, allocation: Int): Response {
        val tag = ++nextTag
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355); putInt(tag); putInt(allocation)
            put(if (allocation == 0) 0 else 0x80.toByte()); put(0); put(cdb.size.toByte()); put(cdb)
            position(31); flip()
        }
        if (bulkOut(cbw) != 31 || cbw.position() != 31) throw IOException("Incomplete cache command CBW")
        var data = byteArrayOf()
        var status: ByteArray? = null
        if (allocation > 0) {
            val buffer = ByteBuffer.allocate(allocation)
            val read = bulkIn(buffer)
            if (read <= 0 || read != buffer.position()) throw IOException("Invalid cache command data transfer")
            val bytes = buffer.array().copyOf(read)
            if (read == 13 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == 0x53425355) status = bytes
            else data = bytes
        }
        if (status == null) {
            val csw = ByteBuffer.allocate(13)
            if (bulkIn(csw) != 13 || csw.position() != 13) throw IOException("Incomplete cache command CSW")
            status = csw.array()
        }
        val csw = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN)
        if (csw.int != 0x53425355 || csw.int != tag) throw IOException("Invalid cache command CSW signature/tag")
        val residue = csw.int.toLong() and 0xffffffffL
        val result = u(csw.get())
        if (result !in 0..1 || residue != (allocation - data.size).toLong())
            throw IOException("Invalid cache command CSW status/residue")
        return Response(result, data)
    }

    companion object {
        private fun u(value: Byte) = value.toInt() and 255

        internal fun unsupported(sense: ByteArray): Boolean {
            if (sense.size < 8 || 8 + u(sense[7]) > sense.size) return false
            val code = u(sense[0]) and 0x7f
            val (key, asc, ascq) = when (code) {
                0x70 -> {
                    if (sense.size < 14 || u(sense[7]) < 6) return false
                    Triple(u(sense[2]) and 15, u(sense[12]), u(sense[13]))
                }
                0x72 -> Triple(u(sense[1]) and 15, u(sense[2]), u(sense[3]))
                else -> return false // Deferred or malformed sense cannot authorize a fallback.
            }
            return key == 5 && asc in listOf(0x20, 0x24) && ascq == 0
        }

        internal fun writeCacheDisabled(data: ByteArray, ten: Boolean): Boolean {
            val header = if (ten) 8 else 4
            if (data.size < header) return false
            val length = if (ten) (u(data[0]) shl 8) + u(data[1]) + 2 else u(data[0]) + 1
            if (length != data.size) return false
            val descriptors = if (ten) (u(data[6]) shl 8) + u(data[7]) else u(data[3])
            val offset = header + descriptors
            if (offset + 3 > length || u(data[offset]) and 0x7f != 8) return false
            val pageLength = u(data[offset + 1])
            // A normal current caching page, including its complete defined payload.
            if (pageLength < 0x12 || offset + 2 + pageLength != length) return false
            return u(data[offset + 2]) and 4 == 0
        }
    }
}
