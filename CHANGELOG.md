# 更新日志 / Changelog

本文档记录 BitLockerDroid（BitUnlocker）的所有版本变更，包括已发布的 Release 历史以及暂存中（Unreleased）的最新改进。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 规范，版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

This document records all changes to BitLockerDroid (BitUnlocker), including released release history and unreleased staging changes.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), adhering to [Semantic Versioning 2.0.0](https://semver.org/).

---

## [Unreleased] - 暂存更新 / Staging Changes

### 中文

#### 新增
- **物理磁盘一键全部弹出**：在物理磁盘汇总卡片（`DeviceGroupCard`）新增“全部弹出”按钮，支持一键安全卸载并弹出当前外接设备下的所有已挂载/已解锁卷。
- **多分区物理拓扑顺序对齐**：单盘多分区根据磁盘底层物理起始扇区（`startSector`）升序排序，严格还原物理空间布局。
- **多分区节点角标细化**：分区徽章清晰标明分区序号与系统块设备节点（例如：`分区 5 · sdg5`），便于准确定位各分区。
- **挂载状态常驻徽章**：盘符卡片右上角常驻 `已挂载` 与 `已解锁` 状态徽章，直观区分卷状态。
- **OTG 插盘后台静默就绪 (Silent Auto-Mount)**：插入外接存储时后台静默探测并自动解锁已保存凭据的分区，推送就绪通知，支持一键打开。

#### 优化
- **USB 传输协议位置优化**：将 USB 传输协议识别从基准测速对话框移出，收纳至已解锁卷的“详细硬件与卷参数”展开列表中，并提供一键复制按钮（如 `USB 3.0 (5 Gbps 高速)`）。
- **测速流程精简**：从基准测速流程中剔除冗余的“检测硬件链路速率…”阶段与延时，测速过程专注于顺序读写速率与 4K 随机延时。
- **拔盘即时主动刷新**：监听系统 USB 插拔广播事件，外接盘拔出时毫秒级主动同步设备列表，彻底消除拔出后界面顿挫与延迟消失的问题。
- **外设过滤与防抖节流**：自动忽略鼠标、键盘等非存储类 USB 外设，加入解密唤醒防抖节流，避免高频广播造成重复唤醒。

#### 修复
- **系统格式化提示屏蔽开关**：修复主界面设置中“屏蔽系统格式化提示”开关无法开启及点击无响应的问题，完善回调连接。

---

### English

#### Added
- **Eject All for Physical Storage Devices**: Added an "Eject All" action button on the physical device card (`DeviceGroupCard`) to safely unmount all unlocked volumes on the device in a single tap.
- **Physical Topology Partition Ordering**: Multi-partitions are now sorted in ascending order of their physical start sector (`startSector`), accurately mirroring on-disk layout.
- **Detailed Partition Badges**: Partition chips explicitly display the partition number and kernel block node name (e.g., `Partition 5 · sdg5`).
- **Persistent Volume Status Badges**: Added permanent `Mounted` / `Unlocked` status badges on the top right of volume cards for immediate state recognition.
- **Silent Auto-Mount on OTG Plug**: Automatically decrypts and mounts remembered BitLocker partitions in the background upon connection, posting a system notification for one-tap browsing.

#### Optimized
- **Relocated USB Protocol Detection**: Moved USB transmission protocol detection out of the benchmark speed test dialog into the "Hardware & Volume Details" expandable section on the volume card, with one-touch clipboard copy.
- **Streamlined Benchmark Engine**: Eliminated the hardware link speed detection phase from the benchmark runner, removing artificial progress delays.
- **Instant USB Detachment Refresh**: Listened directly to USB attach/detach broadcasts to instantly synchronize device lists, resolving UI hesitation when drives are unplugged.
- **Non-Storage Filtering & Throttling**: Automatically ignores non-storage peripherals (keyboards, mice, DACs) and throttles background unlock attempts against broadcast storms.

#### Fixed
- **Suppress System Format Prompt Toggle**: Fixed a callback wiring issue where the "Suppress System Format Prompt" toggle in Settings could not be enabled.

---

## [v1.0.6] - 2026-10-10

### 中文

#### 新增
- **存储硬件健康度与 S.M.A.R.T. 遥测**：为支持 NVMe/SCSI 直通的外接 SSD 固态硬盘引入底层 S.M.A.R.T. 遥测读取器，展示健康度百分比、写入总量、备用块、设备温度与临界警告。
- **智能硬件能力探测**：自动识别并对不支持 SMART 诊断指令的普通 U 盘隐藏该功能入口。
- **物理存储设备聚合卡片**：主页新增 `DeviceGroupCard`，统一归类展示同一物理设备下的所有分区卷、磁盘型号、总容量及健康状态。

#### 优化
- **底层 I/O 流水线与 SAF 双缓冲传输**：调优原生 C 驱动块管道，微调内核块队列参数，大幅提升 SAF 框架流式读取吞吐量。
- **设置中心滑动微卡顿消除**：重构设置中心渲染层级，整合性能调优选项为独立控制面板，实现 144Hz 满帧滑动。

#### 修复
- **高速移动固态硬盘 (PSSD) UAS 协议兼容**：增加 UAS (USB Attached SCSI) 传输协议适配与 SELinux 安全块设备分区枚举。

---

### English

#### Added
- **Storage Hardware Health & S.M.A.R.T. Telemetry**: Implemented low-level S.M.A.R.T. telemetry reader for external SSDs (NVMe/SCSI passthrough), providing health percentages, TBW, spare blocks, and temperatures.
- **Smart Capability Probing**: Automatically detects device capabilities and hides SMART options on unsupported USB thumb drives.
- **Physical Device Grouping Card**: Introduced `DeviceGroupCard` to group all partition volumes under their parent physical drive.

#### Optimized
- **Native Driver Pipeline & SAF Double-Buffering**: Tuned native C driver pipeline and kernel block queue parameters to enhance SAF streaming throughput.
- **Smooth 144Hz Settings Scrolling**: Eliminated micro-stutter in Settings and consolidated performance tuning toggles into an interactive dialog.

#### Fixed
- **PSSD UAS Protocol Compatibility**: Added UAS (USB Attached SCSI) protocol support and SELinux-safe partition traversal for high-speed portable SSDs.

---

## [v1.0.5] - 2026-10-06

### 中文

#### 新增
- **ARMv8 CE 硬件密码学加速**：原生调用 ARMv8 密码学扩展指令集（PMULL, AES, SHA2），将 104.8 万轮 PBKDF2 密钥派生耗时从 ~495ms 缩短至 ~104ms（4.75 倍加速）。
- **Non-Root USB 乒乓交叠写入流水线**：为 `UsbBlockDeviceWorker` 引入双阶段交叠写入流水线，大文件写入速率提升数倍。
- **USB 写入流水线配置开关**：在设置中心提供“USB 写入流水线优化”控制项，支持实时生效与自检。

#### 优化
- **144Hz 丝滑滑动专项调优**：全面采用 LazyColumn 虚拟化、独立 RenderNode 渲染层、隔离无障碍语义合并，消除主线程 Binder IPC 与重组开销。

#### 修复
- **底层稳定性与架构安全**：完善 JNI 异常守卫（`ExceptionCheck`）；消除 AES-XTS 未对齐指针强转警告；增加 x86_64 ABI 支持；清零内存敏感密码字符。

---

### English

#### Added
- **ARMv8 CE Hardware Acceleration**: Added native ARMv8 Cryptography Extensions (PMULL, AES, SHA2) support, accelerating 1.05M-round PBKDF2 derivation from ~495ms to ~104ms (4.75x speedup).
- **USB Overlapping Pipelined Write**: Implemented a 2-stage ping-pong overlapping write pipeline in Non-Root mode, greatly accelerating large file write throughput.
- **Pipelined Write Setting Toggle**: Added an interactive toggle for USB pipelined write under Advanced Settings.

#### Optimized
- **144Hz Smooth Scrolling**: Fully virtualized list rendering with LazyColumn, isolated RenderNode layers, and decoupled main-thread Binder IPC.

#### Fixed
- **Stability & Architecture**: Added JNI ExceptionCheck guards; removed unaligned pointer casts in AES-XTS; added x86_64 ABI; zeroed out password buffers in memory.

---

## [v1.0.4] - 2026-09-27

### 中文

#### 新增
- **局域网无线文件共享 (Web & WebDAV 双协议)**：内置轻量级 Web 门户与 WebDAV 服务端，跨设备免安装客户端秒级访问解密卷，支持多媒体在线串流播放与断点续传。
- **原生非加密外接驱动器支持**：支持直接浏览打开非加密外接盘（FAT32, exFAT），提供存储用量概览与直通打开。

#### 优化
- **Android 15+ 16 KB Page Size 内存分页对齐**：全面适配 Android 15 原生 16 KB Page Size 内存分页规范，通过严格校验。

#### 修复
- **无线共享安全性强化**：增加 HTTP PUT 报文长度防御、防路径穿越校验、防止伪造分离。

---

### English

#### Added
- **Wireless LAN Sharing (Web & WebDAV)**: Built-in Web browser portal and standard WebDAV server for cross-device access without third-party clients, supporting video streaming and range requests.
- **Unencrypted Drive Support**: Added native detection and browsing for unencrypted USB drives (FAT32, exFAT).

#### Optimized
- **Android 15+ 16 KB Page Size Alignment**: Verified and aligned all native shared libraries to support Android 15+ 16 KB memory page sizes.

#### Fixed
- **LAN Sharing Security Hardening**: Added PUT length checks, path traversal guards, and session authentication validations.

---

## [v1.0.3] - 2026-09-20

### 中文

#### 新增
- **生物识别凭据保险箱**：基于 Android Keystore 硬件安全模块加密存储 BitLocker 密码与恢复密钥，支持指纹/面容快速解锁。
- **安全防窥与二次认证**：查看已保存凭据前强制二次生物验证；动态激活 `FLAG_SECURE` 防录屏截屏。
- **FVE 卷头元数据容灾备份 (`.fvemeta`)**：支持备份 Sector 0 与三处 64KB FVE 元数据副本并支持导出底层镜像，防范硬件损坏。

---

### English

#### Added
- **Biometric Credential Vault**: Secured BitLocker passwords and recovery keys using Android Keystore hardware-backed encryption, enabling fingerprint and face unlock.
- **Privacy Shield & Secondary Auth**: Mandatory biometric prompt before viewing credentials, with dynamic `FLAG_SECURE` screen privacy protection.
- **FVE Metadata Disaster Recovery Backup**: Export and backup Sector 0 and three 64KB FVE metadata blocks (`.fvemeta`), along with raw image dumping.

---

## [v1.0.2] - 2026-09-12

### 中文

#### 新增
- **脏卷一键修复**：一键安全重置因未安全弹出造成的 NTFS、FAT32、exFAT unclean 软脏标记。
- **深度文件系统结构诊断**：毫秒级检验引导扇区、分配表一致性与根目录元数据，前置拦截结构性损坏风险。

---

### English

#### Added
- **One-Tap Dirty Volume Repair**: Safely resets unclean unmount flags on NTFS, FAT32, and exFAT volumes.
- **Deep Filesystem Integrity Diagnostics**: Performs non-destructive checks on boot sectors, allocation tables, and directory structures.

---

## [v1.0.1] - 2026-09-08

### 中文

#### 优化
- **独立卷配置与滑动流畅度**：修复 POSIX FUSE 挂载路径兼容性；各驱动器独立记忆只读模式状态；优化界面渲染性能。
- **双语文档分离**：分离中英文文档结构，增加跨语言互联导航。

---

### English

#### Optimized
- **Independent Volume State & Scrolling**: Fixed POSIX FUSE mount paths; remembered read-only settings per volume; smoothed UI rendering.
- **Separated Documentation**: Split Chinese and English documentation with cross-language navigation.

---

## [v1.0.0] - 2026-09-06

### 中文

#### 新增
- **首个正式发布版本**：
  - Android 平台免电脑全功能 BitLocker 加密存储管理器。
  - 支持解密 NTFS、FAT32、exFAT 格式 BitLocker 卷。
  - 支持用户密码与 48 位恢复密钥双重解密途径。
  - 支持 Root 驱动直通极速挂载 与 Non-Root 免 Root USB Host API 双运行模式。
  - 现代化 Material 3 界面设计，支持基准测速、只读保护、深浅色模式与 SAF 存储访问框架集成。

---

### English

#### Added
- **Initial Public Release**:
  - Full-featured BitLocker drive reader on Android without a PC.
  - Decrypts NTFS, FAT32, and exFAT BitLocker-encrypted partitions.
  - Dual unlock methods: User Password and 48-digit Recovery Key.
  - Dual operating modes: Root direct driver mount and Non-Root USB Host API mode.
  - Material 3 design, read-only protection, benchmark testing, dark/light themes, and SAF integration.
