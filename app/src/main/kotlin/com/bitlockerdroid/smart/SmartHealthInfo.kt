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
        if (!isSupported) {
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
        val hasHex = hex.length >= 384

        fun getU8(offset: Int): Int {
            if (hasHex && offset * 2 + 2 <= hex.length) {
                return try { hex.substring(offset * 2, offset * 2 + 2).toInt(16) } catch (_: Exception) { 0 }
            }
            return 0
        }

        fun getU16Le(offset: Int): Int {
            if (hasHex && offset * 2 + 4 <= hex.length) {
                return try {
                    val b0 = hex.substring(offset * 2, offset * 2 + 2).toInt(16)
                    val b1 = hex.substring(offset * 2 + 2, offset * 2 + 4).toInt(16)
                    b0 or (b1 shl 8)
                } catch (_: Exception) { 0 }
            }
            return 0
        }

        fun getU48LeHex(offset: Int): String {
            if (hasHex && offset * 2 + 12 <= hex.length) {
                val s = StringBuilder()
                for (i in 5 downTo 0) {
                    s.append(hex.substring((offset + i) * 2, (offset + i) * 2 + 2).uppercase())
                }
                return s.toString()
            }
            return "000000000000"
        }

        fun getU64Le(offset: Int): Long {
            if (hasHex && offset * 2 + 16 <= hex.length) {
                var v = 0L
                for (i in 0 until 8) {
                    val b = hex.substring((offset + i) * 2, (offset + i) * 2 + 2).toLong(16)
                    v = v or (b shl (i * 8))
                }
                return v
            }
            return 0L
        }

        val critWarn = if (hasHex) getU8(0) else (criticalWarning ?: 0)
        val kelvin = if (hasHex) getU16Le(1) else ((temperatureCelsius ?: 40) + 273)
        val tempC = if (kelvin > 273) kelvin - 273 else (temperatureCelsius ?: 40)
        val availSpare = if (hasHex) getU8(3) else (availableSpare ?: 100)
        val spareThresh = if (hasHex) getU8(4) else (spareThreshold ?: 5)
        val percentUsed = if (hasHex) getU8(5) else (if (healthPercentage != null) maxOf(0, 100 - healthPercentage) else 0)

        val unitsRead = if (hasHex) getU64Le(32) else ((totalBytesRead ?: 0L) / 512000L)
        val unitsWritten = if (hasHex) getU64Le(48) else ((totalBytesWritten ?: 0L) / 512000L)
        val hReads = if (hasHex) getU64Le(64) else (hostReadCommands ?: 0L)
        val hWrites = if (hasHex) getU64Le(80) else (hostWriteCommands ?: 0L)
        val busyTime = if (hasHex) getU64Le(96) else (controllerBusyMinutes ?: 0L)
        val pCycles = if (hasHex) getU64Le(112) else (powerCycles ?: 0L)
        val pHours = if (hasHex) getU64Le(128) else (powerHours ?: 0L)
        val unsafeOff = if (hasHex) getU64Le(144) else (unsafeShutdowns ?: 0L)
        val mediaErrs = if (hasHex) getU64Le(160) else (mediaErrors ?: 0L)
        val errLog = if (hasHex) getU64Le(176) else (errorLogEntries ?: 0L)

        val curWorThr = "__0"

        return listOf(
            SmartRow("01", if (critWarn == 0) "G" else "B", curWorThr, curWorThr, curWorThr, "%012X".format(critWarn), "严重警告标志"),
            SmartRow("02", when { (critWarn and 0x02) != 0 || tempC > 65 -> "B"; tempC > 55 -> "W"; else -> "G" }, curWorThr, curWorThr, curWorThr, "%012X".format(kelvin), "温度"),
            SmartRow("03", if (availSpare >= spareThresh) "G" else if (availSpare > 0) "W" else "B", curWorThr, curWorThr, curWorThr, "%012X".format(availSpare), "可用备用空间"),
            SmartRow("04", "G", curWorThr, curWorThr, curWorThr, "%012X".format(spareThresh), "可用备用空间阈值"),
            SmartRow("05", when { percentUsed > 100 -> "B"; percentUsed >= 90 -> "W"; else -> "G" }, curWorThr, curWorThr, curWorThr, "%012X".format(percentUsed), "已用寿命百分比"),
            SmartRow("06", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(32) else "%012X".format(unitsRead and 0xFFFFFFFFFFFFL), "主机总计读取"),
            SmartRow("07", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(48) else "%012X".format(unitsWritten and 0xFFFFFFFFFFFFL), "主机总计写入"),
            SmartRow("08", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(64) else "%012X".format(hReads and 0xFFFFFFFFFFFFL), "主机读命令计数"),
            SmartRow("09", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(80) else "%012X".format(hWrites and 0xFFFFFFFFFFFFL), "主机写命令计数"),
            SmartRow("0A", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(96) else "%012X".format(busyTime and 0xFFFFFFFFFFFFL), "控制器忙状态时间"),
            SmartRow("0B", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(112) else "%012X".format(pCycles and 0xFFFFFFFFFFFFL), "通电次数"),
            SmartRow("0C", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(128) else "%012X".format(pHours and 0xFFFFFFFFFFFFL), "通电时间(小时)"),
            SmartRow("0D", "G", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(144) else "%012X".format(unsafeOff and 0xFFFFFFFFFFFFL), "不安全关机计数"),
            SmartRow("0E", if (mediaErrs == 0L) "G" else "B", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(160) else "%012X".format(mediaErrs and 0xFFFFFFFFFFFFL), "媒体与数据完整性错误计数"),
            SmartRow("0F", if (errLog == 0L) "G" else "W", curWorThr, curWorThr, curWorThr, if (hasHex) getU48LeHex(176) else "%012X".format(errLog and 0xFFFFFFFFFFFFL), "错误日志项数")
        )
    }

    private fun buildSataRows(): List<SmartRow> {
        val hex = rawPageHex ?: ""
        val hasHex = hex.length >= 720
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
                val id = hex.substring(off * 2, off * 2 + 2).toInt(16)
                if (id == 0) continue
                val cur = hex.substring((off + 3) * 2, (off + 3) * 2 + 2).toInt(16)
                val wor = hex.substring((off + 4) * 2, (off + 4) * 2 + 2).toInt(16)
                val rawSb = StringBuilder()
                for (b in 5 downTo 0) {
                    rawSb.append(hex.substring((off + 5 + b) * 2, (off + 5 + b) * 2 + 2).uppercase())
                }
                val rawStr = rawSb.toString()
                val name = getSataAttrName(id)
                val sta = if (cur <= 10 && cur > 0) "W" else "G"
                val idHex = "%02X".format(id)

                rows.add(SmartRow(idHex, sta, formatCurWor(cur), formatCurWor(wor), "__0", rawStr, name))
            }
        }

        if (rows.isEmpty()) {
            // Synthesized standard SATA rows if raw page missing
            rows.add(SmartRow("01", "G", "100", "100", "_50", "000000000000", "读取错误率"))
            rows.add(SmartRow("05", if ((reallocatedSectors ?: 0L) == 0L) "G" else "B", "100", "100", "_10", "%012X".format(reallocatedSectors ?: 0L), "重新分配扇区数"))
            rows.add(SmartRow("09", "G", "_98", "_98", "__0", "%012X".format(powerHours ?: 0L), "通电时间(小时)"))
            rows.add(SmartRow("0C", "G", "_98", "_98", "__0", "%012X".format(powerCycles ?: 0L), "通电周期计数"))
            rows.add(SmartRow("C2", "G", "_60", "_50", "__0", "%012X".format(temperatureCelsius ?: 35), "温度"))
            rows.add(SmartRow("C5", if ((pendingSectors ?: 0L) == 0L) "G" else "W", "100", "100", "__0", "%012X".format(pendingSectors ?: 0L), "当前待映射扇区数"))
            rows.add(SmartRow("C6", if ((uncorrectableSectors ?: 0L) == 0L) "G" else "B", "100", "100", "__0", "%012X".format(uncorrectableSectors ?: 0L), "脱机无法纠正扇区数"))
            rows.add(SmartRow("E7", "G", "100", "100", "_10", "%012X".format(healthPercentage ?: 100), "SSD 剩余寿命"))
            rows.add(SmartRow("F1", "G", "100", "100", "__0", "%012X".format((totalBytesWritten ?: 0L) / 512L), "主机总计写入"))
        }

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
