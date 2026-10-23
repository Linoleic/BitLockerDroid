package com.bitlockerdroid.smart

/** Exact USB bridge identities for which a read-only SMART transport is implemented. */
class UsbSmartProfile private constructor(
    val vendorId: Int,
    val productId: Int,
    val name: String
) {
    companion object {
        private val realtekRtl9210 = UsbSmartProfile(
            vendorId = 0x0bda,
            productId = 0x9210,
            name = "Realtek RTL9210"
        )

        /** Unknown devices intentionally have no active SMART probing profile. */
        fun profileFor(vendorId: Int, productId: Int): UsbSmartProfile? =
            if (vendorId == realtekRtl9210.vendorId && productId == realtekRtl9210.productId) {
                realtekRtl9210
            } else {
                null
            }
    }
}
