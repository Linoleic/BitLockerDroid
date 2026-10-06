package com.bitlockerdroid.util

import android.content.Context
import com.bitlockerdroid.R
import com.bitlockerdroid.service.DislockerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

enum class BenchmarkTestType(val id: String, val isWrite: Boolean) {
    SEQ_READ("seq_read", false),
    SEQ_WRITE("seq_write", true),
    RAND_4K_READ("rand_4k_read", false),
    RAND_4K_WRITE("rand_4k_write", true);

    companion object {
        fun fromId(id: String): BenchmarkTestType? {
            val clean = id.trim().lowercase()
            return when (clean) {
                "seq_read", "read" -> SEQ_READ
                "seq_write", "write" -> SEQ_WRITE
                "rand_read", "rand_4k_read", "4k_read" -> RAND_4K_READ
                "rand_write", "rand_4k_write", "4k_write" -> RAND_4K_WRITE
                else -> values().find { it.id.equals(clean, ignoreCase = true) }
            }
        }

        val ALL = setOf(SEQ_READ, SEQ_WRITE, RAND_4K_READ, RAND_4K_WRITE)
        val READ_ONLY = setOf(SEQ_READ, RAND_4K_READ)
        val WRITE_ONLY = setOf(SEQ_WRITE, RAND_4K_WRITE)
    }
}

data class BenchmarkResult(
    val sequentialReadMbPerSec: Double? = null,
    val peakReadMbPerSec: Double? = null,
    val sequentialWriteMbPerSec: Double? = null,
    val peakWriteMbPerSec: Double? = null,
    val random4kLatencyMs: Double? = null, // Read latency
    val random4kIops: Double? = null,      // Read IOPS
    val random4kWriteLatencyMs: Double? = null,
    val random4kWriteIops: Double? = null,
    val usbLinkSpeedMbps: Int? = null,
    val usbSpeedDesc: String = "",
    val assessment: String = ""
) {
    fun mergeWith(newer: BenchmarkResult): BenchmarkResult {
        return BenchmarkResult(
            sequentialReadMbPerSec = newer.sequentialReadMbPerSec ?: this.sequentialReadMbPerSec,
            peakReadMbPerSec = newer.peakReadMbPerSec ?: this.peakReadMbPerSec,
            sequentialWriteMbPerSec = newer.sequentialWriteMbPerSec ?: this.sequentialWriteMbPerSec,
            peakWriteMbPerSec = newer.peakWriteMbPerSec ?: this.peakWriteMbPerSec,
            random4kLatencyMs = newer.random4kLatencyMs ?: this.random4kLatencyMs,
            random4kIops = newer.random4kIops ?: this.random4kIops,
            random4kWriteLatencyMs = newer.random4kWriteLatencyMs ?: this.random4kWriteLatencyMs,
            random4kWriteIops = newer.random4kWriteIops ?: this.random4kWriteIops,
            usbLinkSpeedMbps = newer.usbLinkSpeedMbps ?: this.usbLinkSpeedMbps,
            usbSpeedDesc = if (newer.usbSpeedDesc.isNotEmpty()) newer.usbSpeedDesc else this.usbSpeedDesc,
            assessment = if (newer.assessment.isNotEmpty()) newer.assessment else this.assessment
        )
    }
}

object BenchmarkEngine {

    fun detectUsbSpeed(devicePath: String): Int? {
        // Priority 1: Check non-root USB Storage Manager bulk endpoint packet size
        if (devicePath.startsWith("usb://")) {
            val speed = com.bitlockerdroid.usb.UsbStorageManager.getUsbSpeedMbps(devicePath)
            if (speed != null && speed > 0) {
                return speed
            }
        }

        // Priority 2: Trace USB sysfs device tree from the specific block device via Root
        if (RootAccess.hasSu()) {
            try {
                if (DevicePathSecurity.isValid(devicePath)) {
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
                }
                // Fallback: scan all active non-roothub USB device speeds via Root
                val allSpeeds = RootAccess.exec("for s in /sys/bus/usb/devices/*/speed; do [ -f \"\$s\" ] && cat \"\$s\"; done 2>/dev/null")?.second
                val list = allSpeeds?.lines()?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }
                if (!list.isNullOrEmpty()) {
                    return list.maxOrNull()
                }
            } catch (_: Exception) {}
        }

        // Priority 3: Direct unprivileged sysfs read (if permitted by SELinux)
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

    suspend fun testSequentialRead(
        core: DislockerCore,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val volumeSize = core.info.volumeSize
        val sectorSize = core.info.sectorSize.coerceAtLeast(512)
        val stepSeq = context?.getString(R.string.benchmark_step_seq) ?: "Testing sequential read…"
        onProgress(stepSeq, 0.05f)
        val chunkSize = 512 * 1024
        val numChunks = 32 // 16 MB total
        var totalBytesRead = 0L
        val startOffset = 64L * sectorSize
        var peakChunkMbPerSec = 0.0

        val seqStartNano = System.nanoTime()
        for (i in 0 until numChunks) {
            val offset = startOffset + (i.toLong() * chunkSize)
            if (offset + chunkSize > volumeSize) break
            val chunkStart = System.nanoTime()
            val buf = NativeBridge.nativeRead(core.handle, offset, chunkSize)
            val chunkElapsedSec = (System.nanoTime() - chunkStart) / 1_000_000_000.0
            if (buf != null) {
                totalBytesRead += buf.size
                if (chunkElapsedSec > 0) {
                    val rate = (buf.size.toDouble() / (1024.0 * 1024.0)) / chunkElapsedSec
                    if (rate > peakChunkMbPerSec) peakChunkMbPerSec = rate
                }
            }
            if ((i + 1) % 8 == 0 || i == numChunks - 1) {
                val curProgress = 0.05f + (0.90f * (i + 1) / numChunks)
                val readMb = (i + 1) * 512 / 1024
                val seqProgressMsg = context?.getString(R.string.benchmark_step_seq_progress, readMb, 16)
                    ?: "Testing sequential read ($readMb MB / 16 MB)…"
                onProgress(seqProgressMsg, curProgress)
            }
        }
        val seqElapsedSec = (System.nanoTime() - seqStartNano) / 1_000_000_000.0
        val avgSpeed = if (seqElapsedSec > 0 && totalBytesRead > 0) {
            (totalBytesRead.toDouble() / (1024.0 * 1024.0)) / seqElapsedSec
        } else 0.0
        val peakSpeed = maxOf(avgSpeed, peakChunkMbPerSec)
        Pair(avgSpeed, peakSpeed)
    }

    suspend fun testSequentialWrite(
        core: DislockerCore,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val writer = core.writer ?: return@withContext null
        if (!writer.isMounted) return@withContext null

        val stepWrite = context?.getString(R.string.benchmark_step_write) ?: "Testing sequential write…"
        onProgress(stepWrite, 0.05f)
        val writeChunkSize = 256 * 1024
        val numWriteChunks = 32 // 8 MB total
        val testFileName = ".benchmark_tmp_seq_${System.currentTimeMillis()}.bin"
        val writePayload = ByteArray(writeChunkSize) { 0x5A }
        var peakChunkMbPerSec = 0.0

        try {
            val createdRef = writer.createFile("/", testFileName, false)
            if (createdRef < 0) return@withContext null

            var totalWritten = 0L
            val writeStartNano = System.nanoTime()
            for (i in 0 until numWriteChunks) {
                val curOff = i.toLong() * writeChunkSize
                val chunkStart = System.nanoTime()
                val w = writer.write("/$testFileName", curOff, writePayload, writeChunkSize)
                val chunkElapsedSec = (System.nanoTime() - chunkStart) / 1_000_000_000.0
                if (w <= 0) break
                totalWritten += w
                if (chunkElapsedSec > 0) {
                    val rate = (w.toDouble() / (1024.0 * 1024.0)) / chunkElapsedSec
                    if (rate > peakChunkMbPerSec) peakChunkMbPerSec = rate
                }
                if ((i + 1) % 8 == 0 || i == numWriteChunks - 1) {
                    val curProgress = 0.05f + (0.90f * (i + 1) / numWriteChunks)
                    val writeMb = (i + 1) * 256 / 1024
                    val writeProgressMsg = context?.getString(R.string.benchmark_step_write_progress, writeMb, 8)
                        ?: "Testing sequential write ($writeMb MB / 8 MB)…"
                    onProgress(writeProgressMsg, curProgress)
                }
            }
            writer.sync()
            val writeElapsedSec = (System.nanoTime() - writeStartNano) / 1_000_000_000.0
            writer.delete("/$testFileName")
            core.invalidateCache()
            val avgSpeed = if (writeElapsedSec > 0 && totalWritten > 0) {
                (totalWritten.toDouble() / (1024.0 * 1024.0)) / writeElapsedSec
            } else 0.0
            val peakSpeed = maxOf(avgSpeed, peakChunkMbPerSec)
            Pair(avgSpeed, peakSpeed)
        } catch (e: Exception) {
            android.util.Log.w("BenchmarkEngine", "Sequential write benchmark failed: ${e.message}")
            try {
                writer.delete("/$testFileName")
                core.invalidateCache()
            } catch (_: Exception) {}
            null
        }
    }

    suspend fun testRandom4kRead(
        core: DislockerCore,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val volumeSize = core.info.volumeSize
        val sectorSize = core.info.sectorSize.coerceAtLeast(512)
        val startOffset = 64L * sectorSize
        val step4k = context?.getString(R.string.benchmark_step_4k) ?: "Testing 4K random read latency…"
        onProgress(step4k, 0.05f)
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
            if ((i + 1) % 25 == 0 || i == numRandomReads - 1) {
                val curProgress = 0.05f + (0.90f * (i + 1) / numRandomReads)
                val randProgressMsg = context?.getString(R.string.benchmark_step_4k_progress, i + 1, numRandomReads)
                    ?: "Testing 4K random read latency (${i + 1}/$numRandomReads)…"
                onProgress(randProgressMsg, curProgress)
            }
        }

        val avgLatencyMs = if (successfulRandomReads > 0) totalRandomTimeMs / successfulRandomReads else 0.0
        val iops = if (avgLatencyMs > 0) 1000.0 / avgLatencyMs else 0.0
        Pair(avgLatencyMs, iops)
    }

    suspend fun testRandom4kWrite(
        core: DislockerCore,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val writer = core.writer ?: return@withContext null
        if (!writer.isMounted) return@withContext null

        val step4kWrite = context?.getString(R.string.benchmark_step_4k_write) ?: "Testing 4K random write…"
        onProgress(step4kWrite, 0.05f)

        val numRandomWrites = 25
        val randomChunkSize = 4096
        val testFileName = ".benchmark_tmp_rnd_${System.currentTimeMillis()}.bin"
        val testFileSize = 256 * 1024L // 256 KB file space
        val payload = ByteArray(randomChunkSize) { 0x3C }

        try {
            val created = writer.createFile("/", testFileName, false)
            if (created < 0) return@withContext null

            // Pre-fill file with zeros to allocate space
            val initBuf = ByteArray(testFileSize.toInt()) { 0 }
            writer.write("/$testFileName", 0L, initBuf, initBuf.size)
            writer.sync()

            var totalRandomTimeMs = 0.0
            var successfulRandomWrites = 0
            val maxSlots = (testFileSize / randomChunkSize).toInt().coerceAtLeast(1)

            for (i in 0 until numRandomWrites) {
                val randSlot = Random.nextInt(0, maxSlots)
                val randOffset = randSlot.toLong() * randomChunkSize

                val t0 = System.nanoTime()
                val w = writer.write("/$testFileName", randOffset, payload, randomChunkSize)
                writer.sync()
                val t1 = System.nanoTime()

                if (w > 0) {
                    totalRandomTimeMs += (t1 - t0) / 1_000_000.0
                    successfulRandomWrites++
                }

                if ((i + 1) % 5 == 0 || i == numRandomWrites - 1) {
                    val curProgress = 0.05f + (0.90f * (i + 1) / numRandomWrites)
                    val randProgressMsg = context?.getString(R.string.benchmark_step_4k_write_progress, i + 1, numRandomWrites)
                        ?: "Testing 4K random write (${i + 1}/$numRandomWrites)…"
                    onProgress(randProgressMsg, curProgress)
                }
            }

            writer.delete("/$testFileName")
            core.invalidateCache()

            val avgLatencyMs = if (successfulRandomWrites > 0) totalRandomTimeMs / successfulRandomWrites else 0.0
            val iops = if (avgLatencyMs > 0) 1000.0 / avgLatencyMs else 0.0
            Pair(avgLatencyMs, iops)
        } catch (e: Exception) {
            android.util.Log.w("BenchmarkEngine", "4K random write benchmark failed: ${e.message}")
            try {
                writer.delete("/$testFileName")
                core.invalidateCache()
            } catch (_: Exception) {}
            null
        }
    }

    suspend fun runBenchmark(
        core: DislockerCore,
        selectedTests: Set<BenchmarkTestType> = BenchmarkTestType.ALL,
        context: Context? = null,
        onProgress: (phase: String, progress: Float) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val testsToRun = if (selectedTests.isEmpty()) BenchmarkTestType.ALL else selectedTests
        val totalStages = testsToRun.size + 1 // +1 for link speed
        var completedStages = 0

        fun stageProgress(stageProg: Float): Float {
            return (completedStages.toFloat() + stageProg.coerceIn(0f, 1f)) / totalStages.toFloat()
        }

        var seqReadMb: Double? = null
        var peakReadMb: Double? = null
        var seqWriteMb: Double? = null
        var peakWriteMb: Double? = null
        var randReadLatency: Double? = null
        var randReadIops: Double? = null
        var randWriteLatency: Double? = null
        var randWriteIops: Double? = null

        // 1. Sequential Read
        if (BenchmarkTestType.SEQ_READ in testsToRun) {
            val (readAvg, readPeak) = testSequentialRead(core, context) { msg, p ->
                onProgress(msg, stageProgress(p))
            }
            seqReadMb = readAvg
            peakReadMb = readPeak
            completedStages++
        }

        // 2. Sequential Write
        if (BenchmarkTestType.SEQ_WRITE in testsToRun) {
            val writeRes = testSequentialWrite(core, context) { msg, p ->
                onProgress(msg, stageProgress(p))
            }
            if (writeRes != null) {
                seqWriteMb = writeRes.first
                peakWriteMb = writeRes.second
            }
            completedStages++
        }

        // 3. 4K Random Read
        if (BenchmarkTestType.RAND_4K_READ in testsToRun) {
            val (lat, iops) = testRandom4kRead(core, context) { msg, p ->
                onProgress(msg, stageProgress(p))
            }
            randReadLatency = lat
            randReadIops = iops
            completedStages++
        }

        // 4. 4K Random Write
        if (BenchmarkTestType.RAND_4K_WRITE in testsToRun) {
            val res = testRandom4kWrite(core, context) { msg, p ->
                onProgress(msg, stageProgress(p))
            }
            if (res != null) {
                randWriteLatency = res.first
                randWriteIops = res.second
            }
            completedStages++
        }

        // Final: Detect Hardware Link Speed
        val stepLink = context?.getString(R.string.benchmark_step_link) ?: "Detecting hardware link speed…"
        onProgress(stepLink, stageProgress(0.5f))
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
            sequentialReadMbPerSec = seqReadMb,
            peakReadMbPerSec = peakReadMb,
            sequentialWriteMbPerSec = seqWriteMb,
            peakWriteMbPerSec = peakWriteMb,
            random4kLatencyMs = randReadLatency,
            random4kIops = randReadIops,
            random4kWriteLatencyMs = randWriteLatency,
            random4kWriteIops = randWriteIops,
            usbLinkSpeedMbps = usbSpeed,
            usbSpeedDesc = usbDesc,
            assessment = ""
        )
    }
}
