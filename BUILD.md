# BitLockerDroid Build Guide

<p align="center">
  <b>English</b> | <a href="BUILD_zh.md">简体中文</a>
</p>

This guide explains how to set up the development environment and build the BitLockerDroid (BitUnlocker) Android application and its native C driver components from source.

---

## 1. Prerequisites

The following development tools and SDK packages are required:

| Component | Recommended Version | Notes |
|---|---|---|
| **Operating System** | Linux (Ubuntu 20.04+ / WSL2) or macOS | Full support for Android and host-side native C builds |
| **JDK** | OpenJDK 17 | Required by Android Gradle Plugin (AGP 8.3+) |
| **Android SDK** | Platforms: `android-34`<br>Build-Tools: `34.0.0` | Matches `compileSdk = 34` and `targetSdk = 34` |
| **Android NDK** | `26.3.11579264` (NDK r26d) | Configured in `app/build.gradle.kts` |
| **CMake** | 3.22.1+ | Available via Android SDK Manager or system package manager |
| **Gradle** | 8.4 | Automatically provisioned via `./gradlew` |

---

## 2. Environment Setup

### 2.1 Install System Dependencies (Ubuntu / Debian Example)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk git curl unzip cmake ninja-build build-essential
```

### 2.2 Configure Android SDK & Environment Variables

Export the required environment variables in `~/.bashrc` or your shell configuration:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install SDK packages and NDK using `sdkmanager`:

```bash
sdkmanager --sdk_root="$ANDROID_HOME" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools" \
    "cmake;3.22.1" \
    "ndk;26.3.11579264"
```

### 2.3 Configure `local.properties`

Create a `local.properties` file in the project root directory (ignored by `.gitignore`):

```properties
sdk.dir=/opt/android-sdk
ndk.dir=/opt/android-sdk/ndk/26.3.11579264
```

---

## 3. Building the Application

### 3.1 Clone the Repository

```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
```

### 3.2 Build Release APK

```bash
chmod +x gradlew
./gradlew :app:assembleRelease
```

The output artifact is generated at:
```
app/build/outputs/apk/release/app-release.apk
```

#### Signing Configuration
- **Debug Fallback**: If no custom release keystore is provided, the Gradle build script automatically falls back to Debug signing. Developers can clone and build ready-to-test APKs immediately without extra setup.
- **Custom Release Keystore Configuration**:
  1. Copy the keystore template:
     ```bash
     cp keystore.properties.example keystore.properties
     ```
  2. Edit `keystore.properties`:
     ```properties
     STORE_FILE=/path/to/your/release.jks
     STORE_PASSWORD=your_store_password
     KEY_ALIAS=your_key_alias
     KEY_PASSWORD=your_key_password
     ```
     *(Note: `*.jks`, `*.keystore`, and `keystore.properties` are ignored by `.gitignore`)*

- **CI/CD Environment Variables**:
  The build script also reads configuration directly from environment variables for automated workflows (such as GitHub Actions):

  | Variable | Description |
  |---|---|
  | `KEYSTORE_PATH` | Path to keystore file (`.jks` / `.keystore`) |
  | `KEYSTORE_PASSWORD` | Keystore password |
  | `KEY_ALIAS` | Key alias |
  | `KEY_PASSWORD` | Key password |

  In GitHub Actions, configure `RELEASE_KEYSTORE_BASE64` along with secret variables to enable fully automated signed releases.

### 3.3 Build Debug APK

```bash
./gradlew :app:assembleDebug
```

Output path: `app/build/outputs/apk/debug/app-debug.apk`.

Native libraries (`libdislocker.so`, etc.) are compiled for:
- `arm64-v8a`
- `armeabi-v7a`

### 3.4 Clean Build Artifacts

```bash
./gradlew clean
```

---

## 4. Host-Side Verification Tool

The underlying BitLocker C engine can be compiled and executed directly on a Linux host (independent of Android framework and physical devices) to verify volume metadata, VMK/FVEK decryption, and boot sector parsing:

### 4.1 Build the Host Tool

```bash
cd native
bash tests/build_host.sh
cd ..
```

This compiles the standalone test binary to `/tmp/verify_disk`.

### 4.2 Run Verification

Pass a disk image path or loop device along with the volume unlock password:

```bash
/tmp/verify_disk /path/to/disk.img "<BitLocker-Password>"
```

The tool prints:
- Detected sector size and encryption cipher mode (e.g., `0x8002` AES-CBC, `0x8004` AES-XTS);
- FVEK resolution status and key length;
- Hex preview of the decrypted filesystem boot sector (NTFS / FAT32 / exFAT).

---

## 5. Network & Mirrors (Optional)

If accessing Google Maven or Maven Central is slow in specific network environments:

1. **Repository Mirrors**: Add mirror repositories in `settings.gradle.kts` (e.g., Aliyun mirror):
   ```kotlin
   maven { url = uri("https://maven.aliyun.com/repository/google") }
   maven { url = uri("https://maven.aliyun.com/repository/public") }
   ```
2. **Gradle Proxy Configuration**: Set proxy parameters in `gradle.properties`:
   ```properties
   org.gradle.jvmargs=-Xmx2048m -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897
   ```
