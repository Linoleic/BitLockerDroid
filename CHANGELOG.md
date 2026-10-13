# 更新日志 / Changelog

本文档记录 BitLockerDroid（BitUnlocker）的所有版本变更，包括已发布的 Release 历史以及暂存中（Unreleased）的最新改进。
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 规范，版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

This document records all changes to BitLockerDroid (BitUnlocker), including released release history and unreleased staging changes.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), adhering to [Semantic Versioning 2.0.0](https://semver.org/).

---

## [Unreleased] - 暂存更新 / Staging Changes

### 中文

#### 新增
- **物理块设备完整停机与下线脱机 (Physical Spindown & Safe Offline)**：
  - 彻底根治外接 PSSD 移动固态硬盘（如 Realtek RTL9210 / JMicron JMS583 / ASMedia 等桥接方案）每次拔出时 NVMe SMART 异常断电计数（`unsafe_shutdowns`）不断递增的问题。
  - 在“安全弹出”与“全部弹出”时完整补全了硬件级停机下线序列：
    1. 系统级脏页刷新：执行全系统 `sync` 与 `ioctl(BLKFLSBUF)`，清空内核与块层缓冲区。
    2. SCSI 缓存强制落盘：下发 SCSI `SYNCHRONIZE CACHE (10)` (`0x35`) 指令，强制桥接固件与 SSD 刷写内部 DRAM / SLC Cache 缓存。
    3. SCSI 物理停机睡眠：下发 SCSI `START STOP UNIT` (`0x1B`, `START=0`) 指令，触发 SSD 控制器完成 NVMe 关机通告（`CC.SHN`）并进入 Standby 低功耗模式。
    4. 内核电源管理接管：动态开启 `/sys/block/.../device/scsi_disk/*/manage_start_stop = 1`。
    5. SCSI 节点干净脱机：向 `/sys/block/.../device/delete` 写入 `1`，通知内核注销设备节点，安全脱机。
    6. USB 端口低功耗休眠：联动 USB 核心写入 `power/control = auto`，引导 USB 端口进入 U3 Suspend。
  - **多分区物理拓扑安全隔离**：单盘多分区环境下，仅在弹出该物理磁盘上的**最后一个活动分区块**（或一键全部弹出）时才执行物理停机与脱机，防止单卷卸载影响同盘其余活动分区。
  - **免 Root USB 主机模式全面覆盖**：在 Non-Root 模式下通过 USB BOT 通道下发 SCSI 缓存同步与停机指令。
- **物理磁盘一键全部弹出**：在物理磁盘汇总卡片（`DeviceGroupCard`）新增“全部弹出”按钮，支持一键安全卸载并弹出当前外接设备下的所有已挂载/已解锁卷。
- **多分区物理拓扑顺序对齐**：单盘多分区根据磁盘底层物理起始扇区（`startSector`）升序排序，严格还原物理空间布局。
- **多分区节点角标细化**：分区徽章清晰标明分区序号与系统块设备节点（例如：`分区 5 · sdg5`），便于准确定位各分区。
- **挂载状态常驻徽章**：盘符卡片右上角常驻 `已挂载` 与 `已解锁` 状态徽章，直观区分卷状态。
- **OTG 插盘后台静默就绪 (Silent Auto-Mount)**：插入外接存储时后台静默探测并自动解锁已保存凭据的分区，推送就绪通知，支持一键打开。

#### 优化
- **小分辨率竖屏自适应布局重构**：
  - 针对手机窄屏（宽度 < 600dp，如 360dp - 412dp）深度重构卡片响应式布局，调整断点阈值以全面适配竖屏握持。
  - 解决物理磁盘卡片容量文本在动作按钮挤压下逐字竖向换行折行（`9 \n 3 \n 1 \n . \n 5 \n G \n B`）的问题，整合为单行防溢出显示，并将 S.M.A.R.T. 与全部弹出按钮自适应下沉至专属操作行。
  - 优化盘符卡片中 36 字符卷 GUID 与 45 字符恢复密钥标识符的字号与溢出省略规则，避免卡片内部文字越界与双字符断行。
  - 适配 POSIX 虚拟挂载与局域网无线共享在窄屏下的操作布局，防止按钮与地址挤压重叠。
- **性能优化设置文案精简与底层指令集状态诊断细化**：
  - 精简性能优化设置与硬件加速对话框中冗长生涩的技术文案（流水线异步并发写、SAF 双重缓冲管道），统一使用“支持 / 不支持 / 已激活 / 已停用”等简洁明晰的状态徽章。
  - 深度扩充底层硬件密码学指令集诊断信息：在 JNI 原生层扩充 `native_getHardwareAesDetails` 与 `native_getHardwareSha2Details`，直观呈现内核 `HWCAP` 特性掩码、底层执行指令集族（`AESE/AESD/PMULL`、`SHA256H/SHA256SU0`）以及自检向量校验状态。
  - 在“性能优化”与“系统环境与诊断”对话框中引入实时硬件微秒级密钥拉伸基准测试（`nativeBenchmarkKeyStretching`），显示 1000 轮密钥拉伸实测开销（例如：`135µs`），为排查移动端 CPU 硬件密码学加速性能提供精准依据。
- **USB 传输协议位置优化**：将 USB 传输协议识别从基准测速对话框移出，收纳至已解锁卷的“详细硬件与卷参数”展开列表中，并提供一键复制按钮（如 `USB 3.0 (5 Gbps 高速)`）。
- **测速流程精简**：从基准测速流程中剔除冗余的“检测硬件链路速率…”阶段与延时，测速过程专注于顺序读写速率与 4K 随机延时。
- **拔盘即时主动刷新**：监听系统 USB 插拔广播事件，外接盘拔出时毫秒级主动同步设备列表，彻底消除拔出后界面顿挫与延迟消失的问题。
- **外设过滤与防抖节流**：自动忽略鼠标、键盘等非存储类 USB 外设，加入解密唤醒防抖节流，避免高频广播造成重复唤醒。

#### 修复
- **运行诊断日志徽章文案修正**：修复“系统环境与诊断”对话框中“运行诊断日志”操作项的右侧状态徽章错误显示为“查看密码”的文案混淆问题，修正为“查看日志”。
- **横竖屏旋转主页重复加载与防重载**：为 `BitLockerSettingsActivity` 与 `UnlockDialogActivity` 声明完整的 `android:configChanges`（`orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode`），并在 Activity 中接管 `onConfigurationChanged` 旋转回调及记忆当前选中的 Tab 状态；彻底消除在屏幕旋转/横竖屏切换时 Activity 重新创建、盘符列表白屏重扫及刷新闪烁的问题。
- **系统格式化提示屏蔽开关**：修复主界面设置中“屏蔽系统格式化提示”开关无法开启及点击无响应的问题，完善回调连接。

---

### English

#### Added
- **Full Physical Block Device Spindown & Safe Offline**:
  - Completely resolves the issue where external portable SSDs (especially Realtek RTL9210, JMicron JMS583, and ASMedia NVMe bridges) suffered incrementing SMART `unsafe_shutdowns` counters every time they were unplugged.
  - Implements the complete hardware spindown and offlining sequence during "Safe Eject" and "Eject All":
    1. System-wide dirty buffer writeback via `sync` and `ioctl(BLKFLSBUF)`.
    2. SCSI cache synchronization via SCSI `SYNCHRONIZE CACHE (10)` (`0x35`), ensuring internal DRAM/SLC caches are flushed to NAND.
    3. SCSI physical spindown via SCSI `START STOP UNIT` (`0x1B`, `START=0`), signaling the SSD controller to perform clean NVMe shutdown notification (`CC.SHN`) and transition to standby low-power mode.
    4. Kernel power management enablement via `/sys/block/.../device/scsi_disk/*/manage_start_stop = 1`.
    5. Clean SCSI block device offlining and removal via `/sys/block/.../device/delete = 1`.
    6. USB port power suspension via `/sys/bus/usb/devices/.../power/control = auto` and autosuspend delay `0` (U3 Suspend).
  - **Multi-Partition Safety Isolation**: On physical drives hosting multiple partitions, physical spindown and offlining is only executed when ejecting the **last remaining active volume session** on that physical disk (or when invoking "Eject All"), safeguarding all other concurrently active partitions.
  - **Full Non-Root USB Host Mode Coverage**: Issues SCSI cache synchronization and spindown unit commands via USB BOT bulk transfer before closing device handles in non-root mode.
- **Eject All for Physical Storage Devices**: Added an "Eject All" action button on the physical device card (`DeviceGroupCard`) to safely unmount all unlocked volumes on the device in a single tap.
- **Physical Topology Partition Ordering**: Multi-partitions are now sorted in ascending order of their physical start sector (`startSector`), accurately mirroring on-disk layout.
- **Detailed Partition Badges**: Partition chips explicitly display the partition number and kernel block node name (e.g., `Partition 5 · sdg5`).
- **Persistent Volume Status Badges**: Added permanent `Mounted` / `Unlocked` status badges on the top right of volume cards for immediate state recognition.
- **Silent Auto-Mount on OTG Plug**: Automatically decrypts and mounts remembered BitLocker partitions in the background upon connection, posting a system notification for one-tap browsing.

#### Optimized
- **Responsive Layout for Small Portrait Screens**:
  - Deeply refactored responsive layouts across drive cards for mobile portrait viewports (width < 600dp, e.g. 360dp - 412dp) with Material 3 compact breakpoints.
  - Resolved character-by-character vertical text wrapping bugs on physical disk capacity subtitles by consolidating info strings and moving S.M.A.R.T. & Eject All buttons to a dedicated action row.
  - Adjusted font sizes and ellipsis rules for 36-char volume GUIDs and 45-char Recovery Key IDs to prevent two-character orphan line breaks.
  - Adapted POSIX virtual mount and LAN wireless share cards to wrap URL and action controls gracefully on compact displays.
- **Concise Performance Descriptions & Detailed Instruction Set Diagnostics**:
  - Streamlined verbose technical descriptions for USB pipelined writing and SAF double-buffering into clean, concise badges (Supported, Unsupported, Active, Disabled).
  - Deeply expanded low-level cryptographic hardware instruction set diagnostics: extended JNI native helpers (`native_getHardwareAesDetails`, `native_getHardwareSha2Details`) to report kernel `HWCAP` hex bitmasks, execution instruction subsets (`AESE/AESD/PMULL`, `SHA256H/SHA256SU0`), and vector self-test status.
  - Added real-time microsecond key-stretching benchmarks (`nativeBenchmarkKeyStretching`) in both "Performance Optimization" and "System Environment & Diagnostics" dialogs, measuring actual 1000-iteration key derivation latency (e.g. `135µs`) for detailed hardware profiling.
- **Relocated USB Protocol Detection**: Moved USB transmission protocol detection out of the benchmark speed test dialog into the "Hardware & Volume Details" expandable section on the volume card, with one-touch clipboard copy.
- **Streamlined Benchmark Engine**: Eliminated the hardware link speed detection phase from the benchmark runner, removing artificial progress delays.
- **Instant USB Detachment Refresh**: Listened directly to USB attach/detach broadcasts to instantly synchronize device lists, resolving UI hesitation when drives are unplugged.
- **Non-Storage Filtering & Throttling**: Automatically ignores non-storage peripherals (keyboards, mice, DACs) and throttles background unlock attempts against broadcast storms.

#### Fixed
- **Diagnostic Log Action Badge Text**: Corrected the action badge in the "System Environment & Diagnostics" dialog for "Running Diagnostic Logs", which previously erroneously displayed "View Password" instead of "View Logs".
- **Prevent Activity Recreation & Drive Rescanning on Orientation Change**: Configured `android:configChanges` (`orientation|screenSize|smallestScreenSize|screenLayout|density|uiMode`) for `BitLockerSettingsActivity` and `UnlockDialogActivity`, handled `onConfigurationChanged`, and preserved selected navigation tabs via `rememberSaveable`; completely eliminating screen rotation activity teardown, empty white-flash reloading, and repetitive block device rescanning.
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
