package com.bitlockerdroid.service

/** Selects a single physical device and stops on the first unsafe closure. */
internal object DeviceVolumeClosure {
    fun close(
        devicePath: String,
        sessionPaths: List<String>,
        parentDisk: (String) -> String,
        closeVolume: (String) -> Unit
    ): Int {
        require(devicePath.isNotBlank()) { "A physical device is required" }
        val disk = parentDisk(devicePath)
        require(disk.isNotBlank() && !disk.startsWith("storage:")) {
            "A physical device is required"
        }
        val targets = sessionPaths.distinct().filter { parentDisk(it) == disk }
        targets.forEach(closeVolume)
        return targets.size
    }
}
