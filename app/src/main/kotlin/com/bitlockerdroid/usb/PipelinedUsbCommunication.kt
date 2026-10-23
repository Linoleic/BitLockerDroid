package com.bitlockerdroid.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import me.jahnen.libaums.core.ErrNo
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer

/** Synchronous USB Bulk-Only Transport (BOT) communication, capped at 16KB per transfer. */
class PipelinedUsbCommunication(
    private val deviceConnection: UsbDeviceConnection,
    override val usbInterface: UsbInterface,
    override val inEndpoint: UsbEndpoint,
    override val outEndpoint: UsbEndpoint
) : UsbCommunication {

    companion object {
        private const val TAG = "PipelinedUsbComm"
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

    private val inTransferBuffer = ByteArray(CHUNK_SIZE)
    private val outTransferBuffer = ByteArray(CHUNK_SIZE)

    init {
        val claimed = deviceConnection.claimInterface(usbInterface, true)
        if (!claimed) {
            throw IOException("Could not claim USB interface ${usbInterface.id}")
        }
        // Claiming uses only the interface ID. Select the BOT alternate explicitly,
        // especially after the kernel has used a UAS alternate in root mode.
        if (!deviceConnection.setInterface(usbInterface)) {
            deviceConnection.releaseInterface(usbInterface)
            throw IOException("Could not select USB alternate ${usbInterface.alternateSetting}")
        }
        Log.i(TAG, "Selected BOT interface ${usbInterface.id} alternate ${usbInterface.alternateSetting}")
    }

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
        if (!dest.hasRemaining()) return 0
        return transferSingleIn(dest, minOf(dest.remaining(), CHUNK_SIZE))
    }

    private fun transferSingleIn(dest: ByteBuffer, length: Int): Int {
        val requested = minOf(length, CHUNK_SIZE)
        val started = System.nanoTime()
        val result = deviceConnection.bulkTransfer(
            inEndpoint,
            inTransferBuffer,
            0,
            requested,
            TRANSFER_TIMEOUT
        )
        val transferred = validateTransferResult("bulk IN", inEndpoint.address, result, requested, started)
        dest.put(inTransferBuffer, 0, transferred)
        return transferred
    }

    @Synchronized
    @Throws(IOException::class)
    override fun bulkOutTransfer(src: ByteBuffer): Int {
        if (isClosed) throw IOException("Device connection is closed")
        if (!src.hasRemaining()) return 0
        return transferSingleOut(src, minOf(src.remaining(), CHUNK_SIZE))
    }

    private fun transferSingleOut(src: ByteBuffer, length: Int): Int {
        val requested = minOf(length, CHUNK_SIZE)
        src.duplicate().get(outTransferBuffer, 0, requested)
        val started = System.nanoTime()
        val result = deviceConnection.bulkTransfer(
            outEndpoint,
            outTransferBuffer,
            0,
            requested,
            TRANSFER_TIMEOUT
        )
        val transferred = validateTransferResult("bulk OUT", outEndpoint.address, result, requested, started)
        src.position(src.position() + transferred)
        return transferred
    }

    private fun validateTransferResult(direction: String, endpoint: Int, result: Int, requested: Int, started: Long): Int {
        if (result <= 0 || result > requested) {
            Log.e(TAG, "$direction failed: endpoint=$endpoint requested=$requested result=$result " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000} timeoutMs=$TRANSFER_TIMEOUT " +
                "errno=${ErrNo.errno} ${ErrNo.errstr}")
        }
        if (result < 0) {
            when (ErrNo.errno) {
                32 /* EPIPE */ -> throw PipeException()
                else -> throw IOException(
                    "$direction bulkTransfer failed (result=$result, errno=${ErrNo.errno} ${ErrNo.errstr})"
                )
            }
        }
        if (result == 0) {
            throw IOException("$direction bulkTransfer made no progress (requested=$requested)")
        }
        if (result > requested) {
            throw IOException("$direction bulkTransfer exceeded request (result=$result, requested=$requested)")
        }
        return result
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
        if (!deviceConnection.setInterface(usbInterface)) {
            throw IOException("Could not select BOT alternate after reset")
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

    /** Reset this verified idle bridge while usbfs still owns its BOT interface. */
    @Synchronized
    internal fun resetForKernelHandoff() {
        if (isClosed) throw IOException("USB connection is already closed")
        if (!nativeResetDevice(deviceConnection.fileDescriptor)) {
            throw IOException("USB device reset failed")
        }
        Log.i(TAG, "Single-device kernel handoff reset completed")
        // Do not release before reset: releaseInterface reconnects the kernel driver,
        // which can immediately start UAS discovery.
        isClosed = true
        val released = try {
            deviceConnection.releaseInterface(usbInterface)
        } finally {
            deviceConnection.close()
        }
        // A successful device reset can already reconnect the kernel driver and
        // invalidate the old usbfs claim. Closing the connection is still required.
        if (!released) Log.i(TAG, "USB claim no longer held after reset; connection closed")
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { deviceConnection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { deviceConnection.close() } catch (_: Exception) {}
    }
}
