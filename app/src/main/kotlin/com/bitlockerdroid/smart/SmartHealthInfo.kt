package com.bitlockerdroid.smart

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
    val rawPageHex: String? = null
) {
    val totalTbWritten: Double?
        get() = totalBytesWritten?.let { it / 1_000_000_000_000.0 }

    val totalTbRead: Double?
        get() = totalBytesRead?.let { it / 1_000_000_000_000.0 }

    val powerDays: Long?
        get() = powerHours?.let { it / 24 }

    val formattedRawData: String
        get() = buildSmartAttributeTable()

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
        sb.appendLine(" Sta: 状态(G: 良好 | W: 警告 | B: 损坏 | U: 未知)")
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

    private fun buildNvmeRows(): List<SmartRow> {
        val hex = rawPageHex ?: ""
        val hasHex = hex.length >= 384 && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }

        fun getU8(offset: Int): Int? = if (hasHex) hex.substring(offset * 2, offset * 2 + 2).toInt(16) else null

        fun getU16Le(offset: Int): Int? = if (hasHex) {
            hex.substring(offset * 2, offset * 2 + 2).toInt(16) or
                (hex.substring(offset * 2 + 2, offset * 2 + 4).toInt(16) shl 8)
        } else null

        fun getU48LeHex(offset: Int): String? = if (hasHex) buildString {
            for (i in 5 downTo 0) append(hex.substring((offset + i) * 2, (offset + i) * 2 + 2).uppercase())
        } else null

        fun getU64Le(offset: Int): Long? = if (hasHex) {
            var v = 0L
            for (i in 0 until 8) {
                val b = hex.substring((offset + i) * 2, (offset + i) * 2 + 2).toLong(16)
                v = v or (b shl (i * 8))
            }
            v
        } else null

        val critWarn = getU8(0) ?: criticalWarning
        val kelvin = getU16Le(1)?.takeIf { it != 0 }
        val tempC = kelvin?.minus(273) ?: temperatureCelsius
        val availSpare = getU8(3) ?: availableSpare
        val spareThresh = getU8(4) ?: spareThreshold
        val percentUsed = getU8(5) ?: healthPercentage?.let { 100 - it }

        val unitsRead = getU64Le(32) ?: totalBytesRead?.div(512000L)
        val unitsWritten = getU64Le(48) ?: totalBytesWritten?.div(512000L)
        val hReads = getU64Le(64) ?: hostReadCommands
        val hWrites = getU64Le(80) ?: hostWriteCommands
        val busyTime = getU64Le(96) ?: controllerBusyMinutes
        val pCycles = getU64Le(112) ?: powerCycles
        val pHours = getU64Le(128) ?: powerHours
        val unsafeOff = getU64Le(144) ?: unsafeShutdowns
        val mediaErrs = getU64Le(160) ?: mediaErrors
        val errLog = getU64Le(176) ?: errorLogEntries

        fun row(id: String, value: Long?, name: String, sta: String = "U", raw: String? = null) =
            SmartRow(id, sta, "—", "—", "—", raw ?: value?.let { "%012X".format(it and 0xFFFFFFFFFFFFL) } ?: "—", name)

        return listOf(
            row("01", critWarn?.toLong(), "严重警告标志", critWarn?.let { if (it == 0) "G" else "B" } ?: "U"),
            row("02", tempC?.toLong(), "温度", tempC?.let { when { (critWarn ?: 0) and 0x02 != 0 || it > 65 -> "B"; it > 55 -> "W"; else -> "G" } } ?: "U", kelvin?.let { "%012X".format(it) }),
            row("03", availSpare?.toLong(), "可用备用空间", if (availSpare != null && spareThresh != null) if (availSpare >= spareThresh) "G" else if (availSpare > 0) "W" else "B" else "U"),
            row("04", spareThresh?.toLong(), "可用备用空间阈值", if (spareThresh == null) "U" else "G"),
            row("05", percentUsed?.toLong(), "已用寿命百分比", percentUsed?.let { when { it > 100 -> "B"; it >= 90 -> "W"; else -> "G" } } ?: "U"),
            row("06", unitsRead, "主机总计读取", raw = getU48LeHex(32)), row("07", unitsWritten, "主机总计写入", raw = getU48LeHex(48)),
            row("08", hReads, "主机读命令计数", raw = getU48LeHex(64)), row("09", hWrites, "主机写命令计数", raw = getU48LeHex(80)),
            row("0A", busyTime, "控制器忙状态时间", raw = getU48LeHex(96)), row("0B", pCycles, "通电次数", raw = getU48LeHex(112)),
            row("0C", pHours, "通电时间(小时)", raw = getU48LeHex(128)), row("0D", unsafeOff, "不安全关机计数", raw = getU48LeHex(144)),
            row("0E", mediaErrs, "媒体与数据完整性错误计数", sta = mediaErrs?.let { if (it == 0L) "G" else "B" } ?: "U", raw = getU48LeHex(160)),
            row("0F", errLog, "错误日志项数", sta = errLog?.let { if (it == 0L) "G" else "W" } ?: "U", raw = getU48LeHex(176))
        )
    }

    private fun buildSataRows(): List<SmartRow> {
        val hex = rawPageHex ?: ""
        val hasHex = hex.length >= 720 && hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }
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
                val sta = if (cur <= 10 && cur > 0) "W" else "G"
                val idHex = "%02X".format(id)

                rows.add(SmartRow(idHex, sta, formatCurWor(cur), formatCurWor(wor), "—", rawStr, name))
            }
        }

        // Structured counters are useful raw values, but do not contain normalized ATA values or thresholds.
        fun field(id: String, value: Long?, name: String, status: String = if (value == 0L) "G" else "U") =
            SmartRow(id, status, "—", "—", "—", value?.let { "%012X".format(it) } ?: "—", name)
        val knownFields = listOf(
            field("01", null, "读取错误率"),
            field("05", reallocatedSectors, "重新分配扇区数", reallocatedSectors?.let { if (it == 0L) "G" else "B" } ?: "U"),
            field("09", powerHours, "通电时间(小时)"),
            field("0C", powerCycles, "通电周期计数"),
            field("C2", temperatureCelsius?.toLong(), "温度"),
            field("C5", pendingSectors, "当前待映射扇区数", pendingSectors?.let { if (it == 0L) "G" else "W" } ?: "U"),
            field("C6", uncorrectableSectors, "脱机无法纠正扇区数", uncorrectableSectors?.let { if (it == 0L) "G" else "B" } ?: "U"),
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
