package com.bitlockerdroid.smart

import java.math.BigInteger

/** NVMe counters are unsigned 128-bit values; byte totals also include the data-unit scale. */
enum class SmartCounter(val jsonKey: String, val offset: Int, val scale: Long = 1) {
    TOTAL_BYTES_READ("total_bytes_read", 32, 512_000),
    TOTAL_BYTES_WRITTEN("total_bytes_written", 48, 512_000),
    HOST_READ_COMMANDS("host_read_commands", 64),
    HOST_WRITE_COMMANDS("host_write_commands", 80),
    CONTROLLER_BUSY_MINUTES("controller_busy_time", 96),
    POWER_CYCLES("power_cycles", 112),
    POWER_HOURS("power_hours", 128),
    UNSAFE_SHUTDOWNS("unsafe_shutdowns", 144),
    MEDIA_ERRORS("media_errors", 160),
    ERROR_LOG_ENTRIES("error_log_entries", 176);

    internal val maxValue: BigInteger
        get() = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE).multiply(BigInteger.valueOf(scale))

    internal fun readNvme(page: ByteArray): BigInteger {
        require(page.size == 512)
        return BigInteger(1, page.copyOfRange(offset, offset + 16).reversedArray())
            .multiply(BigInteger.valueOf(scale))
    }
}

/** Application severity policy, shared by USB parsing and Root-result validation tests. */
internal fun nvmeHealthStatus(criticalWarning: Int, availableSpare: Int, spareThreshold: Int, percentageUsed: Int): SmartOverallStatus = when {
    criticalWarning != 0 -> SmartOverallStatus.CRITICAL
    availableSpare < spareThreshold || percentageUsed >= 100 -> SmartOverallStatus.WARNING
    else -> SmartOverallStatus.HEALTHY
}
