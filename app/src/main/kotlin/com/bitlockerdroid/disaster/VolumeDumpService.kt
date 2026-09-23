package com.bitlockerdroid.disaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bitlockerdroid.R
import com.bitlockerdroid.ui.BitLockerSettingsActivity
import com.bitlockerdroid.util.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Locale

class VolumeDumpService : Service() {

    companion object {
        const val CHANNEL_ID = "volume_dump_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START_DUMP = "com.bitlockerdroid.action.START_DUMP"
        const val ACTION_CANCEL_DUMP = "com.bitlockerdroid.action.CANCEL_DUMP"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_HANDLE = "extra_handle"
        const val EXTRA_DEVICE_PATH = "extra_device_path"
        const val EXTRA_PARTITION_OFFSET = "extra_partition_offset"
        const val EXTRA_TOTAL_SIZE = "extra_total_size"
        const val EXTRA_TARGET_URI = "extra_target_uri"
        const val EXTRA_VOLUME_LABEL = "extra_volume_label"

        const val MODE_DECRYPTED = 1
        const val MODE_RAW_ENCRYPTED = 2

        private val _progressState = MutableStateFlow(DumpProgress())
        val progressState: StateFlow<DumpProgress> = _progressState.asStateFlow()

        @Volatile
        private var isCancelled = false

        fun cancelDump() {
            isCancelled = true
        }
    }

    data class DumpProgress(
        val isRunning: Boolean = false,
        val mode: Int = MODE_DECRYPTED,
        val volumeLabel: String = "",
        val totalBytes: Long = 0L,
        val writtenBytes: Long = 0L,
        val bytesPerSec: Double = 0.0,
        val etaSeconds: Long = 0L,
        val progressPercent: Float = 0f,
        val error: String? = null,
        val isCompleted: Boolean = false
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var dumpJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DUMP -> {
                val mode = intent.getIntExtra(EXTRA_MODE, MODE_DECRYPTED)
                val handle = intent.getLongExtra(EXTRA_HANDLE, 0L)
                val devicePath = intent.getStringExtra(EXTRA_DEVICE_PATH) ?: ""
                val offset = intent.getLongExtra(EXTRA_PARTITION_OFFSET, 0L)
                val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L)
                val targetUriString = intent.getStringExtra(EXTRA_TARGET_URI)
                val volumeLabel = intent.getStringExtra(EXTRA_VOLUME_LABEL) ?: "BitLocker Volume"

                if (targetUriString != null && totalSize > 0) {
                    val targetUri = Uri.parse(targetUriString)
                    startForeground(NOTIFICATION_ID, buildNotification(volumeLabel, 0f, 0.0, 0))
                    startDumping(mode, handle, devicePath, offset, totalSize, targetUri, volumeLabel)
                } else {
                    stopSelf()
                }
            }
            ACTION_CANCEL_DUMP -> {
                isCancelled = true
                _progressState.value = _progressState.value.copy(
                    isRunning = false,
                    error = getString(R.string.dump_cancelled)
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDumping(
        mode: Int,
        activeHandle: Long,
        devicePath: String,
        offset: Long,
        totalSize: Long,
        targetUri: Uri,
        volumeLabel: String
    ) {
        dumpJob?.cancel()
        isCancelled = false

        acquireWakeLock()

        dumpJob = serviceScope.launch {
            _progressState.value = DumpProgress(
                isRunning = true,
                mode = mode,
                volumeLabel = volumeLabel,
                totalBytes = totalSize,
                writtenBytes = 0L,
                progressPercent = 0f
            )

            var outputStream: OutputStream? = null
            var sessionHandle = activeHandle
            var openedRawDevice = false

            try {
                outputStream = contentResolver.openOutputStream(targetUri, "w")
                if (outputStream == null) {
                    throw IllegalStateException("Failed to open target output stream")
                }

                if (mode == MODE_RAW_ENCRYPTED && sessionHandle == 0L && devicePath.isNotEmpty()) {
                    sessionHandle = NativeBridge.nativeOpenRawDevice(devicePath, offset)
                    if (sessionHandle == 0L) {
                        throw IllegalStateException("Failed to open raw partition: " + NativeBridge.nativeGetLastError())
                    }
                    openedRawDevice = true
                }

                if (sessionHandle == 0L) {
                    throw IllegalStateException("Invalid session handle")
                }

                val chunkSize = 2 * 1024 * 1024 // 2MB chunk
                var written = 0L
                var lastUiUpdate = System.currentTimeMillis()
                val startTime = System.currentTimeMillis()
                var lastBytesCount = 0L
                var lastSpeedCalcTime = startTime

                while (written < totalSize && !isCancelled) {
                    val remaining = totalSize - written
                    val toRead = if (remaining > chunkSize) chunkSize else remaining.toInt()

                    val buffer = if (mode == MODE_DECRYPTED) {
                        NativeBridge.nativeRead(sessionHandle, written, toRead)
                    } else {
                        NativeBridge.nativeReadRaw(sessionHandle, written, toRead)
                    }

                    if (buffer == null || buffer.isEmpty()) {
                        throw IllegalStateException(
                            "I/O read failure at offset $written (${NativeBridge.nativeGetLastError()})"
                        )
                    }

                    outputStream.write(buffer)
                    written += buffer.size

                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 500 || written == totalSize) {
                        val durationSec = (now - lastSpeedCalcTime) / 1000.0
                        val bytesDiff = written - lastBytesCount
                        val speedBps = if (durationSec > 0.0) bytesDiff / durationSec else 0.0

                        val remainingBytes = totalSize - written
                        val etaSec = if (speedBps > 0) (remainingBytes / speedBps).toLong() else 0L
                        val percent = ((written.toDouble() / totalSize) * 100.0).toFloat()

                        _progressState.value = DumpProgress(
                            isRunning = true,
                            mode = mode,
                            volumeLabel = volumeLabel,
                            totalBytes = totalSize,
                            writtenBytes = written,
                            bytesPerSec = speedBps,
                            etaSeconds = etaSec,
                            progressPercent = percent
                        )

                        updateNotification(volumeLabel, percent, speedBps, etaSec)

                        lastUiUpdate = now
                        lastSpeedCalcTime = now
                        lastBytesCount = written
                    }
                }

                outputStream.flush()

                if (isCancelled) {
                    _progressState.value = _progressState.value.copy(
                        isRunning = false,
                        error = getString(R.string.dump_cancelled)
                    )
                } else {
                    _progressState.value = _progressState.value.copy(
                        isRunning = false,
                        isCompleted = true,
                        writtenBytes = totalSize,
                        progressPercent = 100f
                    )
                    showCompletionNotification(volumeLabel, totalSize)
                }
            } catch (e: Exception) {
                _progressState.value = _progressState.value.copy(
                    isRunning = false,
                    error = e.localizedMessage ?: "Dump failed"
                )
                showErrorNotification(volumeLabel, e.localizedMessage ?: "Unknown error")
            } finally {
                try {
                    outputStream?.close()
                } catch (_: Exception) {}

                if (openedRawDevice && sessionHandle != 0L) {
                    NativeBridge.nativeClose(sessionHandle)
                }

                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dump_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.dump_notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        volumeLabel: String,
        percent: Float,
        speedBps: Double,
        etaSec: Long
    ): Notification {
        val speedMb = speedBps / (1024.0 * 1024.0)
        val contentText = String.format(
            Locale.getDefault(),
            "%.1f%% (%.1f MB/s) - ETA %ds",
            percent,
            speedMb,
            etaSec
        )

        val cancelIntent = Intent(this, VolumeDumpService::class.java).apply {
            action = ACTION_CANCEL_DUMP
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, BitLockerSettingsActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dump_in_progress, volumeLabel))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(100, percent.toInt(), false)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                cancelPendingIntent
            )
            .build()
    }

    private fun updateNotification(volumeLabel: String, percent: Float, speedBps: Double, etaSec: Long) {
        val notification = buildNotification(volumeLabel, percent, speedBps, etaSec)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(volumeLabel: String, totalBytes: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sizeStr = FveMetadataContainer.formatBytes(totalBytes)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dump_completed_title))
            .setContentText(getString(R.string.dump_completed_desc, volumeLabel, sizeStr))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(volumeLabel: String, error: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dump_failed_title))
            .setContentText(getString(R.string.dump_failed_desc, volumeLabel, error))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 2, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitLockerDroid:DumpWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // Max 24 hours
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dumpJob?.cancel()
        releaseWakeLock()
    }
}
