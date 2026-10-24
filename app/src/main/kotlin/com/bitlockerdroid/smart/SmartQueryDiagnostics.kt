package com.bitlockerdroid.smart

enum class SmartFailureStage { AUTHORIZATION, CONNECTION, BUSY, UNSUPPORTED_ROUTE, READ, VALIDATION, RESPONSE }

enum class SmartDataIntegrity { NOT_CHECKED, ATA_CHECKSUM_PASSED, ATA_CHECKSUM_FAILED, INVALID_PAGE, NVME_VALIDATED }

internal class SmartQueryException(val stage: SmartFailureStage) : Exception()

/** The checksum validates the received page, not drive health or vendor-specific attribute meanings. */
internal object SmartPageValidation {
    fun decodeHex(hex: String?): ByteArray? {
        if (hex == null || hex.length != 1024 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(512) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun ata(page: ByteArray?): SmartDataIntegrity = when {
        !hasContents(page) -> SmartDataIntegrity.INVALID_PAGE
        (page!!.sumOf { it.toInt() and 0xff } and 0xff) != 0 -> SmartDataIntegrity.ATA_CHECKSUM_FAILED
        else -> SmartDataIntegrity.ATA_CHECKSUM_PASSED
    }

    fun nvme(page: ByteArray?): SmartDataIntegrity {
        if (!hasContents(page)) return SmartDataIntegrity.INVALID_PAGE
        val data = page!!
        val kelvin = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
        return if ((data[0].toInt() and 0xc0) != 0 || (kelvin != 0 && kelvin !in 200..500) ||
            (data[3].toInt() and 0xff) > 100 || (data[4].toInt() and 0xff) > 100)
            SmartDataIntegrity.INVALID_PAGE else SmartDataIntegrity.NVME_VALIDATED
    }

    private fun hasContents(page: ByteArray?): Boolean = page != null && page.size == 512 &&
        page.any { it != 0.toByte() } && page.any { it != 0xff.toByte() }

    fun validate(info: SmartHealthInfo): SmartHealthInfo {
        val reported = info.dataIntegrity
        val integrity = when {
            reported == SmartDataIntegrity.ATA_CHECKSUM_FAILED || reported == SmartDataIntegrity.INVALID_PAGE -> reported
            info.rawPageHex == null -> SmartDataIntegrity.NOT_CHECKED
            info.diskType.contains("NVMe", ignoreCase = true) || reported == SmartDataIntegrity.NVME_VALIDATED -> nvme(decodeHex(info.rawPageHex))
            else -> ata(decodeHex(info.rawPageHex))
        }
        if (integrity != SmartDataIntegrity.ATA_CHECKSUM_FAILED && integrity != SmartDataIntegrity.INVALID_PAGE)
            return info.copy(dataIntegrity = integrity)
        // Do not retain metrics from an untrusted response, including exactCounters and the large health badge.
        return SmartHealthInfo(
            isSupported = false, status = SmartOverallStatus.UNCONFIRMED, diskType = info.diskType,
            model = info.model, serial = info.serial, firmware = info.firmware, vendor = info.vendor,
            product = info.product, deviceNode = info.deviceNode, rawJson = info.rawJson,
            rawPageHex = info.rawPageHex, dataIntegrity = integrity, failureStage = SmartFailureStage.VALIDATION,
            reason = info.reason?.takeIf { reported == integrity && info.failureStage == SmartFailureStage.VALIDATION }
                ?: if (integrity == SmartDataIntegrity.ATA_CHECKSUM_FAILED) "ATA S.M.A.R.T. page checksum mismatch."
                else "S.M.A.R.T. data page is invalid.",
            queryCompletedAtMillis = info.queryCompletedAtMillis, queryDurationMillis = info.queryDurationMillis
        )
    }
}
