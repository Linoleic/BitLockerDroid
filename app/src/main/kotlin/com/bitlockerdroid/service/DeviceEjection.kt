package com.bitlockerdroid.service

/** The caller holds the admission gate and physical-disk lock for the entire operation. */
internal object DeviceEjection {
    fun <T> eject(
        devicePath: String,
        sessionPaths: List<String>,
        knownPaths: List<String>,
        parentDisk: (String) -> String,
        closeVolume: (String) -> Unit,
        finishDevice: (String) -> T
    ): T {
        val disk = parentDisk(devicePath)
        require(disk.isNotBlank() && !disk.startsWith("storage:")) { "A physical device is required" }
        require(knownPaths.any { parentDisk(it) == disk } &&
            (devicePath == disk || devicePath in knownPaths)) { "No tracked BitLocker device at this path" }
        DeviceVolumeClosure.close(disk, sessionPaths, parentDisk, closeVolume)
        // Zero sessions is valid: closed volumes still need a physical cache sync and STOP.
        return finishDevice(disk)
    }
}
