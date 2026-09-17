# BitLockerDroid 构建指南 / Build Guide

本文档介绍如何配置编译环境并从源码构建 BitLockerDroid (BitUnlocker) Android 应用程序及其底层原生 C 驱动组件。

---

## 1. 构建前置要求 / Prerequisites

编译需要安装以下核心开发工具与环境组件：

| 组件 / Component | 推荐版本 / Version | 说明 / Notes |
|---|---|---|
| **操作系统 / OS** | Linux (Ubuntu 20.04+ / WSL2) 或 macOS | 提供完整的 Android 与宿主机 C 原生构建支持 |
| **JDK** | OpenJDK 17 | Android Gradle 插件 (AGP 8.3+) 强制要求 |
| **Android SDK** | Platforms: `android-34`<br>Build-Tools: `34.0.0` | 对应工程 `compileSdk = 34` 与 `targetSdk = 34` |
| **Android NDK** | `26.3.11579264` (NDK r26d) | 与 `app/build.gradle.kts` 内配置一致 |
| **CMake** | 3.22.1+ | 可通过 Android SDK Manager 或系统包管理器获取 |
| **Gradle** | 8.4 | 由项目根目录的 `./gradlew` 自动拉取和分发 |

---

## 2. 环境搭建与配置 / Environment Setup

### 2.1 安装系统依赖包 (以 Ubuntu / Debian 为例)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk git curl unzip cmake ninja-build build-essential
```

### 2.2 配置 Android SDK 与环境变量

在 `~/.bashrc` 或当前 Shell 配置文件中导出环境变量：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

通过 `sdkmanager` 安装必要构建包与 NDK：

```bash
sdkmanager --sdk_root="$ANDROID_HOME" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools" \
    "cmake;3.22.1" \
    "ndk;26.3.11579264"
```

### 2.3 配置 `local.properties`

在项目根目录下创建 `local.properties` 文件（已加入 `.gitignore`，避免提交个人绝对路径）：

```properties
sdk.dir=/opt/android-sdk
ndk.dir=/opt/android-sdk/ndk/26.3.11579264
```

---

## 3. 源码获取与编译 / Building the Application

### 3.1 获取源码

```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
```

### 3.2 编译 Release APK (推荐正式分发使用)

```bash
chmod +x gradlew
./gradlew :app:assembleRelease
```

构建成功后，输出产物位于：
```
app/build/outputs/apk/release/app-release.apk
```

#### 签名机制说明
- **默认开发回退**：若未配置独立的 Release 签名秘钥，Gradle 构建脚本将自动回退至 Debug 签名，确保开发者克隆项目后无需繁琐配置即可直接编译并生成可安装的 APK。
- **正式发布签名配置**：
  1. 复制签名模板：
     ```bash
     cp keystore.properties.example keystore.properties
     ```
  2. 修改 `keystore.properties`：
     ```properties
     STORE_FILE=/path/to/your/release.jks
     STORE_PASSWORD=your_store_password
     KEY_ALIAS=your_key_alias
     KEY_PASSWORD=your_key_password
     ```
     *(注：`*.jks`、`*.keystore` 与 `keystore.properties` 均已加入 `.gitignore`)*

- **环境变量与 CI/CD 配置**：
  构建脚本支持直接读取环境变量，便于集成至自动化流水线（如 GitHub Actions）：

  | 环境变量 | 说明 |
  |---|---|
  | `KEYSTORE_PATH` | 密钥库文件（`.jks` / `.keystore`）路径 |
  | `KEYSTORE_PASSWORD` | 密钥库保护密码 |
  | `KEY_ALIAS` | 密钥别名 |
  | `KEY_PASSWORD` | 密钥密码 |

  在 GitHub Actions 中，可通过配置 Secret `RELEASE_KEYSTORE_BASE64`（Base64 编码的秘钥文件）与密码 Secrets 实现全自动签名发布。

### 3.3 编译 Debug APK

```bash
./gradlew :app:assembleDebug
```

输出产物位于：`app/build/outputs/apk/debug/app-debug.apk`。

包含以下架构的原生动态库（`libdislocker.so` 等）：
- `arm64-v8a`
- `armeabi-v7a`

### 3.4 清理构建缓存

```bash
./gradlew clean
```

---

## 4. 宿主机独立测试工具 / Host-Side Verification Tool

底层 BitLocker C 核心引擎支持直接在 Linux 宿主机上独立编译运行（脱离 Android 框架与真实设备），可用于快速验证卷元数据、VMK/FVEK 解密以及文件系统引导扇区解析：

### 4.1 编译宿主机工具

```bash
cd native
bash tests/build_host.sh
cd ..
```

编译完成后将在 `/tmp/verify_disk` 生成独立测试二进制。

### 4.2 执行测试验证

传入镜像路径或 loop 虚拟设备节点，并附带解锁密码：

```bash
/tmp/verify_disk /path/to/disk.img "<BitLocker-Password>"
```

该工具将输出：
- 探测到的扇区尺寸与加密算法模式（如 `0x8002` AES-CBC、`0x8004` AES-XTS）；
- FVEK 密钥解析状态与长度；
- 解密后的文件系统引导扇区（NTFS / FAT32 / exFAT）十六进制数据预览。

---

## 5. 国内镜像与网络加速配置 / Network & Mirrors

若在特定网络环境下访问 Google Maven 或 Maven Central 较慢，可做如下优化：

1. **仓库镜像**：在 `settings.gradle.kts` 中添加镜像源（如阿里云镜像）：
   ```kotlin
   maven { url = uri("https://maven.aliyun.com/repository/google") }
   maven { url = uri("https://maven.aliyun.com/repository/public") }
   ```
2. **Gradle 代理配置**：在 `gradle.properties` 中设置代理：
   ```properties
   org.gradle.jvmargs=-Xmx2048m -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897
   ```
