package com.bitlockerdroid.share

import org.junit.Assert.*
import org.junit.Test

class LanShareTest {

    @Test
    fun testWebDavXmlEscaping() {
        val raw = "Files & \"Photos\" <2026> 'Summer'"
        val escaped = WebDavHandler.escapeXml(raw)
        assertEquals("Files &amp; &quot;Photos&quot; &lt;2026&gt; &apos;Summer&apos;", escaped)
    }

    @Test
    fun testWebDavHrefEncoding() {
        val dirPath = "My Documents/Vacation 2026"
        val hrefDir = WebDavHandler.encodeHref(dirPath, isDirectory = true)
        assertEquals("/My%20Documents/Vacation%202026/", hrefDir)

        val filePath = "My Documents/report.pdf"
        val hrefFile = WebDavHandler.encodeHref(filePath, isDirectory = false)
        assertEquals("/My%20Documents/report.pdf", hrefFile)
    }

    @Test
    fun testWebDavMultiStatusXmlGeneration() {
        val rootNode = ShareNode(
            name = "USB_DISK",
            path = "/",
            isDirectory = true,
            size = 0L,
            lastModified = 1700000000000L
        )

        val fileNode = ShareNode(
            name = "test_video.mp4",
            path = "/test_video.mp4",
            isDirectory = false,
            size = 104857600L,
            lastModified = 1700000000000L
        )

        val dirNode = ShareNode(
            name = "SubFolder",
            path = "/SubFolder",
            isDirectory = true,
            size = 0L,
            lastModified = 1700000000000L
        )

        val xml = WebDavHandler.buildMultiStatusXml(rootNode, listOf(fileNode, dirNode))

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"))
        assertTrue(xml.contains("<D:collection/>"))
        assertTrue(xml.contains("<D:getcontentlength>104857600</D:getcontentlength>"))
        assertTrue(xml.contains("<D:getcontenttype>video/mp4</D:getcontenttype>"))
        assertTrue(xml.contains("<D:displayname>test_video.mp4</D:displayname>"))
        assertTrue(xml.contains("<D:displayname>SubFolder</D:displayname>"))
    }

    @Test
    fun testWebDavHeaders() {
        val roHeaders = WebDavHandler.getWebDavHeaders(isReadOnly = true)
        assertEquals("1, 2", roHeaders["DAV"])
        assertEquals("DAV", roHeaders["MS-Author-Via"])
        assertTrue(roHeaders["Allow"]!!.contains("GET"))
        assertFalse(roHeaders["Allow"]!!.contains("DELETE"))

        val rwHeaders = WebDavHandler.getWebDavHeaders(isReadOnly = false)
        assertTrue(rwHeaders["Allow"]!!.contains("DELETE"))
        assertTrue(rwHeaders["Allow"]!!.contains("PUT"))
    }

    @Test
    fun testWebPortalItemUrlFormatting() {
        // Root folder item formatting - must never have double leading slashes
        val rootFileUrl = WebPortalGenerator.formatItemUrl("/", "video.mp4", isDirectory = false)
        assertEquals("/video.mp4", rootFileUrl)
        assertFalse(rootFileUrl.startsWith("//"))

        val rootDirUrl = WebPortalGenerator.formatItemUrl("/", "My Documents", isDirectory = true)
        assertEquals("/My%20Documents/", rootDirUrl)
        assertFalse(rootDirUrl.startsWith("//"))

        // Subdirectory item formatting
        val subFileUrl = WebPortalGenerator.formatItemUrl("/My Documents", "notes (1).txt", isDirectory = false)
        assertEquals("/My%20Documents/notes%20%281%29.txt", subFileUrl)
        assertFalse(subFileUrl.contains("//"))

        val subDirUrl = WebPortalGenerator.formatItemUrl("/My Documents", "Work", isDirectory = true)
        assertEquals("/My%20Documents/Work/", subDirUrl)
        assertFalse(subDirUrl.contains("//"))
    }

    @Test
    fun testWebPortalHtmlGeneration() {
        val rootNode = ShareNode(
            name = "MyDrive",
            path = "/",
            isDirectory = true,
            size = 0L,
            lastModified = System.currentTimeMillis()
        )

        val sampleFiles = listOf(
            ShareNode("Video.mkv", "/Video.mkv", false, 1024 * 1024 * 500L, System.currentTimeMillis()),
            ShareNode("Music.flac", "/Music.flac", false, 1024 * 1024 * 30L, System.currentTimeMillis()),
            ShareNode("Docs", "/Docs", true, 0L, System.currentTimeMillis())
        )

        val html = WebPortalGenerator.generateDirectoryHtml(
            volumeLabel = "MyDrive",
            currentPath = "/",
            children = sampleFiles,
            isReadOnly = true
        )

        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("MyDrive"))
        assertTrue(html.contains("Video.mkv"))
        assertTrue(html.contains("Music.flac"))
        assertTrue(html.contains("Docs"))
        assertTrue(html.contains("tag-video"))
        assertTrue(html.contains("tag-audio"))
        assertTrue(html.contains("tag-dir"))
        assertTrue(html.contains("Read-Only"))

        // Critical defense against DNS_PROBE_FINISHED_NXDOMAIN:
        // No href or onclick preview url should ever start with double slashes "//"
        assertFalse(html.contains("href=\"//"))
        assertFalse(html.contains("openPreview(event, '//"))
    }

    @Test
    fun testWebDavQuotaProperties() {
        val rootNode = ShareNode("USB", "/", true, 0L, 1000L)
        val xmlWithQuota = WebDavHandler.buildMultiStatusXml(
            targetNode = rootNode,
            children = emptyList(),
            quotaAvailable = 500_000_000_000L,
            quotaUsed = 100_000_000_000L
        )
        assertTrue(xmlWithQuota.contains("<D:quota-available-bytes>500000000000</D:quota-available-bytes>"))
        assertTrue(xmlWithQuota.contains("<D:quota-used-bytes>100000000000</D:quota-used-bytes>"))
    }

    @Test
    fun testWebPortalHtmlWithStorageInfo() {
        val rootNode = ShareNode("WorkDrive", "/", true, 0L, 1000L)
        val html = WebPortalGenerator.generateDirectoryHtml(
            volumeLabel = "WorkDrive",
            currentPath = "/",
            children = emptyList(),
            isReadOnly = true,
            totalBytes = 1_000_000_000L,
            freeBytes = 300_000_000L,
            usedBytes = 700_000_000L,
            fsType = "NTFS"
        )
        assertTrue(html.contains("NTFS"))
        assertTrue(html.contains("progress-bar-fill"))
        assertTrue(html.contains("Used:"))
        assertTrue(html.contains("Free:"))
        assertTrue(html.contains("Total:"))
    }

    @Test
    fun testShareNodeMimeTypes() {
        assertEquals("video/mp4", ShareNode("vid.mp4", "/vid.mp4", false, 10, 0).mimeType)
        assertEquals("video/x-matroska", ShareNode("movie.mkv", "/movie.mkv", false, 10, 0).mimeType)
        assertEquals("audio/flac", ShareNode("song.flac", "/song.flac", false, 10, 0).mimeType)
        assertEquals("image/jpeg", ShareNode("pic.jpg", "/pic.jpg", false, 10, 0).mimeType)
        assertEquals("application/pdf", ShareNode("doc.pdf", "/doc.pdf", false, 10, 0).mimeType)
        assertEquals("httpd/unix-directory", ShareNode("folder", "/folder", true, 0, 0).mimeType)
    }

    @Test
    fun testPosixFileShareAdapterOperations() {
        val tempDir = java.io.File.createTempFile("lan_share_test_", "").apply {
            delete()
            mkdir()
        }
        try {
            val subDir = java.io.File(tempDir, "SubDir").apply { mkdir() }
            val testFile = java.io.File(tempDir, "hello.txt").apply {
                writeText("0123456789ABCDEF0123456789ABCDEF")
            }

            val adapter = PosixFileShareAdapter(tempDir, "TestVolume", isReadOnly = true)
            val root = adapter.getRoot()
            assertEquals("TestVolume", root.name)
            assertEquals("/", root.path)

            val children = adapter.listChildren(root)
            assertEquals(2, children.size)
            assertTrue(children.any { it.name == "SubDir" && it.isDirectory })
            assertTrue(children.any { it.name == "hello.txt" && !it.isDirectory })

            // Test Range Stream: offset 10, length 6 ("ABCDEF")
            val helloNode = adapter.resolveNode("/hello.txt")
            assertNotNull(helloNode)
            adapter.openRangeStream(helloNode!!, 10L, 6L).use { stream ->
                val buf = ByteArray(6)
                val read = stream.read(buf)
                assertEquals(6, read)
                assertEquals("ABCDEF", String(buf))
            }

            // Path Traversal Defense
            val escapedNode = adapter.resolveNode("/../outside.txt")
            assertNull(escapedNode)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSviFolderHidingInPosixAdapter() {
        val tempDir = java.io.File.createTempFile("svi_test_", "").apply {
            delete()
            mkdir()
        }
        try {
            val sviDir = java.io.File(tempDir, "System Volume Information").apply { mkdir() }
            java.io.File(sviDir, "WPSettings.dat").writeText("data")
            java.io.File(tempDir, "normal_file.txt").writeText("hello")

            val adapter = PosixFileShareAdapter(tempDir, "TestVolume", isReadOnly = false)
            val root = adapter.getRoot()
            val children = adapter.listChildren(root)

            // SVI should not be visible in children
            assertFalse(children.any { it.name.equals("System Volume Information", ignoreCase = true) })
            assertTrue(children.any { it.name == "normal_file.txt" })

            // Direct resolution of SVI should return null
            assertNull(adapter.resolveNode("/System Volume Information"))
            assertNull(adapter.resolveNode("/System Volume Information/WPSettings.dat"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPosixAdapterTruncateAndCopy() {
        val tempDir = java.io.File.createTempFile("trunc_copy_test_", "").apply {
            delete()
            mkdir()
        }
        try {
            val file1 = java.io.File(tempDir, "orig.txt").apply {
                writeText("01234567890123456789") // 20 bytes
            }
            val adapter = PosixFileShareAdapter(tempDir, "TestVolume", isReadOnly = false)

            // Test truncate
            val truncOk = adapter.truncate("/orig.txt", 10L)
            assertTrue(truncOk)
            assertEquals(10L, file1.length())
            assertEquals("0123456789", file1.readText())

            // Test copyNode
            val copyOk = adapter.copyNode("/orig.txt", "/copied.txt")
            assertTrue(copyOk)
            val copiedFile = java.io.File(tempDir, "copied.txt")
            assertTrue(copiedFile.exists())
            assertEquals(10L, copiedFile.length())
            assertEquals("0123456789", copiedFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLanWebServerProtocolResponses() {
        val tempDir = java.io.File.createTempFile("server_proto_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "existing.txt").writeText("Existing Content")
            java.io.File(tempDir, "ExistingFolder").apply { mkdir() }

            val adapter = PosixFileShareAdapter(tempDir, "TestDisk", isReadOnly = false)
            val config = LanShareConfig(
                volumeLabel = "TestDisk",
                isReadOnly = false,
                port = 19182,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // 1. PUT with If-None-Match: * on existing file -> 412 Precondition Failed
            val (putCode, _) = sendRawRequest(port, "PUT /existing.txt HTTP/1.1\r\nIf-None-Match: *\r\nContent-Length: 4\r\n\r\ntest")
            assertEquals(412, putCode)

            // 2. MKCOL on existing folder -> 405 Method Not Allowed
            val (mkcolCode, _) = sendRawRequest(port, "MKCOL /ExistingFolder HTTP/1.1\r\n\r\n")
            assertEquals(405, mkcolCode)

            // 3. MOVE with Overwrite: F to existing destination -> 412 Precondition Failed
            java.io.File(tempDir, "source.txt").writeText("Source Content")
            val (moveCode, _) = sendRawRequest(port, "MOVE /source.txt HTTP/1.1\r\nDestination: http://127.0.0.1:$port/existing.txt\r\nOverwrite: F\r\n\r\n")
            assertEquals(412, moveCode)

            // 4. LOCK request -> 200 OK with Lock-Token
            val (lockCode, lockHeaders) = sendRawRequest(port, "LOCK /existing.txt HTTP/1.1\r\n\r\n")
            assertEquals(200, lockCode)
            assertTrue(lockHeaders.containsKey("lock-token"))

            // 5. UNLOCK request -> 204 No Content
            val (unlockCode, _) = sendRawRequest(port, "UNLOCK /existing.txt HTTP/1.1\r\n\r\n")
            assertEquals(204, unlockCode)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLanShareManagerStopAllWhenEmpty() {
        LanShareManager.stopAll(null)
        assertTrue(LanShareManager.shareStates.value.isEmpty())
        assertFalse(LanShareManager.isSharing("non-existent-guid"))
    }

    @Test
    fun testLanShareManagerStopSharingForDeviceGraceful() {
        LanShareManager.stopSharingForDevice(null, "/dev/block/vold/public:8,1", "guid-1234")
        assertTrue(LanShareManager.shareStates.value.isEmpty())
    }

    private fun sendRawRequest(port: Int, request: String): Pair<Int, Map<String, String>> {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val statusLine = inp.readLine() ?: return Pair(-1, emptyMap())
            val parts = statusLine.split(' ')
            val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = inp.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            return Pair(statusCode, headers)
        }
    }
}
