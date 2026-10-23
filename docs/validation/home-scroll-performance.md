# 主页滚动卡顿排查

日期：2026-10-23。代码基线：`b2f4f86`。

## 已确认的事实

- 小米平板 6SP、Android 16，无线 ADB，Root 模式连接 PSSD，6 个卷已解锁。测试使用当前 Debug 构建。
- `debugRuntimeClasspath` 实际解析为 Compose UI **1.7.5**，BOM 为 `2024.10.01`。
- 主页使用 `Column.verticalScroll`，一次创建整组分区卡片。入口：`app/src/main/kotlin/com/bitlockerdroid/ui/volumes/VolumesTabContent.kt`；分区卡片布局：同目录的 `DeviceGroupCard.kt`、`UnlockedVolumeCard.kt`。
- 平板启用了系统截屏和 SCENE 辅助服务。初次滚动追踪中，`sendAccessibilitySemanticsStructureChangeEvents` 单次耗时中位数约 **29ms**；后续原构建采样约 **58ms**。GPU 绘制中位数通常为 3–4ms。
- ART 方法采样指向语义节点可见范围计算、遍历顺序排序及坐标变换：`getAllUncoveredSemanticsNodesToIntObjectMap`、`subtreeSortedByGeometryGrouping`、`GraphicsLayerOwnerLayer.mapBounds` 等。
- 从本机 Gradle 缓存中的 `ui-android-1.7.5-sources.jar` 核对：可见语义节点失效后，Compose 会重新计算节点范围及遍历顺序；滚动布局变化可触发这条路径。

## 同一进程的短时对照

恢复原代码构建后，保持 6 个卷及滑动坐标不变。每轮先回到顶部，再交替上下滑动 12 次，每次 450ms，间隔 150ms；同时记录 `atrace gfx view` 和 `dumpsys gfxinfo ... framestats`。

| 无障碍设置 | 帧耗时 P50 | P90 | P95 | gfxinfo 掉帧比例 |
| --- | ---: | ---: | ---: | ---: |
| 原设置开启 | 77ms | 133ms | 150ms | 50.00% |
| 临时暂停服务 | 46ms | 81ms | 89ms | 50.00% |
| 恢复原设置 | 77ms | 150ms | 150ms | 49.31% |

**结论：无障碍语义处理是已测得的重要开销，但不能解释全部卡顿。** 暂停服务后仍有较高帧耗时，掉帧比例也没有明显改善。不能把关闭系统辅助服务作为修复办法，也不能仅凭本次数据认定某一个服务存在缺陷。

## 已撤回的试验

- 把硬盘头和每个分区拆成独立 LazyColumn item：未观察到稳定收益，追踪还出现额外卡片创建和布局开销。
- 移除整组卡片上的空 `graphicsLayer`：未观察到稳定收益。
- 上述应用代码改动均已撤回，设备恢复为原代码构建。不同构建之间未严格控制 JIT 和温度，试验不能用于认定某种列表组件普遍更慢。

## 下一步优先验证

1. 使用 Release 构建重复同一组测试，确认 Debug/JIT 对矩阵及语义遍历开销的影响；目前正式包表现未知。
2. 针对纯文本区块验证语义合并与遍历分组，保留按钮和开关的独立可访问性；以帧耗时和实际辅助操作同时验收，不隐藏子控件。
3. 分开追踪暂停辅助服务后仍然较重的动画回调。不能把其全部耗时归到无障碍处理。
4. 单独测量刷新期间的分组计算：`DeviceGroupBuilder.buildGroups` 当前在 composition 中执行，并可能读取设备信息。这是已发现的主线程风险，尚未证明它造成本次连续滚动卡顿。

本轮未验证升级 Compose 的效果，未保留性能修复，也未修改发布日志。

## 验证与恢复

- 原代码 Debug APK 构建成功；没有新增业务逻辑，因此未重复 JVM 测试。
- 每次安装前安全关闭卷会话；只对已核实的 PSSD SCSI host 重新发现设备。
- 最终 6 个 Root 卷恢复，Root/自动解锁设置恢复，系统无障碍设置逐项与备份一致，Wi-Fi 保持开启，主页返回顶部。
- 本机原始追踪、帧数据、采样脚本和对照结果保存在被 Git 忽略的 `.gradle/verification/home-scroll/`。
