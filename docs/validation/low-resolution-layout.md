# 低分辨率界面实机检查

日期：2026-10-24。代码基线 `7185ab7`。**发现 4 处布局问题，尚未修复；原显示参数、Release 包和应用设置已恢复。**

## 原始参数与测试方法

- 小米平板 6SP，Android 16，无线 ADB。
- `wm size`：`Physical size: 2032x3048`，无覆盖值。
- `wm density`：`Physical density: 400`，无覆盖值。
- 字体缩放 `1.0`；`accelerometer_rotation=1`，`user_rotation=0`。
- 初始 Release versionCode 137 启用 `FLAG_SECURE`，截图中的应用区域为黑色。因此使用同源码 Debug versionCode 140 做视觉检查，结束后恢复同签名 Release versionCode 140；没有修改正式防截图逻辑。
- 更换测试包前，临时关闭自动解锁，经现有准备流程同步并关闭六卷，确认 0 个会话后更新；不发送停盘命令，不重新扫描停盘设备。进入 Debug 后恢复自动解锁和六卷，用真实已解锁卡片检查布局。
- 临时执行 `wm size`、`wm density` 和 `wm user-rotation lock`；每个配置的脚本均通过 `finally` 恢复原显示参数。全程保持 Wi-Fi 在线。

| 截图实际像素 | DPI | 按像素和密度折算的空间，含系统栏 | 结果 |
| --- | --- | --- | --- |
| 720×1280，竖屏 | 320 | 360×640dp | 已检查页面和弹窗可操作；未观察到明显截断 |
| 480×800，竖屏 | 240 | 320×533dp | 主页标题裁切、全部弹出按钮竖排 |
| 800×480，横屏 | 240 | 533×320dp | 模式确认正文裁切、凭据列表不可见 |

检查主页、连接与授权、模式切换确认、设置首页/底部、凭据管理弹窗及关闭/返回操作。模式确认只打开并取消；没有因检查而实际切换模式，没有显示、复制或删除凭据，也未操作磁盘文件。

## 已确认的问题

1. **320dp 窄屏主页标题裁切。** `BitUnlocker` 折成两行，副标题下部被顶部栏裁切。授权入口与刷新仍可点击。截图：`wvga480-home.png`。
2. **320dp 窄屏的“全部弹出”被挤成四行。** SMART 长按钮与弹出按钮位于同一行，后者变为“全 / 部 / 弹 / 出”，按钮异常增高。截图同上。
3. **短横屏模式确认正文不完整。** “是否关闭所有卷并重启应用？”在截图中不可见，正文没有滚动容器；取消与确认按钮仍可点击。界面树仍有完整文字，说明不能仅凭文本存在判定显示通过。截图：`landscape480-mode-confirm.png`。
4. **短横屏凭据列表不可用。** 弹窗显示“共 12 个设备凭据”，但正文列表被图标、标题和底部按钮挤掉，界面树没有首条 `NT+C`；只剩标题及清空/完成按钮，无法浏览记录。截图：`landscape480-credentials.png`。

## 源码对应与后续方向

以下是与现象对应的布局代码及建议，尚未实施：

- `app/src/main/kotlin/com/bitlockerdroid/ui/BitLockerSettingsActivity.kt:798`：固定顶部栏中标题 Column 没有窄屏策略。应限制标题行数，按有效宽度调整副标题或标题尺寸。
- `app/src/main/kotlin/com/bitlockerdroid/ui/volumes/DeviceGroupCard.kt:315`：紧凑布局的两个动作仍用普通 Row。应在宽度不足时换行或缩短 SMART 可见文案，保证弹出按钮的最小可用宽度。
- `app/src/main/kotlin/com/bitlockerdroid/ui/dialogs/SwitchModeConfirmDialog.kt:32`：正文 Column 没有滚动。应在保持底部按钮可见的前提下，让正文可滚动。
- `app/src/main/kotlin/com/bitlockerdroid/ui/dialogs/CredentialsManagerDialog.kt:111`：图标和标题在短窗口占用过多高度；列表只有最大高度约束。应优先保证列表空间，短横屏收紧标题或采用更宽/全屏布局。

建议先处理凭据列表不可见，再处理标题、动作行和模式确认正文，之后按同一组参数复测。本轮没有修改布局代码，不能记为界面兼容性通过。

## 恢复与证据

- 恢复 `2032x3048 / 400dpi`，分辨率和密度覆盖值均不存在；字体缩放、自动旋转和用户旋转值与原记录一致。
- 恢复 Release versionCode 140，确认无 DEBUGGABLE 标志。
- 全部 **50 项**应用偏好哈希与原记录一致，包含原先不存在的自动解锁偏好键；Root 六卷正常，Wi-Fi 在线。
- 原始记录、各配置 PNG/界面树、交互结果和 `final-state.json` 保存在本机忽略目录 `.gradle/verification/low-resolution-20261024/`。截图包含卷标识等设备信息，未加入 Git。
- 此方法模拟像素和密度，仍使用平板系统栏、挖孔/窗口边距和系统行为，不能代表所有真实低分辨率手机。本轮未验证键盘弹出、放大字体、其他语言或低端设备性能。
