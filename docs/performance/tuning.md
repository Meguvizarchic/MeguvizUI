# 性能调优

> 适用范围：MeguvizUI 源码候选 1.2.0（含 P2 模块）。已发布 1.0.0 不含 `submitStates` / `StateUpdate` 等源码候选 API。
>
> 证据分级（全文统一）：
> - **host 已验证**：JDK 17 主机探针的可复现结果，完整记录在 [docs/development.md](../development.md) 与 [docs/scheduling.md](../guides/scheduler.md)。只覆盖 JVM 分配与调度语义，不含 ART、真机或绘制。
> - **准入记录**：[docs/p2.md](../p2.md) 的「性能准入」表是 2026-10-27 在单台物理机（PMA110，Android 17 / API 37，源码指纹 `431d13…`）上的 A/B 实测记录，用于说明模块边界，**不是普适数字**。本文不重复这些数字，只引用其结论与边界。
> - **需真机验证**：本文所有参数取值建议都属于推断，必须用 [measurement.md](measurement.md) 的方法在自己的设备上复核。

本文不提议任何新增 API；所有建议都基于现有入口。

---

## 1. 何时启用 P2 模块

| 模块 | 启用条件 | 不适用 / 注意 | 证据等级 |
| --- | --- | --- | --- |
| `ui-recycler` | 长列表 + 高频突发提交（连续多次 `submitList` 后才到达最终列表）；模型不可变或可提供 `snapshotItem`；接受 latest-wins 语义 | 小列表；必须保留每次提交语义；无法提供后台 executor 或稳定 ID | 准入记录 + 需真机验证 |
| `ui-text` | 长纯文本 `String` 标签，API 28+，无 span、非 `EditText`、无 filters；**默认关闭，必须显式 `enabled = true`** | API 27、短文本、富文本、可编辑文本；这些路径自动回退普通 setter | 准入记录 + 需真机验证 |
| `ui-prewarm` | 页面首次切换帧的主线程构造耗时是瓶颈，且页面可拆成可中断单元；能在切换前的若干平台帧内预热 | 无法拆分单元；单元本身超过帧预算；首帧不敏感 | 准入记录 + 需真机验证 |
| `ui-frame-shared` | 同一 owner 线程上多个 `UiSession` 共存（多窗口、多页面），需要把 N 次平台回调合并为 1 次 | 单 session 场景**没有收益**；关闭顺序必须 session 先、driver 后 | 需真机验证（尚未测量） |
| `ui-layouts` | 需要受限原生横向行语义时 | **明确不承诺性能收益**，不是性能模块 | 准入记录 |
| `ui-plan` | 动态配置 / 计划驱动页面 | 非性能目的 | 准入记录 |
| `ui-codegen` | 需要生成工厂、typed ref 与 registry 注册 | 非性能目的 | 准入记录 |

### 1.1 ui-recycler

入口：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/UiListAdapter.kt`。

- 需要调用方提供：`background: Executor`、`snapshotItem`（或不可变模型构造器）、`stableId`、`DiffUtil.ItemCallback`、`onDiffFailure`。
- 不可变模型构造器（不带 `snapshotItem`）每次提交只做一次整体数组拷贝；带快照函数的构造器会在主线程逐项调用 `snapshotItem`。
- 最多一个 diff 计算运行、一个最新输入等待；旧计算不可抢占，仅丢弃过期结果。
- `submitList` 在主线程做快照；ID 唯一性与 diff 在后台执行。
- `close()` 前必须先从所有 `RecyclerView` 分离；关闭时对仍绑定的 holder 调用 `onRebind`。
- 突发提交是 latest-wins：**不能把必须保留次数的业务操作塞进列表更新**。

### 1.2 ui-text

入口：`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt`。

```kotlin
PrecomputedTextBinding(session, ref, background, onFailure, enabled = false, minimumLength = 256)
```

- 默认 `enabled = false`；只有显式启用且满足以下全部条件才走系统 `PrecomputedText`：API 28+、纯 `String`、长度 ≥ `minimumLength`、非 `EditText`、无 filters。
- 不满足条件时 `submit` 返回 `false`，并改用普通 setter：纯 `String` 走 `Properties.plainText()` 的状态通道（可合并），其他 `CharSequence` 走命令通道（`PrecomputedTextBinding.kt`）。
- 后台预计算结果通过主线程 `Handler` 投递；参数在提交后变化时最多重算两次，之后回退普通 setter。
- 外部字体/主题原位变更时，调用方必须先调用 `invalidate()` 或相应资源权威失效方法。
- 收益只针对主线程部分；后台计算使结果更晚可应用（具体延迟见准入记录）。

### 1.3 ui-prewarm

入口：`ui-prewarm/src/main/kotlin/io/github/jichuo1/meguviz/ui/prewarm/UiPrewarmer.kt`。

```kotlin
UiPrewarmer.attach(session, capacity = 4, maxUnitsPerWork = 32, maxUnitNanos = 2_000_000)
```

- 复用已有 `UiScheduler`，每帧最多执行一个主线程工作单元；单元超过 `maxUnitNanos` 只能在返回后检测，**不能抢占正在执行的 View 构造**。
- 队列容量默认 4（`capacity`）；每项工作最多 `maxUnitsPerWork` 个单元。
- 提交结果 `PrewarmSubmission`：`Accepted` / `Closed` / `AtCapacity` / `SchedulerUnavailable`。拒绝时所有权仍归调用方，`discard` 不被调用。
- 所有者必须在 detach、低内存、资源变化时分别调用 `onDetached()`、`onLowMemory()`、`onResourcesChanged()`；共享 scheduler 故障或暂停后调用 `checkScheduler()`。
- `PrewarmWork.discard` 必须释放全部暂存引用；`ready` 负责成功接收所有权。
- 真机冷启动时第一个单元可能超过默认 2ms 预算并被丢弃，此时页面在打开时才懒建——这是设计行为。

### 1.4 ui-frame-shared

入口：`ui-frame-shared/src/main/kotlin/io/github/jichuo1/meguviz/ui/frames/SharedFrameDriver.kt`、`ChoreographerFrameSource.kt`。

- 把同一 owner 线程上多个 `UiSession` 的帧请求合并为**一次**平台帧回调，再把同一个 `FrameBudget` 扇出给各会话。
- 单 session 没有收益：`Choreographer` 本身已合并同一帧的 vsync 请求，共享驱动减少的是 N−1 次回调入队/出队/分派，而不是 N−1 次 vsync。
- 待处理请求上限 `MAX_CLIENTS = 256`；`pendingCount` 只统计下一帧的待处理请求，扇出进行中读数为 0。
- 关闭顺序：先关闭各 session，再关闭 shared driver。
- **聚合预算**：所有 client 共享同一个 `FrameBudget`，但每个 `UiScheduler` 各自计算工作预算。API 27–32 没有平台 deadline（`estimated = true`），N 个 session 在同一帧的聚合主线程工作上界是 `N × maxFrameWorkNanos`，与帧 deadline 无关。session 越多越应下调各自的 `maxFrameWorkNanos`。

---

## 2. UiScheduler 参数取值建议

构造函数（`UiScheduler`）：

```kotlin
class UiScheduler(
    driver: UiFrameDriver,
    private val assertOwner: () -> Unit,
    val stateCapacity: Int = 256,
    val commandCapacity: Int = 256,
    val maxOperationsPerFrame: Int = 64,
    val maxFrameWorkNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val dispatcher: OwnerThreadDispatcher? = null,
)
```

`dispatcher` 为可选参数，默认 `null`（纯 owner 线程独占）；另有一个只含前 7 个参数的次级构造器保留旧的签名，旧二进制调用方不受影响。详见[调度指南](../guides/scheduler.md#uischeduler-构造参数)。

| 参数 | 默认值 | 计量单位 / 语义 |
| --- | --- | --- |
| `stateCapacity` | 256 | 不同状态槽的数量，不是提交次数 |
| `commandCapacity` | 256 | 命令操作数（批次按展开后的操作数计） |
| `maxOperationsPerFrame` | 64 | 每帧执行的操作数上限 |
| `maxFrameWorkNanos` | 2_000_000（2ms） | 固定帧工作预算 |
| `nanoTime` | `System::nanoTime` | 单调时钟，仅供测试替换 |

### 2.1 stateCapacity

- 同槽重提是合并（`SubmitResult.Coalesced`），不新增容量；只有新槽才可能触发 `Rejected(STATE_CAPACITY)`。
- 容量计算包含当前帧尚未处理的集合（`pendingStateCount`）。
- **关键区分**：`PropertyBinding.submit` 根据 `PropertySpec.coalescible` 选择通道（`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`）：
  - `coalescible = true` → `submitState`，受 `stateCapacity` 约束。`Properties.plainText()` / `textColor()` / `visibility()` 属于此类。
  - `coalescible = false`（默认）→ `submitCommand`，受 `commandCapacity` 约束。`Properties.richText()` / `checked()` 属于此类。
- 因此，走哪个通道取决于 `PropertySpec.coalescible`；`stateCapacity` 主要影响 coalescible 属性与直接使用 `UiScheduler.submitState` / `submitStates` 的场景。
- **建议**：默认 256。按「同时 pending 的不同 coalescible 属性数」的峰值估算，留 1.5–2× 余量；不要按提交次数放大。

### 2.2 commandCapacity

- 单操作命令直接入队；批次整体准入：`operations.size > commandCapacity - pendingCommands` 时整批拒绝（`UiScheduler`）。
- 批次整体接收或拒绝，但批次内部操作保持 FIFO；批次**不是事务**，不提供回滚。
- **建议**：默认 256。按单帧峰值命令操作数（含批次展开）估算，至少覆盖一次完整页面更新。

### 2.3 maxOperationsPerFrame

- 每帧最多执行的操作数；达到上限后剩余工作合并回队列，下一帧继续（`UiScheduler`、`550-575`）。
- **建议**：默认 64。若单帧预算内可完成更多操作且希望减少帧数，可上调；若单帧操作耗时波动大，保持或下调。

### 2.4 maxFrameWorkNanos

- 固定工作预算。当平台提供真实 deadline（`estimated = false`）时，有效预算取 `min(maxFrameWorkNanos, deadline - started - 1ms)`；`estimated = true`（deadline 未知或仅估计）时只用固定预算（`UiScheduler`）。
- 代码中 `reservedTailNanos = 1_000_000L`，为 traversal/draw 预留 1ms 尾部。
- `deadline - started <= 1ms` 时派生预算为 0，该帧不执行任何操作。
- **建议**：默认 2ms。API 33+ 有真实 deadline 时预算会被 soft deadline 自动收紧；API ≤32 只用固定预算。共享帧多 session 场景必须按 `N × maxFrameWorkNanos` 折算（见 1.4）。

### 2.5 帧预算与 deadline

`FrameBudget`（`ui-core/.../scheduler/UiFrameDriver.kt`）：

```kotlin
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
)
```

- `estimated = false` 必须有非 null 的 `deadlineNanos`，否则构造失败（fail-closed）。
- `estimated = true` 时调度器完全忽略 `deadlineNanos`，只用固定预算。
- `ChoreographerFrameDriver` / `ChoreographerFrameSource`：API 33+ 改用 `postVsyncCallback` 并复制 `preferredFrameTimeline.deadlineNanos`（`estimated = false`）；API ≤32 保持 `postFrameCallback`、`deadlineNanos = null`、`estimated = true`。
- 上游 `FrameTick.deadlineNanos()` 必须与 `System.nanoTime()` 同基（见 `AndroidFrameDriver.toUiBudget` 契约注释）。

---

## 3. 后台线程与主线程投递边界

- `UiSession` / `UiScheduler` 的所有方法与帧回调都必须在 owner 线程执行；`UiSession.assertOwner()` 失败抛 `IllegalStateException("UI operation requires the main thread")`（`ui-core/.../UiSession.kt`）。
- **唯一例外**：`UiSession.requestClose()` 可从后台线程调用，内部 post 到主线程执行 `close()`（`UiSession.kt`）。`close()` 本身仍要求主线程。
- 以下入口全部要求主线程：`PropertyBinding.submit` / `operation`、`UiScheduler.submitState` / `submitStates` / `submitCommand` / `submitBatch` / `discardInvalid` / `pause` / `resume` / `recover` / `close`。跨线程请改用下面这两个异步入口。
- 跨线程提交用 `submitStateAsync` / `submitCommandAsync`（`UiScheduler` 与 `UiSession` 都有）：后台线程只写入有界 handoff，一次 owner 派发服务整批更新，同槽更新在合并前就已塌缩成一条。详见[调度指南](../guides/scheduler.md#跨线程提交异步入口)。注意不同槽位的突发会比同步路径多一倍 map 操作（handoff 插入 + 合并插入），收益是 owner 派发次数，不是分配。
- P2 模块的 `background: Executor` 必须真正异步：`LatestWork` / `LatestTextWork` 会检查后台线程是否为 owner 线程，若是则抛 `IllegalStateException("Background executor must run off the owner thread")`，并可能关闭控制器（`ui-recycler/.../LatestWork.kt`、`ui-text/.../LatestTextWork.kt`）。
- owner 派发必须在主线程：`PrecomputedTextBinding` 与 `UiListAdapter` 内部用 `Handler(Looper.getMainLooper())` 投递结果。
- `UiPrewarmer` 在 scheduler owner（主线程）执行，每帧最多一个单元；普通 `View` 构造不能放后台，后台只准备调用方自有的纯模型。

---

## 4. 背压与拒绝处理

### 4.1 SubmitResult 与 RejectionReason

`UiScheduler` 的返回类型（`UiScheduler`）：

- `SubmitResult.Accepted`：新槽/新命令入队。
- `SubmitResult.Coalesced`：状态槽已排队，本次覆盖旧值。
- `SubmitResult.Rejected(reason)`：未入队，可重试或丢弃。

`RejectionReason`：`PAUSED`、`FAULTED`、`CLOSED`、`STATE_CAPACITY`、`COMMAND_CAPACITY`、`EMPTY_BATCH`。

**调用方必须检查返回值**，拒绝不会入队，调度器也不会代为重试。

### 4.2 各入口的背压语义

- `submitState`：容量满时只拒绝**新槽**；已排队槽仍可覆盖。
- `submitStates`：空列表返回 `EMPTY_BATCH`；容量超限时前面已入队的条目保留（无回滚），最后一条生效；全部覆盖已排队槽时返回 `Coalesced`，至少一个新槽时返回 `Accepted`。
- `submitCommand`：`pendingCommands >= commandCapacity` 时拒绝。
- `submitBatch`（List 与 vararg 两种重载）：空批 `EMPTY_BATCH`；超限整批 `COMMAND_CAPACITY`；vararg 重载会复制输入数组，调用方随后修改原数组不影响队列。
- `pause(clearPending = true)` 默认清空队列并取消帧；`pause(clearPending = false)` 才保留队列。
- `resume()` 只恢复 `PAUSED`；`recover()` 只恢复 `FAULTED`，且不重试失败操作。
- `FAULTED` 后必须由调用方核对或重建真实状态后才能 `recover()`。

### 4.3 P2 模块的拒绝语义

- `PrewarmSubmission`：`Accepted` / `Closed` / `AtCapacity` / `SchedulerUnavailable`。拒绝时所有权仍归调用方，`discard` 不被调用。
- `LatestWork` / `LatestTextWork`：最多一个 running + 一个 pending；旧计算不可抢占；只有最新 pending 值会运行；后台计算失败不破坏 pending；owner 派发失败后控制器关闭，后续提交抛错。
- `UiListAdapter.close()`：必须先从所有 `RecyclerView` 分离；关闭时对仍绑定的 holder 调用 `onRebind` 释放会话绑定。
- `PrecomputedTextBinding.submit` 返回 `false` 表示改走普通 setter，不表示失败。

### 4.4 观测

- `SchedulerCounters` 区分接收、合并、实际 action、过期、引用释放、拒绝、帧请求与帧执行（`UiScheduler`）。`action` 次数不等同于原生 setter 接受次数或 traversal 次数。
- 排查背压时先看 `Rejected` 计数与 `RejectionReason`，再决定是扩容、合并提交还是降低提交频率。

---

## 5. 同语义集合 submitStates 的适用场景

入口：`UiScheduler.submitStates(updates: List<StateUpdate>)`、`StateUpdate(slot, isValid, action)`（`ui-core/.../scheduler/UiScheduler.kt`、同目录 `StateUpdate.kt`）。

语义：

- 一次 owner 校验、一次帧请求、批内按槽去重。
- 逐条准入语义与重复调用 `submitState` 完全一致；最后一条生效。
- 容量超限时前面条目保留（无回滚）。
- 全部覆盖已排队槽返回 `Coalesced`，至少一个新槽返回 `Accepted`，空列表返回 `Rejected(EMPTY_BATCH)`。
- `StateUpdate.isValid` 默认 `{ true }`，执行前仍会立即复核。

**适用**：

- 一次事件或状态快照要更新一组属性，且这些更新属于**同一语义集合**；希望减少 owner 校验与帧请求次数，并让批内重复槽自动去重。
- 例：把一份完整视图状态一次性应用到多个属性。

**不适用**：

- 需要跨属性/命令的因果顺序：用 `submitBatch`（FIFO，批次不是事务）。
- 需要保留每次业务操作：状态通道是 last-wins，会合并同槽提交。
- 空列表：直接拒绝。

**成本提示**：调用方构造 `StateUpdate` 列表本身有成本；收益在批内去重与单次帧请求，不在分配。不要把它当作「更省的 submitState」来比较单次分配。

**版本**：`submitStates` / `StateUpdate` 是 1.2.0 源码候选 API，不在已发布 1.0.0 中；当前 `api/public-api.txt` 已包含。

---

## 6. 调优检查清单

1. 先测量再调参。host 探针只用于分配与语义回归，真机数据才决定参数。
2. 默认值优先；按场景峰值留余量，不要按最坏情况放大。
3. `stateCapacity` 按不同 coalescible 属性数估算；`commandCapacity` 按单帧峰值命令操作数估算。
4. 共享帧多 session 时，按 `N × maxFrameWorkNanos` 折算聚合预算，必要时下调 `maxFrameWorkNanos`。
5. 检查 `SchedulerCounters` 的拒绝与合并计数，确认背压来源。
6. 不要新增冗余 API；用现有 `submitState` / `submitStates` / `submitBatch` / `PropertyBinding` 组合表达语义。
