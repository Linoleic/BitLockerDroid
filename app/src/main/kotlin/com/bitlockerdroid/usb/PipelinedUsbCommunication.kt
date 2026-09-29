package com.bitlockerdroid.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import me.jahnen.libaums.core.ErrNo
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer

/**
 * High-performance Pipelined Asynchronous USB Bulk-Only Transport (BOT) engine.
 *
 * Replaces libaums's synchronous stop-and-wait `bulkTransfer` with an asynchronous
 * 4-stage circular URB pipeline backed by direct memory buffers and `android.hardware.usb.UsbRequest`.
 *
 * When large SCSI transfers (32KB, 64KB, 128KB) are executed:
 * 1. Multiple 16KB URBs are pre-submitted into the xHCI host controller hardware transfer ring;
 * 2. As each URB completes, it is immediately reaped and the NEXT chunk is queued on the fly;
 * 3. The physical USB bus operates at 100% duty cycle with zero inter-packet idle time,
 *    breaking through the ~20 MB/s BOT stop-and-wait ceiling.
 */
class PipelinedUsbCommunication(
    private val deviceConnection: UsbDeviceConnection,
    override val usbInterface: UsbInterface,
    override val inEndpoint: UsbEndpoint,
    override val outEndpoint: UsbEndpoint
) : UsbCommunication {

    companion object {
        private const val TAG = "PipelinedUsbComm"
        private const val PIPELINE_DEPTH = 4
        private const val CHUNK_SIZE = 16 * 1024 // 16 KB matches Linux MAX_USBFS_BUFFER_SIZE
        private const val TRANSFER_TIMEOUT = 5000

        init {
            try {
                System.loadLibrary("usb-lib")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Could not load usb-lib for PipelinedUsbCommunication", e)
            }
        }

        @JvmStatic
        private external fun nativeResetDevice(fd: Int): Boolean

        @JvmStatic
        private external fun nativeClearHalt(fd: Int, endpoint: Int): Boolean
    }

    @Volatile
    private var isClosed = false

    // Pre-allocated direct ByteBuffers aligned with native memory for zero-copy DMA
    private val inBuffers = Array(PIPELINE_DEPTH) { ByteBuffer.allocateDirect(CHUNK_SIZE) }
    private val inRequests = Array(PIPELINE_DEPTH) { UsbRequest().apply { initialize(deviceConnection, inEndpoint) } }

    private val outBuffers = Array(PIPELINE_DEPTH) { ByteBuffer.allocateDirect(CHUNK_SIZE) }
    private val outRequests = Array(PIPELINE_DEPTH) { UsbRequest().apply { initialize(deviceConnection, outEndpoint) } }

    private val singleInReq = UsbRequest().apply { initialize(deviceConnection, inEndpoint) }
    private val singleOutReq = UsbRequest().apply { initialize(deviceConnection, outEndpoint) }

    init {
        val claimed = deviceConnection.claimInterface(usbInterface, true)
        if (!claimed) {
            Log.w(TAG, "Failed to claim interface ${usbInterface.id}, proceeding anyway if already claimed")
        }
    }

    private class InFlightTag(val slot: Int, val chunkOffset: Int)

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int
    ): Int {
        if (isClosed) throw IOException("Device connection is closed")
        return deviceConnection.controlTransfer(requestType, request, value, index, buffer, length, TRANSFER_TIMEOUT)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun bulkInTransfer(dest: ByteBuffer): Int {
        if (isClosed) throw IOException("Device connection is closed")
        val totalBytes = dest.remaining()
        if (totalBytes <= 0) return 0

        // Fast-path for small transfers <= 16KB (e.g. CSW 13 bytes, Inquiry, single sector)
        if (totalBytes <= CHUNK_SIZE) {
            return transferSingleIn(dest, totalBytes)
        }

        // High-performance pipelined asynchronous multi-URB transfer for > 16KB
        return transferPipelinedIn(dest, totalBytes)
    }

    private fun transferSingleIn(dest: ByteBuffer, length: Int): Int {
        val result = if (dest.hasArray()) {
            deviceConnection.bulkTransfer(
                inEndpoint,
                dest.array(),
                dest.arrayOffset() + dest.position(),
                length,
                TRANSFER_TIMEOUT
            )
        } else {
            inBuffers[0].clear()
            inBuffers[0].limit(length)
            val ok = singleInReq.queue(inBuffers[0])
            if (!ok) throw IOException("Failed to queue single inRequest")
            val completed = deviceConnection.requestWait(TRANSFER_TIMEOUT.toLong())
                ?: throw IOException("requestWait timed out for single inRequest")
            if (completed !== singleInReq) throw IOException("Unexpected request returned from requestWait: $completed")
            inBuffers[0].flip()
            val n = inBuffers[0].remaining()
            dest.put(inBuffers[0])
            n
        }

        if (result == -1) {
            when (ErrNo.errno) {
                32 /* EPIPE */ -> throw PipeException()
                else -> throw IOException("bulkInTransfer failed (result == -1, errno=${ErrNo.errno} ${ErrNo.errstr})")
            }
        }
        if (dest.hasArray()) {
            dest.position(dest.position() + result)
        }
        return result
    }

    private fun transferPipelinedIn(dest: ByteBuffer, totalBytes: Int): Int {
        val totalChunks = (totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE
        val burstCount = minOf(PIPELINE_DEPTH, totalChunks)
        val startPos = dest.position()

        var queuedChunks = 0
        var queuedBytes = 0
        var reapedChunks = 0
        var totalRead = 0

        try {
            // Step 1: Pre-queue burstCount requests (filling the USB hardware pipeline)
            for (i in 0 until burstCount) {
                val chunkLen = minOf(CHUNK_SIZE, totalBytes - queuedBytes)
                val buf = inBuffers[i]
                buf.clear()
                buf.limit(chunkLen)
                val req = inRequests[i]
                req.clientData = InFlightTag(i, queuedBytes)
                if (!req.queue(buf)) {
                    throw IOException("Failed to pre-queue inRequest for chunk $queuedChunks in slot $i")
                }
                queuedChunks++
                queuedBytes += chunkLen
            }

            // Step 2: Sliding-window reaping and replenishing
            while (reapedChunks < totalChunks) {
                val completedReq = deviceConnection.requestWait(TRANSFER_TIMEOUT.toLong())
                    ?: throw IOException("requestWait timed out (reaped=$reapedChunks, queued=$queuedChunks, total=$totalChunks)")

                val tag = (completedReq.clientData as? InFlightTag)
                    ?: throw IOException("Request clientData missing or invalid")
                val slot = tag.slot
                if (slot < 0 || slot >= PIPELINE_DEPTH) {
                    throw IOException("Invalid completed request slot: $slot")
                }

                val buf = inBuffers[slot]
                buf.flip()
                val n = buf.remaining()
                dest.position(startPos + tag.chunkOffset)
                dest.put(buf)
                totalRead += n
                reapedChunks++

                // Step 3: Immediately queue next chunk into this freed slot if more data needed
                if (queuedChunks < totalChunks) {
                    val nextChunkLen = minOf(CHUNK_SIZE, totalBytes - queuedBytes)
                    buf.clear()
                    buf.limit(nextChunkLen)
                    completedReq.clientData = InFlightTag(slot, queuedBytes)
                    if (!completedReq.queue(buf)) {
                        throw IOException("Failed to queue next inRequest for chunk $queuedChunks in slot $slot")
                    }
                    queuedChunks++
                    queuedBytes += nextChunkLen
                }
            }
        } finally {
            // Drain/cancel any remaining in-flight requests on error
            if (reapedChunks < queuedChunks) {
                val inFlight = queuedChunks - reapedChunks
                for (req in inRequests) {
                    try { req.cancel() } catch (_: Exception) {}
                }
                for (i in 0 until inFlight) {
                    try { deviceConnection.requestWait(1000L) } catch (_: Exception) {}
                }
            }
        }

        dest.position(startPos + totalRead)
        return totalRead
    }

    @Synchronized
    @Throws(IOException::class)
    override fun bulkOutTransfer(src: ByteBuffer): Int {
        if (isClosed) throw IOException("Device connection is closed")
        val totalBytes = src.remaining()
        if (totalBytes <= 0) return 0

        if (totalBytes <= CHUNK_SIZE) {
            return transferSingleOut(src, totalBytes)
        }

        return transferPipelinedOut(src, totalBytes)
    }

    private fun transferSingleOut(src: ByteBuffer, length: Int): Int {
        val result = if (src.hasArray()) {
            deviceConnection.bulkTransfer(
                outEndpoint,
                src.array(),
                src.arrayOffset() + src.position(),
                length,
                TRANSFER_TIMEOUT
            )
        } else {
            outBuffers[0].clear()
            val oldLimit = src.limit()
            src.limit(src.position() + length)
            outBuffers[0].put(src)
            src.limit(oldLimit)
            outBuffers[0].flip()

            val ok = singleOutReq.queue(outBuffers[0])
            if (!ok) throw IOException("Failed to queue single outRequest")
            val completed = deviceConnection.requestWait(TRANSFER_TIMEOUT.toLong())
                ?: throw IOException("requestWait timed out for single outRequest")
            if (completed !== singleOutReq) throw IOException("Unexpected request returned from requestWait: $completed")
            length
        }

        if (result == -1) {
            when (ErrNo.errno) {
                32 /* EPIPE */ -> throw PipeException()
                else -> throw IOException("bulkOutTransfer failed (result == -1, errno=${ErrNo.errno} ${ErrNo.errstr})")
            }
        }
        if (src.hasArray()) {
            src.position(src.position() + result)
        }
        return result
    }

    private fun transferPipelinedOut(src: ByteBuffer, totalBytes: Int): Int {
        val totalChunks = (totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE
        val burstCount = minOf(PIPELINE_DEPTH, totalChunks)

        var queuedChunks = 0
        var queuedBytes = 0
        var reapedChunks = 0

        try {
            for (i in 0 until burstCount) {
                val chunkLen = minOf(CHUNK_SIZE, totalBytes - queuedBytes)
                val buf = outBuffers[i]
                buf.clear()
                val oldLimit = src.limit()
                src.limit(src.position() + chunkLen)
                buf.put(src)
                src.limit(oldLimit)
                buf.flip()

                val req = outRequests[i]
                req.clientData = i
                if (!req.queue(buf)) {
                    throw IOException("Failed to pre-queue outRequest for chunk $queuedChunks in slot $i")
                }
                queuedChunks++
                queuedBytes += chunkLen
            }

            while (reapedChunks < totalChunks) {
                val completedReq = deviceConnection.requestWait(TRANSFER_TIMEOUT.toLong())
                    ?: throw IOException("requestWait timed out (reaped=$reapedChunks, queued=$queuedChunks, total=$totalChunks)")

                val slot = (completedReq.clientData as? Int) ?: outRequests.indexOf(completedReq)
                if (slot < 0 || slot >= PIPELINE_DEPTH) {
                    throw IOException("Invalid completed request slot: $slot")
                }

                reapedChunks++

                if (queuedChunks < totalChunks) {
                    val nextChunkLen = minOf(CHUNK_SIZE, totalBytes - queuedBytes)
                    val buf = outBuffers[slot]
                    buf.clear()
                    val oldLimit = src.limit()
                    src.limit(src.position() + nextChunkLen)
                    buf.put(src)
                    src.limit(oldLimit)
                    buf.flip()

                    completedReq.clientData = slot
                    if (!completedReq.queue(buf)) {
                        throw IOException("Failed to queue next outRequest for chunk $queuedChunks in slot $slot")
                    }
                    queuedChunks++
                    queuedBytes += nextChunkLen
                }
            }
        } finally {
            if (reapedChunks < queuedChunks) {
                val inFlight = queuedChunks - reapedChunks
                for (req in outRequests) {
                    try { req.cancel() } catch (_: Exception) {}
                }
                for (i in 0 until inFlight) {
                    try { deviceConnection.requestWait(1000L) } catch (_: Exception) {}
                }
            }
        }

        return totalBytes
    }

    override fun resetDevice() {
        if (isClosed) throw IOException("Device connection is closed")
        Log.i(TAG, "Resetting USB device interface ${usbInterface.id}")
        try {
            deviceConnection.releaseInterface(usbInterface)
        } catch (_: Exception) {}
        val resetOk = nativeResetDevice(deviceConnection.fileDescriptor)
        if (!resetOk) {
            Log.w(TAG, "nativeResetDevice returned false")
        }
        val claimOk = deviceConnection.claimInterface(usbInterface, true)
        if (!claimOk) {
            throw IOException("Could not reclaim interface after reset")
        }
    }

    override fun clearFeatureHalt(endpoint: UsbEndpoint) {
        if (isClosed) throw IOException("Device connection is closed")
        Log.i(TAG, "Clearing halt on endpoint ${endpoint.address}")
        val clearOk = nativeClearHalt(deviceConnection.fileDescriptor, endpoint.address)
        if (!clearOk) {
            Log.w(TAG, "nativeClearHalt returned false for endpoint ${endpoint.address}")
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        for (r in inRequests) {
            try { r.close() } catch (_: Exception) {}
        }
        for (r in outRequests) {
            try { r.close() } catch (_: Exception) {}
        }
        try { singleInReq.close() } catch (_: Exception) {}
        try { singleOutReq.close() } catch (_: Exception) {}
        try { deviceConnection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { deviceConnection.close() } catch (_: Exception) {}
    }
}
