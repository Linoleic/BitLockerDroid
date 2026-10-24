package com.bitlockerdroid.smart

import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartCounterPrecisionTest {
    @Test fun rootDecimalStringsPreserveEveryBitAndScaledByteCount() {
        val units = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val json = JSONObject().put("supported", true).put("status", "HEALTHY").put("disk_type", "NVMe SSD")
        for (counter in SmartCounter.entries) json.put(counter.jsonKey, units.multiply(BigInteger.valueOf(counter.scale)).toString())
        val info = SmartDataParser.parseJson(json.toString())
        for (counter in SmartCounter.entries) {
            assertEquals(counter.name, units.multiply(BigInteger.valueOf(counter.scale)), info.counterValue(counter))
        }
        assertNull(info.powerHours) // Legacy Long fields must not contain a truncated or saturated number.
        assertNull(info.totalBytesWritten)
        assertTrue(info.totalTbWritten!!.isFinite())
    }

    @Test fun malformedOrOutOfRangeExactCountersStayUnknownAndZeroSurvives() {
        for (value in listOf("-1", "1.5", "oops", "null", BigInteger.ONE.shiftLeft(128).toString())) {
            val json = JSONObject().put("supported", true).put("power_hours", value)
            val info = SmartDataParser.parseJson(json.toString())
            assertNull(value, info.counterValue(SmartCounter.POWER_HOURS))
        }
        val zero = SmartDataParser.parseJson("""{"supported":true,"power_hours":"0"}""")
        assertEquals(BigInteger.ZERO, zero.counterValue(SmartCounter.POWER_HOURS))
        assertEquals(0L, zero.powerHours)
    }

    @Test fun fullPageCounterReaderPreservesUnsignedHighBitsAndByteScale() {
        for (bit in listOf(48, 63, 64, 127)) {
            val page = ByteArray(512)
            for (counter in SmartCounter.entries) page[counter.offset + bit / 8] = (1 shl (bit % 8)).toByte()
            for (counter in SmartCounter.entries) {
                assertEquals(BigInteger.ONE.shiftLeft(bit).multiply(BigInteger.valueOf(counter.scale)), counter.readNvme(page))
            }
        }
    }

    @Test fun nvmeSeverityUsesControllerWarningsAndExactBoundaryRules() {
        assertEquals(SmartOverallStatus.HEALTHY, nvmeHealthStatus(0, 10, 10, 99))
        assertEquals(SmartOverallStatus.HEALTHY, nvmeHealthStatus(0, 0, 0, 0))
        assertEquals(SmartOverallStatus.WARNING, nvmeHealthStatus(0, 9, 10, 99))
        assertEquals(SmartOverallStatus.WARNING, nvmeHealthStatus(0, 100, 10, 100))
        assertEquals(SmartOverallStatus.WARNING, nvmeHealthStatus(0, 100, 10, 255))
        for (bit in 0..5) assertEquals(SmartOverallStatus.CRITICAL, nvmeHealthStatus(1 shl bit, 100, 10, 0))
    }
}
