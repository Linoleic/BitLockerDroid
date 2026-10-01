# BitLockerDroid (BitUnlocker)

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

<p align="center">
  <b>Android 原生 Microsoft BitLocker 加密盘管理工具（支持 NTFS、exFAT、FAT32）</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-13.0%2B_API_33--37-blue.svg" alt="Android 13.0+ (API 33 ~ 37)">
  <img src="https://img.shields.io/badge/Root-Optional%20(Non--Root%20USB%20Host%20%7C%20Root)-brightgreen.svg" alt="Root Optional">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## 目录

- [项目简介](#项目简介)
- [双运行模式与架构对比](#双运行模式与架构对比)
- [读写性能基准数据](#读写性能基准数据)
- [核心特性](#核心特性)
- [系统架构](#系统架构)
- [技术规格与兼容性](#技术规格与兼容性)
- [快速上手指南](#快速上手指南)
- [源码构建](#源码构建)
- [常见问题解答](#常见问题解答)
- [开源协议与致谢](#开源协议与致谢)

---

## 项目简介

**BitLockerDroid**（应用显示名 **BitUnlocker**）是一个 Android 平台上的 BitLocker 加密卷访问工具，支持在移动设备上解锁并读写 Windows BitLocker 加密的外接存储设备（USB OTG U 盘、移动硬盘及 SD 卡）。

- **文件系统支持**：支持 NTFS、exFAT 与 FAT32 分区的文件浏览、新建、修改、重命名与删除。
- **认证凭据**：支持用户密码（PBKDF2/SHA-256）与 48 位数字恢复密钥。
- **系统集成**：接入 Android 存储访问框架（SAF DocumentsProvider），可在系统“文件”管理器中直接管理。
- **全局虚拟挂载（Root）**：通过 FUSE 将解密卷挂载至 `/storage/XXXX-XXXX`，供第三方应用通过绝对路径直接访问。
- **双运行模式**：支持免 Root（Android USB Host API）与 Root（直通内核块设备）两种底层架构。

---

## 双运行模式与架构对比

应用提供免 Root 与 Root 两种底层驱动架构，可在设置中无缝切换（切换时自动落盘并安全卸载）：

| 维度 | 免 Root 模式 (USB Host API) | Root 模式 (KernelSU / Magisk / APatch) |
|---|---|---|
| **权限要求** | 仅需 USB 设备授权，无需 Root | 需要 Root 授权 (su) |
| **设备支持** | USB OTG 外接设备 | USB OTG、多分区磁盘、内核块设备节点 |
| **底层通道** | Android USB Host API + 用户态 SCSI 驱动 | Linux 内核块设备节点 (`/dev/block/vold/*`) |
| **读取吞吐** | 顺序读取约 35 ~ 50 MB/s | 顺序读取最高达 140+ MB/s |
| **挂载形式** | SAF DocumentsProvider（系统文件管理器） | SAF DocumentsProvider + 全局 FUSE 挂载 (`/storage/XXXX-XXXX`) |
| **脏卷修复与诊断** | 支持纯只读深度结构诊断与安全卸载 | 支持一键修复脏卷标志位 (Dirty Bit) 与底层结构深度体检 |
| **误报提示** | 通知监听服务自动过滤格式化误报 | 特权屏蔽系统格式化误报提示 |

---

## 读写性能基准数据

测试数据在骁龙 8 Gen 2 平台（Android 16 / HyperOS，KernelSU 5.15）与 USB 3.0 (5 Gbps) 物理闪存盘上完成，每个测试项均独立采集 10 次有效数据计算算术平均值 (N=10)。完整多维度基准测试数据表可参阅 **[perf_test.md](perf_test.md)**。

### 顺序吞吐与随机延时基准表 (10次采样平均)

| 分区与加密算法 | 文件系统 | 非Root 硬件双缓冲 | 非Root 硬件关闭双缓冲 | Root 硬件直通 | Root 吞吐提升 | 非Root 4K 延时 (IOPS) | Root 4K 延时 (IOPS) |
|---|---|---|---|---|---|---|---|
| **NTFS (AES-XTS)** | NTFS | 50.05 MB/s (读) / 18.73 MB/s (写) | 41.76 MB/s (读) / 13.12 MB/s (写) | **122.19 MB/s** (读) / **21.30 MB/s** (写) | **2.44x** (读) | 1.25 ms (860 IOPS) | **1.10 ms** (954 IOPS) |
| **NTFS (AES-CBC)** | NTFS | 34.87 MB/s (读) / 14.72 MB/s (写) | 41.24 MB/s (读) / 11.86 MB/s (写) | **95.04 MB/s** (读) / **26.29 MB/s** (写) | **2.73x** (读) | 1.46 ms (720 IOPS) | **0.99 ms** (1078 IOPS) |
| **exFAT (AES-XTS)** | exFAT | 48.24 MB/s (读) / 20.80 MB/s (写) | 41.29 MB/s (读) / 16.34 MB/s (写) | **97.40 MB/s** (读) / **20.34 MB/s** (写) | **2.02x** (读) | 1.11 ms (937 IOPS) | **0.93 ms** (1124 IOPS) |
| **exFAT (AES-CBC)** | exFAT | 46.59 MB/s (读) / 20.14 MB/s (写) | 38.95 MB/s (读) / 11.74 MB/s (写) | **95.98 MB/s** (读) / **31.63 MB/s** (写) | **2.06x** (读) | 1.24 ms (837 IOPS) | **0.98 ms** (1097 IOPS) |
| **FAT32 (AES-XTS)** | FAT32 | 36.87 MB/s (读) / 1.76 MB/s (写) | 39.80 MB/s (读) / 1.37 MB/s (写) | **93.83 MB/s** (读) / **19.60 MB/s** (写) | **2.55x** (读) | 1.49 ms (703 IOPS) | **0.92 ms** (1140 IOPS) |
| **FAT32 (AES-CBC)** | FAT32 | 42.90 MB/s (读) / 1.70 MB/s (写) | 40.78 MB/s (读) / 1.39 MB/s (写) | **140.66 MB/s** (读) / **18.71 MB/s** (写) | **3.28x** (读) | 1.46 ms (715 IOPS) | **0.92 ms** (1152 IOPS) |

### 核心性能结论

- **ARMv8 CE 硬件加速**：1,048,576 轮 SHA-256 密钥拉伸耗时由 494.52 ms 骤降至 104.03 ms（**加速比 4.75x**），解锁验证近乎瞬时完成。
- **双缓冲流水线增益**：免 Root 模式下开启双缓冲流水线，全卷连续写入吞吐提升 **+39.5%**（12.97 MB/s vs 9.30 MB/s），50MB 大文件写入吞吐提升 **+24.3%**（8.14 MB/s vs 6.55 MB/s）。
- **Root 模式内核直通**：直接绕过用户态 USB 调度层，顺序读取平均达到 **107.52 MB/s**（**2.49x** 加速），50MB 大文件写入平均 **34.52 MB/s**（**4.24x** 加速）。
- **数据一致性验证**：全部文件系统与加密算法组合在多轮测试中均 100% 通过 MD5 与 SHA-256 校验。
- 更多详细数据（包括分卷对比、4K 随机 IOPS、大文件与标准文件传输详情），请查阅 **[全链路性能基准测试数据表 (perf_test.md)](perf_test.md)**。

---

## 核心特性

- **ARMv8 CE 硬件密码学加速**：充分调用 ARMv8-A 硬件密码学扩展指令集（`PMULL`, `AES`, `SHA2`），原生硬件加速 AES-XTS、AES-CBC 扇区加解密与 PBKDF2 SHA-256 密钥拉伸（解锁提速 4.75 倍，耗时仅需 ~104 ms）。支持 `getauxval(AT_HWCAP)` 运行时 CPU 指令集探测、测试向量自检以及纯软件后备平滑降级。
- **双缓冲异步写入流水线**：将 SAF 文档数据接收与底层 USB 传输解耦，利用双缓冲环形流水线交叠并发处理，连续写入吞吐提升高达 39.5%，有效抑制移动闪存写入抖动。
- **Android 15+ 16 KB 页面对齐**：原生 C/C++ 共享库全面适配 Google 16 KB 页面大小规范 —— 采用 `useLegacyPackaging = false`，并在每个原生 CMake 目标上显式添加 `-Wl,-z,max-page-size=16384` 链接选项（`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` 这个 CMake 开关在锁定的 NDK r26d 下并不生效）。确保未来 Android 版本的卓越内存映射性能与合规性。
- **全主流文件系统透明读写**：内置裁剪优化的 `libntfs-3g`（集成卷头扇区写屏障）、exFAT 驱动（支持 >4GB 大文件）与 `FatFs`（支持长文件名 LFN），提供完整的增删改查及系统级快速检索。
- **单盘多分区精准识别**：基于硬件拓扑层级分析，准确辨识父级磁盘与子分区节点，杜绝裸设备与分区重复识别；支持多分区独立或并发自动解锁。
- **脏卷一键修复与结构深度诊断**：
  - **脏标记一键清除**：支持快速重置因热拔或非正常卸载导致的 NTFS、FAT32、exFAT unclean 脏卷状态，恢复读写挂载，告别 PC 端弹窗。
  - **结构真正异常深度诊断**：毫秒级（<50ms）纯只读分析底层元数据，涵盖 NTFS（MFT USN/Fixup 扇区撕裂校验、`$MFTMirr` 一致性、`$INDEX_ROOT` B-Tree 索引树）、FAT32（引导扇区与备份比对、FSInfo 签名、双 FAT 分配表同步、根目录死链探测）以及 exFAT（对齐微软官方标准的 11 扇区循环冗余校验和、备份扇区参数比对、`MediaFailure` 介质故障位检测、根目录流解析）。
  - **修复前置安全拦截**：在一键修复时自动执行前置体检；若发现真正结构损坏，自动展示红色高危警报并拦截盲目写操作，保障数据安全。
- **数据安全与平稳弹出**：提供硬件级只读保护开关，驱动层主动拦截写入；安全弹出强制双级缓存落盘并清除 Dirty Bit，避免插回 Windows 提示扫描修复；自动预警未正常卸载的脏卷。
- **全局 POSIX 虚拟挂载**：Root 模式下通过 FUSE 注入全局挂载命名空间（`/storage/XXXX-XXXX`），MT 管理器、Termux、多媒体播放器等应用可通过标准 Linux 绝对路径直接读写。
- **驱动器基准测速**：内置无损安全测速功能，实时测算大块连续读取吞吐 (MB/s) 与 4K 随机延时 (IOPS)，自动识别 USB 物理协商速率。
- **生物识别凭据保险箱**：基于 Android Keystore 硬件级根密钥加密保护 BitLocker 密码与恢复密钥。在设置中提供独立安全开关，查看已存凭据或快速解锁时强制验证生物识别（指纹/面部）或系统锁屏凭据；动态激活窗口防窥防护（`FLAG_SECURE`），彻底防止敏感密码在录屏、截屏或多任务后台缩略图中泄漏。
- **元数据备份与镜像导出（数据容灾与底层防护）**：
  - **FVE 元数据精准备份与急救恢复 (`.fvemeta`)**：完整提取扇区 0 (VBR) 与全部 3 处 64KB FVE 卷头元数据副本，内置全量 SHA-256 完整性与 CRC-32 区块校验。在引导记录或卷头遭破坏时一键底层穿透回写急救。
  - **严格容量校验安全拦截**：急救恢复时强制对比目标物理分区的字节容量与扇区大小，与备份头不一致时严格禁止写入，杜绝误选分区造成其他磁盘损毁。
  - **双模式分区镜像导出**：支持直接导出已解锁虚拟卷的解密 `.img` 镜像（在电脑端可直接免密挂载为常规分区），或底层原始加密 `.raw` 分区镜像（全盘离线取证与克隆）。配备前台传输服务 (`dataSync`)、防休眠 WakeLock、实时传输速率 (MB/s) 与剩余时间预估 (ETA)。
- **局域网无线共享 (Web & WebDAV 双协议)**：
  - **全平台免客户端直连**：内置高效 WebDAV 服务端，Windows“映射网络驱动器”、macOS 访达“连接服务器”、iOS/iPadOS“文件”应用无需安装任何第三方工具即可将其作为网络磁盘原生挂载与高速读写。
  - **自适应现代 Web 门户**：自动生成极简深浅色响应式 Web 界面，支持二维码扫码即开、按层级极速浏览、大文件多线程断点续传 (HTTP 206 Partial Content)、多媒体音视频流式在线点播播放与文件批量拖拽上传。
  - **多重安全与状态防护**：支持可配置自定义端口、只读防篡改保护与 HTTP Basic 访问凭据认证；配备独立前台常驻服务 (`dataSync`)、Wi-Fi 高性能锁与系统唤醒锁 (`WakeLock`)，设备拔出或应用退出自动终止，杜绝通知与连接残留。
- **未加密存储设备原生协同**：智能识别插入的普通未加密 USB 存储介质（FAT32、exFAT、NTFS 等），免 Root 模式下主动避让 Android 系统原生挂载，杜绝重复弹窗申请 USB Host 权限；在卷列表中清晰展示系统就绪状态与存储空间占用，并支持一键唤起系统文件管理器。
- **系统格式化误报拦截**：自动过滤系统因无法识别加密卷而触发的格式化误报通知。

---

## 系统架构

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

## 技术规格与兼容性

| 维度 | 规格要求 / 支持范围 | 说明 |
|---|---|---|
| **操作系统** | Android 13.0+ (API 33 ~ 37) | 要求 Android 13.0 及以上（`minSdk 33`）；已在 HyperOS 4（Android 17 / API 37）与 HyperOS 3（Android 16 / API 36）实机验证 |
| **处理器架构** | `arm64-v8a`, `armeabi-v7a`, `x86_64` | 提供 64 位与 32 位原生 ABI 支持（ARMv8 CE 硬件加速仅在 arm64-v8a 生效） |
| **Root 方案** | **免 Root** / **KernelSU** / **Magisk** / **APatch** | 免 Root 零门槛；Root 模式性能更优 |
| **受支持文件系统** | **NTFS**, **exFAT**, **FAT32** | 完整增删改查、重命名与大文件读写 |
| **加密算法支持** | AES-XTS (128/256 位)、AES-CBC (128/256 位) | 覆盖 Windows 10/11 默认及 Windows 7 兼容格式 |
| **硬件密码加速** | ARMv8-A Cryptography Extensions (PMULL, AES, SHA2) | 运行时动态探测；4.75 倍解锁提速；自动软件平滑降级 |
| **内存分页对齐** | 16 KB 与 4 KB 页面对齐 | 原生库全面遵循 Android 15+ 16 KB ELF 分页对齐标准 |
| **认证凭据类型** | 用户密码、48 位数字恢复密钥 | 自动展示恢复标识符 (Recovery Key ID) 便于核对 |
| **硬件形态与接口** | U 盘、移动固态硬盘 (PSSD)、移动机械硬盘、SD 卡 | USB 2.0 / USB 3.0 (5Gbps) / USB 3.1+ (10Gbps) |

---

## 快速上手指南

1. **安装**：从 [Releases](https://github.com/Linoleic/BitLockerDroid/releases) 下载并安装 `app-release.apk`。
2. **连接与授权**：插入 USB OTG 设备。免 Root 模式允许 USB 访问授权；Root 用户在授权管理应用中授予 Root 权限。
3. **解锁**：点击卡片「解锁」，输入密码或 48 位恢复密钥（核对界面显示的恢复标识符，可勾选“记住凭据”以便下次插盘秒解）。
4. **浏览与安全弹出**：
   - 点击「打开」直接在系统“文件”管理器中管理数据；
   - Root 模式下点击「虚拟挂载至真实目录」可通过 `/storage/XXXX-XXXX` 供第三方应用访问；
   - 使用完毕后点击「安全弹出」等待通知提示后再拔除设备。

---

## 源码构建

快速本地构建：
```bash
git clone https://github.com/Linoleic/BitLockerDroid.git
cd BitLockerDroid
./gradlew :app:assembleRelease
```
产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

完整依赖环境要求、NDK 独立构建与签名配置请参阅 **[源码构建指南 (BUILD_zh.md)](BUILD_zh.md)**。

---

## 常见问题解答

- **Q: 为什么拔下设备前一定要点击“安全弹出”？**  
  **A**: 驱动在弹出时会强制执行双级数据缓存落盘并清除文件系统的 Dirty Bit 标志位。若直接强拔，未清空的 Dirty Bit 会导致设备插回 Windows 电脑时弹出“此驱动器存在问题，需要扫描并修复”的提示。
- **Q: 恢复标识符（Recovery Key ID）有什么用？**  
  **A**: 同一存储设备在重置或多处备份时可能有多组 48 位恢复密钥。解锁弹窗会显示当前卷的恢复标识符，与微软账户网页端（[account.microsoft.com/devices/recoverykey](https://account.microsoft.com/devices/recoverykey)）查到的标识符比对一致后再输入，避免无效尝试。
- **Q: 为什么部分第三方应用在挂载后找不到 `/storage/XXXX-XXXX`？**  
  **A**: 全局挂载将 FUSE 挂载点注入至 PID 1 挂载命名空间，支持标准 POSIX 路径的应用（如 MT 管理器、Termux、VLC 等）可无障碍访问。部分仅依赖 Android MediaStore 媒体库的简易图库不会主动扫描外置非内建路径。
- **Q: 为什么免 Root 模式下的传输速率明显低于 Root 模式？**  
  **A**: 速率差距是由 Android 沙箱安全模型与 I/O 链路层级的本质差异决定的：  
  1. **驱动层级与分片开销**：Root 模式直接访问 Linux 内核块设备节点（`/dev/block/*`），走内核原生 `uas` / `usb-storage` 驱动与硬件 DMA；而免 Root 模式受限于 Android 权限沙箱，只能通过用户态 `usbfs` (`/dev/bus/usb/*`) 交互。受内核 `MAX_USBFS_BUFFER_SIZE` 限制，单次大块传输必须被切片为多个 16 KB 的 `UsbRequest`，引发密集的 `ioctl` 系统调用与上下文切换。  
  2. **协议机制差异**：内核驱动可开启 UAS 并发命令排队（NCQ）；免 Root 模式在用户态模拟传统的半双工 BOT 协议，每个扇区读写都必须经历 CBW（命令）、数据、CSW（状态确认）三阶段停等握手，无法做到总线零间隙传输。  
  3. **IPC 与 JNI 跨层开销**：免 Root 模式依赖 SAF（Storage Access Framework）提供文件访问，数据流经由 `ProxyFileDescriptor` 产生同步 Binder IPC 进程间通信；且底层扇区 I/O 需在 Native C 驱动与 Java 用户态之间频繁反向 JNI 回调。Root 模式则直接挂载至全局虚拟文件系统，应用直接通过标准 Linux 路径读写，彻底绕过 Binder 中继。  
  4. **闪存写放大与缺乏页缓存**：Root 模式享有 Linux 内核 Page Cache 与 I/O 调度器（合并小写入）；免 Root 模式下文件系统元数据更新（如 FAT32 的 FAT 表）会频繁即时落盘，在 U 盘主控层引发严重的物理擦写循环与写放大，导致小块与持续写入速率受限。  
  BitLockerDroid 已通过自研 4 级异步 URB 环形流水线、双缓冲乒乓写队列与 ARMv8 CE 硬件加速，将免 Root 读取吞吐推至 35 ~ 50 MB/s（普通 BOT 仅约 15 MB/s）；若追求极致速率（100+ MB/s 读取，30+ MB/s 写入），建议切换至 Root 模式。

- **Q: 使用时设备出现问题，该怎么反馈？**  
  **A**: 欢迎到 [GitHub Issues](https://github.com/Linoleic/BitLockerDroid/issues) 提交；**方便的话请附上诊断日志**，这是定位驱动与文件系统问题最快的方式。应用内进入「**设置 → 高级选项 → 系统环境与诊断 → 运行诊断日志**」，点「**复制日志**」后粘贴到 Issue 正文（也可另存为 `.txt` 文件作为附件上传）。日志**不含密码与恢复密钥本体**，但会包含卷 GUID、设备路径与文件名，提交前请先过一眼。系统版本会自动记录，无需特意填写机型。

---

## 开源协议与致谢

本项目采用 **GNU General Public License v2.0 (GPL-2.0)** 协议开源。详细协议文本见 [LICENSE](LICENSE)。

### 核心上游与依赖
- **BitLocker 解析核心**：[dislocker](https://github.com/Aorimn/dislocker) (GPL-2.0)
- **密码学与摘要计算**：[mbedtls](https://github.com/Mbed-TLS/mbedtls) (Apache-2.0 / GPL-2.0)
- **NTFS 文件系统引擎**：[libntfs-3g](https://github.com/tuxera/ntfs-3g) (GPL-2.0)
- **FAT32 文件系统引擎**：[FatFs](http://elm-chan.org/fsw/ff/00index_e.html) (ChaN)
