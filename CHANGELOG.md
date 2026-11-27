# 更新日志 / Changelog

本文档记录各版本的重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v1.2.3] - 2026-11-28

### 中文

#### 安全

- **Root 守护进程与磁盘停盘防误伤加固**：移除 `/data/local/tmp` Root 二进制释放与执行，改为从应用只读原生库目录或 `0700` 私有目录执行；加固 `io_daemon` 块设备路径/符号链接校验与 `scsi_spindown_device` USB 白名单，避免内部 UFS 闪存被误操作。
- **SAF 与局域网 WebDAV 安全加固**：修复 `BitLockerDocumentsProvider.isChildDocument()` 跨卷越权、5 处只读模式绕过、路径遍历与 `call()` 外部 UID 越权调用；为局域网共享的 `.html`/`.svg`/`.xml` 强制 `attachment` + `Content-Security-Policy: sandbox` + `nosniff` 防同源 XSS，修复相对 `../` 遍历、CRLF 响应头注入与 `LOCK` XML 注入，并增加有界线程池与 Basic Auth 失败限流（429）。
- **Android 组件、凭据与 CI 加固**：将 `ACTION_USB_PERMISSION` 隔离为 `RECEIVER_NOT_EXPORTED`；凭据查看与管理弹窗强制 `SecureFlagPolicy.SecureOn`，切换自动解锁需生物识别确认；日志查看与复制先脱敏并标记敏感剪贴板；新增 `data_extraction_rules.xml` 阻止 D2D 迁移与云备份；收紧 `BootReceiver`、Release 签名回退与 GitHub Actions 密钥作用域。

#### 修复

- **修复 FUSE 虚拟盘符写入后元数据不更新与文件无法打开**：在 `FUSE_FLUSH` / `FUSE_FSYNC` / `FUSE_RELEASE` 及读取/列目录前强制同步刷盘，补齐 `O_TRUNC`、POSIX 覆盖式 `rename`、真实 FatFs 修改时间及 `attr_valid=0` / `entry_valid=0`，修复 `SIGUSR1` 信号重启与 FUSE ↔ SAF 双向缓存一致性。
- **修复原生 C/JNI 内存安全与加密边界**：修复 Tier-2 `io_daemon` 管道失步与超 4 MiB 分片；为 JNI VFS 句柄引入会话绑定令牌并修复锁序反转、UAF 与阻塞 I/O 期间持有 `GetPrimitiveArrayCritical` 导致的 GC 死锁；统一区间重叠写屏障、`volume_state` 校验、`ntfs3g_device` 只读/跨引导区拆分与 IEEE 1619 AES-XTS CTS。
- **修复文件系统解析与缓存并发缺陷**：修复 `CachedBlockSource` 清零竞态与 `clear()` 代际校验；修复 NTFS 误跳 Win32 长文件名、支持碎片化 `$MFT` 与簇尾截断；修复 exFAT `noFatChain` 目录限界、FAT32 高 4 位保留位掩码及大文件 10 万簇截断并加入环检测。
- **修复 USB Host 检测与 UI 稳定性**：修复 `isDeviceMountedBySystem()` 误判、`MSWIN4.1` 误劫持普通 FAT32、GPT 越界与 BOT 数据阶段 STALL 恢复及多盘热插拔误关会话；消除主线程阻塞 `su`/Socket、修复 `DisasterRecoveryDialog` 跨线程错误信息丢失及多处弹窗重入与取消竞态。

### English

#### Security

- **Root daemon & spindown hardening**: Stopped staging/executing root binaries in `/data/local/tmp`, running them only from `nativeLibraryDir` or owner-only `0700` app-private storage; added `O_NOFOLLOW` + `S_ISBLK` block-device checks in `io_daemon` and removed the overly broad `"target"` sysfs match in `scsi_spindown_device` to protect internal UFS storage.
- **SAF & LAN WebDAV hardening**: Fixed cross-volume authorization bypass in `BitLockerDocumentsProvider.isChildDocument()`, five read-only bypasses, path traversal, and external UID access to custom `call()` methods; hardened LAN share active content (`.html`/`.svg`/`.xml`) with `Content-Disposition: attachment`, `Content-Security-Policy: sandbox`, and `nosniff`, fixed relative `../` traversal, CRLF header injection, and `LOCK` XML escaping, and added bounded worker threads plus per-IP Basic Auth rate limiting (429).
- **Android components, credentials & CI hardening**: Isolated `ACTION_USB_PERMISSION` with `RECEIVER_NOT_EXPORTED`; enforced `SecureFlagPolicy.SecureOn` on credential dialogs and biometric checks when toggling auto-unlock; redacted viewed/copied logs with sensitive clipboard flags; added `data_extraction_rules.xml` to block cloud/D2D extraction; tightened `BootReceiver`, release signing defaults, and GitHub Actions secret scoping.

#### Fixed

- **Fixed stale metadata and unopenable files after copying/moving to FUSE mount**: Flushed buffered writes on `FUSE_FLUSH`/`FUSE_FSYNC`/`FUSE_RELEASE` and before reads/lookups, implemented `O_TRUNC`, POSIX overwrite `rename`, real FatFs `mtime`, zero-TTL FUSE attribute caching, non-restarting `SIGUSR1`, and bidirectional FUSE ↔ SAF cache coherency.
- **Fixed native C/JNI memory safety and crypto edge cases**: Fixed Tier-2 `io_daemon` pipe poisoning and 4 MiB chunking; bound JNI VFS handles to session tokens and fixed AB-BA lock ordering, UAFs, and `GetPrimitiveArrayCritical` across blocking I/O; enforced interval-overlap write barriers, `METADATA_STATE_ENCRYPTED` checks, `ntfs3g_device` read-only/split-header redirects, and IEEE 1619 AES-XTS CTS.
- **Fixed filesystem parsing and block-cache races**: Fixed `CachedBlockSource` use-after-zero and post-`clear()` repopulation races; fixed NTFS skipping Win32 filenames instead of DOS 8.3 and added fragmented `$MFT` support and non-resident slack-space clamping; fixed exFAT `noFatChain` directory bounds, FAT32 high-4-bit masking, and 100k-cluster truncation with Brent cycle detection.
- **Fixed USB Host detection and UI responsiveness**: Fixed `isDeviceMountedBySystem()` false positives, plain FAT32 (`MSWIN4.1`) hijacking, GPT buffer overflow, BOT `DATA_IN` STALL recovery, and multi-device USB detach; moved blocking `su`/socket work off the UI thread and fixed `DisasterRecoveryDialog` TLS error reporting, dump cancellation isolation, and dialog re-entry races.

## [v1.2.2] - 2026-11-14

### 中文

#### 新增

- 新增 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，集中声明 C/C++ 原生库及 Android/Kotlin 第三方开源依赖与许可证信息。

#### 变更

- 启用 Release 构建的 R8 代码混淆与资源裁剪（保留 `NativeBridge` JNI 桥接规则与行号映射），显著减小 APK 体积。
- 移除设置页「性能优化」入口与开关弹窗，改为默认始终开启硬件加密加速与 SAF 双缓冲。
- 统一 SAF 文档提供器根盘符图标与主页顶部盘符汇总图标。

#### 修复

- 修复 `useLegacyPackaging = false` 下从 APK 内提取 `libbitlocker_fuse.so`（`bitlocker_fuse`）失败的问题。
- 修复 FAT32 8.3 短文件名在仅含小写文件名或扩展名时未正确写入 `DIR_NTRes` 大小写标志的问题。

#### 安全

- 合入 FatFs（`ff16p2`，覆盖 CVE-2026-6682 / 6683 / 6687）与 `libntfs-3g`（截至 `2026.9.28`）上游安全补丁，加固畸形 FAT32 / exFAT / NTFS 镜像解析边界。

### English

#### Added

- Added [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) documenting native C/C++ and Android/Kotlin third-party open-source dependencies and licenses.

#### Changed

- Enabled R8 code minification and resource shrinking for release builds (keeping `NativeBridge` JNI bindings and `SourceFile`/`LineNumberTable` attributes), significantly reducing APK size.
- Removed the Performance Optimization settings entry and toggle dialog so hardware crypto acceleration and SAF double-buffering remain always enabled by default.
- Aligned the SAF `DocumentsProvider` root drive icon with the home screen drive summary icon.

#### Fixed

- Fixed extracting `libbitlocker_fuse.so` (`bitlocker_fuse`) from the APK when `useLegacyPackaging = false` is enabled.
- Fixed FAT32 8.3 short-name `DIR_NTRes` case-bit handling for lowercase names and extensions.

#### Security

- Applied upstream security patches for FatFs (`ff16p2`, addressing CVE-2026-6682 / 6683 / 6687) and `libntfs-3g` (through `2026.9.28`) to harden filesystem metadata parsing.

## [v1.2.1] - 2026-10-26

### 中文

#### 新增

- 新增“关闭卷”和“关闭全部卷”，关闭后保持连接，可查询磁盘健康；“弹出设备”统一负责整盘弹出。
- 显示 SAF 写入数量及关闭、弹出的实际阶段，失败时保留阶段与原因。
- 日志窗口支持导出 TXT，附环境摘要及最近崩溃记录，并隐藏已识别的敏感字段。

#### 修复

- 关闭全部卷后仍可弹出设备；严格检查缓存同步、停盘和下线结果，失败时保留重试入口。
- 修复已关闭写缓存、但不支持缓存同步命令的 U 盘无法弹出的问题。

### English

#### Added

- Close one or all volumes while keeping the device connected for health queries; use Eject device to close its volumes and eject the whole device.
- Show SAF write counts and actual close/eject stages, retaining the failed stage and reason.
- Export logs as TXT with an environment summary, recent crashes and recognized sensitive fields hidden.

#### Fixed

- Keep device eject available after all volumes are closed; validate cache sync, stop and offline results and retain retry on failure.
- Fix ejecting USB drives that report write caching disabled but do not support the cache synchronization command.

## [v1.2.0] - 2026-10-24

### 中文

#### 新增

- 主页统一“连接与授权”，集中管理模式、授权与 Root 环境。
- 磁盘健康新增查询诊断、数据校验及原始数据来源说明。

#### 变更

- 免 Root USB 改用同步传输；模式切换同步关闭卷并保持磁盘在线。
- S.M.A.R.T. 仅使用已验证桥接协议；免 Root 查询需先关闭该设备全部卷。

#### 安全

- 统一解锁、文件访问、虚拟挂载和凭据查看的生物识别授权，防止绕过验证。
- Root 模式仅过滤已确认 BitLocker 卷的系统修复提示，不再卸载卷或长期延后通知。

#### 修复

- 修复 USB 读写超时、分区识别及模式切换后的连接异常。
- 修复文件覆盖、并发写入和会话误关；写入或同步失败时阻止弹出和模式切换，停盘失败时明确提示。
- 修复保存凭据误删、自动解锁设置失效、卷列表查询阻塞及刷新崩溃。
- 修复 S.M.A.R.T. 大计数截断、缺失值误报与表格错位；不再解读坏页或推算未经验证的 SATA 指标。
- 修复窄屏和短横屏的标题、按钮及弹窗布局。

#### 移除

- 移除设置中的重复 Root 入口和免 Root USB 异步写入开关。

### English

#### Added

- Unify mode selection, authorization, and Root environment details in the home connection panel.
- Add query diagnostics, data validation, and raw-value sources to Disk Health.

#### Changed

- Use synchronous non-root USB transfers; flush and close volumes during mode changes while keeping the disk online.
- Use verified S.M.A.R.T. bridge protocols only; close all device volumes before a non-root query.

#### Security

- Enforce biometric authorization for unlocking, file access, virtual mounts, and saved credentials.
- Limit Root notification filtering to verified BitLocker repair prompts, without unmounting volumes or prolonged notification delays.

#### Fixed

- Fix USB I/O timeouts, partition detection, and connection failures after mode changes.
- Fix file overwrites, concurrent writes, and premature session closure; block ejects and mode changes on write or flush failure, and report spindown failures.
- Prevent unintended credential deletion and respect auto-unlock settings; fix blocked volume queries and refresh crashes.
- Fix truncated S.M.A.R.T. counters, misleading missing values, and table alignment; stop interpreting invalid pages or inferring unverified SATA metrics.
- Fix titles, buttons, and dialogs on narrow and short landscape screens.

#### Removed

- Remove duplicate Root settings entries and the non-root USB asynchronous-write toggle.

## [v1.1.1] - 2026-10-22

### 中文

#### 修复

- 同盘仍有其他分区被系统挂载或占用时，只关闭目标卷，不停止或移除整盘。
- 缓存同步失败时阻止磁盘脱机，并区分显示可拔出、同盘仍在使用和停盘失败三种结果。
- 修复同盘多卷并发弹出时，过早停盘导致其他卷掉线的问题。
- 修复免 Root 模式下残留会话导致最后一个卷弹出后未停盘的问题。

### English

#### Fixed

- Close only the target volume when sibling partitions are mounted or in use; keep the physical disk online.
- Block disk offlining if cache synchronization fails, and distinguish safe removal, disk still in use, and spindown failure.
- Fix concurrent ejects stopping a shared physical disk before other volumes have closed.
- Fix stale non-root sessions preventing spindown after the last volume is ejected.

## [v1.1.0] - 2026-10-13

### 中文

#### 新增

- Root 和免 Root 模式均支持弹出前同步缓存并停止磁盘；Root 模式在最后一个活动卷关闭后使设备脱机。
- 新增物理磁盘“全部弹出”按钮，以及卷的“已挂载”和“已解锁”状态徽章。
- 插盘后可自动解锁已保存凭据的卷，并通过通知一键打开。
- 新增硬件加速状态详情和密钥派生耗时测试。

#### 变更

- 按物理分区顺序排列卷，并显示分区序号和设备节点。
- 调整窄屏布局，避免容量、卷标识符、挂载路径和共享地址挤压或换行异常。
- 精简性能设置文案；将 USB 协议信息移至卷详情，测速专注于读写性能。
- 改进插拔后的列表刷新，忽略非存储外设并减少重复扫描。

#### 修复

- 修复旋转屏幕后重复加载、白屏和重新扫描的问题。
- 修复“屏蔽系统格式化提示”开关无响应的问题。
- 修正运行诊断日志按钮误显示“查看密码”的文案。

### English

#### Added

- Synchronize caches and stop disks before ejecting in root and non-root modes; offline the device in root mode after the last active volume closes.
- Add an Eject All action per physical disk and Mounted/Unlocked volume badges.
- Automatically unlock remembered volumes on connection, with a notification to open them.
- Add hardware acceleration details and key derivation timing tests.

#### Changed

- Sort volumes by physical partition order and show partition numbers and device nodes.
- Adapt narrow-screen layouts to prevent wrapping and overlap in capacities, volume identifiers, mount paths, and sharing addresses.
- Simplify performance settings, move USB protocol details to volume information, and focus benchmarks on read/write performance.
- Improve device list updates on connection changes, ignore non-storage peripherals, and reduce duplicate scans.

#### Fixed

- Fix repeated loading, blank screens, and rescanning after screen rotation.
- Fix the unresponsive Suppress System Format Prompt toggle.
- Correct the diagnostic log action incorrectly labeled View Password.

## [v1.0.6] - 2026-10-10

### 中文

#### 新增

- 支持读取兼容外接 SSD 的 S.M.A.R.T. 健康度、写入量、温度和警告信息。
- 自动隐藏不支持的 S.M.A.R.T. 功能。
- 按物理磁盘归类展示分区、型号、容量和健康状态。

#### 变更

- 优化底层传输和系统文件访问的读取性能。
- 改善设置页滚动流畅度，并集中展示性能调优选项。

#### 修复

- 改进高速移动固态硬盘的 UAS 协议兼容性和分区识别。

### English

#### Added

- Read S.M.A.R.T. health, write totals, temperature, and warnings from compatible external SSDs.
- Hide unsupported S.M.A.R.T. features automatically.
- Group partitions, model, capacity, and health information by physical disk.

#### Changed

- Improve low-level transfer and SAF read performance.
- Improve settings scrolling and consolidate performance options.

#### Fixed

- Improve UAS compatibility and partition detection for portable SSDs.

## [v1.0.5] - 2026-10-06

### 中文

#### 新增

- 支持 ARMv8 硬件密码学加速，缩短解锁时的密钥派生耗时。
- 新增免 Root USB 写入流水线及对应设置开关，提高大文件写入性能。
- 支持 x86_64 架构。

#### 变更

- 优化列表渲染，改善高刷新率屏幕上的滚动流畅度。

#### 修复

- 改进原生层异常处理和 AES-XTS 内存对齐。

#### 安全

- 使用后清零内存中的密码缓冲区。

### English

#### Added

- Support ARMv8 hardware cryptography to reduce key derivation time during unlock.
- Add a non-root USB write pipeline and settings toggle to improve large-file writes.
- Support the x86_64 architecture.

#### Changed

- Improve list rendering and scrolling on high-refresh-rate displays.

#### Fixed

- Improve native exception handling and AES-XTS memory alignment.

#### Security

- Clear password buffers from memory after use.

## [v1.0.4] - 2026-09-27

### 中文

#### 新增

- 支持通过浏览器和 WebDAV 共享文件，包括媒体串流和分段下载。
- 支持浏览非加密 FAT32、exFAT 外接盘，并查看存储用量。

#### 变更

- 适配 Android 15 及更高版本的 16 KB 内存分页。

#### 安全

- 增加上传长度检查、路径穿越防护和共享会话身份校验。

### English

#### Added

- Share files through a browser or WebDAV, including media streaming and range downloads.
- Browse unencrypted FAT32/exFAT external drives and view storage usage.

#### Changed

- Support 16 KB memory pages on Android 15 and later.

#### Security

- Add upload length checks, path traversal protection, and sharing session authentication checks.

## [v1.0.3] - 2026-09-20

### 中文

#### 新增

- 使用 Android Keystore 加密保存密码和恢复密钥，支持生物识别快捷解锁。
- 支持 FVE 卷头元数据备份（`.fvemeta`）和卷镜像导出。

#### 安全

- 查看保存的凭据前进行身份验证，并为敏感界面启用截屏和录屏保护。

### English

#### Added

- Encrypt saved passwords and recovery keys with Android Keystore and support biometric quick unlock.
- Support FVE metadata backups (`.fvemeta`) and volume image exports.

#### Security

- Authenticate before revealing saved credentials and protect sensitive screens from screenshots and recording.

## [v1.0.2] - 2026-09-12

### 中文

#### 新增

- 支持清除 NTFS、FAT32、exFAT 卷因未正常卸载留下的脏标记。
- 支持检查文件系统引导扇区、分配表和目录结构。

### English

#### Added

- Clear unclean-unmount flags on NTFS, FAT32, and exFAT volumes.
- Diagnose filesystem boot sectors, allocation tables, and directory structures.

## [v1.0.1] - 2026-09-08

### 中文

#### 变更

- 按卷记忆只读设置，并改善界面滚动性能。
- 分离中英文项目说明，并增加语言切换链接。

#### 修复

- 修复 POSIX FUSE 挂载路径兼容问题。

### English

#### Changed

- Remember read-only settings per volume and improve scrolling performance.
- Separate Chinese and English project documentation with language-switching links.

#### Fixed

- Fix POSIX FUSE mount path compatibility.

## [v1.0.0] - 2026-09-06

### 中文

#### 新增

- 首个正式版本，支持在 Android 上访问 NTFS、FAT32、exFAT 格式的 BitLocker 卷。
- 支持密码和 48 位恢复密钥解锁，以及 Root 和免 Root USB 两种模式。
- 提供系统文件访问、虚拟挂载、基准测速、只读设置和深浅色主题。

### English

#### Added

- Initial public release with Android access to NTFS, FAT32, and exFAT BitLocker volumes.
- Unlock with passwords or 48-digit recovery keys in root or non-root USB mode.
- Provide SAF access, virtual mounting, benchmarks, read-only settings, and light/dark themes.

[Unreleased]: https://github.com/Linoleic/BitLockerDroid/compare/v1.2.1...HEAD
[v1.2.1]: https://github.com/Linoleic/BitLockerDroid/compare/v1.2.0...v1.2.1
[v1.2.0]: https://github.com/Linoleic/BitLockerDroid/compare/v1.1.1...v1.2.0
[v1.1.1]: https://github.com/Linoleic/BitLockerDroid/compare/v1.1.0...v1.1.1
[v1.1.0]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.6...v1.1.0
[v1.0.6]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.5...v1.0.6
[v1.0.5]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.4...v1.0.5
[v1.0.4]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.3...v1.0.4
[v1.0.3]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.2...v1.0.3
[v1.0.2]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.1...v1.0.2
[v1.0.1]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.0...v1.0.1
[v1.0.0]: https://github.com/Linoleic/BitLockerDroid/releases/tag/v1.0.0
