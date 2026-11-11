# BitLockerDroid 架构与性能概览文档

<p align="center">
  <a href="ARCHITECTURE.md">English</a> | <b>简体中文</b>
</p>

本文档阐述 **BitLockerDroid (BitUnlocker)** 的内部系统架构、I/O 传输链路、密码学加速实现、混合文件系统设计、安全防护边界以及完整的实测基准数据集。

---

## 目录

- [1. 整体分层架构](#1-整体分层架构)
- [2. 双模式底层 I/O 传输链路](#2-双模式底层-io-传输链路)
  - [2.1 Root 模式：三级块设备访问架构](#21-root-模式三级块设备访问架构)
  - [2.2 免 Root 模式：用户态 USB BOT 状态机与 SAF 双缓冲队列](#22-免-root-模式用户态-usb-bot-状态机与-saf-双缓冲队列)
  - [2.3 内核驱动与用户态 USB 交接策略](#23-内核驱动与用户态-usb-交接策略)
- [3. 密码学引擎与 ARMv8 硬件加速](#3-密码学引擎与-armv8-硬件加速)
  - [3.1 密钥派生链路 (PBKDF2 / VMK / FVEK)](#31-密钥派生链路-pbkdf2--vmk--fvek)
  - [3.2 ARMv8-CE 4 路超标量交织流水线](#32-armv8-ce-4-路超标量交织流水线)
  - [3.3 运行时硬件探测与自检回退机制](#33-运行时硬件探测与自检回退机制)
- [4. 混合文件系统引擎与全局挂载](#4-混合文件系统引擎与全局挂载)
  - [4.1 读写分离架构与 64KB LRU 块缓存](#41-读写分离架构与-64kb-lru-块缓存)
  - [4.2 引导扇区虚拟重定向 (Boot Sector Redirection)](#42-引导扇区虚拟重定向-boot-sector-redirection)
  - [4.3 全局 FUSE 守护进程与跨进程缓存一致性](#43-全局-fuse-守护进程与跨进程缓存一致性)
- [5. 内存安全、写屏障与二进制加固](#5-内存安全写屏障与二进制加固)
  - [5.1 扇区级元数据写屏障 (Write Barrier)](#51-扇区级元数据写屏障-write-barrier)
  - [5.2 JNI 会话槽位注册表 (防 Use-After-Free)](#52-jni-会话槽位注册表-防-use-after-free)
  - [5.3 编译期安全加固与 16 KB 页面对齐](#53-编译期安全加固与-16-kb-页面对齐)
- [6. 实测性能基准数据集](#6-实测性能基准数据集)
  - [6.1 密码学算力微基准测试](#61-密码学算力微基准测试)
  - [6.2 Tier 1: 1TB 移动固态硬盘 (USB 3.0 / UAS)](#62-tier-1-1tb-移动固态硬盘-usb-30--uas)
  - [6.3 Tier 2: 主流闪存 U 盘 (USB 3.0 / BOT)](#63-tier-2-主流闪存-u-盘-usb-30--bot)
  - [6.4 Root 模式 vs 免 Root 模式性能对比与成因拆解](#64-root-模式-vs-免-root-模式性能对比与成因拆解)

---

## 1. 整体分层架构

```
                       外接 USB OTG 加密存储设备
                                   │
         ┌─────────────────────────┴─────────────────────────┐
         ▼                                                   ▼
【免 Root 运行模式】                                 【Root 运行模式】
Android USB Host API (android.hardware.usb)        Linux 内核块设备节点 (/dev/block/vold/*)
         │                                                   │
         ▼                                                   ▼
PipelinedUsbCommunication + UsbBotTransport        三级块 I/O (Direct pread / bitlocker_io 守护进程)
(16KB USBDEVFS_BULK 同步分片 + 严格 BOT 状态机)      (4MB 扩容匿名管道 + writev + 块队列 sysfs 调优)
         │                                                   │
         └─────────────────────────┬─────────────────────────┘
                                   │
                                   ▼
                   [BitLocker 探测器 (BitLockerDetector)]
                   扫描 -FVE-FS- 签名、MBR/GPT 分区拓扑与子分区偏移
                                   │
                                   ▼
                   [DislockerCore 本地核心引擎 (JNI / C)]
                   mbedtls + ARMv8-CE (PBKDF2-SHA256, AES-CCM, VMK -> FVEK)
                   JNI 16 槽位会话注册表 + 扇区级元数据写屏障拦截
                                   │
         ┌─────────────────────────┴─────────────────────────┐
         ▼                                                   ▼
【只读解析与目录枚举通道 (Kotlin)】                  【写入修改与 FUSE 挂载通道 (Native C)】
CachedBlockSource (64KB 扇区对齐块 / 4MB LRU)       延迟初始化写入器 (Lazy Writer Initialization)
 ├── NtfsReader  (MFT 记录与 Runlist 解析)           ├── libntfs-3g (ntfs3g_device.c: NTFS 写入/脏位修复)
 ├── ExFatReader (簇链、位图与目录项解析)            └── FatFs      (fatfs_device.c: FAT32 & exFAT 写入)
 └── Fat32Reader (FAT 表与长文件名解析)
         │                                                   │
         └─────────────────────────┬─────────────────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
【SAF 存储访问框架】         【局域网无线共享】        【全局 POSIX 虚拟挂载】
BitLockerDocumentsProvider  LanWebServer / WebDAV    bitlocker_fuse_daemon (Root)
+ DocumentWritePipeline     (HTTP 范围流 / WebDAV)   挂载至 /storage/XXXX-XXXX
(512KB 双缓冲异步乒乓写队列)                          (SIGUSR1 跨进程缓存失效重挂载)
```

---

## 2. 双模式底层 I/O 传输链路

### 2.1 Root 模式：三级块设备访问架构

在 `native/dislocker/src/io.c` 与 `native/daemon/io_daemon.c` 中，Root 模式采用三级渐进式块设备访问机制：

1. **Tier 1 — 直接文件描述符访问 (`pread` / `pwrite`)**：若当前进程上下文受 SELinux 策略允许直接打开块设备，则直接通过系统调用读写。
2. **Tier 2 — 常驻特权 I/O 守护进程 (`bitlocker_io`)**：针对 Android 应用沙箱无法直接打开 `/dev/block/*` 的常规情况，通过 `su` 启动单一常驻守护进程 `libbitlocker_io.so`。
   - **消除进程派生开销**：块设备仅在会话建立时打开一次，后续扇区读写通过二进制协议（魔数 `0x4249544C`）在标准输入/输出匿名管道上传输，避免 `su -c dd` 每次请求产生约 30~50 ms 的 `fork`/`exec` 开销。
   - **管道扩容与向量化写入**：初始化时通过 `fcntl(fd, F_SETPIPE_SZ, ...)` 将内核管道缓冲区阶梯式扩容至最高 **4 MB**，并使用 `writev` 合并响应头与数据载荷，减少系统调用次数。
   - **内核块队列参数调优**：守护进程打开块设备后，自动向 `/sys/dev/block/<maj>:<min>/queue/` 写入调优参数（`read_ahead_kb = 2048`、`max_sectors_kb = 1024`、`nr_requests = 256`），提升顺序预读与请求合并效率。
3. **Tier 3 — 单次命令回退 (`su -c dd`)**：当常驻守护进程不可用时，回退至单次 `dd` 命令调用。

### 2.2 免 Root 模式：用户态 USB BOT 状态机与 SAF 双缓冲队列

免 Root 模式受限于 Android 权限沙箱，通过以下三层机制协同工作：

1. **JNI `USBDEVFS_BULK` 同步传输 (`native/libaums_compat/usb.c`)**：
   - 受 Linux 内核 `MAX_USBFS_BUFFER_SIZE` 限制，单次 `USBDEVFS_BULK` `ioctl` 最大传输长度为 **16 KB**（16,384 字节）。
   - JNI 封装在同一次系统调用内捕获并返回 `-errno`，避免了通过 Android Java API `UsbDeviceConnection.bulkTransfer()` 丢失底层真实错误码（如 `-ETIMEDOUT` `-110` 或 `-EPIPE` `-32` Endpoint Halt）的问题。
2. **严格串行 BOT 协议状态机 (`UsbBotTransport.kt`)**：
   - 在用户态显式维护 `CBW (31B)` → `DATA_IN / DATA_OUT` → `CSW (13B)` 四阶段状态转换与 Tag 校验。
   - 遵循 USB Mass Storage BOT 6.7 规范：当读取 CSW 遇到 Endpoint Stall (`-EPIPE`) 时，自动在 Bulk-In 端点执行 `USBDEVFS_CLEAR_HALT` 并重读同一 Tag 的 CSW；在未完成完整 BOT Reset 及双端点 Halt 清理前，严禁重放写命令或发送新 CBW，防止写损坏。
3. **SAF 512KB 双缓冲写流水线 (`DocumentWritePipeline.kt`)**：
   - 在 `BitLockerDocumentsProvider` 接收写入流时，采用容量为 2 的 `ArrayBlockingQueue<Slot>`（每槽默认 **512 KB**）构建生产者-消费者模型，使上层 SAF `InputStream` 数据读取与底层加密落盘交叠执行。
   - **严格事务完成语义**：队列仅负责流式缓冲，最终返回前强制执行 `writer.truncate()` 与 `writer.sync()`，只有底层物理刷盘成功才向调用方确认写入完成。

### 2.3 内核驱动与用户态 USB 交接策略

当外接固态硬盘（PSSD）在内核态使用 UAS（USB Attached SCSI）接口配置，随后切换到免 Root 用户态 BOT 模式（或反之）时：
- **接管阶段 (`PipelinedUsbCommunication.init`)**：不仅调用 `claimInterface(usbInterface, true)` 剥离内核驱动，还显式调用 `setInterface(usbInterface)` 强制切换至 BOT Alternate Setting，防止接口停留在内核遗留的 UAS Alternate Setting 导致传输超时。
- **交还阶段 (`resetForKernelHandoff` & `UsbKernelHandoffPolicy.kt`)**：在保持 `usbfs` 接口占有的前提下先执行 `USBDEVFS_RESET`，随后再释放接口并关闭连接，确保 Linux 内核重新枚举并干净绑定 `uas` / `usb-storage` 驱动。

---

## 3. 密码学引擎与 ARMv8 硬件加速

### 3.1 密钥派生链路 (PBKDF2 / VMK / FVEK)

1. **元数据解析 (`metadata.c`)**：扫描卷头 `-FVE-FS-` 或 BitLocker To Go 签名，读取三份冗余 FVE 元数据块（`information_off[0..2]`）并校验 CRC32 与数据集结构。
2. **密钥拉伸 (`vmkey.c`)**：针对用户密码或 48 位恢复密钥，执行 BitLocker 标准的 **1,048,576 轮（`0x100000`）SHA-256 迭代拉伸**（每轮对 88 字节结构体进行两次 SHA-256 压缩块计算，合计 2,097,152 次 SHA-256 块运算）。
3. **VMK 与 FVEK 解密**：使用拉伸后的密钥通过 **AES-CCM** 解密卷主密钥（VMK），再由 VMK 解密全卷加密密钥（FVEK，支持 AES-XTS 128/256 与无扩散器 AES-CBC 128/256）。对于 Vista 时代遗留的 `AES-CBC + Elephant Diffuser` 卷，引擎在初始化阶段显式拒绝挂载以防止静默数据损坏。

### 3.2 ARMv8-CE 4 路超标量交织流水线

在 `arm64-v8a` 架构下，`aes_xts_armv8ce.c` 与 `sha256_armv8ce.c` 使用 ARMv8-A Cryptography Extensions 内联指令（`vaeseq_u8`、`vaesdq_u8`、`vaesmcq_u8`、`vaesimcq_u8`、`vsha256hq_u32` 等）：

- **4 路指令级并行交织（4-Way Interleaving）**：在扇区加解密循环中，单次迭代同时计算 4 个连续 16 字节 AES 块的 GF($2^{128}$) 调整值（Tweak `t0..t3`），并将 4 个数据块的 `AESE`/`AESMC`（或 `AESD`/`AESIMC`）指令交错排布。这种设计充分利用了现代 Cortex-A / Kryo 核心的超标量乱序执行管道，消除了单块前序轮次间的寄存器数据依赖停顿（Pipeline Stall）。
- **无分支 GF($2^{128}$) 乘法**：XTS 模式的 $\alpha$ 乘运算通过纯寄存器位操作与掩码 `0x87ULL & (-(int64_t)carry)` 实现，避免了软件查表带来的缓存访问延迟与侧信道特征。

### 3.3 运行时硬件探测与自检回退机制

- 引擎在首次调用时通过 `getauxval(AT_HWCAP)` 检查 `HWCAP_AES` 与 `HWCAP_SHA2` 标志位。
- **强制回环自检 (`armv8ce_self_test`)**：即使 CPU 报告支持硬件指令，引擎仍会使用内置标准测试向量执行一次完整的 AES-XTS / AES-CBC 加密-解密回环对比与 NIST SHA-256 摘要校验。仅当自检 100% 匹配时才启用硬件路径，否则自动降级至 `mbedtls` / 纯 C 实现。
- 支持在应用设置或通过 ADB 接口热切换硬件加速与软件实现，便于进行单变量 A/B 性能对照。

---

## 4. 混合文件系统引擎与全局挂载

### 4.1 读写分离架构与 64KB LRU 块缓存

为了兼顾解锁后的快速目录浏览与完整的文件写入能力，`DislockerCore` 采用了**读写通道分离、写入器按需延迟挂载（Lazy Writer Initialization）**的设计：

1. **只读解析通道（Kotlin 层）**：
   - 解锁后默认仅初始化只读 `VolumeReader`（`NtfsReader`、`ExFatReader`、`Fat32Reader`），不触发任何底层写入器挂载。
   - 所有读取请求经由 `CachedBlockSource` 代理：将上层任意字节偏移与长度的请求（如读取 4 字节 FAT 表项或 1KB MFT 记录）自动对齐并聚合为 **64 KB** 的整块扇区解密操作，并在内存中维护最多 64 个块（**4 MB**）的 LRU 缓存。
   - **安全擦除**：当缓存块被淘汰（`removeEldestEntry`）或卷关闭（`clear`）时，显式使用 `Arrays.fill(block, 0)` 将明文缓存清零。
2. **写入修改通道（Native C 层）**：
   - 当发生文件新建、写入、重命名、删除或截断时，`DislockerCore` 才按需挂载底层 C 驱动：
     - **NTFS**：调用裁剪版 **`libntfs-3g`**（`ntfs3g_device.c`）。
     - **FAT32 与 exFAT**：统一调用开启了 `FF_FS_EXFAT = 1`、`FF_USE_LFN = 3`（UTF-8 动态堆缓冲）与 `FF_LBA64 = 1` 的 **ChaN `FatFs`** 引擎（`fatfs_device.c`）。

### 4.2 引导扇区虚拟重定向 (Boot Sector Redirection)

BitLocker 在加密 FAT32 / exFAT 分区时，会将原始文件系统的引导扇区（Sector `0..nb_backup_sectors`）备份至加密卷内部的 `boot_sectors_backup` 偏移处，而将物理起始扇区替换为包含 `-FVE-FS-` 签名的 BitLocker 卷头。

在 `fatfs_device.c` 与 `ntfs3g_device.c` 的底层 `disk_read_internal` / `disk_write_internal` 中，驱动实现了透明偏移重定向：当文件系统请求访问逻辑偏移 `[0, backup_bytes)` 时，自动重定向至物理偏移 `[boot_sectors_backup, boot_sectors_backup + backup_bytes)` 进行加解密读写，从而让标准 `FatFs` 与 `libntfs-3g` 能够直接识别并操作加密卷。

### 4.3 全局 FUSE 守护进程与跨进程缓存一致性

在 Root 模式下，`bitlocker_fuse_daemon`（编译为 `libbitlocker_fuse.so`）通过 `su -mm` 进入 PID 1 全局挂载命名空间，打开 `/dev/fuse` 并将解密卷挂载至 `/storage/XXXX-XXXX`：

- **跨进程缓存同步 (`SIGUSR1`)**：由于应用进程的 SAF `DocumentsProvider` 与独立的 `bitlocker_fuse_daemon` 进程各自持有文件系统上下文，当应用通过 SAF 完成写入、创建或删除操作后，会向 FUSE 守护进程发送 `SIGUSR1` 信号。FUSE 守护进程在主事件循环顶部检测到 `g_cache_dirty` 标志后，安全卸载并重新挂载内部 `ntfs-3g` / `FatFs` 实例，防止出现跨进程元数据脏读。

---

## 5. 内存安全、写屏障与二进制加固

### 5.1 扇区级元数据写屏障 (Write Barrier)

为防止上层文件系统驱动 Bug 或异常偏移计算覆写 BitLocker 关键元数据导致全盘密钥永久丢失，`dislocker.c`（`dis_write_encrypted` / `dis_encrypt_region`）与 `access.c`（`dis_encrypt_sector`）在最底层强制执行写屏障检查：
- 拒绝任何目标偏移位于卷头保护区（`offset < nb_backup_sectors * sector_size`，默认前 8,192 字节）的写请求；
- 拒绝任何目标偏移落入三处 64 KB FVE 元数据块区间（`[information_off[i], information_off[i] + 0x10000)`，$i \in \{0,1,2\}$）的写请求；
- 一旦触发写屏障，立即返回 `-EPERM` 并中止 I/O。

### 5.2 JNI 会话槽位注册表 (防 Use-After-Free)

`dislocker_jni.c` 不直接信任 Java 层传入的 `jlong` 指针，而是维护了容量为 16 的全局静态槽位表 `g_sessions`：
- 每次 JNI I/O 调用通过 `slot_acquire(handle)` 校验句柄有效性并获取该槽位的独立 `pthread_mutex_t`；
- 关闭会话时，`slot_take(handle)` 在全局锁下将该槽位标记为 `closing = 1`，使后续并发到达的 `slot_acquire` 立即返回 `NULL`（抛出干净的 `IllegalStateException`），随后释放全局锁并等待槽位锁排空在途 I/O，最后执行 `dis_secure_zero` 清零 FVEK 密钥并释放内存。

### 5.3 编译期安全加固与 16 KB 页面对齐

`native/CMakeLists.txt` 对所有原生目标（`libdislocker.so`、`libbitlocker_io.so`、`libbitlocker_fuse.so`、`libusb-lib.so`、`liberrno-lib.so`）统一启用：
- `_FILE_OFFSET_BITS=64`：确保 32 位 ABI (`armeabi-v7a`) 下 `off_t` 为 64 位，支持大于 2 GiB 的分区偏移；
- `_FORTIFY_SOURCE=2` 与 `-fstack-protector-strong`：开启 libc 边界检查与栈溢出保护；
- `-Wl,-z,relro` 与 `-Wl,-z,now`：启用完整重定位只读（Full RELRO），防止 GOT 表覆写；
- `-Wl,-z,max-page-size=16384`：显式将 ELF 段对齐至 16 KB 边界，满足 Android 15+ 16 KB 页面大小设备要求。

---

## 6. 实测性能基准数据集

> [!NOTE]
> 测试方法严格遵循 **[存储与加解密性能评估规范 (perf_eval.md)](perf_eval.md)**：
> - **测试宿主**：高通骁龙 8 Gen 2，Android 16，Linux Kernel 5.15（Root Direct I/O）；
> - **物理接口**：USB 3.0 (5 Gbps)；
> - **采样规范**：剔除首轮冷启动预热样本，独立采集 $N=5$ 轮有效样本取算术平均值；读取前清空内核页缓存（`echo 3 > /proc/sys/vm/drop_caches`），写入计时包含底层物理 `sync`。

### 6.1 密码学算力微基准测试 (1,048,576 轮 SHA-256 PBKDF2)

| 实现方案 | 算术平均耗时 (ms) | 最低耗时 (ms) | 最高耗时 (ms) | 硬件加速比 | 离散度 (RSD) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **ARMv8 CE 硬件加速** | **197.65 ~ 207.51** | 158.97 | 227.50 | **3.86x ~ 4.13x** | ±11.9% |
| **纯 C 软件后备实现** | 801.73 ~ 815.56 | 734.77 | 938.64 | 1.00x (基线) | ±10.3% |

### 6.2 Tier 1: 1TB 移动固态硬盘 (HIKSEMI P202 1TB / USB 3.0 / UAS)

#### 连续读写吞吐性能 (MB/s)

| 分区与加密配置 | 连续读取均值 (MB/s) | 读瞬时峰值 (Peak) | 读取离散度 (RSD) | 连续写入均值 (MB/s) | 写瞬时峰值 (Peak) | 写入离散度 (RSD) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **78.77** | 100.36 | ±20.2% | **50.29** | 57.52 | ±13.3% |
| **NTFS (AES-CBC)** | **63.16** | 67.64 | ±6.7% | **40.93** | 47.49 | ±9.6% |
| **exFAT (AES-XTS)** | **77.02** | 99.50 | ±20.3% | **53.17** | 58.29 | ±10.1% |
| **exFAT (AES-CBC)** | **100.58** | 145.55 | ±29.4% | **48.20** | 66.66 | ±21.9% |
| **FAT32 (AES-XTS)** | **61.54** | 67.44 | ±5.6% | **56.59** | 61.83 | ±9.1% |
| **FAT32 (AES-CBC)** | **82.91** | 115.00 | ±25.1% | **41.88** | 43.89 | ±3.3% |

#### 4K 随机访问性能

| 分区与加密配置 | 4K 读取延时 / IOPS | 读延时离散度 (RSD) | 4K 写入延时 / IOPS | 写延时离散度 (RSD) |
| :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **1.40 ms** (718.0 IOPS) | ±7.9% | **7.13 ms** (140.4 IOPS) | ±4.3% |
| **NTFS (AES-CBC)** | **1.36 ms** (744.7 IOPS) | ±14.5% | **7.47 ms** (134.1 IOPS) | ±4.6% |
| **exFAT (AES-XTS)** | **1.32 ms** (759.7 IOPS) | ±8.3% | **10.87 ms** (92.0 IOPS) | ±2.1% |
| **exFAT (AES-CBC)** | **1.37 ms** (731.2 IOPS) | ±5.4% | **10.22 ms** (98.8 IOPS) | ±10.5% |
| **FAT32 (AES-XTS)** | **1.40 ms** (717.1 IOPS) | ±7.2% | **8.05 ms** (126.0 IOPS) | ±13.5% |
| **FAT32 (AES-CBC)** | **1.42 ms** (706.9 IOPS) | ±9.3% | **9.16 ms** (109.2 IOPS) | ±2.4% |

### 6.3 Tier 2: 主流闪存 U 盘 (Lexar JumpDrive 32GB / USB 3.0 / BOT)

#### 连续读写吞吐性能 (MB/s)

| 分区与加密配置 | 连续读取均值 (MB/s) | 读瞬时峰值 (Peak) | 读取离散度 (RSD) | 连续写入均值 (MB/s) | 写瞬时峰值 (Peak) | 写入离散度 (RSD) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **52.16** | 61.32 | ±11.4% | **16.92** | 23.96 | ±30.7% |
| **NTFS (AES-CBC)** | **37.09** | 43.50 | ±12.8% | **12.34** | 20.05 | ±36.2% |
| **exFAT (AES-XTS)** | **66.46** | 79.51 | ±19.3% | **15.96** | 25.33 | ±38.2% |
| **exFAT (AES-CBC)** | **58.36** | 79.32 | ±21.7% | **17.72** | 29.47 | ±47.6% |
| **FAT32 (AES-XTS)** | **57.25** | 79.08 | ±23.8% | **10.43** | 13.33 | ±18.9% |
| **FAT32 (AES-CBC)** | **74.53** | 96.02 | ±20.5% | **9.98** | 12.55 | ±18.1% |

#### 4K 随机访问性能

| 分区与加密配置 | 4K 读取延时 / IOPS | 读延时离散度 (RSD) | 4K 写入延时 / IOPS | 写延时离散度 (RSD) |
| :--- | :--- | :--- | :--- | :--- |
| **NTFS (AES-XTS)** | **1.98 ms** (507.9 IOPS) | ±9.7% | **7.24 ms** (138.5 IOPS) | ±6.5% |
| **NTFS (AES-CBC)** | **2.00 ms** (501.7 IOPS) | ±5.4% | **6.01 ms** (173.3 IOPS) | ±24.4% |
| **exFAT (AES-XTS)** | **2.00 ms** (503.2 IOPS) | ±8.6% | **9.15 ms** (110.9 IOPS) | ±13.4% |
| **exFAT (AES-CBC)** | **2.16 ms** (475.6 IOPS) | ±19.2% | **8.15 ms** (122.9 IOPS) | ±4.0% |
| **FAT32 (AES-XTS)** | **1.97 ms** (508.1 IOPS) | ±4.3% | **8.62 ms** (126.2 IOPS) | ±30.4% |
| **FAT32 (AES-CBC)** | **2.40 ms** (427.6 IOPS) | ±19.6% | **9.65 ms** (106.4 IOPS) | ±17.4% |

### 6.4 Root 模式 vs 免 Root 模式性能对比与成因拆解

> [!NOTE]
> 以下双模式对照数据采集自早期架构验证版本（Tier 2 USB 3.0 闪存盘，10 次采样均值）。早期测试未对每轮读取强制执行 `drop_caches`，因此 Root 模式连续读数据包含部分内核预读缓存贡献（107.52 MB/s）。

| 评估维度与指标 | 免 Root 模式 (USB Host + 双缓冲) | Root 模式 (内核块设备直通) | 差异倍数 |
| :--- | :--- | :--- | :--- |
| **全卷连续读取吞吐** | 43.25 MB/s | **107.52 MB/s** | **2.49x** |
| **全卷连续写入吞吐** | 12.97 MB/s | **22.98 MB/s** | **1.77x** |
| **4K 随机读取平均延时** | 1.33 ms (752 IOPS) | **0.97 ms** (1,031 IOPS) | **延时降低 27.1%** |
| **4K 随机写入平均延时** | 9.25 ms (108 IOPS) | **5.20 ms** (192 IOPS) | **延时降低 43.8%** |
| **50MB 大文件端到端写入** | 8.14 MB/s | **34.52 MB/s** | **4.24x** |
| **50MB 大文件端到端读取** | 13.76 MB/s | **21.12 MB/s** | **1.53x** |

#### 性能差异的四个核心物理与架构成因：

1. **`usbfs` 16 KB 分片限制 vs 内核 DMA**：Root 模式通过 `/dev/block/*` 走 Linux 内核原生块驱动与 DMA 控制器，单次请求可传输数兆字节；免 Root 模式受限于用户态 `usbfs` 的 16 KB 缓冲区上限，大块 I/O 必须拆分为大量 16 KB 的 `USBDEVFS_BULK` `ioctl` 调用。
2. **半双工 BOT 停等 vs UAS 命令队列**：免 Root 模式在用户态执行半双工 BOT 协议，每次 SCSI 命令均需顺序等待 `CBW -> DATA -> CSW` 三阶段往返；Root 模式在支持 UAS 的外设（如移动固态硬盘）上可直接利用内核全双工多队列并发。
3. **Binder IPC 与跨层拷贝开销**：免 Root 模式通过 SAF 对外提供访问，数据流经由系统 `DocumentsUI` 与应用进程间的管道/套接字传输，并在底层 C 文件系统驱动与 Kotlin `UsbBlockDevice` 之间通过 JNI 往返传递；Root 模式的全局 FUSE 挂载直接向第三方应用暴露 POSIX 路径，减少了 SAF 中继环节。
4. **内核页缓存与小写入合并**：Root 模式享有 Linux 内核 Page Cache 与 `blk-mq` 调度器，能够合并文件系统频繁的元数据更新（如 FAT 表或 MFT 位图更新）；免 Root 模式下每次元数据刷新均直接穿透至 USB 闪存介质，更容易触发闪存主控写放大。
