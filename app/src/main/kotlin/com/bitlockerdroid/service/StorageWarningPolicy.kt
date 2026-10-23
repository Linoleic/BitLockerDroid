package com.bitlockerdroid.service

/** Match the public-volume repair warning, never an entire notification channel. */
internal object StorageWarningPolicy {
    const val PUBLIC_VOLUME_NOTIFICATION_ID = 0x53505542
    private val publicTag = Regex("public:[0-9]+,[0-9]+")

    fun isCandidate(packageName: String, id: Int, channelId: String?, category: String?, tag: String?): Boolean =
        (packageName == "com.android.systemui" || packageName == "android") &&
            id == PUBLIC_VOLUME_NOTIFICATION_ID && channelId == "DSK" &&
            category == "err" && tag != null && publicTag.matches(tag)

    fun shouldSuppress(rootEnabled: Boolean, rootGranted: Boolean, candidate: Boolean,
                       verifiedMetadata: Boolean, volumeState: String?): Boolean =
        rootEnabled && rootGranted && candidate && verifiedMetadata && volumeState == "unmountable"

    fun parseVolumeStates(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 3)
        if (fields.size >= 2 && publicTag.matches(fields[0])) fields[0] to fields[1] else null
    }.toMap()
}
