# 04 · 状态与命令

## 两种更新通道

| 通道 | 语义 | 入口 |
| --- | --- | --- |
| 状态（state） | 同槽可合并，仅保留最后一次提交的值 | `PropertyBinding.submit`（`coalescible = true` 时）、`submitState` / `submitStates` / `submitStateAsync` |
| 命令（command） | FIFO 顺序执行，保留每一次调用 | `session.click`、`submitCommand` / `submitBatch` / `submitCommandAsync` |

点击、焦点、开关切换及伴随业务回调的写入属于命令语义，不可为去重擅自合并；需要严格先后顺序时使用批次。

## 常用属性：优先复用 `Properties`

源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`。

| 方法 | 类型 | 适用场景 |
| --- | --- | --- |
| `plainText()` | `PropertySpec<TextView, String>` | 无编辑或过滤器副作用的不可变标签；`coalescible = true`、`SHARED_READ` |
| `richText()` | `PropertySpec<TextView, CharSequence>` | 富文本；每次提交均到达 `TextView`，不按 `toString()` 去重 |
| `textColor()` | `PropertySpec<TextView, Int>` | 文本颜色；启用 `resourceSensitive = true` |
| `visibility()` | `PropertySpec<View, Int>` | 任意 `View` 可见性 |
| `checked()` | `PropertySpec<CompoundButton, Boolean>` | 复选或开关状态；命令语义，保留重复切换 |

```kotlin
val title = FrameScope(session, root).text("title", frameParams()) { text = "Ready" }
val button = FrameScope(session, root).button("button", frameParams()) { text = "Action" }
val binding = title.bind(Properties.plainText())
val result: SubmitResult = binding.submit("Updated")
```

## 处理 `SubmitResult`

```kotlin
when (val result = binding.submit("Ready")) {
    SubmitResult.Accepted -> Unit
    SubmitResult.Coalesced -> Unit
    is SubmitResult.Rejected -> Log.w("MeguvizUI", "rejected: ${result.reason}")
}
```

`RejectionReason` 取值：`PAUSED`、`FAULTED`、`CLOSED`、`STATE_CAPACITY`、`COMMAND_CAPACITY`、`EMPTY_BATCH`（源码：`ui-core/.../scheduler/UiScheduler.kt`）。不要忽略拒绝结果：容量上限、暂停或关闭均不会自动重试。

## 自定义 `PropertySpec`

主要字段（源码：`ui-core/.../Properties.kt`）：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `id` | 必填 | 属性所有权槽名；同一 View 同一 `id` 仅限一个所有者 |
| `effect` | `EffectMask.CONSERVATIVE` | 仅用于合同诊断，不拦截原生 `invalidate` / `requestLayout` |
| `ownership` | `ALWAYS_WRITE` | `EXCLUSIVE` 可缓存确认值；`SHARED_READ` 读当前原生值；`ALWAYS_WRITE` 不比较 |
| `coalescible` | `false` | 设为 `true` 才走可合并的状态通道 |
| `equivalent` | `null` | 相等比较函数；需存在可比前值且允许比较时生效 |
| `snapshot` | `{ it }` | 拷贝可变输入，防止排队后被外部篡改 |
| `read` | `null` | `SHARED_READ` 下必填，否则抛 `Shared comparison requires a live getter` |
| `comparisonAllowed` | `{ true }` | 运行时检查是否允许比较 |
| `resourceSensitive` | `false` | 写入前复核资源与主题 token |
| `write` | 必填 | 写入回调（尾随 lambda） |

```kotlin
val spec = PropertySpec<TextView, String>(
    id = "counter.label",
    ownership = PropertyOwnership.SHARED_READ,
    coalescible = true,
    equivalent = { old, new -> old == new },
    read = { it.text.toString() },
    write = { view, value -> view.text = value },
)
val counterBinding = title.bind(spec)
counterBinding.submit("Count: 1")
```

## 命令：点击与一次性操作

```kotlin
val clickLease = session.click(button) {
    binding.submit("Clicked")
}
// 不再监听时：clickLease.close()
```

`session.click` 占用 `"event:click"` 槽并返回 `AutoCloseable`；外部代码替换 `OnClickListener` 前必须先关闭该句柄。

## 直接使用 `UiScheduler`

普通属性优先通过 `PropertyBinding.submit` 更新；直接调用 `session.scheduler` 适合非属性命令、同语义状态组及有序批次：

```kotlin
val slotTitle = StateSlot(title, "text")
val slotButton = StateSlot(button, "text")

// 1. 单条 FIFO 命令
session.scheduler.submitCommand(
    isValid = { true },
    action = { button.get().requestFocus() },
)

// 2. 同语义状态批次：批内按槽去重，最后一条生效
session.scheduler.submitStates(
    listOf(
        StateUpdate(slotTitle) { title.get().text = "A" },
        StateUpdate(slotButton) { button.get().text = "B" },
    ),
)

// 3. 有序命令批次：保留内部顺序（同时支持 vararg 与 listOf 两种重载）
session.scheduler.submitBatch(
    binding.operation("Saved"),
    ScheduledOperation("focus") { button.get().requestFocus() },
)
```

> 后台线程需向主线程合流更新时，使用 `session.submitStateAsync(slot, isValid) { ... }` 或 `session.submitCommandAsync(isValid) { ... }`，避免为每次更新各自调用 `Handler.post`。

- 批次整批入队或整批拒绝；但执行过程**不是事务**，失败前的操作副作用不会回滚。
- 调度器默认参数：`stateCapacity = 256`、`commandCapacity = 256`、`maxOperationsPerFrame = 64`、`maxFrameWorkNanos = 2_000_000`；调参指南见[调度与帧驱动](../guides/scheduler.md)。

## 调度器诊断

```kotlin
val state: SchedulerState = session.scheduler.state
val failure: SchedulerFailure? = session.scheduler.failure
val counters: SchedulerCounters = session.scheduler.counters
val pendingStates = session.scheduler.pendingStateCount
val pendingCommands = session.scheduler.pendingCommandCount
```

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Property already owned: <id>` | 同一 View 重复绑定同一 `id` | 先 `binding.close()`，或更换 `id` |
| `Conflicting visual property owner` | 与 Lumen / 动画保留槽冲突 | 用 `session.reserveProperties` 明确归属 |
| `Shared comparison requires a live getter` | `SHARED_READ` 未提供 `read` | 补齐 `read`，或改为 `ALWAYS_WRITE` |
| `Binding closed` | 绑定已关闭仍调用 `submit` | 重新绑定 |
| `SubmitResult.Rejected(STATE_CAPACITY)` | 待处理新状态槽超过 `stateCapacity` | 减少并发属性数或合批，再处理退避 |
| `SubmitResult.Rejected(COMMAND_CAPACITY)` | 待处理命令超过 `commandCapacity` | 拆分批次或限流 |
| `SubmitResult.Rejected(EMPTY_BATCH)` | 提交空列表或空参数批次 | 提交前判断非空 |
| `Only a faulted scheduler can recover` | 对非 `FAULTED` 状态调 `recover()` | 先对齐状态再恢复 |
| `UiFrameDriver must not invoke a requested callback synchronously` | 自定义帧驱动在请求中同步执行了回调 | 驱动必须异步投递 |

## 下一步

[05 · 状态保存与恢复](05-restoration.md)
