package com.bitlockerdroid.util

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
    val assessment: String
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
        onProgress: (phase: String, progress: Float) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val volumeSize = core.info.volumeSize
        val sectorSize = core.info.sectorSize.coerceAtLeast(512)

        // Phase 1: Sequential Read (16 MB in 512KB chunks)
        onProgress("连续读取测速中...", 0.05f)
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
            onProgress("连续读取测速中 (${(i + 1) * 512 / 1024} MB / 16 MB)...", curProgress)
        }
        val seqElapsedSec = (System.nanoTime() - seqStartNano) / 1_000_000_000.0
        val seqMbPerSec = if (seqElapsedSec > 0 && totalBytesRead > 0) {
            (totalBytesRead.toDouble() / (1024.0 * 1024.0)) / seqElapsedSec
        } else 0.0

        // Phase 2: Random 4K Read (50 iterations)
        onProgress("4K 随机读取延时测试中...", 0.68f)
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
            onProgress("4K 随机延时测试 (${i + 1}/$numRandomReads)...", curProgress)
        }

        val avgLatencyMs = if (successfulRandomReads > 0) totalRandomTimeMs / successfulRandomReads else 0.0
        val iops = if (avgLatencyMs > 0) 1000.0 / avgLatencyMs else 0.0

        // Phase 3: Hardware Link & Diagnosis
        onProgress("分析硬件链路与瓶颈评估...", 0.98f)
        val usbSpeed = detectUsbSpeed(core.devicePath)
        val usbDesc = when {
            usbSpeed == null -> "未知 USB 协议"
            usbSpeed >= 10000 -> "USB 3.1+ (10 Gbps 超高速)"
            usbSpeed >= 5000 -> "USB 3.0 (5 Gbps 高速)"
            usbSpeed == 480 -> "USB 2.0 (480 Mbps)"
            usbSpeed == 12 -> "USB 1.1 (12 Mbps)"
            else -> "USB ($usbSpeed Mbps)"
        }

        val assessment = when {
            usbSpeed == 480 -> {
                if (seqMbPerSec >= 26.0) {
                    "当前运行于 USB 2.0 链路模式，已基本跑满 OTG 接口物理带宽极限（约 30~35 MB/s）。如需更高传输速度，建议更换支持 USB 3.0 的 OTG 转接线或拓展坞。"
                } else {
                    "当前运行于 USB 2.0 链路模式。传输速率主要受限于当前 OTG 转接链路带宽与 U 盘主控读取表现。"
                }
            }
            usbSpeed != null && usbSpeed >= 5000 -> {
                "当前运行于 USB 3.0+ 高速通道，物理带宽充足无链路瓶颈。当前速率直接反映了该存储介质闪存颗粒与 CPU AES 解密的综合实际表现。"
            }
            else -> {
                "基准读写测试完成。底层解密与数据流传输状态良好。"
            }
        }

        onProgress("测速完成", 1.0f)

        BenchmarkResult(
            sequentialReadMbPerSec = seqMbPerSec,
            random4kLatencyMs = avgLatencyMs,
            random4kIops = iops,
            usbLinkSpeedMbps = usbSpeed,
            usbSpeedDesc = usbDesc,
            assessment = assessment
        )
    }
}
