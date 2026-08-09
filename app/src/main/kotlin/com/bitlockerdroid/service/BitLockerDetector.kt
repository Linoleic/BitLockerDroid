package com.bitlockerdroid.service

import android.content.Context
import android.util.Log
import com.bitlockerdroid.util.LogFile
import com.bitlockerdroid.util.RootAccess

/**
 * Detects BitLocker volumes on mounted / connected block devices.
 *
 * Reads block devices via `su` (KernelSU/Magisk) because SELinux blocks both
 * unprivileged apps and system_server from opening block_device nodes.
 */
object BitLockerDetector {

    private const val TAG = "BitLockerDetector"

    /**
     * App-side scan via su: enumerates /dev/block/vold and reads signatures as
     * root, then triggers the unlock flow for BitLocker matches.
     * Returns the number of BitLocker volumes found.
     */
    fun scanAndDetect(context: Context): Int {
        if (!RootAccess.hasSu()) {
            LogFile.write("app", "scanAndDetect: su not available (not granted root?)")
            return 0
        }

        val nodes = enumerateVoldNodes()
        LogFile.write("app", "scanAndDetect: ${nodes.size} vold nodes")

        // Drop detected-but-unlocked volumes that are no longer present on the
        // bus (device unplugged). Unlocked sessions are left untouched; the
        // DocumentsProvider will re-lock them lazily.
        UnlockManager.forgetDetectedMissing(nodes)

        var found = 0

        for (node in nodes) {
            val signature = readSignature(node)
            LogFile.write("app", "  node $node sig=$signature")
            if (signature == "-FVE-FS-" || signature == "MSWIN4.1") {
                LogFile.write("app", "  >>> BitLocker DETECTED: $node")
                found++
                UnlockManager.onDeviceDetected(context, node, 0)
            }
        }

        LogFile.write("app", "scanAndDetect done, BitLocker found=$found")
        return found
    }

    /** Lists partition nodes under /dev/block/vold as root. */
    fun enumerateVoldNodes(): List<String> {
        val out = LinkedHashSet<String>()

        // Prefer public:* partition nodes (BitLocker headers live there).
        val public = RootAccess.exec("ls -d /dev/block/vold/public:* 2>/dev/null")
        public?.second?.lines()?.forEach { line ->
            val l = line.trim()
            if (l.isNotEmpty()) out.add(l)
        }

        // Also disk:* nodes as fallback (whole-disk encryption, rare).
        val disk = RootAccess.exec("ls -d /dev/block/vold/disk:* 2>/dev/null")
        disk?.second?.lines()?.forEach { line ->
            val l = line.trim()
            if (l.isNotEmpty()) out.add(l)
        }

        return out.toList()
    }

    /** Reads the 8-byte OEM signature at offset 3 of the block device, as root. */
    fun readSignature(path: String): String? {
        // Use dd to read the first 512 bytes and hexdump to extract bytes 3..10.
        val cmd = "dd if=$path bs=512 count=1 2>/dev/null | od -An -tx1 -N 11"
        val out = RootAccess.execTimeout(cmd) ?: return null

        // od output like " 2d 46 56 45 2d 46 53 2d 00 00 00"
        val hex = out.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (hex.size < 11) {
            LogFile.write("app", "readSignature $path: insufficient od output: $out")
            return null
        }

        val sigBytes = hex.subList(3, 11) // bytes 3..10 = 8 bytes
        val sig = StringBuilder()
        for (b in sigBytes) {
            val v = b.toInt(16)
            if (v in 32..126) sig.append(v.toChar())
            else sig.append('?')
        }
        return sig.toString()
    }

    /** Raw signature check on a byte array (first 512 bytes of a device). */
    fun hasBitLockerSignature(sector0: ByteArray): Boolean {
        if (sector0.size < 11) return false
        val sig = String(sector0, 3, 8, Charsets.US_ASCII)
        return sig == "-FVE-FS-" || sig == "MSWIN4.1"
    }
}
