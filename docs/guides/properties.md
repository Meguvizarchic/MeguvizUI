# 属性绑定与 PropertySpec

本文讲如何声明一个属性、如何复用内置 `Properties`、以及自定义属性时的字段含义。**单库即可使用**：属性协议属于 `ui-core`，内置 spec 属于 `ui-widgets`，不需要任何桥。

## 入口

```kotlin
// 会话入口
fun <V : View, T> UiSession.bind(ref: ViewRef<V>, property: PropertySpec<V, T>): PropertyBinding<V, T>

// ViewRef 上的流式简写（纯委托给 session.bind，校验与清理完全相同）
fun <T> ViewRef<V>.bind(property: PropertySpec<V, T>): PropertyBinding<V, T>
```

两者都会检查 session 处于 active、ref 有效，并在安装时占用属性槽。冲突时抛出 `Property already owned: <id>`。来源：`ui-core/.../ui/UiSession.kt`、`ui-core/.../ui/Identity.kt`。

`PropertyBinding` 的关键成员：

```kotlin
fun submit(value: T): SubmitResult          // 提交一次值
fun operation(value: T): ScheduledOperation // 供有序 batch 使用
fun invalidate()                            // 清空 EXCLUSIVE 确认值缓存
fun close()                                 // 释放属性槽并清理队列
var writes: Long; private set               // 实际写入次数
var skips: Long; private set                // 因 equivalent 跳过的次数
```

## PropertySpec 字段逐项

来源：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`

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

构造期约束：`id` 必须非空；`ownership == SHARED_READ` 时 `read` 必须非空（`Shared comparison requires a live getter`）。

| 字段 | 默认 | 含义与用法 |
| --- | --- | --- |
| `id` | 必填 | 稳定属性标识，同时是节点上的所有权槽名。同一节点同一 `id` 只能有一个受管绑定/保留租约。 |
| `effect` | `CONSERVATIVE` | 诊断掩码，`EffectMask.PAINT / LAYOUT / STRUCTURE / SEMANTICS`，可用 `or` 组合。**不压制**原生 `invalidate`/`requestLayout`。 |
| `ownership` | `ALWAYS_WRITE` | 写入策略，见下节。 |
| `coalescible` | `false` | `true` 走状态通道（同槽 latest wins，返回 `Coalesced`）；`false` 走命令通道（FIFO，保留调用次数）。 |
| `equivalent` | `null` | 可选相等判断。只有 `comparisonAllowed(view)` 为真且存在可比较前值时才生效；不声明就不去重。 |
| `snapshot` | `{ it }` | 把可变输入复制成独立快照。**必须**在排队前完成，排队后调用方不得再修改该对象。 |
| `read` | `null` | 从真实 View 读回当前值。`SHARED_READ` 必填；`EXCLUSIVE` 用于写后确认值缓存。 |
| `comparisonAllowed` | `{ true }` | 是否允许对该 View 做比较。用于排除 EditText、带 filter 的 TextView 等不适合按值去重的场景。 |
| `resourceSensitive` | `false` | `true` 时提交会捕获 theme/resource token，并在实际写入前复核；主题或资源失效的排队值会被丢弃。 |
| `write` | 必填 | 实际写入 View 的 lambda。 |

### PropertyOwnership 三种语义

```kotlin
enum class PropertyOwnership { EXCLUSIVE, SHARED_READ, ALWAYS_WRITE }
```

- `EXCLUSIVE`：库认为该属性由本绑定独占。写入后可用 `read` 读回确认值并缓存，下次比较用缓存。调用者必须保证没有其他写入方，否则会把请求值误当成接受值。
- `SHARED_READ`：每次 apply 前用 `read` 读当前真实值；若 `equivalent` 与 `comparisonAllowed` 允许，相等则跳过。适合外部也可能改、但需要一个权威当前值的属性。
- `ALWAYS_WRITE`：不读回、不缓存，每次 submit 都执行 `write`。这是默认，最保守，不会误删事件或副作用。

注意：`ALWAYS_WRITE` 只是比较策略，不是绕过其他属性所有者的权限。`equivalent` 只有在存在可比较前值时才起作用；`ALWAYS_WRITE` 下前值恒为空，因此声明了 `equivalent` 也不会跳过。

### coalescible 与通道选择

```kotlin
return if (spec.coalescible) ref.session.scheduler.submitState(slot, valid, action)
else ref.session.scheduler.submitCommand(valid, action)
```

- `coalescible = true`：进入状态通道，同一槽位在帧前重复提交会被合并，只保留最后一个值。适合纯展示值。
- `coalescible = false`：进入命令通道，FIFO 且保留调用次数。点击、焦点、带业务监听器的写入、开关切换等**必须**保持次数的操作应保持 `false`。

### snapshot、equivalent、resourceSensitive

```kotlin
// richText 的 snapshot：把 CharSequence 复制成不可变快照
snapshot = { android.text.SpannedString(it) }

// textColor 的资源敏感提交：捕获 token，写入前复核
resourceSensitive = true
```

- `snapshot` 必须分离可变输入。`String` 不可变时可保持默认；`CharSequence`、span、集合等可变类型要显式复制或提供版本语义。
- `equivalent` 是 opt-in。普通标签可声明 `equivalent = { a, b -> a == b }`；富文本不要按 `toString()` 去重。
- `resourceSensitive` 用于主题/资源派生值。`textColor()` 已默认启用；普通不依赖资源的模型值保持 `false`。原位换肤由应用显式 `session.invalidateTheme()` 或上游资源权威失效触发，不能只比较 `Context`/`Theme` 对象地址。

## 内置 Properties 的适用与不适用

来源：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`

| spec | 类型 / id | 关键配置 | 适用场景 | 不适用场景 |
| --- | --- | --- | --- | --- |
| `plainText()` | `TextView, String` / `"text"` | `SHARED_READ`、`coalescible=true`、`equivalent={ a, b -> a == b }`、`read={ it.text.toString() }`、`comparisonAllowed={ it.text is String && it !is EditText && it.filters.isEmpty() }` | 无编辑/过滤器副作用的不可变普通标签 | `EditText` 等输入控件；带 `InputFilter` 的 TextView；富文本/span；需要保留每次 setter 的场合 |
| `richText()` | `TextView, CharSequence` / `"text"` | 默认 `ALWAYS_WRITE`、`coalescible=false`、`snapshot={ SpannedString(it) }` | 富文本、span、每次调用都必须到达 TextView 的场合 | 想按 `toString()` 去重的场景（它不去重）；排队后修改 span 对象（调用者负责不可变） |
| `textColor()` | `TextView, Int` / `"textColor"` | `SHARED_READ`、`resourceSensitive=true`、`coalescible=true`、`equivalent=a==b`、`read={ currentTextColor }` | 主题/资源派生的文字颜色 | 不需要资源复核的固定颜色也可用，但会多一次 token 捕获；非 TextView 的颜色属性需自定义 |
| `visibility()` | `View, Int` / `"visibility"` | `SHARED_READ`、`coalescible=true`、`equivalent=a==b`、`read={ visibility }` | 任意 View 的可见性状态 | 无特殊不适用；注意它写的是 `View.visibility`，不是 `alpha` |
| `checked()` | `CompoundButton, Boolean` / `"checked"` | `ALWAYS_WRITE`、默认 `coalescible=false`、无 `equivalent`、`effect=PAINT\|SEMANTICS` | 复选框/开关等由控件或框架持有最终值的状态；需要保留重复尝试次数 | 需要程序化去重或按值比较跳过的场景；此时应自定义 spec 并显式声明 `equivalent`/`coalescible` |

`checked()` 的注释明确：命令保留重复尝试，控件/框架拥有最终值。这也是“命令不得为性能自动合并”的典型例子。

## 自定义模板

```kotlin
val binding = titleRef.bind(
    PropertySpec<TextView, String>(
        id = "fluent.text",
        ownership = PropertyOwnership.SHARED_READ,
        read = { it.text.toString() },
        write = { view, text -> view.text = text },
    ),
)
```

要点：

1. `id` 是稳定契约，同一节点不能重复。
2. `SHARED_READ` 必须给 `read`，否则构造期报错。
3. 可变输入给 `snapshot`；例如 `snapshot = { ArrayList(it) }` 或 `SpannedString(it)`。
4. 主题/资源派生值给 `resourceSensitive = true`。
5. 纯展示值可 `coalescible = true` + `equivalent`；有业务监听器或必须保次的写入保持默认 `false`。
6. `write` 里可以调用 `requestFocus()`、`invalidate()` 等命令式副作用，但要注意它们不会自动合并。

更完整的例子（含资源敏感与快照）：

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

属性槽由节点持有，除 `bind` 外还有两个入口会占用：

```kotlin
fun <V : View> UiSession.reserveProperties(ref: ViewRef<V>, ids: Set<String>): AutoCloseable
fun <V : View> UiSession.click(ref: ViewRef<V>, action: (V) -> Unit): AutoCloseable
```

- `reserveProperties` 用于在装配 UI 绑定前，把视觉属性（如 Lumen 的 `"background"`）保留给动画/桥；冲突抛 `Conflicting visual property owner`。
- `click` 内部保留 `"event:click"` 槽，并替换 `OnClickListener`；外部代码替换 listener 前必须先关闭该句柄。
- 冲突或关闭时的异常消息：`Property already owned: <id>`、`Conflicting visual property owner`。

## 提交结果

`submit` 返回 `SubmitResult`，可能是 `Accepted`、`Coalesced` 或 `Rejected(reason)`。**不要忽略拒绝**：容量满、scheduler PAUSED/FAULTED/CLOSED 都会拒绝。检查方式：

```kotlin
val result = binding.submit("Updated")
if (result is SubmitResult.Rejected) { /* 按 reason 处理背压 */ }
```

`writes`/`skips` 只统计实际 apply 的次数，不等同于原生 setter 接受次数。

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Identity.kt`
- `ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt`
- 可编译样例：`integration/compile-fixtures/positive/FluentConsumer.kt`、`samples/standalone/src/main/kotlin/io/github/jichuo1/meguviz/samples/standalone/MainActivity.kt`
