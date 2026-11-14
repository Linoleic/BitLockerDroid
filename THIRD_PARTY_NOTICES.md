# Third-Party Notices & Open-Source Licenses

BitLockerDroid is licensed under the **GNU General Public License v2.0** (see [`LICENSE`](LICENSE)).  
This project incorporates or depends on the following third-party open-source software:

---

## 1. Native C/C++ Components (`native/`)

### dislocker
- **Location:** `native/dislocker/`
- **Upstream:** <https://github.com/Aorimn/dislocker>
- **License:** GNU General Public License v2.0 (GPL-2.0)
- **Purpose:** Core BitLocker FVE metadata parsing and AES-XTS / AES-CBC volume decryption (with Android/JNI adaptations in this repository).

### Mbed TLS (`2.28.8`)
- **Location:** `native/third_party/mbedtls/` (full license text in [`native/third_party/mbedtls/LICENSE`](native/third_party/mbedtls/LICENSE))
- **Upstream:** <https://github.com/Mbed-TLS/mbedtls>
- **License:** Apache-2.0 OR GPL-2.0-or-later
- **Purpose:** Cryptographic primitives (AES, SHA-256, and platform/error utilities).

### NTFS-3G / `libntfs-3g` (`2022.10.3` + upstream security patches through `2026.9.28`)
- **Location:** `native/third_party/libntfs-3g/`
- **Upstream:** <https://github.com/tuxera/ntfs-3g>
- **License:** GNU General Public License v2.0 or later (GPL-2.0-or-later)
- **Purpose:** NTFS filesystem read/write support over decrypted BitLocker volumes.

### FatFs (`R0.15` + `R0.16 patch-2` security fixes)
- **Location:** `native/third_party/fatfs/` (license header in [`native/third_party/fatfs/ff.h`](native/third_party/fatfs/ff.h))
- **Upstream:** <https://elm-chan.org/fsw/ff/>
- **Copyright:** Copyright (C) 2022, ChaN, all right reserved.
- **License:** FatFs License (BSD-style permissive license)
- **Purpose:** FAT32 and exFAT filesystem read/write support.

---

## 2. Android / Kotlin / JVM Dependencies (`app/build.gradle.kts`)

### AndroidX & Jetpack Libraries
- **Artifacts:** `androidx.compose.*` (BOM `2024.10.01`), `androidx.activity:activity-compose`, `androidx.lifecycle:*`, `androidx.core:core-ktx`, `androidx.appcompat:appcompat`, `androidx.preference:preference-ktx`, `androidx.recyclerview:recyclerview`, `androidx.biometric:biometric`
- **License:** Apache License 2.0

### Material Components for Android (`1.12.0`)
- **Artifact:** `com.google.android.material:material:1.12.0`
- **License:** Apache License 2.0

### libaums (`0.10.0`)
- **Artifact:** `me.jahnen.libaums:core:0.10.0`
- **Upstream:** <https://github.com/magnusja/libaums>
- **License:** Apache License 2.0
- **Purpose:** Non-root Android USB Mass Storage (SCSI/BOT) access.

### ZXing Core (`3.5.3`)
- **Artifact:** `com.google.zxing:core:3.5.3`
- **Upstream:** <https://github.com/zxing/zxing>
- **License:** Apache License 2.0
- **Purpose:** QR code generation for LAN wireless sharing.

---

## 3. Test-Only Dependencies (Not Shipped in Release APK)

- **JUnit 4 (`4.13.2`)** (`junit:junit`): Eclipse Public License 1.0 (EPL-1.0)
- **JSON-java (`20240303`)** (`org.json:json`): The JSON License
