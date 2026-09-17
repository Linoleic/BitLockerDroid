package com.bitlockerdroid.provider

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import com.bitlockerdroid.ntfs.VolumeDirEntry
import com.bitlockerdroid.ntfs.VolumeReader
import com.bitlockerdroid.service.DislockerCore
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.PreferenceHelper
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * DocumentsProvider exposing unlocked BitLocker volumes to the system file
 * manager. Each unlocked volume appears as a root; directories and files map
 * to MFT record numbers via the NTFS reader.
 *
 * Document IDs are "<devicePathHash>:<mftRecord>" with the root record being 5.
 */
class BitLockerDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "BitLockerProvider"

        const val AUTHORITY = "com.bitlockerdroid.provider"
        const val COLUMN_FLAGS = "flags"

        /** Segment separator for document IDs. */
        private const val SEP = ":"

        @Volatile
        private var activeInstance: BitLockerDocumentsProvider? = null

        /**
         * Waits (bounded) for all in-flight SAF pipe writes to finish.
         * Safe-eject step: call before closing/locking a session so a write
         * pipeline is never cut off mid-transaction.
         */
        fun drainActiveWrites(timeoutMs: Long = 8000) {
            val inst = activeInstance ?: return
            val deadline = System.currentTimeMillis() + timeoutMs
            for ((_, thread) in inst.activeWrites) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    thread.join(remaining)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        /**
         * Stable per-volume root id. DocumentsUI opens a root Uri
         * (content://authority/root/<rootId>) without calling findDocumentPath,
         * so the Open button can jump straight into a volume. The id must be
         * unique per volume because multiple drives can be unlocked at once.
         */
        fun rootIdFor(devicePath: String, serial: Long): String {
            val b64 = android.util.Base64.encodeToString(
                devicePath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "$b64$SEP$serial"
        }

        /**
         * Creates an Intent to open the unlocked volume in the system DocumentsUI
         * or preferred file manager.
         */
        fun createOpenVolumeIntent(context: Context, devicePath: String, serial: Long): Intent {
            val rootId = rootIdFor(devicePath, serial)
            val rootUri = DocumentsContract.buildRootUri(AUTHORITY, rootId)
            val pm = context.packageManager

            // Preferred DocumentsUI packages in order
            val knownPackages = listOf("com.google.android.documentsui", "com.android.documentsui")
            for (pkg in knownPackages) {
                val candidateIntent = Intent(Intent.ACTION_VIEW).apply {
                    setPackage(pkg)
                    setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                if (candidateIntent.resolveActivity(pm) != null) {
                    return candidateIntent
                }
            }

            // Fallback 1: Implicit intent with MIME_TYPE_DIR
            val implicitDirIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            if (implicitDirIntent.resolveActivity(pm) != null) {
                return implicitDirIntent
            }

            // Fallback 2: Implicit intent with root URI
            val implicitRootIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rootUri, DocumentsContract.Root.MIME_TYPE_ITEM)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            if (implicitRootIntent.resolveActivity(pm) != null) {
                return implicitRootIntent
            }

            // Fallback 3: Generic implicit view intent
            return Intent(Intent.ACTION_VIEW, rootUri).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        }

        /** Encode the device path + volume serial into the docId so the
         *  provider can resolve it even if this process was killed and the
         *  session map is empty. The serial distinguishes volumes that reuse
         *  the same vold node path (e.g. after swapping the USB drive), so the
         *  file manager treats a new drive as a fresh root and reloads. */
        fun docIdFor(devicePath: String, serial: Long, record: Long): String {
            val b64 = android.util.Base64.encodeToString(
                devicePath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "$b64$SEP$serial$SEP$record"
        }

        private fun devicePathFrom(docId: String): String? {
            val b64 = docId.substringBefore(SEP)
            return try {
                val raw = android.util.Base64.decode(
                    b64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                String(raw, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        private fun recordFrom(docId: String): Long {
            // docId = b64path : serial : record
            val parts = docId.split(SEP)
            if (parts.size >= 3) {
                val lastPart = parts[2]
                if (lastPart.contains('/')) {
                    val parentRecord = lastPart.substringBefore('/').toLongOrNull() ?: -1L
                    val subPath = lastPart.substringAfter('/')
                    val dev = devicePathFrom(docId)
                    val core = dev?.let { UnlockManager.get(it) }
                    if (core != null && parentRecord != -1L) {
                        val parentPath = core.resolvePath(parentRecord) ?: "/"
                        val fullPath = if (parentPath == "/") "/$subPath" else "$parentPath/$subPath"
                        val existing = core.getRecordForPath(fullPath)
                        if (existing != null) return existing
                        val entries = try { core.reader.listDirectory(parentRecord) } catch (_: Exception) { emptyList() }
                        val found = entries.find { it.name.equals(subPath, ignoreCase = true) }
                        if (found != null) {
                            core.registerPath(found.ref, fullPath, parentRecord)
                            return found.ref
                        }
                        return -1L
                    }
                }
                return lastPart.toLongOrNull() ?: -1L
            }
            return -1L
        }

        fun notifyRootsChanged(context: android.content.Context) {
            try {
                val resolver = context.contentResolver
                val rootsUri = DocumentsContract.buildRootsUri(AUTHORITY)
                resolver.notifyChange(rootsUri, null)
                for (v in UnlockManager.unlockedVolumes) {
                    val core = UnlockManager.get(v.devicePath)
                    val rootRef = core?.reader?.rootRef ?: 0L
                    val serial = try { core?.reader?.volumeSerial() ?: 0L } catch (_: Exception) { 0L }
                    val rootDocId = docIdFor(v.devicePath, serial, rootRef)
                    resolver.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, rootDocId), null)
                    resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, rootDocId), null)
                }
                Log.i(TAG, "notifyRootsChanged: notified system that roots and root documents changed")
            } catch (e: Exception) {
                Log.w(TAG, "notifyRootsChanged failed", e)
            }
        }
    }

    override fun onCreate(): Boolean {
        activeInstance = this
        return true
    }

    override fun shutdown() {
        if (activeInstance === this) activeInstance = null
        super.shutdown()
    }

    private val appContext: android.content.Context
        get() = super.getContext() ?: com.bitlockerdroid.util.ContextProvider.app

    data class PendingSync(val file: java.io.File, val path: String, val record: Long)
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<Long, PendingSync>()
    private val activeWrites = java.util.concurrent.ConcurrentHashMap<String, Thread>()

    private val syncThread = android.os.HandlerThread("SafSyncThread").apply { start() }
    private val syncHandler = android.os.Handler(syncThread.looper)



    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        LogFile.write("provider", "call method=$method arg=$arg")
        if (method == "create_document") {
            val parentDocId = arg ?: extras?.getString("parent_doc_id") ?: return null
            val mimeType = extras?.getString("mime_type") ?: "text/plain"
            val displayName = extras?.getString("display_name") ?: "test.txt"
            val newDocId = createDocument(parentDocId, mimeType, displayName)
            val out = Bundle()
            out.putString("document_id", newDocId)
            out.putString("uri", "content://$AUTHORITY/document/$newDocId")
            return out
        }
        if (method == "delete_document") {
            val docId = arg ?: extras?.getString("document_id") ?: return null
            deleteDocument(docId)
            val out = Bundle()
            out.putBoolean("success", true)
            return out
        }
        if (method == "rename_document") {
            val docId = if (arg != null && arg.contains('|')) arg.substringBefore('|') else (arg ?: extras?.getString("document_id") ?: return null)
            val newName = if (arg != null && arg.contains('|')) arg.substringAfter('|') else (extras?.getString("display_name") ?: return null)
            val resId = renameDocument(docId, newName)
            val out = Bundle()
            out.putString("document_id", resId)
            return out
        }
        if (method == "copy_document") {
            val srcId = extras?.getString("source_document_id") ?: (if (arg != null && arg.contains('|')) arg.substringBefore('|') else return null)
            val targetParentId = extras?.getString("target_parent_document_id") ?: (if (arg != null && arg.contains('|')) arg.substringAfter('|') else return null)
            val newDocId = copyDocument(srcId, targetParentId)
            val out = Bundle()
            out.putString("document_id", newDocId)
            return out
        }
        if (method == "lock_volume") {
            val dev = arg ?: extras?.getString("device_path") ?: return null
            UnlockManager.lock(dev)
            val out = Bundle()
            out.putBoolean("success", true)
            return out
        }
        if (method == "refresh_scan") {
            UnlockManager.clearManualLockSuppression()
            com.bitlockerdroid.service.BitLockerDetector.scanAndDetect(appContext)
            val out = Bundle()
            out.putBoolean("success", true)
            return out
        }
        if (method == "move_document") {
            val parts = arg?.split('|') ?: emptyList()
            val srcId = extras?.getString("source_document_id") ?: (if (parts.size >= 3) parts[0] else return null)
            val srcParentId = extras?.getString("source_parent_document_id") ?: (if (parts.size >= 3) parts[1] else return null)
            val targetParentId = extras?.getString("target_parent_document_id") ?: (if (parts.size >= 3) parts[2] else return null)
            val newDocId = moveDocument(srcId, srcParentId, targetParentId)
            val out = Bundle()
            out.putString("document_id", newDocId)
            return out
        }
        if (method == "search_documents") {
            if (UnlockManager.unlockedVolumes.isEmpty()) {
                UnlockManager.restoreRemembered(appContext)
            }
            val rootId = extras?.getString("root_id")
                ?: UnlockManager.unlockedVolumes.firstOrNull()?.let {
                    val core = UnlockManager.get(it.devicePath)
                    val serial = try { core?.reader?.volumeSerial() ?: 0L } catch (_: Exception) { 0L }
                    rootIdFor(it.devicePath, serial)
                } ?: return null
            val query = arg ?: extras?.getString("query") ?: ""
            val cursor = querySearchDocuments(rootId, query, null)
            val names = ArrayList<String>()
            val paths = ArrayList<String>()
            val docIds = ArrayList<String>()
            val nameIdx = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val summaryIdx = cursor.getColumnIndex(Document.COLUMN_SUMMARY)
            val idIdx = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) names.add(cursor.getString(nameIdx))
                if (summaryIdx >= 0) paths.add(cursor.getString(summaryIdx) ?: "")
                if (idIdx >= 0) docIds.add(cursor.getString(idIdx))
            }
            cursor.close()
            val out = Bundle()
            out.putStringArrayList("names", names)
            out.putStringArrayList("paths", paths)
            out.putStringArrayList("doc_ids", docIds)
            out.putInt("count", names.size)
            return out
        }
        if (method == "run_benchmark") {
            val dev = arg ?: extras?.getString("device_path") ?: return null
            val core = UnlockManager.get(dev) ?: return null
            val res = kotlinx.coroutines.runBlocking {
                com.bitlockerdroid.util.BenchmarkEngine.runBenchmark(core, appContext) { _, _ -> }
            }
            val out = Bundle()
            out.putDouble("seq_mb_s", res.sequentialReadMbPerSec)
            out.putDouble("random_4k_ms", res.random4kLatencyMs)
            out.putDouble("random_4k_iops", res.random4kIops)
            out.putString("usb_speed_desc", res.usbSpeedDesc)
            out.putString("assessment", res.assessment)
            return out
        }
        if (method == "set_mount_read_only") {
            val devPath = extras?.getString("device_path") ?: arg
            val guid = extras?.getString("guid")
            val ro = if (extras != null && extras.containsKey("read_only")) extras.getBoolean("read_only") else (arg == "true")
            if (!devPath.isNullOrBlank() || !guid.isNullOrBlank()) {
                PreferenceHelper.setVolumeReadOnly(appContext, guid, devPath, ro)
            } else {
                PreferenceHelper.mountReadOnly = ro
            }
            notifyRootsChanged(appContext)
            val out = Bundle()
            out.putBoolean("read_only", ro)
            return out
        }
        if (method == "switch_mode_and_restart") {
            val target = if (extras != null && extras.containsKey("target_root")) extras.getBoolean("target_root") else (arg == "true")
            UnlockManager.safeEjectAll()
            try {
                com.bitlockerdroid.service.VirtualStorageMountManager.unmountAll()
            } catch (_: Throwable) {}
            PreferenceHelper.useRootAccess = target
            com.bitlockerdroid.util.RootAccess.invalidateCache()
            com.bitlockerdroid.util.AppRestarter.restartApp(appContext)
            val out = Bundle()
            out.putBoolean("success", true)
            return out
        }
        try {
            val res = super.call(method, arg, extras)
            LogFile.write("provider", "call SUCCESS method=$method arg=$arg")
            return res
        } catch (t: Throwable) {
            LogFile.write("provider", "call EXCEPTION method=$method arg=$arg: ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))
        // If this process was killed since unlock, restore saved volumes.
        if (UnlockManager.unlockedVolumes.isEmpty()) {
            val n = UnlockManager.restoreRemembered(appContext)
            Log.i(TAG, "queryRoots: restored $n remembered volumes")
        }
        val volumes = UnlockManager.unlockedVolumes
        for (v in volumes) {
            val core = UnlockManager.get(v.devicePath)
            val rootRef = core?.reader?.rootRef ?: 0L
            val serial = try { core?.reader?.volumeSerial() ?: 0L } catch (e: Exception) { 0L }
            val row = result.newRow()
            row.add(Root.COLUMN_ROOT_ID, rootIdFor(v.devicePath, serial))
            row.add(Root.COLUMN_DOCUMENT_ID, docIdFor(v.devicePath, serial, rootRef))
            row.add(Root.COLUMN_QUERY_ARGS, Bundle())
            row.add(Root.COLUMN_TITLE, v.label)
            row.add(Root.COLUMN_SUMMARY, "BitLocker encrypted volume")
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            row.add(Root.COLUMN_ICON, com.bitlockerdroid.R.drawable.ic_drive_bitlocker)
            if (v.freeBytes > 0L) {
                row.add(Root.COLUMN_AVAILABLE_BYTES, v.freeBytes)
            }
            if (v.size > 0L) {
                row.add(Root.COLUMN_CAPACITY_BYTES, v.size)
            }
            val isRo = PreferenceHelper.isVolumeReadOnly(appContext, core?.volumeGuid, v.devicePath)
            val canWrite = (core?.writer?.isMounted == true) && !isRo
            var rootFlags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH
            if (canWrite) {
                rootFlags = rootFlags or Root.FLAG_SUPPORTS_CREATE
            }
            row.add(Root.COLUMN_FLAGS, rootFlags)
        }
        result.setNotificationUri(appContext.contentResolver, DocumentsContract.buildRootsUri(AUTHORITY))
        return result
    }

    override fun getDocumentType(documentId: String): String {
        val core = coreFor(documentId) ?: return Document.MIME_TYPE_DIR
        val rec = try { core.getEntry(recordFrom(documentId)) } catch (e: Exception) { null }
        if (rec?.isDirectory == true) return Document.MIME_TYPE_DIR
        val name = rec?.fileName ?: if (documentId.contains('/')) documentId.substringAfterLast('/') else ""
        val ext = name.substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            if (mime != null) return mime
        }
        return "application/octet-stream"
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        LogFile.write("provider", "queryDocument: docId=$documentId proj=${projection?.joinToString()}")
        try {
            val result = MatrixCursor(resolveDocumentProjection(projection))
            addDocumentRow(result, documentId)
            if (result.count == 0) {
                LogFile.write("provider", "queryDocument: WARNING 0 rows returned for $documentId")
            } else {
                LogFile.write("provider", "queryDocument: SUCCESS for $documentId (1 row)")
            }
            result.setNotificationUri(appContext.contentResolver, DocumentsContract.buildDocumentUri(AUTHORITY, documentId))
            return result
        } catch (t: Throwable) {
            LogFile.write("provider", "queryDocument EXCEPTION docId=$documentId: ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        LogFile.write("provider", "queryChildDocuments: parent=$parentDocumentId")
        try {
            val result = MatrixCursor(resolveDocumentProjection(projection))
            val core = coreFor(parentDocumentId)
            if (core == null) {
                LogFile.write("provider", "queryChildDocuments: coreFor returned null for $parentDocumentId")
                return result
            }
            val record = recordFrom(parentDocumentId)
            val parentPath = core.resolvePath(record) ?: "/"
            val entries = core.reader.listDirectory(record)
            LogFile.write("provider", "queryChildDocuments: listDirectory returned ${entries.size} entries for $parentPath")
            val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }
            val hideSvi = try { PreferenceHelper.hideSviFolder } catch (_: Throwable) { true }
            for (e in entries) {
                if (hideSvi && e.name.equals("System Volume Information", ignoreCase = true)) {
                    continue
                }
                val childPath = if (parentPath == "/") "/${e.name}" else "$parentPath/${e.name}"
                core.registerPath(e.ref, childPath, record)
                addDocumentRow(result, docIdFor(core.devicePath, serial, e.ref), e)
            }
            result.setNotificationUri(appContext.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
            return result
        } catch (t: Throwable) {
            LogFile.write("provider", "queryChildDocuments EXCEPTION parent=$parentDocumentId: ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    override fun querySearchDocuments(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle
    ): Cursor {
        val q = queryArgs.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME)
            ?: queryArgs.getString("android:query-arg-display-name")
            ?: queryArgs.getString("query")
            ?: queryArgs.getString(Intent.EXTRA_CONTENT_QUERY)
            ?: ""
        LogFile.write("provider", "querySearchDocuments (queryArgs): rootId=$rootId query='$q'")
        return querySearchDocuments(rootId, q, projection)
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        LogFile.write("provider", "querySearchDocuments: rootId=$rootId query='$query'")
        val result = MatrixCursor(resolveDocumentProjection(projection))
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return result
        }

        val core = coreFor(rootId)
            ?: (if (UnlockManager.activeSessions.size == 1) UnlockManager.activeSessions.first() else null)
            ?: run {
                LogFile.write("provider", "querySearchDocuments: core not found for $rootId")
                return result
            }

        val rootRef = core.reader.rootRef
        val serial = try { core.reader.volumeSerial() } catch (_: Exception) { 0L }
        val hideSvi = try { PreferenceHelper.hideSviFolder } catch (_: Throwable) { true }

        val maxResults = 500
        val maxDirs = 2000
        val timeLimitMs = 4000L
        val startTime = System.currentTimeMillis()

        val queue = ArrayDeque<Pair<Long, String>>() // (dirRecord, dirPath)
        val visited = HashSet<Long>()
        queue.add(Pair(rootRef, "/"))
        visited.add(rootRef)
        var dirsScanned = 0

        try {
            while (queue.isNotEmpty() && result.count < maxResults) {
                if (System.currentTimeMillis() - startTime > timeLimitMs) {
                    LogFile.write("provider", "querySearchDocuments: search reached time limit of ${timeLimitMs}ms")
                    break
                }
                if (dirsScanned++ >= maxDirs) {
                    LogFile.write("provider", "querySearchDocuments: search reached dir limit of $maxDirs")
                    break
                }

                val (parentRecord, parentPath) = queue.removeFirst()
                val entries = try {
                    core.reader.listDirectory(parentRecord)
                } catch (e: Exception) {
                    LogFile.write("provider", "querySearchDocuments: listDirectory failed for $parentPath: ${e.message}")
                    emptyList()
                }

                for (e in entries) {
                    if (hideSvi && e.name.equals("System Volume Information", ignoreCase = true)) {
                        continue
                    }
                    val childPath = if (parentPath == "/") "/${e.name}" else "$parentPath/${e.name}"
                    core.registerPath(e.ref, childPath, parentRecord)

                    if (matchesSearchQuery(e.name, trimmedQuery)) {
                        val docId = docIdFor(core.devicePath, serial, e.ref)
                        val parentDisplay = if (parentPath == "/") "/" else parentPath
                        addDocumentRow(result, docId, e, summary = parentDisplay)
                        if (result.count >= maxResults) break
                    }

                    if (e.isDirectory && visited.add(e.ref)) {
                        queue.add(Pair(e.ref, childPath))
                    }
                }
            }
            LogFile.write("provider", "querySearchDocuments: found ${result.count} matches in $dirsScanned dirs (${System.currentTimeMillis() - startTime}ms)")
            result.setNotificationUri(
                appContext.contentResolver,
                DocumentsContract.buildSearchDocumentsUri(AUTHORITY, rootId, query)
            )
            return result
        } catch (t: Throwable) {
            LogFile.write("provider", "querySearchDocuments EXCEPTION rootId=$rootId query='$query': ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    private fun matchesSearchQuery(fileName: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false

        // Exact or substring match (case-insensitive)
        if (fileName.contains(q, ignoreCase = true)) return true

        // Wildcard pattern match (e.g. *.txt, report*2026, test?.doc)
        if (q.contains('*') || q.contains('?')) {
            try {
                val sb = java.lang.StringBuilder("^")
                for (ch in q) {
                    when (ch) {
                        '*' -> sb.append(".*")
                        '?' -> sb.append(".")
                        '$', '^', '[', ']', '(', ')', '{', '}', '|', '\\', '.', '+', '-' -> {
                            sb.append('\\').append(ch)
                        }
                        else -> sb.append(ch)
                    }
                }
                sb.append("$")
                if (Regex(sb.toString(), RegexOption.IGNORE_CASE).containsMatchIn(fileName)) {
                    return true
                }
            } catch (_: Exception) {}
        }

        // Multi-keyword match (all keywords present)
        val tokens = q.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            return tokens.all { fileName.contains(it, ignoreCase = true) }
        }

        return false
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        LogFile.write("provider", "isChildDocument: parent=$parentDocumentId child=$documentId")
        try {
            val core = coreFor(parentDocumentId) ?: return false
            val parentRecord = recordFrom(parentDocumentId)
            val record = recordFrom(documentId)
            if (record == parentRecord) return false
            if (parentRecord == core.reader.rootRef) {
                LogFile.write("provider", "isChildDocument: true (parent is volume root)")
                return true
            }

            val childPath = core.resolvePath(record)
            val parentPath = core.resolvePath(parentRecord)
            if (childPath != null && parentPath != null) {
                val prefix = if (parentPath == "/") "/" else "$parentPath/"
                if (childPath.startsWith(prefix)) {
                    LogFile.write("provider", "isChildDocument: true by path prefix ($childPath in $parentPath)")
                    return true
                }
            }

            var cur = record
            var depth = 0
            while (cur != core.reader.rootRef && depth < 32) {
                val p = core.parentOf(cur)
                if (p == cur) break
                cur = p
                if (cur == parentRecord) {
                    LogFile.write("provider", "isChildDocument: true by parentOf chain")
                    return true
                }
                depth++
            }
            LogFile.write("provider", "isChildDocument: false for parent=$parentDocumentId child=$documentId")
            return false
        } catch (t: Throwable) {
            LogFile.write("provider", "isChildDocument EXCEPTION: ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    private fun buildUniqueDisplayName(dirEntries: List<com.bitlockerdroid.ntfs.VolumeDirEntry>, name: String, isDir: Boolean): String {
        if (dirEntries.none { it.name.equals(name, ignoreCase = true) }) {
            return name
        }
        val dotIndex = name.lastIndexOf('.')
        val nameBase = if (dotIndex > 0 && !isDir) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0 && !isDir) name.substring(dotIndex) else ""
        var counter = 1
        while (dirEntries.any { it.name.equals("$nameBase ($counter)$ext", ignoreCase = true) }) {
            counter++
        }
        return "$nameBase ($counter)$ext"
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        Log.i(TAG, "createDocument: parent=$parentDocumentId, mime=$mimeType, name=$displayName")
        val core = coreFor(parentDocumentId) ?: throw SecurityException("Volume is locked")
        val parentDevPath = core.devicePath
        if (PreferenceHelper.isVolumeReadOnly(appContext, core.volumeGuid, parentDevPath)) {
            throw UnsupportedOperationException("Volume mounted in read-only mode")
        }
        val writer = core.writer ?: throw UnsupportedOperationException("Writing not supported on this volume")
        val parentRecord = recordFrom(parentDocumentId)
        val parentPath = core.resolvePath(parentRecord) ?: "/"

        val isDir = (mimeType == Document.MIME_TYPE_DIR)
        val existingEntries = try { core.reader.listDirectory(parentRecord) } catch (_: Exception) { emptyList() }
        val actualName = buildUniqueDisplayName(existingEntries, displayName, isDir)

        val createdRef = writer.createFile(parentPath, actualName, isDir)
        if (createdRef < 0) {
            throw IllegalStateException("Failed to create $actualName in $parentPath: error=$createdRef")
        }

        val newPath = if (parentPath == "/") "/$actualName" else "$parentPath/$actualName"
        core.invalidateCache()

        val dirEntries = try { core.reader.listDirectory(parentRecord) } catch (_: Exception) { emptyList() }
        val found = dirEntries.find { it.name.equals(actualName, ignoreCase = true) && it.isDirectory == isDir }
        val newRecord = found?.ref ?: if (createdRef > 0) createdRef else (0x80000000L or (System.nanoTime() and 0x7FFFFFFFL))

        core.registerPath(newRecord, newPath, parentRecord)
        if (found != null && found.ref != newRecord) {
            core.setRecordAlias(newRecord, found.ref)
        }
        core.registerCreatedEntry(
            com.bitlockerdroid.ntfs.VolumeEntry(
                ref = newRecord,
                isDirectory = isDir,
                fileName = actualName,
                fileSize = 0L,
                lastModified = System.currentTimeMillis()
            )
        )
        notifyChange(parentDocumentId)

        val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }
        val newDocId = docIdFor(core.devicePath, serial, newRecord)
        notifyChange(newDocId)
        Log.i(TAG, "createDocument succeeded: newDocId=$newDocId, ref=$newRecord, path=$newPath")
        return newDocId
    }

    override fun deleteDocument(documentId: String) {
        LogFile.write("provider", "deleteDocument: docId=$documentId")
        val core = coreFor(documentId) ?: throw SecurityException("Volume is locked")
        val writer = core.writer ?: throw UnsupportedOperationException("Writing not supported on this volume")
        val record = recordFrom(documentId)
        val parentRecord = if (record != -1L) core.parentOf(record) else {
            val parts = documentId.split(SEP)
            if (parts.size >= 3 && parts[2].contains('/')) {
                parts[2].substringBefore('/').toLongOrNull() ?: core.reader.rootRef
            } else core.reader.rootRef
        }
        val path = if (record != -1L) core.resolvePath(record) else {
            val parts = documentId.split(SEP)
            if (parts.size >= 3 && parts[2].contains('/')) {
                val pRec = parts[2].substringBefore('/').toLongOrNull() ?: core.reader.rootRef
                val sub = parts[2].substringAfter('/')
                val pPath = core.resolvePath(pRec) ?: "/"
                if (pPath == "/") "/$sub" else "$pPath/$sub"
            } else null
        }

        if (path == null) {
            // A real (MFT-backed) record still resolves through its own
            // parentRecord chain, so an unresolvable record means the docId
            // was synthetic (create_document marker) and its alias mapping
            // was lost — e.g. after a process restart. Refuse with an error
            // instead of silently reporting success while the file survives
            // on the volume.
            val recordExists = record > 0 && record != core.reader.rootRef &&
                try { core.getEntry(record) != null } catch (_: Exception) { false }
            if (!recordExists) {
                LogFile.write("provider", "deleteDocument: stale unresolvable docId=$documentId — refusing silent success")
                throw java.io.FileNotFoundException("Document id is stale and no longer resolvable: $documentId")
            }
            LogFile.write("provider", "deleteDocument: Cannot resolve path for docId=$documentId, already non-existent")
            return
        }

        // Wait for any active write on this path before deleting
        activeWrites[path]?.let { thread ->
            try {
                thread.join(3000)
            } catch (_: Exception) {}
        }

        if (path == "/" || record == core.reader.rootRef) {
            throw SecurityException("Cannot delete volume root directory")
        }

        val ok = writer.delete(path)
        if (!ok) {
            val fileName = path.substringAfterLast('/')
            val exists = try {
                core.reader.listDirectory(parentRecord).any { it.name.equals(fileName, ignoreCase = true) }
            } catch (_: Exception) { false }
            if (exists) {
                LogFile.write("provider", "Failed to delete $path")
                throw IllegalStateException("Failed to delete $path")
            } else {
                LogFile.write("provider", "deleteDocument: $path did not exist or was already deleted")
            }
        }

        if (record != -1L) {
            core.removePath(record)
        }
        core.invalidateCache()
        notifyChange(documentId)
        val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }
        notifyChange(docIdFor(core.devicePath, serial, parentRecord))
        LogFile.write("provider", "deleteDocument succeeded for $path")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        LogFile.write("provider", "renameDocument: docId=$documentId, displayName=$displayName")
        val lowerName = displayName.lowercase()
        if (lowerName.endsWith(".rollback") || lowerName.endsWith(".rollback.bak") ||
            lowerName.endsWith(".force_replace_target") || lowerName.endsWith(".compress_rollback") ||
            lowerName.contains(".rollback.")) {
            LogFile.write("provider", "renameDocument: rejecting internal backup/rollback name: $displayName")
            throw UnsupportedOperationException("Atomic rollback renaming not supported for document")
        }
        val core = coreFor(documentId) ?: throw SecurityException("Volume is locked")
        val writer = core.writer ?: throw UnsupportedOperationException("Writing not supported on this volume")
        val record = recordFrom(documentId)
        val effRecord = core.resolveRecord(record)
        val parentRecord = core.parentOf(record)
        val oldPath = core.resolvePath(record)
            ?: throw IllegalStateException("Cannot resolve path for rename")

        // Wait for any active write on oldPath before renaming
        activeWrites[oldPath]?.let { thread ->
            try {
                thread.join(3000)
            } catch (_: Exception) {}
        }

        val parentPath = oldPath.substringBeforeLast('/', "")
        val newPath = if (parentPath.isEmpty()) "/$displayName" else "$parentPath/$displayName"

        if (oldPath.equals(newPath, ignoreCase = false)) {
            LogFile.write("provider", "renameDocument: oldPath == newPath ($newPath), nothing to do")
            return documentId
        }

        val oldTargetRecord = core.getRecordForPath(newPath)
        val ok = writer.rename(oldPath, newPath)
        if (!ok) {
            LogFile.write("provider", "Failed to rename $oldPath -> $newPath")
            throw IllegalStateException("Failed to rename $oldPath -> $newPath")
        }

        core.updatePathAfterRename(oldPath, newPath, record, parentRecord)
        if (oldTargetRecord != null && oldTargetRecord != record && oldTargetRecord != effRecord) {
            core.setRecordAlias(oldTargetRecord, effRecord)
            core.updatePathAfterRename(newPath, newPath, oldTargetRecord, parentRecord)
        }
        core.invalidateCache()

        // Re-read directory to immediately prime the cache with new name and new ref
        try {
            val dirEntries = core.reader.listDirectory(parentRecord)
            val found = dirEntries.find { it.name.equals(displayName, ignoreCase = true) }
            if (found != null) {
                if (found.ref != effRecord) {
                    core.setRecordAlias(record, found.ref)
                    core.setRecordAlias(effRecord, found.ref)
                    core.updatePathAfterRename(oldPath, newPath, found.ref, parentRecord)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-prime dir entries after rename", e)
        }

        notifyChange(documentId)
        val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }
        notifyChange(docIdFor(core.devicePath, serial, parentRecord))
        LogFile.write("provider", "renameDocument succeeded for $oldPath -> $newPath")
        return documentId
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String? {
        Log.i(TAG, "copyDocument: src=$sourceDocumentId, targetParent=$targetParentDocumentId")
        val sourceCore = coreFor(sourceDocumentId) ?: throw SecurityException("Source volume is locked")
        val targetCore = coreFor(targetParentDocumentId) ?: throw SecurityException("Target volume is locked")
        val writer = targetCore.writer ?: throw UnsupportedOperationException("Target volume is read-only")

        val sourceRecord = recordFrom(sourceDocumentId)
        val sourceEntry = sourceCore.getEntry(sourceRecord)
            ?: throw IllegalStateException("Cannot read source entry")
        val sourceName = sourceEntry.fileName ?: "file"
        val isDir = sourceEntry.isDirectory

        val targetParentRecord = recordFrom(targetParentDocumentId)
        val targetParentPath = targetCore.resolvePath(targetParentRecord) ?: "/"

        val existingTargetEntries = try { targetCore.reader.listDirectory(targetParentRecord) } catch (_: Exception) { emptyList() }
        val actualTargetName = buildUniqueDisplayName(existingTargetEntries, sourceName, isDir)

        val createdRef = writer.createFile(targetParentPath, actualTargetName, isDir)
        if (createdRef < 0) {
            throw IllegalStateException("Failed to create copy $actualTargetName in $targetParentPath: error=$createdRef")
        }

        val targetPath = if (targetParentPath == "/") "/$actualTargetName" else "$targetParentPath/$actualTargetName"
        val newRecord = if (createdRef > 0) createdRef else (0x80000000L or (System.nanoTime() and 0x7FFFFFFFL))
        targetCore.registerPath(newRecord, targetPath, targetParentRecord)

        if (!isDir) {
            val size = sourceEntry.fileSize
            val buf = ByteArray(64 * 1024)
            var offset = 0L
            while (offset < size) {
                val len = minOf(buf.size.toLong(), size - offset).toInt()
                val n = sourceCore.readFile(sourceRecord, offset, buf, 0, len)
                if (n <= 0) break
                val w = writer.write(targetPath, offset, buf, n)
                if (w < 0) {
                    Log.e(TAG, "Failed writing copy chunk to $targetPath at offset $offset")
                    break
                }
                offset += w
            }
        }

        targetCore.invalidateCache()
        notifyChange(targetParentDocumentId)

        val serial = try { targetCore.reader.volumeSerial() } catch (e: Exception) { 0L }
        val newDocId = docIdFor(targetCore.devicePath, serial, newRecord)
        notifyChange(newDocId)
        Log.i(TAG, "copyDocument succeeded: $sourceDocumentId -> $newDocId")
        return newDocId
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String? {
        Log.i(TAG, "moveDocument: src=$sourceDocumentId, srcParent=$sourceParentDocumentId, targetParent=$targetParentDocumentId")
        val sourceCore = coreFor(sourceDocumentId) ?: throw SecurityException("Source volume is locked")
        val targetCore = coreFor(targetParentDocumentId) ?: throw SecurityException("Target volume is locked")

        if (sourceCore.devicePath != targetCore.devicePath) {
            return super.moveDocument(sourceDocumentId, sourceParentDocumentId, targetParentDocumentId)
        }

        val writer = targetCore.writer ?: throw UnsupportedOperationException("Volume is read-only")
        val sourceRecord = recordFrom(sourceDocumentId)
        val sourcePath = sourceCore.resolvePath(sourceRecord)
            ?: throw IllegalStateException("Cannot resolve source path for move")

        val targetParentRecord = recordFrom(targetParentDocumentId)
        val targetParentPath = targetCore.resolvePath(targetParentRecord)
            ?: throw IllegalStateException("Cannot resolve target parent path for move")

        val displayName = sourcePath.substringAfterLast('/')
        val existingTargetEntries = try { targetCore.reader.listDirectory(targetParentRecord) } catch (_: Exception) { emptyList() }
        val sourceEntry = sourceCore.getEntry(sourceRecord)
        val isDir = sourceEntry?.isDirectory ?: false
        val actualTargetName = buildUniqueDisplayName(existingTargetEntries, displayName, isDir)
        val targetPath = if (targetParentPath == "/") "/$actualTargetName" else "$targetParentPath/$actualTargetName"

        val ok = writer.rename(sourcePath, targetPath)
        if (!ok) {
            throw IllegalStateException("Failed to move $sourcePath -> $targetPath")
        }

        targetCore.registerPath(sourceRecord, targetPath, targetParentRecord)
        sourceCore.invalidateCache()
        notifyChange(sourceDocumentId)
        notifyChange(sourceParentDocumentId)
        notifyChange(targetParentDocumentId)

        Log.i(TAG, "moveDocument succeeded: $sourcePath -> $targetPath")
        return sourceDocumentId
    }

    override fun removeDocument(documentId: String, parentDocumentId: String?) {
        Log.i(TAG, "removeDocument: docId=$documentId, parent=$parentDocumentId")
        deleteDocument(documentId)
    }

    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String
    ): DocumentsContract.Path? {
        Log.i(TAG, "findDocumentPath: parent=$parentDocumentId, child=$childDocumentId")
        val core = coreFor(childDocumentId) ?: return null
        val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }

        val pathList = mutableListOf<String>()
        var cur = recordFrom(childDocumentId)
        val rootRef = core.reader.rootRef
        val stopRecord = if (parentDocumentId != null) recordFrom(parentDocumentId) else rootRef

        var depth = 0
        while (depth < 64) {
            pathList.add(0, docIdFor(core.devicePath, serial, cur))
            if (cur == stopRecord || cur == rootRef) break
            val p = core.parentOf(cur)
            if (p == cur) break
            cur = p
            depth++
        }

        val rootId = rootIdFor(core.devicePath, serial)
        return DocumentsContract.Path(rootId, pathList)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        LogFile.write("provider", "openDocument docId=$documentId mode=$mode")
        try {
            val core = coreFor(documentId) ?: throw SecurityException("Volume is locked")
            val record = recordFrom(documentId)
            val effRecord = core.resolveRecord(record)

            if (mode.contains("w") || mode.contains("rw")) {
                val docDevPath = core.devicePath
                if (PreferenceHelper.isVolumeReadOnly(appContext, core.volumeGuid, docDevPath)) {
                    throw SecurityException("Volume is mounted in read-only mode")
                }
                val writer = core.writer ?: throw SecurityException("Volume is read-only")
                val path: String = (if (record != -1L) core.resolvePath(record) else null)
                    ?: run {
                        val parts = documentId.split(SEP)
                        if (parts.size >= 3 && parts[2].contains('/')) {
                            val pRec = parts[2].substringBefore('/').toLongOrNull() ?: core.reader.rootRef
                            val sub = parts[2].substringAfter('/')
                            val pPath = core.resolvePath(pRec) ?: "/"
                            val full = if (pPath == "/") "/$sub" else "$pPath/$sub"
                            val entries = try { core.reader.listDirectory(pRec) } catch (_: Exception) { emptyList() }
                            val existing = entries.find { it.name.equals(sub, ignoreCase = true) }
                            if (existing != null) {
                                core.registerPath(existing.ref, full, pRec)
                                full
                            } else {
                                val createdRef = writer.createFile(pPath, sub, false)
                                if (createdRef >= 0) {
                                    val newRec = if (createdRef > 0) createdRef else (0x80000000L or (System.nanoTime() and 0x7FFFFFFFL))
                                    core.registerPath(newRec, full, pRec)
                                    full
                                } else null
                            }
                        } else null
                    }
                    ?: throw SecurityException("Cannot resolve path for document write (record=$record, effRecord=$effRecord)")

                // Wait for any previous write on this path
                activeWrites[path]?.let { thread ->
                    try {
                        thread.join(3000)
                    } catch (_: Exception) {}
                }

                val append = mode.contains("a")

                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readFd = pipe[0]
                val writeFd = pipe[1]

                val writerThread = Thread {
                    var totalWritten = 0L
                    var writeFailed = false
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(readFd).use { input ->
                            val buf = ByteArray(64 * 1024)
                            var curOffset = if (append) (core.getEntry(record)?.fileSize ?: 0L) else 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                val w = writer.write(path, curOffset, buf, n)
                                if (w < 0) {
                                    LogFile.write("provider", "Failed writing pipe chunk at $curOffset to $path: $w")
                                    writeFailed = true
                                    break
                                }
                                curOffset += w
                                totalWritten = curOffset
                            }
                            // Truncate only after a successful, non-empty transfer:
                            // a failed or zero-byte pipe write must leave the
                            // original file content intact.
                            if (!writeFailed && !append && totalWritten > 0) {
                                writer.truncate(path, totalWritten)
                            }
                            LogFile.write("provider", "Pipe write complete for $path ($totalWritten bytes, append=$append, failed=$writeFailed)")
                        }
                    } catch (e: Exception) {
                        writeFailed = true
                        LogFile.write("provider", "Pipe write error for $path: ${Log.getStackTraceString(e)}")
                    } finally {
                        activeWrites.remove(path)
                        core.invalidateCache()
                        core.removeCreatedEntry(record)
                        core.removeCreatedEntry(effRecord)
                        val parentRecord = core.parentOf(record)
                        try {
                            val dirEntries = core.reader.listDirectory(parentRecord)
                            val fileName = path.substringAfterLast('/')
                            val found = dirEntries.find {
                                (it.name.trim().equals(fileName.trim(), ignoreCase = true) ||
                                it.name.equals(fileName, ignoreCase = true)) && !it.isDirectory
                            }
                            if (found != null) {
                                // On a failed transfer the on-disk size is the
                                // truth — never cache the partial byte count.
                                val finalSize = if (writeFailed) found.size else totalWritten
                                if (found.ref != record) {
                                    core.setRecordAlias(record, found.ref)
                                    core.registerPath(found.ref, path, parentRecord)
                                }
                                core.registerPath(record, path, parentRecord)
                                core.registerCreatedEntry(
                                    com.bitlockerdroid.ntfs.VolumeEntry(
                                        ref = record,
                                        isDirectory = false,
                                        fileName = fileName,
                                        fileSize = finalSize,
                                        lastModified = if (found.lastModified > 0L) found.lastModified else System.currentTimeMillis()
                                    )
                                )
                                core.registerCreatedEntry(
                                    com.bitlockerdroid.ntfs.VolumeEntry(
                                        ref = found.ref,
                                        isDirectory = false,
                                        fileName = fileName,
                                        fileSize = finalSize,
                                        lastModified = if (found.lastModified > 0L) found.lastModified else System.currentTimeMillis()
                                    )
                                )
                                LogFile.write("provider", "Alias registered: old=$record -> new=${found.ref} for $path (size=$finalSize)")
                            }
                        } catch (e: Exception) {
                            LogFile.write("provider", "Failed updating alias after pipe write: ${e.message}")
                        }
                        notifyChange(documentId)
                        val serial = try { core.reader.volumeSerial() } catch (e: Exception) { 0L }
                        notifyChange(docIdFor(core.devicePath, serial, parentRecord))
                    }
                }.apply { isDaemon = true; name = "saf-writer-$record" }

                activeWrites[path] = writerThread
                writerThread.start()

                return writeFd
            }

            // Mode is READ
            val readPath = if (record != -1L) core.resolvePath(record) else null
            if (readPath != null) {
                activeWrites[readPath]?.let { thread ->
                    try {
                        thread.join(3000)
                    } catch (_: Exception) {}
                }
            }
            var size = core.getEntry(record)?.fileSize ?: 0L
            if (size == 0L) {
                val p = core.resolvePath(record)
                if (p != null && p != "/") {
                    val pr = core.parentOf(record)
                    val fn = p.substringAfterLast('/')
                    val de = try { core.reader.listDirectory(pr) } catch (_: Exception) { emptyList() }
                    val f = de.find { it.name.equals(fn, ignoreCase = true) && !it.isDirectory }
                    if (f != null && f.size > 0L) {
                        size = f.size
                        core.setRecordAlias(record, f.ref)
                    }
                }
            }
            LogFile.write("provider", "openDocument read: record=$record eff=$effRecord size=$size")

            // For files <= 20MB, create a seekable temp file so random-access editors like MT Manager work seamlessly!
            if (size <= 20 * 1024 * 1024L) {
                val readTemp = java.io.File.createTempFile("saf_read_", ".tmp", appContext.cacheDir)
                if (size > 0L) {
                    readTemp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var offset = 0L
                        while (offset < size) {
                            val len = minOf(buf.size.toLong(), size - offset).toInt()
                            val n = core.readFile(record, offset, buf, 0, len)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            offset += n
                        }
                    }
                }
                return ParcelFileDescriptor.open(readTemp, ParcelFileDescriptor.MODE_READ_ONLY, syncHandler) {
                    readTemp.delete()
                }
            }

            // Large files (>20MB): stream through reliable pipe
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var offset = 0L
                        while (offset < size) {
                            val len = minOf(buf.size.toLong(), size - offset).toInt()
                            val n = core.readFile(record, offset, buf, 0, len)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            offset += n
                        }
                    }
                } catch (e: Exception) {
                    LogFile.write("provider", "pipe read failed: ${e.message}")
                    try { writeFd.closeWithError("read failed") } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }.start()

            return readFd
        } catch (t: Throwable) {
            LogFile.write("provider", "openDocument EXCEPTION docId=$documentId mode=$mode: ${Log.getStackTraceString(t)}")
            throw t
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        LogFile.write("provider", "openDocumentThumbnail: docId=$documentId hint=${sizeHint.x}x${sizeHint.y}")
        val core = coreFor(documentId) ?: throw FileNotFoundException("Volume is locked")
        val record = recordFrom(documentId)
        val entry = core.getEntry(record) ?: throw FileNotFoundException("Document not found: $documentId")
        val size = entry.fileSize
        if (size <= 0L) throw FileNotFoundException("Empty file")

        val thumbTemp = File.createTempFile("thumb_", ".jpg", appContext.cacheDir)
        try {
            val stream1 = CoreFileInputStream(core, record, size)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(BufferedInputStream(stream1), null, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw FileNotFoundException("Could not decode image bounds")
            }

            val targetW = sizeHint.x.coerceAtLeast(64)
            val targetH = sizeHint.y.coerceAtLeast(64)
            var inSample = 1
            var halfW = options.outWidth / 2
            var halfH = options.outHeight / 2
            while (halfW / inSample >= targetW && halfH / inSample >= targetH) {
                inSample *= 2
            }

            val stream2 = CoreFileInputStream(core, record, size)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeStream(BufferedInputStream(stream2), null, decodeOptions)
                ?: throw FileNotFoundException("Failed to decode thumbnail bitmap")

            thumbTemp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()

            val pfd = ParcelFileDescriptor.open(thumbTemp, ParcelFileDescriptor.MODE_READ_ONLY, syncHandler) {
                thumbTemp.delete()
            }
            return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Exception) {
            thumbTemp.delete()
            LogFile.write("provider", "openDocumentThumbnail failed: ${e.message}")
            throw FileNotFoundException("Thumbnail decode failed: ${e.message}")
        }
    }

    // ---------------- helpers ----------------

    private fun coreFor(docId: String): DislockerCore? {
        val path = devicePathFrom(docId)
        if (path != null) {
            val direct = UnlockManager.get(path)
            if (direct != null) return direct
        }

        // Serial-based fallback: if device node path changed (e.g. USB re-plug),
        // match by volume serial extracted from docId (b64path:serial:record).
        val parts = docId.split(SEP)
        val serial = if (parts.size >= 2) parts[1].toLongOrNull() ?: 0L else 0L
        if (serial != 0L) {
            val matchingSession = UnlockManager.activeSessions.find {
                (try { it.reader.volumeSerial() } catch (_: Exception) { 0L }) == serial
            }
            if (matchingSession != null) {
                return matchingSession
            }
        }

        // No "single active session" fallback: handing an arbitrary docId to the
        // only unlocked core silently routes reads/writes to the WRONG volume
        // when another (locked) volume is plugged in.

        if (path != null) {
            Log.i(TAG, "coreFor: no core for $path, trying auto re-unlock")
            val core = UnlockManager.ensureUnlocked(appContext, path, 0)
            if (core != null) return core
        }

        // Also try restoreRemembered across all detected devices
        val restored = UnlockManager.restoreRemembered(appContext)
        if (restored > 0) {
            if (path != null) {
                val core = UnlockManager.get(path)
                if (core != null) return core
            }
            if (serial != 0L) {
                val matchingSession = UnlockManager.activeSessions.find {
                    (try { it.reader.volumeSerial() } catch (_: Exception) { 0L }) == serial
                }
                if (matchingSession != null) return matchingSession
            }
        }

        Log.i(TAG, "coreFor: no core found for $docId, unlocked=${UnlockManager.unlockedVolumes.map { it.devicePath }}")
        return null
    }

    private fun addDocumentRow(result: MatrixCursor, documentId: String) {
        val core = coreFor(documentId) ?: run {
            LogFile.write("provider", "addDocumentRow: coreFor returned null for $documentId")
            return
        }
        val record = recordFrom(documentId)
        val effRecord = core.resolveRecord(record)
        val pending = pendingWrites[record] ?: pendingWrites[effRecord]
        val rec = if (pending != null && pending.file.exists() && pending.file.length() > 0) {
            com.bitlockerdroid.ntfs.VolumeEntry(
                ref = record,
                isDirectory = false,
                fileName = pending.path.substringAfterLast('/'),
                fileSize = pending.file.length()
            )
        } else {
            try { core.getEntry(record) } catch (e: Exception) { null }
                ?: try { core.getEntry(effRecord) } catch (e: Exception) { null }
        } ?: run {
            if (record == core.reader.rootRef || effRecord == core.reader.rootRef) {
                com.bitlockerdroid.ntfs.VolumeEntry(record, isDirectory = true, fileName = core.volumeLabel, fileSize = 0L)
            } else {
                LogFile.write("provider", "addDocumentRow: Entry not found for $documentId (record=$record, eff=$effRecord)")
                null
            }
        } ?: return

        val isDir = rec.isDirectory
        val isRoot = (record == core.reader.rootRef)
        val name = if (isRoot) core.volumeLabel else (rec.fileName ?: "BitLocker")
        val mimeType1 = if (isDir) {
            Document.MIME_TYPE_DIR
        } else {
            val ext = name.substringAfterLast('.', "")
            if (ext.isNotEmpty()) {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                    ?: "application/octet-stream"
            } else "application/octet-stream"
        }

        var flags = 0
        val docDevPath = core.devicePath
        val isRo = PreferenceHelper.isVolumeReadOnly(appContext, core.volumeGuid, docDevPath)
        val canWrite = (core.writer?.isMounted == true) && !isRo
        if (isDir) {
            flags = flags or Document.FLAG_DIR_PREFERS_LAST_MODIFIED
            if (canWrite) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
                if (!isRoot) {
                    flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                            Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_REMOVE
                }
            }
        } else {
            if (canWrite) {
                flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                        Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_REMOVE
            }
            if (isThumbnailSupported(mimeType1, name)) {
                flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
            }
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, name)
        row.add("display_name", name)
        row.add(Document.COLUMN_MIME_TYPE, mimeType1)
        row.add(Document.COLUMN_SIZE, rec.fileSize)
        val lastModified = if (rec.lastModified > 0L) rec.lastModified else null
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun addDocumentRow(
        result: MatrixCursor,
        documentId: String,
        entry: VolumeDirEntry,
        summary: String? = null
    ) {
        val core = coreFor(documentId)
        val record = recordFrom(documentId)
        val isRoot = (record == core?.reader?.rootRef)
        val rowDevPath = core?.devicePath
        val rowRo = PreferenceHelper.isVolumeReadOnly(appContext, core?.volumeGuid, rowDevPath)
        val canWrite = (core?.writer?.isMounted == true) && !rowRo

        val mimeType2 = if (entry.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            val ext = entry.name.substringAfterLast('.', "")
            if (ext.isNotEmpty()) {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                    ?: "application/octet-stream"
            } else "application/octet-stream"
        }

        var flags = 0
        if (entry.isDirectory) {
            flags = flags or Document.FLAG_DIR_PREFERS_LAST_MODIFIED
            if (canWrite) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
                if (!isRoot) {
                    flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                            Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_REMOVE
                }
            }
        } else {
            if (canWrite) {
                flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                        Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_REMOVE
            }
            if (isThumbnailSupported(mimeType2, entry.name)) {
                flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
            }
        }

        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, entry.name)
        row.add("display_name", entry.name)
        row.add(Document.COLUMN_MIME_TYPE, mimeType2)
        row.add(Document.COLUMN_SIZE, entry.size)
        val lastModified = if (entry.lastModified > 0L) entry.lastModified else null
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(Document.COLUMN_FLAGS, flags)
        if (summary != null) {
            row.add(Document.COLUMN_SUMMARY, summary)
        }
    }

    private fun notifyChange(documentId: String) {
        try {
            val resolver = appContext.contentResolver
            val docUri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
            resolver.notifyChange(docUri, null)
            val childUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId)
            resolver.notifyChange(childUri, null)
        } catch (e: Exception) {
            Log.w(TAG, "notifyChange failed for $documentId", e)
        }
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        if (projection == null) return arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_QUERY_ARGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS
        )
        return projection.toList().toTypedArray()
    }

    private fun isThumbnailSupported(mimeType: String, fileName: String): Boolean {
        if (mimeType.startsWith("image/")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> {
        if (projection == null) return arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY
        )
        return projection.toList().toTypedArray()
    }
}

private class CoreFileInputStream(
    private val core: DislockerCore,
    private val record: Long,
    private val totalSize: Long
) : InputStream() {
    private var pos = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n > 0) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= totalSize) return -1
        val toRead = minOf(len.toLong(), totalSize - pos).toInt()
        if (toRead <= 0) return -1
        val n = core.readFile(record, pos, b, off, toRead)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0L
        val toSkip = minOf(n, totalSize - pos).coerceAtLeast(0L)
        pos += toSkip
        return toSkip
    }

    override fun available(): Int = (totalSize - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
