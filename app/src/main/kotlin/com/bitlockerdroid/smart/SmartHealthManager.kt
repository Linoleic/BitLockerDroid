package com.bitlockerdroid.smart

import android.content.Context
import android.util.Log
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
        smartSupportCache[key]?.let { return@withContext it }

        val info = querySmartInternal(context, devicePath, usbDeviceId)
        smartSupportCache[key] = info.isSupported
        if (info.isSupported) {
            smartInfoCache[key] = info
        }
        info.isSupported
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
        }
        info
    }

    private suspend fun querySmartInternal(
        context: Context,
        devicePath: String,
        usbDeviceId: Int? = null
    ): SmartHealthInfo = withContext(Dispatchers.IO) {
        val useRoot = PreferenceHelper.useRootAccess && RootAccess.hasSu()

        if (useRoot) {
            try {
                RootAccess.ensureDaemonInstalled(context)
                val daemonPath = "/data/local/tmp/bitlocker_io"
                val res = RootAccess.exec("$daemonPath --smart '$devicePath'", 6000)
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

        // Try Non-Root USB BOT probe
        try {
            val holder = (usbDeviceId?.let { UsbStorageManager.getActiveHolder(it) })
                ?: UsbStorageManager.getAnyActiveHolder()

            if (holder != null) {
                val info = UsbSmartProbe.probeSmart(holder.communication, devicePath)
                if (info.isSupported) {
                    return@withContext info
                } else if (!useRoot) {
                    return@withContext info
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-Root USB SMART probe error", e)
        }

        SmartHealthInfo.unsupported(
            node = devicePath,
            reason = if (useRoot) "该介质（普通 U 盘 / 存储卡）未内置标准 ATA/NVMe S.M.A.R.T. 硬件监控传感器"
                     else "当前运行模式或外设主控未提供标准 S.M.A.R.T. 传感器数据"
        )
    }
}
