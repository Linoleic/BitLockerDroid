package com.bitlockerdroid.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDataParserTest {
    private fun row(table: String, id: String): List<String> = table.lineSequence()
        .first { it.trimStart().startsWith("$id |") }
        .split(" | ").map(String::trim)

    @Test
    fun testParseNvmeSmartJsonAndBuildAttributeTable() {
        val json = """
            {
                "supported": true,
                "status": "HEALTHY",
                "disk_type": "NVMe SSD",
                "model": "ZHITAI Ti600 1TB",
                "serial": "ZTA601TAB24113ETED",
                "firmware": "ZTA23002",
                "temperature_c": 41,
                "health_percent": 99,
                "available_spare": 99,
                "spare_threshold": 1,
                "critical_warning": 0,
                "power_cycles": 389,
                "power_hours": 648,
                "unsafe_shutdowns": 153,
                "total_bytes_read": 870959448064,
                "total_bytes_written": 6243622912000,
                "host_read_commands": 126926292,
                "host_write_commands": 80840077,
                "controller_busy_time": 548,
                "media_errors": 14,
                "error_log_entries": 5,
                "device_node": "/dev/block/sdg"
            }
        """.trimIndent()

        val info = SmartDataParser.parseJson(json)
        assertTrue(info.isSupported)
        assertEquals(SmartOverallStatus.HEALTHY, info.status)
        assertEquals(41, info.temperatureCelsius)
        assertEquals(99, info.healthPercentage)
        assertEquals(14L, info.mediaErrors)
        assertEquals(5L, info.errorLogEntries)

        val table = info.formattedRawData
        assertTrue(table.contains("-- S.M.A.R.T. --------------------------------------------------------------"))
        assertTrue(table.lines().any { it.contains("ID") && it.contains("RawValues") && it.contains("Src") && it.contains("AttributeName") })
        assertEquals(listOf("01", "G", "-", "-", "-", "000000000000", "S", "严重警告标志"), row(table, "01"))
        assertEquals(listOf("02", "U", "-", "-", "-", "00000000013A", "D", "温度"), row(table, "02"))
        assertEquals(listOf("03", "G", "-", "-", "-", "000000000063", "S", "可用备用空间"), row(table, "03"))
        assertEquals(listOf("04", "G", "-", "-", "-", "000000000001", "S", "可用备用空间阈值"), row(table, "04"))
        assertEquals(listOf("05", "G", "-", "-", "-", "000000000001", "D", "已用寿命百分比"), row(table, "05"))
        assertEquals(listOf("0B", "U", "-", "-", "-", "000000000185", "S", "通电次数"), row(table, "0B"))
        assertEquals(listOf("0C", "U", "-", "-", "-", "000000000288", "S", "通电时间(小时)"), row(table, "0C"))
        assertEquals(listOf("0D", "U", "-", "-", "-", "000000000099", "S", "不安全关机计数"), row(table, "0D"))
        assertEquals(listOf("0E", "W", "-", "-", "-", "00000000000E", "S", "媒体与数据完整性错误计数"), row(table, "0E"))
        assertEquals(listOf("0F", "W", "-", "-", "-", "000000000005", "S", "错误日志项数"), row(table, "0F"))
        assertTrue(table.contains("标题含义说明:"))
        assertTrue(table.contains(" Sta: 状态(G: 良好 | W: 警告 | B: 严重告警 | U: 未知)"))
        assertTrue(table.contains(" Cur: 当前值"))
        assertTrue(table.contains(" Wor: 历史最差值"))
        assertTrue(table.contains(" Thr: 临界值"))
        assertTrue(table.contains(" RawValues: 十六进制数值，来源见 Src"))
        assertTrue(table.contains(" AttributeName: 属性名称"))
        assertTrue(table.contains(" Src: 数据来源(R: 设备原始页 | S: 摘要直接字段 | D: 摘要换算 | U: 未知)"))
        assertTrue(table.contains(" D 表示摘要换算值，不是设备原始页数据。"))
    }

    @Test
    fun unconfirmedUsbResponseDoesNotClaimHardwareIsUnsupported() {
        val info = SmartDataParser.parseJson("""{
            "supported": false,
            "status": "UNCONFIRMED",
            "reason": "USB bridge has no verified query route"
        }""")

        assertFalse(info.isSupported)
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals("USB bridge has no verified query route", info.reason)
    }

    @Test
    fun testParseUnsupportedSmartJson() {
        val json = """
            {
                "supported": false,
                "reason": "Drive does not support ATA SMART or NVMe Telemetry",
                "vendor": "Generic",
                "product": "Flash Disk",
                "device_node": "/dev/block/sda"
            }
        """.trimIndent()

        val info = SmartDataParser.parseJson(json)
        assertFalse(info.isSupported)
        assertEquals(SmartOverallStatus.UNSUPPORTED, info.status)
        assertEquals("Drive does not support ATA SMART or NVMe Telemetry", info.reason)
    }

    @Test
    fun testParseSataSmartJsonAndShowUnknownRawAtaFields() {
        val json = """
            {
                "supported": true,
                "status": "HEALTHY",
                "disk_type": "SATA SSD",
                "model": "Samsung SSD 870 EVO 1TB",
                "serial": "S5Y2NJ0R123456",
                "temperature_c": 32,
                "health_percent": 98,
                "power_cycles": 120,
                "power_hours": 2400,
                "reallocated_sectors": 0,
                "pending_sectors": 0,
                "uncorrectable_sectors": 0,
                "total_bytes_written": 10485760000,
                "device_node": "/dev/block/sda"
            }
        """.trimIndent()

        val info = SmartDataParser.parseJson(json)
        assertTrue(info.isSupported)
        assertEquals("SATA SSD", info.diskType)
        val table = info.formattedRawData
        assertFalse(table.contains("01  "))
        assertEquals(listOf("05", "G", "-", "-", "-", "000000000000", "S", "重新分配扇区数"), row(table, "05"))
        assertFalse(table.contains("E7  "))
        assertEquals(listOf("C2", "U", "-", "-", "-", "000000000020", "S", "温度"), row(table, "C2"))
        assertTrue(table.contains("SATA 属性名称仅供参考，厂商定义可能不同。"))
        assertFalse(table.contains("F1  "))
        assertFalse(table.contains("100"))
        assertFalse(table.contains("000000000000 通电时间"))
    }
}
