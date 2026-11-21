# DSL 与挂载作用域

本文介绍如何用 MeguvizUI 的声明式作用域把原生 `View` 挂到父容器上。**单库即可使用**：只需 `meguviz-ui-widgets`（传递依赖 `meguviz-ui-core`），不需要 Ref、Android 桥或 Lumen。桥接扩展见[可选桥边界](../bridges.md)。

## 核心对象

| 对象 | 作用 | 来源 |
| --- | --- | --- |
| `UiSession` | 会话根，拥有节点、属性与调度器；`AutoCloseable` | `ui-core/.../ui/UiSession.kt` |
| `CreationEnvironment` | 保留调用方的 themed `Context` 与可选资源权威 | `ui-core/.../ui/Identity.kt` |
| `ViewKey` | 父命名域内的稳定 key（value class） | `ui-core/.../ui/Identity.kt` |
| `ComponentType<V>` | 组件类型标识：`id`、`viewClass`、`schema` | `ui-core/.../ui/Identity.kt` |
| `ViewFactory<V>` | 使用 `CreationEnvironment` 构造准确的 `V` | `ui-core/.../ui/Identity.kt` |
| `ParentPolicy<P, LP>` | 将 child 以 `LP` 挂载到父容器 `P` | `ui-core/.../ui/Identity.kt` |
| `ViewRef<V>` | 已挂载节点的类型化引用 | `ui-core/.../ui/Identity.kt` |

最小装配：

```kotlin
val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
```

`UiSession` 默认使用 `ChoreographerFrameDriver()`，须在主线程构造；所有 View 与会话 API 均在主线程执行（异步更新和关闭入口除外）。

## ParentScope

源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Widgets.kt`

```kotlin
@UiDsl
open class ParentScope<P : ViewGroup, LP : ViewGroup.LayoutParams>(
    val session: UiSession,
    val parent: P,
    val policy: ParentPolicy<P, LP>,
)
```

`@UiDsl` 标记防止内层作用域意外调用外层父容器的方法。泛型 `LP` 是不变的：`LinearScope` 只接受 `LinearLayout.LayoutParams`，`FrameScope` 只接受 `FrameLayout.LayoutParams`。

常用挂载方法（均返回 `ViewRef<V>`，同时提供 `ViewKey` 与 `String` key 两种 `@JvmOverloads` 重载，供 Kotlin 与 Java 直接调用）：

```kotlin
fun <V : View> view(key: ViewKey, type: ComponentType<V>, params: LP,
                    factory: ViewFactory<V>, configure: V.() -> Unit = {}): ViewRef<V>

fun text(key: ViewKey, params: LP, configure: TextView.() -> Unit = {}): ViewRef<TextView>
fun edit(key: ViewKey, params: LP, configure: EditText.() -> Unit = {}): ViewRef<EditText>
fun button(key: ViewKey, params: LP, configure: Button.() -> Unit = {}): ViewRef<Button>
fun image(key: ViewKey, params: LP, configure: ImageView.() -> Unit = {}): ViewRef<ImageView>

fun linear(key: ViewKey, params: LP, configure: LinearLayout.() -> Unit = {},
           children: LinearScope.() -> Unit): ViewRef<LinearLayout>

fun frame(key: ViewKey, params: LP, configure: FrameLayout.() -> Unit = {},
          children: FrameScope.() -> Unit): ViewRef<FrameLayout>
```

`linear` / `frame` 先挂载容器节点，再在对应子作用域中执行 `children`；若 `children` 抛出异常，会自动调用 `session.release(ref)` 回滚本次容器挂载。`children` 没有默认值，必须显式传入。

`Framework` 提供预置 `ComponentType` 与 `ViewFactory`：

```kotlin
object Framework {
    val text / edit / button / image / linear / frame / scroll
    val textFactory / editFactory / buttonFactory / imageFactory
    val linearFactory / frameFactory / scrollFactory
}
```

`Framework.scroll` 没有对应的 `ParentScope.scroll` 简写，须调用 `view(key, Framework.scroll, params, Framework.scrollFactory)`。

## FrameScope 与 LinearScope

```kotlin
class LinearScope(session: UiSession, parent: LinearLayout) :
    ParentScope<LinearLayout, LinearLayout.LayoutParams>(session, parent, LinearPolicy)

class FrameScope(session: UiSession, parent: FrameLayout) :
    ParentScope<FrameLayout, FrameLayout.LayoutParams>(session, parent, FramePolicy)
```

`LinearPolicy`、`FramePolicy`、`ScrollPolicy` 为 `ParentPolicy` 的单例实现。`FrameScope` 搭配 `frameParams()`，`LinearScope` 搭配 `linearParams()`。

典型嵌套示例：

```kotlin
val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
FrameScope(session, root).linear("counter", frameParams(), configure = {
    orientation = LinearLayout.VERTICAL
    val padding = Dp(24f).pixels(context)
    setPadding(padding, padding, padding, padding)
}) {
    val title = text("count", linearParams()) { text = "0" }
    val increment = button("increment", linearParams()) { text = "+" }
}
```

## LayoutParams 与尺寸

```kotlin
val MatchParent: Int = ViewGroup.LayoutParams.MATCH_PARENT
val WrapContent: Int = ViewGroup.LayoutParams.WRAP_CONTENT

fun linearParams(width: Int = MatchParent, height: Int = WrapContent,
                 configure: LinearLayout.LayoutParams.() -> Unit = {}): LinearLayout.LayoutParams

fun frameParams(width: Int = MatchParent, height: Int = WrapContent,
                configure: FrameLayout.LayoutParams.() -> Unit = {}): FrameLayout.LayoutParams
```

`Dp` / `Sp` 是值类型，按当前 `Context` 的 `displayMetrics` 换算：

```kotlin
@JvmInline value class Dp(val value: Float) {
    fun pixels(context: Context): Int
}
@JvmInline value class Sp(val value: Float) {
    fun pixels(context: Context): Float
}
```

> `linear` 不会在 `configure` 里替你强制竖直方向；横向排布可使用 `nativeRow` 或自行设置 `orientation = LinearLayout.HORIZONTAL`。

## `nativeRow`（可选 `ui-layouts`）

`nativeRow` 属于已发布的 `meguviz-ui-layouts:1.2.0`（源码构建需启用 `-PenableLayouts=true`）。

源码：`ui-layouts/src/main/kotlin/io/github/jichuo1/meguviz/ui/layouts/NativeRow.kt`

```kotlin
data class NativeRowConstraints(val maxChildren: Int = 8) {
    fun checkChildCount(count: Int)
}

fun <P : ViewGroup, LP : ViewGroup.LayoutParams> ParentScope<P, LP>.nativeRow(
    key: ViewKey,
    params: LP,
    constraints: NativeRowConstraints = NativeRowConstraints(),
    configure: LinearLayout.() -> Unit = {},
    children: LinearScope.() -> Unit,
): ViewRef<LinearLayout>
```

它复用 `linear`、强制 `orientation == LinearLayout.HORIZONTAL`，并在 `children` 执行后检查 `constraints.checkChildCount(parent.childCount)`（`maxChildren` 范围为 `1..32`）。原生横向行仍由 `LinearLayout` 负责测量、基线、RTL、焦点与无障碍语义，不改变测量实现。

```kotlin
FrameScope(session, root).nativeRow(ViewKey("row"), frameParams()) {
    text("label", linearParams()) { text = "Label" }
    button("action", linearParams()) { text = "Go" }
}
```

## key 的语义

`ViewKey` 作用域归属当前父容器，而非全局唯一：

```kotlin
@JvmInline value class ViewKey(val value: String) {
    init { require(value.isNotBlank()) }
}
```

- 同一父容器下重复 key 在构造前即被拒绝：`Duplicate key in parent: <value>`。
- 不同父容器下可以复用同一 key；`"title"` 字符串形式与 `ViewKey("title")` 指向同一 key。
- 同一 key 更换组件类型须先显式 `session.release(ref)` 再挂载，不提供隐式通用树 diff/reconcile。
- `borrow` 无 parent 的根 View 时，若已有同 key 的根节点则抛出 `Duplicate root key: <value>`。

挂载顺序为：占用 key → 工厂创建 → `configure` → `policy.attach` → 登记 `ViewRef`。工厂返回的 View 必须尚未挂载且未被当前会话管理；已有外部 `parent` 的 View 不会被自动拆走。

## 常见错误

| 错误 | 现象 | 处理 |
| --- | --- | --- |
| 将 `frameParams()` 传入 `LinearScope` | 编译期 LP 类型不匹配 | 改用 `linearParams()` |
| 同父重复 `ViewKey` | `IllegalStateException: Duplicate key in parent` | 更换 key 或先 `release` |
| 工厂返回已有 parent 的 View | `Factory returned an already parented View` | 复用既有视图改用 `borrow` / `transfer` |
| 在 `children` 内引用外层作用域 | `@UiDsl` 编译期拦截 | 使用内层作用域方法 |

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Identity.kt`
- `ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Widgets.kt`
- `ui-layouts/src/main/kotlin/io/github/jichuo1/meguviz/ui/layouts/NativeRow.kt`
- 可编译样例：`samples/standalone/src/main/kotlin/io/github/jichuo1/meguviz/samples/standalone/MainActivity.kt`
