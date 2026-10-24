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

    fun getCachedSupport(key: String): Boolean? {
        if (key.isBlank() || key.startsWith("storage:")) return false
        return smartSupportCache[key]
    }

    fun clearCache(key: String? = null) {
        if (key != null) {
            smartSupportCache.remove(key)
        } else {
            smartSupportCache.clear()
        }
    }

    /** Query-entry eligibility: USB requires a known route; inconclusive local results remain retryable. */
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
                cacheSupport(key, info)
                // An inconclusive query must remain reachable for a manual retry.
                info.status != SmartOverallStatus.UNSUPPORTED
            },
            unsupported = {
                smartSupportCache[key] = false
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
        cacheSupport(key, info)
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

        queryLocalSmart(
            devicePath = devicePath,
            rootEnabled = PreferenceHelper.useRootAccess,
            failureReason = context.getString(R.string.smart_query_failed),
            rootQuery = {
                if (!RootAccess.hasSu()) null else {
                    RootAccess.ensureDaemonInstalled(context)
                    val escapedPath = devicePath.replace("'", "'\\''")
                    val result = RootAccess.exec("/data/local/tmp/bitlocker_io --smart '$escapedPath'", 12000)
                    result?.takeIf { it.first == 0 }?.second
                }
            },
            directQuery = { NativeBridge.nativeReadDeviceSmart(devicePath) }
        )
    }

    private fun cacheSupport(key: String, info: SmartHealthInfo) {
        when (val evidence = supportEvidence(info)) {
            null -> smartSupportCache.remove(key)
            else -> smartSupportCache[key] = evidence
        }
    }

    internal fun supportEvidence(info: SmartHealthInfo): Boolean? = when {
        info.isSupported -> true
        info.status == SmartOverallStatus.UNSUPPORTED -> false
        else -> null
    }

    /** A failed Root attempt is inconclusive; do not issue a second probe through JNI. */
    internal fun queryLocalSmart(
        devicePath: String,
        rootEnabled: Boolean,
        failureReason: String,
        rootQuery: () -> String?,
        directQuery: () -> String?
    ): SmartHealthInfo {
        val info = try {
            SmartDataParser.parseJson(if (rootEnabled) rootQuery() else directQuery())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SmartHealthInfo.unconfirmed(reason = failureReason)
        } catch (_: LinkageError) {
            SmartHealthInfo.unconfirmed(reason = failureReason)
        }
        return info.copy(
            deviceNode = info.deviceNode ?: devicePath,
            reason = if (!info.isSupported && info.status == SmartOverallStatus.UNCONFIRMED)
                info.reason.takeIf { info.rawJson != null } ?: failureReason else info.reason
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
