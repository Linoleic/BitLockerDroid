package com.bitlockerdroid.share

import com.bitlockerdroid.service.DislockerCore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

data class ShareNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val ref: Long = -1L,
    val realFile: File? = null
) {
    val extension: String
        get() = name.substringAfterLast('.', "")

    val mimeType: String
        get() {
            if (isDirectory) return "httpd/unix-directory"
            val ext = extension.lowercase()
            return when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "m4a", "aac" -> "audio/mp4"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                "txt", "log", "md" -> "text/plain; charset=utf-8"
                "html", "htm" -> "text/html; charset=utf-8"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "zip" -> "application/zip"
                "tar", "gz", "7z", "rar" -> "application/octet-stream"
                "iso", "img" -> "application/octet-stream"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                else -> "application/octet-stream"
            }
        }
}

interface ShareFilesystemAdapter {
    val volumeLabel: String
    val isReadOnly: Boolean
    val totalBytes: Long get() = 0L
    val freeBytes: Long get() = -1L
    val usedBytes: Long get() = -1L
    val fsType: String get() = ""

    fun getRoot(): ShareNode
    fun resolveNode(path: String): ShareNode?
    fun listChildren(dirNode: ShareNode): List<ShareNode>
    fun openRangeStream(node: ShareNode, startOffset: Long, length: Long): InputStream
    fun readDirect(node: ShareNode, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int

    // Modifications
    fun createDirectory(parentPath: String, name: String): Boolean
    fun createFile(parentPath: String, name: String): Boolean
    fun writeData(filePath: String, offset: Long, data: ByteArray, count: Int): Long
    fun truncate(filePath: String, newSize: Long): Boolean
    fun delete(path: String): Boolean
    fun rename(oldPath: String, newPath: String): Boolean
    fun copyNode(sourcePath: String, destPath: String): Boolean
}

/**
 * Universal filesystem adapter for unlocked BitLocker volumes using DislockerCore
 * and underlying VolumeReader (NTFS libntfs-3g, FatFs FAT32, exFAT).
 */
class VolumeCoreShareAdapter(
    private val core: DislockerCore,
    override val isReadOnly: Boolean = true
) : ShareFilesystemAdapter {

    override val volumeLabel: String = core.volumeLabel

    override val fsType: String = when (core.reader) {
        is com.bitlockerdroid.ntfs.NtfsReader -> "NTFS"
        is com.bitlockerdroid.ntfs.ExFatReader -> "exFAT"
        is com.bitlockerdroid.ntfs.Fat32Reader -> "FAT32"
        else -> "BitLocker"
    }

    override val totalBytes: Long
        get() {
            val sp = core.getSpaceInfo()
            return if (sp != null && sp.first > 0L) sp.first else core.info.volumeSize
        }

    override val freeBytes: Long
        get() = core.getSpaceInfo()?.second ?: -1L

    override val usedBytes: Long
        get() {
            val f = freeBytes
            val t = totalBytes
            return if (f >= 0L && t >= f) t - f else -1L
        }

    private val pathCache = ConcurrentHashMap<String, ShareNode>()

    private fun normalizePath(rawPath: String): String {
        var p = rawPath.replace('\\', '/')
        while (p.contains("//")) {
            p = p.replace("//", "/")
        }
        p = p.trim()
        if (!p.startsWith('/')) p = "/$p"
        if (p.length > 1 && p.endsWith('/')) p = p.dropLast(1)
        return p
    }

    override fun getRoot(): ShareNode {
        val rootRef = core.reader.rootRef
        return ShareNode(
            name = volumeLabel,
            path = "/",
            isDirectory = true,
            size = 0L,
            lastModified = System.currentTimeMillis(),
            ref = rootRef
        ).also { pathCache["/"] = it }
    }

    private fun isSvi(pathOrName: String): Boolean {
        val hideSvi = try { com.bitlockerdroid.util.PreferenceHelper.hideSviFolder } catch (_: Throwable) { true }
        if (!hideSvi) return false
        val clean = pathOrName.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return clean.equals("System Volume Information", ignoreCase = true) ||
                clean.startsWith("System Volume Information/", ignoreCase = true)
    }

    override fun resolveNode(path: String): ShareNode? {
        val norm = normalizePath(path)
        if (isSvi(norm)) return null
        if (norm == "/") return getRoot()

        pathCache[norm]?.let { return it }

        val segments = norm.split('/').filter { it.isNotEmpty() }
        var curr = getRoot()
        var accumulatedPath = ""

        for (seg in segments) {
            if (!curr.isDirectory) return null
            val children = listChildren(curr)
            val found = children.find { it.name.equals(seg, ignoreCase = true) } ?: return null
            curr = found
            accumulatedPath = if (accumulatedPath.isEmpty()) "/${curr.name}" else "$accumulatedPath/${curr.name}"
            pathCache[accumulatedPath] = curr
        }

        pathCache[norm] = curr
        return curr
    }

    override fun listChildren(dirNode: ShareNode): List<ShareNode> {
        val effRef = core.resolveRecord(dirNode.ref)
        val entries = try {
            core.reader.listDirectory(effRef)
        } catch (_: Exception) {
            emptyList()
        }

        val result = mutableListOf<ShareNode>()
        val basePath = if (dirNode.path == "/") "" else dirNode.path

        for (entry in entries) {
            // Filter out self/parent pointers and metadata system files
            if (entry.name == "." || entry.name == "..") continue
            if (entry.name.startsWith('$') && dirNode.path == "/") continue
            if (dirNode.path == "/" && isSvi(entry.name)) continue

            val nodePath = "$basePath/${entry.name}"
            val node = ShareNode(
                name = entry.name,
                path = nodePath,
                isDirectory = entry.isDirectory,
                size = entry.size,
                lastModified = entry.lastModified,
                ref = entry.ref
            )
            pathCache[nodePath] = node
            core.registerPath(entry.ref, nodePath, dirNode.ref)
            result.add(node)
        }

        // Sort directories first, then alphabetically
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun openRangeStream(node: ShareNode, startOffset: Long, length: Long): InputStream {
        val validStart = maxOf(0L, startOffset)
        val validLen = if (length < 0) maxOf(0L, node.size - validStart) else minOf(length, node.size - validStart)
        return VolumeRangeInputStream(core, node.ref, validStart, validLen)
    }

    override fun readDirect(node: ShareNode, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        return core.readFile(node.ref, offset, dst, dstPos, len)
    }

    override fun createDirectory(parentPath: String, name: String): Boolean {
        if (isReadOnly) return false
        val writer = core.writer ?: return false
        val ok = try {
            val res = writer.createFile(parentPath, name, isDirectory = true)
            res >= 0L
        } catch (_: Exception) {
            false
        }
        if (ok) {
            core.invalidateCache()
            pathCache.clear()
        }
        return ok
    }

    override fun createFile(parentPath: String, name: String): Boolean {
        if (isReadOnly) return false
        val writer = core.writer ?: return false
        val ok = try {
            val res = writer.createFile(parentPath, name, isDirectory = false)
            res >= 0L
        } catch (_: Exception) {
            false
        }
        if (ok) {
            core.invalidateCache()
            pathCache.clear()
        }
        return ok
    }

    override fun writeData(filePath: String, offset: Long, data: ByteArray, count: Int): Long {
        if (isReadOnly) return -1L
        val writer = core.writer ?: return -1L
        val written = try {
            writer.write(filePath, offset, data, count)
        } catch (_: Exception) {
            -1L
        }
        if (written > 0L) {
            core.invalidateCache()
            pathCache.clear()
        }
        return written
    }

    override fun truncate(filePath: String, newSize: Long): Boolean {
        if (isReadOnly) return false
        val writer = core.writer ?: return false
        val ok = try {
            writer.truncate(filePath, newSize)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            core.invalidateCache()
            pathCache.clear()
        }
        return ok
    }

    override fun delete(path: String): Boolean {
        if (isReadOnly) return false
        // Never allow deleting the volume root itself
        if (normalizePath(path) == "/") return false
        val writer = core.writer ?: return false
        val ok = try {
            writer.delete(path)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            core.invalidateCache()
            pathCache.clear()
        }
        return ok
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (isReadOnly) return false
        val writer = core.writer ?: return false
        val ok = try {
            writer.rename(oldPath, newPath)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            core.invalidateCache()
            pathCache.clear()
        }
        return ok
    }

    override fun copyNode(sourcePath: String, destPath: String): Boolean {
        if (isReadOnly) return false
        val src = resolveNode(sourcePath) ?: return false
        if (src.isDirectory) return false
        val destParent = destPath.substringBeforeLast('/').ifEmpty { "/" }
        val destName = destPath.substringAfterLast('/')
        if (!createFile(destParent, destName)) return false
        val buf = ByteArray(64 * 1024)
        var offset = 0L
        var copyOk = true
        try {
            openRangeStream(src, 0L, src.size).use { input ->
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    val w = writeData(destPath, offset, buf, r)
                    if (w < 0) {
                        copyOk = false
                        break
                    }
                    offset += r
                }
            }
        } catch (_: Exception) {
            copyOk = false
        }
        if (copyOk) {
            truncate(destPath, offset)
        }
        return copyOk
    }
}

/**
 * Seekable range input stream directly backed by DislockerCore sector decryption.
 */
class VolumeRangeInputStream(
    private val core: DislockerCore,
    private val ref: Long,
    private val startOffset: Long,
    private val totalLength: Long
) : InputStream() {

    private var curOffset = startOffset
    private val endOffset = startOffset + totalLength

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n > 0) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (curOffset >= endOffset) return -1
        val toRead = minOf(len.toLong(), endOffset - curOffset).toInt()
        val n = core.readFile(ref, curOffset, b, off, toRead)
        if (n <= 0) return -1
        curOffset += n
        return n
    }

    override fun available(): Int {
        val rem = endOffset - curOffset
        return if (rem > Int.MAX_VALUE) Int.MAX_VALUE else rem.toInt()
    }
}

/**
 * Real POSIX file-backed adapter when volume is mounted via FUSE to /storage/XXXX-XXXX.
 */
class PosixFileShareAdapter(
    private val rootDir: File,
    override val volumeLabel: String,
    override val isReadOnly: Boolean = true
) : ShareFilesystemAdapter {

    override val fsType: String = "POSIX"

    override val totalBytes: Long
        get() = try { rootDir.totalSpace } catch (_: Exception) { 0L }

    override val freeBytes: Long
        get() = try { rootDir.freeSpace } catch (_: Exception) { -1L }

    override val usedBytes: Long
        get() {
            val t = totalBytes
            val f = freeBytes
            return if (f >= 0L && t >= f) t - f else -1L
        }

    override fun getRoot(): ShareNode {
        return ShareNode(
            name = volumeLabel,
            path = "/",
            isDirectory = true,
            size = 0L,
            lastModified = rootDir.lastModified(),
            realFile = rootDir
        )
    }

    private fun resolveFile(path: String): File? {
        val norm = path.trim().removePrefix("/")
        val file = if (norm.isEmpty()) rootDir else File(rootDir, norm)
        val canonicalRoot = rootDir.canonicalPath
        val canonicalFile = file.canonicalPath
        // Directory traversal protection. Compare against the root with a trailing
        // separator so a sibling directory (/data/volx) cannot pass a /data/vol root
        // check via plain string prefix; the root itself is still allowed through.
        if (canonicalFile != canonicalRoot &&
            !canonicalFile.startsWith(canonicalRoot + File.separator)
        ) return null
        return file
    }

    private fun isSvi(pathOrName: String): Boolean {
        val hideSvi = try { com.bitlockerdroid.util.PreferenceHelper.hideSviFolder } catch (_: Throwable) { true }
        if (!hideSvi) return false
        val clean = pathOrName.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return clean.equals("System Volume Information", ignoreCase = true) ||
                clean.startsWith("System Volume Information/", ignoreCase = true)
    }

    override fun resolveNode(path: String): ShareNode? {
        if (isSvi(path)) return null
        val file = resolveFile(path) ?: return null
        if (!file.exists()) return null
        val relPath = file.canonicalPath.removePrefix(rootDir.canonicalPath).replace('\\', '/')
        val finalPath = if (relPath.isEmpty() || relPath == "/") "/" else if (relPath.startsWith('/')) relPath else "/$relPath"
        if (isSvi(finalPath)) return null
        return ShareNode(
            name = if (finalPath == "/") volumeLabel else file.name,
            path = finalPath,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            realFile = file
        )
    }

    override fun listChildren(dirNode: ShareNode): List<ShareNode> {
        val dir = dirNode.realFile ?: resolveFile(dirNode.path) ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        val basePath = if (dirNode.path == "/") "" else dirNode.path
        return files.filter { f ->
            !(dirNode.path == "/" && isSvi(f.name))
        }.map { f ->
            ShareNode(
                name = f.name,
                path = "$basePath/${f.name}",
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified(),
                realFile = f
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override fun openRangeStream(node: ShareNode, startOffset: Long, length: Long): InputStream {
        val file = node.realFile ?: resolveFile(node.path) ?: throw IllegalArgumentException("File not found")
        val validStart = maxOf(0L, startOffset)
        val validLen = if (length < 0) maxOf(0L, file.length() - validStart) else minOf(length, file.length() - validStart)
        val raf = RandomAccessFile(file, "r")
        raf.seek(validStart)
        return object : InputStream() {
            private var bytesLeft = validLen
            override fun read(): Int {
                if (bytesLeft <= 0) return -1
                val r = raf.read()
                if (r != -1) bytesLeft--
                return r
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesLeft <= 0) return -1
                val toRead = minOf(len.toLong(), bytesLeft).toInt()
                val n = raf.read(b, off, toRead)
                if (n > 0) bytesLeft -= n
                return n
            }
            override fun close() {
                raf.close()
            }
            override fun available(): Int = if (bytesLeft > Int.MAX_VALUE) Int.MAX_VALUE else bytesLeft.toInt()
        }
    }

    override fun readDirect(node: ShareNode, offset: Long, dst: ByteArray, dstPos: Int, len: Int): Int {
        val file = node.realFile ?: resolveFile(node.path) ?: return 0
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            return raf.read(dst, dstPos, len)
        }
    }

    override fun createDirectory(parentPath: String, name: String): Boolean {
        if (isReadOnly) return false
        val parent = resolveFile(parentPath) ?: return false
        val dir = File(parent, name)
        return dir.mkdir()
    }

    override fun createFile(parentPath: String, name: String): Boolean {
        if (isReadOnly) return false
        val parent = resolveFile(parentPath) ?: return false
        val f = File(parent, name)
        return f.createNewFile()
    }

    override fun writeData(filePath: String, offset: Long, data: ByteArray, count: Int): Long {
        if (isReadOnly) return -1L
        val file = resolveFile(filePath) ?: return -1L
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(data, 0, count)
        }
        return count.toLong()
    }

    override fun truncate(filePath: String, newSize: Long): Boolean {
        if (isReadOnly) return false
        val file = resolveFile(filePath) ?: return false
        return try {
            RandomAccessFile(file, "rw").use { it.setLength(newSize) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun delete(path: String): Boolean {
        if (isReadOnly) return false
        val file = resolveFile(path) ?: return false
        // Never allow deleting the volume root itself
        if (file.canonicalPath == rootDir.canonicalPath) return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (isReadOnly) return false
        val oldFile = resolveFile(oldPath) ?: return false
        val newFile = resolveFile(newPath) ?: return false
        return oldFile.renameTo(newFile)
    }

    override fun copyNode(sourcePath: String, destPath: String): Boolean {
        if (isReadOnly) return false
        val src = resolveFile(sourcePath) ?: return false
        val dst = resolveFile(destPath) ?: return false
        return try {
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
            } else {
                src.copyTo(dst, overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
