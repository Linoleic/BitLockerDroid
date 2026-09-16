package com.bitlockerdroid.util

/**
 * Security validator for block device paths before handing them to root shell (su)
 * or native I/O routines.
 *
 * Prevents command injection and path traversal via malformed or malicious Intent extras.
 */
object DevicePathSecurity {

    /**
     * Matches legitimate Linux/Android block device node paths, e.g.:
     *   /dev/block/vold/public:8,97
     *   /dev/block/vold/disk:8,96
     *   /dev/block/sdg1
     *   /dev/block/mmcblk0p1
     *
     * Strictly rejects shell metacharacters: ';', '|', '&', '$', '`', ''', '"', '\n', etc.
     */
    private val SAFE_BLOCK_DEVICE_PATTERN =
        Regex("^(?:/dev/block/(?:[a-zA-Z0-9_.,:-]+/)*[a-zA-Z0-9_.,:-]+|usb://[a-zA-Z0-9_.,:-]+(?:/[a-zA-Z0-9_.,:-]+)*|fd:[0-9]+(?::[0-9]+)?)$")

    fun isValid(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (!SAFE_BLOCK_DEVICE_PATTERN.matches(path)) return false
        // Strictly reject path traversal segments (".." or ".")
        val segments = path.split('/')
        if (segments.any { it == ".." || it == "." }) return false
        return true
    }

    fun requireValid(path: String): String {
        if (!isValid(path)) {
            throw SecurityException("Security violation: Invalid or dangerous block device path: $path")
        }
        return path
    }
}
