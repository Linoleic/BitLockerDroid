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

    suspend fun querySmart(
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
                    if (info.isSupported) {
                        return@withContext info
                    }
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
            reason = if (useRoot) "该介质（普通 U 盘 / 存储卡）未内置标准 ATA/NVMe SMART 硬件监控传感器"
                     else "当前运行模式或外设主控未提供标准 SMART 传感器数据"
        )
    }
}
