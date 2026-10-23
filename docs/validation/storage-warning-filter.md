# 系统修复提示过滤与 Root 入口合并

## 影响范围核对

旧代码仅按系统包名和卷 tag 取消通知，还执行 `sm unmount` 以及长期 snooze。
因此不能据旧实现保证只影响格式化提示，现已删除这些副作用。

新规则同时要求：

- Root 模式已启用且 su 已授权。
- 来源为 `com.android.systemui` 或 `android`，通知 ID 为 `0x53505542`，
  通道为 `DSK`，类别为 `err`，tag 是精确的 `public:主设备号,次设备号`。
- 系统当前卷状态为 `unmountable`。
- 重新读取当前块设备并验证 BitLocker 元数据及非零卷 GUID。
  `MSWIN4.1` OEM 字符串或回退 GUID 单独出现均不足以通过。

只取消当前匹配通知，不关闭通知通道、不卸载卷、不长期延后通知。
验证失败、未知状态或厂商通知格式不匹配时保留原通知。元数据读取参加模式切换屏障，
不会在驱动交接期间另行开启读取。应用自身的解锁、读写、弹出错误提示不在过滤范围。

Android 将多种公共卷状态复用同一通知 ID，因此仅匹配 ID/tag 不够，必须再检查状态及类别。
参考 [Android 16 StorageNotification](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/packages/SystemUI/src/com/android/systemui/usb/StorageNotification.java)
和 [系统通知 ID 定义](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/proto/src/system_messages.proto)。

## 界面与默认行为

Root 环境详情合入主页“连接与授权”；设置中的同名入口和 Root 高级入口删除。
过滤在 Root 模式自动启用，面板只说明行为，不提供开关；免 Root 模式不启用过滤。
旧 `suppress_corrupt_notification` 偏好不再控制该功能。

## 2026-10-23 验证

- 小米平板 6SP、RTL9210 PSSD，6 个 Root 块设备会话。
- 6 条真实的 SystemUI 修复提示减少为 0。
- 在稳定状态再次刷新过滤，原有 3 条其他系统通知全部保留。
- 主页面板包含 Root 环境、KernelSU 详情和自动过滤说明，没有过滤开关；
  设置中没有重复入口。
- 保存凭据不变，6 个 Root 会话和 Wi-Fi 正常；测试未请求 USB 设备复位。
- JVM 回归覆盖非目标通知、普通 FAT OEM、未知卷状态、正常挂载、进度、弹出和异常拔出。

此结果覆盖当前平板的通知格式。其他 ROM 若使用不同标识，提示会继续显示，需取得
对应格式后再添加精确规则，不能扩大为整个存储通道或模糊包名匹配。
