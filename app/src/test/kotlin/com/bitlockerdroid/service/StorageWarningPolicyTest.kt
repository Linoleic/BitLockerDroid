package com.bitlockerdroid.service

import org.junit.Assert.*
import org.junit.Test

class StorageWarningPolicyTest {
    private fun candidate(pkg: String = "com.android.systemui", id: Int = 0x53505542,
                          channel: String? = "DSK", category: String? = "err", tag: String? = "public:8,101") =
        StorageWarningPolicy.isCandidate(pkg, id, channel, category, tag)

    @Test fun matchesOnlyExactPublicStorageError() {
        assertTrue(candidate())
        assertTrue(candidate(pkg = "android"))
        assertFalse(candidate(pkg = "com.example.android"))
        assertFalse(candidate(id = 0x5344534b))
        assertFalse(candidate(channel = "BATTERY"))
        assertFalse(candidate(category = "sys"))
        assertFalse(candidate(category = "progress"))
        assertFalse(candidate(tag = "disk:8,96"))
        assertFalse(candidate(tag = "public:8,101|extra"))
        assertFalse(candidate(tag = null))
    }

    @Test fun requiresRootVerifiedMetadataAndUnmountableState() {
        assertTrue(StorageWarningPolicy.shouldSuppress(true, true, true, true, "unmountable"))
        assertFalse(StorageWarningPolicy.shouldSuppress(false, true, true, true, "unmountable"))
        assertFalse(StorageWarningPolicy.shouldSuppress(true, false, true, true, "unmountable"))
        assertFalse(StorageWarningPolicy.shouldSuppress(true, true, false, true, "unmountable"))
        assertFalse(StorageWarningPolicy.shouldSuppress(true, true, true, false, "unmountable"))
        for (state in listOf(null, "mounted", "mounted_ro", "checking", "ejecting", "removed", "bad_removal")) {
            assertFalse(StorageWarningPolicy.shouldSuppress(true, true, true, true, state))
        }
    }

    @Test fun parsesExactVolumeStatesWithoutSubstringMatches() {
        assertEquals(mapOf("public:8,101" to "unmountable", "public:8,102" to "mounted"),
            StorageWarningPolicy.parseVolumeStates("public:8,101 unmountable null\npublic:8,102 mounted ABCD\ndisk:8,96 unmountable\nnoise"))
    }

    @Test fun genericFatOemAndFallbackGuidDoNotVerifyBitLocker() {
        val boot = ByteArray(512)
        "MSWIN4.1".toByteArray().copyInto(boot, 3)
        boot[0x1a8] = 1
        assertFalse(BitLockerDetector.extractMetadataGuidsFromReader("MSWIN4.1", boot) { _, _ -> null }.verifiedMetadata)
    }

    @Test fun knownBootSignatureRequiresValidNonzeroMetadataGuid() {
        val boot = ByteArray(512)
        boot[0xb1] = 2
        val metadata = ByteArray(4096)
        "-FVE-FS-".toByteArray().copyInto(metadata)
        metadata[0x50] = 1
        assertTrue(BitLockerDetector.extractMetadataGuidsFromReader("-FVE-FS-", boot) { _, _ -> metadata }.verifiedMetadata)
        metadata[0x50] = 0
        assertFalse(BitLockerDetector.extractMetadataGuidsFromReader("-FVE-FS-", boot) { _, _ -> metadata }.verifiedMetadata)
    }
}
