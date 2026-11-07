# DSL 与挂载作用域

本文讲如何用 MeguvizUI 的声明式作用域把原生 View 挂到父容器上。**单库即可使用**：只需要 `meguviz-ui-widgets`（传递依赖 `meguviz-ui-core`），不需要 Ref、Android 桥或 Lumen。桥是可选增强，见文末与 [bridges](../bridges.md)。

## 核心对象

| 对象 | 作用 | 来源 |
| --- | --- | --- |
| `UiSession` | 会话根，拥有节点、属性与调度器；`AutoCloseable` | `ui-core/.../ui/UiSession.kt` |
| `CreationEnvironment` | 保留调用方的 themed `Context` 与可选资源权威 | `ui-core/.../ui/Identity.kt` |
| `ViewKey` | 父命名域内的稳定 key（value class） | `ui-core/.../ui/Identity.kt` |
| `ComponentType<V>` | 组件类型标识：`id`、`viewClass`、`schema` | `ui-core/.../ui/Identity.kt` |
| `ViewFactory<V>` | 用 `CreationEnvironment` 构造准确的 V | `ui-core/.../ui/Identity.kt` |
| `ParentPolicy<P, LP>` | 把 child 以 LP 加到父 P 上 | `ui-core/.../ui/Identity.kt` |
| `ViewRef<V>` | 已挂载节点的类型化引用 | `ui-core/.../ui/Identity.kt` |

最小装配：

```kotlin
val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
```

`UiSession` 的 `driver` 默认是 `ChoreographerFrameDriver()`，主线程构造。所有 View/会话 API 都在主线程执行。

## ParentScope

来源：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Widgets.kt`

```kotlin
@UiDsl
open class ParentScope<P : ViewGroup, LP : ViewGroup.LayoutParams>(
    val session: UiSession,
    val parent: P,
    val policy: ParentPolicy<P, LP>,
)
```

`@UiDsl` 标记防止内层 receiver 在 LP 不匹配时意外落到外层父容器。泛型 `LP` 是不变的：`LinearScope` 只接受 `LinearLayout.LayoutParams`，`FrameScope` 只接受 `FrameLayout.LayoutParams`。

常用方法（均返回 `ViewRef<V>`）：

```kotlin
fun <V : View> view(key: ViewKey, type: ComponentType<V>, params: LP,
                    factory: ViewFactory<V>, configure: V.() -> Unit = {}): ViewRef<V>

fun text(key: ViewKey, params: LP, configure: TextView.() -> Unit = {})
fun edit(key: ViewKey, params: LP, configure: EditText.() -> Unit = {})
fun button(key: ViewKey, params: LP, configure: Button.() -> Unit = {})
fun image(key: ViewKey, params: LP, configure: ImageView.() -> Unit = {})

fun linear(key: ViewKey, params: LP, configure: LinearLayout.() -> Unit = {},
           children: LinearScope.() -> Unit): ViewRef<LinearLayout>

fun frame(key: ViewKey, params: LP, configure: FrameLayout.() -> Unit = {},
           children: FrameScope.() -> Unit): ViewRef<FrameLayout>
```

`linear`/`frame` 先挂载容器，再在对应子作用域里执行 `children`；子作用域抛错时会 `session.release(ref)` 回滚本次容器挂载。`children` 没有默认值，必须显式给出。

`Framework` 提供预置 `ComponentType` 与 `ViewFactory`：

```kotlin
object Framework {
    val text / edit / button / image / linear / frame / scroll
    val textFactory / editFactory / buttonFactory / imageFactory
    val linearFactory / frameFactory / scrollFactory
}
```

`Framework.text` 的 `ComponentType("framework.text", TextView::class.java)` 与 `textFactory` 是分开的两个成员。`scroll` 没有对应的 `ParentScope.scroll` 简写，需要走通用 `view(key, Framework.scroll, params, Framework.scrollFactory)`。

## FrameScope 与 LinearScope

```kotlin
class LinearScope(session: UiSession, parent: LinearLayout) :
    ParentScope<LinearLayout, LinearLayout.LayoutParams>(session, parent, LinearPolicy)

class FrameScope(session: UiSession, parent: FrameLayout) :
    ParentScope<FrameLayout, FrameLayout.LayoutParams>(session, parent, FramePolicy)
```

`LinearPolicy`、`FramePolicy`、`ScrollPolicy` 是 `ParentPolicy` 的单例实现。`FrameScope` 的 LP 是 `FrameLayout.LayoutParams`，因此 `frameParams()` 适用；`LinearScope` 用 `linearParams()`。

典型嵌套：

```kotlin
val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
FrameScope(session, root).linear(ViewKey("counter"), frameParams(), configure = {
    orientation = LinearLayout.VERTICAL
    val padding = Dp(24f).pixels(context)
    setPadding(padding, padding, padding, padding)
}) {
    val title = text(ViewKey("count"), linearParams()) { text = "0" }
    val increment = button(ViewKey("increment"), linearParams()) { text = "+" }
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

`Dp`/`Sp` 是值类型，按当前 `Context` 的 `displayMetrics` 换算：

```kotlin
@JvmInline value class Dp(val value: Float) {
    fun pixels(context: Context): Int
}
@JvmInline value class Sp(val value: Float) {
    fun pixels(context: Context): Float
}
```

注意 `configure` 里若覆盖 `orientation`，`linear` 不会替你强制竖直；需要横排请用 `nativeRow` 或自行设置。

## nativeRow（可选 ui-layouts）

`nativeRow` 属于可选模块 `ui-layouts`，需要 Gradle 属性 `enableLayouts=true` 才会加入构建（见 `settings.gradle.kts`）。不启用时单库 DSL 完全不受影响。

来源：`ui-layouts/src/main/kotlin/io/github/jichuo1/meguviz/ui/layouts/NativeRow.kt`

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

它复用 `linear`，强制 `orientation == LinearLayout.HORIZONTAL`，并在 `children` 执行完后用 `constraints.checkChildCount(parent.childCount)` 校验子节点数。`maxChildren` 限定在 1..32。它不改变测量实现，也不宣称性能收益；原生横向行仍由 `LinearLayout` 负责测量、基线、RTL、焦点与无障碍。

```kotlin
FrameScope(session, root).nativeRow(ViewKey("row"), frameParams()) {
    text(ViewKey("label"), linearParams()) { text = "Label" }
    button(ViewKey("action"), linearParams()) { text = "Go" }
}
```

## key 的语义

`ViewKey` 是父命名域内的 key，不是全局唯一：

```kotlin
@JvmInline value class ViewKey(val value: String) {
    init { require(value.isNotBlank()) }
}
```

- 同一父下重复 key 在构造前拒绝：`Duplicate key in parent: <value>`。
- 不同父下可以重用同一 key。
- 同 key 改类型需先显式 `session.release(ref)` 再重建，不提供隐式树 reconcile。
- `borrow` 一个没有父的 View 时，会检查所有根节点是否有同 key：`Duplicate root key: <value>`。

挂载顺序是：预留 key → 工厂构造 → `configure` → `policy.attach` → 登记 `ViewRef`。工厂返回的 View 必须尚未挂载且未被本 session 管理，否则拒绝；已有外部 parent 的 View 不会被自动拆走。

## 常见错误

| 错误 | 现象 | 处理 |
| --- | --- | --- |
| 把 `frameParams()` 传进 `LinearScope` | 编译期 LP 类型不匹配 | 用 `linearParams()` |
| 同父重复 `ViewKey` | `IllegalStateException: Duplicate key in parent` | 换 key 或先 `release` |
| 工厂返回已挂载 View | `Factory returned an already parented View` | 改用 `borrow`/`transfer` |
| 在 `children` 里引用外层 `ParentScope` | `@UiDsl` 编译期拦截 | 用内层作用域的方法 |

## 来源

- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Identity.kt`
- `ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Widgets.kt`
- `ui-layouts/src/main/kotlin/io/github/jichuo1/meguviz/ui/layouts/NativeRow.kt`（可选模块）
- 可编译样例：`samples/standalone/src/main/kotlin/io/github/jichuo1/meguviz/samples/standalone/MainActivity.kt`
