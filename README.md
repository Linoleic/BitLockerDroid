# BitLockerDroid (BitUnlocker)

<p align="center">
  <b>Android 原生 Microsoft BitLocker 加密盘管理工具（支持 NTFS、exFAT、FAT32）</b><br>
  <b>Native Android application to unlock, browse, mount, read, and write Microsoft BitLocker encrypted drives.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-8.0+--15%20(16KB%20Ready)-blue.svg" alt="Android 8.0+ 16KB Ready">
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## 目录 / Table of Contents

- [项目简介](#项目简介--introduction)
- [双运行模式与架构对比](#双运行模式与架构对比--dual-mode-architecture)
- [读写性能对比与基准数据](#读写性能对比与基准数据--performance)
- [核心特性](#核心特性--features)
- [系统架构](#系统架构--architecture)
- [技术规格与兼容性矩阵](#技术规格与兼容性矩阵--specifications)
- [快速上手指南](#快速上手指南--quick-start)
- [项目目录结构](#项目目录结构--project-structure)
- [源码编译与构建](#源码编译与构建--build-from-source)
- [常见问题与技术说明](#常见问题与技术说明--faq--notes)
- [开源协议与致谢](#开源协议与致谢--license--acknowledgments)

---

## 项目简介 / Introduction

**BitLockerDroid**（应用显示名 **BitUnlocker**）是一个 Android 平台上的 BitLocker 加密卷访问工具，支持在移动设备上解锁并读写 Windows BitLocker 加密的外接存储设备（USB OTG U 盘、移动硬盘及 SD 卡）。

主要功能特性：
- **文件系统支持**：支持 NTFS、exFAT 与 FAT32 分区的文件浏览、新建、修改、重命名与删除。
- **认证凭据**：支持用户密码（PBKDF2/SHA-256）与 48 位数字恢复密钥。
- **系统集成**：接入 Android 存储访问框架（SAF DocumentsProvider），可在系统“文件”管理器中直接进行文件管理。
- **全局虚拟挂载（Root）**：支持通过 FUSE 将解密卷挂载至 `/storage/XXXX-XXXX`，供第三方应用通过绝对路径直接访问。
- **双运行模式**：支持免 Root（基于 Android USB Host API）与 Root（直通内核块设备）两种运行架构。

---

## 双运行模式与架构对比 / Dual-Mode Architecture

应用提供免 Root 与 Root 两种底层驱动架构，可在高级设置中按需切换：

| 维度 | 免 Root 模式 (Non-Root USB Host) | Root 模式 (KernelSU / Magisk / APatch) |
|---|---|---|
| **权限要求** | 仅需系统 USB 设备权限，无需 Root | 需要 Root 授权 (su) |
| **设备支持** | 仅限 USB OTG 外接设备 | USB OTG 外接设备、多分区磁盘、块设备节点 |
| **底层通道** | Android USB Host API + 用户态 SCSI 驱动栈 | Linux 内核块设备节点 (`/dev/block/vold/*`) |
| **读写表现** | 顺序吞吐约 15 ~ 19 MB/s | 顺序吞吐最高达 163+ MB/s |
| **挂载形式** | Android SAF DocumentsProvider（系统文件管理器） | SAF DocumentsProvider + 全局 FUSE 挂载 (`/storage/XXXX-XXXX`) |
| **误报提示处理** | 通过通知监听服务过滤系统格式化提示 | 支持直接通过特权屏蔽系统格式化误报提示 |

> 切换运行模式时，应用会自动将脏数据同步落盘、安全卸载活跃会话并重启服务，以确保文件系统完整性。

---

## 读写性能对比与基准数据 / Performance

以下测试数据采集自同一块 USB 3.0 物理闪存盘划分的 6 个独立 BitLocker 加密分区，涵盖不同文件系统与加密算法组合：

| 分区标识 | 文件系统 | 加密算法模式 | 免 Root 顺序读取 | Root 顺序读取 | 吞吐提升 | 免 Root 4K 随机延时 (IOPS) | Root 4K 随机延时 (IOPS) |
|---|---|---|---|---|---|---|---|
| **NT+X** | **NTFS** | **AES-XTS-128** | 16.14 MB/s | **163.94 MB/s** | **10.16x** | 1.10 ms (909 IOPS) | **0.69 ms** (1451 IOPS) |
| **EX+X** | **exFAT** | **AES-XTS-128** | 18.90 MB/s | **101.13 MB/s** | **5.35x** | 1.25 ms (798 IOPS) | **0.74 ms** (1357 IOPS) |
| **EX+C** | **exFAT** | **AES-CBC-128** | 16.00 MB/s | **89.53 MB/s** | **5.60x** | 1.10 ms (908 IOPS) | **0.81 ms** (1241 IOPS) |
| **32+C** | **FAT32** | **AES-CBC-128** | 14.87 MB/s | **66.26 MB/s** | **4.46x** | 1.11 ms (904 IOPS) | **0.63 ms** (1581 IOPS) |
| **32+X** | **FAT32** | **AES-XTS-128** | 16.67 MB/s | **43.90 MB/s** | **2.63x** | 1.39 ms (720 IOPS) | **1.83 ms** (545 IOPS) |
| **NT+C** | **NTFS** | **AES-CBC-128** | 16.55 MB/s | **41.03 MB/s** | **2.48x** | 1.21 ms (826 IOPS) | **0.99 ms** (1006 IOPS) |

- **吞吐差异说明**：免 Root 模式受限于 Android Framework `UsbDeviceConnection.bulkTransfer` 用户态与内核态数据多次拷贝和队列调度机制，各分区读取速率基本在 15 ~ 19 MB/s；Root 模式直通内核原生异步块设备 I/O，消除了中间层队列瓶颈，顺序读取吞吐提升 2.5 ~ 10 倍。
- **稳定性验证**：上述 6 种组合在高负载下均通过了目录递归遍历、文件创建、SHA-256 校验回读与安全删除测试。

---

## 核心特性 / Features

### 1. 全主流文件系统透明增删改查 (Full Read/Write CRUD)
- **NTFS 深度支持**：内置高度定制优化的 `libntfs-3g`，支持大文件读写、目录嵌套、重命名与删除。底层集成扇区写屏障，强制保护卷首 `-FVE-FS-` 关键元数据区；
- **exFAT 高效支持**：内置优化的 exFAT 驱动，支持单文件大于 4GB 的读写，自动维护簇链与 Entry Set；
- **FAT32 兼容支持**：内置裁剪版 `FatFs`，完整支持长文件名 (LFN) 及全功能读写；
- **系统级快速搜索**：接入 DocumentsProvider 原生搜索接口，支持在系统文件管理器搜索栏中实时检索卷内文件。

### 2. 单盘多分区精准识别 (Multi-Partition Scanning)
- 支持单块物理移动硬盘/U 盘上划分的多个不同格式的 BitLocker 分区；
- 采用硬件拓扑层级分析，准确辨识父级磁盘与子分区设备节点，杜绝将磁盘裸设备与子分区误判为多个重复设备的现象；
- 每一个独立分区均可单独输入密码/密钥解锁，或通过记住的凭据实现插入全盘并发自动解锁。

### 3. 数据安全保护与平稳弹出 (Safety & Eject Protection)
- **安全弹出 (Safe Eject)**：
  - 卸载前强制触发底层两级数据 Flush 与 `sync`，清理文件系统 Dirty Bit 标志位；
  - 卸载 FUSE 挂载点并优雅断开连接，避免拔出设备后在 Windows 电脑上提示“扫描并修复此驱动器”；
- **只读保护模式 (Read-Only Mode)**：
  - 卷卡片提供硬件级只读保护开关；
  - 开启后驱动层主动拦截任何写入、重命名、截断或删除调用，并向调用方返回明确的只读异常，适合仅查阅敏感文件的安全场景；
- **Dirty 状态预警**：
  - 自动识别上次在其他电脑上未正常卸载的“脏卷”，在卡片上给出醒目黄色状态徽标提示。

### 4. 全局 POSIX 虚拟挂载 (`/storage/XXXX-XXXX`)
- Root 模式下内置轻量级 FUSE 守护进程，并将其注入至 PID 1 全局挂载命名空间；
- 生成规范的八位十六进制卷标路径（如 `/storage/78F0-B809`）；
- MT 管理器、Termux、第三方代码编辑器、播放器等均可通过标准 Linux 绝对路径直接访问卷内文件。

### 5. 驱动器基准测速 (Drive Benchmark)
- 卡片内置一键测速功能，全程采用安全只读方式，不破坏盘内现有数据：
  - **连续读取吞吐**：实时测算大块顺序读取速度（MB/s）；
  - **4K 随机读取延时**：测试零碎小文件并发寻道响应能力（ms / IOPS）；
  - **物理链路感知**：准确识别接口协商速率（USB 2.0 / USB 3.0 5Gbps / USB 3.1+ 10Gbps）。

### 6. 安全凭据管理与系统误报屏蔽
- **硬件加密存储**：支持记住密码与恢复密钥，凭据由 Android Keystore 硬件根密钥加密存储；
- **生物识别安全验证**：在凭据管理器中查看明文或修改凭据时，强制校验设备指纹或锁屏密码；
- **系统格式化提示屏蔽**：自动过滤系统由于无法识别加密分区而误报的“USB 存储设备已损坏，点击格式化”的侵扰通知。

---

## 系统架构 / Architecture

```
                       外接 USB OTG 加密存储设备
                                   │
         ┌─────────────────────────┴─────────────────────────┐
         ▼                                                   ▼
【免 Root 运行模式】                                 【Root 运行模式】
Android USB Host API (android.hardware.usb)        Linux 内核块设备节点 (/dev/block/vold/*)
         │                                                   │
         ▼                                                   ▼
用户态 SCSI/BOT 协议栈 (UsbMassStorageDriver)       Direct I/O 高性能块读取 (su daemon)
         │                                                   │
         └─────────────────────────┬─────────────────────────┘
                                   │
                                   ▼
                   [BitLocker 探测器 (BitLockerDetector)]
                   扫描检测 -FVE-FS- 卷头签名与分区拓扑
                                   │
                                   ▼
                   [DislockerCore 本地核心引擎 (JNI / C)]
                   mbedtls 密码学原语 (PBKDF2, AES-CCM, VMK 派生)
                   提取 FVEK 会话密钥 (AES-XTS / AES-CBC)
                                   │
                                   ▼
                   [按需动态扇区解密与写屏障拦截]
                   扇区级写拦截 (保护元数据区域与 MBR/GPT)
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
   libntfs-3g 驱动            FatFs 驱动                exFAT 自研驱动
  (NTFS 读写与重命名)      (FAT32 长文件名读写)        (exFAT 簇链与元数据)
         │                         │                         │
         └─────────────────────────┼─────────────────────────┘
                                   │
         ┌─────────────────────────┴─────────────────────────┐
         ▼                                                   ▼
【SAF 存储访问框架】                                 【全局 POSIX 虚拟挂载】
BitLockerDocumentsProvider                         bitlocker_fuse_daemon (Root 特权)
         │                                                   │
         ▼                                                   ▼
Android 原生“文件”应用 (DocumentsUI)                系统全局绝对路径 (/storage/XXXX-XXXX)
(直接在系统抽屉内增删改查)                          (MT管理器、Termux、媒体播放器无缝读写)
```

---

## 技术规格与兼容性矩阵 / Specifications

| 维度 | 规格要求 / 支持范围 | 补充说明 |
|---|---|---|
| **操作系统** | Android 8.0 (API 26) 及更高版本 | 全面适配 Android 14 / 15，支持 16KB 内存页对齐 |
| **处理器架构** | `arm64-v8a`, `armeabi-v7a` | 提供 64 位与 32 位全指令集编译支持 |
| **Root 授权方案** | **免 Root** / **KernelSU** / **Magisk** / **APatch** | 免 Root 模式无需刷机；Root 模式支持三大主流授权工具 |
| **受支持文件系统** | **NTFS**, **exFAT**, **FAT32** | 完整支持文件与目录的查看、创建、修改写入、重命名与删除 |
| **支持加密算法** | AES-XTS (128-bit / 256-bit)<br>AES-CBC (128-bit / 256-bit) | 覆盖 Windows 10/11 默认加密模式及 Windows 7 兼容模式 |
| **认证凭据类型** | 用户密码、48 位数字恢复密钥 | 恢复密钥支持连字符分隔输入，自动显示恢复标识符前缀 |
| **存储硬件形态** | U 盘、移动固态硬盘 (PSSD)、外置移动机械硬盘、外置 SD 卡 | 支持单分区盘与多分区复用盘 |
| **物理连接接口** | USB Type-C OTG、USB-A 转接线、各类扩展坞 | 支持 USB 2.0、USB 3.0 (5 Gbps)、USB 3.1+ (10 Gbps) 链路 |

---

## 快速上手指南 / Quick Start

### 1. 安装应用
- 从 GitHub Releases 下载最新的 `app-release.apk` 安装包；
- 直接在设备上安装。

### 2. 授权与准备
- **免 Root 用户**：打开应用，将加密 U 盘插入手机 OTG 接口，系统弹出“允许应用访问该 USB 设备”时点击**确定**；
- **Root 用户（推荐以获取更高性能）**：打开 KernelSU / Magisk / APatch，在超级用户管理列表中为 **BitUnlocker** 授予 Root 权限。

### 3. 解锁驱动器
1. 插入设备后，应用将自动完成扫描并在首页展示识别出的 BitLocker 卷卡片；
2. 点击卡片上的 **「解锁」** 按钮；
3. 选择输入**用户密码**或 **48 位恢复密钥**（界面会贴心显示当前卷的恢复标识符前缀，便于在微软账户中查找对应的密钥）；
4. 可勾选“记住凭据”，之后插盘即可实现自动秒解。

### 4. 浏览与管理文件
- **原生文件管理器**：解锁成功后，点击卡片上的 **「在文件管理器中打开」**，即可在系统原生“文件”管理器中直接查看并编辑文件；
- **第三方应用直接访问**（Root 模式）：点击 **「虚拟挂载至真实目录」**，卷将被挂载至 `/storage/XXXX-XXXX`，打开 MT 管理器或 Termux 即可通过路径自由操作；
- **只读保护模式**：若仅需临时拷贝或查阅盘内资料，可开启卡片上的 **「只读保护模式」** 开关，驱动层将实时拒绝一切写入操作。

### 5. 测速与安全弹出
- **基准测速**：点击卡片上的 **「基准测速」**，即可无损检测当前设备的顺序读取与 4K 随机读取表现；
- **安全移除设备**：使用完毕后，**强烈建议点击卡片上的「安全弹出」**，待系统提示“已安全弹出，现在可以安全拔出设备”后再拔出硬件。

---

## 项目目录结构 / Project Structure

```
BitLockerDroid/
├── app/                                 # Android 应用主工程
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 清单声明 (SAF DocumentsProvider, USB Receiver)
│   │   ├── kotlin/com/bitlockerdroid/
│   │   │   ├── BitLockerApp.kt          # 应用程序上下文入口
│   │   │   ├── crypto/                  # Keystore 硬件密钥加密凭据存储
│   │   │   ├── provider/                # SAF DocumentsProvider 文件系统虚拟化接入
│   │   │   ├── service/                 # 后台核心服务 (UnlockManager, Detector, FUSE)
│   │   │   ├── ui/                      # Jetpack Compose UI 界面层
│   │   │   │   ├── BitLockerSettingsActivity.kt # 主界面与导航
│   │   │   │   ├── dialogs/             # 解锁弹窗、高级设置、模式切换确认对话框
│   │   │   │   ├── settings/            # 设置面板、组件与凭据管理
│   │   │   │   └── volumes/             # 卷卡片、硬件参数展开视图、测速面板
│   │   │   ├── usb/                     # 免 Root 用户态 USB Host/SCSI 通信驱动
│   │   │   └── util/                    # 工具类 (Root 探测, 偏好设置, 自动重启器)
│   │   └── res/                         # 资源文件 (多语言 strings.xml, 图标, 布局)
│   └── build.gradle.kts                 # 模块构建脚本
├── native/                              # 底层 C/C++ 核心与驱动引擎
│   ├── CMakeLists.txt                   # NDK CMake 构建配置 (16KB 页对齐支持)
│   ├── daemon/                          # FUSE 守护进程 (fuse_daemon) 与 I/O 特权守护进程
│   ├── dislocker/                       # BitLocker 解密核心逻辑 (VMK/FVEK, 算法解析)
│   ├── jni/                             # JNI 绑定胶水层 (dislocker_jni.c)
│   ├── tests/                           # 宿主机独立测试工具集 (verify_disk 等)
│   └── third_party/                     # 开源驱动裁剪库
│       ├── fatfs/                       # FatFs FAT32 文件系统引擎
│       ├── libntfs-3g/                  # libntfs-3g NTFS 读写驱动
│       └── mbedtls/                     # mbedtls 密码学原语库
├── BUILD.md                             # 详细构建与环境搭建指南
├── CONTRIBUTING.md                      # 代码贡献指南
└── README.md                            # 项目主文档
```

---

## 源码编译与构建 / Build from Source

### 环境配置要求
- **操作系统**：Linux (Ubuntu 22.04+ / WSL2) 或 macOS
- **JDK**：OpenJDK 17
- **Android SDK**：Platforms `android-34`，Build-Tools `34.0.0`
- **Android NDK**：`26.3.11579264` (NDK r26d)
- **CMake**：`3.22.1+`

### 快速编译

1. **克隆源码仓库**：
   ```bash
   git clone https://github.com/Linoleic/BitLockerDroid.git
   cd BitLockerDroid
   ```

2. **配置 SDK 路径**：
   在项目根目录创建 `local.properties` 并指定 SDK 与 NDK 本地绝对路径：
   ```properties
   sdk.dir=/path/to/your/android-sdk
   ndk.dir=/path/to/your/android-sdk/ndk/26.3.11579264
   ```

3. **编译 Release APK**：
   ```bash
   chmod +x gradlew
   ./gradlew :app:assembleRelease
   ```
   构建成功后，输出文件位于：`app/build/outputs/apk/release/app-release.apk`。

4. **编译 Debug APK**：
   ```bash
   ./gradlew :app:assembleDebug
   ```
   输出文件位于：`app/build/outputs/apk/debug/app-debug.apk`。

更详细的编译参数、密钥签名与 CI/CD 自动化流水线配置，请参见 [BUILD.md](BUILD.md)。

---

## 常见问题与技术说明 / FAQ & Notes

### Q1: 免 Root 模式与 Root 模式有何区别？日常使用该如何选择？
- **免 Root 模式**：无需解锁手机 Bootloader 或刷入任何 Magisk/KernelSU 模块。只要插入 USB OTG 设备并允许 USB 访问授权，即可使用系统原生“文件”管理器完整读写 NTFS、exFAT 与 FAT32 加密盘。适合绝大部分追求安全与便捷的用户。
- **Root 模式**：若设备已获取 Root 权限，强烈建议在设置中保持开启。它能够绕过 Android USB Host HAL 层的数据调度限制，直接通过内核直通读写设备，**读写速率可提升数倍至十倍（高达 163 MB/s）**，同时还可开启 `/storage/XXXX-XXXX` 全局绝对路径虚拟挂载。

### Q2: 为什么从手机拔下 U 盘前一定要点击“安全弹出”？
- 操作系统对磁盘数据写入通常具备缓存机制，直接拔掉设备可能导致尚未完全落盘的缓存数据丢失；
- 更关键的是，文件系统在挂载时会将磁盘元数据头部的“Dirty Bit”置位，若未执行正常卸载，Dirty Bit 将保持置位状态。当这块盘再次插回 Windows 电脑时，Windows 会检测到异常并强制提示“此驱动器存在问题，需要扫描并修复”。
- 点击 BitLockerDroid 卡片上的**「安全弹出」**，驱动会自动完成两级缓存落盘、清除 Dirty Bit 并安全卸载，保证在 Windows 电脑上能够无缝直接读取。

### Q3: 为什么输入恢复密钥时需要核对“恢复标识符”？
- 一个 BitLocker 加密盘可能在多次备份或不同时期生成过多组 48 位数字恢复密钥；
- BitLockerDroid 会在解锁界面上方自动显示当前加密卷的 **恢复标识符（Recovery Key ID）前 8 位**（例如 `ECCB8418`）；
- 用户登录微软账户网页端（[https://account.microsoft.com/devices/recoverykey](https://account.microsoft.com/devices/recoverykey)）查阅密钥库时，只需核对该 8 位前缀是否吻合，即可确保输入的恢复密钥绝对正确，避免无效尝试。

### Q4: 虚拟挂载到 `/storage/XXXX-XXXX` 后，为什么部分第三方文件管理器看不到？
- 全局挂载依赖 Root 权限将 FUSE 守护进程命名空间同步至系统的 PID 1 初始命名空间；
- 绝大部分支持标准 Linux 绝对路径的工具（如 **MT 管理器**、**Termux**、**VLC** 等）可以直接通过切换到根目录并在 `/storage/` 下找到对应的十六进制目录进行无障碍访问；
- 部分仅依赖 Android 抽象媒体库 (MediaStore) 的简单图库应用可能不会主动扫描非系统内建的挂载点，此时推荐在系统自带“文件”管理器或通过 MT 管理器进行文件管理与打开。

---

## 开源协议与致谢 / License & Acknowledgments

本项目采用 **GNU General Public License v2.0 (GPL-2.0)** 协议开源。详细协议文本请阅读根目录下的 [LICENSE](LICENSE) 文件。

### 核心上游与依赖致谢
- **BitLocker 解析核心**：参考并移植自开源项目 [dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)；
- **密码学与摘要计算**：采用 [mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)；
- **NTFS 文件系统引擎**：内置深度定制并进行 16KB 页对齐改造的 [libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)；
- **FAT32 文件系统引擎**：采用 [FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)。
