# BitLockerDroid ADB 调试与自动化测试接口指南

> **Provider Authority**: `com.bitlockerdroid.provider`  
> **适用版本**: v1.0+ (`main`)  
> **适用场景**: 自动化 CI/CD 测试、无头解锁、文件系统读写验证、硬件加速基准测速与调试定位。

---

## 目录
- [一、基础命令语法](#一基础命令语法)
- [二、硬件加速与基准测试（统一接口）](#二硬件加速与基准测试统一接口)
- [三、卷管理与无头解锁](#三卷管理与无头解锁)
- [四、SAF 文件系统操作 (VFS)](#四saf-文件系统操作-vfs)
- [五、系统模式与高级配置](#五系统模式与高级配置)
- [六、日志与诊断定位](#六日志与诊断定位)
- [七、自动化测试脚本示例](#七自动化测试脚本示例)

---

## 一、基础命令语法

BitLockerDroid 将所有控制能力通过 Android 原生 `DocumentsProvider` (ContentProvider) 导出，直接使用 `adb shell content` 操作，无需模拟 UI 点击。

### 1.1 常用子命令格式
- **控制调用 (`call`)**:
  ```bash
  adb shell content call --uri content://com.bitlockerdroid.provider --method <方法名> [--arg "<字符串参数>"] [--extra <键>:<类型前缀>:<值>] ...
  ```
- **数据查询 (`query`)**:
  ```bash
  adb shell content query --uri content://com.bitlockerdroid.provider/<路径>
  ```
- **流式读取 (`read`)**:
  ```bash
  adb shell content read --uri content://com.bitlockerdroid.provider/document/<DOC_ID> > <本地输出路径>
  ```
- **流式写入 (`write`)**:
  ```bash
  adb shell content write --uri content://com.bitlockerdroid.provider/document/<DOC_ID> < <本地输入文件>
  ```

### 1.2 Extra 参数类型前缀速查
| 前缀 | Kotlin/Java 类型 | 示例 |
| :---: | :--- | :--- |
| `s` | `String` | `--extra password:s:"MySecretPass"` |
| `b` | `Boolean` | `--extra enabled:b:true` 或 `--extra remember:b:true` |
| `i` | `Int` | `--extra rounds:i:1048576` |
| `l` | `Long` | `--extra offset:l:0` |

---

## 二、硬件加速与基准测试（统一接口）

BitLockerDroid 已将 ARMv8 Cryptography Extensions（AES-XTS/CBC 数据加密 + SHA-256 密钥拉伸）统一为统一硬件加速管理接口，支持免重启热切换。

### 2.1 查询硬件加速状态 (`get_hardware_accel`)
查询当前 CPU 硬件指令集支持情况与运行时开启状态。

```bash
adb shell content call --uri content://com.bitlockerdroid.provider --method get_hardware_accel
```
- **返回 Bundle 字段**:
  - `supported` (bool): CPU 是否支持 ARMv8 CE 并通过回环自检（AES 与 SHA256 均支持）
  - `enabled` (bool): 当前底层 C 驱动硬件加速是否生效
  - `pref` (bool): 持久化配置开关状态
  - `aes_supported` / `sha2_supported` (bool): 细分算法硬件支持状态

### 2.2 动态热切换硬件加速 (`set_hardware_accel`)
在运行时即时切换底层硬件加密通道与纯软件降级通道（用于性能对照或容错测试），立即对后续所有扇区读写与密钥推导生效。

```bash
# 启用 ARMv8 硬件加速
adb shell content call --uri content://com.bitlockerdroid.provider --method set_hardware_accel --arg true

# 降级至纯软件算法 (AES 查表 + 软件 SHA-256)
adb shell content call --uri content://com.bitlockerdroid.provider --method set_hardware_accel --arg false
```

### 2.3 密钥拉伸微基准测试 (`benchmark_key_stretching`)
测试 BitLocker 核心密钥拉伸（`stretch_key`，0x100000 轮连续 88 字节哈希迭代，共 2,097,152 次 SHA-256 块运算）的底层耗时。

```bash
adb shell content call --uri content://com.bitlockerdroid.provider --method benchmark_key_stretching --arg 1048576
```
- **返回 Bundle 字段**:
  - `ms`: 耗时毫秒（开启 ARMv8 CE 通常约 100ms；纯软件模式通常约 400~500ms）
  - `hw_enabled`: 当前是否运行于硬件加速模式 (`true` / `false`)

### 2.4 存储性能基准测速 (`run_benchmark`)
对已解锁的卷执行连续读写、4K 随机延时与 IOPS 测速，以及探测物理 USB 协议协商速率。

```bash
# 全量测试 (连续读写 + 4K 随机读写 + 延时 + USB 协商速度)
adb shell content call --uri content://com.bitlockerdroid.provider --method run_benchmark --arg "<设备路径>" --extra test_type:s:all

# 仅测试连续读取
adb shell content call --uri content://com.bitlockerdroid.provider --method run_benchmark --arg "<设备路径>" --extra test_type:s:seq_read

# 仅测试只读维度 (连续读 + 4K 读)
adb shell content call --uri content://com.bitlockerdroid.provider --method run_benchmark --arg "<设备路径>" --extra test_type:s:read_only
```
- **返回 Bundle 关键指标**:
  - `seq_read_mbps` / `seq_write_mbps`: 连续平均吞吐量 (MB/s)
  - `peak_read_mbps` / `peak_write_mbps`: 连续瞬时峰值吞吐量 (MB/s)
  - `random_4k_read_ms` / `random_4k_read_iops`: 4K 读延迟 (ms) 与 IOPS
  - `random_4k_write_ms` / `random_4k_write_iops`: 4K 写延迟 (ms) 与 IOPS
  - `usb_speed_mbps` / `usb_speed_desc`: USB 物理协商速度（如 `5000` / `USB 3.0 (5 Gbps SuperSpeed)`）

---

## 三、卷管理与无头解锁

### 3.1 查询卷状态 (`get_volumes_info`)
列出当前所有已检测到的待解锁卷和已挂载的解锁卷。

```bash
adb shell content call --uri content://com.bitlockerdroid.provider --method get_volumes_info
```
- **返回 Bundle 字段**:
  - `detected_count` (int): 待解锁卷数量
  - `detected_paths` (ArrayList<String>): 待解锁卷的物理设备路径（如 `/dev/block/vold/public:8,102` 或 `usb://2002/p1`）
  - `unlocked_count` (int): 已解锁卷数量
  - `unlocked_paths` / `unlocked_labels` / `unlocked_guids`: 已解锁卷的路径、卷标与 GUID

### 3.2 命令行无头解锁 (`unlock_volume`)
无需拉起任何 UI 界面，直接通过密码或 48 位恢复密钥解锁卷并挂载至 SAF 文件系统。

```bash
# 方式 A：使用用户密码解锁
adb shell content call --uri content://com.bitlockerdroid.provider \
  --method unlock_volume \
  --arg "<设备路径>" \
  --extra password:s:"<密码明文>" \
  [--extra remember:b:true] \
  [--extra offset:l:0]

# 方式 B：使用 48 位数字恢复密钥解锁
adb shell content call --uri content://com.bitlockerdroid.provider \
  --method unlock_volume \
  --arg "<设备路径>" \
  --extra recovery_key:s:"139293-168685-596629-613910-116622-511181-044550-287243"
```
- **返回 Bundle**:
  - 成功：`{success=true, guid=<GUID>}`
  - 失败：`{success=false, error=<错误详情>}`

### 3.3 锁定与卸载卷 (`lock_volume`)
安全刷盘并关闭加密驱动会话，注销 SAF 根目录。

```bash
adb shell content call --uri content://com.bitlockerdroid.provider --method lock_volume --arg "<设备路径>"
```

### 3.4 重新扫描硬件总线 (`refresh_scan`)
重置手动锁定标记，重新探测所有块设备与 USB Host 节点，并自动装载已记住凭据的卷。

```bash
adb shell content call --uri content://com.bitlockerdroid.provider --method refresh_scan
```

---

## 四、SAF 文件系统操作 (VFS)

已解锁的 BitLocker 卷均作为虚拟文件系统挂载至 Android SAF 框架，可通过以下接口读写。

| 操作 | 对应命令 | 说明 |
| :--- | :--- | :--- |
| **查询所有挂载根** | `adb shell content query --uri content://com.bitlockerdroid.provider/root` | 获取卷名、容量与根 `document_id` |
| **遍历目录内容** | `adb shell content query --uri content://com.bitlockerdroid.provider/document/<PARENT_ID>/children` | 获取目录下的文件与子目录列表 |
| **读取文件内容** | `adb shell content read --uri content://com.bitlockerdroid.provider/document/<DOC_ID> > <本地路径>` | 流式读取文件数据（解密通道） |
| **写入文件内容** | `adb shell content write --uri content://com.bitlockerdroid.provider/document/<DOC_ID> < <本地路径>` | 流式写入数据（流水线加密通道） |
| **创建新文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method create_document --arg "<PARENT_ID>" --extra display_name:s:"test.bin" --extra mime_type:s:"application/octet-stream"` | 返回新文件的 `document_id` |
| **删除文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method delete_document --arg "<DOC_ID>"` | 返回 `{success=true}` |
| **重命名文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method rename_document --arg "<DOC_ID>" --extra display_name:s:"new_name.bin"` | 返回新 `document_id` |
| **复制文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method copy_document --extra source_document_id:s:"<SRC_ID>" --extra target_parent_document_id:s:"<TARGET_PARENT_ID>"` | 返回新 `document_id` |
| **移动文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method move_document --extra source_document_id:s:"<SRC_ID>" --extra source_parent_document_id:s:"<SRC_P_ID>" --extra target_parent_document_id:s:"<TGT_P_ID>"` | 返回新 `document_id` |
| **搜索文件** | `adb shell content call --uri content://com.bitlockerdroid.provider --method search_documents --arg "<关键字>" [--extra root_id:s:"<ROOT_ID>"]` | 递归模糊搜索匹配文件 |

---

## 五、系统模式与高级配置

### 5.1 设置只读挂载模式 (`set_mount_read_only`)
```bash
# 对指定卷设置为只读保护
adb shell content call --uri content://com.bitlockerdroid.provider --method set_mount_read_only --extra device_path:s:"<设备路径>" --extra read_only:b:true

# 全局默认只读挂载
adb shell content call --uri content://com.bitlockerdroid.provider --method set_mount_read_only --arg true
```

### 5.2 切换 Root / 非 Root 模式并热重启 (`switch_mode_and_restart`)
```bash
# 切换至纯非 Root 模式 (USB Host 模式) 并重启应用
adb shell content call --uri content://com.bitlockerdroid.provider --method switch_mode_and_restart --extra target_root:b:false

# 切换至 Root 模式并重启应用
adb shell content call --uri content://com.bitlockerdroid.provider --method switch_mode_and_restart --extra target_root:b:true
```

### 5.3 Activity 快捷调起
```bash
# 打开主界面 (tab: 0=卷列表, 1=设置)
adb shell am start -n com.bitlockerdroid/.ui.BitLockerSettingsActivity --ei tab 1

# 调起特定卷的解锁弹窗
adb shell am start -n com.bitlockerdroid/.ui.UnlockDialogActivity --es device_path "<设备路径>"
```

---

## 六、日志与诊断定位

### 6.1 Logcat 实时过滤跟踪
```bash
adb logcat -v time -s BitLockerNative BitLockerLog BitLockerDroid UnlockManager BitLockerProvider UsbStorageManager PipelinedUsb
```

### 6.2 提取应用私有日志与崩溃栈
```bash
# 查看或导出应用内部环形滚动日志 (512KB 自动翻滚)
adb shell "cat /data/data/com.bitlockerdroid/files/logs/bitlocker.log"
adb exec-out "cat /data/data/com.bitlockerdroid/files/logs/bitlocker.log" > ./bitlocker.log

# 查看最近一次未捕获异常崩溃堆栈 (如有)
adb shell "cat /data/data/com.bitlockerdroid/files/logs/crash.txt"

# 查看持久化偏好设置
adb shell "cat /data/data/com.bitlockerdroid/shared_prefs/com.bitlockerdroid_preferences.xml"
```

---

## 七、自动化测试脚本示例

### 脚本 1：全自动加解密回环与 MD5 一致性验证
```bash
#!/bin/bash
set -e
URI="content://com.bitlockerdroid.provider"

# 1. 查找第一个已挂载根节点
ROOT_DOC=$(adb shell content query --uri $URI/root | grep "document_id=" | head -n 1 | sed -n 's/.*document_id=\([^,]*\).*/\1/p')
[ -z "$ROOT_DOC" ] && echo "错误: 未找到已解锁卷" && exit 1

# 2. 创建 10MB 测试文件节点
DOC_RES=$(adb shell content call --uri $URI --method create_document --arg "$ROOT_DOC" --extra display_name:s:autotest.bin --extra mime_type:s:application/octet-stream)
TARGET_DOC=$(echo "$DOC_RES" | sed -n 's/.*document_id=\([^, }]*\).*/\1/p')

# 3. 本地生成随机数据并写入卷
adb shell "dd if=/dev/urandom of=/data/local/tmp/src.bin bs=1M count=10 2>/dev/null"
SRC_MD5=$(adb shell md5sum /data/local/tmp/src.bin | awk '{print $1}')
adb shell "content write --uri $URI/document/$TARGET_DOC < /data/local/tmp/src.bin"

# 4. 从卷内回读并校验 MD5
adb shell "content read --uri $URI/document/$TARGET_DOC > /data/local/tmp/dst.bin"
DST_MD5=$(adb shell md5sum /data/local/tmp/dst.bin | awk '{print $1}')

if [ "$SRC_MD5" == "$DST_MD5" ]; then
    echo "✅ [通过] 数据 100% 逐字节一致，加解密回环正常！"
else
    echo "❌ [失败] MD5 不一致！SRC=$SRC_MD5 DST=$DST_MD5"
fi

# 5. 清理测试文件
adb shell content call --uri $URI --method delete_document --arg "$TARGET_DOC" > /dev/null
adb shell "rm -f /data/local/tmp/src.bin /data/local/tmp/dst.bin"
```

### 脚本 2：硬件加速 vs 软件模式性能对照测试
```bash
#!/bin/bash
URI="content://com.bitlockerdroid.provider"

echo "=== 1. 密钥拉伸基准测试 (1,048,576 轮) ==="
adb shell content call --uri $URI --method set_hardware_accel --arg true > /dev/null
HW_MS=$(adb shell content call --uri $URI --method benchmark_key_stretching --arg 1048576 | grep -o 'ms=[0-9.]*')
echo "  [ARMv8 硬件加速]: $HW_MS ms"

adb shell content call --uri $URI --method set_hardware_accel --arg false > /dev/null
SW_MS=$(adb shell content call --uri $URI --method benchmark_key_stretching --arg 1048576 | grep -o 'ms=[0-9.]*')
echo "  [纯软件模式]    : $SW_MS ms"

# 恢复硬件加速
adb shell content call --uri $URI --method set_hardware_accel --arg true > /dev/null
echo "硬件加速已重置为开启状态。"
```
