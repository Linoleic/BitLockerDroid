package com.bitlockerdroid.util

import org.junit.Assert.*
import org.junit.Test

class LogExportReportTest {
    @Test fun chronologicalMultilineMessagesAndMissingSourcesAreExplicit() {
        val environment = LogExportReport.Environment("1.2 (170)", "16 (API 36)", "Xiaomi tablet", "Root", "2026-10-25T10:00:00+08:00")
        val raw = "10-25 09:00:00 [app] first\n10-25 09:00:01 [app] java.io.IOException: EIO\n\tat com.example.Writer.write(Writer.kt:42)\n"
        val report = LogExportReport.create(environment, LogSnapshot(raw, null, listOf("crash.txt: unavailable (IOException).")))
        assertTrue(report.contains(raw))
        assertTrue(report.contains("[No crash log]"))
        assertTrue(report.contains("crash.txt: unavailable"))
        assertTrue(report.contains("Mode: Root"))
        assertFalse(report.contains("[No application log]"))
        assertTrue(LogExportReport.create(environment, LogSnapshot("", "")).contains("[No application log]"))
    }

    @Test fun hidesIdentifiersSecretsAndUserTextButKeepsTransportAndFailureDetails() {
        val guid = "b9bf6f41-4707-4344-99dd-ee413cca2ed8"
        val raw = """
            closing /dev/block/sda6 (Personal label, guid=$guid, rkId=$guid)
            BitLocker partition found -> usb://2002/p6 guid=$guid
            password="odd,pass'word"
            recovery_key=123456-123456-123456-123456-123456-123456-123456-123456
            FVEK=deadbeef
            SerialNumber=PRIVATE-SERIAL
            querySearchDocuments: rootId=encodedRoot query='private' search term'
            renameDocument: docId=encodedDoc displayName=family "photo" 生日.jpg
            call method=rename_document arg=encodedDoc|private file.txt
            File write failed for /Private folder/生日 file.bin: EIO 0x05 at offset 512
            USB 0bda:9210 SCSI ASC=0x20 /dev/bus/usb/002/002
            java.io.IOException: content://com.example/document/private%20file
                at com.example.Writer.write(Writer.kt:42)
        """.trimIndent()
        val out = LogExportRedactor().redact(raw)
        for (privateValue in listOf(guid, "Personal label", "odd,pass", "123456-123456", "deadbeef", "PRIVATE-SERIAL", "search term", "family", "生日", "private file", "encodedRoot", "encodedDoc")) {
            assertFalse("Leaked: $privateValue\n$out", out.contains(privateValue))
        }
        for (technical in listOf("/dev/block/sda6", "usb://2002/p6", "0bda:9210", "ASC=0x20", "/dev/bus/usb/002/002", "EIO 0x05", "offset 512", "Writer.kt:42")) {
            assertTrue("Lost diagnostic value: $technical\n$out", out.contains(technical))
        }
        assertEquals(3, Regex("""\[id#1]""").findAll(out).count())
    }

    @Test fun reportKeepsAliasesStableAcrossAppAndCrashSections() {
        val environment = LogExportReport.Environment("1", "16", "tablet", "Non-root", "now")
        val id = "12345678-1234-1234-1234-123456789abc"
        val report = LogExportReport.create(environment, LogSnapshot("guid=$id\n", "Exception guid=$id\n"))
        assertEquals(2, Regex("""\[id#1]""").findAll(report).count())
        assertFalse(report.contains(id))
    }
}
