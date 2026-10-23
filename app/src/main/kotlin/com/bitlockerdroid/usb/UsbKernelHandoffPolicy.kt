package com.bitlockerdroid.usb

/** Limit the BOT-to-UAS recovery to the bridge verified on the test PSSD. */
internal object UsbKernelHandoffPolicy {
    fun needsReset(vendorId: Int, productId: Int, exposesUas: Boolean): Boolean =
        vendorId == 0x0bda && productId == 0x9210 && exposesUas
}
