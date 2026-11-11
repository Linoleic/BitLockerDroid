# BitLockerDroid (BitUnlocker)

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

<p align="center">
  <b>Android 平台 Microsoft BitLocker 加密卷访问与管理工具（支持 NTFS、exFAT、FAT32）</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-13.0%2B_API_33%2B-blue.svg" alt="Android 13.0+ (minSdk 33)">
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## 目录

- [项目简介](#项目简介)
- [双运行模式对比](#双运行模式对比)
- [核心特性](#核心特性)
- [性能概览](#性能概览)
- [技术规格与兼容性](#技术规格与兼容性)
- [快速上手指南](#快速上手指南)
- [源码构建](#源码构建)
- [相关文档](#相关文档)
- [常见问题解答](#常见问题解答)
- [开源协议与致谢](#开源协议与致谢)

---

## 项目简介

**BitLockerDroid**（应用显示名 **BitUnlocker**）是一个运行在 Android 平台上的 BitLocker 加密卷访问工具，支持在移动设备上解锁并读写 Windows BitLocker 加密的外接存储设备（USB OTG U 盘、移动固态/机械硬盘及 SD 卡）。

- **文件系统支持**：支持 NTFS、exFAT 与 FAT32 分区的文件浏览、新建、写入、重命名与删除。
- **认证凭据**：支持用户密码（PBKDF2/SHA-256）与 48 位数字恢复密钥。
- **系统集成**：接入 Android 存储访问框架（SAF `DocumentsProvider`），可在系统“文件”管理器中直接访问。
- **全局虚拟挂载（Root）**：通过 FUSE 守护进程将解密卷挂载至 `/storage/XXXX-XXXX`，供第三方应用通过标准 POSIX 路径直接访问。
- **双运行模式**：支持免 Root（Android USB Host API）与 Root（Linux 内核块设备节点）两种底层模式。

---

## 双运行模式对比

应用提供免 Root 与 Root 两种底层驱动路径，可在设置中切换（切换前自动同步落盘并关闭已打开会话）：

| 维度 | 免 Root 模式 (USB Host API) | Root 模式 (KernelSU / Magisk / APatch) |
|---|---|---|
| **权限要求** | 仅需 USB 设备访问授权，无需 Root | 需要 Root 授权 (`su`) |
| **设备支持** | USB OTG 外接存储设备 | USB OTG、多分区磁盘、内核块设备节点 |
| **底层通道** | Android `usbfs` (`USBDEVFS_BULK` 16KB 分片) + 用户态 BOT 状态机 | Linux 内核块设备 (`/dev/block/vold/*`) + 常驻 `bitlocker_io` 管道守护进程 |
| **挂载形式** | SAF `DocumentsProvider`（系统文件管理器） | SAF `DocumentsProvider` + 全局 FUSE 挂载 (`/storage/XXXX-XXXX`) |
| **脏卷处理与诊断** | 支持只读元数据健康检查与安全弹出 | 支持清除脏卷标志位 (Dirty Bit) 与底层元数据检查 |
| **系统格式化提示** | 通知监听服务自动过滤系统格式化提示 | 特权抑制系统格式化误报通知 |

---

## 核心特性

- **读写分离的混合文件系统架构**：
  - **只读浏览通道**：通过 Kotlin 层解析器（`NtfsReader`、`ExFatReader`、`Fat32Reader`）配合 64 KB 扇区对齐的 LRU 内存块缓存（`CachedBlockSource`）提供目录枚举与读取；
  - **按需写入通道**：发生写入、创建、重命名或删除时，按需挂载底层 C 驱动（NTFS 使用 `libntfs-3g`，FAT32 与 exFAT 使用启用 `FF_FS_EXFAT` 的 `FatFs`）。
- **ARMv8-CE 硬件密码学加速**：在 `arm64-v8a` 设备上利用 ARMv8-A 密码学扩展指令（`AES`、`PMULL`、`SHA2`）与 4 路超标量交织循环加速 PBKDF2 密钥推导及 AES-XTS / AES-CBC 扇区加解密；内置启动回环自检，不通过或不支持时自动回退至软件实现。
- **凭据加密与生物识别保护**：支持保存密码或 48 位恢复密钥（通过 Android Keystore AES-GCM 密钥加密存储），支持生物识别验证与窗口防截屏保护（`FLAG_SECURE`）。
- **元数据写屏障与容灾备份**：
  - 底层扇区写函数强制拦截针对卷头保护区与三处 64 KB FVE 元数据块的写入请求（返回 `-EPERM`），防止文件系统异常覆写密钥区；
  - 支持提取与恢复 FVE 卷头元数据（`.fvemeta`），以及后台导出解密镜像（`.img`）或原始加密镜像（`.raw`）。
- **单盘多分区识别与安全弹出**：区分物理磁盘与子分区拓扑，支持在保持连接的状态下单独关闭卷，或在拔盘前执行缓冲区同步与 SCSI 停盘（Safe Eject）。
- **局域网无线共享 (Web & WebDAV)**：内置 HTTP 门户与 WebDAV 服务端，支持同一局域网下的桌面与移动设备挂载访问。

---

## 性能概览

完整的分层架构图、I/O 传输协议细节、安全加固机制以及 6 分区完整测试矩阵，请参阅 **[架构与性能概览文档 (ARCHITECTURE_zh.md)](ARCHITECTURE_zh.md)** 与 **[存储与加解密性能评估规范 (perf_eval.md)](perf_eval.md)**。

以下为骁龙 8 Gen 2（Android 16，Linux 5.15，USB 3.0）平台下的实测代表数据汇总：

| 测试项目与场景 | 运行模式与配置 | 实测指标 |
| :--- | :--- | :--- |
| **PBKDF2 密钥推导** (1,048,576 轮 SHA-256) | ARMv8-CE 硬件加速 vs 纯 C 软件实现 | **197.7 ~ 207.5 ms** vs 801.7 ~ 815.6 ms (**3.86x ~ 4.13x**) |
| **Tier 1 移动固态硬盘** (HIKSEMI P202 1TB / UAS) | **Root 模式** · NTFS / exFAT (AES-XTS) | 连续读 **77.0 ~ 78.8 MB/s**（峰值 100.4 MB/s）· 连续写 **50.3 ~ 53.2 MB/s** |
| **Tier 2 主流闪存 U 盘** (Lexar 32GB / BOT) | **Root 模式** · NTFS / exFAT (AES-XTS) | 连续读 **52.2 ~ 66.5 MB/s**（峰值 79.5 MB/s）· 连续写 **16.0 ~ 16.9 MB/s** |
| **Tier 2 主流闪存 U 盘** (USB 3.0 / BOT) | **免 Root 模式** · USB Host + SAF 双缓冲 | 连续读 **43.25 MB/s** · 连续写 **12.97 MB/s**（50MB 端到端读 **13.76 MB/s** / 写 **8.14 MB/s**） |
| **4K 随机访问延迟** (Tier 1 PSSD / Tier 2 U 盘) | **Root 模式** vs **免 Root 模式** | Root: 读 **1.32 ~ 1.40 ms** (~740 IOPS) / 写 **7.13 ms** · 免 Root: 读 **1.33 ms** (752 IOPS) / 写 **9.25 ms** (108 IOPS) |

---

## 技术规格与兼容性

| 维度 | 规格要求 / 支持范围 | 说明 |
|---|---|---|
| **操作系统** | Android 13.0+ (`minSdk 33`) | 编译目标 `compileSdk 34` / `targetSdk 34`，向下兼容至 Android 13，向上兼容 Android 15/16+ |
| **处理器架构** | `arm64-v8a`, `armeabi-v7a`, `x86_64` | 64 位与 32 位原生 ABI（ARMv8-CE 硬件加速仅在 `arm64-v8a` 下生效） |
| **Root 方案** | **免 Root** / **KernelSU** / **Magisk** / **APatch** | 免 Root 模式开箱即用；Root 模式支持内核块设备直通与全局 FUSE 挂载 |
| **受支持文件系统** | **NTFS**, **exFAT**, **FAT32** | 支持浏览、新建、修改、重命名、删除及 >4GB 大文件传输 |
| **加密算法支持** | AES-XTS (128/256 位)、AES-CBC (128/256 位，无扩散器) | 覆盖 Windows 10/11 默认 XTS 格式及 Windows 7/8 CBC 兼容格式（不支持 Vista 时代 Diffuser 卷） |
| **内存分页对齐** | 16 KB 与 4 KB 页面对齐 | 所有原生 `.so` 目标均启用 `-Wl,-z,max-page-size=16384`，兼容 Android 15+ 16 KB 页大小设备 |
| **认证凭据类型** | 用户密码、48 位数字恢复密钥 | 解锁界面展示恢复密钥标识符（Recovery Key ID）以便核对 |

---

## 快速上手指南

1. **安装**：从 [Releases](https://github.com/Linoleic/BitLockerDroid/releases) 下载并安装 `app-release.apk`。
2. **连接与授权**：插入 USB OTG 设备。免 Root 模式在系统弹窗中允许 USB 访问授权；Root 模式在 Root 管理应用中授予超级用户权限。
3. **解锁**：点击卷卡片的「解锁」，输入密码或 48 位恢复密钥（可核对界面显示的恢复标识符，按需勾选“记住凭据”）。
4. **访问与安全弹出**：
   - 点击「打开」在系统“文件”管理器中读写文件；
   - Root 模式下点击「虚拟挂载至真实目录」可将卷挂载至 `/storage/XXXX-XXXX` 供第三方应用访问；
   - 「关闭卷」或「关闭全部卷」会同步并关闭加密会话，但保持物理设备连接；
   - 拔除设备前请点击设备顶部的「弹出设备」，等待刷盘与停盘完成后再拔下。

---

## 源码构建

快速本地构建：
```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
./gradlew :app:assembleRelease
```
产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

详细环境配置、NDK 构建要求与宿主机测试工具说明请参阅 **[源码构建指南 (BUILD_zh.md)](BUILD_zh.md)**。

---

## 相关文档

| 文档 | 说明 |
| :--- | :--- |
| **[架构与性能概览文档 (ARCHITECTURE_zh.md)](ARCHITECTURE_zh.md)** | 分层系统架构、双模式 I/O 链路、ARMv8-CE 4 路交织加速、混合文件系统、安全边界及完整基准测试表（[English](ARCHITECTURE.md)） |
| **[源码构建指南 (BUILD_zh.md)](BUILD_zh.md)** | JDK / SDK / NDK 环境配置、Release 签名机制、16 KB 页面对齐要求与 Linux 宿主机独立验证工具（[English](BUILD.md)） |
| **[存储与加解密性能评估规范 (perf_eval.md)](perf_eval.md)** | 三级存储介质分类、预热剔除、`drop_caches` 缓存隔离、闭环 `sync` 计时与 6 分区标准测试矩阵定义 |
| **[ADB 调试与自动化测试接口指南 (ADB_DEBUG_INTERFACE.md)](ADB_DEBUG_INTERFACE.md)** | 基于 `DocumentsProvider` 的命令行无头解锁、硬件加速热切换、基准测速与 VFS 自动化回环校验脚本 |
| **[版本更新日志 (CHANGELOG.md)](CHANGELOG.md)** | 各版本功能演进、架构优化与问题修复记录 |
| **[贡献指南 (CONTRIBUTING.md)](CONTRIBUTING.md)** | Issue 反馈规范、安全漏洞私密披露流程与代码风格约定 |

---

## 常见问题解答

- **Q: “关闭卷”和“弹出设备”有什么区别？**  
  **A**: “关闭卷”和“关闭全部卷”会等待在途写入完成、同步刷盘并释放加密会话，物理设备仍保持连接；拔盘前应使用“弹出设备”，它会关闭该设备下所有卷、检查占用并发送硬件缓存同步与停盘指令。
- **Q: 恢复标识符（Recovery Key ID）有什么用？**  
  **A**: 同一加密盘在不同时期可能生成过多个恢复密钥。解锁弹窗显示的恢复标识符可用于同微软账户页面（[account.microsoft.com/devices/recoverykey](https://account.microsoft.com/devices/recoverykey)）中的标识符核对。
- **Q: 为什么部分第三方应用在全局挂载后看不到 `/storage/XXXX-XXXX`？**  
  **A**: 全局挂载将 FUSE 挂载点注入 PID 1 挂载命名空间，支持标准文件路径访问的应用（如 MT 管理器、Termux、VLC 等）可直接读写；但仅读取 Android `MediaStore` 媒体数据库的相册类应用不会主动索引自定义外置挂载目录。
- **Q: 为什么免 Root 模式的读写速率低于 Root 模式？**  
  **A**: 免 Root 模式受限于 Android 沙箱，必须通过用户态 `usbfs` 进行最大 16 KB 分片的同步 `USBDEVFS_BULK` 传输、在用户态执行半双工 BOT 停等握手，并通过 SAF 管道跨进程传输数据，且缺乏 Linux 内核页缓存合并小写入。BitLockerDroid 通过 64 KB LRU 块缓存与 SAF 512 KB 双缓冲写队列缓解了部分开销，但若追求发挥外置 SSD 的物理总线带宽，建议使用 Root 模式。详见 [架构与性能概览文档 §6.4](ARCHITECTURE_zh.md#64-root-模式-vs-免-root-模式性能对比与成因拆解)。
- **Q: 遇到设备兼容或挂载问题如何反馈？**  
  **A**: 欢迎提交 [GitHub Issues](https://github.com/Linoleic/BitLockerDroid/issues)，建议附上应用内「**设置 → 高级选项 → 系统环境与诊断 → 运行诊断日志**」导出的日志文本（日志不含密码或恢复密钥明文，但包含卷 GUID、设备路径和文件名，提交前请自行审阅）。

---

## 开源协议与致谢

本项目采用 **GNU General Public License v2.0 (GPL-2.0)** 协议开源，详见 [LICENSE](LICENSE)。

### 核心上游项目与依赖
- **BitLocker 解析核心**：[dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)
- **密码学原语库**：[mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)
- **NTFS 文件系统引擎**：[libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)
- **FAT32 / exFAT 写入引擎**：[FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)
- **USB 大容量存储框架**：[libaums](https://github.com/magnusja/libaums) (Apache-2.0)
