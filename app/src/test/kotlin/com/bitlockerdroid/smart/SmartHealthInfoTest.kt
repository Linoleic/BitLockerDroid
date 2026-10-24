package com.bitlockerdroid.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SmartHealthInfoTest {
    private fun info(
        type: String = "SATA SSD", raw: String? = null,
        status: SmartOverallStatus = SmartOverallStatus.HEALTHY, supported: Boolean = true,
        temperature: Int? = null, health: Int? = null, spare: Int? = null,
        spareThreshold: Int? = null, criticalWarning: Int? = null,
        reallocated: Long? = null, pending: Long? = null, errors: Long? = null,
        powerHours: Long? = null
    ) = SmartHealthInfo(
        isSupported = supported, status = status, diskType = type,
        temperatureCelsius = temperature, healthPercentage = health,
        availableSpare = spare, spareThreshold = spareThreshold, criticalWarning = criticalWarning,
        reallocatedSectors = reallocated, pendingSectors = pending,
        uncorrectableSectors = errors, powerHours = powerHours, rawPageHex = raw
    )

    private fun cells(table: String, id: String): List<String> = table.lineSequence()
        .first { it.trimStart().startsWith("$id |") }
        .split(" | ").map(String::trim)

    private fun assertRow(table: String, id: String, vararg expected: String) {
        assertEquals("row $id", expected.toList(), cells(table, id))
    }

    @Test fun absentAtaFieldsAreUnknownAndRealZeroIsPreserved() {
        val table = info(reallocated = 0, pending = 0, errors = 0).formattedRawData
        assertRow(table, "05", "05", "G", "-", "-", "-", "000000000000", "S", "重新分配扇区数")
        assertRow(table, "09", "09", "U", "-", "-", "-", "-", "U", "通电时间(小时)")
        assertRow(table, "C5", "C5", "G", "-", "-", "-", "000000000000", "S", "当前待映射扇区数")
        assertRow(table, "C6", "C6", "G", "-", "-", "-", "000000000000", "S", "脱机无法纠正扇区数")
        assertFalse(table.contains("35"))
        assertFalse(table.contains("100"))
    }

    @Test fun structuredFieldsAndMalformedOrTruncatedPagesDoNotCrashOrInventValues() {
        for (raw in listOf("XYZ" + "0".repeat(720), "0".repeat(719))) {
            val table = info(raw = raw, temperature = 0, powerHours = 0).formattedRawData
            assertRow(table, "09", "09", "U", "-", "-", "-", "000000000000", "S", "通电时间(小时)")
            assertRow(table, "C2", "C2", "U", "-", "-", "-", "000000000000", "S", "温度")
            assertRow(table, "05", "05", "U", "-", "-", "-", "-", "U", "重新分配扇区数")
        }
    }

    @Test fun validAtaRawPagePreservesRawCountersAndDoesNotInventThreshold() {
        val bytes = ByteArray(512)
        bytes[2] = 5; bytes[5] = 100; bytes[6] = 90
        val table = info(raw = bytes.joinToString("") { "%02X".format(it) }, reallocated = 9, powerHours = 222).formattedRawData
        assertRow(table, "05", "05", "U", "100", "90", "-", "000000000000", "R", "重新分配扇区数")
        assertRow(table, "09", "09", "U", "-", "-", "-", "-", "U", "通电时间(小时)")
    }

    @Test fun lowAtaNormalizedValuesRemainUnknownWithoutThresholdPage() {
        val bytes = ByteArray(512); bytes[2] = 5; bytes[5] = 5; bytes[6] = 4
        val table = info(raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertRow(table, "05", "05", "U", "5", "4", "-", "000000000000", "R", "重新分配扇区数")
    }

    @Test fun vendorAtaIdsAreNotLabeledWithAssumedMeanings() {
        val bytes = ByteArray(512); bytes[2] = 0xE7.toByte(); bytes[5] = 100; bytes[14] = 0xF1.toByte(); bytes[17] = 100
        val table = info(raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertRow(table, "E7", "E7", "U", "100", "0", "-", "000000000000", "R", "厂商属性 0xE7（含义未验证）")
        assertRow(table, "F1", "F1", "U", "100", "0", "-", "000000000000", "R", "厂商属性 0xF1（含义未验证）")
    }

    @Test fun nvmeWithoutRawPageUsesKnownFieldsAndUnknownMarkers() {
        val table = info(type = "NVMe SSD", temperature = 40, health = 100, errors = 0).formattedRawData
        assertRow(table, "01", "01", "U", "-", "-", "-", "-", "U", "严重警告标志")
        assertRow(table, "02", "02", "U", "-", "-", "-", "000000000139", "D", "温度")
        assertRow(table, "05", "05", "G", "-", "-", "-", "000000000000", "D", "已用寿命百分比")
        assertRow(table, "0B", "0B", "U", "-", "-", "-", "-", "U", "通电次数")
        assertTrue(table.contains("Src: 数据来源(R: 设备原始页 | S: 摘要直接字段 | D: 摘要换算 | U: 未知)"))
    }

    @Test fun nvmeCompleteRawPagePreservesExplicitZeroAndUsesNoCurWorThrValues() {
        val raw = ByteArray(512).joinToString("") { "00" }
        val table = info(type = "NVMe SSD", raw = raw, temperature = 40).formattedRawData
        assertRow(table, "01", "01", "G", "-", "-", "-", "000000000000", "R", "严重警告标志")
        assertRow(table, "02", "02", "U", "-", "-", "-", "-", "U", "温度")
        assertRow(table, "0E", "0E", "G", "-", "-", "-", "00000000000000000000000000000000", "R", "媒体与数据完整性错误计数")
    }

    @Test fun nvmeCountersKeepAll128BitsFromRawPageAndExactFields() {
        val bytes = ByteArray(512); bytes[40] = 1; bytes[63] = 0x80.toByte()
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val rawTable = info(type = "NVMe SSD", raw = raw).formattedRawData
        assertEquals("00000000000000010000000000000000", cells(rawTable, "06")[5])
        assertEquals("80000000000000000000000000000000", cells(rawTable, "07")[5])

        val exact = BigInteger.ONE.shiftLeft(127)
        val fromExact = info(type = "NVMe SSD").copy(exactCounters = mapOf(SmartCounter.MEDIA_ERRORS to exact))
        val exactTable = fromExact.formattedRawData
        assertRow(exactTable, "0E", "0E", "W", "-", "-", "-", "80000000000000000000000000000000", "S", "媒体与数据完整性错误计数")
        assertTrue(fromExact.counterValue(SmartCounter.MEDIA_ERRORS) == exact)
    }

    @Test fun almostCompleteNvmePageIsRejectedAsTruncated() {
        val raw = ByteArray(511).joinToString("") { "00" }
        val table = info(type = "NVMe SSD", raw = raw).formattedRawData
        assertRow(table, "01", "01", "U", "-", "-", "-", "-", "U", "严重警告标志")
        assertRow(table, "0E", "0E", "U", "-", "-", "-", "-", "U", "媒体与数据完整性错误计数")
    }

    @Test fun nvmeWarningsUseExplicitThresholdRules() {
        val percentageUsed90 = info(type = "NVMe SSD", health = 10).formattedRawData
        assertEquals("00000000005A", cells(percentageUsed90, "05")[5])
        val zeroHealth = info(type = "NVMe SSD", health = 0).formattedRawData
        assertRow(zeroHealth, "05", "05", "U", "-", "-", "-", "-", "U", "已用寿命百分比")

        val bytes = ByteArray(512); bytes[3] = 10; bytes[4] = 20; bytes[5] = 100
        val spareBelowThreshold = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertRow(spareBelowThreshold, "03", "03", "W", "-", "-", "-", "00000000000A", "R", "可用备用空间")
        assertRow(spareBelowThreshold, "05", "05", "W", "-", "-", "-", "000000000064", "R", "已用寿命百分比")
        bytes[0] = 1
        val criticalSpare = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertEquals("B", cells(criticalSpare, "03")[1])
    }

    @Test fun unalignedSummaryBytesAreNotRoundedIntoNvmeRawValues() {
        val info = info(type = "NVMe SSD").copy(exactCounters = mapOf(SmartCounter.TOTAL_BYTES_READ to BigInteger.valueOf(512_001)))
        assertRow(info.formattedRawData, "06", "06", "U", "-", "-", "-", "-", "U", "主机总计读取")
    }

    @Test fun controllerWarningsStillTakePriorityAfterAddingSources() {
        val bytes = ByteArray(512); bytes[0] = 3; bytes[1] = 0x3A; bytes[2] = 1; bytes[3] = 10; bytes[4] = 10
        val table = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertRow(table, "02", "02", "B", "-", "-", "-", "00000000013A", "R", "温度")
        assertEquals("B", cells(table, "03")[1])
    }

    @Test fun unknownAndLongRawValuesKeepEveryAsciiColumnAligned() {
        val info = info(type = "NVMe SSD").copy(exactCounters = mapOf(
            SmartCounter.MEDIA_ERRORS to BigInteger.ONE.shiftLeft(127),
            SmartCounter.HOST_READ_COMMANDS to BigInteger.valueOf(5)
        ))
        val lines = info.formattedRawData.lines()
        val header = lines.first { it.contains("RawValues") }
        val sample = listOf("0E", "08", "0B").map { id -> lines.first { it.trimStart().startsWith("$id |") } }
        fun boundaries(line: String): List<Int> = Regex("\\|").findAll(line).map { it.range.first }.toList()
        assertEquals(7, boundaries(header).size)
        assertTrue(sample.all { boundaries(it) == boundaries(header) })
        assertEquals("80000000000000000000000000000000", cells(info.formattedRawData, "0E")[5])
        assertEquals("000000000005", cells(info.formattedRawData, "08")[5])
        assertEquals("-", cells(info.formattedRawData, "0B")[5])
        assertTrue(cells(info.formattedRawData, "0E").last().startsWith("媒体"))
    }

    @Test fun unconfirmedAndUnsupportedHaveDifferentExplanations() {
        val unconfirmed = info(status = SmartOverallStatus.UNCONFIRMED, supported = false).formattedRawData
        assertTrue(unconfirmed.contains("状态尚未确认"))
        assertFalse(unconfirmed.contains("当前设备不支持"))
        val unsupported = info(status = SmartOverallStatus.UNSUPPORTED, supported = false).formattedRawData
        assertTrue(unsupported.contains("当前设备不支持标准 S.M.A.R.T. 硬件监控。"))
    }
}
