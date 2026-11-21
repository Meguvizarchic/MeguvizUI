# 性能调优

本文基于 MeguvizUI `1.2.0` 现有公开 API 给出 P2 模块启用条件、调度器容量配置、跨线程投递与背压处理建议。

## 1. 何时启用 P2 模块

| 模块 | 适用场景 | 不适用 / 注意事项 | 证据类型 |
| --- | --- | --- | --- |
| `ui-recycler` | 长列表且存在高频突发更新；模型不可变或可提供 `snapshotItem`；接受 latest-wins 语义 | 小列表；需要保留每次列表提交语义；无后台 `Executor` 或无稳定 ID | 真机基准记录 + 业务实测 |
| `ui-text` | `API 28+`、长纯文本 `String` 标签（`>= minimumLength`）、非 `EditText` 且无 filters；**需显式 `enabled = true`** | `API 27`、短文本、富文本、可编辑文本框（这些路径自动回退普通 setter） | 真机基准记录 + 业务实测 |
| `ui-prewarm` | 页面首次切换帧的主线程构造耗时为瓶颈，且页面可拆分为有界单元在切换前预热 | 不可拆分的大块构造；单单元超出帧预算；首帧无卡顿 | 真机基准记录 + 业务实测 |
| `ui-frame-shared` | 同一主线程同时运行多个 `UiSession`（如多窗口/多页），需合并为 1 次平台回调 | 单会话场景**没有收益**；关闭须遵循「session 先、driver 后」 | 需业务多会话实测 |
| `ui-layouts` | 需要限制子控件上限的原生横向行语义 | 非性能导向模块，不改变 `LinearLayout` 测量过程 | 真机基准记录 |
| `ui-plan` | 配置驱动或模板化动态界面 | 静态页面直接用 DSL 即可 | 真机基准记录 |
| `ui-codegen` | 减少手写 `ComponentType` 与 `ViewFactory` 模板代码 | 非性能导向模块 | 真机基准记录 |

### 1.1 `ui-recycler`

入口：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/UiListAdapter.kt`。
- **模型不可变（所有参与 `stableId` 与 `itemCallback` 的字段均为 `val`）**：使用不带 `snapshotItem` 的构造器，主线程每次提交只做一次整体数组拷贝。
- **模型包含可变字段**：必须提供 `snapshotItem` 逐项复制可变字段，避免后台 Diff 读写竞态。
- 后台最多运行 1 个 Diff 任务并排队 1 个最新输入，不可把必须按次执行的业务操作放入列表提交；调用 `close()` 前先从所有 `RecyclerView` 解绑。

### 1.2 `ui-text`

入口：`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt`。
- 默认 `enabled = false`；只有满足全部条件（`API 28+`、纯 `String`、长度 `>= minimumLength` 默认 `256`、非 `EditText`、无 `filters`）时才进入后台预计算。
- 不满足条件时 `submit()` 返回 `false` 并走普通 setter；字体/主题原地变化后须主动调用 `invalidate()`。
- 预计算节省的是主线程测量与设置文本耗时，后台计算自身会让结果晚到约 1 ms 左右。

### 1.3 `ui-prewarm`

入口：`ui-prewarm/src/main/kotlin/io/github/jichuo1/meguviz/ui/prewarm/UiPrewarmer.kt`。
- `UiPrewarmer.attach(session, capacity = 4, maxUnitsPerWork = 32, maxUnitNanos = 2_000_000)` 复用会话调度器，每帧最多执行 1 个单元；单单元超预算只能在返回后发现，无法抢占正在进行的构造器。
- 宿主在 detach、低内存、资源变化时需调用 `onDetached()`、`onLowMemory()`、`onResourcesChanged()`；提交返回 `PrewarmSubmission`（`Accepted` / `Closed` / `AtCapacity` / `SchedulerUnavailable`）。

### 1.4 `ui-frame-shared`

入口：`ui-frame-shared/src/main/kotlin/io/github/jichuo1/meguviz/ui/frames/SharedFrameDriver.kt`、`ChoreographerFrameSource.kt`。
- 待处理请求上限 `MAX_CLIENTS = 256`；必须先关闭各个 `UiSession`，最后再关闭 `SharedFrameDriver`。
- **聚合预算控制**：所有客户端共用同一个 `FrameBudget`，但每个 `UiScheduler` 各自扣减工作预算。在 `API 27–32` 无真实平台 deadline 时，N 个会话同帧聚合耗时上限为 `N × maxFrameWorkNanos`，会话越多越需调低单会话预算。

---

## 2. `UiScheduler` 参数调优

| 参数 | 默认值 | 含义与建议 |
| --- | --- | --- |
| `stateCapacity` | `256` | 按**不同状态槽数量**计数（含本帧未处理快照）；同槽覆盖不消耗额外槽位。建议按单帧峰值并发属性数留 `1.5–2×` 余量，不按提交次数扩大。 |
| `commandCapacity` | `256` | 按**命令操作项数量**计数（`submitBatch` 按展开后操作数计）；建议覆盖单帧峰值命令数，超限保持背压拒绝而非设成无限。 |
| `maxOperationsPerFrame` | `64` | 单帧最大执行项数；超出部分保留到下一帧继续处理。 |
| `maxFrameWorkNanos` | `2_000_000`（`2 ms`） | 固定帧工作时间上限；`API 33+` 在真实平台 deadline 下自动取 `min(maxFrameWorkNanos, deadline - started - 1ms)`（预留 `1 ms` 尾部）。 |

> 注意区分通道：`PropertyBinding.submit` 根据 `PropertySpec.coalescible` 选择通道——`plainText()` / `textColor()` / `visibility()` 走状态通道（受 `stateCapacity` 约束），`richText()` / `checked()` 走命令通道（受 `commandCapacity` 约束）。

---

## 3. 后台线程与跨线程提交边界

- 同步入口（`PropertyBinding.submit`、`submitState`、`submitStates`、`submitCommand`、`submitBatch`、`discardInvalid`、`pause` / `resume` / `recover` / `close`）均要求在 **Android 主线程**调用。
- **后台允许的入口**：
  - `UiSession.requestClose()`：向主线程投递异步关闭请求。
  - `UiSession.submitStateAsync` / `submitCommandAsync`：后台写入有界 handoff，同一热槽原地折叠，一次主线程派发合并整批更新；注意不同槽位的大量并发异步提交会比主线程同步路径多一次 handoff 映射操作，其核心优势在于减少跨线程派发次数。
- `UiListAdapter` 与 `PrecomputedTextBinding` 的 `background: Executor` 必须真正运行在非主线程，否则内部断言会拒绝并关闭控制器。

---

## 4. 背压与批量状态提交

- 调度器拒绝时返回 `SubmitResult.Rejected(reason)`（`PAUSED`、`FAULTED`、`CLOSED`、`STATE_CAPACITY`、`COMMAND_CAPACITY`、`EMPTY_BATCH`），调用方须按原因处理退避、节流或状态恢复。
- `UiScheduler.submitStates(updates: List<StateUpdate>)` 适合把同一语义集合的多个槽位更新合并为一次 owner 校验、一次帧请求与批内按槽去重；超出 `stateCapacity` 时保留已入队的前序条目。需要跨属性的严格先后执行顺序时，改用 `submitBatch`。

---

## 5. 适用范围与验证说明

- **主机探针结果（`host 已验证`）**：JDK `17` 探针数据见 [构建、验证与贡献](../development.md) 与 [测量方法](measurement.md)，用于验证 JVM 分配变化与调度语义。
- **设备实测参考（`基准记录`）**：[P2 可选能力](../p2.md) 中的实测数据基于 PMA110（Android 17 / API 37）无 R8 的分离层级基准测得，仅供模块边界参考。
- 参数调优建议应在目标工程真实设备上开启 R8 后按 [测量方法](measurement.md) 重新采样验证。
