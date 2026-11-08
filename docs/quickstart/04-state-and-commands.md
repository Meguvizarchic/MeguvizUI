# 04 · 状态与命令

## 两种更新通道

| 通道 | 语义 | 入口 |
| --- | --- | --- |
| 状态（state） | 同槽可合并，只保留最新值 | `PropertyBinding.submit`（`coalescible=true` 时） |
| 命令（command） | FIFO，保留次数 | `session.click`、`UiScheduler.submitCommand`、`submitBatch` |

点击、焦点、带业务监听器的写入**不得**为性能而自动合并；需要显式先后关系时用批次。

## 常用属性：优先复用 `Properties`

源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`。

| 方法 | 类型 | 适用场景 |
| --- | --- | --- |
| `plainText()` | `PropertySpec<TextView, String>` | 无编辑/过滤器副作用的不可变标签；`coalescible=true`、`SHARED_READ` |
| `richText()` | `PropertySpec<TextView, CharSequence>` | 富文本；每次调用都到达 `TextView`，不能按 `toString` 去重 |
| `textColor()` | `PropertySpec<TextView, Int>` | 颜色；`resourceSensitive=true` |
| `visibility()` | `PropertySpec<View, Int>` | 任意 View 的可见性 |
| `checked()` | `PropertySpec<CompoundButton, Boolean>` | 复选/开关；命令语义，保留重复次数 |

```kotlin
val title = FrameScope(session, root).text(ViewKey("title"), frameParams()) { text = "Ready" }
val button = FrameScope(session, root).button(ViewKey("button"), frameParams()) { setText("Action") }
val binding = session.bind(title, Properties.plainText())
val result: SubmitResult = binding.submit("Updated")
```

`title.bind(Properties.plainText())` 是等价简写（`Identity.kt`）。

## 处理 `SubmitResult`

```kotlin
when (val result = binding.submit("Ready")) {
    SubmitResult.Accepted -> Unit
    SubmitResult.Coalesced -> Unit
    is SubmitResult.Rejected -> Log.w("MeguvizUI", "rejected: ${result.reason}")
}
```

`RejectionReason` 取值：`PAUSED`、`FAULTED`、`CLOSED`、`STATE_CAPACITY`、`COMMAND_CAPACITY`、`EMPTY_BATCH`（源码：`ui-core/.../scheduler/UiScheduler.kt`）。不要忽略拒绝：容量、暂停和关闭都会拒绝。

## 自定义 `PropertySpec`

字段（源码：`ui-core/.../Properties.kt`）：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `id` | 必填 | 属性所有权槽；同 View 同 id 只能有一个受管绑定 |
| `effect` | `EffectMask.CONSERVATIVE` | 仅用于合同与诊断，不拦截原生 `invalidate/requestLayout` |
| `ownership` | `ALWAYS_WRITE` | `EXCLUSIVE` 可缓存确认值；`SHARED_READ` 每次读真实值；`ALWAYS_WRITE` 不比较 |
| `coalescible` | `false` | `true` 才进入 latest-state 通道 |
| `equivalent` | `null` | 比较函数；`SHARED_READ` 下必填 |
| `snapshot` | `{ it }` | 必须分离可变输入 |
| `read` | `null` | `SHARED_READ` 下必填，否则抛 `Shared comparison requires a live getter` |
| `comparisonAllowed` | `{ true }` | 是否允许比较 |
| `resourceSensitive` | `false` | 提交前复核资源/主题 token |
| `write` | 必填 | 尾随 lambda |

```kotlin
val spec = PropertySpec<TextView, String>(
    id = "counter.label",
    ownership = PropertyOwnership.SHARED_READ,
    coalescible = true,
    equivalent = { old, new -> old == new },
    read = { it.text.toString() },
    write = { view, value -> view.text = value },
)
val counterBinding = session.bind(title, spec)
counterBinding.submit("Count: 1")
```

`binding` 还暴露 `operation(value)`（生成 `ScheduledOperation` 供批次使用）、`invalidate()`、`close()`，以及诊断计数 `writes` / `skips`（源码：`ui-core/.../Properties.kt`）。

## 命令：点击与一次性操作

```kotlin
val clickLease = session.click(button) { view ->
    binding.submit("Clicked")
}
// 不再需要时：clickLease.close()
```

`session.click` 会保留 `event:click` 属性槽，返回 `AutoCloseable`；外部代码若替换 listener，必须先关闭该句柄（源码：`UiSession.kt`）。

## 直接使用 `UiScheduler`

普通属性写入应继续走 `PropertyBinding.submit`；直接调度器只用于非属性场景（一次性命令、跨属性协调、显式批次）。

```kotlin
// 承接上文：title/button 是 ViewRef，binding 是 PropertyBinding<TextView, String>
val slotTitle = StateSlot(title, "text")
val slotButton = StateSlot(button, "text")

// 单条命令
session.scheduler.submitCommand(
    isValid = { true },
    action = { button.get().requestFocus() },
)

// 状态批次：同槽最后一次更新生效
session.scheduler.submitStates(
    listOf(
        StateUpdate(slotTitle) { title.get().text = "A" },
        StateUpdate(slotButton) { button.get().text = "B" },
    ),
)

// 有序批次：保持自身顺序，不是事务
session.scheduler.submitBatch(
    listOf(
        binding.operation("Saved"),
        ScheduledOperation("focus") { button.get().requestFocus() },
    ),
)
```

`StateSlot` / `StateUpdate` / `ScheduledOperation` 都在 `io.github.jichuo1.meguviz.ui.scheduler` 包下。

- `ScheduledOperation(label, isValid, action)`（源码：`ui-core/.../scheduler/UiScheduler.kt`）。
- `StateUpdate(slot, isValid, action)`（源码：`ui-core/.../scheduler/StateUpdate.kt`）。
- `StateSlot(binding, property)` 把同一绑定的多个属性归入一个公平组（源码：`UiScheduler.kt`）。
- 已发布 `1.0.0` 的批次入口是 `submitBatch(List<ScheduledOperation>)`；`submitBatch(vararg ...)` 便利重载属于当前源码候选（`1.2.0`），消费 `1.0.0` 时请用 `listOf(...)` 形式（见 [核心合同](../contracts.md)）。
- 批次整体准入或拒绝；应用过程**不是事务**，失败前的副作用可能已经发生。

## 调度器诊断

```kotlin
val state: SchedulerState = session.scheduler.state
val failure: SchedulerFailure? = session.scheduler.failure
val counters: SchedulerCounters = session.scheduler.counters
val pendingStates = session.scheduler.pendingStateCount
val pendingCommands = session.scheduler.pendingCommandCount
```

`UiScheduler` 构造参数（源码默认值，`UiScheduler.kt`）：`stateCapacity = 256`、`commandCapacity = 256`、`maxOperationsPerFrame = 64`、`maxFrameWorkNanos = 2_000_000`。普通会话用默认值即可；自定义驱动/容量前请先读 [调度与帧所有权](../scheduling.md)。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Property already owned: <id>` | 同一 View 重复绑定同 id | 先 `binding.close()`，或换 id |
| `Conflicting visual property owner` | 与 Lumen/动画保留槽冲突 | 用 `session.reserveProperties` 明确保权 |
| `Shared comparison requires a live getter` | `SHARED_READ` 未传 `read` | 补上 `read`，或改用 `ALWAYS_WRITE` |
| `Binding closed` | 绑定已关闭仍提交 | 重新绑定 |
| `SubmitResult.Rejected(STATE_CAPACITY)` | 同帧新槽超过 `stateCapacity` | 合并状态、减少同帧属性数，或调大容量 |
| `SubmitResult.Rejected(COMMAND_CAPACITY)` | 命令/批次超过 `commandCapacity` | 拆分批次 |
| `SubmitResult.Rejected(EMPTY_BATCH)` | 空批次 | 不要提交空列表 |
| `Only a faulted scheduler can recover` | 对非 FAULTED 调 `recover()` | 先核对/重建真实状态 |
| `Only a paused scheduler can resume` | 对非 PAUSED 调 `resume()` | 先确认 `scheduler.state` |
| `UiFrameDriver must not invoke a requested callback synchronously` | 自定义驱动内联回调 | 驱动必须异步投递 |
| `Nested frame delivery is not supported` | 帧回调中重入投递 | 不要在 action 里再次请求同帧 |
| `A frame driver must declare its owner` | 自定义驱动 `owner` 为空 | 返回非空 owner |

## 下一步

[05 · 状态保存与恢复](05-restoration.md)
