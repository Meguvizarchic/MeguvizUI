# 构建、验证与贡献

## 常用命令

Python 3.11+ 用于标准库验证脚本，JDK 17 与 Android SDK 配置见 [安装说明](installation.md)。

```sh
./gradlew verifyHost :samples:standalone:assembleDebugAndroidTest --no-daemon --max-workers=2
./gradlew publishCandidate --no-daemon --max-workers=2
```

`verifyHost` 实际执行核心合同测试、Android lint、示例构建、发布 AAR 正负编译、JVM API 基线比较与核心依赖边界检查。可选桥开启时增加对应模块验证，Android 还需导入固定哈希的上游候选，Ref 1.0.0 直接从 Maven Central 解析。

Windows 可用 `gradlew.bat`；遇到 JDK Unix-domain socket 临时目录问题时，可用 `scripts/gradle-local.ps1`，它仅对当前进程设置工程内临时目录。

## 调度器专项测量

不新增生产或基准依赖；探针直接运行当前编译的正式 `UiScheduler`，使用 JDK `ThreadMXBean` 计数当前线程分配：

```sh
./gradlew :ui-core:schedulerProbe --offline --no-daemon --max-workers=2 --no-configuration-cache
./gradlew :ui-core:schedulerProbeV2 --offline --no-daemon --max-workers=2 --no-configuration-cache
# 可选：-PprobeWarmup=10000 -PprobeIterations=10000
```

Windows 将相同参数传给 `scripts/gradle-local.ps1`。该任务先运行核心合同，再编译 [SchedulerProbe.java](../scripts/performance/SchedulerProbe.java)。CSV 覆盖 16/64/256 个独立状态、同组状态、独立命令和批次，记录每轮提交加一次 drain 的 p50/p95/p99 与分配字节；不包含原生 setter、绘制、跨线程传输或设备调度延迟。输入槽位与批次操作预建，所有实现使用相同夹具；Java 侧还检查 vararg 输入数组在准入后被调用者修改不会改变队列。

第二轮的 [SchedulerProbeV2.java](../scripts/performance/SchedulerProbeV2.java) 另列提交加 drain、仅 drain、部分 drain 和重入场景，并在计量前核验 FIFO、合并、公平与故障恢复。调度器复用的是实例私有的组索引与空闲队列缓冲，帧结束清掉 binding、slot 和闭包引用；命令-only 帧跳过状态索引。不能据此把整个调度链称为零分配。

相对固定基线 `dee57b5`，同一 JDK 17 主机夹具的 256 状态每轮分配约 40,546 → 16,467 B；仅 state drain 场景约 24,096 → 14–15 B。每轮包含的工作不同，不能跨两行直接比较；这是探索性 HotSpot 分配结果，不是 ART、帧耗时或设备性能承诺。完整正确性还由容量 13、哈希碰撞、相等不同实例、公平轮转、重入与索引清引用合同锁定。

2026-11-05 的另一轮（命令快路径、`submitState` 单哈希、分组集合复用、帧身份序列）在同一探针上把 `command-only,256` 从 20,538 B 降到 6,184 B、`state-partial,256` 从 34,400 B 降到 21,728 B，其余场景在噪声内。仍是 host 分配证据，不含设备结论。

对比时保存改前/改后的源码状态和原始输出，在同一 JDK、堆参数、热身/迭代数下多进程交错运行，观察尾延迟及分配，不只挑最快一轮。HotSpot 逃逸分析可能消除临时对象，主机测量不可外推 ART；后台负载也会影响 p95/p99。此探针不是 Android 性能门禁。

为验证兄弟 runtime 的未发布改动，可把审查并核对 SHA-256 的候选 AAR 放入独立空目录，只提取 `classes.jar`，再运行：

```sh
./gradlew -PenableAndroid=true -PruntimeCandidateClasses=/absolute/path/to/classes.jar \
  :ui-android-bridge:testRuntimeCandidate --offline --no-daemon --no-configuration-cache
```

任务先运行固定上游制品的桥合同，再单独把指定 runtime 放在 JVM 测试类路径首位；合同会断言 `FrameScheduler` 实际来自该文件。不会替换 `build/upstream-repo` 的 Maven metadata、pins 或共享仓库。这仅证明“指定新 runtime + 原固定 window + 当前 UI”的主机接口合同，不能当作新 window 或三库设备端到端证据。

只有同时显式提供 Drawable core 候选时，才注册三库联合主机合同：

```sh
./gradlew -PenableAndroid=true \
  -PruntimeCandidateClasses=/absolute/path/to/runtime/classes.jar \
  -PdrawableCandidateJar=/absolute/path/to/meguviz-drawable-core.jar \
  :ui-android-bridge:jointPipelineProbe --offline --no-daemon --no-configuration-cache
```

[JointPipelineProbe.java](../scripts/performance/JointPipelineProbe.java) 检查真实候选加载位置、同键准备只执行一次、后台不写 UI、owner 交付后同槽合并、一次上游帧完成有界 drain、缓存命中同步处理和取消清理。它不执行 Android 绘制、不安装设备，也不证明 ART 性能。该探针及其编译任务均不属于默认 `check`/`verifyHost`，不向任何库的生产依赖添加兄弟库；单库用户完全不需要它。

研究依据采用一手资料：[Disruptor 论文（Thompson 等，2011）](https://lmax-exchange.github.io/disruptor/files/Disruptor-1.0.pdf) 关于批量消费和预分配的讨论、[JCTools MPSC 实现](https://github.com/JCTools/JCTools/blob/master/jctools-core/src/main/java/org/jctools/queues/MpscArrayQueue.java) 的单消费者约束、[Go singleflight](https://github.com/golang/sync/blob/master/singleflight/singleflight.go) 的同键结果共享，以及 [Choreographer](https://developer.android.com/reference/android/view/Choreographer) 的 Looper 归属。这些资料用于选择设计与验证方法，不代表本库达到其服务器基准收益；本轮 Exa MCP 授权失败，以上来源为直接阅读，不计为 Exa 检索结果。

## 验证边界

报告生成在各模块 `build/` 及根目录 `build/reports/`，不提交设备标识或运行回执。API 基线描述本候选的 JVM 公开签名，不是历史稳定版二进制兼容证明。

`verifyDevice` 需要已授权的真实设备及完整设备矩阵输入；缺少证据时应失败。`verifyReleaseCandidate` 是额外的完整验收门禁，需要公开仓库之外的项目选择、设备、性能及整合记录。公开源码不附带这些私有记录，因此默认 host 成功不能使发布门禁通过。

已进行 API 37 范围的设备验证；API 27/29、完整无障碍、OEM 与性能矩阵仍有缺项。声明最低 SDK 不等于每个版本均已通过设备认证。工具链的可构建结果也不等于所有组合均处于 Kotlin 官方完整支持矩阵中。

## 维护规则

依赖版本固定在版本目录、锁文件和校验元数据中。常规构建不使用 `--write-locks` 或 `--write-verification-metadata`；更新依赖时应审查实际坐标、来源、许可证与哈希。API 变化需审查 `api/` 基线及正负编译案例，不应仅为消除失败自动更新基线。

仅提交源码、构建配置、公共文档与通用测试夹具。凭据、签名、真实业务数据、本机路径、设备记录和内部工程材料保留在公开仓库之外。提交信息使用 Conventional Commits。

构建工具链与可选上游版本受已审查的兼容性及精确制品合同约束，因此仅在对应版本声明处抑制新版本提示；AGP 的定点抑制只表示有意固定工具链，不关闭其他 lint、类型、依赖完整性或哈希检查。

[返回文档目录](README.md)
