package com.bitlockerdroid.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDataParserTest {

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
        assertTrue(table.contains("ID Sta Cur Wor Thr RawValues    AttributeName (标题含义见说明)"))
        assertTrue(table.contains("01  G  __0 __0 __0 000000000000 严重警告标志"))
        assertTrue(table.contains("02  G  __0 __0 __0 00000000013A 温度"))
        assertTrue(table.contains("03  G  __0 __0 __0 000000000063 可用备用空间"))
        assertTrue(table.contains("04  G  __0 __0 __0 000000000001 可用备用空间阈值"))
        assertTrue(table.contains("05  G  __0 __0 __0 000000000001 已用寿命百分比"))
        assertTrue(table.contains("0B  G  __0 __0 __0 000000000185 通电次数"))
        assertTrue(table.contains("0C  G  __0 __0 __0 000000000288 通电时间(小时)"))
        assertTrue(table.contains("0D  G  __0 __0 __0 000000000099 不安全关机计数"))
        assertTrue(table.contains("0E  B  __0 __0 __0 00000000000E 媒体与数据完整性错误计数"))
        assertTrue(table.contains("0F  W  __0 __0 __0 000000000005 错误日志项数"))
        assertTrue(table.contains("标题含义说明:"))
        assertTrue(table.contains(" Sta: 状态(G: 良好 | W: 警告 | B: 损坏 | U: 未知)"))
        assertTrue(table.contains(" Cur: 当前值"))
        assertTrue(table.contains(" Wor: 历史最差值"))
        assertTrue(table.contains(" Thr: 临界值"))
        assertTrue(table.contains(" RawValues: 原始数据"))
        assertTrue(table.contains(" AttributeName: 属性名称"))
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
    fun testParseSataSmartJsonAndSynthesizeTable() {
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
        assertTrue(table.contains("01  G  100 100 _50 000000000000 读取错误率"))
        assertTrue(table.contains("05  G  100 100 _10 000000000000 重新分配扇区数"))
        assertTrue(table.contains("E7  G  100 100 _10 000000000062 SSD 剩余寿命"))
    }
}
