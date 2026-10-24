# SMART 状态与缺失指标验证

日期：2026-10-24。代码提交：`a42b7e6`、`fbb0415`。

## 本次改动

- Root 命令失败、无授权、空响应、解析失败或未知状态不再推断为硬件不支持。临时失败不缓存为“不支持”，主页保留手动查询入口。
- Root 查询失败后不再经 JNI 重复探测。非 Root USB 仍只识别已验证桥接器，显式查询仍要求安全关闭卷，没有增加探测命令或自动恢复动作。
- JSON 的空值、非法数值、小数计数和溢出计数保持未知；实际返回的零值保留。
- SATA 原生读取按属性是否存在输出数值或 `null`。缺少风险计数时不推断整体健康良好；读取失败保持待确认。
- NVMe 温度字段为 0 K 时表示未提供；273 K 正确显示为 0°C。
- 原始表的缺失值显示为 `—`、状态为 `U`；不补默认温度、健康度、错误计数、标准化值或阈值。ATA 实际提供的标准化值继续保留。
- 部分遥测已可读取但整体状态不能确认时，界面显示“健康状态待确认”；未取得有效结果时显示“尚未确认 SMART 支持”。明确的不支持结果仍单独处理。

关键文件：

- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartHealthManager.kt`
- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartDataParser.kt`
- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartHealthInfo.kt`
- `app/src/main/kotlin/com/bitlockerdroid/ui/dialogs/SmartHealthDialog.kt`
- `native/daemon/smart_reader.c`

## 已验证

| 检查 | 结果 |
| --- | --- |
| `:app:testReleaseUnitTest` | 111 项通过，无失败或错误；包括新增 12 项状态、缺失值和原始表测试 |
| 原生 `smart_reader_test` | Android NDK 交叉编译，`-Wall -Wextra -Werror`，在平板执行通过 |
| `:app:assembleDebug :app:assembleRelease :app:lintVitalRelease` | 通过，包含 arm64-v8a、armeabi-v7a、x86_64 原生构建 |
| 小米平板 6SP Release versionCode 147 | 主页入口“磁盘健康”、详情标题“磁盘健康（SMART）”正确；Root 查询现有 RTL9210 PSSD 显示健康良好、40°C、99% |

JVM 测试覆盖空/坏响应、矛盾状态、旧错误响应、明确不支持、部分遥测、异常数值、Root 失败不重复调用 JNI、取消传播、真实零值、缺失/损坏原始页及未知阈值。原生测试覆盖 SATA 缺失属性与真实零值、只有寿命属性时的待确认状态、空/全 FF 页、NVMe 缺失温度与 0°C、不存在设备路径的失败状态；原有桥接白名单与日志校验测试继续通过。

原生测试使用内存样本、临时目录和不存在的路径，没有对 PSSD 发送测试命令。

## 设备与数据保留

- 全程使用无线 ADB，Wi-Fi 正常。
- 更新测试包前先同步并关闭六卷，确认会话为 0 后覆盖安装；没有执行停盘或卸载应用。完成后六个 Root 卷恢复。
- 本地测试副本沿用设备现有签名以保留数据；设备运行 Release，无 DEBUGGABLE 标记。
- 全部 50 项配置与凭据记录的指纹与更新前一致，没有显示、复制或删除密码，没有操作磁盘文件。
- 显示参数保持原始 2032×3048、400 dpi、字体缩放 1.0、自动旋转开启，没有修改覆盖值。
- 本地证据目录：`.gradle/verification/smart-unknown-20261024/`；包含配置指纹、更新后的设备状态与 Root 界面检查结果，不纳入版本控制。

## 验证边界

- 本次实际介质为 NVMe PSSD；SATA 缺失属性与错误路径通过样本验证，未用 SATA 实盘复测。
- 未人为断开 USB、撤销 su 授权或制造传输超时；这些分支依靠回归测试验证。
- 没有增加查询时间、历史趋势或告警推送，也未重新执行双模式长时间读写和低分辨率布局测试。
