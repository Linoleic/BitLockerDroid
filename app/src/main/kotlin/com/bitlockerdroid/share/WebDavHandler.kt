package com.bitlockerdroid.share

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object WebDavHandler {

    private val httpDateFormat: SimpleDateFormat
        get() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

    private val isoDateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

    fun formatHttpDate(timestamp: Long): String {
        return httpDateFormat.format(Date(timestamp))
    }

    fun escapeXml(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun encodeHref(path: String, isDirectory: Boolean): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val encoded = segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val res = if (encoded.isEmpty()) "/" else "/$encoded"
        return if (isDirectory && !res.endsWith('/')) "$res/" else res
    }

    /**
     * Builds RFC 4918 multistatus XML response for PROPFIND requests,
     * including RFC 4331 quota properties (quota-available-bytes, quota-used-bytes).
     */
    fun buildMultiStatusXml(
        targetNode: ShareNode,
        children: List<ShareNode>? = null,
        quotaAvailable: Long = -1L,
        quotaUsed: Long = -1L
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n")

        appendNodeResponse(sb, targetNode, quotaAvailable, quotaUsed)

        if (children != null) {
            for (child in children) {
                appendNodeResponse(sb, child, -1L, -1L)
            }
        }

        sb.append("</D:multistatus>")
        return sb.toString()
    }

    private fun appendNodeResponse(
        sb: StringBuilder,
        node: ShareNode,
        quotaAvailable: Long = -1L,
        quotaUsed: Long = -1L
    ) {
        val href = encodeHref(node.path, node.isDirectory)
        val displayName = escapeXml(node.name)
        val lastModStr = httpDateFormat.format(Date(node.lastModified))
        val creationStr = isoDateFormat.format(Date(node.lastModified))

        sb.append("  <D:response>\n")
        sb.append("    <D:href>").append(href).append("</D:href>\n")
        sb.append("    <D:propstat>\n")
        sb.append("      <D:prop>\n")
        sb.append("        <D:displayname>").append(displayName).append("</D:displayname>\n")
        sb.append("        <D:creationdate>").append(creationStr).append("</D:creationdate>\n")
        sb.append("        <D:getlastmodified>").append(lastModStr).append("</D:getlastmodified>\n")

        if (node.isDirectory) {
            sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n")
            if (quotaAvailable >= 0L) {
                sb.append("        <D:quota-available-bytes>").append(quotaAvailable).append("</D:quota-available-bytes>\n")
            }
            if (quotaUsed >= 0L) {
                sb.append("        <D:quota-used-bytes>").append(quotaUsed).append("</D:quota-used-bytes>\n")
            }
        } else {
            val etag = "\"${node.lastModified.toString(16)}-${node.size.toString(16)}\""
            sb.append("        <D:resourcetype/>\n")
            sb.append("        <D:getcontentlength>").append(node.size).append("</D:getcontentlength>\n")
            sb.append("        <D:getcontenttype>").append(escapeXml(node.mimeType)).append("</D:getcontenttype>\n")
            sb.append("        <D:getetag>").append(etag).append("</D:getetag>\n")
        }

        sb.append("      </D:prop>\n")
        sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n")
        sb.append("    </D:propstat>\n")
        sb.append("  </D:response>\n")
    }

    /**
     * WebDAV headers required for compliance with Windows Explorer, macOS Finder,
     * and modern WebDAV clients.
     */
    fun getWebDavHeaders(isReadOnly: Boolean): Map<String, String> {
        val allow = if (isReadOnly) {
            "OPTIONS, GET, HEAD, PROPFIND"
        } else {
            "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, MKCOL, PUT, DELETE, COPY, MOVE, LOCK, UNLOCK"
        }
        return mapOf(
            "DAV" to "1, 2",
            "MS-Author-Via" to "DAV",
            "Allow" to allow,
            "Public" to allow,
            "Accept-Ranges" to "bytes"
        )
    }
}
