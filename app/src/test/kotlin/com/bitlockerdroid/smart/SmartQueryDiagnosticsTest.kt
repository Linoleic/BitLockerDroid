package com.bitlockerdroid.smart

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartQueryDiagnosticsTest {
    private fun ataPage(): ByteArray = ByteArray(512).apply {
        this[0] = 1
        this[2] = 5
        this[5] = 100
        this[6] = 90
        this[511] = (-take(511).sumOf { it.toInt() and 0xff }).toByte()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun response(page: ByteArray, type: String = "SATA SSD", integrity: String? = null): String =
        JSONObject().put("supported", true).put("status", "HEALTHY").put("disk_type", type)
            .put("health_percent", 100).put("power_hours", "123").put("temperature_c", 35)
            .put("raw_page_hex", page.hex()).also { if (integrity != null) it.put("data_integrity", integrity) }.toString()

    @Test fun ataChecksumRejectsBitFlipsEmptyAndTruncatedPages() {
        val valid = ataPage()
        assertEquals(SmartDataIntegrity.ATA_CHECKSUM_PASSED, SmartPageValidation.ata(valid))
        assertEquals(SmartDataIntegrity.ATA_CHECKSUM_FAILED, SmartPageValidation.ata(valid.copyOf().also { it[100] = 1 }))
        for (page in listOf(ByteArray(512), ByteArray(512) { 0xff.toByte() }, valid.copyOf(511)))
            assertEquals(SmartDataIntegrity.INVALID_PAGE, SmartPageValidation.ata(page))
    }

    @Test fun parserRechecksNativeClaimsAndRemovesUntrustedHealthMetrics() {
        val bad = ataPage().also { it[100] = 1 }
        val info = SmartDataParser.parseJson(response(bad, integrity = "ATA_CHECKSUM_PASSED"))
        assertFalse(info.isSupported)
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals(SmartFailureStage.VALIDATION, info.failureStage)
        assertEquals(SmartDataIntegrity.ATA_CHECKSUM_FAILED, info.dataIntegrity)
        assertNull(info.healthPercentage)
        assertNull(info.temperatureCelsius)
        assertNull(info.powerHours)
        assertTrue(info.exactCounters.isEmpty())
        assertEquals(bad.hex(), info.rawPageHex)
        assertTrue(info.formattedRawData.contains("不进行健康解读"))
        assertTrue(info.formattedRawData.contains("0000: " + bad.hex().take(32)))
        assertFalse(info.formattedRawData.contains("Cur Wor Thr"))
    }

    @Test fun validAtaPageIsNotMistakenForAHealthyDrive() {
        val json = JSONObject(response(ataPage())).put("status", "UNCONFIRMED").toString()
        val info = SmartDataParser.parseJson(json)
        assertTrue(info.isSupported)
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals(SmartDataIntegrity.ATA_CHECKSUM_PASSED, info.dataIntegrity)
        assertNull(info.failureStage)
    }

    @Test fun nvmeStructuralValidationDoesNotApplyAtaChecksum() {
        val page = ByteArray(512).apply { this[1] = 0x3a; this[2] = 1; this[3] = 100; this[4] = 10 }
        assertNotEquals(0, page.sumOf { it.toInt() and 0xff } and 0xff)
        val info = SmartDataParser.parseJson(response(page, "NVMe SSD", "NVME_VALIDATED"))
        assertTrue(info.isSupported)
        assertEquals(SmartDataIntegrity.NVME_VALIDATED, info.dataIntegrity)
        page[3] = 101
        val invalid = SmartDataParser.parseJson(response(page, "NVMe SSD", "NVME_VALIDATED"))
        assertFalse(invalid.isSupported)
        assertEquals(SmartDataIntegrity.INVALID_PAGE, invalid.dataIntegrity)
    }

    @Test fun checksumClaimsWithoutRawDataAreNotPresentedAsVerified() {
        val info = SmartDataParser.parseJson("""{"supported":true,"data_integrity":"ATA_CHECKSUM_PASSED"}""")
        assertEquals(SmartDataIntegrity.NOT_CHECKED, info.dataIntegrity)
    }

    @Test fun failureStagesSurviveParsingAndRootAuthorizationIsDistinctFromReadFailure() {
        for (stage in SmartFailureStage.entries) {
            val info = SmartDataParser.parseJson("""{"supported":false,"status":"UNCONFIRMED","failure_stage":"${stage.name}"}""")
            assertEquals(stage, info.failureStage)
        }
        for (stage in listOf(SmartFailureStage.AUTHORIZATION, SmartFailureStage.READ)) {
            val info = SmartHealthManager.queryLocalSmart("node", true, "Retry",
                rootQuery = { throw SmartQueryException(stage) }, directQuery = { error("No fallback") })
            assertEquals(stage, info.failureStage)
        }
        assertEquals(SmartFailureStage.RESPONSE, SmartDataParser.parseJson("garbage").failureStage)
    }

    @Test fun elapsedTimeUsesMonotonicClockAndFailureAlsoGetsATimestamp() = runBlocking {
        var tick = 0
        val info = SmartHealthManager.measuredQuery(nowMillis = { 1234L },
            monotonicNanos = { if (tick++ == 0) 5_000_000_000L else 6_250_000_000L }) {
            throw SecurityException("Denied")
        }
        assertEquals(1234L, info.queryCompletedAtMillis)
        assertEquals(1250L, info.queryDurationMillis)
        assertEquals(SmartFailureStage.AUTHORIZATION, info.failureStage)
    }

    @Test fun cancellationIsNeverConvertedToACompletedFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking { SmartHealthManager.measuredQuery { throw CancellationException() } }
        }
    }
}
