# BitUnlocker

<p align="center">
  <b>A native Android application to unlock and browse Microsoft BitLocker encrypted drives (NTFS, FAT32, exFAT) on mobile devices.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL%20v2.0-blue.svg" alt="License: GPL-2.0"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Root-KernelSU%20%7C%20Magisk%20%7C%20APatch-orange.svg" alt="Root Required">
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%20(NDK)-lightgrey.svg" alt="Kotlin & C">
</p>

---

## 📖 简介 / Introduction

**BitUnlocker** 是一款让 Android 设备能够在脱离电脑的情况下，直接读取 Windows **BitLocker 加密盘**（通过 USB OTG 连接的 U 盘、移动硬盘或 SD 卡）的应用。

只需将加密盘接入手机，打开应用点击 **Scan** 识别盘符，输入**用户密码**或 **48 位恢复密钥**解锁，解密后的内容即可直接在 Android 系统原生文件管理器（DocumentsUI）中像普通存储卷一样直接浏览与读取。

> 💡 **轻量独立**：已完全脱离 Xposed/LSPosed 框架依赖，作为**纯普通 APK** 运行，只需系统拥有 Root 权限（KernelSU、Magisk 或 APatch）以获取底层块设备读取能力。

---

## ✨ 核心特性 / Features

- 📁 **全主流文件系统读写与增删改 (CRUD)**：
  - **NTFS**：嵌入纯 C 裁剪版 **`libntfs-3g`**，支持完整文件与文件夹创建、修改写入、重命名、截断追加与安全删除；内置扇区级硬件安全写屏障，彻底阻断对 FVE 元数据区的越界修改。
  - **FAT32**：嵌入纯 C 裁剪版 **`FatFs`**，支持完整的读写操作与长文件名（LFN）。
  - **exFAT**：支持 Win10 1903+ 卷头自适应容错（sector_size=0 fallback）、FAT 簇链与 Entry Set (0xC0/0xC1) 文件名拼接只读解析。
- 🌐 **全局 POSIX 虚拟挂载 (`/storage/XXXX-XXXX`)**：
  - 内置高性能用户态 FUSE 守护进程（运行于 PID 1 全局命名空间），将解密卷模拟为 Android 原生 Vold USB OTG 存储盘。
  - 路径命名采用规范的 GUID 前 8 位十六进制格式（如 `/storage/78F0-B809`）。
  - **第三方应用全面兼容**：支持 MT 管理器、Termux、影音播放器等直接通过真实绝对路径读写与创建文件。
- 🔐 **主流加密算法支持**：
  - **AES-CBC-128 / AES-CBC-256**（`0x8002`，Windows 7/8/To-Go 兼容模式）。
  - **AES-XTS-128 / AES-XTS-256**（`0x8004`，Windows 10/11 默认模式）。
- 🔑 **双重解锁认证方式**：
  - **用户密码**：支持完整 PBKDF2/SHA-256 密钥拉伸（1,048,576 轮）。
  - **48 位恢复密钥**：规范实现了 8 组数字恢复密码转换与校验。
- 📂 **深度系统集成**：
  - 基于 Android 原生 `DocumentsProvider` (SAF) 实现，解密卷直接在系统文件管理器左侧导航抽屉中显示，并支持在 DocumentsUI 中直接新建、编辑与删除文件。
  - 设置中心提供 **「Open」** 一键直达按钮与 **「POSIX 挂载路径」** 一键复制。
  - 具备 **Volume Serial 换盘感知**：即使用户插拔更换不同加密盘而复用同一系统节点，也能自动强制刷新文件管理器缓存。
- 🛡️ **极简交互与权限控制**：
  - **按需手动 Scan 驱动**：无无谓的后台周期常驻，插盘按需主动扫描。
  - **独立安全选项**：「记住密码」与「扫描时自动解锁」互相解耦，密码使用 Android Keystore 硬件级加密保存。
  - **16KB 内存页对齐**：全库严格遵循 Android 15+ 16KB 页对齐规范 (`-Wl,-z,max-page-size=16384`)。

---

## 🛠️ 工作原理 / Architecture

```
                       USB OTG 加密设备
                              │
                              ▼
                     /dev/block/vold/*
                              │
┌─────────────────────────────┼─────────────────────────────┐
│ 用户层 (App UI)             │ 底层服务与安全层            │
│                             ▼                             │
│  [Scan 扫描] ──────► [BitLockerDetector]                  │
│                             │ 探测 -FVE-FS- 签名          │
│                             ▼                             │
│  [解锁弹窗]  ──────► [DislockerCore (JNI)]                │
│  (密码/恢复密钥)             │ mbedtls 2.28.8 密钥派生    │
│                             │ AES-CCM 解密 VMK ➔ FVEK     │
│                             ▼                             │
│                     [按需扇区解密引擎]                     │
│                             ▲                             │
│                             │ Root 权限提权读取加密扇区   │
│                             │ (KernelSU / Magisk / APatch)│
│                             ▼                             │
│                [统一 VolumeReader 接口]                   │
│             ┌───────────────┼───────────────┐             │
│             ▼               ▼               ▼             │
│        NtfsReader      Fat32Reader     ExFatReader        │
│             └───────────────┬───────────────┘             │
│                             ▼                             │
│               [BitLockerDocumentsProvider]                │
└─────────────────────────────┼─────────────────────────────┘
                              │
                              ▼
                   系统原生文件管理器 (DocumentsUI)
                   (显示真实卷标，像普通 U 盘一样直接浏览)
```

---

## 📋 兼容性与技术规格 / Specifications

| 维度 | 支持范围 | 说明 |
|---|---|---|
| **Android 版本** | Android 8.0+ (API 26+) | 在 Android 13、14、15 (HyperOS / AOSP) 真机测试通过 |
| **Root 方案** | KernelSU, Magisk, APatch | 用于授权应用进程读取 `/dev/block/vold/*` 块设备 |
| **文件系统** | NTFS, FAT32, exFAT | 统一实现 `VolumeReader` 接口，仅支持只读浏览 |
| **加密算法** | AES-XTS (128/256-bit), AES-CBC (128/256-bit) | 暂不支持老式 Elephant Diffuser (`0x8000`/`0x8001`) |
| **认证凭据** | 密码、48 位数字恢复密钥 | 恢复密钥格式形如 `xxxxxx-xxxxxx-...-xxxxxx` |
| **扇区规格** | 512 字节逻辑扇区 | USB/SD 常见规格 |

---

## 🚀 快速上手 / Quick Start

### 1. 安装与授权
1. 从 [Releases](../../releases) 下载最新版的 `app-debug.apk`（或参考 [BUILD.md](BUILD.md) 自行编译）。
2. 在已获得 Root 权限的 Android 设备上安装应用。
3. 打开 **KernelSU** / **Magisk** / **APatch** 管理器，为 **BitUnlocker** 授予 Root (su) 权限。

### 2. 解锁与浏览
1. 使用 OTG 适配器将 BitLocker 加密 U 盘 / 移动硬盘连接至手机。
2. 打开 **BitUnlocker** 应用，点击右上角 **「Scan」** 按钮主动检测设备。
3. 检测到加密卷后，卷将出现在「Locked volumes detected」列表中，点击 **「Unlock」**。
4. 输入解锁密码或 48 位恢复密钥。
5. 解锁成功后，卷会进入「Unlocked volumes」列表：
   - 点击 **「Open」** 按钮：直接调起系统原生文件管理器进入该盘根目录；
   - 或直接打开系统自带的「文件 / 文件管理器」，在侧边栏存储列表中找到对应的卷标（如 `BitLocker` 或盘名）。
6. 使用完毕后，可在应用内点击 **「Lock」** 锁定卷并安全拔出设备。

---

## 🏗️ 源码编译 / Build from Source

本项目使用标准 Gradle + Android NDK (C/CMake) 混合构建。

**基础环境要求**：
- JDK 17
- Android SDK (compileSdk = 34, build-tools = 34.0.0)
- Android NDK (r26d / 26.3.11579264)
- CMake 3.22.1+

**编译 Debug APK**：
```bash
git clone https://github.com/Linoleic/BitUnlocker.git
cd BitUnlocker
./gradlew :app:assembleDebug
```
产物位置：`app/build/outputs/apk/debug/app-debug.apk`。

👉 完整构建环境配置、环境变量导出与 Host 端测试工具编译指南，请参阅 **[BUILD.md](BUILD.md)**。

---

## 🔒 安全与隐私设计 / Security & Privacy

1. **按需流式解密**：文件解密全程在内存中完成，**绝不落盘缓存明文文件**。
2. **凭据安全**：
   - 当用户启用「Remember password」时，密码通过 **Android Keystore**（硬件安全模块/TEE 保护的 AES-GCM-256）加密持久化。
   - 用户可随时在设置中取消勾选并清除记住的密码。
3. **内存即时清零**：Native 层解锁会话在关闭（Lock 或拔盘）时，会主动覆盖并清零内存中的 VMK、FVEK 及加密上下文结构体。
4. **无外网请求**：本应用不包含任何网络请求逻辑，纯本地离线运行。

---

## ⚠️ 常见问题与局限 / FAQ & Limitations

- **Q: 为什么必须需要 Root 权限？**
  - **A**: Android 系统具备极严格的 SELinux 存储隔离策略，普通应用 domain (`untrusted_app`) 无法直接读取 `/dev/block/*` 原始块设备，只有具备 root 权限才可安全读取加密的原始扇区数据。
- **Q: 是否支持向 BitLocker 盘写入或修改文件？**
  - **A**: 目前仅支持**只读（Read-Only）**。BitLocker 与 NTFS/exFAT 复杂的元数据写入机制在移动端存在损坏磁盘数据结构的风险，出于数据安全考虑目前仅提供只读浏览与复制。
- **Q: 为什么打开较多文件的大目录时速度偏慢？**
  - **A**: 当前版本通过 `su dd` 逐扇区按需提权读取底层设备。针对此项性能问题，后续版本将引入常驻管道批量预读缓存机制以大幅提升浏览速度。

---

## 🤝 贡献 / Contributing

欢迎提交 Issue 报告 Bug 或提出新特性建议！在提交代码前，请先阅读 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

---

## 📄 开源协议与致谢 / License & Acknowledgments

- **核心解密逻辑**：移植自开源项目 [dislocker](https://github.com/Aorimn/dislocker) v0.7.3，遵循 **GPL-2.0** 许可证。
- **加密原语组件**：内置 [mbedtls](https://github.com/Mbed-TLS/mbedtls) v2.28.8，遵循 **Apache-2.0 / GPL-2.0-or-later** 许可证。
- **项目主体与应用层代码**：依据 **GNU General Public License v2.0 (GPL-2.0)** 协议开源。详细条款请见 [LICENSE](LICENSE)。

感谢开源社区对逆向工程和密码学实现所做的卓越贡献！
