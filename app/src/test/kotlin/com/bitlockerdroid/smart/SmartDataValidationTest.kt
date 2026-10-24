package com.bitlockerdroid.smart

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SmartDataValidationTest {
    @Test fun invalidResponsesCannotEstablishUnsupportedHardware() {
        for (json in listOf(null, "", "garbage", "{}", "{\"supported\":\"false\"}",
            "{\"supported\":false,\"error\":\"Permission denied\"}",
            "{\"supported\":false,\"status\":\"HEALTHY\"}",
            "{\"supported\":false,\"status\":\"unexpected\"}")) {
            val info = SmartDataParser.parseJson(json)
            assertEquals(json, SmartOverallStatus.UNCONFIRMED, info.status)
            assertNull(SmartHealthManager.supportEvidence(info))
        }
        val unsupported = SmartDataParser.parseJson("{\"supported\":false}")
        assertEquals(SmartOverallStatus.UNSUPPORTED, unsupported.status)
        assertEquals(false, SmartHealthManager.supportEvidence(unsupported))
    }

    @Test fun partialTelemetryPreservesSupportWithoutInventingGoodHealth() {
        val info = SmartDataParser.parseJson("""{"supported":true,"power_hours":0}""")
        assertTrue(info.isSupported)
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals(0L, info.powerHours)
        assertNull(info.healthPercentage)
        assertEquals(true, SmartHealthManager.supportEvidence(info))
    }

    @Test fun optionalNumbersRejectNullMalformedFractionalAndOverflowingValues() {
        val info = SmartDataParser.parseJson("""{
            "supported":true,"status":"HEALTHY","model":null,
            "temperature_c":null,"health_percent":"oops","power_cycles":false,
            "power_hours":1.5,"media_errors":9223372036854775808,
            "pending_sectors":-1,"available_spare":101,"total_bytes_written":"bad",
            "unsafe_shutdowns":0,"reallocated_sectors":"0","critical_warning":0
        }""")
        assertNull(info.model)
        assertNull(info.temperatureCelsius)
        assertNull(info.healthPercentage)
        assertNull(info.powerCycles)
        assertNull(info.powerHours)
        assertNull(info.mediaErrors)
        assertNull(info.pendingSectors)
        assertNull(info.availableSpare)
        assertNull(info.totalBytesWritten)
        assertEquals(0L, info.unsafeShutdowns)
        assertEquals(0L, info.reallocatedSectors)
        assertEquals(0, info.criticalWarning)
    }

    @Test fun rootFailureDoesNotIssueAnotherProbeOrHideRetryAsUnsupported() {
        for (response in listOf(null, "", "invalid")) {
            var rootCalls = 0
            var directCalls = 0
            val info = SmartHealthManager.queryLocalSmart("/dev/block/test", true, "Retry",
                rootQuery = { rootCalls++; response },
                directQuery = { directCalls++; error("Unexpected JNI probe") })
            assertEquals(1, rootCalls)
            assertEquals(0, directCalls)
            assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
            assertEquals("Retry", info.reason)
            assertEquals("/dev/block/test", info.deviceNode)
        }
    }

    @Test fun directQueryPreservesAuthoritativeResultAndReason() {
        val info = SmartHealthManager.queryLocalSmart("/dev/block/test", false, "Retry",
            rootQuery = { error("Unexpected root query") },
            directQuery = { """{"supported":false,"status":"UNCONFIRMED","reason":"Unverified bridge"}""" })
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals("Unverified bridge", info.reason)
    }

    @Test fun thrownQueryIsUnconfirmedButCancellationPropagates() {
        val info = SmartHealthManager.queryLocalSmart("node", true, "Retry",
            rootQuery = { throw SecurityException("No permission") }, directQuery = { null })
        assertEquals(SmartOverallStatus.UNCONFIRMED, info.status)
        assertEquals("Retry", info.reason)
        assertThrows(CancellationException::class.java) {
            SmartHealthManager.queryLocalSmart("node", true, "Retry",
                rootQuery = { throw CancellationException("Cancelled") }, directQuery = { null })
        }
    }
}
