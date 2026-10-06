package com.bitlockerdroid.smart

import org.json.JSONObject

object SmartDataParser {

    fun parseJson(jsonStr: String?): SmartHealthInfo {
        if (jsonStr.isNullOrBlank()) {
            return SmartHealthInfo.unsupported(reason = "Empty telemetry response")
        }
        return try {
            val obj = JSONObject(jsonStr)
            val supported = obj.optBoolean("supported", false)
            val statusStr = obj.optString("status", if (supported) "HEALTHY" else "UNSUPPORTED")
            val status = when (statusStr.uppercase()) {
                "HEALTHY" -> SmartOverallStatus.HEALTHY
                "WARNING" -> SmartOverallStatus.WARNING
                "CRITICAL" -> SmartOverallStatus.CRITICAL
                else -> SmartOverallStatus.UNSUPPORTED
            }

            SmartHealthInfo(
                isSupported = supported,
                status = status,
                diskType = obj.optString("disk_type", "Unknown"),
                model = obj.optString("model").takeIf { it.isNotBlank() },
                serial = obj.optString("serial").takeIf { it.isNotBlank() },
                firmware = obj.optString("firmware").takeIf { it.isNotBlank() },
                vendor = obj.optString("vendor").takeIf { it.isNotBlank() },
                product = obj.optString("product").takeIf { it.isNotBlank() },
                temperatureCelsius = if (obj.has("temperature_c")) obj.optInt("temperature_c") else null,
                healthPercentage = if (obj.has("health_percent")) obj.optInt("health_percent") else null,
                availableSpare = if (obj.has("available_spare")) obj.optInt("available_spare") else null,
                spareThreshold = if (obj.has("spare_threshold")) obj.optInt("spare_threshold") else null,
                criticalWarning = if (obj.has("critical_warning")) obj.optInt("critical_warning") else null,
                powerCycles = if (obj.has("power_cycles")) obj.optLong("power_cycles") else null,
                powerHours = if (obj.has("power_hours")) obj.optLong("power_hours") else null,
                unsafeShutdowns = if (obj.has("unsafe_shutdowns")) obj.optLong("unsafe_shutdowns") else null,
                totalBytesRead = if (obj.has("total_bytes_read")) obj.optLong("total_bytes_read") else null,
                totalBytesWritten = if (obj.has("total_bytes_written")) obj.optLong("total_bytes_written") else null,
                reallocatedSectors = if (obj.has("reallocated_sectors")) obj.optLong("reallocated_sectors") else null,
                pendingSectors = if (obj.has("pending_sectors")) obj.optLong("pending_sectors") else null,
                uncorrectableSectors = if (obj.has("uncorrectable_sectors")) obj.optLong("uncorrectable_sectors") else null,
                hostReadCommands = if (obj.has("host_read_commands")) obj.optLong("host_read_commands") else null,
                hostWriteCommands = if (obj.has("host_write_commands")) obj.optLong("host_write_commands") else null,
                controllerBusyMinutes = if (obj.has("controller_busy_time")) obj.optLong("controller_busy_time") else null,
                mediaErrors = if (obj.has("media_errors")) obj.optLong("media_errors") else null,
                errorLogEntries = if (obj.has("error_log_entries")) obj.optLong("error_log_entries") else null,
                deviceNode = obj.optString("device_node").takeIf { it.isNotBlank() },
                reason = obj.optString("reason").takeIf { it.isNotBlank() },
                rawJson = jsonStr,
                rawPageHex = obj.optString("raw_page_hex").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            SmartHealthInfo.unsupported(reason = "JSON parse error: ${e.message}")
        }
    }
}
