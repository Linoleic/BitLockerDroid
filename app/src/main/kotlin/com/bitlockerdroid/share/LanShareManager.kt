package com.bitlockerdroid.share

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DislockerCore
import com.bitlockerdroid.service.VirtualStorageMountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LanShareManager {

    private const val TAG = "LanShareManager"

    private val activeServers = ConcurrentHashMap<String, LanWebServer>()
    private val _shareStates = MutableStateFlow<Map<String, LanShareState>>(emptyMap())
    val shareStates: StateFlow<Map<String, LanShareState>> = _shareStates.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var tickerJob: Job? = null

    init {
        startMetricsTicker()
    }

    private fun startMetricsTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (activeServers.isNotEmpty()) {
                    updateAllStates()
                }
            }
        }
    }

    fun isSharing(volumeGuid: String): Boolean {
        return activeServers.containsKey(volumeGuid)
    }

    fun getState(volumeGuid: String): LanShareState? {
        return _shareStates.value[volumeGuid]
    }

    /**
     * Builds a non-running state carrying [message] so callers (Compose UI)
     * get a clean failure signal instead of an escaping exception. Internal so
     * unit tests can assert the failure contract.
     */
    internal fun buildFailureState(config: LanShareConfig, message: String?): LanShareState {
        return LanShareState(
            isRunning = false,
            volumeGuid = config.volumeGuid,
            volumeLabel = config.volumeLabel,
            devicePath = config.devicePath,
            port = config.port,
            isReadOnly = config.isReadOnly,
            authEnabled = config.authEnabled,
            username = config.username,
            errorMessage = message
        )
    }

    private fun publishState(guid: String, state: LanShareState) {
        val updated = _shareStates.value.toMutableMap()
        updated[guid] = state
        _shareStates.value = updated
    }

    @Synchronized
    fun startSharing(context: Context, config: LanShareConfig, core: DislockerCore): LanShareState {
        val guid = config.volumeGuid

        // Security guard: an authenticated share must never run with an empty password
        if (config.authEnabled && config.password.isEmpty()) {
            Log.w(TAG, "Refusing to start LAN sharing for $guid: auth enabled but password is empty")
            val failed = buildFailureState(
                config,
                context.getString(R.string.lan_share_password_required)
            )
            publishState(guid, failed)
            return failed
        }

        activeServers[guid]?.let { existing ->
            if (existing.isRunning) {
                return getState(guid) ?: buildState(existing, config)
            } else {
                existing.stop()
                activeServers.remove(guid)
            }
        }

        // Determine filesystem adapter: check if POSIX mount exists and is readable
        val adapter = findPosixMountAdapter(config) ?: VolumeCoreShareAdapter(core, config.isReadOnly)

        val server = LanWebServer(config, adapter)
        val actualPort = try {
            server.start()
        } catch (t: Throwable) {
            // Port exhaustion / bind failures must surface as a failed state,
            // never as an exception that crashes the Compose caller
            Log.e(TAG, "Failed to start LAN sharing for $guid", t)
            val message = try {
                context.getString(R.string.lan_share_bind_failed, config.port, config.port + 10)
            } catch (_: Throwable) {
                t.message ?: "Failed to bind a port"
            }
            val failed = buildFailureState(config, message)
            publishState(guid, failed)
            return failed
        }

        activeServers[guid] = server

        val state = buildState(server, config.copy(port = actualPort))
        publishState(guid, state)

        // Start Foreground Service
        val serviceIntent = Intent(context, LanShareService::class.java).apply {
            action = LanShareService.ACTION_START_SHARING
            putExtra(LanShareService.EXTRA_VOLUME_GUID, guid)
            putExtra(LanShareService.EXTRA_VOLUME_LABEL, config.volumeLabel)
            putExtra(LanShareService.EXTRA_PORT, actualPort)
            putExtra(LanShareService.EXTRA_PRIMARY_URL, state.primaryUrl)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            // Sharing itself is running; a rejected foreground service start must
            // not crash the caller (the notification will simply be missing)
            Log.w(TAG, "Foreground service start rejected for $guid", t)
        }

        Log.i(TAG, "Started sharing for volume $guid (${config.volumeLabel}) at port $actualPort")
        return state
    }

    fun hasActiveServers(): Boolean = activeServers.isNotEmpty()

    @Synchronized
    fun stopSharing(context: Context, volumeGuid: String) {
        val server = activeServers.remove(volumeGuid)
        server?.stop()

        val updated = _shareStates.value.toMutableMap()
        updated.remove(volumeGuid)
        _shareStates.value = updated

        if (activeServers.isEmpty()) {
            if (context !is LanShareService) {
                val serviceIntent = Intent(context, LanShareService::class.java).apply {
                    action = LanShareService.ACTION_STOP_ALL
                }
                context.startService(serviceIntent)
            }
        } else {
            // Update notification with another active volume
            val first = _shareStates.value.values.firstOrNull()
            if (first != null) {
                val serviceIntent = Intent(context, LanShareService::class.java).apply {
                    action = LanShareService.ACTION_UPDATE_NOTIFICATION
                    putExtra(LanShareService.EXTRA_VOLUME_LABEL, first.volumeLabel)
                    putExtra(LanShareService.EXTRA_PRIMARY_URL, first.primaryUrl)
                }
                context.startService(serviceIntent)
            }
        }

        Log.i(TAG, "Stopped sharing for volume $volumeGuid")
    }

    private fun getAppContext(context: Context? = null): Context? {
        if (context != null) return context
        return try {
            com.bitlockerdroid.util.ContextProvider.app
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun stopSharingForDevice(context: Context?, devicePath: String, guid: String?) {
        val toStop = mutableSetOf<String>()

        for ((serverGuid, _) in activeServers) {
            val state = _shareStates.value[serverGuid]
            val matchesGuid = !guid.isNullOrBlank() && (
                serverGuid.equals(guid, ignoreCase = true) ||
                state?.volumeGuid.equals(guid, ignoreCase = true)
            )
            val matchesPath = devicePath.isNotBlank() && (
                serverGuid == devicePath ||
                state?.devicePath == devicePath
            )
            if (matchesGuid || matchesPath) {
                toStop.add(serverGuid)
            }
        }

        if (toStop.isEmpty()) {
            return
        }

        val app = getAppContext(context)
        for (targetGuid in toStop) {
            if (app != null) {
                stopSharing(app, targetGuid)
            } else {
                activeServers.remove(targetGuid)?.stop()
                val updated = _shareStates.value.toMutableMap()
                updated.remove(targetGuid)
                _shareStates.value = updated
            }
        }

        if (activeServers.isEmpty() && app != null) {
            stopAll(app)
        }
    }

    @Synchronized
    fun stopAllServersDirectly() {
        for ((_, server) in activeServers) {
            try {
                server.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping server", e)
            }
        }
        activeServers.clear()
        _shareStates.value = emptyMap()
    }

    @Synchronized
    fun stopAll(context: Context? = null) {
        val wasActive = activeServers.isNotEmpty() || _shareStates.value.isNotEmpty()
        stopAllServersDirectly()

        val app = getAppContext(context)
        if (app != null && wasActive && context !is LanShareService) {
            try {
                val serviceIntent = Intent(app, LanShareService::class.java).apply {
                    action = LanShareService.ACTION_STOP_ALL
                }
                app.startService(serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send stop service intent", e)
            }
        }
        if (wasActive) {
            Log.i(TAG, "Stopped all LAN sharing servers")
        }
    }

    private fun findPosixMountAdapter(config: LanShareConfig): ShareFilesystemAdapter? {
        val activeMount = VirtualStorageMountManager.getMount(config.devicePath)
        if (activeMount != null) {
            val mountFile = File(activeMount.mountPoint)
            if (mountFile.exists() && mountFile.canRead() && (mountFile.listFiles() != null)) {
                return PosixFileShareAdapter(mountFile, config.volumeLabel, config.isReadOnly)
            }
        }
        return null
    }

    private fun buildState(server: LanWebServer, config: LanShareConfig): LanShareState {
        val addresses = NetworkUtils.getAvailableLanAddresses()
        return LanShareState(
            isRunning = server.isRunning,
            volumeGuid = config.volumeGuid,
            volumeLabel = config.volumeLabel,
            devicePath = config.devicePath,
            port = server.actualPort,
            isReadOnly = config.isReadOnly,
            authEnabled = config.authEnabled,
            username = config.username,
            addresses = addresses,
            activeConnections = server.activeConnections.get(),
            bytesServed = server.bytesServed.get(),
            startedAt = System.currentTimeMillis()
        )
    }

    private fun updateAllStates() {
        val current = _shareStates.value.toMutableMap()
        var changed = false
        val addresses = NetworkUtils.getAvailableLanAddresses()
        val app = getAppContext()

        val deadGuids = mutableListOf<String>()

        for ((guid, server) in activeServers) {
            val old = current[guid]
            val isSessionActive = com.bitlockerdroid.service.UnlockManager.isVolumeSessionActive(guid) ||
                (old != null && com.bitlockerdroid.service.UnlockManager.isVolumeSessionActive(old.devicePath))

            if (!server.isRunning || !isSessionActive) {
                Log.w(TAG, "Watchdog detected inactive/stopped volume share for $guid (serverRunning=${server.isRunning}, sessionActive=$isSessionActive). Auto-stopping.")
                deadGuids.add(guid)
                continue
            }

            val updated = old?.copy(
                isRunning = server.isRunning,
                activeConnections = server.activeConnections.get(),
                bytesServed = server.bytesServed.get(),
                addresses = addresses
            )
            if (updated != null && updated != old) {
                current[guid] = updated
                changed = true
            }
        }

        for (deadGuid in deadGuids) {
            if (app != null) {
                stopSharing(app, deadGuid)
            } else {
                activeServers.remove(deadGuid)?.stop()
                current.remove(deadGuid)
                changed = true
            }
        }

        if (changed) {
            _shareStates.value = current
        }

        if (activeServers.isEmpty() && deadGuids.isNotEmpty()) {
            stopAll(app)
        }
    }
}
