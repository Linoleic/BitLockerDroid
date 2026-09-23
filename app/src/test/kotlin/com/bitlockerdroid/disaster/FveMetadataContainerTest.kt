package com.bitlockerdroid.disaster

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FveMetadataContainerTest {

    @Test
    fun testParseHeaderAndIntegrityVerification() {
        // Construct a mock .fvemeta container
        val headerLen = FveMetadataContainer.HEADER_LEN
        val vbrLen = 512
        val payloadLen = vbrLen + 64 // mock payload
        val totalLen = headerLen + payloadLen

        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Magic
        buffer.put(FveMetadataContainer.MAGIC.toByteArray(Charsets.US_ASCII))
        // 2. Version
        buffer.putInt(1)
        // 3. Header len
        buffer.putInt(headerLen)
        // 4. Volume GUID (36 chars)
        val guid = "56e8e9c9-8280-4d3c-9406-058ad02679f8"
        val guidBytes = ByteArray(36)
        System.arraycopy(guid.toByteArray(Charsets.US_ASCII), 0, guidBytes, 0, 36)
        buffer.put(guidBytes)
        // 5. Disk Label (64 chars)
        val labelBytes = ByteArray(64)
        val labelStr = "Test Disk"
        System.arraycopy(labelStr.toByteArray(Charsets.UTF_8), 0, labelBytes, 0, labelStr.length)
        buffer.put(labelBytes)
        // 6. Sector size
        buffer.putInt(512)
        // 7. Volume size
        val volumeSize = 4820000000L
        buffer.putLong(volumeSize)
        // 8. Timestamp
        val now = System.currentTimeMillis()
        buffer.putLong(now)

        // Fill payload
        val payload = ByteArray(payloadLen) { (it % 256).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val sha256 = digest.digest(payload)

        // 9. Payload SHA-256
        buffer.put(sha256)

        // Write payload
        buffer.put(payload)

        val fullData = buffer.array()

        // Test parseHeader
        val parsed = FveMetadataContainer.parseHeader(fullData)
        assertNotNull(parsed)
        assertEquals(FveMetadataContainer.MAGIC, parsed!!.magic)
        assertEquals(1, parsed.version)
        assertEquals(headerLen, parsed.headerLen)
        assertEquals(guid, parsed.volumeGuid)
        assertEquals("Test Disk", parsed.diskLabel)
        assertEquals(512, parsed.sectorSize)
        assertEquals(volumeSize, parsed.volumeSize)
        assertEquals(now, parsed.timestampMs)
        assertArrayEquals(sha256, parsed.payloadSha256)

        // Test verifyIntegrity
        assertTrue(FveMetadataContainer.verifyIntegrity(fullData))

        // Tamper with one byte in payload
        fullData[headerLen + 10] = (fullData[headerLen + 10] + 1).toByte()
        assertFalse(FveMetadataContainer.verifyIntegrity(fullData))
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", FveMetadataContainer.formatBytes(0L))
        assertEquals("512.00 B", FveMetadataContainer.formatBytes(512L))
        assertEquals("1.00 KB", FveMetadataContainer.formatBytes(1024L))
        assertEquals("1.50 MB", FveMetadataContainer.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("4.82 GB", FveMetadataContainer.formatBytes(5175435264L))
    }
}
