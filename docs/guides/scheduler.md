# 调度器、帧驱动与背压

本文介绍 `UiScheduler` 的容量控制、入队语义、帧预算与跨线程提交机制。**单库即可使用**：调度器位于 `ui-core`，不依赖任何桥接模块。

## 两个基础角色

```kotlin
// 唯一且不可替换的帧驱动；方法在所属 owner 线程执行
interface UiFrameDriver {
    val owner: String
    fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
}

fun interface FrameTicket { fun cancel() }
```

`requestFrame` 必须**异步**投递一次帧机会，绝不能在调用栈内同步调用 `callback`；同步回调会让调度器进入 `FAULTED`。源码：`ui-core/.../scheduler/UiFrameDriver.kt`。

默认平台驱动为 `ChoreographerFrameDriver`（`owner = "android.choreographer"`）：
- API `33+` 使用 `Choreographer.postVsyncCallback`，在回调中**立即复制** `frameTimeNanos` 与 `preferredFrameTimeline.deadlineNanos`，生成 `FrameBudget(deadlineNanos = ..., estimated = false)`。
- API `<= 32` 使用 `postFrameCallback`，生成 `deadlineNanos = null`、`estimated = true`，调度器仅使用固定工作预算。

`FrameBudget` 是纯数据对象，不持有任何平台回调引用：

```kotlin
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
)
```

构造约束：`estimated = false` 时必须提供非空 `deadlineNanos`，否则立即抛出 `IllegalArgumentException`。

## `UiScheduler` 构造参数

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
) : AutoCloseable

fun interface OwnerThreadDispatcher {
    fun post(callback: Runnable): Boolean
}
```

调度器同时保留了不含 `dispatcher` 的 **7 参次级构造器**（内部以 `dispatcher = null` 委托，并带有完全相同的默认值），确保以 `1.0.0` 编译的旧调用方可以直接链接运行。

当 `dispatcher` 为 `null` 时，异步提交入口（`submitStateAsync` / `submitCommandAsync`）一律返回 `Rejected(CLOSED)`；`UiSession` 默认会装配基于主 `Handler` 的派发器，因此通过 `UiSession` 使用时异步提交默认可用。

常规属性写入只需通过 `PropertyBinding.submit`；只有非属性命令、一次性操作或跨节点协调才需要直接调用 `session.scheduler`。

## 提交入口

```kotlin
fun submitState(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitStates(updates: List<StateUpdate>): SubmitResult
fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitBatch(operations: List<ScheduledOperation>): SubmitResult
fun submitBatch(vararg operations: ScheduledOperation): SubmitResult
```

配套类型（均在 `io.github.jichuo1.meguviz.ui.scheduler` 包下）：

```kotlin
data class StateSlot(val binding: Any, val property: Any)
class StateUpdate(val slot: Any, val isValid: () -> Boolean = { true }, val action: () -> Unit)
class ScheduledOperation(
    val label: String = "operation",
    val isValid: () -> Boolean = { true },
    val action: () -> Unit,
)
```

### 状态通道 vs 命令通道

- **状态通道**（`submitState` / `submitStates`）：按 `slot` 合并，尚未执行时同槽后值直接覆盖前值，满容量时允许覆盖既有槽位，仅拒绝未排队的新槽。返回 `Coalesced` 表示命中既有槽，`Accepted` 表示有新槽入队。
- **命令通道**（`submitCommand` / `submitBatch`）：按 FIFO 保留全部调用次数。批次整体入队或整体拒绝，批内保持相对顺序，但**不是事务**，出错前已执行的副作用不会回滚。

两个通道之间不存在提交时刻的全局先后顺序；需要跨状态与命令的严格先后顺序时使用 `submitBatch`。

### `submitStates`：同语义集合更新

```kotlin
val slotTitle = StateSlot(titleRef, "text")
val slotButton = StateSlot(buttonRef, "text")
val result = session.scheduler.submitStates(
    listOf(
        StateUpdate(slotTitle) { titleRef.get().text = "A" },
        StateUpdate(slotButton) { buttonRef.get().text = "B" },
    ),
)
```

行为规则：执行一次 owner 线程检查和一次帧请求，在批内按 `slot` 去重（同槽最后一条生效）；逐条检查与顺序调用 `submitState` 等价，遇到 `STATE_CAPACITY` 时前序已入队条目保留在队列中。

`submitBatch(vararg operations: ScheduledOperation)` 与 `List` 重载具有相同的入队与数组快照拷贝语义。

### 跨线程提交（异步入口）

后台线程不应频繁自行调用 `Handler.post { submitState(...) }`，否则 N 次更新会产生 N 次主线程派发开销。改用异步入口后，N 次后台更新只产生 **1 次 owner 派发 + 1 次帧内合并**：

```kotlin
fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
```

`UiSession` 也暴露了同名方法（`uiSession.submitStateAsync(...)` / `submitCommandAsync(...)`）：

- **非阻塞写入**：后台线程仅加锁写入有界 handoff 缓存并立即返回，同槽状态在 handoff 阶段就已完成原地折叠合并。
- **有界容量**：handoff 上限与 `stateCapacity` / `commandCapacity` 相同，超限拒绝与同步入口一致。
- **帧开头合并**：`drain()` 起始将暂存更新并入本帧队列（`mergeHandoff`）；若当前主线程队列暂满，剩余被接受项继续留在 handoff 等待后续帧空位，不会静默丢失。
- **线程归属安全**：`isValid` 与 `action` 始终只在 owner 主线程运行，后台不触碰 `View`。
- **派发拒绝一致性**：若派发器拒绝本次投递，调度器仅回滚未被 owner 取走的该条目并返回 `Rejected(CLOSED)`；若 owner 在此期间已将其并入队列执行，则返回原本的 `Accepted` / `Coalesced`，确保返回值与实际执行严格对齐。当派发器恢复后，异步入口自动重新可用；若需要保证极端退出场景下的必达性，请采用同步入口或业务侧可靠队列。

> **成本说明**：不同槽位的高并发异步突发会比主线程同步路径多一次 handoff 映射操作；对于同一热门槽位的频繁更新，由于复用内部 `StateEntry`，分配接近零。该机制的核心收益是大幅减少跨线程 `Handler` 派发次数。

### `StateSlot` 与公平轮转

```kotlin
data class StateSlot(val binding: Any, val property: Any)
```

相同 `binding` 的状态槽自动归为同一组；同一帧内各活跃组按轮转顺序依次消费，避免单个拥有大量属性的控件挤占其余控件的更新机会。传入普通 `Any` 作为 `slot` 时，每个键自身独立成为一个分组；任何 `slot` 在排队期间必须保持稳定的 `equals` / `hashCode` 语义。

## 容量与背压拒绝

```kotlin
enum class SchedulerState { ACTIVE, PAUSED, FAULTED, CLOSED }
enum class RejectionReason { PAUSED, FAULTED, CLOSED, STATE_CAPACITY, COMMAND_CAPACITY, EMPTY_BATCH }

sealed class SubmitResult {
    data object Accepted : SubmitResult()
    data object Coalesced : SubmitResult()
    data class Rejected(val reason: RejectionReason) : SubmitResult()
}
```

- 状态容量按**不同槽位数量**计数（含当前帧尚未处理的快照），命令容量按**操作项数量**计数（批次按展开后项数计）。
- 调度器处于 `PAUSED`、`FAULTED` 或 `CLOSED` 时拒绝全部新提交；空集合 / 空批次返回 `EMPTY_BATCH`。
- **背压原则**：队列有界是有意设计，切勿把容量设为无限来掩盖更新过载。调用方应检查 `SubmitResult`，根据 `reason` 决定丢弃、节流或重建。

## 帧内 `drain` 与工作预算

每帧回调触发时，调度器先冻结本帧快照（帧回调执行过程中新提交的更新将在下一帧处理），受以下边界控制：

- 单帧最多执行 `maxOperationsPerFrame`（默认 `64`）个操作；
- 状态队列与命令队列同时非空时通过 `preferState` 交替执行；
- 单调时间预算：
  - `estimated = false`（平台给出真实 deadline）：取 `min(maxFrameWorkNanos, deadline - started - 1_000_000)`，为 traversal/draw 预留 `1ms` 尾部；
  - `estimated = true`（deadline 未知或估计）：仅采用 `maxFrameWorkNanos`（默认 `2ms`）。

时间预算在操作边界检查，不中断单个执行中的 setter。每个操作运行前即时复核 `isValid`；无效条目直接释放（计入 `staleOperations`）。若校验器或 `action` 抛出异常，调度器记录 `SchedulerFailure`，切入 `FAULTED` 并清空待处理队列（`Error` 会继续向上抛出）。

## 暂停、恢复、清理与关闭

```kotlin
fun pause(clearPending: Boolean = true)   // detach 默认清空队列；clearPending = false 时保留
fun resume()                              // 仅从 PAUSED 恢复为 ACTIVE
fun recover()                             // 仅从 FAULTED 恢复；不重试此前失败的操作
fun discardInvalid()                      // 移除并释放已失效的待处理项，不执行 action
override fun close()
```

- `recover()` 要求调用方先手动核对或重建真实的 `View` 与业务状态。
- `discardInvalid()` 在 `rebind`、节点释放与绑定关闭后触发，清理失效闭包并释放占用容量。
- `close()` 置为 `CLOSED`、取消待处理帧票据并释放全部队列引用，不抢占已经进入的用户回调。

## 可观测性

`UiScheduler` 提供只读状态与统计计数：
- `state: SchedulerState`、`failure: SchedulerFailure?`、`cancellationFailure: String?`
- `pendingStateCount: Int`、`pendingCommandCount: Int`、`driverOwner: String`
- `counters: SchedulerCounters`（包含 `acceptedSubmissions`、`coalescedStates`、`appliedOperations`、`staleOperations`、`releasedOperations`、`rejectedSubmissions`、`requestedFrames`、`drainedFrames`）

`failure` 仅保留异常类名与消息文本，不长期持有 `Throwable` 或外部对象引用。

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiScheduler.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiFrameDriver.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/ChoreographerFrameDriver.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/StateUpdate.kt`
- [核心合同](../contracts.md) · [构建、验证与贡献](../development.md)
