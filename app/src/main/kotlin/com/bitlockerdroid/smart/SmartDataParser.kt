package com.bitlockerdroid.smart

import org.json.JSONObject

object SmartDataParser {

    fun parseJson(jsonStr: String?): SmartHealthInfo {
        if (jsonStr.isNullOrBlank()) {
            return SmartHealthInfo.unconfirmed(reason = "Empty telemetry response").copy(failureStage = SmartFailureStage.RESPONSE)
        }
        return try {
            val obj = JSONObject(jsonStr)
            // Only an explicit capability result can establish lack of support.
            // Error responses from older daemons may contain supported:false alone.
            val supportedClaim = obj.opt("supported") as? Boolean
            val supported = supportedClaim == true
            val statusText = obj.text("status")?.uppercase(java.util.Locale.ROOT)
            val status = when {
                obj.text("error") != null || supportedClaim == null -> SmartOverallStatus.UNCONFIRMED
                supported && statusText == "HEALTHY" -> SmartOverallStatus.HEALTHY
                supported && statusText == "WARNING" -> SmartOverallStatus.WARNING
                supported && statusText == "CRITICAL" -> SmartOverallStatus.CRITICAL
                !supported && (statusText == null || statusText == "UNSUPPORTED") -> SmartOverallStatus.UNSUPPORTED
                else -> SmartOverallStatus.UNCONFIRMED
            }

            val info = SmartHealthInfo(
                isSupported = supported,
                status = status,
                diskType = obj.text("disk_type") ?: "Unknown",
                model = obj.text("model"),
                serial = obj.text("serial"),
                firmware = obj.text("firmware"),
                vendor = obj.text("vendor"),
                product = obj.text("product"),
                temperatureCelsius = obj.integer("temperature_c", Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())?.toInt(),
                healthPercentage = obj.integer("health_percent", 0L..100L)?.toInt(),
                availableSpare = obj.integer("available_spare", 0L..100L)?.toInt(),
                spareThreshold = obj.integer("spare_threshold", 0L..100L)?.toInt(),
                criticalWarning = obj.integer("critical_warning", 0L..255L)?.toInt(),
                powerCycles = obj.integer("power_cycles", 0L..Long.MAX_VALUE),
                powerHours = obj.integer("power_hours", 0L..Long.MAX_VALUE),
                unsafeShutdowns = obj.integer("unsafe_shutdowns", 0L..Long.MAX_VALUE),
                totalBytesRead = obj.integer("total_bytes_read", 0L..Long.MAX_VALUE),
                totalBytesWritten = obj.integer("total_bytes_written", 0L..Long.MAX_VALUE),
                reallocatedSectors = obj.integer("reallocated_sectors", 0L..Long.MAX_VALUE),
                pendingSectors = obj.integer("pending_sectors", 0L..Long.MAX_VALUE),
                uncorrectableSectors = obj.integer("uncorrectable_sectors", 0L..Long.MAX_VALUE),
                hostReadCommands = obj.integer("host_read_commands", 0L..Long.MAX_VALUE),
                hostWriteCommands = obj.integer("host_write_commands", 0L..Long.MAX_VALUE),
                controllerBusyMinutes = obj.integer("controller_busy_time", 0L..Long.MAX_VALUE),
                mediaErrors = obj.integer("media_errors", 0L..Long.MAX_VALUE),
                errorLogEntries = obj.integer("error_log_entries", 0L..Long.MAX_VALUE),
                deviceNode = obj.text("device_node"),
                reason = obj.text("reason") ?: obj.text("error"),
                failureStage = obj.text("failure_stage")?.let { value -> SmartFailureStage.entries.find { it.name == value } }
                    ?: if (status == SmartOverallStatus.UNCONFIRMED && !supported) SmartFailureStage.RESPONSE else null,
                dataIntegrity = obj.text("data_integrity")?.let { value -> SmartDataIntegrity.entries.find { it.name == value } }
                    ?: SmartDataIntegrity.NOT_CHECKED,
                rawJson = jsonStr,
                rawPageHex = obj.text("raw_page_hex"),
                exactCounters = SmartCounter.entries.mapNotNull { counter ->
                    obj.counter(counter)?.let { counter to it }
                }.toMap()
            )
            SmartPageValidation.validate(info)
        } catch (e: Exception) {
            SmartHealthInfo.unconfirmed(reason = "Invalid telemetry response").copy(failureStage = SmartFailureStage.RESPONSE)
        }
    }

    private fun JSONObject.text(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONObject.counter(counter: SmartCounter): java.math.BigInteger? {
        val value = opt(counter.jsonKey)
        if (value !is Number && value !is String) return null
        // Native readers send decimal strings so Android's JSON parser cannot round them through Double.
        if (value is Double || value is Float) return null
        return try {
            java.math.BigDecimal(value.toString()).toBigIntegerExact()
                .takeIf { it.signum() >= 0 && it <= counter.maxValue }
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Reject null, malformed, fractional and overflowing values instead of coercing them to zero. */
    private fun JSONObject.integer(key: String, range: LongRange): Long? {
        val value = opt(key)
        if (value !is Number && value !is String) return null
        if (value is Double || value is Float) return null
        return try {
            java.math.BigDecimal(value.toString()).longValueExact().takeIf { it in range }
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

}
