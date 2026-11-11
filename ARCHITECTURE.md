# BitLockerDroid Architecture & Performance Overview

<p align="center">
  <b>English</b> | <a href="ARCHITECTURE_zh.md">简体中文</a>
</p>

This document describes the internal system architecture, I/O transport pipelines, hardware cryptographic acceleration, hybrid filesystem design, security boundaries, and empirical benchmark datasets of **BitLockerDroid (BitUnlocker)**.

---

## Table of Contents

- [1. Layered Architecture Overview](#1-layered-architecture-overview)
- [2. Dual-Mode Underlying I/O Transport](#2-dual-mode-underlying-io-transport)
  - [2.1 Root Mode: 3-Tier Block Device Access](#21-root-mode-3-tier-block-device-access)
  - [2.2 Non-Root Mode: Userspace USB BOT State Machine & SAF Double-Buffering](#22-non-root-mode-userspace-usb-bot-state-machine--saf-double-buffering)
  - [2.3 Kernel-to-Userspace USB Handoff Policy](#23-kernel-to-userspace-usb-handoff-policy)
- [3. Cryptographic Engine & ARMv8 Hardware Acceleration](#3-cryptographic-engine--armv8-hardware-acceleration)
  - [3.1 Key Derivation Chain (PBKDF2 / VMK / FVEK)](#31-key-derivation-chain-pbkdf2--vmk--fvek)
  - [3.2 ARMv8-CE 4-Way Superscalar Interleaved Pipeline](#32-armv8-ce-4-way-superscalar-interleaved-pipeline)
  - [3.3 Runtime Capability Detection & Self-Test Fallback](#33-runtime-capability-detection--self-test-fallback)
- [4. Hybrid Filesystem Engine & Global FUSE Mount](#4-hybrid-filesystem-engine--global-fuse-mount)
  - [4.1 Read/Write Path Separation & 64KB LRU Block Cache](#41-readwrite-path-separation--64kb-lru-block-cache)
  - [4.2 Transparent Boot Sector Redirection](#42-transparent-boot-sector-redirection)
  - [4.3 Global FUSE Daemon & Cross-Process Cache Coherency](#43-global-fuse-daemon--cross-process-cache-coherency)
- [5. Memory Safety, Write Barriers & Binary Hardening](#5-memory-safety-write-barriers--binary-hardening)
  - [5.1 Sector-Level Metadata Write Barrier](#51-sector-level-metadata-write-barrier)
  - [5.2 JNI Session Registry (Use-After-Free Prevention)](#52-jni-session-registry-use-after-free-prevention)
  - [5.3 Compiler Hardening & 16 KB Page Size Alignment](#53-compiler-hardening--16-kb-page-size-alignment)
- [6. Empirical Benchmark Datasets](#6-empirical-benchmark-datasets)
  - [6.1 Cryptographic Microbenchmark](#61-cryptographic-microbenchmark)
  - [6.2 Tier 1: 1TB Portable SSD (USB 3.0 / UAS)](#62-tier-1-1tb-portable-ssd-usb-30--uas)
  - [6.3 Tier 2: Mainstream USB Flash Drive (USB 3.0 / BOT)](#63-tier-2-mainstream-usb-flash-drive-usb-30--bot)
  - [6.4 Root vs Non-Root Architectural Comparison & Bottleneck Breakdown](#64-root-vs-non-root-architectural-comparison--bottleneck-breakdown)

---

## 1. Layered Architecture Overview

```
                         External USB OTG Encrypted Drive
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼                                                     ▼
     [Non-Root Mode]                                          [Root Mode]
Android USB Host API (android.hardware.usb)           Linux Kernel Block Nodes (/dev/block/vold/*)
             │                                                     │
             ▼                                                     ▼
PipelinedUsbCommunication + UsbBotTransport           3-Tier Block I/O (Direct pread / bitlocker_io daemon)
(16KB synchronous USBDEVFS_BULK + BOT state machine)  (4MB expanded pipes + writev + block queue sysfs tuning)
             │                                                     │
             └──────────────────────────┬──────────────────────────┘
                                        │
                                        ▼
                       [BitLocker Detector (BitLockerDetector)]
                       Scans -FVE-FS- signature, MBR/GPT topology & partition offsets
                                        │
                                        ▼
                       [DislockerCore Native Engine (JNI / C)]
                       mbedtls + ARMv8-CE (PBKDF2-SHA256, AES-CCM, VMK -> FVEK)
                       16-slot JNI session registry + sector-level metadata write barrier
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼                                                     ▼
[Read-Only Parsing & Directory Enumeration (Kotlin)]  [Write Operations & FUSE Mount (Native C)]
CachedBlockSource (64KB aligned blocks / 4MB LRU)     Lazy Writer Initialization
 ├── NtfsReader  (MFT records & runlist parsing)       ├── libntfs-3g (ntfs3g_device.c: NTFS write & dirty fix)
 ├── ExFatReader (Cluster chains, bitmap & dir items)  └── FatFs      (fatfs_device.c: FAT32 & exFAT write)
 └── Fat32Reader (FAT table & LFN parsing)
             │                                                     │
             └──────────────────────────┬──────────────────────────┘
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             ▼                          ▼                          ▼
[SAF Storage Access Framework]  [LAN Wireless Sharing]    [Global POSIX Virtual Mount]
BitLockerDocumentsProvider      LanWebServer / WebDAV     bitlocker_fuse_daemon (Root)
+ DocumentWritePipeline         (HTTP range / WebDAV)     Mounted at /storage/XXXX-XXXX
(512KB double-buffered async write queue)                 (SIGUSR1 cross-process cache invalidation)
```

---

## 2. Dual-Mode Underlying I/O Transport

### 2.1 Root Mode: 3-Tier Block Device Access

Implemented in `native/dislocker/src/io.c` and `native/daemon/io_daemon.c`, Root mode accesses kernel block devices through a 3-tier hierarchy:

1. **Tier 1 — Direct File Descriptor I/O (`pread` / `pwrite`)**: Used directly if the current process context is permitted by SELinux to open the block node.
2. **Tier 2 — Persistent Privileged I/O Daemon (`bitlocker_io`)**: Because standard Android app sandboxes cannot open `/dev/block/*` directly, the engine spawns a persistent root helper (`libbitlocker_io.so`) via `su`.
   - **Zero Per-Sector Process Spawning**: The block device is opened once per session. Subsequent sector reads and writes are serviced over anonymous stdin/stdout pipes using a binary protocol (magic `0x4249544C`), eliminating the ~30–50 ms `fork`/`exec` overhead of invoking `su -c dd` per operation.
   - **Pipe Buffer Expansion & Vectorized Writes**: On initialization, `fcntl(fd, F_SETPIPE_SZ, ...)` expands the kernel pipe capacity up to **4 MB**, and `writev` coalesces response headers with payload data to minimize syscall overhead.
   - **Kernel Block Queue Tuning**: When opening a block device, the daemon tunes `/sys/dev/block/<maj>:<min>/queue/` parameters (`read_ahead_kb = 2048`, `max_sectors_kb = 1024`, `nr_requests = 256`) to improve sequential readahead and request merging.
3. **Tier 3 — Single-Invocation Fallback (`su -c dd`)**: Retained only as a last-resort fallback if the persistent daemon cannot be initialized.

### 2.2 Non-Root Mode: Userspace USB BOT State Machine & SAF Double-Buffering

In Non-Root mode, I/O traverses the Android USB Host sandbox through three coordinated layers:

1. **Synchronous 16 KB `USBDEVFS_BULK` JNI Bridge (`native/libaums_compat/usb.c`)**:
   - Due to the Linux kernel `MAX_USBFS_BUFFER_SIZE` limit, each `USBDEVFS_BULK` `ioctl` transfer is capped at **16 KB** (16,384 bytes).
   - The native bridge captures `-errno` within the same JNI call as the `ioctl`, preserving exact kernel error codes (such as `-ETIMEDOUT` `-110` or `-EPIPE` `-32` endpoint stall) that would otherwise be lost by Android's Java `UsbDeviceConnection.bulkTransfer()` API.
2. **Strict Serial BOT State Machine (`UsbBotTransport.kt`)**:
   - Enforces explicit `CBW (31B)` → `DATA_IN / DATA_OUT` → `CSW (13B)` phase transitions and tag verification in user space.
   - Implements USB Mass Storage BOT 6.7 recovery semantics: if reading the CSW encounters a Bulk-In endpoint stall (`-EPIPE`), the transport clears the halt (`USBDEVFS_CLEAR_HALT`) and re-reads the CSW for the *same* command tag. No new CBW or write replay is permitted unless a full BOT class reset and dual-endpoint halt clear succeed.
3. **SAF 512 KB Double-Buffered Write Queue (`DocumentWritePipeline.kt`)**:
   - When streaming file writes through `BitLockerDocumentsProvider`, a 2-slot `ArrayBlockingQueue<Slot>` (**512 KB** per slot by default) decouples incoming SAF `InputStream` reads from underlying encrypted block writes.
   - **Strict Commit Semantics**: Queued buffers are never treated as committed data; `copy()` explicitly executes `writer.truncate()` and `writer.sync()` and propagates any flush failure before returning.

### 2.3 Kernel-to-Userspace USB Handoff Policy

When an external drive (especially a UAS-capable portable SSD) transitions between the Linux kernel driver (Root mode) and user-space `usbfs` (Non-Root mode):
- **Claiming for Userspace BOT (`PipelinedUsbCommunication.init`)**: After calling `claimInterface(usbInterface, true)` to detach the kernel driver, the transport explicitly calls `setInterface(usbInterface)` to select the BOT alternate setting, ensuring the device does not remain stuck on a UAS alternate setting previously selected by the kernel.
- **Returning to Kernel Driver (`resetForKernelHandoff` & `UsbKernelHandoffPolicy.kt`)**: Executes `USBDEVFS_RESET` while `usbfs` still owns the interface before releasing the claim and closing the descriptor, allowing the kernel to cleanly re-enumerate the device.

---

## 3. Cryptographic Engine & ARMv8 Hardware Acceleration

### 3.1 Key Derivation Chain (PBKDF2 / VMK / FVEK)

1. **Metadata Parsing (`metadata.c`)**: Detects `-FVE-FS-` or BitLocker To Go volume signatures, reads the three redundant 64 KB FVE metadata blocks (`information_off[0..2]`), and validates their CRC32 checksums and datum structures.
2. **Key Stretching (`vmkey.c`)**: Stretches user passwords or 48-digit recovery keys through **1,048,576 rounds (`0x100000`) of SHA-256** (each round hashing an 88-byte structure spanning two SHA-256 compression blocks, totaling 2,097,152 block compressions).
3. **VMK & FVEK Unwrapping**: Unwraps the Volume Master Key (VMK) via **AES-CCM**, then unwraps the Full Volume Encryption Key (FVEK) for sector encryption/decryption (supporting AES-XTS 128/256 and non-diffuser AES-CBC 128/256). Legacy Vista-era `AES-CBC + Elephant Diffuser` volumes are explicitly rejected at initialization to prevent silent corruption.

### 3.2 ARMv8-CE 4-Way Superscalar Interleaved Pipeline

On `arm64-v8a`, `aes_xts_armv8ce.c` and `sha256_armv8ce.c` utilize ARMv8-A Cryptography Extensions intrinsics (`vaeseq_u8`, `vaesdq_u8`, `vaesmcq_u8`, `vaesimcq_u8`, `vsha256hq_u32`):

- **4-Way Instruction-Level Interleaving**: In `aes_xts_crypt_armv8ce`, each loop iteration pre-computes four consecutive 16-byte tweaks (`t0..t3`) in GF($2^{128}$) and interleaves `AESE`/`AESMC` (or `AESD`/`AESIMC`) instructions across four independent 128-bit NEON registers (`b0..b3`). This keeps superscalar out-of-order execution units saturated and eliminates back-to-back round register dependency stalls.
- **Branchless GF($2^{128}$) Multiplication**: Tweak progression (`armv8ce_gf128mul_x`) uses branchless bit shifts and mask arithmetic (`0x87ULL & (-(int64_t)carry)`), avoiding lookup-table cache misses.

### 3.3 Runtime Capability Detection & Self-Test Fallback

- Hardware support is queried at runtime via `getauxval(AT_HWCAP)` (`HWCAP_AES` and `HWCAP_SHA2`).
- **Mandatory Round-Trip Self-Test (`armv8ce_self_test`)**: Before enabling hardware acceleration, the engine executes a known-answer round-trip encryption/decryption check for AES-XTS/CBC and a NIST vector check for SHA-256. If any check fails, the engine falls back to the software `mbedtls` implementation.
- Hardware acceleration can be toggled at runtime via Settings or ADB (`set_hardware_accel`) for A/B benchmarking.

---

## 4. Hybrid Filesystem Engine & Global FUSE Mount

### 4.1 Read/Write Path Separation & 64KB LRU Block Cache

`DislockerCore` separates read-only browsing from write operations using **lazy writer initialization**:

1. **Read-Only Parsing Path (Kotlin)**:
   - Unlocking a volume initializes only a lightweight Kotlin `VolumeReader` (`NtfsReader`, `ExFatReader`, or `Fat32Reader`) without mounting a native write driver.
   - All reads pass through `CachedBlockSource`, which aligns arbitrary byte reads (such as 4-byte FAT entries or 1 KB MFT records) into **64 KB** sector-aligned decrypted blocks and caches up to 64 blocks (**4 MB** LRU footprint).
   - **Zero-Fill Eviction**: Plaintext byte arrays are explicitly zeroed (`Arrays.fill(block, 0)`) upon LRU eviction or volume closure.
2. **Write & Mutation Path (Native C)**:
   - Native write drivers are mounted lazily on the first create, write, rename, delete, or truncate operation:
     - **NTFS**: Uses a tailored **`libntfs-3g`** build (`ntfs3g_device.c`).
     - **FAT32 & exFAT**: Uses **ChaN's `FatFs`** (`fatfs_device.c`) configured with `FF_FS_EXFAT = 1`, `FF_USE_LFN = 3` (UTF-8 heap buffer), and `FF_LBA64 = 1`.

### 4.2 Transparent Boot Sector Redirection

When BitLocker encrypts a FAT32 or exFAT volume, it relocates the original filesystem boot sectors (`0..nb_backup_sectors`) to `boot_sectors_backup` inside the encrypted volume and writes the `-FVE-FS-` header at physical sector 0.

In `fatfs_device.c` and `ntfs3g_device.c` (`disk_read_internal` / `disk_write_internal`), logical accesses within `[0, backup_bytes)` are transparently redirected to `[boot_sectors_backup, boot_sectors_backup + backup_bytes)`, allowing standard `FatFs` and `libntfs-3g` drivers to mount the volume unmodified.

### 4.3 Global FUSE Daemon & Cross-Process Cache Coherency

In Root mode, `bitlocker_fuse_daemon` (`libbitlocker_fuse.so`) enters the PID 1 mount namespace (`su -mm`), opens `/dev/fuse`, and mounts the volume at `/storage/XXXX-XXXX`:

- **Cross-Process Cache Coherency (`SIGUSR1`)**: Because the app's SAF `DocumentsProvider` and `bitlocker_fuse_daemon` run in separate processes with independent filesystem caches, any mutation performed through SAF sends `SIGUSR1` to the FUSE daemon. On the next iteration of its dispatch loop, the FUSE daemon detects `g_cache_dirty` and remounts its internal `ntfs-3g` / `FatFs` handle to prevent stale metadata reads.

---

## 5. Memory Safety, Write Barriers & Binary Hardening

### 5.1 Sector-Level Metadata Write Barrier

To prevent filesystem bugs or miscalculated offsets from overwriting critical BitLocker metadata, `dislocker.c` (`dis_write_encrypted` / `dis_encrypt_region`) and `access.c` (`dis_encrypt_sector`) enforce a strict write barrier:
- Blocks any write targeting the BitLocker volume header region (`offset < nb_backup_sectors * sector_size`, minimum 8,192 bytes);
- Blocks any write overlapping any of the three 64 KB FVE metadata blocks (`[information_off[i], information_off[i] + 0x10000)` for $i \in \{0,1,2\}$);
- Violations immediately abort with `-EPERM`.

### 5.2 JNI Session Registry (Use-After-Free Prevention)

Rather than dereferencing raw `jlong` pointers passed from Java, `dislocker_jni.c` validates every handle against a 16-slot registry (`g_sessions`):
- Each active operation calls `slot_acquire(handle)`, which verifies the slot is active and acquires its per-session `pthread_mutex_t`.
- On teardown, `slot_take(handle)` marks `closing = 1` under the global lock (causing subsequent `slot_acquire` calls to fail cleanly with `IllegalStateException`), releases the global lock while waiting for in-flight I/O on the per-session lock to drain, and then zeros out the FVEK (`dis_secure_zero`) before freeing memory.

### 5.3 Compiler Hardening & 16 KB Page Size Alignment

`native/CMakeLists.txt` applies the following flags across all native targets (`libdislocker.so`, `libbitlocker_io.so`, `libbitlocker_fuse.so`, `libusb-lib.so`, `liberrno-lib.so`):
- `_FILE_OFFSET_BITS=64`: Ensures 64-bit `off_t` on 32-bit ABIs (`armeabi-v7a`) so offsets beyond 2 GiB do not truncate;
- `_FORTIFY_SOURCE=2` and `-fstack-protector-strong`: Enables bounds-checked libc functions and stack canaries;
- `-Wl,-z,relro` and `-Wl,-z,now`: Enables full RELRO so the GOT of privileged root daemons is read-only;
- `-Wl,-z,max-page-size=16384`: Aligns ELF segments to 16 KB boundaries for Android 15+ 16 KB page size compatibility.

---

## 6. Empirical Benchmark Datasets

> [!NOTE]
> Benchmarks follow the **[Performance Evaluation Specification (perf_eval.md)](perf_eval.md)**:
> - **Host Platform**: Qualcomm Snapdragon 8 Gen 2, Android 16, Linux Kernel 5.15 (Root Direct I/O);
> - **Bus Interface**: USB 3.0 (5 Gbps);
> - **Methodology**: First cold-start warmup run discarded; arithmetic mean of $N=5$ valid runs; kernel Page Cache dropped (`echo 3 > /proc/sys/vm/drop_caches`) before each read; write timing includes physical `sync`.

### 6.1 Cryptographic Microbenchmark (1,048,576 rounds PBKDF2 SHA-256)

| Implementation | Mean Time (ms) | Min (ms) | Max (ms) | Hardware Speedup | RSD |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ARMv8 CE Hardware Acceleration** | **197.65 ~ 207.51** | 158.97 | 227.50 | **3.86x ~ 4.13x** | ±11.9% |
| **Pure C Software Fallback** | 801.73 ~ 815.56 | 734.77 | 938.64 | 1.00x (Baseline) | ±10.3% |

### 6.2 Tier 1: 1TB Portable SSD (HIKSEMI P202 1TB / USB 3.0 / UAS)

#### Sequential Throughput (MB/s)

| Partition & Cipher | Seq Read Mean (MB/s) | Read Peak (MB/s) | Read RSD | Seq Write Mean (MB/s) | Write Peak (MB/s) | Write RSD |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **78.77** | 100.36 | ±20.2% | **50.29** | 57.52 | ±13.3% |
| **NTFS (AES-CBC)** | **63.16** | 67.64 | ±6.7% | **40.93** | 47.49 | ±9.6% |
| **exFAT (AES-XTS)** | **77.02** | 99.50 | ±20.3% | **53.17** | 58.29 | ±10.1% |
| **exFAT (AES-CBC)** | **100.58** | 145.55 | ±29.4% | **48.20** | 66.66 | ±21.9% |
| **FAT32 (AES-XTS)** | **61.54** | 67.44 | ±5.6% | **56.59** | 61.83 | ±9.1% |
| **FAT32 (AES-CBC)** | **82.91** | 115.00 | ±25.1% | **41.88** | 43.89 | ±3.3% |

#### 4K Random Access Performance

| Partition & Cipher | 4K Read Latency / IOPS | Read Latency RSD | 4K Write Latency / IOPS | Write Latency RSD |
| :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **1.40 ms** (718.0 IOPS) | ±7.9% | **7.13 ms** (140.4 IOPS) | ±4.3% |
| **NTFS (AES-CBC)** | **1.36 ms** (744.7 IOPS) | ±14.5% | **7.47 ms** (134.1 IOPS) | ±4.6% |
| **exFAT (AES-XTS)** | **1.32 ms** (759.7 IOPS) | ±8.3% | **10.87 ms** (92.0 IOPS) | ±2.1% |
| **exFAT (AES-CBC)** | **1.37 ms** (731.2 IOPS) | ±5.4% | **10.22 ms** (98.8 IOPS) | ±10.5% |
| **FAT32 (AES-XTS)** | **1.40 ms** (717.1 IOPS) | ±7.2% | **8.05 ms** (126.0 IOPS) | ±13.5% |
| **FAT32 (AES-CBC)** | **1.42 ms** (706.9 IOPS) | ±9.3% | **9.16 ms** (109.2 IOPS) | ±2.4% |

### 6.3 Tier 2: Mainstream USB Flash Drive (Lexar JumpDrive 32GB / USB 3.0 / BOT)

#### Sequential Throughput (MB/s)

| Partition & Cipher | Seq Read Mean (MB/s) | Read Peak (MB/s) | Read RSD | Seq Write Mean (MB/s) | Write Peak (MB/s) | Write RSD |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **52.16** | 61.32 | ±11.4% | **16.92** | 23.96 | ±30.7% |
| **NTFS (AES-CBC)** | **37.09** | 43.50 | ±12.8% | **12.34** | 20.05 | ±36.2% |
| **exFAT (AES-XTS)** | **66.46** | 79.51 | ±19.3% | **15.96** | 25.33 | ±38.2% |
| **exFAT (AES-CBC)** | **58.36** | 79.32 | ±21.7% | **17.72** | 29.47 | ±47.6% |
| **FAT32 (AES-XTS)** | **57.25** | 79.08 | ±23.8% | **10.43** | 13.33 | ±18.9% |
| **FAT32 (AES-CBC)** | **74.53** | 96.02 | ±20.5% | **9.98** | 12.55 | ±18.1% |

#### 4K Random Access Performance

| Partition & Cipher | 4K Read Latency / IOPS | Read Latency RSD | 4K Write Latency / IOPS | Write Latency RSD |
| :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **1.98 ms** (507.9 IOPS) | ±9.7% | **7.24 ms** (138.5 IOPS) | ±6.5% |
| **NTFS (AES-CBC)** | **2.00 ms** (501.7 IOPS) | ±5.4% | **6.01 ms** (173.3 IOPS) | ±24.4% |
| **exFAT (AES-XTS)** | **2.00 ms** (503.2 IOPS) | ±8.6% | **9.15 ms** (110.9 IOPS) | ±13.4% |
| **exFAT (AES-CBC)** | **2.16 ms** (475.6 IOPS) | ±19.2% | **8.15 ms** (122.9 IOPS) | ±4.0% |
| **FAT32 (AES-XTS)** | **1.97 ms** (508.1 IOPS) | ±4.3% | **8.62 ms** (126.2 IOPS) | ±30.4% |
| **FAT32 (AES-CBC)** | **2.40 ms** (427.6 IOPS) | ±19.6% | **9.65 ms** (106.4 IOPS) | ±17.4% |

### 6.4 Root vs Non-Root Architectural Comparison & Bottleneck Breakdown

> [!NOTE]
> The comparison table below was collected during earlier architectural validation on Tier 2 USB 3.0 flash media (10-sample mean) prior to enforcing per-iteration `drop_caches` on reads; Root sequential read metrics therefore include partial kernel readahead contributions (107.52 MB/s).

| Evaluation Dimension & Metric | Non-Root Mode (USB Host + Double-Buffer) | Root Mode (Kernel Block Direct I/O) | Speedup Ratio |
| :--- | :--- | :--- | :--- |
| **Sequential Read Throughput** | 43.25 MB/s | **107.52 MB/s** | **2.49x** |
| **Sequential Write Throughput** | 12.97 MB/s | **22.98 MB/s** | **1.77x** |
| **4K Random Read Latency** | 1.33 ms (752 IOPS) | **0.97 ms** (1,031 IOPS) | **27.1% lower latency** |
| **4K Random Write Latency** | 9.25 ms (108 IOPS) | **5.20 ms** (192 IOPS) | **43.8% lower latency** |
| **50MB Large File E2E Write** | 8.14 MB/s | **34.52 MB/s** | **4.24x** |
| **50MB Large File E2E Read** | 13.76 MB/s | **21.12 MB/s** | **1.53x** |

#### Four Primary Architectural Causes of the Performance Delta:

1. **`usbfs` 16 KB Packet Slicing vs Kernel DMA**: Root mode accesses `/dev/block/*` via native Linux kernel block drivers and hardware DMA, transferring multi-megabyte buffers per request. Non-Root mode is constrained by the user-space `usbfs` 16 KB buffer ceiling, requiring large transfers to be split into many 16 KB `USBDEVFS_BULK` `ioctl` calls.
2. **Half-Duplex BOT Stop-and-Wait vs UAS Command Queueing**: Non-Root mode executes half-duplex BOT in user space, waiting sequentially for `CBW -> DATA -> CSW` on every SCSI command. Root mode leverages kernel UAS command queueing (NCQ) on compatible external SSDs.
3. **SAF Stream Bridging & JNI Boundary Crossings**: Non-Root mode serves files through Android's Storage Access Framework (SAF) and routes sector I/O across the JNI boundary between native C filesystem drivers and Kotlin `UsbBlockDevice` handlers. Root mode's FUSE daemon exposes a direct POSIX mount point (`/storage/XXXX-XXXX`).
4. **Kernel Page Cache & Write Coalescing**: Root mode benefits from the Linux kernel Page Cache and `blk-mq` scheduler, which coalesce small metadata updates (such as FAT table or MFT bitmap writes). In Non-Root mode, metadata updates flush directly to the USB flash controller, increasing flash write amplification.
