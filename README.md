# BitLockerDroid (BitUnlocker)

<p align="center">
  <b>A native Android application to unlock, browse, read, and write Microsoft BitLocker encrypted drives (NTFS, FAT32, exFAT) on mobile devices.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## 简介 / Introduction

**BitLockerDroid**（手机端显示为 **BitUnlocker**）是一款 Android 原生存储管理应用，让手机/平板等移动设备能够直接访问 Windows **BitLocker 加密盘**（通过 USB OTG 连接的 U 盘、移动固态硬盘或 SD 卡）。

将加密设备连接至手机后：
1. 识别设备并检测 BitLocker 分区；
2. 输入**用户密码**或 **48 位数字恢复密钥**解锁；
3. 解锁后直接在 Android 系统原生文件管理器（DocumentsUI）中进行**文件浏览、新建、重命名、读写编辑与安全删除**；
4. 若设备具备 Root 权限，同时支持启动**全局 POSIX 虚拟挂载**（FUSE `/storage/XXXX-XXXX`），让第三方应用（如 MT 管理器、Termux、影音播放器等）通过绝对路径直接读写访问。

> **运行模式**：
> - **免 Root 模式（Non-Root USB Host）**：支持外接 USB OTG 设备，通过 Android USB Host API 及用户态 SCSI 驱动栈完成通信与解密，利用 DocumentsProvider 提供原生文件管理器的完整读写（NTFS、exFAT、FAT32）；
> - **Root 模式（KernelSU / Magisk / APatch）**：额外支持全局 POSIX 虚拟挂载（`/storage/XXXX-XXXX`）以及全部块设备访问。

---

## 核心特性 / Features

### 1. 主流文件系统读写 (Full Read/Write CRUD)
- **NTFS**：集成裁剪版 **`libntfs-3g`**，支持文件与目录的新建、读取、修改写入、重命名与删除；底层设置扇区级写屏障，禁止覆盖 `-FVE-FS-` 元数据保留区。
- **FAT32**：集成裁剪版 **`FatFs`**，支持长文件名与完整读写。
- **exFAT**：支持读取与写入，内置 FAT 簇链与 Entry Set 动态维护。
- **快速文件检索**：接入 DocumentsProvider 原生搜索接口，支持在系统文件管理器中按关键字检索卷内文件。

### 2. 数据安全与卸载保护
- **安全弹出 (Safe Eject)**：
  - 卸载前强制双向 `sync` 数据落盘并清理 Dirty Bit 状态，降低因直接拔出 OTG 导致文件损坏或在 Windows 上报错的概率。
- **只读保护开关 (Read-Only Mode)**：
  - 卷卡片提供一键只读模式开关，开启后驱动层直接拦截所有写操作，适合仅需查阅文件的场景。
- **Dirty 状态检测**：
  - 自动识别上次在电脑或其他设备上未正常弹出的脏卷（Dirty Bit 置位）并在卡片中警示。

### 3. 全局 POSIX 虚拟挂载 (`/storage/XXXX-XXXX`)
- 内置用户态 **FUSE 守护进程**（运行于 PID 1 挂载命名空间），将解密卷模拟为系统的外置存储盘。
- 挂载路径采用规范的十六进制格式（如 `/storage/78F0-B809`）。
- MT 管理器、Termux、本地播放器等第三方应用可直接通过绝对路径进行文件读写。

### 4. 驱动器基准测速 (Drive Benchmark)
- 内置只读硬件测速逻辑，不破坏磁盘数据：
  - **连续读取速率**：测试大文件顺序读取带宽（MB/s）；
  - **4K 随机读取延时**：测试闪存小块寻道响应（ms）；
  - **OTG 物理链路检测**：识别当前接口工作在 USB 2.0、USB 3.0 (5 Gbps) 还是 USB 3.1+ (10 Gbps)。

### 5. 凭据管理与自动解锁
- **解锁方式**：支持用户密码（PBKDF2/SHA-256）与 48 位数字恢复密钥。在识别到恢复密钥时，自动显示其对应的**恢复标识符（Recovery Key ID）前 8 位**以方便核对。
- **凭据存储**：记住的密码由 Android Keystore 硬件加密存储；在凭据管理界面查看明文需通过设备锁屏/生物识别验证。
- **插盘自动解锁**：对已记住凭据的设备，检测到插入时可自动执行解密挂载。
- **拦截误报**：自动抑制系统对未解密 BitLocker 分区弹出的“USB 设备损坏，提示格式化”通知。

---

## 工作原理 / Architecture

```
                       外接 USB OTG 加密设备
                               │
                               ▼
                      /dev/block/vold/*
                               │
┌──────────────────────────────┼──────────────────────────────┐
│ 应用与系统服务层             │ 底层驱动与解密引擎           │
│                              ▼                              │
│   [设备检测 / Scan] ──────► [BitLockerDetector]             │
│                              │ 探测 -FVE-FS- 签名           │
│                              ▼                              │
│   [解锁弹窗 / Keystore] ──► [DislockerCore (JNI)]           │
│   (密码 / 恢复密钥)          │ mbedtls 密钥派生与解析        │
│                              │ 解密 VMK -> FVEK 会话         │
│                              ▼                              │
│                     [按需扇区解密引擎]                      │
│                      (AES-XTS / AES-CBC)                    │
│                              ▲                              │
│                              │ Root 权限读写块设备          │
│                              │ (KernelSU / Magisk / APatch) │
│                              ▼                              │
│                 [统一 Volume 读写与写屏障]                  │
│             ┌────────────────┼────────────────┐             │
│             ▼                ▼                ▼             │
│        libntfs-3g          FatFs          ExFatDriver       │
│             └────────────────┼────────────────┘             │
│                              │                              │
│              ┌───────────────┴───────────────┐              │
│              ▼                               ▼              │
│   [BitLockerDocumentsProvider]    [bitlocker_fuse_daemon]   │
│   (Android SAF 存储访问框架)       (PID 1 全局 FUSE 命名空间) │
└──────────────┬───────────────────────────────┬──────────────┘
               ▼                               ▼
    系统原生文件管理器 (DocumentsUI)    全局 POSIX 绝对路径 (/storage/XXXX)
    (直接在系统抽屉浏览/增删改查)       (MT管理器/Termux/播放器透明读写)
```

---

## 兼容性与技术规格 / Specifications

| 维度 | 支持范围 | 说明 |
|---|---|---|
| **支持环境** | Android 8+ | 目前在搭载 KernelSU/Magisk 的 HyperOS（A15+） 设备上测试通过，其他系统及版本尚未做完整回归 |
| **Root 方案** | KernelSU, Magisk, APatch | 用于授权应用读取底层 `/dev/block/vold/*` 块设备节点 |
| **文件系统** | **NTFS**, **FAT32**, **exFAT** | 支持读写增删改查，并可切换只读模式 |
| **加密算法** | AES-XTS (128/256 位), AES-CBC (128/256 位) | 覆盖主流 Windows 默认及兼容模式 |
| **认证凭据** | 用户密码、48 位恢复密钥 | 恢复密钥形如 `xxxxxx-xxxxxx-...-xxxxxx` |
| **物理接口** | USB 2.0 / USB 3.0 / USB 3.1+ | 支持 Type-C OTG 转接头及拓展坞 |

---

## 快速上手 / Quick Start

### 1. 安装与 Root 授权
1. 从 Releases 下载最新的 `app-release.apk`（或自行编译安装）。
2. 在 **KernelSU** / **Magisk** / **APatch** 中为 **BitUnlocker** 授予 Root (su) 权限。

### 2. 解锁与使用
1. 将 BitLocker 加密盘通过 OTG 接入手机。
2. 打开应用（或点击系统检测通知），点击「解锁」。
3. 输入用户密码或 48 位恢复密钥。
4. 解锁完成后：
   - 点击 **「打开」** 即可调起系统原生文件管理器进行浏览与管理；
   - 点击 **「虚拟挂载到 /storage」**，第三方文件管理应用可直接在 `/storage/XXXX-XXXX` 访问；
   - 如仅需浏览且防止文件改动，可在卡片上开启 **「只读保护模式」**。
5. 使用完毕后，点击卡片上的 **「安全弹出」**，待数据同步完成后拔出设备。

---

## 源码编译 / Build from Source

项目使用标准 Gradle + Android NDK (C/CMake) 构建。

### 环境要求
- **JDK 17**
- **Android SDK**（`compileSdk = 34`, `targetSdk = 34`）
- **Android NDK**（`26.3.11579264` / `r26d`）
- **CMake**（`3.22.1+`）

### 编译 Release APK (推荐)
```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid

chmod +x gradlew
./gradlew :app:assembleRelease
```
产物位置：`app/build/outputs/apk/release/app-release.apk`。

### 编译 Debug APK
```bash
./gradlew :app:assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 常见说明 / Notes & FAQ

- **是否必须需要 Root 权限？**
  - **不需要**。对于通过 USB OTG 连接的外接 U 盘或移动硬盘，应用可在免 Root 状态下通过 Android 原生 USB Host 权限直接与设备进行底层 SCSI 通信，配合 SAF DocumentsProvider 实现原生文件管理器的文件读写（支持 NTFS、exFAT、FAT32）。
  - 若需要**全局 POSIX 虚拟挂载**（即在 `/storage/XXXX-XXXX` 生成系统绝对路径供第三方 App 访问）或访问手机板载内部存储/SD 卡分区，则需要设备具备 Root 权限。
- **为什么拔盘前建议点击「安全弹出」？**
  - 写入操作通常具有缓存机制，安全弹出能确保所有修改已全部写回磁盘闪存，并清除卷的 Dirty Bit 标记，避免拔盘后在电脑上提示“扫描并修复”。

---

## 开源协议与致谢 / License & Acknowledgments

- **核心解密与算法移植**：参考自开源项目 [dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)。
- **加密原语组件**：内置 [mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)。
- **NTFS 驱动核心**：内置裁剪版 [libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)。
- **FAT32 驱动核心**：内置 [FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)。
- **项目主体**：依据 **GNU General Public License v2.0 (GPL-2.0)** 开源。详见 [LICENSE](LICENSE)。
