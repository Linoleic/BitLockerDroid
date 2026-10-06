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

    val formattedRawData: String
        get() {
            if (!rawJson.isNullOrBlank()) {
                try {
                    return org.json.JSONObject(rawJson).toString(2)
                } catch (_: Exception) {}
                return rawJson
            }
            return try {
                val obj = org.json.JSONObject()
                obj.put("supported", isSupported)
                obj.put("status", status.name)
                obj.put("disk_type", diskType)
                model?.let { obj.put("model", it) }
                serial?.let { obj.put("serial", it) }
                firmware?.let { obj.put("firmware", it) }
                vendor?.let { obj.put("vendor", it) }
                product?.let { obj.put("product", it) }
                deviceNode?.let { obj.put("device_node", it) }
                temperatureCelsius?.let { obj.put("temperature_c", it) }
                healthPercentage?.let { obj.put("health_percent", it) }
                availableSpare?.let { obj.put("available_spare", it) }
                spareThreshold?.let { obj.put("spare_threshold", it) }
                criticalWarning?.let { obj.put("critical_warning", it) }
                powerHours?.let { obj.put("power_hours", it) }
                powerCycles?.let { obj.put("power_cycles", it) }
                unsafeShutdowns?.let { obj.put("unsafe_shutdowns", it) }
                totalBytesRead?.let { obj.put("total_bytes_read", it) }
                totalBytesWritten?.let { obj.put("total_bytes_written", it) }
                reallocatedSectors?.let { obj.put("reallocated_sectors", it) }
                pendingSectors?.let { obj.put("pending_sectors", it) }
                uncorrectableSectors?.let { obj.put("uncorrectable_sectors", it) }
                reason?.let { obj.put("reason", it) }
                obj.toString(2)
            } catch (e: Exception) {
                "{ \"error\": \"Unable to serialize raw data: ${e.message}\" }"
            }
        }

    companion object {
        fun unsupported(vendor: String? = null, product: String? = null, node: String? = null, reason: String? = null): SmartHealthInfo {
            return SmartHealthInfo(
                isSupported = false,
                status = SmartOverallStatus.UNSUPPORTED,
                diskType = "USB Flash Drive",
                vendor = vendor,
                product = product,
                deviceNode = node,
                reason = reason ?: "The connected storage controller does not implement standard ATA or NVMe S.M.A.R.T. telemetry."
            )
        }
    }
}
