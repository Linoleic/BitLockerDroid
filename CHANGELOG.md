# 更新日志 / Changelog

本文档记录各版本的重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 中文

#### 新增

- 主页和设置统一连接与授权入口，支持模式选择、授权检测和 USB 授权。

#### 变更

- 免 Root USB 读写改为每次最多 16 KB 的同步传输，停用多请求并行传输。
- SMART 查询按已验证桥接芯片选择协议；RTL9210 需通过控制器标识和日志校验，未知 USB 不发送专用探测命令。
- 免 Root 主页仅识别可查询设备；安全关闭卷后，才可手动查询 SMART，避免干扰解锁和读写。
- SMART 区分“尚未确认支持”和“不支持”，避免将设备占用或查询失败误报为硬件不支持。
- Root 环境详情与通知屏蔽保留在高级设置；模式切换仍沿用安全关闭后再重启流程。

#### 安全

- 修复开启生物识别保护后，文件访问和虚拟挂载仍可能绕过验证、自动解锁的问题。
- 快捷解锁、查看和复制保存的凭据遵守生物识别保护开关；切换解锁目标后，旧验证结果失效。

#### 修复

- 切换模式时暂停扫描和新解锁，等待正在进行的操作结束后再安全关闭卷。
- 调试解锁接口未指定保存选项时不再删除已有凭据；自动和快捷解锁保留原保存项，快捷解锁成功后正常提示。
- 修复设置分组和驱动器卡片隐藏子控件的无障碍信息，使入口、开关和解锁按钮可被识别。
- 自动解锁统一遵守全局开关、单卷开关和手动锁定设置。
- 修复不同卷的同名文件并发写入时互相干扰的问题。
- 修复文件写入不完整、空文件覆盖后仍残留旧内容的问题。
- 修复免 Root 模式下分区序号显示错误，以及重复扫描误判空盘、断开卷连接的问题。
- 修复读取卷信息失败时刷新界面可能导致应用崩溃的问题。
- USB 读写通道超时或响应不完整时终止会话，避免继续使用错位响应或返回补零数据。
- 列出卷和文件时不再初始化写入组件，避免大容量卷长时间等待。
- 免 Root 连接显式选择 USB BOT 接口，修复从 Root 模式切回后可能沿用 UAS 接口的问题。
- 重复解锁已打开的卷时复用现有会话，避免误关 USB 通道、导致读取或安全关闭失败。
- 写入未完成、刷盘失败或虚拟挂载未退出时，停止弹出和模式切换并提示原因；等待写入超时时保留卷会话。

### English

#### Added

- Unify connection and authorization entry points on the home screen and in settings, with mode selection, authorization checks, and USB access requests.

#### Changed

- Use synchronous USB transfers of up to 16 KB in non-root mode, replacing multiple queued requests.
- Select SMART protocols by verified bridge identity. Validate RTL9210 controller identity and logs; do not send vendor probes to unknown USB devices.
- Offer non-root SMART queries only after volumes are safely closed. Home discovery only identifies candidate devices without issuing probe commands.
- Distinguish unconfirmed SMART support from unsupported hardware when a device is busy or a query fails.
- Keep root environment details and notification suppression in advanced settings; mode switching still follows the safe-close-and-restart flow.

#### Security

- Fix file access and virtual mounting bypassing authentication when biometric protection is enabled.
- Respect the biometric protection switch for quick unlock and when revealing or copying saved credentials. Discard old authentication results when the unlock target changes.

#### Fixed

- Pause scans and new unlocks during mode switching, and wait for ongoing operations before safely closing volumes.
- Preserve saved credentials when debug unlock calls omit the save option. Automatic and quick unlocks keep existing entries, and quick unlocks show success feedback.
- Restore accessibility information for controls inside settings groups and drive cards, including unlock buttons.
- Apply global, per-volume, and manual-lock settings consistently to automatic unlocking.
- Fix concurrent writes to identically named files on different volumes interfering with each other.
- Fix incomplete writes and old content remaining after an empty overwrite.
- Fix incorrect partition numbers in non-root mode and repeated scans incorrectly treating encrypted drives as empty and closing their connections.
- Fix crashes during UI refresh when volume information cannot be read.
- Terminate USB I/O sessions on timeout or incomplete responses instead of reusing misaligned responses or returning zero-filled data.
- Avoid initializing filesystem writers while listing volumes and files, preventing long waits on large volumes.
- Explicitly select the USB BOT interface in non-root mode instead of retaining UAS after switching from root mode.
- Reuse live sessions for duplicate unlock requests instead of closing their USB workers and breaking reads or safe closure.
- Stop ejects and mode switches if writes are unfinished, flushing fails, or a virtual mount has not exited. Show the cause and keep the volume session open on write timeout.

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

[Unreleased]: https://github.com/Linoleic/BitLockerDroid/compare/v1.1.1...HEAD
[v1.1.1]: https://github.com/Linoleic/BitLockerDroid/compare/v1.1.0...v1.1.1
[v1.1.0]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.6...v1.1.0
[v1.0.6]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.5...v1.0.6
[v1.0.5]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.4...v1.0.5
[v1.0.4]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.3...v1.0.4
[v1.0.3]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.2...v1.0.3
[v1.0.2]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.1...v1.0.2
[v1.0.1]: https://github.com/Linoleic/BitLockerDroid/compare/v1.0.0...v1.0.1
[v1.0.0]: https://github.com/Linoleic/BitLockerDroid/releases/tag/v1.0.0
