# 调度器、帧驱动与背压

本文讲 `UiScheduler` 的容量、准入、帧预算与背压语义。**单库即可使用**：调度器属于 `ui-core`，不需要任何桥。

## 两个角色

```kotlin
// 唯一、不可替换的帧驱动；方法在其 owner 线程执行
interface UiFrameDriver {
    val owner: String
    fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
}

fun interface FrameTicket { fun cancel() }
```

`requestFrame` 必须**异步**投递一次机会，绝不能同步调用 callback；内联回调会让调度器进入 `FAULTED`。来源：`ui-core/.../scheduler/UiFrameDriver.kt`。

默认驱动：

```kotlin
class ChoreographerFrameDriver : UiFrameDriver {
    override val owner: String = "android.choreographer"
}
```

- API 33+ 走 `Choreographer.postVsyncCallback`，在回调内**立即复制** `frameTimeNanos` 与 `preferredFrameTimeline.deadlineNanos`，构造 `FrameBudget(deadlineNanos=…, estimated=false)`。
- API ≤ 32 走 `postFrameCallback`，`deadlineNanos=null`、`estimated=true`，调度器只用固定预算。

`FrameBudget` 是纯数据，不持有平台 callback：

```kotlin
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
)
```

`estimated` 表示 deadline 是否未知/仅估计。构造期 fail-closed：`estimated=false` 必须有非空 `deadlineNanos`，否则抛错。

## UiScheduler 构造参数

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

/** 与 MeguvizAndroid 的 OwnerDispatcher.post 同形，但重新声明在本模块内，不引入兄弟库依赖。 */
fun interface OwnerThreadDispatcher {
    fun post(callback: Runnable): Boolean
}
```

另有一个**只含前 7 个参数的次级构造器**，内部以 `dispatcher = null` 委托——它是 1.2.0 之前存在的签名，保留下来让旧的二进制调用方继续链接。

`dispatcher` 为 `null` 时（默认）调度器保持纯 owner 线程独占，`submitStateAsync` / `submitCommandAsync` 一律返回 `Rejected(CLOSED)`；同步入口的行为与 1.2.0 之前完全一致。`UiSession` 会把主 `Handler` 装进去，因此用 `UiSession` 时异步入口默认可用。

来源：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiScheduler.kt`。

`UiSession` 在构造时创建唯一的 scheduler，并通过 `session.scheduler` 暴露：

```kotlin
val scheduler = UiScheduler(driver, ::assertOwner)
```

`assertOwner` 由装配层提供，通常就是 `UiSession::assertOwner`（主线程检查）。普通属性写入不需要直接碰 scheduler，走 `PropertyBinding.submit` 即可；直接 scheduler 只用于非属性的命令/状态场景。

## 提交入口

```kotlin
fun submitState(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitStates(updates: List<StateUpdate>): SubmitResult
fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitBatch(operations: List<ScheduledOperation>): SubmitResult
fun submitBatch(vararg operations: ScheduledOperation): SubmitResult
```

配套类型：

```kotlin
data class StateSlot(val binding: Any, val property: Any)
class StateUpdate(val slot: Any, val isValid: () -> Boolean = { true }, val action: () -> Unit)
class ScheduledOperation(val label: String = "operation",
                         val isValid: () -> Boolean = { true },
                         val action: () -> Unit)
```

### 状态通道与命令通道

- **状态通道**（`submitState`/`submitStates`）：按 slot 合并，同槽 pending 值可被覆盖，满容量时只允许覆盖既有 key。返回 `Coalesced` 表示复写了已排队槽，`Accepted` 表示有新增槽。
- **命令通道**（`submitCommand`/`submitBatch`）：FIFO，保留调用次数。批次整体准入或拒绝，批内保持相对顺序，但**不是事务**，失败前的副作用已经发生。

两个通道之间没有提交时的隐含全序；需要跨属性/命令的因果顺序时用 `submitBatch`。

### submitStates：同语义集合

```kotlin
val slotTitle = Any()
val slotButton = Any()
val result = session.scheduler.submitStates(
    listOf(
        StateUpdate(slotTitle) { println("A") },
        StateUpdate(slotButton) { println("B") },
    ),
)
```

语义：一次 owner 校验、一次帧请求、批内按 slot 去重。准入逐条进行，与按顺序调用 `submitState` 完全等价；超出 `stateCapacity` 时前面的条目已入队，不会回滚。同一 slot 的最后一条生效。全部命中已排队槽时返回 `Coalesced`，有新增槽时返回 `Accepted`。

`submitBatch` 的 vararg 重载与 `List` 版本契约相同，属于当前源码改动，**尚未发布**；已发布 1.0.0 使用 `listOf(...)` 形式。两种入口都会复制输入数组，调用方准入后修改 vararg 数组不会改变队列。

### 跨线程提交（异步入口）

后台线程不要自己 `Handler.post { submitState(...) }`：N 次更新就是 N 次主线程派发。用异步入口，N 次更新只产生 **1 次 owner 派发 + 1 次合并**。

```kotlin
fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
```

`UiSession` 上有一致的同名入口（`ui.submitStateAsync(...)`），内部用主 `Handler` 作派发器。

语义：

- **不阻塞**：后台线程只做一次加锁的 handoff 写入，立刻返回。
- **有界**：handoff 上界就是 `stateCapacity` / `commandCapacity`；满时对新槽返回 `STATE_CAPACITY` / `COMMAND_CAPACITY`（与同步入口同一个 `RejectionReason`）。
- **同槽合并**：handoff 阶段就按 slot 复用条目，所以同一热槽的 N 次后台提交在合并前已塌缩成一条。
- **帧内合并**：`drain()` 开头把暂存工作并入本帧；装不下的留在 handoff，等队列腾出位置后的下一次 merge——**被接受的更新不会被丢弃**。
- **拒绝语义与同步入口一致**：PAUSED / FAULTED / CLOSED 返回相同原因；未装配派发器时（`UiScheduler` 直接构造且 `dispatcher = null`）返回 `CLOSED`。
- **动作始终在 owner 线程执行**：`isValid` 与 `action` 都不会在调用方线程跑。
- **派发器拒绝时**：返回 `CLOSED`，只回滚本次暂存（同槽合并按条目版本号判定，不会抹掉并发生产者后写入的值），其他生产者的接受/拒绝诊断按增量保留；残留条目会补发一次派发。连补发也被拒绝时条目仍留在 handoff，等下一次成功派发或下一次帧合并（`drain` 开头会 merge）取走；**没有永久终止标志**，派发器恢复后异步入口立即可用。派发器持续不可用时，已返回 `Accepted` 的条目可能一直不执行——异步入口不能承载必须送达的工作，需要送达请用同步入口或自建可靠队列。

成本要清楚：不同槽位的突发比同步路径多一倍 map 操作（handoff 插入 + 合并插入）。host 探针实测（N=256）：不同槽位 16,457 → 34,968 B/轮；同一槽位 152 → 240 B/轮。**收益是 owner 派发次数，不是分配**——单线程探针测不出派发成本。

派发器是可选构造参数，默认 `null`：不装配时 `UiScheduler` 保持纯 owner 线程独占，同步路径一行未变，`submit*Async` 一律返回 `CLOSED`。`UiSession` 总是装配主 `Handler`，所以用 `UiSession` 时异步入口默认可用。

### StateSlot 与公平轮转

```kotlin
data class StateSlot(val binding: Any, val property: Any)
```

`binding` 相同的 slot 归入同一组，各活跃组轮转后才处理下一属性；一个拥有很多属性的节点不会挤占其他节点的全部机会。普通 `Any` slot 也可用，其自身就是独立公平组。key 必须在排队期间保持稳定相等语义。

## 容量与拒绝

```kotlin
enum class SchedulerState { ACTIVE, PAUSED, FAULTED, CLOSED }
enum class RejectionReason { PAUSED, FAULTED, CLOSED, STATE_CAPACITY, COMMAND_CAPACITY, EMPTY_BATCH }

sealed class SubmitResult {
    data object Accepted : SubmitResult()
    data object Coalesced : SubmitResult()
    data class Rejected(val reason: RejectionReason) : SubmitResult()
}
```

- 状态容量按**槽**计算，包括当前帧尚未处理的快照；新槽超容时拒绝 `STATE_CAPACITY`，覆盖已有槽不受限。
- 命令容量按**操作数**计算（批次按 item 数）；超容拒绝 `COMMAND_CAPACITY`。
- `PAUSED`、`FAULTED`、`CLOSED` 状态下新提交一律拒绝，拒绝原因对应枚举。
- 空批次/空 `submitStates` 拒绝 `EMPTY_BATCH`。

**背压原则**：队列有界是特性，不要为了消除拒绝把队列扩成无界。调用方必须检查 `SubmitResult`，按 `reason` 决定丢弃、重试或重建。状态通道只适合允许丢弃中间值的状态；必须保留次数的业务操作走命令通道。

## 帧内 drain 与预算

每帧开始时调度器冻结状态与命令队列，本轮回调重入提交的工作只在下一帧可见。每帧限制：

- 最多 `maxOperationsPerFrame` 个操作；
- 状态与命令都有工作时交替取一项（`preferState` 轮转）；
- 单调时间预算：
  - `estimated=false`（平台给了真实 deadline）：`min(maxFrameWorkNanos, deadline - started - 1_000_000)`，为 traversal/draw 留 1ms 尾部；
  - `estimated=true`（deadline 未知或仅估计）：只用 `maxFrameWorkNanos`。

预算只限制操作边界，不承诺单个 setter 的最长耗时。帧末把未处理项合并回队列，组轮转位置跨帧保留。

每个操作执行前立即复核 `isValid`；过期则释放而不写入（计入 `staleOperations`）。验证器或 action 抛异常会记录 `SchedulerFailure`，默认进入 `FAULTED` 并清空队列；`Error` 会原样抛出。

## 暂停、恢复、清理与关闭

```kotlin
fun pause(clearPending: Boolean = true)   // detach 默认行为；clearPending=false 保留队列
fun resume()                              // 只能从 PAUSED 恢复
fun recover()                             // 只能从 FAULTED 恢复；不重试失败操作
fun discardInvalid()                      // 物理释放失效的待处理项，不执行 action
override fun close()
```

- `pause()` 默认清空队列并取消帧票据；`pause(clearPending=false)` 才保留队列。
- `recover()` 要求调用者先核对或重建实际状态；失败操作不会被重试。
- `discardInvalid()` 用于节点释放、`rebind`、属性绑定 `close` 后的清理；它只复核 validity、移除失效项、释放容量。
- `close()` 先置 `CLOSED`、使票据失效，再尽力取消物理帧并释放所有待处理闭包。已进入的用户回调不可抢占。

## 可观测性

```kotlin
val state: SchedulerState            // private set
val failure: SchedulerFailure?       // private set
val cancellationFailure: String?     // private set
val pendingStateCount: Int
val pendingCommandCount: Int
val counters: SchedulerCounters
val driverOwner: String
```

```kotlin
data class SchedulerCounters(
    val acceptedSubmissions: Long,
    val coalescedStates: Long,
    val appliedOperations: Long,
    val staleOperations: Long,
    val releasedOperations: Long,
    val rejectedSubmissions: Long,
    val requestedFrames: Long,
    val drainedFrames: Long,
)
```

`appliedOperations` 不等同于原生 setter 接受次数；属性层另有 `writes`/`skips`。`failure` 只保存异常类型与消息文本，不持有 `Throwable`/receiver。

## 调参建议

- `stateCapacity`/`commandCapacity` 是背压阈值，按页面最大并发属性/命令数留余量，不要直接设成无限。
- `maxOperationsPerFrame` 控制单帧操作数上限；超出的操作留在队列进入下一帧。
- `maxFrameWorkNanos` 是固定预算上限；API 33+ 的真实 deadline 会进一步收窄它。
- 后台线程只计算脱离 View/Context 的结果，不要直接调用 UI setter。跨线程提交请用[跨线程提交（异步入口）](#跨线程提交异步入口)里的 `submitStateAsync` / `submitCommandAsync`：后台线程只写入有界 handoff，一次 owner 派发服务整批更新。业务自行 `Handler.post` 再调同步入口会让 N 次更新产生 N 次主线程派发。

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiScheduler.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiFrameDriver.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/ChoreographerFrameDriver.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/StateUpdate.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`（`PropertyBinding.submit` 的通道选择）
- 合同与历史：`docs/scheduling.md`、`docs/contracts.md`
