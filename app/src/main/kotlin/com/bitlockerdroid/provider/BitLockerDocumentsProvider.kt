package com.bitlockerdroid.provider

import android.database.Cursor
import android.database.MatrixCursor
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

        const val ROOT_ID = "bitlocker"

        /** Encode the device path into the docId so the provider can resolve it
         *  even if this process was killed and the session map is empty. */
        fun docIdFor(devicePath: String, record: Long): String {
            val b64 = android.util.Base64.encodeToString(
                devicePath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            return "$b64$SEP$record"
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
            return docId.substringAfter(SEP, "").toLongOrNull() ?: -1L
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    private val appContext: android.content.Context
        get() = super.getContext() ?: com.bitlockerdroid.util.ContextProvider.app

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))
        // If this process was killed since unlock, restore saved volumes.
        if (UnlockManager.unlockedVolumes.isEmpty()) {
            val n = UnlockManager.restoreRemembered(appContext)
            Log.i(TAG, "queryRoots: restored $n remembered volumes")
        }
        val volumes = UnlockManager.unlockedVolumes
        for (v in volumes) {
            val rootRef = UnlockManager.get(v.devicePath)?.reader?.rootRef ?: 0L
            val row = result.newRow()
            row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
            row.add(Root.COLUMN_DOCUMENT_ID, docIdFor(v.devicePath, rootRef))
            row.add(Root.COLUMN_QUERY_ARGS, Bundle())
            row.add(Root.COLUMN_TITLE, v.label)
            row.add(Root.COLUMN_SUMMARY, "BitLocker encrypted volume")
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            row.add(Root.COLUMN_ICON, com.bitlockerdroid.R.drawable.ic_notification)
            row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or 0)
        }
        return result
    }

    override fun getDocumentType(documentId: String): String {
        val core = coreFor(documentId) ?: return Document.MIME_TYPE_DIR
        val rec = try { core.reader.readEntry(recordFrom(documentId)) } catch (e: Exception) { null }
        return if (rec?.isDirectory == true) Document.MIME_TYPE_DIR else "application/octet-stream"
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        addDocumentRow(result, documentId)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(resolveDocumentProjection(projection))
        Log.i(TAG, "queryChildDocuments parent=$parentDocumentId unlocked=${UnlockManager.unlockedVolumes.size}")
        val core = coreFor(parentDocumentId)
        if (core == null) {
            Log.i(TAG, "coreFor returned null for $parentDocumentId")
            return result
        }
        try {
            val record = recordFrom(parentDocumentId)
            Log.i(TAG, "listing dir record=$record")
            val entries = core.reader.listDirectory(record)
            Log.i(TAG, "listDirectory returned ${entries.size} entries")
            for (e in entries) {
                addDocumentRow(result, docIdFor(core.devicePath, e.ref), e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "listDirectory failed", e)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode.contains("w") || mode.contains("rw")) {
            throw SecurityException("BitLocker documents are read-only")
        }

        val core = coreFor(documentId) ?: throw SecurityException("Volume is locked")
        val record = recordFrom(documentId)
        val size = core.reader.run {
            readEntry(record)?.fileSize ?: 0L
        }

        // Reliable pipe: the reader thread writes decrypted bytes; the write end
        // signals EOF by close. We return the read side to the caller.
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
                        val n = core.reader.readFile(record, offset, buf, 0, len)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        offset += n
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pipe read failed", e)
                try { writeFd.closeWithError("read failed") } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()

        return readFd
    }

    // ---------------- helpers ----------------

    private fun coreFor(docId: String): DislockerCore? {
        val path = devicePathFrom(docId)
        if (path == null) {
            Log.i(TAG, "coreFor: no path for $docId, unlocked=${UnlockManager.unlockedVolumes.map { it.devicePath }}")
            return null
        }
        var core = UnlockManager.get(path)
        if (core == null) {
            Log.i(TAG, "coreFor: no core for $path, trying auto re-unlock")
            // The process may have been killed since unlock; try to restore the
            // session from the saved password.
            core = UnlockManager.ensureUnlocked(appContext, path, 0)
            Log.i(TAG, "coreFor: auto re-unlock -> ${if (core != null) "ok" else "failed"}")
        }
        return core
    }

    private fun addDocumentRow(result: MatrixCursor, documentId: String) {
        val core = coreFor(documentId) ?: return
        val record = recordFrom(documentId)
        val rec = try { core.reader.readEntry(record) } catch (e: Exception) { null } ?: return

        val isDir = rec.isDirectory
        result.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, rec.fileName ?: "BitLocker")
            .add(
                Document.COLUMN_MIME_TYPE,
                if (isDir) Document.MIME_TYPE_DIR else "application/octet-stream"
            )
            .add(Document.COLUMN_SIZE, rec.fileSize)
            .add(
                Document.COLUMN_FLAGS,
                if (isDir) Document.FLAG_DIR_PREFERS_LAST_MODIFIED else Document.FLAG_SUPPORTS_DELETE
            )
    }

    private fun addDocumentRow(result: MatrixCursor, documentId: String, entry: VolumeDirEntry) {
        result.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, entry.name)
            .add(
                Document.COLUMN_MIME_TYPE,
                if (entry.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            )
            .add(Document.COLUMN_FLAGS, if (entry.isDirectory) 0 else Document.FLAG_SUPPORTS_DELETE)
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        if (projection == null) return arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_QUERY_ARGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS
        )
        return projection.toList().toTypedArray()
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> {
        if (projection == null) return arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS
        )
        return projection.toList().toTypedArray()
    }
}
