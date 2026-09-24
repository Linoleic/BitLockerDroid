package com.bitlockerdroid.share

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebPortalGenerator {

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var idx = 0
        while (b >= 1024.0 && idx < units.size - 1) {
            b /= 1024.0
            idx++
        }
        return if (idx == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", b, units[idx])
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatItemUrl(parentPath: String, itemName: String, isDirectory: Boolean): String {
        val cleanParent = if (parentPath == "/" || parentPath.isEmpty()) {
            ""
        } else {
            "/" + parentPath.split('/').filter { it.isNotEmpty() }
                .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        }
        val encodedName = URLEncoder.encode(itemName, "UTF-8").replace("+", "%20")
        val trailingSlash = if (isDirectory) "/" else ""
        return "$cleanParent/$encodedName$trailingSlash"
    }

    fun generateDirectoryHtml(
        volumeLabel: String,
        currentPath: String,
        children: List<ShareNode>,
        isReadOnly: Boolean,
        totalBytes: Long = 0L,
        freeBytes: Long = -1L,
        usedBytes: Long = -1L,
        fsType: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("<title>").append(escapeHtml(volumeLabel)).append(" - ").append(escapeHtml(currentPath)).append("</title>\n")
        sb.append("<style>\n")
        sb.append("""
            :root {
                --bg: #f8f9fa;
                --card-bg: #ffffff;
                --text: #212529;
                --text-muted: #6c757d;
                --border: #dee2e6;
                --primary: #0d6efd;
                --primary-hover: #0b5ed7;
                --hover-bg: #f1f3f5;
                --badge-dir: #0d6efd;
                --badge-video: #dc3545;
                --badge-audio: #fd7e14;
                --badge-img: #198754;
                --badge-doc: #6f42c1;
                --badge-file: #6c757d;
            }
            @media (prefers-color-scheme: dark) {
                :root {
                    --bg: #121212;
                    --card-bg: #1e1e1e;
                    --text: #e0e0e0;
                    --text-muted: #a0a0a0;
                    --border: #333333;
                    --primary: #3d8bfd;
                    --primary-hover: #5c9eff;
                    --hover-bg: #282828;
                }
            }
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: var(--bg); color: var(--text); padding: 16px; font-size: 15px; }
            .container { max-width: 1080px; margin: 0 auto; }
            header { background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; padding: 16px 20px; margin-bottom: 16px; box-shadow: 0 2px 6px rgba(0,0,0,0.04); }
            .vol-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 8px; }
            .vol-title-group { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
            .vol-title { font-size: 1.35rem; font-weight: 700; color: var(--text); }
            .badge-fs { font-size: 0.72rem; font-weight: 700; padding: 2px 7px; border-radius: 4px; background: var(--primary); color: #fff; letter-spacing: 0.5px; }
            .badge-mode { font-size: 0.8rem; font-weight: 600; padding: 3px 9px; border-radius: 20px; border: 1px solid var(--border); }
            .badge-ro { color: #198754; background: rgba(25, 135, 84, 0.1); border-color: rgba(25, 135, 84, 0.3); }
            .badge-rw { color: #0d6efd; background: rgba(13, 110, 253, 0.1); border-color: rgba(13, 110, 253, 0.3); }
            .storage-box { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border); }
            .storage-labels { display: flex; justify-content: space-between; align-items: center; font-size: 0.84rem; color: var(--text-muted); margin-bottom: 5px; flex-wrap: wrap; gap: 4px; }
            .storage-used { font-weight: 600; color: var(--text); }
            .progress-bar-bg { width: 100%; height: 7px; background: var(--hover-bg); border-radius: 4px; overflow: hidden; border: 1px solid var(--border); }
            .progress-bar-fill { height: 100%; border-radius: 3px; }
            .fill-green { background: #198754; }
            .fill-amber { background: #fd7e14; }
            .fill-red { background: #dc3545; }
            .folder-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
            .folder-stats { font-size: 0.82rem; color: var(--text-muted); }
            .breadcrumbs { font-size: 0.95rem; color: var(--primary); display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
            .breadcrumbs a { color: var(--primary); text-decoration: none; font-weight: 500; }
            .breadcrumbs a:hover { text-decoration: underline; }
            .breadcrumbs span { color: var(--text-muted); }
            .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
            .search-box { flex: 1; max-width: 380px; padding: 9px 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--card-bg); color: var(--text); font-size: 0.95rem; outline: none; }
            .search-box:focus { border-color: var(--primary); }
            .file-table { width: 100%; border-collapse: collapse; background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; box-shadow: 0 2px 6px rgba(0,0,0,0.04); }
            .file-table th { background: var(--hover-bg); text-align: left; padding: 12px 16px; font-weight: 600; font-size: 0.85rem; color: var(--text-muted); text-transform: uppercase; border-bottom: 1px solid var(--border); }
            .file-table td { padding: 12px 16px; border-bottom: 1px solid var(--border); vertical-align: middle; }
            .file-table tr:last-child td { border-bottom: none; }
            .file-table tr:hover { background: var(--hover-bg); }
            .item-link { color: var(--text); text-decoration: none; font-weight: 500; display: inline-flex; align-items: center; gap: 8px; word-break: break-all; }
            .item-link:hover { color: var(--primary); }
            .tag { display: inline-block; font-size: 0.72rem; font-weight: 600; padding: 2px 6px; border-radius: 4px; color: #fff; text-transform: uppercase; width: 44px; text-align: center; }
            .tag-dir { background: var(--badge-dir); }
            .tag-video { background: var(--badge-video); }
            .tag-audio { background: var(--badge-audio); }
            .tag-img { background: var(--badge-img); }
            .tag-doc { background: var(--badge-doc); }
            .tag-file { background: var(--badge-file); }
            .size-col { font-size: 0.88rem; color: var(--text-muted); white-space: nowrap; width: 120px; }
            .date-col { font-size: 0.88rem; color: var(--text-muted); white-space: nowrap; width: 160px; }
            .act-col { width: 130px; text-align: right; white-space: nowrap; }
            .btn { display: inline-block; padding: 5px 10px; border-radius: 6px; border: 1px solid var(--border); background: var(--card-bg); color: var(--text); font-size: 0.82rem; text-decoration: none; cursor: pointer; transition: background 0.15s; }
            .btn:hover { background: var(--hover-bg); border-color: var(--primary); }
            .btn-primary { background: var(--primary); color: #fff; border-color: var(--primary); }
            .btn-primary:hover { background: var(--primary-hover); }
            .modal-bg { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.85); z-index: 1000; align-items: center; justify-content: center; padding: 20px; }
            .modal-content { max-width: 90vw; max-height: 85vh; background: var(--card-bg); border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
            .modal-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 18px; border-bottom: 1px solid var(--border); font-weight: 600; }
            .modal-close { background: none; border: none; font-size: 1.5rem; color: var(--text); cursor: pointer; }
            .modal-body { display: flex; align-items: center; justify-content: center; padding: 16px; overflow: auto; }
            .modal-body video, .modal-body audio, .modal-body img { max-width: 100%; max-height: 70vh; }
            @media (max-width: 640px) {
                .date-col { display: none; }
                .size-col { width: 80px; }
                .act-col { width: 80px; }
            }
        """.trimIndent())
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<div class=\"container\">\n")

        // Header
        sb.append("<header>\n")
        sb.append("  <div class=\"vol-header\">\n")
        sb.append("    <div class=\"vol-title-group\">\n")
        sb.append("      <span class=\"vol-title\">").append(escapeHtml(volumeLabel)).append("</span>\n")
        if (fsType.isNotBlank()) {
            sb.append("      <span class=\"badge-fs\">").append(escapeHtml(fsType.uppercase())).append("</span>\n")
        }
        sb.append("    </div>\n")
        val modeClass = if (isReadOnly) "badge-ro" else "badge-rw"
        val modeText = if (isReadOnly) "Read-Only" else "Read / Write"
        sb.append("    <div class=\"badge-mode ").append(modeClass).append("\">").append(modeText).append("</div>\n")
        sb.append("  </div>\n")

        // Storage statistics progress bar if available
        if (totalBytes > 0L) {
            val used = if (usedBytes >= 0L) usedBytes else if (freeBytes >= 0L) (totalBytes - freeBytes).coerceAtLeast(0L) else 0L
            val free = if (freeBytes >= 0L) freeBytes else (totalBytes - used).coerceAtLeast(0L)
            val usedPct = ((used.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            val fillClass = when {
                usedPct >= 90 -> "fill-red"
                usedPct >= 75 -> "fill-amber"
                else -> "fill-green"
            }
            sb.append("  <div class=\"storage-box\">\n")
            sb.append("    <div class=\"storage-labels\">\n")
            sb.append("      <span class=\"storage-used\">Used: ").append(formatSize(used)).append(" (").append(usedPct).append("%)</span>\n")
            sb.append("      <span>Free: ").append(formatSize(free)).append(" / Total: ").append(formatSize(totalBytes)).append("</span>\n")
            sb.append("    </div>\n")
            sb.append("    <div class=\"progress-bar-bg\">\n")
            sb.append("      <div class=\"progress-bar-fill ").append(fillClass).append("\" style=\"width: ").append(usedPct).append("%;\"></div>\n")
            sb.append("    </div>\n")
            sb.append("  </div>\n")
        }

        // Folder statistics & Breadcrumbs
        val dirCount = children.count { it.isDirectory }
        val fileCount = children.count { !it.isDirectory }
        val summaryText = when {
            dirCount > 0 && fileCount > 0 -> "$dirCount folders, $fileCount files"
            dirCount > 0 -> "$dirCount folders"
            fileCount > 0 -> "$fileCount files"
            else -> "Empty directory"
        }
        sb.append("  <div class=\"folder-bar\">\n")
        sb.append("    <div class=\"breadcrumbs\">\n")
        sb.append("      <a href=\"/\">Root</a>\n")
        val segments = currentPath.split('/').filter { it.isNotEmpty() }
        for (i in segments.indices) {
            val s = segments[i]
            sb.append("      <span>/</span>\n")
            if (i == segments.size - 1) {
                sb.append("      <span>").append(escapeHtml(s)).append("</span>\n")
            } else {
                val subSegments = segments.take(i + 1)
                val encodedSubPath = "/" + subSegments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "/"
                sb.append("      <a href=\"").append(encodedSubPath).append("\">").append(escapeHtml(s)).append("</a>\n")
            }
        }
        sb.append("    </div>\n")
        sb.append("    <div class=\"folder-stats\">").append(summaryText).append("</div>\n")
        sb.append("  </div>\n")
        sb.append("</header>\n")

        // Toolbar
        sb.append("<div class=\"toolbar\">\n")
        sb.append("  <input type=\"text\" id=\"filter\" class=\"search-box\" placeholder=\"Filter files in folder...\">\n")
        sb.append("</div>\n")

        // File Table
        sb.append("<table class=\"file-table\" id=\"fileList\">\n")
        sb.append("  <thead><tr><th>Name</th><th class=\"size-col\">Size</th><th class=\"date-col\">Modified</th><th class=\"act-col\">Action</th></tr></thead>\n")
        sb.append("  <tbody>\n")

        // Parent directory link if not root
        if (currentPath != "/") {
            val parentSegments = currentPath.split('/').filter { it.isNotEmpty() }.dropLast(1)
            val parentUrl = if (parentSegments.isEmpty()) "/" else "/" + parentSegments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "/"
            sb.append("    <tr>\n")
            sb.append("      <td colspan=\"4\"><a class=\"item-link\" href=\"").append(parentUrl).append("\"><span class=\"tag tag-dir\">DIR</span> .. (Parent Directory)</a></td>\n")
            sb.append("    </tr>\n")
        }

        for (node in children) {
            val itemUrl = formatItemUrl(currentPath, node.name, node.isDirectory)
            val isMedia = isMediaFile(node.extension)

            val tagClass = when {
                node.isDirectory -> "tag-dir"
                isTag(node.extension, "video") -> "tag-video"
                isTag(node.extension, "audio") -> "tag-audio"
                isTag(node.extension, "image") -> "tag-img"
                isTag(node.extension, "doc") -> "tag-doc"
                else -> "tag-file"
            }
            val tagLabel = if (node.isDirectory) "DIR" else node.extension.uppercase().take(4).ifEmpty { "FILE" }

            sb.append("    <tr class=\"file-row\" data-name=\"").append(escapeHtml(node.name.lowercase())).append("\">\n")
            sb.append("      <td>\n")
            if (node.isDirectory) {
                sb.append("        <a class=\"item-link\" href=\"").append(itemUrl).append("\"><span class=\"tag ").append(tagClass).append("\">").append(tagLabel).append("</span> ").append(escapeHtml(node.name)).append("</a>\n")
            } else {
                val previewAttr = if (isMedia) {
                    " onclick=\"openPreview(event, '$itemUrl', '${node.extension.lowercase()}', '${escapeHtml(node.name)}')\""
                } else ""
                sb.append("        <a class=\"item-link\" href=\"").append(itemUrl).append("\"").append(previewAttr).append("><span class=\"tag ").append(tagClass).append("\">").append(tagLabel).append("</span> ").append(escapeHtml(node.name)).append("</a>\n")
            }
            sb.append("      </td>\n")
            sb.append("      <td class=\"size-col\">").append(if (node.isDirectory) "-" else formatSize(node.size)).append("</td>\n")
            sb.append("      <td class=\"date-col\">").append(dateFormat.format(Date(node.lastModified))).append("</td>\n")
            sb.append("      <td class=\"act-col\">\n")
            if (!node.isDirectory) {
                sb.append("        <a class=\"btn\" href=\"").append(itemUrl).append("\" download>Download</a>\n")
            }
            sb.append("      </td>\n")
            sb.append("    </tr>\n")
        }

        if (children.isEmpty()) {
            sb.append("    <tr><td colspan=\"4\" style=\"text-align:center; padding: 30px; color: var(--text-muted);\">Empty directory</td></tr>\n")
        }

        sb.append("  </tbody>\n</table>\n")

        // Modal for media preview
        sb.append("""
            <div id="mediaModal" class="modal-bg" onclick="closeModal(event)">
                <div class="modal-content" onclick="event.stopPropagation()">
                    <div class="modal-header">
                        <span id="modalTitle">Preview</span>
                        <button class="modal-close" onclick="closeModal()">&times;</button>
                    </div>
                    <div id="modalBody" class="modal-body"></div>
                </div>
            </div>
            <script>
                document.getElementById('filter').addEventListener('input', function(e) {
                    var q = e.target.value.toLowerCase().trim();
                    var rows = document.querySelectorAll('.file-row');
                    rows.forEach(function(row) {
                        var name = row.getAttribute('data-name');
                        row.style.display = (!q || name.indexOf(q) !== -1) ? '' : 'none';
                    });
                });
                function openPreview(e, url, ext, title) {
                    var videoExts = ['mp4','m4v','mkv','webm','mov','avi'];
                    var audioExts = ['mp3','flac','wav','ogg','m4a','aac'];
                    var imgExts = ['jpg','jpeg','png','gif','webp','svg','bmp'];
                    var body = document.getElementById('modalBody');
                    document.getElementById('modalTitle').textContent = title;
                    if (videoExts.indexOf(ext) !== -1) {
                        e.preventDefault();
                        body.innerHTML = '<video controls autoplay src="' + url + '">Your browser does not support HTML5 video.</video>';
                        document.getElementById('mediaModal').style.display = 'flex';
                    } else if (audioExts.indexOf(ext) !== -1) {
                        e.preventDefault();
                        body.innerHTML = '<audio controls autoplay src="' + url + '">Your browser does not support HTML5 audio.</audio>';
                        document.getElementById('mediaModal').style.display = 'flex';
                    } else if (imgExts.indexOf(ext) !== -1) {
                        e.preventDefault();
                        body.innerHTML = '<img src="' + url + '" alt="' + title + '">';
                        document.getElementById('mediaModal').style.display = 'flex';
                    }
                }
                function closeModal(e) {
                    var modal = document.getElementById('mediaModal');
                    modal.style.display = 'none';
                    document.getElementById('modalBody').innerHTML = '';
                }
            </script>
        """.trimIndent())

        sb.append("</div>\n</body>\n</html>")
        return sb.toString()
    }

    private fun isMediaFile(ext: String): Boolean {
        val e = ext.lowercase()
        return isTag(e, "video") || isTag(e, "audio") || isTag(e, "image")
    }

    private fun isTag(ext: String, category: String): Boolean {
        val e = ext.lowercase()
        return when (category) {
            "video" -> e in listOf("mp4", "m4v", "mkv", "webm", "mov", "avi")
            "audio" -> e in listOf("mp3", "flac", "wav", "ogg", "m4a", "aac")
            "image" -> e in listOf("jpg", "jpeg", "png", "gif", "webp", "svg", "bmp")
            "doc" -> e in listOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md", "json", "xml")
            else -> false
        }
    }
}
