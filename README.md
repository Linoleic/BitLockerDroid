# BitLockerDroid (BitUnlocker)

<p align="center">
  <b>English</b> | <a href="README_zh.md">简体中文</a>
</p>

<p align="center">
  <b>Android application for unlocking, browsing, mounting, reading, and writing Microsoft BitLocker encrypted drives (NTFS, exFAT, FAT32).</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-13.0%2B_API_33%2B-blue.svg" alt="Android 13.0+ (minSdk 33)">
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## Table of Contents

- [Introduction](#introduction)
- [Dual Operation Modes](#dual-operation-modes)
- [Key Features](#key-features)
- [Performance Summary](#performance-summary)
- [Technical Specifications & Compatibility](#technical-specifications--compatibility)
- [Quick Start](#quick-start)
- [Build from Source](#build-from-source)
- [Documentation](#documentation)
- [FAQ](#faq)
- [License & Acknowledgments](#license--acknowledgments)

---

## Introduction

**BitLockerDroid** (display name **BitUnlocker**) is an Android application for accessing and managing Microsoft BitLocker encrypted storage devices on mobile platforms. It supports unlocking, reading, and writing external media including USB OTG flash drives, portable SSDs/HDDs, and SD cards.

- **Filesystem Support**: Browse, create, modify, rename, and delete files on NTFS, exFAT, and FAT32 partitions.
- **Authentication Credentials**: Supports user passwords (PBKDF2/SHA-256) and 48-digit numerical recovery keys.
- **System Integration**: Exposes volumes through Android's Storage Access Framework (SAF `DocumentsProvider`) for direct management in the system "Files" app.
- **Global POSIX Mount (Root)**: Mounts decrypted volumes to `/storage/XXXX-XXXX` via a FUSE daemon so third-party apps can access files through standard Linux paths.
- **Dual Operation Modes**: Operates in either Non-Root mode (Android USB Host API) or Root mode (Linux kernel block device nodes).

---

## Dual Operation Modes

The application provides two underlying driver paths that can be switched in Settings (active sessions are flushed and unmounted before switching):

| Dimension | Non-Root Mode (USB Host API) | Root Mode (KernelSU / Magisk / APatch) |
|---|---|---|
| **Privilege Requirement** | USB device access permission only; no Root required | Root permission (`su`) |
| **Device Support** | USB OTG external storage devices | USB OTG devices, multi-partition disks, kernel block nodes |
| **I/O Transport** | Android `usbfs` (`USBDEVFS_BULK` 16 KB chunks) + user-space BOT state machine | Linux kernel block nodes (`/dev/block/vold/*`) + persistent `bitlocker_io` pipe daemon |
| **Mount Form** | SAF `DocumentsProvider` (system Files app) | SAF `DocumentsProvider` + Global FUSE mount (`/storage/XXXX-XXXX`) |
| **Dirty Bit & Diagnostics** | Read-only metadata health check & safe eject | Dirty Bit reset & metadata health inspection |
| **System Format Prompts** | Notification listener filters system format alerts | Privileged suppression of false format notifications |

---

## Key Features

- **Read/Write Separated Hybrid Filesystem Engine**:
  - **Read-Only Path**: Uses lightweight Kotlin parsers (`NtfsReader`, `ExFatReader`, `Fat32Reader`) backed by a 64 KB sector-aligned LRU block cache (`CachedBlockSource`, up to 4 MB, zero-filled on eviction) for fast directory browsing;
  - **Lazy Write Path**: Mounts native C filesystem drivers on demand when mutations occur (`libntfs-3g` for NTFS, and ChaN's `FatFs` with `FF_FS_EXFAT` enabled for FAT32 and exFAT).
- **ARMv8-CE Hardware Cryptography**: Utilizes ARMv8-A Cryptography Extensions (`AES`, `PMULL`, `SHA2`) with a 4-way superscalar interleaved loop on `arm64-v8a` devices to accelerate PBKDF2 key stretching and AES-XTS / AES-CBC sector encryption/decryption; includes a mandatory startup round-trip self-test with automatic software fallback.
- **Credential Encryption & Biometric Guard**: Optionally stores credentials encrypted via Android Keystore AES-GCM keys, with optional biometric authentication and window screenshot protection (`FLAG_SECURE`).
- **Sector Write Barrier & Disaster Recovery**:
  - Enforces a low-level write barrier that blocks any write targeting the BitLocker volume header or the three 64 KB FVE metadata blocks (`-EPERM`);
  - Supports extracting and restoring FVE metadata headers (`.fvemeta`) as well as exporting decrypted (`.img`) or raw encrypted (`.raw`) volume images.
- **Multi-Partition Topology & Safe Eject**: Distinguishes parent disk nodes from child partitions; supports closing individual volumes while staying connected or performing full cache sync and SCSI spin-down on device ejection.
- **LAN Wireless Sharing (Web & WebDAV)**: Built-in HTTP web portal and WebDAV server for mounting unlocked volumes over the local network.

---

## Performance Summary

For the complete layered architecture diagram, I/O transport internals, security hardening details, and the full 6-partition benchmark matrices, see the **[Architecture & Performance Overview (ARCHITECTURE.md)](ARCHITECTURE.md)** and the **[Performance Evaluation Specification (perf_eval.md)](perf_eval.md)**.

Representative benchmarks measured on Qualcomm Snapdragon 8 Gen 2 (Android 16, Linux 5.15, USB 3.0):

| Benchmark Scenario | Mode & Configuration | Measured Result |
| :--- | :--- | :--- |
| **PBKDF2 Key Derivation** (1,048,576 rounds SHA-256) | ARMv8-CE Hardware vs Pure C Software | **197.7 ~ 207.5 ms** vs 801.7 ~ 815.6 ms (**3.86x ~ 4.13x**) |
| **Tier 1 Portable SSD** (HIKSEMI P202 1TB / UAS) | **Root Mode** · NTFS / exFAT (AES-XTS) | Seq Read **77.0 ~ 78.8 MB/s** (Peak 100.4 MB/s) · Seq Write **50.3 ~ 53.2 MB/s** |
| **Tier 2 USB Flash Drive** (Lexar 32GB / BOT) | **Root Mode** · NTFS / exFAT (AES-XTS) | Seq Read **52.2 ~ 66.5 MB/s** (Peak 79.5 MB/s) · Seq Write **16.0 ~ 16.9 MB/s** |
| **Tier 2 USB Flash Drive** (USB 3.0 / BOT) | **Non-Root Mode** · USB Host + SAF Double-Buffer | Seq Read **43.25 MB/s** · Seq Write **12.97 MB/s** (50MB E2E Read **13.76 MB/s** / Write **8.14 MB/s**) |
| **4K Random Latency** (Tier 1 PSSD / Tier 2 Flash) | **Root Mode** vs **Non-Root Mode** | Root: Read **1.32 ~ 1.40 ms** (~740 IOPS) / Write **7.13 ms** · Non-Root: Read **1.33 ms** (752 IOPS) / Write **9.25 ms** (108 IOPS) |

---

## Technical Specifications & Compatibility

| Dimension | Supported Range / Specification | Notes |
|---|---|---|
| **Operating System** | Android 13.0+ (`minSdk 33`) | Built with `compileSdk 34` / `targetSdk 34`; compatible with Android 13 through Android 15/16+ |
| **CPU Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` | 64-bit and 32-bit native ABIs (ARMv8-CE hardware crypto applies to `arm64-v8a`) |
| **Root Schemes** | **Non-Root** / **KernelSU** / **Magisk** / **APatch** | Non-Root works out of the box; Root enables kernel block I/O and global FUSE mounting |
| **Supported Filesystems** | **NTFS**, **exFAT**, **FAT32** | Browse, create, modify, rename, delete, and >4 GB large file support |
| **Encryption Ciphers** | AES-XTS (128/256-bit), AES-CBC (128/256-bit, non-diffuser) | Covers Windows 10/11 default XTS and Windows 7/8 CBC formats (Vista-era Diffuser volumes are rejected) |
| **Page Size Alignment** | 16 KB and 4 KB page sizes | All native `.so` targets link with `-Wl,-z,max-page-size=16384` for Android 15+ 16 KB page compatibility |
| **Authentication Types** | User Password, 48-digit Recovery Key | Displays the Recovery Key ID in the unlock dialog for verification |

---

## Quick Start

1. **Install**: Download and install `app-release.apk` from [GitHub Releases](https://github.com/Linoleic/BitLockerDroid/releases).
2. **Connect & Authorize**: Plug in your USB OTG drive. In Non-Root mode, approve the system USB permission prompt; in Root mode, grant superuser access in KernelSU / Magisk / APatch.
3. **Unlock**: Tap **Unlock** on the volume card and enter your password or 48-digit recovery key (verify the displayed Recovery Key ID; optionally enable "Remember credential").
4. **Access & Safe Eject**:
   - Tap **Open** to browse and manage files in the system "Files" app.
   - In Root mode, tap **Virtual mount to real directory** to expose `/storage/XXXX-XXXX` to third-party apps.
   - **Close volume** and **Close all volumes** flush and close active encrypted sessions while keeping the USB device connected.
   - Before unplugging, tap **Eject device** in the device header and wait for completion.

---

## Build from Source

Quick local build:
```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
./gradlew :app:assembleRelease
```
Output artifact: `app/build/outputs/apk/release/app-release.apk`.

For environment setup, NDK CMake build options, and Linux host verification tools, see the **[Build Guide (BUILD.md)](BUILD.md)** (or **[中文构建指南 (BUILD_zh.md)](BUILD_zh.md)**).

---

## Documentation

| Document | Description |
| :--- | :--- |
| **[Architecture & Performance Overview (ARCHITECTURE.md)](ARCHITECTURE.md)** | Layered architecture, dual-mode I/O transport, ARMv8-CE 4-way interleaved acceleration, hybrid filesystem design, security boundaries, and full benchmark tables ([简体中文](ARCHITECTURE_zh.md)) |
| **[Build Guide (BUILD.md)](BUILD.md)** | JDK / SDK / NDK prerequisites, release signing fallback, 16 KB page alignment requirements, and Linux host verification tools ([简体中文](BUILD_zh.md)) |
| **[Performance Evaluation Specification (perf_eval.md)](perf_eval.md)** | Storage tier classification, warmup discard, `drop_caches` isolation, closed-loop `sync` timing, and 6-partition test matrix definition |
| **[ADB Debug & Automation Interface (ADB_DEBUG_INTERFACE.md)](ADB_DEBUG_INTERFACE.md)** | `DocumentsProvider` CLI methods for headless unlock, runtime hardware crypto toggling, benchmarking, and automated VFS verification |
| **[Changelog (CHANGELOG.md)](CHANGELOG.md)** | Release history, architectural changes, and bug fixes across versions |
| **[Contributing Guide (CONTRIBUTING.md)](CONTRIBUTING.md)** | Issue reporting checklist, private security disclosure policy, and coding conventions |

---

## FAQ

- **Q: How do "Close volume" and "Eject device" differ?**  
  **A**: **Close volume** and **Close all volumes** wait for active writes to finish, flush the filesystem, and close the encrypted session while keeping the physical device connected. Before unplugging, use **Eject device**, which closes all volumes on that device, checks for active users, and issues SCSI cache sync and spin-down commands.
- **Q: What is the Recovery Key ID used for?**  
  **A**: A drive may have multiple historical recovery keys. The unlock dialog displays the volume's Recovery Key ID so you can match it against your Microsoft account ([account.microsoft.com/devices/recoverykey](https://account.microsoft.com/devices/recoverykey)).
- **Q: Why do some apps not see `/storage/XXXX-XXXX` after virtual mounting?**  
  **A**: Global mount injects the FUSE mount into the PID 1 mount namespace, which is accessible to any app that reads standard file paths (MT Manager, Termux, VLC, etc.). Gallery apps that rely exclusively on Android's `MediaStore` index do not scan custom external mount paths.
- **Q: Why is Non-Root transfer speed lower than Root mode?**  
  **A**: Non-Root mode is constrained by Android's sandbox to synchronous 16 KB `USBDEVFS_BULK` transfers over user-space `usbfs`, sequential half-duplex BOT handshakes (`CBW -> DATA -> CSW`), SAF stream bridging, and the absence of kernel Page Cache write coalescing. While the 64 KB LRU block cache and 512 KB SAF double-buffered write queue reduce this overhead, Root mode is recommended for saturating high-speed external SSDs. See [Architecture & Performance Overview §6.4](ARCHITECTURE.md#64-root-vs-non-root-architectural-comparison--bottleneck-breakdown) for details.
- **Q: How should I report a device or filesystem issue?**  
  **A**: Open a [GitHub Issue](https://github.com/Linoleic/BitLockerDroid/issues) and attach the diagnostic log from **Settings → Advanced Options → System & Diagnostics → Diagnostic Log** (**Copy Log**). The log never records passwords or recovery keys, though it includes volume GUIDs, device paths, and file names—please review before posting.

---

## License & Acknowledgments

This project is licensed under the **GNU General Public License v2.0 (GPL-2.0)**. See [LICENSE](LICENSE) for details, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party library notices.

### Core Upstream Projects & Dependencies
- **BitLocker Parsing Core**: [dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)
- **Cryptographic Primitives**: [mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)
- **NTFS Filesystem Engine**: [libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)
- **FAT32 / exFAT Write Engine**: [FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)
- **USB Mass Storage Framework**: [libaums](https://github.com/magnusja/libaums) (Apache-2.0)
