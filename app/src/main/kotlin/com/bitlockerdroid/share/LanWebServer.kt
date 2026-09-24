package com.bitlockerdroid.share

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Outcome of RFC 7233 byte-range parsing for a GET request. */
sealed class ByteRangeRequest {
    /** Malformed or unsupported header: ignore it and serve a full 200 response. */
    object Ignore : ByteRangeRequest()

    /** Syntactically valid but not satisfiable: respond 416. */
    object Unsatisfiable : ByteRangeRequest()

    /** Inclusive byte window to serve as 206 Partial Content. */
    data class Satisfiable(val start: Long, val end: Long) : ByteRangeRequest()
}

/** Raised when request headers exceed the configured size limits. */
private class HeaderTooLargeException : Exception("request headers exceed size limit")

class LanWebServer(
    val config: LanShareConfig,
    val filesystem: ShareFilesystemAdapter
) {
    companion object {
        private const val TAG = "LanWebServer"
        private const val LOCAL_IP_CACHE_MS = 5000L

        /** Per-line and cumulative caps defending against oversized request headers. */
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BLOCK_BYTES = 16 * 1024

        @Volatile
        private var cachedLocalIps: Set<String>? = null

        @Volatile
        private var localIpsFetchedAt = 0L

        /** All IP addresses (v4/v6) currently assigned to this device, briefly cached. */
        fun getLocalIpAddresses(): Set<String> {
            val now = System.currentTimeMillis()
            val cached = cachedLocalIps
            if (cached != null && now - localIpsFetchedAt < LOCAL_IP_CACHE_MS) return cached
            val ips = mutableSetOf<String>()
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (intf in java.util.Collections.list(interfaces)) {
                    for (addr in java.util.Collections.list(intf.inetAddresses)) {
                        val host = addr.hostAddress ?: continue
                        // IPv6 literals may carry a zone suffix such as fe80::1%wlan0
                        ips.add(host.lowercase().substringBefore('%'))
                    }
                }
            } catch (_: Throwable) {}
            if (ips.isNotEmpty()) {
                cachedLocalIps = ips
                localIpsFetchedAt = now
            }
            return ips
        }

        /** Strips a trailing :port from a Host header value, keeping IPv6 literals intact. */
        private fun stripPort(host: String): String {
            val h = host.trim()
            if (h.startsWith("[")) {
                val end = h.indexOf(']')
                return if (end > 0) h.substring(0, end + 1) else h
            }
            val first = h.indexOf(':')
            val last = h.lastIndexOf(':')
            // A single colon separates host and port; multiple colons mean a bare IPv6 literal
            return if (first > 0 && first == last) h.substring(0, first) else h
        }

        /**
         * DNS-rebinding defense: accept Host headers only when they point at this device
         * (its own addresses, loopback) or at a private/link-local IP. Public hostnames
         * and foreign addresses are rejected so a rebound domain cannot script this server.
         */
        fun isAllowedHost(hostHeader: String?): Boolean {
            if (hostHeader.isNullOrBlank()) return true
            val host = stripPort(hostHeader).lowercase().removePrefix("[").removeSuffix("]")
            if (host.isEmpty()) return true
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            if (getLocalIpAddresses().contains(host)) return true

            val parts = host.split('.')
            if (parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { ch -> ch.isDigit() } }) {
                val octets = parts.mapNotNull { it.toIntOrNull() }
                if (octets.size == 4 && octets.all { it in 0..255 }) {
                    val a = octets[0]
                    val b = octets[1]
                    return a == 127 || a == 10 ||
                            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                            (a == 169 && b == 254) || (a == 100 && b in 64..127)
                }
                return false
            }

            if (host.contains(':')) {
                // IPv6 literal: loopback was handled above; allow link-local (fe80::/10)
                // and unique-local (fc00::/7) addresses
                if (host.startsWith("fe8") || host.startsWith("fe9") ||
                    host.startsWith("fea") || host.startsWith("feb")) return true
                if (host.startsWith("fc") || host.startsWith("fd")) return true
                return false
            }

            // Non-IP hostname: cannot be verified as targeting this device -> reject
            return false
        }

        /**
         * RFC 7233 byte-range parsing.
         * Returns [ByteRangeRequest.Unsatisfiable] for a valid-but-impossible range
         * (416), [ByteRangeRequest.Ignore] for malformed or multi-range headers
         * (fall back to a full 200 response), or [ByteRangeRequest.Satisfiable]
         * with the inclusive [start, end] byte window.
         */
        fun parseByteRange(header: String, fileSize: Long): ByteRangeRequest {
            val spec = header.trim()
            if (!spec.startsWith("bytes=")) return ByteRangeRequest.Ignore
            val rangeSpec = spec.removePrefix("bytes=").trim()

            if (rangeSpec.isEmpty() || rangeSpec.contains(',')) {
                // Multi-range requests are not supported: ignore the header entirely
                return ByteRangeRequest.Ignore
            }

            if (rangeSpec.startsWith('-')) {
                // Suffix form "bytes=-N": the last N bytes of the file
                val suffixLen = rangeSpec.substring(1).toLongOrNull()
                    ?: return ByteRangeRequest.Ignore
                if (suffixLen <= 0 || fileSize <= 0) return ByteRangeRequest.Unsatisfiable
                return ByteRangeRequest.Satisfiable(
                    start = (fileSize - suffixLen).coerceAtLeast(0L),
                    end = fileSize - 1
                )
            }

            val dashIdx = rangeSpec.indexOf('-')
            if (dashIdx < 0) return ByteRangeRequest.Ignore
            val firstSpec = rangeSpec.substring(0, dashIdx)
            val lastSpec = if (dashIdx < rangeSpec.length - 1) rangeSpec.substring(dashIdx + 1) else ""

            val first = firstSpec.toLongOrNull() ?: return ByteRangeRequest.Ignore
            if (first < 0) return ByteRangeRequest.Ignore
            val last = if (lastSpec.isEmpty()) {
                fileSize - 1
            } else {
                lastSpec.toLongOrNull()?.let { minOf(fileSize - 1, it) }
                    ?: return ByteRangeRequest.Ignore
            }
            // Invalid spec (last-byte-pos < first-byte-pos) -> ignore per RFC 7233.
            // Only applies when a last-byte-pos was given: an open-ended range
            // beyond EOF is valid but unsatisfiable (416 below).
            if (lastSpec.isNotEmpty() && last < first) return ByteRangeRequest.Ignore
            if (fileSize <= 0 || first >= fileSize) return ByteRangeRequest.Unsatisfiable
            return ByteRangeRequest.Satisfiable(start = first, end = last)
        }
    }

    private fun logI(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG] $msg") }
    }

    private fun logW(msg: String, t: Throwable? = null) {
        try { if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg) } catch (_: Throwable) { println("[$TAG] $msg") }
    }

    private fun logE(msg: String, t: Throwable? = null) {
        try { if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg) } catch (_: Throwable) { System.err.println("[$TAG] $msg") }
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    var isRunning = false
        private set

    val activeConnections = AtomicInteger(0)
    val bytesServed = AtomicLong(0L)
    var actualPort = config.port
        private set

    fun start(): Int {
        if (isRunning) return actualPort

        // Try configured port, fallback to sequential ports if occupied
        var portToTry = config.port
        var boundSocket: ServerSocket? = null
        for (i in 0..10) {
            try {
                boundSocket = ServerSocket(portToTry)
                actualPort = portToTry
                break
            } catch (e: Exception) {
                portToTry++
            }
        }

        if (boundSocket == null) {
            throw IllegalStateException("Failed to bind server socket to port ${config.port} through ${portToTry - 1}")
        }

        serverSocket = boundSocket
        isRunning = true
        executor = Executors.newCachedThreadPool()

        executor?.submit {
            acceptLoop()
        }

        logI("LanWebServer started on port $actualPort for volume ${config.volumeLabel}")
        return actualPort
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        executor?.shutdownNow()
        executor = null
        logI("LanWebServer stopped")
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                executor?.submit {
                    handleConnection(socket)
                }
            } catch (e: SocketException) {
                if (!isRunning) break
                logW("accept socket exception", e)
            } catch (t: Throwable) {
                if (!isRunning) break
                logE("accept error", t)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        activeConnections.incrementAndGet()
        try {
            socket.soTimeout = 30000 // 30 sec read timeout
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = try {
                readLine(input, MAX_REQUEST_LINE_BYTES) ?: return
            } catch (_: HeaderTooLargeException) {
                sendError(output, 431, "Request Header Fields Too Large")
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawUri = parts[1]

            // Parse HTTP headers with per-line and cumulative size caps so an
            // oversized header block cannot exhaust the heap (431 + disconnect)
            val headers = try {
                mutableMapOf<String, String>().also { map ->
                    var totalHeaderBytes = 0
                    while (true) {
                        val line = readLine(input, MAX_HEADER_BLOCK_BYTES) ?: break
                        if (line.isEmpty()) break
                        totalHeaderBytes += line.length + 2
                        if (totalHeaderBytes > MAX_HEADER_BLOCK_BYTES) throw HeaderTooLargeException()
                        val colonIdx = line.indexOf(':')
                        if (colonIdx > 0) {
                            val key = line.substring(0, colonIdx).trim().lowercase()
                            val value = line.substring(colonIdx + 1).trim()
                            map[key] = value
                        }
                    }
                }
            } catch (_: HeaderTooLargeException) {
                sendError(output, 431, "Request Header Fields Too Large")
                return
            }

            // DNS-rebinding defense: verify the request targets this device before
            // authentication and dispatch
            if (!isAllowedHost(headers["host"])) {
                logW("Rejected request with untrusted Host header: ${headers["host"]}")
                sendError(output, 403, "Forbidden: Untrusted Host")
                return
            }

            // Authentication check
            if (config.authEnabled) {
                val authHeader = headers["authorization"]
                if (!isAuthorized(authHeader)) {
                    val authResp = "HTTP/1.1 401 Unauthorized\r\n" +
                            "WWW-Authenticate: Basic realm=\"BitLockerDroid\"\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: 17\r\n\r\n" +
                            "401 Unauthorized\n"
                    output.write(authResp.toByteArray())
                    output.flush()
                    return
                }
            }

            // Path sanitization & traversal defense
            val decodedPath = try {
                URLDecoder.decode(rawUri.substringBefore('?'), "UTF-8")
            } catch (_: Exception) {
                rawUri.substringBefore('?')
            }

            val sanitizedPath = sanitizePath(decodedPath)
            if (sanitizedPath == null) {
                sendError(output, 403, "Forbidden")
                return
            }

            // Dispatch method
            when (method) {
                "OPTIONS" -> handleOptions(output)
                "PROPFIND" -> handlePropfind(sanitizedPath, headers, output)
                "PROPPATCH" -> handleProppatch(sanitizedPath, headers, output)
                "GET", "HEAD" -> handleGetOrHead(method, sanitizedPath, headers, output)
                "MKCOL" -> handleMkcol(sanitizedPath, output)
                "PUT" -> handlePut(sanitizedPath, headers, input, output)
                "DELETE" -> handleDelete(sanitizedPath, output)
                "COPY" -> handleCopy(sanitizedPath, headers, output)
                "MOVE" -> handleMove(sanitizedPath, headers, output)
                "LOCK" -> handleLock(sanitizedPath, headers, output)
                "UNLOCK" -> handleUnlock(sanitizedPath, headers, output)
                else -> sendError(output, 501, "Not Implemented")
            }
        } catch (_: SocketException) {
            // Client closed stream or aborted seek
        } catch (t: Throwable) {
            logW("handleConnection error: ${t.message}", t)
        } finally {
            activeConnections.decrementAndGet()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun isAuthorized(authHeader: String?): Boolean {
        if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) return false
        // A server configured with an empty password must never authenticate anyone
        if (config.password.isEmpty()) return false
        val base64Credentials = authHeader.substring(6).trim()
        val decoded = try {
            // java.util.Base64 instead of android.util.Base64: same leniency for
            // Basic auth payloads, and the parser stays unit-testable on the JVM
            String(java.util.Base64.getMimeDecoder().decode(base64Credentials), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val colonIdx = decoded.indexOf(':')
        if (colonIdx < 0) return false
        val user = decoded.substring(0, colonIdx)
        val pass = decoded.substring(colonIdx + 1)
        // Constant-time comparison: don't leak credential content via timing
        return MessageDigest.isEqual(
            user.toByteArray(Charsets.UTF_8), config.username.toByteArray(Charsets.UTF_8)
        ) && MessageDigest.isEqual(
            pass.toByteArray(Charsets.UTF_8), config.password.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sanitizePath(path: String): String? {
        var p = path.replace('\\', '/')
        if (p.contains("/../") || p.endsWith("/..") || p == "..") return null
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        if (!p.startsWith('/')) p = "/$p"
        if (p.length > 1 && p.endsWith('/')) p = p.dropLast(1)
        return p
    }

    /** True when the (already sanitized) path refers to the volume root itself. */
    private fun isVolumeRoot(path: String): Boolean = path.isEmpty() || path == "/"

    private fun handleOptions(output: OutputStream) {
        val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
        for ((k, v) in WebDavHandler.getWebDavHeaders(filesystem.isReadOnly)) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: 0\r\n\r\n")
        output.write(sb.toString().toByteArray())
        output.flush()
    }

    private fun handlePropfind(path: String, headers: Map<String, String>, output: OutputStream) {
        val target = filesystem.resolveNode(path)
        if (target == null) {
            sendError(output, 404, "Not Found")
            return
        }

        val depth = headers["depth"]?.trim() ?: "1"
        val children = if (depth == "0" || !target.isDirectory) {
            null
        } else {
            filesystem.listChildren(target)
        }

        val xml = WebDavHandler.buildMultiStatusXml(
            targetNode = target,
            children = children,
            quotaAvailable = filesystem.freeBytes,
            quotaUsed = filesystem.usedBytes
        )
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)

        val respHeaders = StringBuilder("HTTP/1.1 207 Multi-Status\r\n")
        respHeaders.append("Content-Type: application/xml; charset=utf-8\r\n")
        respHeaders.append("Content-Length: ").append(xmlBytes.size).append("\r\n")
        respHeaders.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        respHeaders.append("Pragma: no-cache\r\n")
        for ((k, v) in WebDavHandler.getWebDavHeaders(filesystem.isReadOnly)) {
            respHeaders.append(k).append(": ").append(v).append("\r\n")
        }
        respHeaders.append("\r\n")

        output.write(respHeaders.toString().toByteArray())
        output.write(xmlBytes)
        output.flush()
        bytesServed.addAndGet(xmlBytes.size.toLong())
    }

    private fun handleGetOrHead(
        method: String,
        path: String,
        headers: Map<String, String>,
        output: OutputStream
    ) {
        val target = filesystem.resolveNode(path)
        if (target == null) {
            sendError(output, 404, "Not Found")
            return
        }

        if (target.isDirectory) {
            val userAgent = headers["user-agent"]?.lowercase() ?: ""
            val accept = headers["accept"] ?: ""
            val isWebDavClient = userAgent.contains("webdav") || userAgent.contains("davfs") ||
                    userAgent.contains("microsoft-webdav") || userAgent.contains("gvfs")

            if (!isWebDavClient && (accept.contains("text/html") || accept.contains("*/*") || accept.isEmpty())) {
                val children = filesystem.listChildren(target)
                val html = WebPortalGenerator.generateDirectoryHtml(
                    volumeLabel = filesystem.volumeLabel,
                    currentPath = target.path,
                    children = children,
                    isReadOnly = filesystem.isReadOnly,
                    totalBytes = filesystem.totalBytes,
                    freeBytes = filesystem.freeBytes,
                    usedBytes = filesystem.usedBytes,
                    fsType = filesystem.fsType
                )
                val htmlBytes = html.toByteArray(Charsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${htmlBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(resp.toByteArray())
                if (method == "GET") {
                    output.write(htmlBytes)
                    bytesServed.addAndGet(htmlBytes.size.toLong())
                }
                output.flush()
                return
            } else {
                // Non-browser GET on directory: return listing or 200 OK
                val msg = "Directory: ${target.path}\n"
                val b = msg.toByteArray()
                val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${b.size}\r\n\r\n"
                output.write(resp.toByteArray())
                if (method == "GET") output.write(b)
                output.flush()
                return
            }
        }

        // Regular file streaming with HTTP Range support
        val fileSize = target.size
        val mimeType = target.mimeType
        val rangeRequest = headers["range"]?.let { parseByteRange(it, fileSize) }

        when (rangeRequest) {
            is ByteRangeRequest.Unsatisfiable -> {
                val resp = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$fileSize\r\n" +
                        "Content-Length: 0\r\n\r\n"
                output.write(resp.toByteArray())
                output.flush()
                return
            }

            is ByteRangeRequest.Satisfiable -> {
                val start = rangeRequest.start
                val end = rangeRequest.end
                val chunkLen = end - start + 1
                val encodedFilename = try {
                    URLEncoder.encode(target.name, "UTF-8").replace("+", "%20")
                } catch (_: Exception) {
                    target.name
                }
                val asciiFallback = target.name.replace("\"", "")
                val etag = "\"${target.lastModified.toString(16)}-${target.size.toString(16)}\""
                val lastMod = WebDavHandler.formatHttpDate(target.lastModified)
                val resp = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $chunkLen\r\n" +
                        "Content-Range: bytes $start-$end/$fileSize\r\n" +
                        "ETag: $etag\r\n" +
                        "Last-Modified: $lastMod\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Content-Disposition: inline; filename*=UTF-8''$encodedFilename; filename=\"$asciiFallback\"\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(resp.toByteArray())

                if (method == "GET" && chunkLen > 0L) {
                    streamRange(target, start, chunkLen, output)
                }
                output.flush()
                return
            }

            else -> {
                // No header, malformed header, or multi-range: serve the full file
                val encodedFilename = try {
                    URLEncoder.encode(target.name, "UTF-8").replace("+", "%20")
                } catch (_: Exception) {
                    target.name
                }
                val asciiFallback = target.name.replace("\"", "")
                val etag = "\"${target.lastModified.toString(16)}-${target.size.toString(16)}\""
                val lastMod = WebDavHandler.formatHttpDate(target.lastModified)
                val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: $fileSize\r\n" +
                        "ETag: $etag\r\n" +
                        "Last-Modified: $lastMod\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Content-Disposition: inline; filename*=UTF-8''$encodedFilename; filename=\"$asciiFallback\"\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(resp.toByteArray())

                if (method == "GET" && fileSize > 0L) {
                    streamRange(target, 0L, fileSize, output)
                }
                output.flush()
            }
        }
    }

    private fun streamRange(target: ShareNode, start: Long, length: Long, output: OutputStream) {
        filesystem.openRangeStream(target, start, length).use { stream ->
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0 && isRunning) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, toRead)
                if (n <= 0) break
                output.write(buf, 0, n)
                remaining -= n
                bytesServed.addAndGet(n.toLong())
            }
        }
    }

    private fun handleMkcol(path: String, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        // RFC 4918 Section 9.3: If resource already exists, MKCOL must return 405 Method Not Allowed
        if (filesystem.resolveNode(path) != null) {
            sendError(output, 405, "Method Not Allowed")
            return
        }
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val ok = filesystem.createDirectory(parentPath, name)
        if (ok) {
            val resp = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n"
            output.write(resp.toByteArray())
        } else {
            sendError(output, 409, "Conflict")
        }
        output.flush()
    }

    private fun handlePut(path: String, headers: Map<String, String>, input: InputStream, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        if (isVolumeRoot(path)) {
            sendError(output, 403, "Forbidden: cannot write to the volume root")
            return
        }

        val existingNode = filesystem.resolveNode(path)
        val ifNoneMatch = headers["if-none-match"]
        // RFC 7232 Section 3.2: If-None-Match: * requires 412 Precondition Failed if resource already exists
        if (ifNoneMatch != null && ifNoneMatch.trim() == "*" && existingNode != null) {
            sendError(output, 412, "Precondition Failed")
            return
        }

        val isChunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val contentLength = headers["content-length"]?.toLongOrNull()
        // A missing Content-Length used to fall through as a 0-byte write followed
        // by truncate(path, 0), silently wiping the existing file. RFC 7230 requires
        // a determinable body length: accept chunked or an explicit Content-Length
        // (explicit 0 is a valid empty PUT), reject everything else with 411.
        if (!isChunked && (contentLength == null || contentLength < 0)) {
            sendError(output, 411, "Length Required")
            return
        }

        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')

        if (existingNode == null) {
            val created = filesystem.createFile(parentPath, name)
            if (!created) {
                sendError(output, 500, "Internal Server Error: Failed to create file")
                return
            }
        }

        // Write streaming content
        var offset = 0L
        var writeFailed = false
        var malformedBody = false
        val buf = ByteArray(64 * 1024)

        if (isChunked) {
            // Basic RFC 7230 chunk decoding: size line (hex, chunk extensions
            // after ';'), chunk data, terminating CRLF, until the 0-size chunk.
            while (true) {
                val sizeLine: String?
                try {
                    sizeLine = readLine(input, MAX_REQUEST_LINE_BYTES)
                } catch (_: HeaderTooLargeException) {
                    malformedBody = true
                    break
                }
                if (sizeLine == null) {
                    malformedBody = true
                    break
                }
                val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                if (chunkSize == null || chunkSize < 0L) {
                    malformedBody = true
                    break
                }
                if (chunkSize == 0L) break
                var remaining = chunkSize
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) {
                        malformedBody = true
                        break
                    }
                    val written = filesystem.writeData(path, offset, buf, n)
                    if (written < 0) {
                        writeFailed = true
                        break
                    }
                    offset += n
                    remaining -= n
                }
                if (malformedBody || writeFailed) break
                // Consume the CRLF that terminates the chunk data
                try {
                    if (readLine(input, MAX_REQUEST_LINE_BYTES) == null) {
                        malformedBody = true
                        break
                    }
                } catch (_: HeaderTooLargeException) {
                    malformedBody = true
                    break
                }
            }
        } else {
            var remaining = contentLength ?: 0L
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                val written = filesystem.writeData(path, offset, buf, n)
                if (written < 0) {
                    writeFailed = true
                    break
                }
                offset += n
                remaining -= n
            }
        }

        if (writeFailed) {
            sendError(output, 500, "Internal Server Error: Failed to write data")
            return
        }

        if (malformedBody) {
            // Keep what was successfully received, then reject the request
            filesystem.truncate(path, offset)
            sendError(output, 400, "Bad Request: Malformed chunked body")
            return
        }

        // Truncate to offset in case existing file was larger
        filesystem.truncate(path, offset)

        val statusCode = if (existingNode == null) "201 Created" else "204 No Content"
        val resp = "HTTP/1.1 $statusCode\r\nContent-Length: 0\r\n\r\n"
        output.write(resp.toByteArray())
        output.flush()
    }

    private fun handleDelete(path: String, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        if (isVolumeRoot(path)) {
            sendError(output, 403, "Forbidden: cannot delete the volume root")
            return
        }
        val ok = filesystem.delete(path)
        if (ok) {
            val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
            output.write(resp.toByteArray())
        } else {
            sendError(output, 404, "Not Found")
        }
        output.flush()
    }

    private fun handleMove(path: String, headers: Map<String, String>, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        if (isVolumeRoot(path)) {
            sendError(output, 403, "Forbidden: cannot move the volume root")
            return
        }
        val destinationUri = headers["destination"]
        if (destinationUri == null) {
            sendError(output, 400, "Bad Request: Missing Destination")
            return
        }
        val destDecoded = try {
            URLDecoder.decode(destinationUri.substringAfter("://").substringAfter('/'), "UTF-8")
        } catch (_: Exception) {
            destinationUri.substringAfter("://").substringAfter('/')
        }
        val destPath = sanitizePath(destDecoded) ?: run {
            sendError(output, 400, "Invalid Destination")
            return
        }
        if (isVolumeRoot(destPath)) {
            sendError(output, 403, "Forbidden: invalid destination")
            return
        }
        // Cycle detection: moving onto itself or into its own subtree would
        // recurse without bound
        if (destPath == path) {
            sendError(output, 403, "Forbidden: source and destination are identical")
            return
        }
        if (destPath.startsWith("$path/")) {
            sendError(output, 409, "Conflict: destination lies inside the source")
            return
        }

        val overwriteHeader = headers["overwrite"]?.trim()?.uppercase() ?: "T"
        val destExists = filesystem.resolveNode(destPath) != null
        if (destExists) {
            if (overwriteHeader == "F") {
                // RFC 4918 Section 9.9.2: Overwrite: F on existing destination must return 412 Precondition Failed
                sendError(output, 412, "Precondition Failed")
                return
            } else {
                filesystem.delete(destPath)
            }
        }

        val ok = filesystem.rename(path, destPath)
        if (ok) {
            val status = if (destExists) "204 No Content" else "201 Created"
            val resp = "HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n"
            output.write(resp.toByteArray())
        } else {
            sendError(output, 409, "Conflict")
        }
        output.flush()
    }

    private fun handleCopy(path: String, headers: Map<String, String>, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        if (isVolumeRoot(path)) {
            sendError(output, 403, "Forbidden: cannot copy the volume root")
            return
        }
        val destinationUri = headers["destination"]
        if (destinationUri == null) {
            sendError(output, 400, "Bad Request: Missing Destination")
            return
        }
        val destDecoded = try {
            URLDecoder.decode(destinationUri.substringAfter("://").substringAfter('/'), "UTF-8")
        } catch (_: Exception) {
            destinationUri.substringAfter("://").substringAfter('/')
        }
        val destPath = sanitizePath(destDecoded) ?: run {
            sendError(output, 400, "Invalid Destination")
            return
        }
        if (isVolumeRoot(destPath)) {
            sendError(output, 403, "Forbidden: invalid destination")
            return
        }
        // Cycle detection: copying onto itself or into its own subtree would
        // recurse without bound and exhaust the volume
        if (destPath == path) {
            sendError(output, 403, "Forbidden: source and destination are identical")
            return
        }
        if (destPath.startsWith("$path/")) {
            sendError(output, 409, "Conflict: destination lies inside the source")
            return
        }

        val overwriteHeader = headers["overwrite"]?.trim()?.uppercase() ?: "T"
        val destExists = filesystem.resolveNode(destPath) != null
        if (destExists) {
            if (overwriteHeader == "F") {
                sendError(output, 412, "Precondition Failed")
                return
            } else {
                filesystem.delete(destPath)
            }
        }

        val ok = filesystem.copyNode(path, destPath)
        if (ok) {
            val status = if (destExists) "204 No Content" else "201 Created"
            val resp = "HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n"
            output.write(resp.toByteArray())
        } else {
            sendError(output, 409, "Conflict")
        }
        output.flush()
    }

    private fun handleLock(path: String, headers: Map<String, String>, output: OutputStream) {
        if (filesystem.isReadOnly) {
            sendError(output, 403, "Read-Only Mode")
            return
        }
        val node = filesystem.resolveNode(path)
        val token = "urn:uuid:" + java.util.UUID.randomUUID().toString()
        val timeout = headers["timeout"] ?: "Second-3600"

        val lockXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:prop xmlns:D=\"DAV:\">\n" +
                "  <D:lockdiscovery>\n" +
                "    <D:activelock>\n" +
                "      <D:locktype><D:write/></D:locktype>\n" +
                "      <D:lockscope><D:exclusive/></D:lockscope>\n" +
                "      <D:depth>0</D:depth>\n" +
                "      <D:timeout>$timeout</D:timeout>\n" +
                "      <D:locktoken><D:href>$token</D:href></D:locktoken>\n" +
                "      <D:lockroot><D:href>${WebDavHandler.encodeHref(path, node?.isDirectory ?: false)}</D:href></D:lockroot>\n" +
                "    </D:activelock>\n" +
                "  </D:lockdiscovery>\n" +
                "</D:prop>"
        val xmlBytes = lockXml.toByteArray(Charsets.UTF_8)
        val status = if (node == null) "201 Created" else "200 OK"
        val resp = "HTTP/1.1 $status\r\n" +
                "Content-Type: application/xml; charset=utf-8\r\n" +
                "Content-Length: ${xmlBytes.size}\r\n" +
                "Lock-Token: <$token>\r\n\r\n"
        output.write(resp.toByteArray())
        output.write(xmlBytes)
        output.flush()
    }

    private fun handleUnlock(path: String, headers: Map<String, String>, output: OutputStream) {
        val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
        output.write(resp.toByteArray())
        output.flush()
    }

    private fun handleProppatch(path: String, headers: Map<String, String>, output: OutputStream) {
        val target = filesystem.resolveNode(path)
        if (target == null) {
            sendError(output, 404, "Not Found")
            return
        }
        val href = WebDavHandler.encodeHref(target.path, target.isDirectory)
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:multistatus xmlns:D=\"DAV:\">\n" +
                "  <D:response>\n" +
                "    <D:href>$href</D:href>\n" +
                "    <D:propstat>\n" +
                "      <D:status>HTTP/1.1 200 OK</D:status>\n" +
                "    </D:propstat>\n" +
                "  </D:response>\n" +
                "</D:multistatus>"
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 207 Multi-Status\r\n" +
                "Content-Type: application/xml; charset=utf-8\r\n" +
                "Content-Length: ${xmlBytes.size}\r\n\r\n"
        output.write(resp.toByteArray())
        output.write(xmlBytes)
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, text: String) {
        val b = "$code $text\n".toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 $code $text\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(resp.toByteArray())
        output.write(b)
        output.flush()
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val sb = StringBuilder()
        var count = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                if (++count > maxBytes) throw HeaderTooLargeException()
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
