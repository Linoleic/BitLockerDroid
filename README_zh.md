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
- [理论性能与架构特征](#理论性能与架构特征)
- [实测数据展示](#实测数据展示)
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
| **理论性能特征** | 受限于用户态 usbfs 切片与半双工 BOT 停等协议；双缓冲流水线可平抑写入抖动 | 直通 Linux 内核原生块设备与硬件 DMA，支持 UAS 命令队列，理论逼近物理总线与硬件算力上限 |
| **挂载形式** | SAF DocumentsProvider（系统文件管理器） | SAF DocumentsProvider + 全局 FUSE 挂载 (`/storage/XXXX-XXXX`) |
| **脏卷修复与诊断** | 支持纯只读深度结构诊断与安全卸载 | 支持一键修复脏卷标志位 (Dirty Bit) 与底层结构深度体检 |
| **误报提示** | 通知监听服务自动过滤格式化误报 | 特权屏蔽系统格式化误报提示 |

---

## 理论性能与架构特征

BitLockerDroid 的数据吞吐与加解密效率由底层架构与硬件指令集协同保障：

- **ARMv8 CE 硬件密码学加速**：ARMv8-A 硬件密码学扩展指令集（`PMULL`, `AES`, `SHA2`）提供单周期硬件指令执行，彻底消除了传统软件查表与位移操作的大量 CPU 时钟周期消耗与缓存污染，理论上可将百余万轮 PBKDF2 密钥推导与扇区加解密效率提升数倍，实现近乎瞬时的密码验证与流式解密。
- **多线程并发块解密**：Native C 核心引擎内置多核动态工作池。在大块连续读取时，将密集型扇区密文按数据块分发至各 CPU 核心并发解密，理论计算吞吐可随可用 CPU 核心数线性扩展，消除大文件流媒体播放及批量传输时的单核计算瓶颈。
- **双缓冲异步写入流水线**：针对免 Root 模式下传统 BOT 协议的停等间隙，在用户态构建双缓冲乒乓流水线，将上层 SAF 数据接收与底层 USB 传输交叠并发执行，理论上能够最大化填充总线传输空窗，显著降低闪存主控的写放大效应。
- **Root 模式零中间层直通**：直接绕过 Android 权限沙箱与用户态 `usbfs` 切片限制，借由 Linux 内核块设备节点 (`/dev/block/*`)、Page Cache 与硬件 DMA 控制器进行数据交互，并原生支持 UAS (USB Attached SCSI) 并发命令排队，理论上可完全释放外接存储介质的物理总线带宽上限。

---

## 实测数据展示

*（暂时无内容）*

> [!NOTE]
> 实测基准测试需严格遵循 **[存储与加解密性能评估规范 (perf_eval.md)](perf_eval.md)** 建立的标准方法论执行（包括存储介质分级、预热剔除、Page Cache 强制隔离、N≥10 采样与哈希一致性校验）。标准化实测数据将在完成全矩阵多设备回归后补充。

---

## 核心特性

- **主流文件系统全功能透明读写**：内置裁剪优化的 `libntfs-3g`、`exFAT` 与 `FatFs` 引擎，完整支持 NTFS、exFAT 与 FAT32 分区的文件浏览、大文件传输（>4GB）、重命名及增删改查 (CRUD)。
- **原生系统集成与全局虚拟挂载**：
  - **标准 SAF 接入**：直接在 Android 原生“文件”应用中无缝管理外接卷；
  - **全局 POSIX 虚拟挂载 (Root)**：通过 FUSE 挂载至系统真实路径 (`/storage/XXXX-XXXX`)，供第三方应用（MT 管理器、Termux、多媒体播放器等）按绝对路径读写。
- **多重认证与硬件级凭据保险箱**：支持密码与 48 位恢复密钥；基于 Android Keystore 硬件级根密钥保护凭据；支持生物识别（指纹/面部）鉴权与动态窗口防窥保护 (`FLAG_SECURE`)。
- **脏卷一键修复与深度安全体检**：支持免 PC 一键重置因意外拔出导致的 Dirty Bit 脏标记，恢复读写；内置毫秒级只读底层元数据诊断与写拦截保护，防范结构性损坏。
- **单盘多分区拓扑与智能热插拔**：精准识别物理磁盘与多子分区拓扑，支持移动固态硬盘 (PSSD)；智能避让普通未加密外设，自动屏蔽系统格式化误报通知。
- **底层容灾备份与全盘镜像导出**：支持一键提取/急救回写 FVE 卷头元数据 (`.fvemeta`)；提供前台服务支持将卷导出为免密解密镜像 (`.img`) 或离线取证加密镜像 (`.raw`)。
- **全平台局域网无线共享 (Web & WebDAV)**：内置高效 WebDAV 服务端（支持 Windows/macOS/iOS 原生免客户端挂载）与极简深浅色 Web 门户（支持扫码速连、多媒体在线串流与批量上传）。

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
| **硬件密码加速** | ARMv8-A Cryptography Extensions (PMULL, AES, SHA2) | 运行时动态探测；硬件级高速推导与扇区解密；自动软件平滑降级 |
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
  BitLockerDroid 已通过自研 4 级异步 URB 环形流水线、双缓冲乒乓写队列与 ARMv8 CE 硬件加速，显著改善了免 Root 模式下的协议传输效率与连续读写表现；若追求发挥外设物理总线极限与更低访问延迟，建议切换至 Root 模式。

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
