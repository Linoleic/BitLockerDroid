# v1.2.0 发版验证

日期：2026-10-24。发版源码提交：`a2e2898b9800b4613a71166ddb592555c36bc0b0`，versionName `1.2.0`，versionCode `160`。

## 构建与签名

- `:app:testReleaseUnitTest :app:assembleRelease :app:assembleDebug :app:lintVitalRelease` 通过；133 项 JVM 测试，0 失败、0 错误，三种 ABI 原生构建通过。
- 从 GitHub v1.1.1 下载原正式 APK，SHA-256 与 Release 资产摘要一致：`5e8df7649b927e4ddbaa0a10ccc19c98cfb8d331e8efa39bc56ef918cec34cd8`。
- v1.1.1 与 v1.2.0 正式 APK 均通过 apksigner 校验，签名证书 SHA-256 相同：`88e6cb9fa807951be33cbbb61b226affc558c3d356d88cb93ed4595be257612c`。
- 正式附件直接使用 Release 构建产物，不使用平板测试重签副本。

## 正式签名覆盖升级

- 创建独立临时模拟器，使用本机已有 Android SDK 37 x86_64 系统镜像，实际页大小 16384 字节；没有改动用户已有 AVD。
- 安装 GitHub 原始 v1.1.1 / versionCode 109，使用已有 ADB 接口将 AES、SHA-2 两项偏好设为关闭，并核实 0 个解锁会话。
- 停止应用后，以 `adb install -r --no-incremental` 安装正式签名的 v1.2.0 / versionCode 160，未卸载、清数据或重签。
- 升级成功，UID 与首次安装时间不变，两项偏好仍为关闭。应用启动、卷信息查询及原生加密能力接口正常，进程保持运行，保留日志未见致命异常。
- 模拟器未连接 USB 磁盘、没有保存凭据；此项验证覆盖包签名兼容、配置保留和启动，不代表在模拟器验证了真实磁盘或旧凭据解密。

## 平板数据保留

- 小米平板 6SP 原为同源码系列 Release 测试包 versionCode 158。先通过既有同步关闭流程确认 0 个会话，再覆盖安装沿用测试签名的 v1.2.0 副本。
- Root 六卷以保存凭据恢复，50 项配置及凭据记录指纹与更新前一致，未改变原自动解锁行为。
- 显示保持物理 2032×3048、400 DPI，无覆盖值；字体缩放 1.0，旋转设置与基线一致。Wi-Fi 保持启用，最终包无 DEBUGGABLE 标志。
- 没有卸载应用、重置 USB / ADB 或操作 PSSD 文件。平板测试副本仅用于保留数据验证，不作为发布附件。

## 附件清单

产物目录：`app/build/outputs/releases/v1.2.0/`（Git 忽略）。

| 文件 | SHA-256 |
| --- | --- |
| app-release.apk | `640a49f443ce495d41c52f4b041d2ec8f1b895b98e6938204b325a117279c67a` |
| app-debug.apk | `c6d2dc21b80211c8c72b40d44886b806b2e58f2dc2dc88e18a7234f1a68231b2` |

- 独立的 `checksums.txt` 附件已按用户要求移除；保留上述验证记录，附件摘要使用 GitHub 提供的信息。
- `release-notes.md` 由 [版本说明](../releases/v1.2.0.md) 复制，更新正文与 Changelog 的 v1.2.0 中英文原文一致，之后附 APK 用途及 Debug 滚动开销说明。
- 本地证据：`.gradle/verification/release-1.2.0-20261024/`。正式附件与平板测试副本分开保存。

核心双模式读写及弹出验证见 [大文件回归](pssd-large-file-eject.md)，新版详情与空闲查询见 [S.M.A.R.T. 复测](smart-layout-idle-query.md)。本轮未扩大既有硬件支持声明。
