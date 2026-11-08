# 构建、验证与贡献

## 常用命令

Python 3.11+ 用于标准库验证脚本，JDK 17 与 Android SDK 配置见 [安装说明](installation.md)。

```sh
./gradlew verifyHost :samples:standalone:assembleDebugAndroidTest --no-daemon --max-workers=2
./gradlew publishCandidate --no-daemon --max-workers=2
```

`verifyHost` 实际执行核心合同测试、Android lint、示例构建、发布 AAR 正负编译、JVM API 基线比较与核心依赖边界检查。可选桥开启时增加对应模块验证，Android 还需导入固定哈希的上游候选，Ref 1.2.0 直接从 Maven Central 解析。

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

2026-11-07 的另一轮落地了跨线程提交入口。`UiScheduler` 增加可选的 `OwnerThreadDispatcher`（默认 `null`，保持纯 owner 线程独占，`submit*Async` 返回 `CLOSED`），并新增 `submitStateAsync` / `submitCommandAsync`：后台提交进入有界 handoff（上界 = `stateCapacity` / `commandCapacity`，按 slot 复用 `StateEntry`），**一次 owner 派发服务任意数量更新**；`drain()` 开头把暂存工作并入本帧（`mergeHandoff`），装不下的留在 handoff 等队列腾位后的下一次 merge，绝不丢弃。`UiSession` 用主 `Handler` 装配派发器，并暴露同名的 `submitStateAsync` / `submitCommandAsync` 简洁入口。同步快路径一行未改。

`SchedulerProbeV2`（JDK 17，2000 热身 / 4000 迭代，中位数）每轮分配：

| 场景 | 同步 | 异步 |
| --- | --- | --- |
| N=256 个不同槽位 | 16,457 B（`state-full,256`） | 34,968 B（`state-async,256`） |
| N=256 次同一槽位 | 152 B（`state-coalesce,256`） | 240 B（`state-async-coalesce,256`） |

口径说明：

- 异步路径的收益是 **owner 派发次数**：N 次后台提交只产生 1 次 owner 派发 + 1 次合并；业务自行 `Handler.post` 是 N 次派发。单线程探针测不出派发成本，上表只反映分配。
- 不同槽位的突发比同步路径多一倍 map 操作（handoff 插入 + 合并插入），这是分流暂存的固有成本；同槽位突发因为复用 `StateEntry` 而接近零分配（暂存命中时改为原地改写，`-coalesce` 场景因此从 6,360 B 降到 240 B）。
- `state-full` / `state-partial` / `state-reentrant` 等既有场景数值与改动前一致，说明同步快路径没有回退。

行为由 `CrossThreadSubmissionTest`（16 例）锁定：owner 队列只在 owner 线程被触碰、handoff 有界、拒绝原因与同步入口一致（PAUSED/FAULTED/CLOSED/容量）、动作在 owner 线程执行、worker 线程提交不会在 worker 上跑动作、状态与命令共用一次派发、暂存工作并入当前帧、队列满时被接受的更新重试而非丢弃、无派发器时为 `CLOSED`、派发被拒时丢弃 handoff 并返回 `CLOSED`（调用方与队列状态一致）、非 ACTIVE 时 `mergeHandoff` 不入队、8 生产者 × 200 次同槽压测无丢失、异步计数与同步口径一致。

2026-11-07 的再一轮处理对抗审查点名的第一热点：`PropertyBinding.close` 无条件调用 `discardInvalid()`，而 `UiSession.releaseNode` 的扫描又排在 `runCleanup` 之前，于是一次子树释放会扫描 B+1 次（B 为关闭的绑定数），每次 O(S+C)。现在 `UiSession` 用私有 `discardBatch` 计数把 `rebind`/`releaseNode` 的清理包成批次，逐绑定关闭跳过自己的扫描、由批次在 cleanup 之后统一扫描一次；`releaseNode` 的扫描也移到 `runCleanup` 之后——绑定关闭才是让其排队工作失效的原因，原来的顺序正是迫使每个 `PropertyBinding.close` 再扫一遍的根因。

代价与限制：新增 1 个 mangled 内部方法 `discardForBindingClose$ui_core`（本仓基线此前没有 mangled internal，这里为一个显著的释放风暴收益付出最小 ABI 面）。批量抑制需要真实 View/Context，**只能设备验证**，本轮未测。

同轮复核后**刻意未做**的一项：`discardInvalid` 命令循环里的 `queue.contains(entry)` 与 `queue.remove(entry)` 在 `ArrayDeque` 上都是线性，多命令同时失效时是 O(C²)（实测 `command-discard-stale,256` 的 p99 从 4.2µs 劣化到 46.7µs，分配与中位数不变）。没做的原因：那个 `contains` 同时是校验器重入（`close()`/`pause()`/嵌套 `discardInvalid`）期间的防护——去掉它会让队列已清空或被嵌套调用搬走的条目被重复计数，修它需要引入能跨嵌套调用可见的「仍在队列中」标记，风险高于收益。留待有设备与压测条件时再评估。

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
| ~~跨线程提交入口~~ **已落地 2026-11-07** | `UiSession.submitStateAsync` / `submitCommandAsync`：有界 handoff + 一次 owner 派发 + 帧内合并，替代业务自行 `Handler.post`。详见下方「2026-11-07（跨线程提交入口）」 | React/Flutter/Compose 均以帧为合并窗口（已验证）；Android 17 无锁 MessageQueue（DeliQueue）官方实测主线程锁竞争 −15%、app 掉帧 −4%、首帧 p95 −9.1%（已验证，[官方博客](https://developer.android.com/blog/posts/under-the-hood-android-17-lock-free-message-queue)） | 内存序与可见性；队列必须有界。已实现并由 `CrossThreadSubmissionTest` 锁定 |
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

### 下一轮候选（已分析，未实施）

- **`discardInvalid` 命令循环的 `queue.contains` 是 O(n)**（`UiScheduler.kt`，`ArrayDeque` 线性查找）。已用探针新增场景实测：`command-discard-stale,256` 相对 `command-discard,256` 分配几乎不变（8,632 vs 8,544 B/轮），中位数持平，但 **p99 从 4.2µs 劣化到 46.7µs**——O(n²) 只体现在尾部。**未实施**：那个 `contains` 同时是校验器重入（`close()`/`pause()`/嵌套 `discardInvalid`）期间的防护——去掉它会让队列已清空或被嵌套调用搬走的条目被重复计数；修它需要引入能跨嵌套调用可见的「仍在队列中」标记，风险高于收益。留待有设备与压测条件时再评估。

已实施并从候选移除：`PropertyBinding.close` 的逐绑定全量扫描（见上，2026-11-07 第三轮）。

对抗复核同时修正了上一轮分析报告的几处偏差，记录备查：`UiScheduler` 的「最大热点」结论基于 `80e6d28` 之前的源码，本轮优化后 `state-reentrant,256`（55,560 B）才是当前最大分配场景，稳态帧仅约 36 B；`indexStates` 的「抖动重分配」前提不成立（只在容量增长到新最大值时发生）；`PrecomputedTextBinding` 为 opt-in（默认关闭），不应计入默认路径。

### 明确不可依赖

- 虚拟线程（Project Loom）在 Android ART 上为 flag 实验，interpreter-only 且 JNI / `synchronized` 会 pin，不可用于生产。
- 厂商宣传的触摸延迟改善未在本项目复现，仅作数量级参考。

### 跨库联合调优

2026-11-07 的 exa 联合调研（报告见会话研究副本）给出 8 条建议，按收益/成本排序，全部要求显式启用、可独立回退：

| # | 建议 | 适用层 | 显式启用方式 |
| --- | --- | --- | --- |
| R1 | 把帧预算真源贯通到调度决策（`FrameTick.deadlineNanos()` 作为唯一数值真源） | MeguvizAndroid | `meguviz.frame.budget=explicit` |
| R2 | 统一 vsync fan-out 为「单入口派发 + 订阅」，桥接模块不得各自 `postFrameCallback` | ui-frame-shared / 各桥 | `enableSharedFrameDriver=true` + 编译期断言 |
| R3 | 把「同帧同槽合并」扩展到桥接栈的 begin/commit 往返 | MeguvizUI / Drawable / Lumen | `meguviz.jointCommit` |
| R4 | 无锁队列拓扑显式化（SPSC / MPSC / multicast），不动 `BoundedMailbox` 语义 | MeguvizAndroid | `meguviz.queue.topology=auto` |
| R5 | `BoundedMailbox` 的锁内 O(n) 移除改为 tombstone + owner 延迟清理 | MeguvizAndroid | `meguviz.queue.tombstone=true` |
| R6 | 帧预算在层间传递的显式契约（携带 deadline 或显式声明未知，禁止裸 null/字符串） | 全部四层 | `FrameBudget.isKnown()` 检查 |
| R7 | 跨线程 handoff 的 wakeup 合并（conflated / 单次唤醒） | MeguvizAndroid | `meguviz.handoff.coalesce=true`（API 28+ 分支） |
| R8 | 联合收益的可观测性与门槛（FrameMetrics / FrameTimeline / Perfetto 探针 + 开关 A/B） | 全部四层 | `meguviz.bridge.telemetry=true` |

优先级：先做 **R1 + R6**（改动面小、是其他建议的度量基础），再做 **R2 + R3**（「联合」的核心），**R4 + R5** 必须在有 profile 证据之后，**R7 + R8** 收尾。R1 本轮已实施（`WindowSession.open` 默认改用 `BudgetFrameDriver`）。

2026-11-08 的两份专题调研对上面两条做了修正，记录备查：

- **R2 的强制手段**：推荐「模块边界 + ArchUnit/Konsist 之类的架构测试 + 装配期断言 + `allowDirectSource` 开关」四层，而不是只靠评审；RN 的 `ReactChoreographer` 与 Fabric 共享回调是先行例。
- **R5 的本质**：问题不在 O(n) 本身——`ArrayDeque.remove` 是顺序 memmove（约 0.5 ns/元素），比链表指针追逐快 10–30 倍；真正的代价是**持锁执行**导致 producer 阻塞与 priority inversion。DeliQueue 的解法是「逻辑删除 O(1)（CAS 标记 + freelist）+ owner 批量物理清理（O(1) unlink，靠 backlink）」。因此 R5 的验证应先证明热点在「锁内耗时」而不是「移除复杂度」。
- **R3 的语义**：桥接 begin/commit 应显式建模为「帧内 slot 合并」——`begin(slot)` 覆盖写 `pendingBySlot[slot]` 并只在该 slot 首次出现时请求帧；`commit(slot)` 只标记，不单独请求帧；帧回调内按 slot 应用已提交的最后一次值。与 React「一个 handler 内多次 setState = 一次 render」（实测 render 次数 −69%、rendering operations −33.3%）同构。**注意**：这些是 React 树 render 次数，映射到本项目是 `UiScheduler` 的 action 次数与下游 setter 次数，不等于帧数。
- **R4 的拓扑**：JCTools/Agrona/Disruptor **均不支持任意位置移除**，拓扑改造必须叠加 tombstone。

三库桥接模块互不 import，「桥接可选」在模块级成立（已核对源码，并经独立对抗复核确认）。经交叉复核**确认**的联合调优点：

- **background 归属无共同注册表**：Lumen 侧 `LumenSurfaceBridge` 用宿主传入的 `BindingScope.claim`，Drawable 侧 `RecipeBackground` 用每次新建的独立 `BindingScope` 走 `UiSession.bind`；两个 `BindingScope` 之间只在同 scope 内才互斥，跨栈没有交集，`LumenSurfaceSession` 遇到同 View 重复绑定时静默替换而不报错。混用两套桥时建议引入可选的共享登记处，默认关闭。
- **Lumen 表面 detach 即销毁且不自动重绑**：列表回收场景会出现抖动，建议桥层提供可配置的重绑策略。

以下说法经复核后**修正或降级**，不要按原样引用：

- 「三套同语义桥」只对 Lumen→View 绑定与 background 登记成立；三栈的 WindowSession 接入桥是不同库的适配器，不可互代。
- 「每次 post 约 15+ 分配」未实测：post 本身约 7 个对象，15+ 是 begin+post 往返的静态估算。
- `SkinPrefs.write` 确为主线程同步 `commit()` 且全量读回，但不是该路径唯一的磁盘 I/O。

## 验证边界

报告生成在各模块 `build/` 及根目录 `build/reports/`，不提交设备标识或运行回执。API 基线描述本候选的 JVM 公开签名，不是历史稳定版二进制兼容证明。

`verifyDevice` 需要已授权的真实设备及完整设备矩阵输入；缺少证据时应失败。`verifyReleaseCandidate` 是额外的完整验收门禁，需要公开仓库之外的项目选择、设备、性能及整合记录。公开源码不附带这些私有记录，因此默认 host 成功不能使发布门禁通过。

已进行 API 37 范围的设备验证；API 27/29、完整无障碍、OEM 与性能矩阵仍有缺项。声明最低 SDK 不等于每个版本均已通过设备认证。工具链的可构建结果也不等于所有组合均处于 Kotlin 官方完整支持矩阵中。

### 可选桥 profile 的本地验证

`ui-android-bridge` / `ui-lumen-bridge` / `ui-ref-bridge` 由 `-PenableAndroid=true` / `-PenableLumen=true` / `-PenableRef=true` 引入，`verifyHost` 只在对应开关打开时验证它们。它们依赖家族 1.2.0 制品，而这三个版本**尚未发布到 Maven Central**，因此离线或常规联网解析都会失败——这是环境限制，不是代码失败。

在装有 MeguvizAndroid 源码的机器上可以这样本地验证（不修改仓库文件）：

```sh
# 1) 把上游 1.2.0 制品发布到 mavenLocal（MeguvizAndroid 仓库内）
./gradlew :android-runtime:publishReleasePublicationToMavenLocal \
          :android-window:publishReleasePublicationToMavenLocal
# 2) 把 ui-core 发布到 mavenLocal（本仓库内）
./gradlew :ui-core:publishReleasePublicationToMavenLocal
# 3) 用一个临时 init 脚本把 mavenLocal 注入 settings 级仓库并关闭依赖校验
./gradlew :ui-android-bridge:testDebugUnitTest :ui-android-bridge:assembleRelease :ui-android-bridge:lintDebug \
    -PenableAndroid=true --offline --dependency-verification=off -I <临时 init 脚本>
```

临时 init 脚本内容只有：

```kotlin
settingsEvaluated { dependencyResolutionManagement.repositories.mavenLocal() }
```

要点与限制：

- 该方式验证的是「本机构建的上游 1.2.0 + 当前源码」，**不是** Central 上即将发布的字节；`gradle/verification-metadata.xml` 与 `upstream-candidates.toml` 的哈希校验必须以正式发布为准，所以这种运行要配合 `--dependency-verification=off`。
- `ui-ref-bridge` 需要 `meguviz-ref-core:1.2.0`。它的源码在 `D:\DevArea\MeguvizRef` 的 `release/1.2.0` 分支（领先 `main` 28 个提交，`meguviz.version=1.2.0`，含 `verification/api-baseline/ref-core.txt`），尚未合并也未发布。该分支已有 worktree `D:\DevArea\MeguvizRef.worktrees\release-120-MeguvizRef`，在那里执行 `./gradlew :ref-core:publishLibraryPublicationToMavenLocal --offline` 即可补上这个坐标。
- MeguvizDrawable 的 `-Pmeguviz.profile=bridges` 需要 `meguviz-android-runtime` 与 `meguviz-ui-core` 的 1.2.0，可用同一 init 脚本与同两个发布步骤验证。

本轮已用上述手法把三座桥全部实测通过：`ui-android-bridge` 11 例、`ui-lumen-bridge` 编译 + lint、`ui-ref-bridge` 13 例。

## 维护规则

依赖版本固定在版本目录、锁文件和校验元数据中。常规构建不使用 `--write-locks` 或 `--write-verification-metadata`；更新依赖时应审查实际坐标、来源、许可证与哈希。API 变化需审查 `api/` 基线及正负编译案例，不应仅为消除失败自动更新基线。

仅提交源码、构建配置、公共文档与通用测试夹具。凭据、签名、真实业务数据、本机路径、设备记录和内部工程材料保留在公开仓库之外。提交信息使用 Conventional Commits。

构建工具链与可选上游版本受已审查的兼容性及精确制品合同约束，因此仅在对应版本声明处抑制新版本提示；AGP 的定点抑制只表示有意固定工具链，不关闭其他 lint、类型、依赖完整性或哈希检查。

[返回文档目录](README.md)
