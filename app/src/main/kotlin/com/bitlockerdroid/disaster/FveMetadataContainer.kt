package com.bitlockerdroid.disaster

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Specification and parsing utilities for BitLockerDroid's .fvemeta disaster recovery containers.
 */
object FveMetadataContainer {

    const val MAGIC = "BLDFVEMETA\u0000"
    const val MAGIC_LEN = 11
    const val VERSION = 1
    const val HEADER_LEN = 171

    data class Header(
        val magic: String,
        val version: Int,
        val headerLen: Int,
        val volumeGuid: String,
        val diskLabel: String,
        val sectorSize: Int,
        val volumeSize: Long,
        val timestampMs: Long,
        val payloadSha256: ByteArray
    ) {
        val formattedDate: String
            get() = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
            } catch (e: Exception) {
                "Unknown"
            }

        val formattedVolumeSize: String
            get() = formatBytes(volumeSize)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return magic == other.magic &&
                    version == other.version &&
                    headerLen == other.headerLen &&
                    volumeGuid == other.volumeGuid &&
                    diskLabel == other.diskLabel &&
                    sectorSize == other.sectorSize &&
                    volumeSize == other.volumeSize &&
                    timestampMs == other.timestampMs &&
                    payloadSha256.contentEquals(other.payloadSha256)
        }

        override fun hashCode(): Int {
            var result = magic.hashCode()
            result = 31 * result + version
            result = 31 * result + headerLen
            result = 31 * result + volumeGuid.hashCode()
            result = 31 * result + diskLabel.hashCode()
            result = 31 * result + sectorSize
            result = 31 * result + volumeSize.hashCode()
            result = 31 * result + timestampMs.hashCode()
            result = 31 * result + payloadSha256.contentHashCode()
            return result
        }
    }

    fun parseHeader(data: ByteArray): Header? {
        if (data.size < HEADER_LEN) return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(MAGIC_LEN)
        bb.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != MAGIC) return null

        val version = bb.int
        if (version != VERSION) return null

        val headerLen = bb.int
        if (headerLen != HEADER_LEN || headerLen > data.size) return null

        val guidBytes = ByteArray(36)
        bb.get(guidBytes)
        val volumeGuid = String(guidBytes, Charsets.US_ASCII).trimEnd('\u0000', ' ')

        val labelBytes = ByteArray(64)
        bb.get(labelBytes)
        val diskLabel = String(labelBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')

        val sectorSize = bb.int
        val volumeSize = bb.long
        val timestampMs = bb.long

        val sha256 = ByteArray(32)
        bb.get(sha256)

        return Header(
            magic = magic,
            version = version,
            headerLen = headerLen,
            volumeGuid = volumeGuid,
            diskLabel = diskLabel,
            sectorSize = sectorSize,
            volumeSize = volumeSize,
            timestampMs = timestampMs,
            payloadSha256 = sha256
        )
    }

    fun verifyIntegrity(data: ByteArray): Boolean {
        val header = parseHeader(data) ?: return false
        if (data.size <= header.headerLen) return false

        val payloadLen = data.size - header.headerLen
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, header.headerLen, payloadLen)
        val computed = digest.digest()

        return computed.contentEquals(header.payloadSha256)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var idx = 0
        while (b >= 1024.0 && idx < units.size - 1) {
            b /= 1024.0
            idx++
        }
        return String.format(Locale.US, "%.2f %s", b, units[idx])
    }
}
