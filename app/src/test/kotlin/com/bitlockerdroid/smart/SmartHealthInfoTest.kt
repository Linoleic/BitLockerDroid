package com.bitlockerdroid.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class SmartHealthInfoTest {
    private fun info(
        type: String = "SATA SSD",
        raw: String? = null,
        status: SmartOverallStatus = SmartOverallStatus.HEALTHY,
        supported: Boolean = true,
        temperature: Int? = null,
        health: Int? = null,
        spare: Int? = null,
        spareThreshold: Int? = null,
        criticalWarning: Int? = null,
        reallocated: Long? = null,
        pending: Long? = null,
        errors: Long? = null,
        powerHours: Long? = null
    ) = SmartHealthInfo(
        isSupported = supported, status = status, diskType = type,
        temperatureCelsius = temperature, healthPercentage = health,
        availableSpare = spare, spareThreshold = spareThreshold, criticalWarning = criticalWarning,
        reallocatedSectors = reallocated, pendingSectors = pending,
        uncorrectableSectors = errors, powerHours = powerHours, rawPageHex = raw
    )

    @Test fun absentAtaFieldsAreUnknownAndRealZeroIsPreserved() {
        val table = info(reallocated = 0, pending = 0, errors = 0).formattedRawData
        assertTrue(table.contains("05  G  — — — 000000000000 重新分配扇区数 S"))
        assertTrue(table.contains("09  U  — — — — 通电时间(小时) U"))
        assertTrue(table.contains("C5  G  — — — 000000000000 当前待映射扇区数 S"))
        assertTrue(table.contains("C6  G  — — — 000000000000 脱机无法纠正扇区数 S"))
        assertFalse(table.contains("35"))
        assertFalse(table.contains("100"))
        assertFalse(table.contains("_50"))
    }

    @Test fun structuredFieldsAndMalformedOrTruncatedPagesDoNotCrashOrInventValues() {
        for (raw in listOf("XYZ" + "0".repeat(720), "0".repeat(719))) {
            val table = info(raw = raw, temperature = 0, powerHours = 0).formattedRawData
            assertTrue(table.contains("09  U  — — — 000000000000 通电时间(小时) S"))
            assertTrue(table.contains("C2  U  — — — 000000000000 温度 S"))
            assertTrue(table.contains("05  U  — — — — 重新分配扇区数 U"))
        }
    }

    @Test fun validAtaRawPagePreservesRawCountersAndDoesNotInventThreshold() {
        val bytes = ByteArray(512)
        bytes[2] = 5
        bytes[5] = 100
        bytes[6] = 90
        bytes[7] = 0
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val table = info(raw = raw, reallocated = 9, powerHours = 222).formattedRawData
        assertTrue(table.contains("05  U  100 _90 — 000000000000 重新分配扇区数 R"))
        assertFalse(table.contains("000000000009 重新分配扇区数"))
        assertTrue(table.contains("09  U  — — — — 通电时间(小时) U"))
    }

    @Test fun lowAtaNormalizedValuesRemainUnknownWithoutThresholdPage() {
        val bytes = ByteArray(512)
        bytes[2] = 5
        bytes[5] = 5
        bytes[6] = 4
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val table = info(raw = raw).formattedRawData
        assertTrue(table.contains("05  U  __5 __4 — 000000000000 重新分配扇区数 R"))
    }

    @Test fun vendorAtaIdsAreNotLabeledWithAssumedMeanings() {
        val bytes = ByteArray(512)
        bytes[2] = 0xE7.toByte()
        bytes[5] = 100
        bytes[14] = 0xF1.toByte()
        bytes[17] = 100
        val table = info(raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertTrue(table.contains("E7  U  100 __0 — 000000000000 厂商属性 0xE7（含义未验证） R"))
        assertTrue(table.contains("F1  U  100 __0 — 000000000000 厂商属性 0xF1（含义未验证） R"))
    }

    @Test fun nvmeWithoutRawPageUsesKnownFieldsAndUnknownMarkers() {
        val table = info(type = "NVMe SSD", temperature = 40, health = 100, errors = 0).formattedRawData
        assertTrue(table.contains("01  U  — — — — 严重警告标志 U"))
        assertTrue(table.contains("02  U  — — — 000000000139 温度 D"))
        assertTrue(table.contains("05  G  — — — 000000000000 已用寿命百分比 D"))
        assertTrue(table.contains("0B  U  — — — — 通电次数 U"))
        assertTrue(table.contains(" Src: 数据来源(R: 设备原始页 | S: 摘要直接字段 | D: 摘要换算 | U: 未知)"))
    }

    @Test fun nvmeCompleteRawPagePreservesExplicitZeroAndUsesNoCurWorThrValues() {
        val raw = ByteArray(512).joinToString("") { "00" }
        val table = info(type = "NVMe SSD", raw = raw, temperature = 40).formattedRawData
        assertTrue(table.contains("01  G  — — — 000000000000 严重警告标志 R"))
        assertTrue(table.contains("02  U  — — — — 温度 U"))
        assertTrue(table.contains("0E  G  — — — 00000000000000000000000000000000 媒体与数据完整性错误计数 R"))
    }

    @Test fun nvmeCountersKeepAll128BitsFromRawPageAndExactFields() {
        val bytes = ByteArray(512)
        bytes[32 + 8] = 1 // 2^64 little-endian host data units read
        bytes[48 + 15] = 0x80.toByte() // 2^127 host data units written
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val rawTable = info(type = "NVMe SSD", raw = raw).formattedRawData
        assertTrue(rawTable.contains("00000000000000010000000000000000 主机总计读取 R"))
        assertTrue(rawTable.contains("80000000000000000000000000000000 主机总计写入 R"))

        val exact = BigInteger.ONE.shiftLeft(127)
        val fromExact = info(type = "NVMe SSD").copy(exactCounters = mapOf(SmartCounter.MEDIA_ERRORS to exact))
        val exactTable = fromExact.formattedRawData
        assertTrue(exactTable.contains("0E  W  — — — 80000000000000000000000000000000 媒体与数据完整性错误计数 S"))
        assertTrue(fromExact.counterValue(SmartCounter.MEDIA_ERRORS) == exact)
    }

    @Test fun almostCompleteNvmePageIsRejectedAsTruncated() {
        val raw = ByteArray(511).joinToString("") { "00" }
        val table = info(type = "NVMe SSD", raw = raw).formattedRawData
        assertTrue(table.contains("01  U  — — — — 严重警告标志 U"))
        assertTrue(table.contains("0E  U  — — — — 媒体与数据完整性错误计数 U"))
    }

    @Test fun nvmeWarningsUseExplicitThresholdRules() {
        val percentageUsed90 = info(type = "NVMe SSD", health = 10).formattedRawData
        assertTrue(percentageUsed90.contains("05  G  — — — 00000000005A 已用寿命百分比 D"))
        val zeroHealth = info(type = "NVMe SSD", health = 0).formattedRawData
        assertTrue(zeroHealth.contains("05  U  — — — — 已用寿命百分比 U"))

        val bytes = ByteArray(512)
        bytes[3] = 10
        bytes[4] = 20
        bytes[5] = 100
        val spareBelowThreshold = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) })
            .formattedRawData
        assertTrue(spareBelowThreshold.contains("03  W  — — — 00000000000A 可用备用空间 R"))
        assertTrue(spareBelowThreshold.contains("05  W  — — — 000000000064 已用寿命百分比 R"))

        bytes[0] = 1
        val criticalSpare = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) })
            .formattedRawData
        assertTrue(criticalSpare.contains("03  B  — — — 00000000000A 可用备用空间 R"))
    }

    @Test fun unalignedSummaryBytesAreNotRoundedIntoNvmeRawValues() {
        val info = info(type = "NVMe SSD").copy(
            exactCounters = mapOf(SmartCounter.TOTAL_BYTES_READ to BigInteger.valueOf(512_001))
        )
        assertTrue(info.formattedRawData.contains("06  U  — — — — 主机总计读取 U"))
    }

    @Test fun controllerWarningsStillTakePriorityAfterAddingSources() {
        val bytes = ByteArray(512)
        bytes[0] = 3 // Controller explicitly reports both spare and temperature warnings.
        bytes[1] = 0x3A // 314 K
        bytes[2] = 1
        bytes[3] = 10
        bytes[4] = 10 // Equality must not suppress the controller warning bit.
        val table = info(type = "NVMe SSD", raw = bytes.joinToString("") { "%02X".format(it) }).formattedRawData
        assertTrue(table.contains("02  B  — — — 00000000013A 温度 R"))
        assertTrue(table.contains("03  B  — — — 00000000000A 可用备用空间 R"))
    }

    @Test fun unconfirmedAndUnsupportedHaveDifferentExplanations() {
        val unconfirmed = info(status = SmartOverallStatus.UNCONFIRMED, supported = false).formattedRawData
        assertTrue(unconfirmed.contains("状态尚未确认"))
        assertFalse(unconfirmed.contains("当前设备不支持"))
        val unsupported = info(status = SmartOverallStatus.UNSUPPORTED, supported = false).formattedRawData
        assertTrue(unsupported.contains("当前设备不支持标准 S.M.A.R.T. 硬件监控。"))
    }
}
