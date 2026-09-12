# BitUnlocker Build Guide

This document describes how to set up the build environment and compile the BitUnlocker Android application and its native components.

---

## 1. Prerequisites

To build BitUnlocker, you need the following tools installed:

| Component | Minimum / Recommended Version | Note |
|---|---|---|
| **OS** | Linux (Ubuntu 20.04+ / WSL2) or macOS | Tested on Ubuntu 24.04 (WSL2) |
| **JDK** | OpenJDK 17 | Required by Android Gradle Plugin 8.3+ |
| **Android SDK** | Platforms: `android-34`<br>Build-Tools: `34.0.0` | `compileSdk = 34`, `targetSdk = 34` |
| **Android NDK** | `26.3.11579264` (NDK r26d) | Matched in `app/build.gradle.kts` |
| **CMake** | 3.22.1+ | Installed via Android SDK Manager |
| **Gradle** | 8.4 | Managed by `./gradlew` wrapper |

---

## 2. Environment Setup

### 2.1 Install System Dependencies (Ubuntu / Debian example)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk git curl unzip cmake ninja-build build-essential
```

### 2.2 Configure Android SDK & NDK

Export the necessary environment variables in your `~/.bashrc` or shell profile:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

If installing SDK packages via `sdkmanager`:

```bash
sdkmanager --sdk_root="$ANDROID_HOME" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools" \
    "cmake;3.22.1" \
    "ndk;26.3.11579264"
```

### 2.3 Configure `local.properties`

Create a `local.properties` file in the project root directory:

```properties
sdk.dir=/opt/android-sdk
ndk.dir=/opt/android-sdk/ndk/26.3.11579264
```

*(Note: `local.properties` is git-ignored and should point to your local SDK/NDK path).*

---

## 3. Building the Application

### 3.1 Build Debug APK

Run the Gradle wrapper from the root of the project:

```bash
./gradlew :app:assembleDebug
```

Upon success, the APK is generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

The APK contains native shared libraries (`libdislocker.so`) compiled for:
- `arm64-v8a` (16 KB page-aligned for Android 15+ compatibility)
- `armeabi-v7a`

### 3.2 Clean Build Cache

```bash
./gradlew clean
```

---

## 4. Host-Side Verification Tool (Standalone C Testing)

The native BitLocker decoding core can be compiled and executed directly on the host machine (Linux) without an Android device. This allows rapid verification of volume headers, metadata, VMK/FVEK decryption, and file system boot sector parsing.

### 4.1 Compile Host Tool

```bash
cd native
bash tests/build_host.sh
```

This compiles the native dislocker core with mbedtls and generates the standalone testing binary at `/tmp/verify_disk`.

### 4.2 Run Host Verification

Feed a raw disk image (or loop device) and the unlock password:

```bash
/tmp/verify_disk /path/to/disk.img "<Your-BitLocker-Password>"
```

The tool will output:
- Detected sector size, encryption algorithm (e.g. `0x8002` for AES-CBC, `0x8004` for AES-XTS)
- Decrypted FVEK status and length
- Decrypted filesystem boot sector preview (NTFS / FAT32 / exFAT)

---

## 5. Network & Mirror Configuration (Optional)

If accessing Google Maven or Maven Central is restricted or slow in your region:

1. **Repository Mirrors**: `settings.gradle.kts` can be configured with mirrors (e.g., Aliyun):
   ```kotlin
   maven { url = uri("https://maven.aliyun.com/repository/google") }
   maven { url = uri("https://maven.aliyun.com/repository/public") }
   ```
2. **Gradle Proxy**: In `gradle.properties`, define JVM proxy settings:
   ```properties
   org.gradle.jvmargs=-Xmx2048m -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897
   ```
