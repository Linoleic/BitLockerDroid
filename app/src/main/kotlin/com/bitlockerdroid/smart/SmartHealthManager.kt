package com.bitlockerdroid.smart

import android.content.Context
import android.util.Log
import com.bitlockerdroid.R
import com.bitlockerdroid.usb.UsbStorageManager
import com.bitlockerdroid.util.NativeBridge
import com.bitlockerdroid.util.PreferenceHelper
import com.bitlockerdroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified SMART Health Telemetry Manager.
 * Orchestrates SCSI, SAT, and NVMe-over-SCSI queries across Root and Non-Root modes.
 */
object SmartHealthManager {

    private const val TAG = "SmartHealthManager"

    private val smartSupportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val smartInfoCache = java.util.concurrent.ConcurrentHashMap<String, SmartHealthInfo>()

    fun getCachedSupport(key: String): Boolean? {
        if (key.isBlank() || key.startsWith("storage:")) return false
        return smartSupportCache[key]
    }

    fun clearCache(key: String? = null) {
        if (key != null) {
            smartSupportCache.remove(key)
            smartInfoCache.remove(key)
        } else {
            smartSupportCache.clear()
            smartInfoCache.clear()
        }
    }

    /** USB results only indicate a known query route; hardware support is confirmed on explicit query. */
    suspend fun isDeviceSmartSupported(
        context: Context,
        devicePath: String,
        usbDeviceId: Int? = null,
        cacheKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val key = cacheKey ?: devicePath
        if (key.isBlank() || key.startsWith("storage:") || devicePath.isBlank() || devicePath.startsWith("storage:")) {
            return@withContext false
        }

        val isUsbUri = devicePath.startsWith("usb://")
        if (isUsbUri || usbDeviceId != null) {
            val holder = usbHolder(devicePath, usbDeviceId) ?: return@withContext false
            return@withContext UsbSmartProfile.profileFor(holder.usbDevice.vendorId, holder.usbDevice.productId) != null
        }
        if (!isUsbUri) smartSupportCache[key]?.let { return@withContext it }

        runAutomaticSupportCheck(
            isUsbUri = isUsbUri,
            query = {
                val info = querySmartInternal(context, devicePath)
                smartSupportCache[key] = info.isSupported
                if (info.isSupported) {
                    smartInfoCache[key] = info
                }
                info.isSupported
            },
            unsupported = {
                smartSupportCache[key] = false
                smartInfoCache.remove(key)
                false
            }
        )
    }

    suspend fun querySmart(
        context: Context,
        devicePath: String,
        usbDeviceId: Int? = null,
        cacheKey: String? = null
    ): SmartHealthInfo = withContext(Dispatchers.IO) {
        val key = cacheKey ?: devicePath
        val info = querySmartInternal(context, devicePath, usbDeviceId)
        smartSupportCache[key] = info.isSupported
        if (info.isSupported) {
            smartInfoCache[key] = info
        } else {
            smartInfoCache.remove(key)
        }
        info
    }

    private suspend fun querySmartInternal(
        context: Context,
        devicePath: String,
        usbDeviceId: Int? = null
    ): SmartHealthInfo = withContext(Dispatchers.IO) {
        if (devicePath.startsWith("usb://") || usbDeviceId != null) {
            val holder = usbHolder(devicePath, usbDeviceId)
                ?: return@withContext SmartHealthInfo.unconfirmed(node = devicePath,
                    reason = context.getString(R.string.smart_usb_unavailable))
            if (UsbSmartProfile.profileFor(holder.usbDevice.vendorId, holder.usbDevice.productId) == null) {
                return@withContext SmartHealthInfo.unconfirmed(node = devicePath,
                    reason = context.getString(R.string.smart_usb_unverified))
            }
            if (!holder.transportHealthy) {
                return@withContext SmartHealthInfo.unconfirmed(node = devicePath,
                    reason = context.getString(R.string.smart_usb_unavailable))
            }
            return@withContext UsbStorageManager.withIdleDeviceProbe(holder.usbDevice.deviceId) { current ->
                try {
                    UsbSmartProbe.probeSmart(current.communication, devicePath,
                        current.usbDevice.vendorId, current.usbDevice.productId)
                } catch (e: RecoveredProbeException) {
                    Log.w(TAG, "SMART query failed but BOT recovery completed", e)
                    SmartHealthInfo.unconfirmed(node = devicePath,
                        reason = context.getString(R.string.smart_query_retry))
                } catch (e: java.io.IOException) {
                    // There are no open volume sessions here. Refuse new sessions on an
                    // untrusted transport until physical reconnection; never terminate a live volume.
                    current.transportHealthy = false
                    Log.w(TAG, "SMART transport recovery failed for ${current.usbDevice.deviceName}", e)
                    SmartHealthInfo.unconfirmed(node = devicePath,
                        reason = context.getString(R.string.smart_usb_unavailable))
                }
            } ?: SmartHealthInfo.unconfirmed(node = devicePath,
                reason = context.getString(R.string.smart_usb_close_volumes))
        }

        val useRoot = PreferenceHelper.useRootAccess && RootAccess.hasSu()

        if (useRoot) {
            try {
                RootAccess.ensureDaemonInstalled(context)
                val daemonPath = "/data/local/tmp/bitlocker_io"
                val res = RootAccess.exec("$daemonPath --smart '$devicePath'", 12000)
                if (res != null && res.first == 0 && res.second.isNotBlank()) {
                    val info = SmartDataParser.parseJson(res.second)
                    // The root daemon executes SCSI, SAT, and NVMe queries.
                    // If it returns a valid response (whether supported or unsupported),
                    // that is the authoritative hardware result for this block device.
                    return@withContext info
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root SMART query error for $devicePath", e)
            }
        }

        // Try direct JNI if device node is accessible directly
        try {
            val jniJson = NativeBridge.nativeReadDeviceSmart(devicePath)
            if (!jniJson.isNullOrBlank()) {
                val info = SmartDataParser.parseJson(jniJson)
                if (info.isSupported) {
                    return@withContext info
                }
            }
        } catch (_: Throwable) {}

        SmartHealthInfo.unsupported(
            node = devicePath,
            reason = if (useRoot) "该介质（普通 U 盘 / 存储卡）未内置标准 ATA/NVMe S.M.A.R.T. 硬件监控传感器"
                     else "当前运行模式或外设主控未提供标准 S.M.A.R.T. 传感器数据"
        )
    }

    private fun usbHolder(devicePath: String, requestedId: Int?): UsbStorageManager.UsbDeviceConnectionHolder? {
        val pathId = if (devicePath.startsWith("usb://")) {
            devicePath.removePrefix("usb://").substringBefore('/').toIntOrNull()
        } else null
        if (pathId != null && requestedId != null && pathId != requestedId) return null
        return UsbStorageManager.getActiveHolder(pathId ?: requestedId ?: return null)
    }

    /**
     * Automatic UI support checks must never run non-root raw SMART commands on USB BOT.
     * Root-based checks use the native reader; explicit USB queries use the guarded
     * bridge-specific path above instead of this automatic-check helper.
     */
    internal suspend fun <T> runAutomaticSupportCheck(
        isUsbUri: Boolean,
        query: suspend () -> T,
        unsupported: () -> T
    ): T = if (isUsbUri) unsupported() else query()
}
