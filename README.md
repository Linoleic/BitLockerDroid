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
- [Read/Write Benchmark & Performance](#readwrite-benchmark--performance)
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
| **Read Throughput** | Sequential read ~35 to 50 MB/s | Sequential read up to 140+ MB/s |
| **Mount Form** | SAF DocumentsProvider (system Files app) | SAF DocumentsProvider + Global FUSE (`/storage/XXXX-XXXX`) |
| **Dirty Repair & Diagnostics** | Read-only structural diagnostics & clean unmount | 1-Click Dirty Bit reset & deep structural integrity diagnostics |
| **System False Alerts** | Notification listener suppresses system format prompts | Privileged suppression of false format notifications |

---

## Read/Write Benchmark & Performance

The following benchmarks were evaluated across 6 independent BitLocker partitions on a physical USB 3.0 flash drive under Android 16 (Snapdragon 8 Gen 2), with 10 independent samples averaged per test condition (N=10). For comprehensive multi-dimensional data tables, see **[perf_test.md](perf_test.md)**.

### Sequential Throughput & Random Latency (10-Sample Average)

| Partition & Cipher | Filesystem | Non-Root HW Pipeline | Non-Root HW No Pipeline | Root HW Mode | Root Speedup | Non-Root 4K Latency | Root 4K Latency |
|---|---|---|---|---|---|---|---|
| **NTFS (AES-XTS)** | NTFS | 50.05 MB/s (Read) / 18.73 MB/s (Write) | 41.76 MB/s (Read) / 13.12 MB/s (Write) | **122.19 MB/s** (Read) / **21.30 MB/s** (Write) | **2.44x** (Read) | 1.25 ms (860 IOPS) | **1.10 ms** (954 IOPS) |
| **NTFS (AES-CBC)** | NTFS | 34.87 MB/s (Read) / 14.72 MB/s (Write) | 41.24 MB/s (Read) / 11.86 MB/s (Write) | **95.04 MB/s** (Read) / **26.29 MB/s** (Write) | **2.73x** (Read) | 1.46 ms (720 IOPS) | **0.99 ms** (1078 IOPS) |
| **exFAT (AES-XTS)** | exFAT | 48.24 MB/s (Read) / 20.80 MB/s (Write) | 41.29 MB/s (Read) / 16.34 MB/s (Write) | **97.40 MB/s** (Read) / **20.34 MB/s** (Write) | **2.02x** (Read) | 1.11 ms (937 IOPS) | **0.93 ms** (1124 IOPS) |
| **exFAT (AES-CBC)** | exFAT | 46.59 MB/s (Read) / 20.14 MB/s (Write) | 38.95 MB/s (Read) / 11.74 MB/s (Write) | **95.98 MB/s** (Read) / **31.63 MB/s** (Write) | **2.06x** (Read) | 1.24 ms (837 IOPS) | **0.98 ms** (1097 IOPS) |
| **FAT32 (AES-XTS)** | FAT32 | 36.87 MB/s (Read) / 1.76 MB/s (Write) | 39.80 MB/s (Read) / 1.37 MB/s (Write) | **93.83 MB/s** (Read) / **19.60 MB/s** (Write) | **2.55x** (Read) | 1.49 ms (703 IOPS) | **0.92 ms** (1140 IOPS) |
| **FAT32 (AES-CBC)** | FAT32 | 42.90 MB/s (Read) / 1.70 MB/s (Write) | 40.78 MB/s (Read) / 1.39 MB/s (Write) | **140.66 MB/s** (Read) / **18.71 MB/s** (Write) | **3.28x** (Read) | 1.46 ms (715 IOPS) | **0.92 ms** (1152 IOPS) |

### Key Benchmark Takeaways

- **ARMv8 CE Hardware Acceleration**: Reduces 1,048,576 rounds of SHA-256 PBKDF2 key stretching from 494.52 ms down to 104.03 ms (**4.75x speedup**), achieving instantaneous password verification.
- **Double-Buffering Pipeline Gain**: In Non-Root mode, the asynchronous write pipeline boosts continuous write throughput by up to **+39.5%** (12.97 MB/s vs 9.30 MB/s) and accelerates large 50MB file transfers by **+24.3%** (8.14 MB/s vs 6.55 MB/s).
- **Root Mode Kernel Direct I/O**: Direct kernel block device access achieves an average sequential read throughput of **107.52 MB/s** (**2.49x** over Non-Root) and 50MB file write throughput of **34.52 MB/s** (**4.24x** over Non-Root).
- **Data Integrity**: 100% of read and write transfer cycles across all formats passed end-to-end MD5 and SHA-256 hash consistency checks.
- For complete raw and categorized benchmark tables, refer to **[perf_test.md](perf_test.md)**.

---

## Key Features

- **ARMv8 CE Hardware Cryptography Acceleration**: Harnesses ARMv8-A Cryptography Extensions (`PMULL`, `AES`, `SHA2`) for native hardware execution of AES-XTS, AES-CBC, and PBKDF2 SHA-256 key stretching (accelerating volume unlock by 4.75x, down to ~104 ms). Includes automatic runtime CPU capability detection (`getauxval(AT_HWCAP)`), vector self-tests, and graceful software fallback.
- **Double-Buffered Asynchronous Write Pipeline**: Decouples SAF document streaming from underlying USB Mass Storage protocol transfer via background ring buffering, improving continuous write throughput by up to 39.5% and reducing 4K random write latency.
- **16 KB Page Alignment (Android 15+)**: Fully conforms to the Google Android 15+ 16 KB ELF page size standard (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` and `useLegacyPackaging = false`), ensuring maximum virtual memory mapping efficiency and future-proof compatibility.
- **Transparent Filesystem Read/Write**: Custom optimized `libntfs-3g` (with sector write barriers protecting `-FVE-FS-` metadata), exFAT driver (supporting files >4GB), and `FatFs` (full Long File Name / LFN support). Delivers complete CRUD operations and native system search integration.
- **Accurate Multi-Partition Scanning**: Analyzes hardware topology to distinguish parent disk devices from partition nodes, preventing duplicate drive listings and supporting concurrent auto-unlocking.
- **Dirty Volume Repair & Structural Diagnostics**:
  - **1-Click Dirty Bit Reset**: Instantly resets unclean unmount flags across NTFS, FAT32, and exFAT partitions caused by hot-unplugging, restoring full read/write access without requiring a PC.
  - **Deep Structural Integrity Diagnostics**: Sub-50ms non-destructive metadata integrity scan covering NTFS (MFT USN/Fixup torn-write validation, `$MFTMirr` consistency, `$INDEX_ROOT` B-Tree index), FAT32 (Sector 0 vs Sector 6 backup boot sector, FSInfo signature, FAT1 vs FAT2 consistency, directory loop detection), and exFAT (Microsoft-compliant 11-sector cyclic redundancy boot checksum, Sector 12 backup comparison, `MediaFailure` hardware flag, root directory stream parsing).
  - **Pre-Flight Safety Barrier**: Automatically validates volume health prior to dirty bit reset. Displays high-risk warning banners and prevents accidental writes if true structural corruption is detected.
- **Data Safety & Eject Protection**: Hardware-level read-only protection toggle intercepts all write operations at driver level. Safe eject forces two-level cache flush and clears filesystem Dirty Bits to prevent Windows from prompting "Scan and fix drive". Warns on unclean unmounted volumes.
- **Global POSIX Virtual Mount**: In Root mode, injects a FUSE mount into the PID 1 mount namespace (`/storage/XXXX-XXXX`), enabling direct access via standard Linux paths in MT Manager, Termux, media players, and terminal utilities.
- **Non-Destructive Drive Benchmark**: Built-in read-only benchmark tool to measure sequential read throughput (MB/s), 4K random read latency (IOPS), and negotiated USB bus speed (USB 2.0 / USB 3.0 5Gbps / USB 3.1+ 10Gbps).
- **Biometric Credential Vault**: Safeguards BitLocker passwords and recovery keys using Android Keystore hardware-backed encryption. Features an independent toggle in Settings, requiring biometric (fingerprint/face) or lockscreen credentials to inspect saved credentials. Dynamically enforces window `FLAG_SECURE` to prevent sensitive key leakage in screenshots, screen recordings, or recent apps overview.
- **Metadata Backup & Image Export**:
  - **Precise FVE Metadata Backup & Emergency Restore (`.fvemeta`)**: Extracts Sector 0 (VBR) and all three 64KB FVE metadata blocks into an integrity-verified container (SHA-256 and CRC-32). Allows emergency low-level sector writeback if volume headers become corrupted.
  - **Strict Capacity Safety Barrier**: During metadata restoration, the engine strictly compares the physical partition capacity and sector size against the backup header. If any mismatch is detected, writes are unconditionally blocked to protect other drives and partitions.
  - **Dual-Mode Volume Dump Service**: Streams either a decrypted virtual volume image (`.img`) directly mountable on PC without BitLocker, or a raw encrypted block partition (`.raw`) for forensic backup. Equipped with an Android Foreground Service (`dataSync`), partial WakeLock, real-time throughput monitoring (MB/s), ETA calculation, and clean cancellation.
- **LAN Wireless Sharing (Dual Web & WebDAV Protocols)**:
  - **Zero-Client Native OS Mounting**: Fully compliant WebDAV server allows Windows ("Map Network Drive"), macOS Finder ("Connect to Server"), and iOS/iPadOS "Files" to mount the decrypted volume directly as a network disk with fast read/write throughput.
  - **Modern Responsive Web Portal**: Automatically hosts an adaptive light/dark web interface with QR code quick access, folder hierarchy navigation, multi-threaded resume (HTTP 206 Partial Content), inline multimedia streaming, and drag-and-drop batch file uploads.
  - **Granular Security & Lifecycle Guard**: Configurable custom port, read-only tamper protection, and HTTP Basic authentication; protected by Android Foreground Service (`dataSync`), High-Performance Wi-Fi Lock, and CPU WakeLock, with automatic cleanup on drive detachment or unexpected unmount.
- **Native Unencrypted Volume Coexistence**: Intelligently identifies unencrypted USB flash drives (FAT32, exFAT, NTFS), relinquishing USB Host exclusivity to Android OS in non-root mode to prevent redundant permission prompts; clearly displays volume status and disk usage with 1-click navigation to system file managers.
- **False Alert Filter**: Automatically filters Android system notifications falsely claiming the encrypted drive is corrupted or needs formatting.

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
| **Operating System** | Android 13.0+ (API 33 ~ 37) | Requires Android 13.0 or newer (`minSdk 33`); forward-compatible with current Android releases (verified through Android 17 / API 37) |
| **CPU Architectures** | `arm64-v8a`, `armeabi-v7a` | Full 64-bit and 32-bit native ABI binaries |
| **Root Schemes** | **Non-Root** / **KernelSU** / **Magisk** / **APatch** | Zero setup for non-root; higher performance with Root |
| **Supported Filesystems** | **NTFS**, **exFAT**, **FAT32** | Full browse, create, modify, rename, and delete capabilities |
| **Encryption Ciphers** | AES-XTS (128/256-bit), AES-CBC (128/256-bit) | Covers Windows 10/11 defaults and Windows 7 legacy volumes |
| **Hardware Crypto** | ARMv8-A Cryptography Extensions (PMULL, AES, SHA2) | Dynamic runtime detection; 4.75x faster unlock; pure software fallback |
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

---

## License & Acknowledgments

This project is licensed under the **GNU General Public License v2.0 (GPL-2.0)**. See the [LICENSE](LICENSE) file for details.

### Core Upstream Dependencies
- **BitLocker Parsing Core**: [dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)
- **Cryptographic Primitives**: [mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)
- **NTFS Filesystem Engine**: [libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)
- **FAT32 Filesystem Engine**: [FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)
