package com.bitlockerdroid.smart

import java.math.BigInteger

enum class SmartOverallStatus {
    HEALTHY,    // Normal/Good health
    WARNING,    // Caution/Threshold near warning
    CRITICAL,   // Failing/Threshold exceeded
    UNSUPPORTED, // Controller lacks ATA/NVMe telemetry
    UNCONFIRMED // No valid telemetry result yet, including busy/unverified connections
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
    val hostReadCommands: Long? = null,
    val hostWriteCommands: Long? = null,
    val controllerBusyMinutes: Long? = null,
    val mediaErrors: Long? = null,
    val errorLogEntries: Long? = null,
    val deviceNode: String? = null,
    val reason: String? = null,
    val rawJson: String? = null,
    val rawPageHex: String? = null,
    val exactCounters: Map<SmartCounter, BigInteger> = emptyMap()
) {
    val totalTbWritten: Double?
        get() = counterValue(SmartCounter.TOTAL_BYTES_WRITTEN)?.toDouble()?.div(1_000_000_000_000.0)

    val totalTbRead: Double?
        get() = counterValue(SmartCounter.TOTAL_BYTES_READ)?.toDouble()?.div(1_000_000_000_000.0)

    val powerDays: Long?
        get() = counterValue(SmartCounter.POWER_HOURS)?.divide(BigInteger.valueOf(24L))
            ?.takeIf { it <= BigInteger.valueOf(Long.MAX_VALUE) }?.toLong()

    val formattedRawData: String
        get() = buildSmartAttributeTable()

    fun counterValue(counter: SmartCounter): BigInteger? = exactCounters[counter]
        ?.takeIf { it.signum() >= 0 }
        ?: legacyCounterValue(counter)?.takeIf { it >= 0L }?.let(BigInteger::valueOf)

    private fun legacyCounterValue(counter: SmartCounter): Long? = when (counter) {
        SmartCounter.TOTAL_BYTES_READ -> totalBytesRead
        SmartCounter.TOTAL_BYTES_WRITTEN -> totalBytesWritten
        SmartCounter.HOST_READ_COMMANDS -> hostReadCommands
        SmartCounter.HOST_WRITE_COMMANDS -> hostWriteCommands
        SmartCounter.CONTROLLER_BUSY_MINUTES -> controllerBusyMinutes
        SmartCounter.POWER_CYCLES -> powerCycles
        SmartCounter.POWER_HOURS -> powerHours
        SmartCounter.UNSAFE_SHUTDOWNS -> unsafeShutdowns
        SmartCounter.MEDIA_ERRORS -> mediaErrors
        SmartCounter.ERROR_LOG_ENTRIES -> errorLogEntries
    }

    /**
     * Builds standard CrystalDiskInfo-compatible S.M.A.R.T. telemetry attribute table.
     */
    private fun buildSmartAttributeTable(): String {
        if (!isSupported && status == SmartOverallStatus.UNSUPPORTED) {
            val sb = StringBuilder()
            sb.appendLine("-- S.M.A.R.T. --------------------------------------------------------------")
            sb.appendLine("当前设备不支持标准 S.M.A.R.T. 硬件监控。")
            if (!reason.isNullOrBlank()) {
                sb.appendLine(reason)
            }
            return sb.toString().trimEnd()
        }

        val sb = StringBuilder()
        sb.appendLine("-- S.M.A.R.T. --------------------------------------------------------------")
        sb.appendLine("ID Sta Cur Wor Thr RawValues    AttributeName (标题含义见说明)")

        if (!isSupported && status == SmartOverallStatus.UNCONFIRMED) {
            sb.appendLine("状态尚未确认，当前没有可用的原始 S.M.A.R.T. 数据。")
            if (!reason.isNullOrBlank()) sb.appendLine(reason)
            return sb.toString().trimEnd()
        }

        val isNvme = diskType.contains("NVMe", ignoreCase = true)
        val rows = if (isNvme) buildNvmeRows() else buildSataRows()

        for (row in rows) {
            // Formats: "01  G  __0 __0 __0 000000000000 严重警告标志"
            sb.appendLine("%s  %s  %s %s %s %s %s".format(
                row.id,
                row.sta,
                row.cur,
                row.wor,
                row.thr,
                row.rawValues,
                row.attributeName
            ))
        }

        sb.appendLine()
        sb.appendLine("标题含义说明:")
        sb.appendLine(" Sta: 状态(G: 良好 | W: 警告 | B: 严重告警 | U: 未知)")
        sb.appendLine(" Cur: 当前值")
        sb.appendLine(" Wor: 历史最差值")
        sb.appendLine(" Thr: 临界值")
        sb.appendLine(" RawValues: 原始数据")
        sb.append(" AttributeName: 属性名称")

        return sb.toString()
    }

    private data class SmartRow(
        val id: String,
        val sta: String,
        val cur: String,
        val wor: String,
        val thr: String,
        val rawValues: String,
        val attributeName: String
    )

    private fun isCompleteRawPage(hex: String): Boolean =
        hex.length == 1024 && hex.all { it.digitToIntOrNull(16) != null }

    private fun buildNvmeRows(): List<SmartRow> {
        val hex = rawPageHex ?: ""
        val hasHex = isCompleteRawPage(hex)
        fun getU8(offset: Int): Int? = if (hasHex) hex.substring(offset * 2, offset * 2 + 2).toInt(16) else null
        fun getU16Le(offset: Int): Int? = if (hasHex) hex.substring(offset * 2, offset * 2 + 2).toInt(16) or
            (hex.substring(offset * 2 + 2, offset * 2 + 4).toInt(16) shl 8) else null
        fun getU128Le(counter: SmartCounter): BigInteger? = if (hasHex) {
            val start = counter.offset * 2
            val bytes = ByteArray(16) { i -> hex.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte() }
            BigInteger(1, bytes.reversedArray())
        } else null

        val critWarn = getU8(0) ?: criticalWarning
        val rawKelvin = getU16Le(1)?.takeIf { it != 0 }
        val kelvin = rawKelvin ?: temperatureCelsius?.plus(273)
        val availSpare = getU8(3) ?: availableSpare
        val spareThresh = getU8(4) ?: spareThreshold
        val percentUsed = getU8(5) ?: healthPercentage?.let { 100 - it }

        fun row(id: String, value: BigInteger?, name: String, sta: String = "U", raw: String? = null) =
            SmartRow(id, sta, "—", "—", "—", raw ?: value?.toString(16)?.uppercase()?.padStart(12, '0') ?: "—", name)
        fun counter(counter: SmartCounter): BigInteger? {
            val rawValue = getU128Le(counter)
            if (rawValue != null) return rawValue
            val value = counterValue(counter) ?: return null
            return if (counter.scale > 1L) value.divide(BigInteger.valueOf(counter.scale)) else value
        }
        fun counterHex(counter: SmartCounter): String? = getU128Le(counter)?.toString(16)?.uppercase()?.padStart(32, '0')
            ?: counter(counter)?.toString(16)?.uppercase()?.padStart(12, '0')
        val mediaErrors = counter(SmartCounter.MEDIA_ERRORS)
        val errorLogEntries = counter(SmartCounter.ERROR_LOG_ENTRIES)

        return listOf(
            row("01", critWarn?.let { BigInteger.valueOf(it.toLong()) }, "严重警告标志", critWarn?.let { if (it == 0) "G" else "B" } ?: "U"),
            row("02", kelvin?.let { BigInteger.valueOf(it.toLong()) }, "温度", if ((critWarn ?: 0) and 0x02 != 0) "B" else "U", kelvin?.let { "%012X".format(it) }),
            row("03", availSpare?.let { BigInteger.valueOf(it.toLong()) }, "可用备用空间", if (availSpare != null && spareThresh != null) {
                when { (critWarn ?: 0) and 0x01 != 0 -> "B"; availSpare < spareThresh -> "W"; else -> "G" }
            } else "U"),
            row("04", spareThresh?.let { BigInteger.valueOf(it.toLong()) }, "可用备用空间阈值", if (spareThresh == null) "U" else "G"),
            row("05", percentUsed?.let { BigInteger.valueOf(it.toLong()) }, "已用寿命百分比", percentUsed?.let { if (it >= 100) "W" else "G" } ?: "U"),
            row("06", counter(SmartCounter.TOTAL_BYTES_READ), "主机总计读取", raw = counterHex(SmartCounter.TOTAL_BYTES_READ)),
            row("07", counter(SmartCounter.TOTAL_BYTES_WRITTEN), "主机总计写入", raw = counterHex(SmartCounter.TOTAL_BYTES_WRITTEN)),
            row("08", counter(SmartCounter.HOST_READ_COMMANDS), "主机读命令计数", raw = counterHex(SmartCounter.HOST_READ_COMMANDS)),
            row("09", counter(SmartCounter.HOST_WRITE_COMMANDS), "主机写命令计数", raw = counterHex(SmartCounter.HOST_WRITE_COMMANDS)),
            row("0A", counter(SmartCounter.CONTROLLER_BUSY_MINUTES), "控制器忙状态时间", raw = counterHex(SmartCounter.CONTROLLER_BUSY_MINUTES)),
            row("0B", counter(SmartCounter.POWER_CYCLES), "通电次数", raw = counterHex(SmartCounter.POWER_CYCLES)),
            row("0C", counter(SmartCounter.POWER_HOURS), "通电时间(小时)", raw = counterHex(SmartCounter.POWER_HOURS)),
            row("0D", counter(SmartCounter.UNSAFE_SHUTDOWNS), "不安全关机计数", raw = counterHex(SmartCounter.UNSAFE_SHUTDOWNS)),
            row("0E", mediaErrors, "媒体与数据完整性错误计数", sta = mediaErrors?.let { if (it == BigInteger.ZERO) "G" else "W" } ?: "U", raw = counterHex(SmartCounter.MEDIA_ERRORS)),
            row("0F", errorLogEntries, "错误日志项数", sta = errorLogEntries?.let { if (it == BigInteger.ZERO) "G" else "W" } ?: "U", raw = counterHex(SmartCounter.ERROR_LOG_ENTRIES))
        )
    }

    private fun buildSataRows(): List<SmartRow> {
        val hex = rawPageHex ?: ""
        val hasHex = isCompleteRawPage(hex)
        val rows = mutableListOf<SmartRow>()

        fun formatCurWor(v: Int): String {
            return when {
                v >= 100 -> "%3d".format(v)
                v >= 10 -> "_%2d".format(v)
                else -> "__%d".format(v)
            }
        }

        if (hasHex) {
            for (i in 0 until 30) {
                val off = 2 + i * 12
                if (off * 2 + 24 > hex.length) break
                val id = hex.substring(off * 2, off * 2 + 2).toIntOrNull(16) ?: continue
                if (id == 0) continue
                val cur = hex.substring((off + 3) * 2, (off + 3) * 2 + 2).toIntOrNull(16) ?: continue
                val wor = hex.substring((off + 4) * 2, (off + 4) * 2 + 2).toIntOrNull(16) ?: continue
                val rawSb = StringBuilder()
                for (b in 5 downTo 0) {
                    rawSb.append(hex.substring((off + 5 + b) * 2, (off + 5 + b) * 2 + 2).uppercase())
                }
                val rawStr = rawSb.toString()
                val name = getSataAttrName(id)
                val idHex = "%02X".format(id)

                rows.add(SmartRow(idHex, "U", formatCurWor(cur), formatCurWor(wor), "—", rawStr, name))
            }
        }

        // Structured counters are useful raw values, but do not contain normalized ATA values or thresholds.
        fun field(id: String, value: Long?, name: String, status: String = "U") =
            SmartRow(id, status, "—", "—", "—", value?.let { "%012X".format(it) } ?: "—", name)
        val knownFields = listOf(
            field("01", null, "读取错误率"),
            field("05", reallocatedSectors, "重新分配扇区数", reallocatedSectors?.let { if (it == 0L) "G" else "W" } ?: "U"),
            field("09", powerHours, "通电时间(小时)"),
            field("0C", powerCycles, "通电周期计数"),
            field("C2", temperatureCelsius?.toLong(), "温度"),
            field("C5", pendingSectors, "当前待映射扇区数", pendingSectors?.let { if (it == 0L) "G" else "W" } ?: "U"),
            field("C6", uncorrectableSectors, "脱机无法纠正扇区数", uncorrectableSectors?.let { if (it == 0L) "G" else "W" } ?: "U"),
            field("E7", healthPercentage?.toLong(), "SSD 剩余寿命"),
            field("F1", totalBytesWritten?.div(512L), "主机总计写入")
        )
        val existingIds = rows.mapTo(mutableSetOf()) { it.id }
        rows.addAll(knownFields.filterNot { it.id in existingIds })

        return rows
    }

    private fun getSataAttrName(id: Int): String {
        return when (id) {
            0x01 -> "读取错误率"
            0x05 -> "重新分配扇区数"
            0x07 -> "寻道错误率"
            0x09 -> "通电时间(小时)"
            0x0A -> "起旋重试计数"
            0x0C -> "通电周期计数"
            0xBB -> "报告的无法纠正错误"
            0xBC -> "命令超时"
            0xC2 -> "温度"
            0xC4 -> "重新分配事件计数"
            0xC5 -> "当前待映射扇区数"
            0xC6 -> "脱机无法纠正扇区数"
            0xC7 -> "UltraDMA CRC 错误计数"
            0xE7 -> "SSD 剩余寿命"
            0xF1 -> "主机总计写入"
            0xF2 -> "主机总计读取"
            else -> "厂商专属属性 0x%02X".format(id)
        }
    }

    companion object {
        fun unconfirmed(vendor: String? = null, product: String? = null, node: String? = null, reason: String? = null) =
            SmartHealthInfo(isSupported = false, status = SmartOverallStatus.UNCONFIRMED,
                diskType = "Unknown", vendor = vendor, product = product, deviceNode = node, reason = reason)

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
