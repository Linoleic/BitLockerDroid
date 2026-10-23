package com.bitlockerdroid.usb

/** Parsed identity encoded by USB Host partition paths such as `usb://2003/p5`. */
data class UsbPartitionPathInfo(
    val deviceKey: String,
    val partitionIndex: Int,
    val startSector: Long,
)

object UsbPartitionPathSupport {
    private val partitionSegment = Regex("(?i)^p?(\\d+)$")

    /**
     * Parses both the current `usb://<deviceId>/p<partition>` form and older
     * multi-component forms whose final component is a numeric partition index.
     * The start sector is only known when supplied by the live partition scan.
     */
    fun parse(path: String, discoveredStartLba: Long? = null): UsbPartitionPathInfo? {
        if (!path.startsWith("usb://")) return null

        val components = path.removePrefix("usb://").split('/')
        if (components.size < 2 || components.first().isBlank()) return null

        val index = partitionSegment.matchEntire(components.last())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null

        return UsbPartitionPathInfo(
            deviceKey = components.first(),
            partitionIndex = index,
            startSector = discoveredStartLba?.takeIf { it >= 0L } ?: 0L,
        )
    }

    fun format(deviceId: Int, partitionIndex: Int): String {
        require(deviceId >= 0) { "deviceId must be non-negative" }
        require(partitionIndex > 0) { "partitionIndex must be positive" }
        return "usb://$deviceId/p$partitionIndex"
    }
}

/** Keeps transient read failures from being treated as proof of an unencrypted device. */
object UsbPartitionScanPolicy {
    fun isConfirmedEmpty(scanCompleted: Boolean, bitLockerPartitionCount: Int): Boolean =
        scanCompleted && bitLockerPartitionCount == 0
}
