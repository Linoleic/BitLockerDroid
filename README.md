# BitLockerDroid (BitUnlocker)

<p align="center">
  <b>English</b> | <a href="README_zh.md">简体中文</a>
</p>

<p align="center">
  <b>Native Android application to unlock, browse, mount, read, and write Microsoft BitLocker encrypted drives (NTFS, exFAT, FAT32).</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-8.0--17-blue.svg" alt="Android 8.0 ~ 17">
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
| **Read Throughput** | Sequential read ~15 to 19 MB/s | Sequential read up to 163+ MB/s |
| **Mount Form** | SAF DocumentsProvider (system Files app) | SAF DocumentsProvider + Global FUSE (`/storage/XXXX-XXXX`) |
| **Dirty Repair & Diagnostics** | Read-only structural diagnostics & clean unmount | 1-Click Dirty Bit reset & deep structural integrity diagnostics |
| **System False Alerts** | Notification listener suppresses system format prompts | Privileged suppression of false format notifications |

---

## Read/Write Benchmark & Performance

The following benchmarks were collected from 6 independent BitLocker partitions configured with different filesystems and encryption ciphers on the same physical USB 3.0 flash drive:

| Partition | Filesystem | Cipher Mode | Non-Root Seq Read | Root Seq Read | Speedup | Non-Root 4K Latency (IOPS) | Root 4K Latency (IOPS) |
|---|---|---|---|---|---|---|---|
| **NT+X** | **NTFS** | **AES-XTS-128** | 16.14 MB/s | **163.94 MB/s** | **10.16x** | 1.10 ms (909 IOPS) | **0.69 ms** (1451 IOPS) |
| **EX+X** | **exFAT** | **AES-XTS-128** | 18.90 MB/s | **101.13 MB/s** | **5.35x** | 1.25 ms (798 IOPS) | **0.74 ms** (1357 IOPS) |
| **EX+C** | **exFAT** | **AES-CBC-128** | 16.00 MB/s | **89.53 MB/s** | **5.60x** | 1.10 ms (908 IOPS) | **0.81 ms** (1241 IOPS) |
| **32+C** | **FAT32** | **AES-CBC-128** | 14.87 MB/s | **66.26 MB/s** | **4.46x** | 1.11 ms (904 IOPS) | **0.63 ms** (1581 IOPS) |
| **32+X** | **FAT32** | **AES-XTS-128** | 16.67 MB/s | **43.90 MB/s** | **2.63x** | 1.39 ms (720 IOPS) | **1.83 ms** (545 IOPS) |
| **NT+C** | **NTFS** | **AES-CBC-128** | 16.55 MB/s | **41.03 MB/s** | **2.48x** | 1.21 ms (826 IOPS) | **0.99 ms** (1006 IOPS) |

- **Throughput Analysis**: Non-Root mode is constrained by repeated user-space to kernel-space buffer copies and USB Host queue scheduling in the Android Framework. Root mode bypasses intermediate layers and accesses block devices directly via asynchronous kernel I/O, delivering 2.5x to 10x throughput improvements.
- **Stability Verified**: All combinations passed rigorous stress testing including recursive directory walks, file creation/modification, SHA-256 verification readback, and safe deletion.

---

## Key Features

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
| **Operating System** | Android 8.0 ~ 17 (API 26 ~ 36) | Broad compatibility with current and future Android releases |
| **CPU Architectures** | `arm64-v8a`, `armeabi-v7a` | Full 64-bit and 32-bit native ABI binaries |
| **Root Schemes** | **Non-Root** / **KernelSU** / **Magisk** / **APatch** | Zero setup for non-root; higher performance with Root |
| **Supported Filesystems** | **NTFS**, **exFAT**, **FAT32** | Full browse, create, modify, rename, and delete capabilities |
| **Encryption Ciphers** | AES-XTS (128/256-bit), AES-CBC (128/256-bit) | Covers Windows 10/11 defaults and Windows 7 legacy volumes |
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
