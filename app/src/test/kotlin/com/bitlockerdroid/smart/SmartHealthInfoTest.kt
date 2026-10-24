package com.bitlockerdroid.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHealthInfoTest {
    private fun info(
        type: String = "SATA SSD",
        raw: String? = null,
        status: SmartOverallStatus = SmartOverallStatus.HEALTHY,
        supported: Boolean = true,
        temperature: Int? = null,
        health: Int? = null,
        reallocated: Long? = null,
        pending: Long? = null,
        errors: Long? = null,
        powerHours: Long? = null
    ) = SmartHealthInfo(
        isSupported = supported, status = status, diskType = type,
        temperatureCelsius = temperature, healthPercentage = health,
        reallocatedSectors = reallocated, pendingSectors = pending,
        uncorrectableSectors = errors, powerHours = powerHours, rawPageHex = raw
    )

    @Test fun absentAtaFieldsAreUnknownAndRealZeroIsPreserved() {
        val table = info(reallocated = 0, pending = 0, errors = 0).formattedRawData
        assertTrue(table.contains("05  G  — — — 000000000000 重新分配扇区数"))
        assertTrue(table.contains("09  U  — — — — 通电时间(小时)"))
        assertTrue(table.contains("C5  G  — — — 000000000000 当前待映射扇区数"))
        assertTrue(table.contains("C6  G  — — — 000000000000 脱机无法纠正扇区数"))
        assertFalse(table.contains("35"))
        assertFalse(table.contains("100"))
        assertFalse(table.contains("_50"))
    }

    @Test fun structuredFieldsAndMalformedOrTruncatedPagesDoNotCrashOrInventValues() {
        for (raw in listOf("XYZ" + "0".repeat(720), "0".repeat(719))) {
            val table = info(raw = raw, temperature = 0, powerHours = 0).formattedRawData
            assertTrue(table.contains("09  G  — — — 000000000000 通电时间(小时)"))
            assertTrue(table.contains("C2  G  — — — 000000000000 温度"))
            assertTrue(table.contains("05  U  — — — — 重新分配扇区数"))
        }
    }

    @Test fun validAtaRawPagePreservesRawCountersAndDoesNotInventThreshold() {
        val bytes = ByteArray(360)
        bytes[2] = 5
        bytes[5] = 100
        bytes[6] = 90
        bytes[7] = 0
        val raw = bytes.joinToString("") { "%02X".format(it) }
        val table = info(raw = raw).formattedRawData
        assertTrue(table.contains("05  G  100 _90 — 000000000000 重新分配扇区数"))
        assertTrue(table.contains("09  U  — — — — 通电时间(小时)"))
    }

    @Test fun nvmeWithoutRawPageUsesKnownFieldsAndUnknownMarkers() {
        val table = info(type = "NVMe SSD", temperature = 40, health = 100, errors = 0).formattedRawData
        assertTrue(table.contains("01  U  — — — — 严重警告标志"))
        assertTrue(table.contains("02  G  — — — 000000000028 温度"))
        assertTrue(table.contains("05  G  — — — 000000000000 已用寿命百分比"))
        assertTrue(table.contains("0B  U  — — — — 通电次数"))
        assertTrue(table.contains("000000000000 已用寿命百分比"))
    }

    @Test fun nvmeCompleteRawPagePreservesExplicitZeroAndUsesNoCurWorThrValues() {
        val raw = ByteArray(192).joinToString("") { "00" }
        val table = info(type = "NVMe SSD", raw = raw).formattedRawData
        assertTrue(table.contains("01  G  — — — 000000000000 严重警告标志"))
        assertTrue(table.contains("02  U  — — — — 温度"))
        assertTrue(table.contains("0E  G  — — — 000000000000 媒体与数据完整性错误计数"))
    }

    @Test fun unconfirmedAndUnsupportedHaveDifferentExplanations() {
        val unconfirmed = info(status = SmartOverallStatus.UNCONFIRMED, supported = false).formattedRawData
        assertTrue(unconfirmed.contains("状态尚未确认"))
        assertFalse(unconfirmed.contains("当前设备不支持"))
        val unsupported = info(status = SmartOverallStatus.UNSUPPORTED, supported = false).formattedRawData
        assertTrue(unsupported.contains("当前设备不支持标准 S.M.A.R.T. 硬件监控。"))
    }
}
