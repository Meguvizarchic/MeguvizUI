# MeguvizUI API 与模块速查

本文档汇总 MeguvizUI `1.2.0` 的全部制品坐标、工具链要求、核心 API 签名及与家族其他库的边界。

## 1. 工具链与编译要求

来源：`gradle/libs.versions.toml`、`gradle.properties` 及各模块 `build.gradle.kts`。

| 项目 | 要求 | 说明 |
| --- | --- | --- |
| `compileSdk` | `37` 或更新版本 | 库模块均在 `compileSdk = 37` 下编译与验证 |
| `minSdk` | `27` | 消费工程的 `minSdk` 不得低于 `27` |
| JDK / 字节码目标 | JDK `17`（`JVM_17`） | Android 与 JVM 模块均配置 `JavaVersion.VERSION_17` |
| Kotlin 编译器 | 消费方至少 `2.3+` | 本库以 Kotlin `2.4.21` 编译，元数据版本 `2.4.0`；Kotlin `2.2` 及以下无法读取 |
| KSP（仅 Codegen） | `2.3.12` | 仅使用 `meguviz-ui-codegen` 时需要启用 `com.google.devtools.ksp` 插件 |
| Gradle / AGP 基线 | Gradle `9.8.2` / AGP `9.4.1` | 源码构建锁定的版本基线 |
| Maven 仓库 | `google()` 与 `mavenCentral()` | 源码构建 `ui-lumen-bridge` 时额外需要 `jitpack.io` |

## 2. Maven 制品与版本口径（`1.2.0`）

统一 BOM：`io.github.jichuo1.meguviz:meguviz-bom:1.2.0`（已发布到 Maven Central，集中对齐家族 33 个 `1.2.0` 制品）。

### 2.1 已发布到 Maven Central 的 12 个制品

groupId 均为 `io.github.jichuo1.meguviz`，version 均为 `1.2.0`。

| 源码模块 | artifactId | 核心职责与传递依赖 | 源码开关 |
| --- | --- | --- | --- |
| `:ui-core` | `meguviz-ui-core` | `UiSession`、`ViewRef`、`PropertySpec`、`UiScheduler`、`ViewRestoration`、`RegistrySnapshot` | 默认开启 |
| `:ui-widgets` | `meguviz-ui-widgets` | `openMeguvizUi()`、`ParentScope` / `FrameScope` / `LinearScope`、`Framework`、内置 `Properties`（`api` 传递 `ui-core`） | 默认开启 |
| `:ui-plan` | `meguviz-ui-plan` | `UiPlan`、`PlanNode`、`PlanComponent`、分阶段验证/解析/绑定/挂载（`api` 传递 `ui-core`） | `-PenablePlan=true` |
| `:ui-codegen-api` | `meguviz-ui-codegen-api` | `@GenerateUiFactory(id, schema)` 编译期源码注解 | `-PenableCodegen=true` |
| `:ui-codegen` | `meguviz-ui-codegen` | KSP 符号处理器 `UiFactoryProcessorProvider`（依赖 `symbol-processing-api:2.3.12`） | `-PenableCodegen=true` |
| `:ui-recycler` | `meguviz-ui-recycler` | `UiListAdapter` 后台有界 Diff 列表适配（`api` 传递 `ui-core` 与 `recyclerview:1.4.0`） | `-PenableRecycler=true` |
| `:ui-text` | `meguviz-ui-text` | `PrecomputedTextBinding` 长纯文本异步预计算（`api` 传递 `ui-core`） | `-PenableText=true` |
| `:ui-layouts` | `meguviz-ui-layouts` | `nativeRow` 与 `NativeRowConstraints` 受限横向行（`api` 传递 `ui-widgets`） | `-PenableLayouts=true` |
| `:ui-prewarm` | `meguviz-ui-prewarm` | `UiPrewarmer` / `PrewarmWork` 分帧有界主线程预热（`api` 传递 `ui-core`） | `-PenablePrewarm=true` |
| `:ui-frame-shared` | `meguviz-ui-frame-shared` | `SharedFrameDriver` / `ChoreographerFrameSource` 多会话合流帧驱动（`api` 传递 `ui-core`） | `-PenableFrameShared=true` |
| `:ui-ref-bridge` | `meguviz-ui-ref-bridge` | `RefBindingSession`（`api` 传递 `ui-core` 与 `meguviz-ref-core:1.2.0`） | `-PenableRef=true` |
| `:ui-android-bridge` | `meguviz-ui-android-bridge` | `AndroidUiBinding` / `AndroidFrameDriver`（`api` 传递 `ui-core`、`meguviz-android-runtime:1.2.0`、`meguviz-android-window:1.2.0`） | `-PenableAndroid=true` |

> 内部辅助模块 `:ui-testkit`（提供 `VirtualFrameDriver`）不发布到 Maven Central，仅用于仓库内单元测试。启用全部 7 个 P2 能力时可用聚合开关 `-PenableP2=true`。

### 2.2 尚未发布到 Central 的例外模块

| 源码模块 | artifactId | 当前状态与使用方式 |
| --- | --- | --- |
| `:ui-lumen-bridge` | `meguviz-ui-lumen-bridge:1.2.0` | **尚未发布到 Maven Central**，仅可源码构建启用 `-PenableLumen=true` 并通过 `publishCandidate` 生成本地 Maven 仓库消费。源码 `gradle/libs.versions.toml` 当前锁定 JitPack `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5`（注：`LumenCoacervationEngine` 本身已有 `io.github.jichuo1:lumen-engine:1.6.2` 发布到 Central 与 JitPack）。 |

### 2.3 Gradle 依赖配置模板（含 BOM 与 KSP 提醒）

```kotlin
dependencies {
    val meguvizBom = platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")
    implementation(meguvizBom)

    // 1. 常规界面（必需）
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")

    // 2. 可选 P2 扩展（按实际需要增删）
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-recycler")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-text")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-layouts")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-prewarm")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-plan")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-frame-shared")

    // 3. 若启用 KSP 代码生成，compileOnly 与 ksp 必须各自显式引入 BOM（或手动指定 :1.2.0）
    // compileOnly(meguvizBom)
    // ksp(meguvizBom)
    // compileOnly("io.github.jichuo1.meguviz:meguviz-ui-codegen-api")
    // ksp("io.github.jichuo1.meguviz:meguviz-ui-codegen")

    // 4. 可选桥接（无对应兄弟宿主时勿添加）
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-ref-bridge")
    // implementation("io.github.jichuo1.meguviz:meguviz-ui-android-bridge")
}
```

## 3. 核心 API 签名速查

### 3.1 一行入口与 Widgets 作用域（`io.github.jichuo1.meguviz.ui.widgets`）

```kotlin
fun Activity.openMeguvizUi(): UiScope // Java: UiScopeKt.openMeguvizUi(activity)

class UiScope(val session: UiSession, val root: FrameLayout) : AutoCloseable {
    val scope: FrameScope
    val isClosed: Boolean
    override fun close()
}

open class ParentScope<P : ViewGroup, LP : ViewGroup.LayoutParams>(
    val session: UiSession, val parent: P, val policy: ParentPolicy<P, LP>,
) {
    fun <V : View> view(key: ViewKey, type: ComponentType<V>, params: LP, factory: ViewFactory<V>, configure: V.() -> Unit = {}): ViewRef<V>
    fun text(key: ViewKey, params: LP, configure: TextView.() -> Unit = {}): ViewRef<TextView>
    fun edit(key: ViewKey, params: LP, configure: EditText.() -> Unit = {}): ViewRef<EditText>
    fun button(key: ViewKey, params: LP, configure: Button.() -> Unit = {}): ViewRef<Button>
    fun image(key: ViewKey, params: LP, configure: ImageView.() -> Unit = {}): ViewRef<ImageView>
    fun linear(key: ViewKey, params: LP, configure: LinearLayout.() -> Unit = {}, children: LinearScope.() -> Unit): ViewRef<LinearLayout>
    fun frame(key: ViewKey, params: LP, configure: FrameLayout.() -> Unit = {}, children: FrameScope.() -> Unit): ViewRef<FrameLayout>

    // String key 重载（带 @JvmOverloads，Java 与 Kotlin 均直接调用，与 ViewKey("...") 等价）
    @JvmOverloads fun <V : View> view(key: String, type: ComponentType<V>, params: LP, factory: ViewFactory<V>, configure: V.() -> Unit = {}): ViewRef<V>
    @JvmOverloads fun text(key: String, params: LP, configure: TextView.() -> Unit = {}): ViewRef<TextView>
    @JvmOverloads fun edit(key: String, params: LP, configure: EditText.() -> Unit = {}): ViewRef<EditText>
    @JvmOverloads fun button(key: String, params: LP, configure: Button.() -> Unit = {}): ViewRef<Button>
    @JvmOverloads fun image(key: String, params: LP, configure: ImageView.() -> Unit = {}): ViewRef<ImageView>
    @JvmOverloads fun linear(key: String, params: LP, configure: LinearLayout.() -> Unit = {}, children: LinearScope.() -> Unit): ViewRef<LinearLayout>
    @JvmOverloads fun frame(key: String, params: LP, configure: FrameLayout.() -> Unit = {}, children: FrameScope.() -> Unit): ViewRef<FrameLayout>
}

fun linearParams(width: Int = MatchParent, height: Int = WrapContent, configure: LinearLayout.LayoutParams.() -> Unit = {}): LinearLayout.LayoutParams
fun frameParams(width: Int = MatchParent, height: Int = WrapContent, configure: FrameLayout.LayoutParams.() -> Unit = {}): FrameLayout.LayoutParams
```

`Framework` 内置组件与工厂：`text`、`edit`、`button`、`image`、`linear`、`frame`、`scroll` 及对应 `*Factory`（`scroll` 需通过 `view(..., Framework.scroll, params, Framework.scrollFactory)` 挂载）。

### 3.2 会话、引用与属性（`io.github.jichuo1.meguviz.ui`）

```kotlin
class CreationEnvironment(
    val context: Context,
    val resourceOwner: String = "application",
    val resourceAuthority: ResourceAuthority? = null,
)

@JvmInline value class ViewKey(val value: String)

class ViewRef<V : View> internal constructor(...) {
    fun get(): V
    fun token(resources: Boolean = false): BindingToken
    fun <T> bind(property: PropertySpec<V, T>): PropertyBinding<V, T>
}

class UiSession(
    environment: CreationEnvironment,
    driver: UiFrameDriver = ChoreographerFrameDriver(),
) : AutoCloseable {
    val environment: CreationEnvironment
    val state: SessionState // ACTIVE, DETACHED, DISPOSED
    val scheduler: UiScheduler
    fun assertOwner()
    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> mount(...): ViewRef<V>
    fun <V : View> borrow(view: V, key: ViewKey, component: ComponentType<V>): ViewRef<V>
    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> transfer(view: V, parent: P, policy: ParentPolicy<P, LP>, params: LP, key: ViewKey, component: ComponentType<V>): ViewRef<V>
    fun inflate(inflater: LayoutInflater, resource: Int, parent: ViewGroup, key: ViewKey): ViewRef<View>
    fun <V : View> token(ref: ViewRef<V>, resources: Boolean = false): BindingToken
    fun <V : View, T> bind(ref: ViewRef<V>, property: PropertySpec<V, T>): PropertyBinding<V, T>
    fun <V : View> reserveProperties(ref: ViewRef<V>, ids: Set<String>): AutoCloseable
    fun <V : View> click(ref: ViewRef<V>, action: (V) -> Unit): AutoCloseable
    fun <V : View> onRelease(ref: ViewRef<V>, cleanup: () -> Unit)
    fun onClose(cleanup: () -> Unit)
    fun rebind(ref: ViewRef<*>)
    fun release(ref: ViewRef<*>)
    fun detach()
    fun reattach()
    fun invalidateTheme()
    fun invalidateResources()
    fun requestClose(): CloseRequest
    fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    override fun close()
}
```

内置 `Properties`（`io.github.jichuo1.meguviz.ui.widgets.Properties`）：
- `plainText()`: `PropertySpec<TextView, String>`，`SHARED_READ`、`coalescible = true`（仅适合不可变普通标签，不适合 `EditText` 或带 filter 控件）。
- `richText()`: `PropertySpec<TextView, CharSequence>`，`ALWAYS_WRITE`、`coalescible = false`、`snapshot = { SpannedString(it) }`。
- `textColor()`: `PropertySpec<TextView, Int>`，`SHARED_READ`、`coalescible = true`、`resourceSensitive = true`。
- `visibility()`: `PropertySpec<View, Int>`，`SHARED_READ`、`coalescible = true`。
- `checked()`: `PropertySpec<CompoundButton, Boolean>`，`ALWAYS_WRITE`、`coalescible = false`（保留重复切换）。

### 3.3 调度器与异步提交通道（`io.github.jichuo1.meguviz.ui.scheduler`）

```kotlin
fun interface OwnerThreadDispatcher {
    fun post(callback: Runnable): Boolean
}

class UiScheduler(
    driver: UiFrameDriver,
    assertOwner: () -> Unit,
    val stateCapacity: Int = 256,
    val commandCapacity: Int = 256,
    val maxOperationsPerFrame: Int = 64,
    val maxFrameWorkNanos: Long = 2_000_000,
    nanoTime: () -> Long = System::nanoTime,
    dispatcher: OwnerThreadDispatcher? = null, // 另有 7 参次级构造器，dispatcher 默认为 null
) : AutoCloseable {
    fun submitState(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    fun submitStates(updates: List<StateUpdate>): SubmitResult
    fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    fun submitBatch(operations: List<ScheduledOperation>): SubmitResult
    fun submitBatch(vararg operations: ScheduledOperation): SubmitResult
    fun submitStateAsync(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    fun submitCommandAsync(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult
    fun pause(clearPending: Boolean = true)
    fun resume()
    fun recover()
    fun discardInvalid()
}
```

`SubmitResult` 取值：`Accepted`、`Coalesced`、`Rejected(reason: RejectionReason)`；`RejectionReason` 取值：`PAUSED`、`FAULTED`、`CLOSED`、`STATE_CAPACITY`、`COMMAND_CAPACITY`、`EMPTY_BATCH`。

### 3.4 状态恢复、P2 模块与可选桥入口

| 类 / 方法 | 包名 | 关键用途 |
| --- | --- | --- |
| `ViewRestoration` / `RestorationSnapshot` | `io.github.jichuo1.meguviz.ui.state` | 登记稳定 path 与 id，布局后恢复焦点、选区与滚动；不恢复文本 |
| `UiPlan` / `PlanNode` / `PlanComponent` / `MountedUiPlan` | `io.github.jichuo1.meguviz.ui.plan` | `validate() -> resolve() -> bind() -> mount()` 分阶段纯数据挂载 |
| `@GenerateUiFactory(id, schema = 1)` | `io.github.jichuo1.meguviz.ui.codegen` | 为 public 顶层非泛型单一非空 `Context` 构造 View 生成 `*UiGenerated` 与 `*ViewRef` |
| `UiListAdapter` | `io.github.jichuo1.meguviz.ui.recycler` | 后台有界 Diff 列表适配；提供带 `snapshotItem` 与不可变模型两种构造器 |
| `PrecomputedTextBinding` | `io.github.jichuo1.meguviz.ui.text` | 默认关闭（`enabled = false`），API 28+ 长纯文本异步预计算，占用 `"text"` |
| `ParentScope.nativeRow` / `NativeRowConstraints` | `io.github.jichuo1.meguviz.ui.layouts` | 受限横向 `LinearLayout`，限制初始子控件数 `1..32`（默认 `8`） |
| `UiPrewarmer` / `PrewarmWork` | `io.github.jichuo1.meguviz.ui.prewarm` | 复用会话调度器每帧至多执行 1 个主线程预热单元 |
| `SharedFrameDriver` / `ChoreographerFrameSource` | `io.github.jichuo1.meguviz.ui.frames` | 同主线程多 `UiSession` 合流到单次平台帧回调；上限 256 个待处理请求 |
| `AndroidUiBinding.open(window, root)` / `AndroidFrameDriver` | `io.github.jichuo1.meguviz.ui.android` | 接入 `WindowSession` 同一帧通道并绑定 `androidResourceAuthority` |
| `RefBindingSession.attach(ui, code)` | `io.github.jichuo1.meguviz.ui.ref` | 借用 `CodeScope` 创建 `RefViewFactory` 与 `RefViewLease` |
| `LumenUiBridge(ui, lumen, ownsSession = false)` | `io.github.jichuo1.meguviz.ui.lumen` | 占用 `"background"` 槽，把 UI 节点绑定到 `LumenSurfaceSession` |

## 4. 家族跨库协同边界

- **单独使用 MeguvizUI**：标准 Android `Activity` / `Fragment` 直接用 `openMeguvizUi()` 或 `UiSession(CreationEnvironment(context))`，不引入任何桥模块。
- **与 `MeguvizAndroid` 配合**：存在 `WindowSession` 时才引入 `meguviz-ui-android-bridge`，使用 `AndroidUiBinding.open(window, root)`；不要额外运行第二套独立的帧调度循环。
- **与 `MeguvizRef` 配合**：仅动态加载未知 `View` 类或延迟方法调用时引入 `meguviz-ui-ref-bridge`；静态已知类型直接写 Kotlin 工厂或 `@GenerateUiFactory`。
- **与 `MeguvizDrawable` / `Lumen` 的冲突避让**：`LumenUiBridge.bind` 会调用 `ui.reserveProperties(ref, setOf("background"))`，与同一 `UiSession` 内绑定 `"background"` 属性互斥；但 `MeguvizDrawable` 侧的 `RecipeBackground` 使用独立 `BindingScope`，跨栈不共享注册表，应用层不得把同一个 `View` 的 `background` 同时交给 Lumen 表面与 Drawable 配方。
