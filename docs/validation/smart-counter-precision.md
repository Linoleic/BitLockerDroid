# SMART 计数精度与告警规则验证

日期：2026-10-24。代码提交：`198177e`（原生输出）、`d638c10`（解析、界面和原始表）。前一轮缺失值验证见 [smart-result-classification.md](smart-result-classification.md)。

## 实现

- Root 原生读取完整 16 字节 NVMe 计数，使用十进制计算保留全部位数；读写总量乘 512000 后仍保持精确值，不截断为 64 位。计数以十进制字符串传入 JSON，避免 Android JSON 解析通过 Double 丢失精度。
- `SmartCounter` 定义计数字段、原始页偏移和换算单位。Root 解析与免 Root 解码均填充 `exactCounters`，使用 `BigInteger`；详情和原始表通过 `counterValue` 获取完整值。超出 Long 的兼容字段留空，不填截断值或 Long.MAX_VALUE。
- NVMe 原始表保留完整 128 位十六进制计数，不再截成低 48 位；仅接受完整 512 字节原始页。摘要的 TB/GB 显示按十进制单位舍入，完整值仍保留在精确计数和原始表中。
- 两种模式采用同一整体等级规则：控制器 critical warning 非零为严重告警；备用空间低于返回阈值或已用寿命达到 100% 为警告；其余为健康良好。等于备用空间阈值不额外猜测异常，已用寿命超过 100% 不单独推断设备损坏。这是本应用的等级策略。
- ATA 原始属性没有读取对应阈值，状态统一为未知，继续保留实际标准化值和原始值。错误累计计数不直接标成“损坏”。没有控制器温度告警时，不再用固定温度猜测硬件告警；不再根据不安全关机次数超过 50 来改变指标颜色。

依据：NVMe 计数完整宽度和换算参考 [smartmontools 的实现](https://www.smartmontools.org/static/doxygen/nvmeprint_8cpp_source.html)；控制器告警位和备用空间阈值含义参考 [Kingston NVMe SMART 属性说明](https://media.kingston.com/support/pdf/ssd-smart-attribute.pdf)。本次没有增加 USB 命令、阈值查询或探测重试。

关键文件：

- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartCounter.kt`
- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartDataParser.kt`
- `app/src/main/kotlin/com/bitlockerdroid/smart/SmartHealthInfo.kt`
- `app/src/main/kotlin/com/bitlockerdroid/smart/UsbSmartProbe.kt`
- `app/src/main/kotlin/com/bitlockerdroid/ui/dialogs/SmartHealthDialog.kt`
- `native/daemon/smart_reader.c`

## 自动验证

- `:app:testReleaseUnitTest`：121 项通过，无失败或错误。新增测试覆盖完整大计数、缩放后字节数、Root 十进制字符串与免 Root 日志解码一致、未知值、截断页、ATA 未知阈值、NVMe 告警边界。
- 原生 `smart_reader_test`：NDK 交叉编译，开启 `-Wall -Wextra -Werror`，在小米平板 6SP 执行通过。覆盖 2^48、2^64、2^127、128 位全 FF、乘 512000、带引号的 JSON 计数、小缓冲拒绝，以及各控制器告警位和阈值边界。
- `:app:assembleRelease :app:lintVitalRelease`：通过，包含 arm64-v8a、armeabi-v7a、x86_64 原生构建。
- 测试夹具使用内存数据、临时目录和不存在的路径，没有对实际 PSSD 制造超时或错误。

## 实机验证

- 小米平板 6SP，无线 ADB，Release versionCode 150；实际介质仍为 RTL9210 NVMe PSSD。
- 更新包前同步关闭六卷，确认会话为 0 后覆盖安装；没有停盘、卸载应用或删除凭据。随后恢复六个 Root 卷。
- 全部 50 项配置及凭据记录指纹与更新前一致，Wi-Fi 正常；显示仍为 2032×3048、400 dpi、字体缩放 1.0，无覆盖值。
- Root 详情显示健康良好，原始表 15 项正常展开；10 项宽计数均显示完整 32 位十六进制。通电时间、通电次数、不安全关机次数及写入总量的摘要与原始表换算一致；温度未读取厂商阈值，原始表状态为未知。查询前后同一进程保持运行，六卷正常。
- 本地证据目录 `.gradle/verification/smart-precision-20261024/` 不纳入版本控制。

## 验证边界

- 大计数和异常等级使用夹具覆盖；实际 PSSD 没有被改造成异常状态。
- 本次未在实际设备上切换到免 Root 执行 SMART 查询；免 Root 路径通过模拟 BOT 通道测试，既有命令数量和恢复行为断言继续通过。
- ATA 阈值读取和 SATA 实盘复测仍未完成；后续已补来源标注并移除未经验证的厂商属性推算，见 [数据来源验证](smart-data-provenance.md)。
- 本次没有重新执行低分辨率和双模式长时间读写回归。
