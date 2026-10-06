package com.bitlockerdroid.ui.volumes

import androidx.compose.runtime.Immutable
import com.bitlockerdroid.service.DetectedVolume
import com.bitlockerdroid.service.UnencryptedVolume
import com.bitlockerdroid.service.UnlockedVolume
import com.bitlockerdroid.util.DeviceIdentity

/**
 * Represents a physical storage drive housing one or more partitions.
 * Unifies device-level attributes (model, physical capacity, SMART telemetry)
 * and groups child partitions (unlocked, detected, unencrypted) under one container.
 */
@Immutable
data class DeviceVolumeGroup(
    val deviceKey: String,
    val deviceName: String,
    val physicalDiskPath: String,
    val totalCapacityBytes: Long = 0L,
    val vendor: String = "",
    val model: String = "",
    val usbDeviceId: Int? = null,
    val unlockedVolumes: List<UnlockedVolume> = emptyList(),
    val detectedVolumes: List<DetectedVolume> = emptyList(),
    val unencryptedVolumes: List<UnencryptedVolume> = emptyList()
) {
    val totalVolumeCount: Int
        get() = unlockedVolumes.size + detectedVolumes.size + unencryptedVolumes.size

    val hasUnlocked: Boolean
        get() = unlockedVolumes.isNotEmpty()

    val hasDetected: Boolean
        get() = detectedVolumes.isNotEmpty()

    val hasUnencrypted: Boolean
        get() = unencryptedVolumes.isNotEmpty()
}

object DeviceGroupBuilder {

    fun buildGroups(
        unlockedVolumes: List<UnlockedVolume>,
        detectedVolumes: List<DetectedVolume>,
        unencryptedVolumes: List<UnencryptedVolume>
    ): List<DeviceVolumeGroup> {
        val groupMap = LinkedHashMap<String, MutableGroupHolder>()

        // 1. Group Unlocked Volumes
        for (vol in unlockedVolumes) {
            val diskNode = DeviceIdentity.resolveParentDiskNode(vol.devicePath)
            val holder = groupMap.getOrPut(diskNode) { MutableGroupHolder(diskNode) }
            holder.unlocked.add(vol)
            if (holder.fallbackDeviceName.isBlank() && vol.deviceName.isNotBlank()) {
                holder.fallbackDeviceName = vol.deviceName
            }
        }

        // 2. Group Detected Volumes
        for (det in detectedVolumes) {
            val diskNode = DeviceIdentity.resolveParentDiskNode(det.devicePath)
            val holder = groupMap.getOrPut(diskNode) { MutableGroupHolder(diskNode) }
            holder.detected.add(det)
            if (holder.fallbackDeviceName.isBlank() && det.deviceName.isNotBlank()) {
                holder.fallbackDeviceName = det.deviceName
            }
        }

        // 3. Group Unencrypted Volumes
        for (unenc in unencryptedVolumes) {
            // Check if there is an existing diskNode that matches or can be resolved
            val matchedDiskNode = findDiskNodeForUnencrypted(unenc, groupMap.keys)
            val diskNode = matchedDiskNode ?: "storage:${unenc.uuid ?: unenc.id}"
            val holder = groupMap.getOrPut(diskNode) { MutableGroupHolder(diskNode) }
            holder.unencrypted.add(unenc)
            if (holder.fallbackDeviceName.isBlank() && unenc.label.isNotBlank()) {
                holder.fallbackDeviceName = unenc.label
            }
        }

        // 4. Assemble Immutable DeviceVolumeGroup list
        val result = mutableListOf<DeviceVolumeGroup>()
        for ((diskNode, holder) in groupMap) {
            val devInfo = DeviceIdentity.queryDeviceInfo(diskNode)
            val deviceName = when {
                devInfo.friendlyName.isNotBlank() && devInfo.friendlyName != "USB Storage Device" -> devInfo.friendlyName
                holder.fallbackDeviceName.isNotBlank() -> holder.fallbackDeviceName
                devInfo.friendlyName.isNotBlank() -> devInfo.friendlyName
                else -> diskNode
            }

            val partitionSumBytes = holder.unlocked.sumOf { it.size } +
                    holder.detected.sumOf { it.capacity } +
                    holder.unencrypted.sumOf { it.totalBytes }

            val totalCapacity = if (devInfo.sizeBytes > 0L) devInfo.sizeBytes else partitionSumBytes
            val uniqueKey = if (diskNode.startsWith("storage:")) {
                diskNode
            } else {
                val parts = listOf(diskNode, devInfo.vendor, devInfo.model, totalCapacity.toString())
                    .filter { it.isNotBlank() && it != "0" }
                parts.joinToString(":")
            }

            result.add(
                DeviceVolumeGroup(
                    deviceKey = uniqueKey,
                    deviceName = deviceName,
                    physicalDiskPath = diskNode,
                    totalCapacityBytes = totalCapacity,
                    vendor = devInfo.vendor,
                    model = devInfo.model,
                    unlockedVolumes = holder.unlocked.toList(),
                    detectedVolumes = holder.detected.toList(),
                    unencryptedVolumes = holder.unencrypted.toList()
                )
            )
        }

        return result
    }

    private fun findDiskNodeForUnencrypted(
        unenc: UnencryptedVolume,
        existingNodes: Set<String>
    ): String? {
        val uuid = unenc.uuid ?: unenc.id
        // If only 1 physical disk exists, unencrypted removable volume on bus belongs to it
        if (existingNodes.size == 1) {
            val singleNode = existingNodes.first()
            if (singleNode.startsWith("/dev/block/sd") || singleNode.startsWith("/dev/block/nvme")) {
                return singleNode
            }
        }
        return null
    }

    private class MutableGroupHolder(val diskNode: String) {
        var fallbackDeviceName: String = ""
        val unlocked = mutableListOf<UnlockedVolume>()
        val detected = mutableListOf<DetectedVolume>()
        val unencrypted = mutableListOf<UnencryptedVolume>()
    }
}
