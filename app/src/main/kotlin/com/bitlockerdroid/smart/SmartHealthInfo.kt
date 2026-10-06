package com.bitlockerdroid.smart

enum class SmartOverallStatus {
    HEALTHY,    // Normal/Good health
    WARNING,    // Caution/Threshold near warning
    CRITICAL,   // Failing/Threshold exceeded
    UNSUPPORTED // Controller lacks ATA/NVMe telemetry
}

data class SmartHealthInfo(
    val isSupported: Boolean,
    val status: SmartOverallStatus,
    val diskType: String,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val vendor: String? = null,
    val product: String? = null,
    val temperatureCelsius: Int? = null,
    val healthPercentage: Int? = null,
    val availableSpare: Int? = null,
    val spareThreshold: Int? = null,
    val criticalWarning: Int? = null,
    val powerCycles: Long? = null,
    val powerHours: Long? = null,
    val unsafeShutdowns: Long? = null,
    val totalBytesRead: Long? = null,
    val totalBytesWritten: Long? = null,
    val reallocatedSectors: Long? = null,
    val pendingSectors: Long? = null,
    val uncorrectableSectors: Long? = null,
    val deviceNode: String? = null,
    val reason: String? = null,
    val rawJson: String? = null
) {
    val totalTbWritten: Double?
        get() = totalBytesWritten?.let { it / 1_000_000_000_000.0 }

    val totalTbRead: Double?
        get() = totalBytesRead?.let { it / 1_000_000_000_000.0 }

    val powerDays: Long?
        get() = powerHours?.let { it / 24 }

    companion object {
        fun unsupported(vendor: String? = null, product: String? = null, node: String? = null, reason: String? = null): SmartHealthInfo {
            return SmartHealthInfo(
                isSupported = false,
                status = SmartOverallStatus.UNSUPPORTED,
                diskType = "USB Flash Drive",
                vendor = vendor,
                product = product,
                deviceNode = node,
                reason = reason ?: "The connected storage controller does not implement standard ATA or NVMe SMART telemetry."
            )
        }
    }
}
