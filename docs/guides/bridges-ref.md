# Ref 桥（消费者视角）

本文面向应用开发者，讲如何依赖、启用并用 `ui-ref-bridge` 从真实 `CodeScope` 动态构造 View、安全调用方法。

**桥接可选、单库独立可用。** 静态已知类型直接用 `Framework` 工厂即可，不需要 `CodeScope`，也不需要本桥。只有运行时才能确定类型、且该类型来自某个已打开 `CodeScope` 的 ClassLoader 时，才需要 Ref 桥。

## 坐标与版本

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `meguviz.version`，当前 `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-ref-bridge` | `ui-ref-bridge/build.gradle.kts` |
| 上游 | `io.github.jichuo1.meguviz:meguviz-ref-core:1.2.0` | `gradle/libs.versions.toml` |

`meguviz-ui-ref-bridge` 的 `1.0.0` 已随 core/widgets 一起发布到 Maven Central（见 `docs/installation.md`）；当前源码版本 `1.2.0` 是本地候选。上游 `meguviz-ref-core:1.2.0` 的 SHA-256 固定在 `gradle/upstream-candidates.toml`，已发布的 `x.y.z` 版本从 Maven Central 解析并按哈希校验。

## 启用开关

```sh
./gradlew -PenableRef=true verifyHost
./gradlew -PenableRef=true publishCandidate
```

`enableRef` 是 `settings.gradle.kts` 中的真实 Gradle 属性名。不启用时 `ui-ref-bridge` 不参与构建。

## 本地消费

`publishCandidate` 把候选发布到源码根目录的 `build/repo/`。消费项目按 `docs/installation.md` 的方式指向该仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("meguvizUiRepository").get()) }
        google()
        mavenCentral()
    }
}
```

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-ref-bridge:1.2.0")
}
```

上游 `meguviz-ref-core:1.2.0` 发布到 Central 后可直接从 Central 解析；发布前需要本地 Maven 仓库或已按 pins 校验的候选镜像。

## 核心 API

来源：`ui-ref-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/ref/RefBindingSession.kt`

### attach

```kotlin
class RefBindingSession internal constructor(/* ... */) : AutoCloseable {
    companion object {
        fun attach(ui: UiSession, code: CodeScope): RefBindingSession
    }
}
```

`attach` 是唯一公开入口。它要求：

- 主线程（`ui.assertOwner()`）；
- `code.state() == CodeScope.State.OPEN`，否则抛 `CodeScope must be open`；
- 记录 `scope.identity()` 与 `scope.codeEpoch()`，之后每次调用都会校验二者未变；
- 注册 `ui.onClose(binding::close)`，UI 关闭时自动关闭本桥。

桥**借用**真实 `CodeScope`，不关闭共享的代码域；它只负责清理自己的工厂、receiver 与方法句柄。

### viewFactory

```kotlin
fun <V : View> viewFactory(
    type: Class<V>,
    parameterTypes: Array<Class<*>> = arrayOf(Context::class.java),
    arguments: (CreationEnvironment) -> Array<out Any?> = { arrayOf(it.context) },
): RefViewFactory<V>
```

返回的 `RefViewFactory<V>` 实现了 `ViewFactory<V>`，可直接用于 `ParentScope.view`：

```kotlin
class RefViewFactory<V : View> internal constructor(
    owner: RefBindingSession, type: Class<V>, plan: ConstructorPlan,
    arguments: (CreationEnvironment) -> Array<out Any?>,
) : ViewFactory<V>, AutoCloseable {
    override fun create(environment: CreationEnvironment): V
    override fun close()
}
```

约束与行为：

- 只在 `viewFactory` 时解析一次构造计划；`create` 用调用方传入的实际 `CreationEnvironment.context`。
- `type` 必须来自该 `CodeScope` 的实际 ClassLoader，否则 `require(scope.usesLoader(type.classLoader))` 失败。
- 构造失败只传播一次，不尝试其他构造器或 provider。
- 关闭工厂会清除 `Class`、`ConstructorPlan` 与参数回调引用。

### bindView

```kotlin
fun <V : View> bindView(ref: ViewRef<V>, resources: Boolean = false): RefViewLease<V>
```

返回 UI 自有的短寿命接收者句柄：

```kotlin
class RefViewLease<V : View> internal constructor(private val receiver: ReceiverLease<V>) : AutoCloseable {
    fun isCurrent(): Boolean
    fun method(query: QuerySpec): RefMethodHandle
    fun operation(label: String = "ref-view", action: (V) -> Unit): ScheduledOperation
    override fun close()
}
```

每次执行都会同时校验：

1. 真实 `CodeScope` 的 `state/identity/codeEpoch` 未变；
2. UI `ViewRef` 的 binding/attach/resource token 仍当前（`resources=true` 时还检查资源域）。

`bindView` 会在 UI 节点 release/rebind 时通过 `ui.onRelease(ref, lease::close)` 自动关闭。关闭会清除 receiver、token 回调，并让所属 UI 队列清理无效命令。

### 方法调用

```kotlin
class RefMethodHandle internal constructor(receiver: ReceiverLease<*>, plan: MethodPlan) : AutoCloseable {
    fun isCurrent(): Boolean
    fun invoke(vararg arguments: Any?): Any?
    fun operation(label: String = "ref-method", vararg arguments: Any?): ScheduledOperation
    override fun close()
}
```

- `invoke` 直接调用真实 `MethodPlan`；`InvocationTargetException` 等错误原样传播，不改写成“找不到”。
- `operation` 把调用包装成 `ScheduledOperation`，交给所属 `UiSession` 的命令队列或有序 batch。反射参数数组会复制，但可变参数对象仍需调用方提供合适快照。

## 典型流程

```kotlin
val codeBinding = RefBindingSession.attach(ui, codeScope)

// 1. 动态工厂：解析一次构造计划
val factory = codeBinding.viewFactory(MyCustomView::class.java)

// 2. 挂到父容器，得到类型化引用
val ref = FrameScope(ui, root).view(ViewKey("custom"), ComponentType("custom", MyCustomView::class.java), frameParams(), factory)

// 3. 绑定已挂载节点，取得接收者句柄
val lease = codeBinding.bindView(ref)

// 4. 命令式调用：保留次数与顺序
ui.scheduler.submitBatch(
    lease.operation("focus") { it.requestFocus() },
    lease.method(QuerySpec.builder(MyCustomView::class.java, MemberKind.METHOD).name("refresh").build())
        .operation("refresh"),
)

// 5. 释放
lease.close()
codeBinding.close()
```

## 常见错误

| 错误 | 原因 | 处理 |
| --- | --- | --- |
| `CodeScope must be open` | `attach` 时 scope 已关闭 | 先打开/重建 `CodeScope` |
| `Dynamic factory type must belong to this CodeScope's actual loader` | 类型来自别的 ClassLoader | 确认类型由该 scope 的 loader 加载 |
| `Ref binding session is closed` / `View binding token is stale` | 句柄已关闭或 View token 失效 | 检查 `isCurrent()`，不要缓存旧句柄 |
| `The borrowed CodeScope identity or epoch changed` | 共享 CodeScope 被替换/重建 | 关闭旧桥并重新 `attach` |
| 把 `RefViewLease`/`RefMethodHandle` 当 latest 状态槽 | 它们走命令通道 | 保持 `submitBatch`，不要改成 `submitState` |

## 来源

- `ui-ref-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/ref/RefBindingSession.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`（`onClose`/`onRelease`）
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiScheduler.kt`（`submitBatch`）
- `settings.gradle.kts`、`gradle/libs.versions.toml`、`gradle/upstream-candidates.toml`
- `docs/bridges/ref.md`、`docs/bridges.md`、`docs/installation.md`
- 可编译样例：`integration/compile-fixtures/positive-ref/Consumer.kt`
