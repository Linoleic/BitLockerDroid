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

    // ---------------- Security regression tests ----------------

    @Test
    fun testParseByteRange() {
        // RFC 7233 suffix form: bytes=-N means the LAST N bytes
        assertEquals(
            ByteRangeRequest.Satisfiable(500L, 999L),
            LanWebServer.parseByteRange("bytes=-500", 1000L)
        )
        // Suffix longer than the file -> the whole file, still 206
        assertEquals(
            ByteRangeRequest.Satisfiable(0L, 999L),
            LanWebServer.parseByteRange("bytes=-5000", 1000L)
        )
        // Suffix length 0 is unsatisfiable
        assertEquals(ByteRangeRequest.Unsatisfiable, LanWebServer.parseByteRange("bytes=-0", 1000L))

        // Prefix / closed ranges
        assertEquals(
            ByteRangeRequest.Satisfiable(0L, 9L),
            LanWebServer.parseByteRange("bytes=0-9", 1000L)
        )
        assertEquals(
            ByteRangeRequest.Satisfiable(500L, 999L),
            LanWebServer.parseByteRange("bytes=500-", 1000L)
        )
        // End clamped to EOF
        assertEquals(
            ByteRangeRequest.Satisfiable(0L, 999L),
            LanWebServer.parseByteRange("bytes=0-99999", 1000L)
        )
        // Start beyond EOF -> 416
        assertEquals(ByteRangeRequest.Unsatisfiable, LanWebServer.parseByteRange("bytes=1500-", 1000L))

        // Malformed / unsupported -> ignore header (full 200 response)
        assertEquals(ByteRangeRequest.Ignore, LanWebServer.parseByteRange("bytes=0-1,5-6", 1000L))
        assertEquals(ByteRangeRequest.Ignore, LanWebServer.parseByteRange("bytes=abc-", 1000L))
        assertEquals(ByteRangeRequest.Ignore, LanWebServer.parseByteRange("bytes=99-50", 1000L))
        assertEquals(ByteRangeRequest.Ignore, LanWebServer.parseByteRange("chunks=0-1", 1000L))
        assertEquals(ByteRangeRequest.Ignore, LanWebServer.parseByteRange("bytes=", 1000L))
    }

    @Test
    fun testHostHeaderValidation() {
        // Pure logic, machine-independent
        assertTrue(LanWebServer.isAllowedHost(null))
        assertTrue(LanWebServer.isAllowedHost(""))
        assertTrue(LanWebServer.isAllowedHost("localhost"))
        assertTrue(LanWebServer.isAllowedHost("localhost:8080"))
        assertTrue(LanWebServer.isAllowedHost("127.0.0.1:19199"))
        assertTrue(LanWebServer.isAllowedHost("[::1]:8080"))
        assertTrue(LanWebServer.isAllowedHost("192.168.1.50:8080"))
        assertTrue(LanWebServer.isAllowedHost("10.0.0.7"))
        assertTrue(LanWebServer.isAllowedHost("172.16.0.9"))
        assertTrue(LanWebServer.isAllowedHost("172.31.255.255"))
        assertTrue(LanWebServer.isAllowedHost("169.254.1.2"))
        // Foreign hostnames and addresses are refused (DNS-rebinding defense)
        assertFalse(LanWebServer.isAllowedHost("evil.com"))
        assertFalse(LanWebServer.isAllowedHost("evil.com:8080"))
        assertFalse(LanWebServer.isAllowedHost("attacker.example.net"))
        assertFalse(LanWebServer.isAllowedHost("8.8.8.8"))
        assertFalse(LanWebServer.isAllowedHost("172.32.0.1"))
        assertFalse(LanWebServer.isAllowedHost("999.1.1.1"))
    }

    @Test
    fun testRootPathDestructiveOperationsRejected() {
        val tempDir = java.io.File.createTempFile("root_guard_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "existing.txt").writeText("Precious Content")
            java.io.File(tempDir, "Folder").apply { mkdir() }
            java.io.File(tempDir, "Folder/child.txt").writeText("child")

            val adapter = PosixFileShareAdapter(tempDir, "GuardDisk", isReadOnly = false)
            val config = LanShareConfig(
                volumeLabel = "GuardDisk",
                isReadOnly = false,
                port = 19184,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // DELETE / must be refused and leave the volume intact
            val (delCode, _) = sendRawRequest(port, "DELETE / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertEquals(403, delCode)
            assertTrue(java.io.File(tempDir, "existing.txt").exists())
            assertTrue(java.io.File(tempDir, "Folder").exists())

            // PUT / must be refused as well
            val (putCode, _) = sendRawRequest(port, "PUT / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 4\r\n\r\ntest")
            assertEquals(403, putCode)

            // COPY into its own subtree -> 409 (would recurse without bound)
            val (copyCycle, _) = sendRawRequest(
                port,
                "COPY /Folder HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nDestination: http://127.0.0.1:$port/Folder/inside\r\n\r\n"
            )
            assertEquals(409, copyCycle)

            // MOVE onto itself -> 403 (overwrite path would delete the source)
            val (moveSelf, _) = sendRawRequest(
                port,
                "MOVE /Folder HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nDestination: http://127.0.0.1:$port/Folder\r\n\r\n"
            )
            assertEquals(403, moveSelf)

            // Adapter-level defense in depth: the root itself is never deletable
            assertFalse(adapter.delete("/"))
            assertTrue(java.io.File(tempDir, "Folder/child.txt").exists())
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRangeSuffixNegativeOffsetContent() {
        val tempDir = java.io.File.createTempFile("range_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            val content = "0123456789".repeat(100) // 1000 bytes
            java.io.File(tempDir, "data.bin").writeText(content)

            val adapter = PosixFileShareAdapter(tempDir, "RangeDisk", isReadOnly = true)
            val config = LanShareConfig(
                volumeLabel = "RangeDisk",
                isReadOnly = true,
                port = 19185,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // bytes=-500 -> exactly the last 500 bytes
            val (code, headers, body) = sendRawRequestWithBody(
                port,
                "GET /data.bin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=-500\r\n\r\n"
            )
            assertEquals(206, code)
            assertEquals("bytes 500-999/1000", headers["content-range"])
            assertEquals(500, body.length)
            assertEquals(content.substring(500), body)

            // bytes=-5000 (N >= fileSize) -> complete file as 206, not 416
            val (fullCode, fullHeaders, fullBody) = sendRawRequestWithBody(
                port,
                "GET /data.bin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=-5000\r\n\r\n"
            )
            assertEquals(206, fullCode)
            assertEquals("bytes 0-999/1000", fullHeaders["content-range"])
            assertEquals(content, fullBody)

            // bytes=-0 -> 416
            val (zeroCode, _) = sendRawRequest(
                port,
                "GET /data.bin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=-0\r\n\r\n"
            )
            assertEquals(416, zeroCode)

            // Closed range still correct
            val (headCode, headHeaders, headBody) = sendRawRequestWithBody(
                port,
                "GET /data.bin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-9\r\n\r\n"
            )
            assertEquals(206, headCode)
            assertEquals("bytes 0-9/1000", headHeaders["content-range"])
            assertEquals("0123456789", headBody)

            // Multi-range unsupported -> full 200 fallback
            val (multiCode, _) = sendRawRequest(
                port,
                "GET /data.bin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-1,5-6\r\n\r\n"
            )
            assertEquals(200, multiCode)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOversizedHeadersRejected431() {
        val tempDir = java.io.File.createTempFile("hdr_limit_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "existing.txt").writeText("ok")
            val adapter = PosixFileShareAdapter(tempDir, "HdrDisk", isReadOnly = true)
            val config = LanShareConfig(
                volumeLabel = "HdrDisk",
                isReadOnly = true,
                port = 19186,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // A 20 KB single header line blows the 16 KB cap -> 431, not OOM
            val hugeHeader = "X-Bloat: " + "A".repeat(20_000)
            val (code, _) = sendRawRequest(
                port,
                "GET /existing.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$hugeHeader\r\n\r\n"
            )
            assertEquals(431, code)

            // Many medium lines accumulating past the block cap -> 431 too
            val manyHeaders = (1..40).joinToString("") { "X-Pad-$it: " + "B".repeat(700) + "\r\n" }
            val (code2, _) = sendRawRequest(
                port,
                "GET /existing.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$manyHeaders\r\n\r\n"
            )
            assertEquals(431, code2)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testBasicAuthEnforcement() {
        val tempDir = java.io.File.createTempFile("auth_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "secret.txt").writeText("secret content")
            val adapter = PosixFileShareAdapter(tempDir, "AuthDisk", isReadOnly = true)
            val config = LanShareConfig(
                volumeLabel = "AuthDisk",
                isReadOnly = true,
                port = 19187,
                authEnabled = true,
                username = "admin",
                password = "s3cret"
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // No credentials -> 401 with a WWW-Authenticate challenge
            val (noAuthCode, noAuthHeaders) = sendRawRequest(
                port,
                "GET /secret.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            )
            assertEquals(401, noAuthCode)
            assertTrue(noAuthHeaders.containsKey("www-authenticate"))

            // Wrong password -> 401
            val badAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin:wrongpass".toByteArray())
            val (badCode, _) = sendRawRequest(
                port,
                "GET /secret.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: $badAuth\r\n\r\n"
            )
            assertEquals(401, badCode)

            // Correct credentials -> 200 with the file body
            val goodAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin:s3cret".toByteArray())
            val (goodCode, _, goodBody) = sendRawRequestWithBody(
                port,
                "GET /secret.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: $goodAuth\r\n\r\n"
            )
            assertEquals(200, goodCode)
            assertEquals("secret content", goodBody)

            // WebDAV write methods must be equally protected: DELETE without auth -> 401
            val (delCode, _) = sendRawRequest(
                port,
                "DELETE /secret.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            )
            assertEquals(401, delCode)
            assertTrue(java.io.File(tempDir, "secret.txt").exists())
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEmptyPasswordAuthRejectsEverything() {
        val tempDir = java.io.File.createTempFile("auth_empty_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "data.txt").writeText("data")
            val adapter = PosixFileShareAdapter(tempDir, "EmptyAuthDisk", isReadOnly = true)
            val config = LanShareConfig(
                volumeLabel = "EmptyAuthDisk",
                isReadOnly = true,
                port = 19188,
                authEnabled = true,
                username = "admin",
                password = ""
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // Anonymous -> 401
            val (noAuthCode, _) = sendRawRequest(
                port,
                "GET /data.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            )
            assertEquals(401, noAuthCode)

            // The classic footgun "admin:" (empty password) must ALSO be rejected
            val emptyAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("admin:".toByteArray())
            val (emptyCode, _) = sendRawRequest(
                port,
                "GET /data.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: $emptyAuth\r\n\r\n"
            )
            assertEquals(401, emptyCode)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testWebPortalXssEscaping() {
        val children = listOf(
            ShareNode("<script>alert(1)</script>.mp4", "/<script>alert(1)</script>.mp4", false, 10L, 0L),
            ShareNode("it's \"quoted\" & <tagged>.jpg", "/it's \"quoted\" & <tagged>.jpg", false, 10L, 0L)
        )
        val html = WebPortalGenerator.generateDirectoryHtml(
            volumeLabel = "XssDrive",
            currentPath = "/",
            children = children,
            isReadOnly = true
        )

        // Raw markup from file names must never reach the HTML...
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("it's \"quoted\""))
        // ...only the escaped forms do
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;.mp4"))
        assertTrue(html.contains("it&#39;s &quot;quoted&quot; &amp; &lt;tagged&gt;.jpg"))

        // Inline JS handlers are gone entirely
        assertFalse(html.contains("onclick="))
        // Media items expose escaped data attributes consumed by the delegated listener
        assertTrue(html.contains("data-preview=\"1\""))
        assertTrue(html.contains("data-name=\"&lt;script&gt;alert(1)&lt;/script&gt;.mp4\""))
        assertTrue(html.contains("data-ext=\"mp4\""))
    }

    @Test
    fun testPutWithoutLengthRejected411() {
        val tempDir = java.io.File.createTempFile("put_411_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            java.io.File(tempDir, "report.txt").writeText("Important Data")
            val adapter = PosixFileShareAdapter(tempDir, "PutDisk", isReadOnly = false)
            val config = LanShareConfig(
                volumeLabel = "PutDisk",
                isReadOnly = false,
                port = 19189,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // PUT without Content-Length and without chunked encoding used to be
            // treated as a 0-byte write followed by truncate(path, 0), wiping the
            // file. It must now be refused with 411 and leave the file intact.
            val (noLenCode, _) = sendRawRequest(
                port,
                "PUT /report.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            )
            assertEquals(411, noLenCode)
            assertEquals("Important Data", java.io.File(tempDir, "report.txt").readText())

            // An unparseable Content-Length value is equally rejected
            val (badLenCode, _) = sendRawRequest(
                port,
                "PUT /report.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: abc\r\n\r\nx"
            )
            assertEquals(411, badLenCode)
            assertEquals("Important Data", java.io.File(tempDir, "report.txt").readText())

            // An explicit Content-Length: 0 is an intentional empty PUT -> 204
            val (zeroCode, _) = sendRawRequest(
                port,
                "PUT /report.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 0\r\n\r\n"
            )
            assertEquals(204, zeroCode)
            assertEquals("", java.io.File(tempDir, "report.txt").readText())
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPutChunkedEncoding() {
        val tempDir = java.io.File.createTempFile("put_chunked_test_", "").apply {
            delete()
            mkdir()
        }
        var server: LanWebServer? = null
        try {
            val adapter = PosixFileShareAdapter(tempDir, "ChunkDisk", isReadOnly = false)
            val config = LanShareConfig(
                volumeLabel = "ChunkDisk",
                isReadOnly = false,
                port = 19190,
                authEnabled = false
            )
            server = LanWebServer(config, adapter)
            val port = server.start()

            // Chunked PUT creating a new file: 5+4+2 bytes across three chunks
            val (createCode, _, createBody) = sendRawRequestWithBody(
                port,
                "PUT /chunked.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nHello\r\n4\r\n,wor\r\n2\r\nld\r\n0\r\n\r\n"
            )
            assertEquals(201, createCode)
            assertEquals("Hello,world", java.io.File(tempDir, "chunked.txt").readText())

            // Chunked PUT overwriting a longer existing file truncates exactly
            // to the received bytes
            java.io.File(tempDir, "existing.txt")
                .writeText("Old Content That Is Much Longer Than The New Data")
            val (overCode, _) = sendRawRequest(
                port,
                "PUT /existing.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "2\r\nHi\r\n0\r\n\r\n"
            )
            assertEquals(204, overCode)
            assertEquals("Hi", java.io.File(tempDir, "existing.txt").readText())

            // Malformed chunk size -> 400 Bad Request
            val (badCode, _) = sendRawRequest(
                port,
                "PUT /broken.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "ZZ\r\nabcd"
            )
            assertEquals(400, badCode)
        } finally {
            server?.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPortExhaustionAndFailureState() {
        // Bind the entire fallback range the server would scan so start() must fail
        var base: Int? = null
        var blockers: List<java.net.ServerSocket> = emptyList()
        for (candidate in intArrayOf(19200, 19500, 19800)) {
            val sockets = (candidate..candidate + 10).mapNotNull { p ->
                try { java.net.ServerSocket(p) } catch (_: Exception) { null }
            }
            if (sockets.size == 11) {
                base = candidate
                blockers = sockets
                break
            } else {
                sockets.forEach { try { it.close() } catch (_: Exception) {} }
            }
        }
        // Requires a free 11-port window to set up the exhaustion scenario
        org.junit.Assume.assumeTrue(base != null)

        val tempDir = java.io.File.createTempFile("port_exhaust_test_", "").apply {
            delete()
            mkdir()
        }
        try {
            val config = LanShareConfig(
                volumeLabel = "ExhaustDisk",
                isReadOnly = true,
                port = base!!,
                authEnabled = false
            )
            val adapter = PosixFileShareAdapter(tempDir, "ExhaustDisk", isReadOnly = true)
            val server = LanWebServer(config, adapter)

            // Exhausted range -> a catchable IllegalStateException, server not running
            var thrown: Throwable? = null
            try {
                server.start()
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("start() must throw when the whole port range is occupied", thrown)
            assertTrue(thrown is IllegalStateException)
            assertFalse(server.isRunning)
            // A stop() after a failed start must be safe and side-effect free
            server.stop()

            // Manager-level failure contract: a failed start is reported as a
            // non-running state carrying the message, which the Compose dialog
            // surfaces as a toast instead of crashing
            val failed = LanShareManager.buildFailureState(config, "bind failed")
            assertFalse(failed.isRunning)
            assertEquals("bind failed", failed.errorMessage)
            assertEquals("ExhaustDisk", failed.volumeLabel)
            assertEquals(base!!, failed.port)
            assertEquals(config.isReadOnly, failed.isReadOnly)
            assertEquals(config.authEnabled, failed.authEnabled)
        } finally {
            blockers.forEach { try { it.close() } catch (_: Exception) {} }
            tempDir.deleteRecursively()
        }
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

    private fun sendRawRequestWithBody(port: Int, request: String): Triple<Int, Map<String, String>, String> {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val statusLine = inp.readLine() ?: return Triple(-1, emptyMap(), "")
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

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = inp.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""
            return Triple(statusCode, headers, body)
        }
    }
}
