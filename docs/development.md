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

同日的第二轮把 `ChoreographerFrameDriver` 在 API 33+ 改为 `postVsyncCallback`，把 `preferredFrameTimeline.deadlineNanos` 复制进 `FrameBudget`（`estimated=false`），使 `UiScheduler` 的 deadline 检查在默认路径上生效；API ≤32 保持原路径。`estimated` 语义统一为“deadline 是否未知/仅估计”。该设备相关改动只验证了编译、lint 与 API 基线不变，真机行为需设备门禁。

同日的第三轮把 `UiScheduler.drain` 改为按 `estimated` 门控的派生预算：`estimated=false` 时取 `min(maxFrameWorkNanos, deadline - started - 1ms)`（soft deadline），`estimated=true` 时只用固定预算；`FrameBudget` 增加 fail-closed 校验（`estimated=false` 必须有 deadline），并要求上游 `FrameTick.deadlineNanos()` 与 `System.nanoTime()` 同基。`PropertyBinding.apply` 的死缓存收敛（`SHARED_READ`/`ALWAYS_WRITE` 不再做不被读取的 read-back/snapshot）依赖真实 View，未在 host 覆盖，需设备回归（`samples/standalone` 的 `shared_property_reads_external_write`、`switch_rejection_final_authority` 是现成入口）。

对比时保存改前/改后的源码状态和原始输出，在同一 JDK、堆参数、热身/迭代数下多进程交错运行，观察尾延迟及分配，不只挑最快一轮。HotSpot 逃逸分析可能消除临时对象，主机测量不可外推 ART；后台负载也会影响 p95/p99。此探针不是 Android 性能门禁。

2026-11-07 的这一轮把状态队列的值由 `ScheduledOperation` 改为内部可变 `StateEntry`，同槽重提不再新建对象；新增 `submitStates(List<StateUpdate>)` 作为「同语义集合」入口（一次 owner 校验、一次帧请求、批内去重，逐条准入与重复调用 `submitState` 等价）；`discardInvalid` 改用键快照，且只有在真的删除了条目时才重建分组集合。同一探针上 `state-coalesce,256` 从约 6,296 B 降到 152 B，`state-discard,256` 从 54,795 B 降到 18,704 B，`state-full`/`state-partial`/`state-reentrant` 留在噪声内。`state-coalesce` 的改前值由 size=16/64 的实测（22.5 与 23.6 B 每次提交）结合 `state-batch` 相对 `state-full` 的差值推算——size=256 的直接对照会被 HotSpot 逃逸分析消除；`state-discard` 的改前值是直接实测。口径与完整数据表由会话留存。

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

## 性能调研路线（2026-11-07）

本轮通过 Exa MCP 检索一手论文、AOSP 源码、Android 官方文档与开源实现（React/Flutter/Compose、JCTools/Agrona/Disruptor 等），覆盖帧调度与快路径、桥接与 IPC、语义批处理、无锁并发、Android 图形管线五个方向。完整报告与来源清单由会话留存，下表只保留可执行的结论，并标注证据等级：**已验证** = 官方文档 / AOSP 源码 / 可复现实测；**理论** = 论文或模型推导但未在本项目复现；**传闻** = 仅作数量级参考。

### P0 高收益 / 低成本

| 项 | 做法 | 依据 | 风险 |
| --- | --- | --- | --- |
| 跨线程提交入口 | 提供不阻塞的投递 API：有界聚合 + 主线程帧内 drain，替代业务自行 `Handler.post` 造成的重复排帧 | React/Flutter/Compose 均以帧为合并窗口（已验证）；Android 17 无锁 MessageQueue（DeliQueue）官方实测主线程锁竞争 −15%、app 掉帧 −4%、首帧 p95 −9.1%（已验证，[官方博客](https://developer.android.com/blog/posts/under-the-hood-android-17-lock-free-message-queue)） | 内存序与可见性；队列必须有界 |
| 动画时钟 | `ChoreographerFrameDriver` 在 API 33+ 把 `getPreferredFrameTimeline().getExpectedPresentationTimeNanos()` 暴露给动画插值使用 | Android 官方 `FrameData` 文档明确：`frameTimeNanos` 用于动画会在迟到帧后跳变，应改用动画时钟（已验证）。注意 `FrameData.getAnimationTimeNanos()` 是 hidden API，不要引用；公开等价物是 `FrameTimeline.getExpectedPresentationTimeNanos()`（API 33） | 需保留 API ≤32 回退 |
| 帧预算校准 | 用 `Display.getMode().getRefreshRate()` 与实测 tail 调整 `maxFrameWorkNanos` 与尾部预留 | Swappy 在慢会话把 jank 从 40% 降到 10%（已验证，游戏场景） | 预算过紧会把操作推到下一帧；需 Macrobenchmark |

### P1 高收益 / 中成本

| 项 | 做法 | 依据 |
| --- | --- | --- |
| 数据驱动预算 | 接入 `FrameMetrics` / `JankStats`，按 `frameOverrunNanos` 校准预算，并对外提供只读的每帧 overrun / deadline / 利用率统计 | Android 官方 API（已验证存在） |
| 渲染路径 | 文档与示例优先 `RenderNode` / `SurfaceView`，避免 `TextureView` 双重合成；transform/opacity 动画优先 `RenderNode` 属性 | AOSP HWUI 源码与官方文档（已验证） |
| 桥接语义批处理 | 跨桥调用提供批量接口，客户端在帧内收集后一次发送 | Android 官方批量调用建议；语义批处理在多篇论文中报告 5–200× 吞吐（已验证，其他系统） |

### P2 待真机

- ARR / `setFrameRate` 按场景降帧；着色器与 PSO 预编译消除首帧编译尖峰；`targetSdk 37` 升级评估（可获得的收益包括无锁 MessageQueue；需排查反射 `MessageQueue` 私有字段与修改 `static final` 字段，二者在 API 37 分别会失效与抛异常）。

### 明确不可依赖

- 虚拟线程（Project Loom）在 Android ART 上为 flag 实验，interpreter-only 且 JNI / `synchronized` 会 pin，不可用于生产。
- 厂商宣传的触摸延迟改善未在本项目复现，仅作数量级参考。

### 跨库联合调优

三库桥接模块互不 import，「桥接可选」在模块级成立（已核对源码，并经独立对抗复核确认）。经交叉复核**确认**的联合调优点：

- **background 归属无共同注册表**：Lumen 侧 `LumenSurfaceBridge` 用 `BindingScope.claim`，Drawable 侧 `RecipeBackground` 用独立 `BindingScope` 走 `UiSession.bind`，两者无交集，`LumenSurfaceSession` 遇到冲突时静默替换而不报错。混用两套桥时建议引入可选的共享登记处，默认关闭。
- **Lumen 表面 detach 即销毁且不自动重绑**：列表回收场景会出现抖动，建议桥层提供可配置的重绑策略。

以下说法经复核后**修正或降级**，不要按原样引用：

- 「三套同语义桥」只对 Lumen→View 绑定与 background 登记成立；三栈的 WindowSession 接入桥是不同库的适配器，不可互代。
- 「每次 post 约 15+ 分配」未实测：post 本身约 7 个对象，15+ 是 begin+post 往返的静态估算。
- `SkinPrefs.write` 确为主线程同步 `commit()` 且全量读回，但不是该路径唯一的磁盘 I/O。

## 验证边界

报告生成在各模块 `build/` 及根目录 `build/reports/`，不提交设备标识或运行回执。API 基线描述本候选的 JVM 公开签名，不是历史稳定版二进制兼容证明。

`verifyDevice` 需要已授权的真实设备及完整设备矩阵输入；缺少证据时应失败。`verifyReleaseCandidate` 是额外的完整验收门禁，需要公开仓库之外的项目选择、设备、性能及整合记录。公开源码不附带这些私有记录，因此默认 host 成功不能使发布门禁通过。

已进行 API 37 范围的设备验证；API 27/29、完整无障碍、OEM 与性能矩阵仍有缺项。声明最低 SDK 不等于每个版本均已通过设备认证。工具链的可构建结果也不等于所有组合均处于 Kotlin 官方完整支持矩阵中。

## 维护规则

依赖版本固定在版本目录、锁文件和校验元数据中。常规构建不使用 `--write-locks` 或 `--write-verification-metadata`；更新依赖时应审查实际坐标、来源、许可证与哈希。API 变化需审查 `api/` 基线及正负编译案例，不应仅为消除失败自动更新基线。

仅提交源码、构建配置、公共文档与通用测试夹具。凭据、签名、真实业务数据、本机路径、设备记录和内部工程材料保留在公开仓库之外。提交信息使用 Conventional Commits。

构建工具链与可选上游版本受已审查的兼容性及精确制品合同约束，因此仅在对应版本声明处抑制新版本提示；AGP 的定点抑制只表示有意固定工具链，不关闭其他 lint、类型、依赖完整性或哈希检查。

[返回文档目录](README.md)
