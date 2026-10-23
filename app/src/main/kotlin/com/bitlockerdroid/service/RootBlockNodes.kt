package com.bitlockerdroid.service

/** Keep a stable kernel path when vold later publishes an alias for a USB partition. */
internal object RootBlockNodes {
    fun merge(publicNodes: List<String>, usbNodes: List<Pair<String, String?>>): List<String> {
        val result = publicNodes.toMutableSet()
        for ((kernelPath, majorMinor) in usbNodes) {
            if (!majorMinor.isNullOrBlank()) {
                result.remove("/dev/block/vold/public:${majorMinor.replace(':', ',')}")
            }
            result.add(kernelPath)
        }
        return result.toList()
    }
}
