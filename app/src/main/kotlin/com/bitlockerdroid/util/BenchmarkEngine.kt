package com.bitlockerdroid.util

import android.content.Context
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DislockerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

data class BenchmarkResult(
    val sequentialReadMbPerSec: Double,
    val random4kLatencyMs: Double,
    val random4kIops: Double,
    val usbLinkSpeedMbps: Int?,
    val usbSpeedDesc: String,
    val assessment: String = ""
)

object BenchmarkEngine {

    fun detectUsbSpeed(devicePath: String): Int? {
        // Priority 1: Trace USB sysfs device tree from the specific block device via Root
        if (RootAccess.hasSu() && DevicePathSecurity.isValid(devicePath)) {
            try {
                val fileName = File(devicePath).name
                val majMin = when {
                    fileName.startsWith("public:") -> fileName.removePrefix("public:").replace(',', ':')
                    fileName.startsWith("disk:") -> fileName.removePrefix("disk:").replace(',', ':')
                    else -> {
                        val lsOut = RootAccess.exec("ls -l '$devicePath' 2>/dev/null")?.second
                        val match = Regex("""(\d+),\s*(\d+)""").find(lsOut ?: "")
                        if (match != null) "${match.groupValues[1]}:${match.groupValues[2]}" else null
                    }
                }
                if (majMin != null) {
                    val cmd = "p=\$(realpath /sys/dev/block/$majMin 2>/dev/null); while [ \"\$p\" != \"/\" -a -n \"\$p\" ]; do if [ -f \"\$p/speed\" ]; then cat \"\$p/speed\"; break; fi; p=\$(dirname \"\$p\"); done"
                    val out = RootAccess.exec(cmd)?.second?.trim()
                    val s = out?.toIntOrNull()
                    if (s != null && s > 0) {
                        return s
                    }
                }
                // Fallback: scan all active non-roothub USB device speeds via Root
                val allSpeeds = RootAccess.exec("for s in /sys/bus/usb/devices/*/speed; do [ -f \"\$s\" ] && cat \"\$s\"; done 2>/dev/null")?.second
                val list = allSpeeds?.lines()?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }
                if (!list.isNullOrEmpty()) {
                    return list.maxOrNull()
                }
            } catch (_: Exception) {}
        }

        // Priority 2: Direct unprivileged sysfs read (if permitted by SELinux)
        try {
            val usbDir = File("/sys/bus/usb/devices")
            if (usbDir.exists() && usbDir.isDirectory) {
                val speeds = mutableListOf<Int>()
                usbDir.listFiles()?.forEach { dev ->
                    val speedFile = File(dev, "speed")
                    val isRootHub = dev.name.startsWith("usb")
                    if (!isRootHub && speedFile.exists()) {
                        val s = speedFile.readText().trim().toIntOrNull()
                        if (s != null && s > 0) {
                            speeds.add(s)
                        }
                    }
                }
                if (speeds.isNotEmpty()) {
                    return speeds.maxOrNull()
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun runBenchmark(
        core: DislockerCore,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val volumeSize = core.info.volumeSize
        val sectorSize = core.info.sectorSize.coerceAtLeast(512)

        // Phase 1: Sequential Read (16 MB in 512KB chunks)
        val stepSeq = context?.getString(R.string.benchmark_step_seq) ?: "Testing sequential read…"
        onProgress(stepSeq, 0.05f)
        val chunkSize = 512 * 1024
        val numChunks = 32 // 16 MB total
        var totalBytesRead = 0L
        val startOffset = 64L * sectorSize // Skip header sectors

        val seqStartNano = System.nanoTime()
        for (i in 0 until numChunks) {
            val offset = startOffset + (i.toLong() * chunkSize)
            if (offset + chunkSize > volumeSize) break
            val buf = NativeBridge.nativeRead(core.handle, offset, chunkSize)
            if (buf != null) {
                totalBytesRead += buf.size
            }
            val curProgress = 0.05f + (0.60f * (i + 1) / numChunks)
            val readMb = (i + 1) * 512 / 1024
            val seqProgressMsg = context?.getString(R.string.benchmark_step_seq_progress, readMb, 16)
                ?: "Testing sequential read ($readMb MB / 16 MB)…"
            onProgress(seqProgressMsg, curProgress)
        }
        val seqElapsedSec = (System.nanoTime() - seqStartNano) / 1_000_000_000.0
        val seqMbPerSec = if (seqElapsedSec > 0 && totalBytesRead > 0) {
            (totalBytesRead.toDouble() / (1024.0 * 1024.0)) / seqElapsedSec
        } else 0.0

        // Phase 2: Random 4K Read (50 iterations)
        val step4k = context?.getString(R.string.benchmark_step_4k) ?: "Testing 4K random latency…"
        onProgress(step4k, 0.68f)
        val numRandomReads = 50
        val randomChunkSize = 4096
        val maxOffset = (volumeSize - randomChunkSize).coerceAtLeast(startOffset)
        var totalRandomTimeMs = 0.0
        var successfulRandomReads = 0

        for (i in 0 until numRandomReads) {
            val randCluster = Random.nextLong(startOffset / 4096, (maxOffset / 4096).coerceAtLeast(startOffset / 4096 + 1))
            val randOffset = (randCluster * 4096).coerceIn(startOffset, maxOffset)
            val t0 = System.nanoTime()
            val buf = NativeBridge.nativeRead(core.handle, randOffset, randomChunkSize)
            val t1 = System.nanoTime()
            if (buf != null) {
                totalRandomTimeMs += (t1 - t0) / 1_000_000.0
                successfulRandomReads++
            }
            val curProgress = 0.68f + (0.28f * (i + 1) / numRandomReads)
            val randProgressMsg = context?.getString(R.string.benchmark_step_4k_progress, i + 1, numRandomReads)
                ?: "Testing 4K random latency (${i + 1}/$numRandomReads)…"
            onProgress(randProgressMsg, curProgress)
        }

        val avgLatencyMs = if (successfulRandomReads > 0) totalRandomTimeMs / successfulRandomReads else 0.0
        val iops = if (avgLatencyMs > 0) 1000.0 / avgLatencyMs else 0.0

        // Phase 3: Hardware Link Speed
        val stepLink = context?.getString(R.string.benchmark_step_link) ?: "Detecting hardware link speed…"
        onProgress(stepLink, 0.98f)
        val usbSpeed = detectUsbSpeed(core.devicePath)
        val usbDesc = when {
            usbSpeed == null -> context?.getString(R.string.benchmark_usb_unknown) ?: "Unknown USB Protocol"
            usbSpeed >= 10000 -> context?.getString(R.string.benchmark_usb_superspeed_plus) ?: "USB 3.1+ (10 Gbps SuperSpeed+)"
            usbSpeed >= 5000 -> context?.getString(R.string.benchmark_usb_superspeed) ?: "USB 3.0 (5 Gbps SuperSpeed)"
            usbSpeed == 480 -> "USB 2.0 (480 Mbps)"
            usbSpeed == 12 -> "USB 1.1 (12 Mbps)"
            else -> "USB ($usbSpeed Mbps)"
        }

        val stepDone = context?.getString(R.string.benchmark_done) ?: "Benchmark completed"
        onProgress(stepDone, 1.0f)

        BenchmarkResult(
            sequentialReadMbPerSec = seqMbPerSec,
            random4kLatencyMs = avgLatencyMs,
            random4kIops = iops,
            usbLinkSpeedMbps = usbSpeed,
            usbSpeedDesc = usbDesc,
            assessment = ""
        )
    }
}
