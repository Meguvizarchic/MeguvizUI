# 属性绑定与 PropertySpec

本文介绍如何声明属性、复用内置 `Properties`，以及自定义 `PropertySpec` 时的各字段含义。**单库即可使用**：属性协议位于 `ui-core`，内置规格位于 `ui-widgets`，不需要任何桥模块。

## 入口

```kotlin
// 会话入口
fun <V : View, T> UiSession.bind(ref: ViewRef<V>, property: PropertySpec<V, T>): PropertyBinding<V, T>

// ViewRef 上的流式简写（纯委托给 session.bind，校验与清理完全相同）
fun <T> ViewRef<V>.bind(property: PropertySpec<V, T>): PropertyBinding<V, T>
```

两个入口均检查会话处于 `ACTIVE`、引用有效，并在安装时占用属性槽。重复绑定同一 `id` 会抛出 `Property already owned: <id>`。源码：`ui-core/.../ui/UiSession.kt`、`ui-core/.../ui/Identity.kt`。

`PropertyBinding` 的核心成员：

```kotlin
fun submit(value: T): SubmitResult          // 提交一次值
fun operation(value: T): ScheduledOperation // 生成供有序 batch 使用的命令项
fun invalidate()                            // 清空 EXCLUSIVE 确认值缓存
fun close()                                 // 释放属性槽并清理队列
var writes: Long; private set               // 实际执行写入的次数
var skips: Long; private set                // 因 equivalent 命中而跳过的次数
```

## `PropertySpec` 字段说明

源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`

```kotlin
class PropertySpec<V : View, T>(
    val id: String,
    val effect: EffectMask = EffectMask.CONSERVATIVE,
    val ownership: PropertyOwnership = PropertyOwnership.ALWAYS_WRITE,
    val coalescible: Boolean = false,
    val equivalent: ((T, T) -> Boolean)? = null,
    val snapshot: (T) -> T = { it },
    val read: ((V) -> T)? = null,
    val comparisonAllowed: (V) -> Boolean = { true },
    val resourceSensitive: Boolean = false,
    val write: (V, T) -> Unit,   // 必填，可作尾随 lambda
)
```

构造约束：`id` 必须非空白；当 `ownership == PropertyOwnership.SHARED_READ` 时，`read` 必须非空，否则抛出 `Shared comparison requires a live getter`。

| 字段 | 默认值 | 含义与用法 |
| --- | --- | --- |
| `id` | 必填 | 稳定属性标识与节点上的所有权槽名，同一节点同 `id` 只允许一个所有者。 |
| `effect` | `CONSERVATIVE` | 诊断掩码（`EffectMask.PAINT / LAYOUT / STRUCTURE / SEMANTICS`，可用 `or` 组合），不拦截原生 `invalidate` / `requestLayout`。 |
| `ownership` | `ALWAYS_WRITE` | 比较与缓存策略，见下节。 |
| `coalescible` | `false` | `true` 走状态通道（同槽合并，返回 `Coalesced`）；`false` 走命令通道（FIFO 保留次数）。 |
| `equivalent` | `null` | 可选相等判定函数；仅在 `comparisonAllowed(view)` 为真且存在可比前值时生效。 |
| `snapshot` | `{ it }` | 将可变输入复制为独立快照；排队后调用方不得再修改输入对象。 |
| `read` | `null` | 从真实 `View` 读回当前值。`SHARED_READ` 必填；`EXCLUSIVE` 用于写后缓存读回。 |
| `comparisonAllowed` | `{ true }` | 运行时检查是否允许对该 `View` 做值比较，用于排除 `EditText` 或带 filter 的文本框。 |
| `resourceSensitive` | `false` | 设为 `true` 时提交会捕获 theme / resource token 并在应用前复核，失效任务自动跳过。 |
| `write` | 必填 | 实际将值写入原生 `View` 的回调。 |

### `PropertyOwnership` 三种语义

```kotlin
enum class PropertyOwnership { EXCLUSIVE, SHARED_READ, ALWAYS_WRITE }
```

- `EXCLUSIVE`：假设该属性完全由本绑定独占。写入后优先通过 `read` 读回确认值（未提供 `read` 时退回使用写入值）缓存起来用于下次对比；存在外部写入方时不可用。
- `SHARED_READ`：每次 apply 前通过 `read` 读取当前真实值，若 `equivalent` 判定与新值相等且 `comparisonAllowed` 为真则跳过写入。适合存在外部修改但需要对齐当前权威值的场景。
- `ALWAYS_WRITE`：不读回、不比较、不缓存，每次提交均执行 `write`。这是最保守的默认行为，不会误删副作用。

### `coalescible` 与提交通道

```kotlin
return if (spec.coalescible) ref.session.scheduler.submitState(slot, valid, action)
else ref.session.scheduler.submitCommand(valid, action)
```

- `coalescible = true`：进入状态通道，同槽在帧前多次提交会自动合并，仅应用最后一次。适合纯展示属性。
- `coalescible = false`：进入命令通道，按 FIFO 保留每一次调用。点击、焦点、开关切换或伴随业务回调的操作应保持 `false`。

### `snapshot`、`equivalent` 与 `resourceSensitive`

```kotlin
// richText 的 snapshot：将 CharSequence 复制为不可变快照
snapshot = { android.text.SpannedString(it) }

// textColor 的资源敏感标记：捕获资源与主题 token
resourceSensitive = true
```

- `String` 等不可变类型可使用默认 `snapshot`；`CharSequence`、span、集合等可变类型必须主动拷贝。
- 普通纯展示值可声明 `equivalent = { a, b -> a == b }`；富文本切勿按 `toString()` 比较去重。
- 涉及主题色或资源引用的属性声明 `resourceSensitive = true`；原位换肤时需由应用调用 `session.invalidateTheme()` 或触发上游资源权威失效。

## 内置 `Properties` 选型对照

源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`

| spec | 类型 / `id` | 关键配置 | 适用场景 | 不适用场景 |
| --- | --- | --- | --- | --- |
| `plainText()` | `TextView, String` / `"text"` | `SHARED_READ`、`coalescible = true`、`equivalent` 比较、`comparisonAllowed` 排除 `EditText` 与 filter | 无输入/过滤器副作用的不可变纯标签 | `EditText`、带 `InputFilter` 的文本框、富文本 span、必须保次的写入 |
| `richText()` | `TextView, CharSequence` / `"text"` | `ALWAYS_WRITE`、`coalescible = false`、`snapshot = { SpannedString(it) }` | 富文本、span、每次均须触达 `TextView` 的设置 | 需要按 `toString()` 去重的纯文本标签 |
| `textColor()` | `TextView, Int` / `"textColor"` | `SHARED_READ`、`resourceSensitive = true`、`coalescible = true`、`read = { currentTextColor }` | 主题或资源派生的文本颜色 | 非 `TextView` 控件颜色（需自行定义 spec） |
| `visibility()` | `View, Int` / `"visibility"` | `SHARED_READ`、`coalescible = true`、`read = { visibility }` | 任意 `View` 可见性切换 | 渐变透明度动画（应控制 `alpha`） |
| `checked()` | `CompoundButton, Boolean` / `"checked"` | `ALWAYS_WRITE`、`coalescible = false`、无 `equivalent` | 复选框、开关等保留每次交互状态的操作 | 需按值合并去重的纯展示状态 |

## 自定义 `PropertySpec` 模板

```kotlin
val binding = titleRef.bind(
    PropertySpec<TextView, String>(
        id = "fluent.text",
        ownership = PropertyOwnership.SHARED_READ,
        coalescible = true,
        equivalent = { a, b -> a == b },
        read = { it.text.toString() },
        write = { view, text -> view.text = text },
    ),
)
```

含可变快照与资源敏感复核的进阶示例：

```kotlin
PropertySpec<TextView, CharSequence>(
    id = "detail.body",
    effect = EffectMask.LAYOUT or EffectMask.SEMANTICS,
    ownership = PropertyOwnership.SHARED_READ,
    coalescible = true,
    equivalent = { a, b -> a.toString() == b.toString() },
    snapshot = { SpannedString(it) },
    read = { it.text },
    comparisonAllowed = { it !is EditText },
    resourceSensitive = true,
    write = { view, text -> view.text = text },
)
```

## 属性占用与冲突

除了 `bind`，以下两个入口也会向节点登记属性槽：

```kotlin
fun <V : View> UiSession.reserveProperties(ref: ViewRef<V>, ids: Set<String>): AutoCloseable
fun <V : View> UiSession.click(ref: ViewRef<V>, action: (V) -> Unit): AutoCloseable
```

- `reserveProperties` 用于在挂载 UI 绑定前，将视觉属性（如 Lumen 桥的 `"background"`）预留给外部适配器；冲突时抛出 `Conflicting visual property owner`。
- `click` 占用 `"event:click"` 槽并接管 `OnClickListener`；外部替换监听器前须先关闭该返回的 `AutoCloseable` 句柄。

## 检查提交结果

`submit` 返回 `SubmitResult`（`Accepted`、`Coalesced` 或 `Rejected(reason)`）。当调度器处于 `PAUSED`、`FAULTED`、`CLOSED` 或队列满载时不会静默丢弃或抛出，而是返回 `Rejected`，调用方必须检查并处理：

```kotlin
val result = binding.submit("Updated")
if (result is SubmitResult.Rejected) {
    // 按 result.reason 处理背压或状态恢复
}
```

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Identity.kt`
- `ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`
- 可编译样例：`integration/compile-fixtures/positive/FluentConsumer.kt`
