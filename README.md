# BitLockerDroid (BitUnlocker)

<p align="center">
  <b>English</b> | <a href="README_zh.md">简体中文</a>
</p>

<p align="center">
  <b>Native Android application to unlock, browse, mount, read, and write Microsoft BitLocker encrypted drives (NTFS, exFAT, FAT32).</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-13.0%2B_API_33--37-blue.svg" alt="Android 13.0+ (API 33 ~ 37)">
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## Table of Contents

- [Introduction](#introduction)
- [Dual-Mode Architecture](#dual-mode-architecture)
- [Theoretical Performance & Architecture](#theoretical-performance--architecture)
- [Measured Benchmark Data](#measured-benchmark-data)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [Technical Specifications & Compatibility](#technical-specifications--compatibility)
- [Quick Start](#quick-start)
- [Build from Source](#build-from-source)
- [FAQ & Technical Notes](#faq--technical-notes)
- [License & Acknowledgments](#license--acknowledgments)

---

## Introduction

**BitLockerDroid** (application display name **BitUnlocker**) is an Android application designed to access and manage Microsoft BitLocker encrypted drives on mobile devices. It enables unlocking, browsing, reading, and writing to external storage media (USB OTG flash drives, external HDDs/SSDs, and SD cards).

- **Filesystem Support**: Full CRUD operations (browse, create, modify, rename, delete) on NTFS, exFAT, and FAT32 partitions.
- **Authentication Credentials**: Supports user passwords (PBKDF2/SHA-256) and 48-digit numerical recovery keys.
- **System Integration**: Integrated with the Android Storage Access Framework (SAF DocumentsProvider) for file management directly within the system "Files" app.
- **Global POSIX Virtual Mount (Root)**: Leverages FUSE to mount decrypted volumes to `/storage/XXXX-XXXX`, enabling third-party apps to access files via standard Linux absolute paths.
- **Dual Operation Modes**: Supports both Non-Root (Android USB Host API) and Root (direct kernel block device access) underlying architectures.

---

## Dual-Mode Architecture

The application provides two distinct driver architectures, seamlessly switchable in Settings (dirty data is flushed and active sessions are cleanly unmounted before switching):

| Dimension | Non-Root Mode (USB Host API) | Root Mode (KernelSU / Magisk / APatch) |
|---|---|---|
| **Privilege Requirement** | USB device permission only; no Root required | Root permission (su) |
| **Device Support** | USB OTG external storage devices | USB OTG devices, multi-partition disks, kernel block nodes |
| **I/O Channel** | Android USB Host API + user-space SCSI driver | Linux kernel block device nodes (`/dev/block/vold/*`) |
| **Theoretical Performance** | Constrained by userspace usbfs packetization & half-duplex BOT protocol; double-buffering pipeline mitigates flash write jitter | Direct Linux kernel block nodes & hardware DMA with UAS queueing; theoretically approaches physical bus & crypto limits |
| **Mount Form** | SAF DocumentsProvider (system Files app) | SAF DocumentsProvider + Global FUSE (`/storage/XXXX-XXXX`) |
| **Dirty Repair & Diagnostics** | Read-only structural diagnostics & clean unmount | 1-Click Dirty Bit reset & deep structural integrity diagnostics |
| **System False Alerts** | Notification listener suppresses system format prompts | Privileged suppression of false format notifications |

---

## Theoretical Performance & Architecture

The throughput and cryptographic efficiency of BitLockerDroid are governed by its underlying driver architecture and hardware acceleration:

- **ARMv8 CE Hardware Cryptography**: The ARMv8-A Cryptography Extensions (`PMULL`, `AES`, `SHA2`) execute cryptographic primitives via dedicated single-cycle hardware instructions. This eliminates the heavy CPU cycle overhead and L1/L2 cache pollution of software table lookups, theoretically boosting PBKDF2 key stretching and sector decryption throughput by several multiples for near-instantaneous authentication.
- **Multi-Threaded Parallel Block Decryption**: The Native C core engine integrates a dynamic multi-core worker thread pool. For large sequential I/O requests, sector ciphertext blocks are partitioned and dispatched across available CPU cores in parallel. Theoretical compute throughput scales linearly with CPU core count, preventing single-core bottlenecks during 4K/8K media streaming and large file transfers.
- **Double-Buffered Asynchronous Pipeline**: To overcome the stop-and-wait overhead inherent to the half-duplex BOT protocol in Non-Root mode, a userspace ping-pong ring buffer overlaps upper SAF data streaming with underlying USB bus transfers, theoretically maximizing bus utilization and mitigating flash write amplification.
- **Root Mode Zero-Overhead Direct I/O**: Direct interaction with Linux kernel block device nodes (`/dev/block/*`), system Page Cache, and hardware DMA controllers completely bypasses Android sandbox `usbfs` packetization and Binder IPC. Native support for UAS (USB Attached SCSI) concurrent command queuing allows throughput to approach the physical bus and media ceiling.

---

## Measured Benchmark Data

> [!NOTE]
> Benchmarks are conducted strictly adhering to the **[Performance Evaluation Specification (perf_eval.md)](perf_eval.md)**:
> - **Host Platform**: Qualcomm Snapdragon 8 Gen 2, Android 16, 5.15 Linux Kernel (Root Direct I/O);
> - **Bus Interface**: USB 3.0 high-speed link;
> - **Methodology**: Initial cold-start warmup run is discarded; arithmetic mean of $N=5$ valid iterations; kernel Page Cache dropped (`drop_caches`) before each read; write timing tightly bounds underlying physical `sync`; instantaneous peak (Peak) and relative standard deviation (RSD) are reported across all metrics.

### 1. Cryptographic Microbenchmark (1,048,576 rounds PBKDF2 SHA-256)

Evaluating core cryptographic throughput during key derivation:

| Implementation | Mean Time (ms) | Min (ms) | Max (ms) | Hardware Speedup | RSD |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ARMv8 CE Hardware Acceleration** | **197.65 ~ 207.51** | 158.97 | 227.50 | **3.86x ~ 4.13x** | ±11.9% |
| **Pure C Software Fallback** | 801.73 ~ 815.56 | 734.77 | 938.64 | 1.00x (Baseline) | ±10.3% |

### 2. Tier 1: 1TB Portable Solid-State Drive (HIKSEMI P202 1TB / USB 3.0 / UAS Protocol)

Empirical throughput and random seek performance across filesystems and BitLocker ciphers:

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

### 3. Tier 2: Mainstream USB Flash Drive (Lexar JumpDrive 32GB / USB 3.0 / BOT Protocol)

Empirical throughput and random seek performance across filesystems and BitLocker ciphers:

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

### 4. Root Mode vs Non-Root Mode Architectural Performance Comparison

Comparing empirical performance under identical hardware and cryptographic configurations between Linux Kernel Direct I/O (Root) and Android Sandbox Userspace USB Host API (Non-Root):

#### Comprehensive Architectural Performance Benchmark (10-sample mean)

| Evaluation Dimension & Metric | Non-Root Mode (USB Host + Pipeline) | Root Mode (Kernel Direct I/O) | Speedup / Improvement |
| :--- | :--- | :--- | :--- |
| **Sequential Read Throughput** | 43.25 MB/s | **107.52 MB/s** | **+148.6% (2.49x)** |
| **Sequential Write Throughput** | 12.97 MB/s | **22.98 MB/s** | **+77.2% (1.77x)** |
| **4K Random Read Latency** | 1.33 ms (752 IOPS) | **0.97 ms** (1,031 IOPS) | **Latency reduced by 27.1%** |
| **4K Random Write Latency** | 9.25 ms (108 IOPS) | **5.20 ms** (192 IOPS) | **Latency reduced by 43.8% (1.78x)** |
| **50MB Large File Real Write** | 8.14 MB/s | **34.52 MB/s** | **+324.1% (4.24x)** |
| **50MB Large File Real Read** | 13.76 MB/s | **21.12 MB/s** | **+53.5% (1.53x)** |

#### Root vs Non-Root Bottleneck Breakdown

1. **I/O Dispatch & Buffer Chunking Overhead**: Root mode directly accesses Linux kernel block nodes (`/dev/block/*`) leveraging kernel drivers and hardware DMA. Non-Root mode is constrained by Android user-space `usbfs`, forcing I/O requests to be sliced into 16 KB `UsbRequest` packets, introducing frequent `ioctl` syscalls and context switches.
2. **Bus Protocol Mechanics**: Root mode supports kernel UAS with concurrent command queueing; Non-Root mode simulates half-duplex BOT in user space with sequential CBW -> Data -> CSW round trips.
3. **IPC & Virtual Filesystem Layers**: Root mode mounts directly to POSIX `/storage/XXXX-XXXX` via FUSE. Non-Root mode relies on SAF (Storage Access Framework) with synchronous Binder IPC over `ProxyFileDescriptor` and JNI bridging.
4. **Non-Root Double-Buffered Pipeline Mitigation**: BitLockerDroid integrates a 4-stage asynchronous URB ring pipeline and double-buffered ping-pong queue, boosting Non-Root write throughput by **+39.5%** (12.97 MB/s vs 9.30 MB/s baseline BOT) and large-file streaming by **+24.3%**.

---

## Key Features

- **Transparent Full-Featured Filesystem Access**: Custom-tailored `libntfs-3g`, `exFAT`, and `FatFs` drivers provide complete CRUD operations (browse, create, rename, delete) on NTFS, exFAT, and FAT32 partitions with full large-file (>4GB) and long-file-name (LFN) support.
- **Native OS Integration & Global Virtual Mounting**:
  - **Standard SAF Integration**: Seamlessly manage files directly within the native Android system "Files" application;
  - **Global POSIX Mount (Root)**: Injects a FUSE virtual mount to `/storage/XXXX-XXXX`, allowing third-party apps (MT Manager, Termux, media players) to access files via standard Linux absolute paths.
- **Multi-Factor Authentication & Biometric Vault**: Supports user passwords and 48-digit numerical recovery keys; protects stored credentials via Android Keystore hardware-backed encryption, optional biometric (fingerprint/face) authentication, and screen-capture prevention (`FLAG_SECURE`).
- **1-Click Dirty Bit Repair & Structural Integrity Guard**: Safely resets unclean unmount flags (Dirty Bit) without requiring a PC; features pre-flight sub-50ms non-destructive metadata health diagnostics to block dangerous writes if true structural corruption is detected.
- **Accurate Multi-Partition Topology & Hotplug**: Intelligently distinguishes parent disk devices from child partition nodes on multi-partition drives and PSSDs; coexists seamlessly with unencrypted media and automatically suppresses false system formatting warnings.
- **Disaster Recovery Backup & Volume Image Export**: Precise extraction and emergency sector writeback for FVE volume headers (`.fvemeta`); background-service-backed export of decrypted volume images (`.img`) or raw encrypted partitions (`.raw`) with ETA and cancellation support.
- **Zero-Client LAN Wireless Sharing (Web & WebDAV)**: Integrated WebDAV server for native network drive mounting on Windows, macOS, and iOS/iPadOS; alongside a responsive light/dark web portal with QR-code access, media streaming, and batch file uploads.

---

## System Architecture

```
                         External USB OTG Encrypted Drive
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼                                                     ▼
    [Non-Root Mode]                                           [Root Mode]
Android USB Host API (android.hardware.usb)              Linux Kernel Block Nodes (/dev/block/vold/*)
             │                                                     │
             ▼                                                     ▼
User-space SCSI/BOT Stack (UsbMassStorageDriver)         Direct I/O High-Performance Block I/O (su)
             │                                                     │
             └──────────────────────────┬──────────────────────────┘
                                        │
                                        ▼
                       [BitLocker Detector (BitLockerDetector)]
                       Scans -FVE-FS- volume header & partition topology
                                        │
                                        ▼
                       [DislockerCore Native Engine (JNI / C)]
                       mbedtls crypto primitives (PBKDF2, AES-CCM, VMK derivation)
                       Extracts FVEK session key (AES-XTS / AES-CBC)
                                        │
                                        ▼
                       [Dynamic Sector Decryption & Write Barrier]
                       Sector-level write filter (protects volume metadata & MBR/GPT)
                                        │
             ┌──────────────────────────┼──────────────────────────┐
             ▼                          ▼                          ▼
      libntfs-3g Driver            FatFs Driver               Custom exFAT Driver
     (NTFS read/write)          (FAT32 LFN support)          (Cluster chain & metadata)
             │                          │                          │
             └──────────────────────────┼──────────────────────────┘
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼                                                     ▼
    [SAF Storage Access Framework]                            [Global POSIX Virtual Mount]
    BitLockerDocumentsProvider                                bitlocker_fuse_daemon (Root)
             │                                                     │
             ▼                                                     ▼
    Android System Files App (DocumentsUI)                    Global Absolute Path (/storage/XXXX-XXXX)
    (Native file management & search)                         (MT Manager, Termux, media players)
```

---

## Technical Specifications & Compatibility

| Dimension | Supported Range / Specification | Notes |
|---|---|---|
| **Operating System** | Android 13.0+ (API 33 ~ 37) | Requires Android 13.0 or newer (`minSdk 33`); verified on HyperOS 4 (Android 17 / API 37) and HyperOS 3 (Android 16 / API 36) |
| **CPU Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` | Full 64-bit and 32-bit native ABI binaries (ARMv8 CE hardware crypto is arm64-v8a only) |
| **Root Schemes** | **Non-Root** / **KernelSU** / **Magisk** / **APatch** | Zero setup for non-root; higher performance with Root |
| **Supported Filesystems** | **NTFS**, **exFAT**, **FAT32** | Full browse, create, modify, rename, and delete capabilities |
| **Encryption Ciphers** | AES-XTS (128/256-bit), AES-CBC (128/256-bit) | Covers Windows 10/11 defaults and Windows 7 legacy volumes |
| **Hardware Crypto** | ARMv8-A Cryptography Extensions (PMULL, AES, SHA2) | Dynamic runtime detection; dedicated hardware acceleration; automatic software fallback |
| **Page Size Alignment** | 16 KB and 4 KB page sizes | Native libraries built with Android 15+ 16 KB ELF page alignment |
| **Authentication Types** | User Password, 48-digit Recovery Key | Displays Recovery Key ID for verification against Microsoft account |
| **Hardware Form Factors** | USB flash drives, Portable SSDs (PSSD), External HDDs, SD cards | Single-partition and multi-partition drives |
| **Physical Interfaces** | USB Type-C OTG, USB-A adapters, Hubs/Docks | USB 2.0 / USB 3.0 (5 Gbps) / USB 3.1+ (10 Gbps) links |

---

## Quick Start

1. **Install**: Download and install `app-release.apk` from [GitHub Releases](https://github.com/Linoleic/BitLockerDroid/releases).
2. **Connect & Grant Permission**: Connect your USB OTG drive. Non-root users tap **Allow** when the system USB prompt appears; Root users grant superuser permission in KernelSU / Magisk / APatch.
3. **Unlock**: Tap **Unlock** on the volume card. Enter the user password or 48-digit recovery key (verify the displayed Recovery Key ID; optionally check "Remember credential" for automatic unlock on next insertion).
4. **Access & Safe Eject**:
   - Tap **Open** to manage files directly in the Android system "Files" app.
   - In Root mode, tap **Virtual mount to real directory** to access files at `/storage/XXXX-XXXX` using third-party apps.
   - Always tap **Safe Eject** and wait for confirmation before physically unplugging the drive.

---

## Build from Source

Quick local build:
```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
./gradlew :app:assembleRelease
```
Output artifact: `app/build/outputs/apk/release/app-release.apk`.

For full environment prerequisites, NDK CMake builds, host verification tools, and release signing configurations, see the comprehensive **[Build Guide (BUILD.md)](BUILD.md)** (or **[中文构建指南 (BUILD_zh.md)](BUILD_zh.md)**).

---

## FAQ & Technical Notes

- **Q: Why should I always tap "Safe Eject" before unplugging?**  
  **A**: Safe eject triggers a two-level cache flush to storage and clears the filesystem Dirty Bit. Directly pulling the drive leaves the Dirty Bit active, causing Windows to display "There is a problem with this drive, scan and fix now" upon reconnection.
- **Q: What is the Recovery Key ID used for?**  
  **A**: An encrypted drive may have multiple historical recovery keys. The unlock dialog displays the Recovery Key ID so you can verify it matches the key listed in your Microsoft account ([account.microsoft.com/devices/recoverykey](https://account.microsoft.com/devices/recoverykey)) before entering the 48 digits.
- **Q: Why do some apps not see `/storage/XXXX-XXXX` after virtual mount?**  
  **A**: Global mount injects the FUSE filesystem into the system PID 1 mount namespace, making it accessible to any app that reads standard POSIX paths (such as MT Manager, Termux, VLC, text editors). However, simplified gallery apps that rely strictly on the Android MediaStore database will not index external non-standard mount paths.
- **Q: Why is transfer throughput in Non-Root mode significantly lower than in Root mode?**  
  **A**: This performance delta stems from fundamental architectural differences between Android's unprivileged user-space sandbox and the Linux kernel storage stack:  
  1. **Driver Privilege & URB Chunking**: Root mode communicates directly with Linux kernel block devices (`/dev/block/*`) backed by native `uas` / `usb-storage` drivers and hardware DMA. Non-Root mode is restricted to user-space `usbfs` (`/dev/bus/usb/*`). Due to the kernel's `MAX_USBFS_BUFFER_SIZE` ceiling (typically 16 KB), large block transfers must be fragmented into multiple 16 KB `UsbRequest` URBs, triggering heavy `ioctl` syscall and context-switching overhead.  
  2. **Protocol Overhead & Handshake Latency**: Kernel drivers leverage UAS command queuing (NCQ) for full-duplex parallel execution. Non-Root mode simulates legacy half-duplex Bulk-Only Transport (BOT) in user space, where every block transfer requires a rigid 3-phase stop-and-wait handshake (CBW -> Data Phase -> CSW), leaving physical USB bus idle time between packets.  
  3. **Binder IPC & JNI Trampoline**: Non-Root mode exposes decrypted files via Android's Storage Access Framework (SAF), where `ProxyFileDescriptor` incurs synchronous Binder IPC overhead across process boundaries. Additionally, low-level sector I/O must repeatedly bounce across the JNI boundary between the native C filesystem driver and Kotlin user-space USB handlers. Root mode bypasses Binder entirely by exposing a global POSIX FUSE mount point.  
  4. **Flash Write Amplification & Lack of Kernel Page Cache**: Root mode benefits from the Linux kernel Page Cache and `blk-mq` I/O scheduler, which coalesces fragmented sector updates before flushing. In Non-Root mode, filesystem metadata updates (such as FAT32 cluster table writes) penetrate directly to storage, triggering severe flash write amplification and controller garbage collection on USB drives.  
  BitLockerDroid mitigates these constraints via a custom 4-stage asynchronous URB pipeline, double-buffered ping-pong write queue, and ARMv8 CE hardware cryptography acceleration, substantially improving Non-Root throughput efficiency; for peak bus bandwidth and minimal access latency, switching to Root mode is recommended.

- **Q: Something went wrong on my device — how can I report it?**  
  **A**: Open a [GitHub Issue](https://github.com/Linoleic/BitLockerDroid/issues) and, if convenient, **attach the diagnostic log** — it is the fastest way to pin down driver or file-system problems. In the app, go to **Settings → Advanced Options → System & Diagnostics → Diagnostic Log**, tap **Copy Log**, then paste it into the issue (or save it as a `.txt` and attach the file). The log never contains your password or recovery key, but it does include volume GUIDs, device paths and file names, so please give it a quick review before posting. The OS build is recorded automatically, so the exact device model is optional.

---

## License & Acknowledgments

This project is licensed under the **GNU General Public License v2.0 (GPL-2.0)**. See the [LICENSE](LICENSE) file for details.

### Core Upstream Dependencies
- **BitLocker Parsing Core**: [dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)
- **Cryptographic Primitives**: [mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)
- **NTFS Filesystem Engine**: [libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)
- **FAT32 Filesystem Engine**: [FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)
