package com.bitlockerdroid.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitLockerDocumentsProviderSecurityTest {

    @Test
    fun `isSafeDocumentSegment rejects traversal separators control chars and system entries`() {
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment(""))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("."))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment(".."))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("../secret.txt"))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("dir/file.txt"))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("dir\\file.txt"))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("bad\u0000name.txt"))
        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("bad\nname.txt"))

        assertFalse(BitLockerDocumentsProvider.isSafeDocumentSegment("\$MFT", hideSvi = true, isVolumeRoot = true))
        assertFalse(
            BitLockerDocumentsProvider.isSafeDocumentSegment(
                "System Volume Information",
                hideSvi = true,
                isVolumeRoot = true
            )
        )
        assertTrue(
            BitLockerDocumentsProvider.isSafeDocumentSegment(
                "System Volume Information",
                hideSvi = false,
                isVolumeRoot = true
            )
        )
        assertTrue(
            BitLockerDocumentsProvider.isSafeDocumentSegment(
                "System Volume Information",
                hideSvi = true,
                isVolumeRoot = false
            )
        )
        assertTrue(BitLockerDocumentsProvider.isSafeDocumentSegment("2026 报告 (final).pdf", hideSvi = true, isVolumeRoot = true))
    }
}
