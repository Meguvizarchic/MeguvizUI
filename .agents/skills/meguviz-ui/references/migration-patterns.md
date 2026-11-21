# MeguvizUI 迁移对照与排障

本文档提供存量 Android 命令式代码迁移、MeguvizUI `1.0.0 -> 1.2.0` 升级、所有权/线程铁律、高频排障以及安全回退方案。

## 1. 存量命令式代码迁移范式

### 1.1 `findViewById` + 手工 setter → `openMeguvizUi()` + `ViewRef.bind`

**迁移前（命令式裸写）**：

```kotlin
class OldActivity : Activity() {
    private lateinit var titleView: TextView
    private var clickListener: View.OnClickListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        titleView = findViewById(R.id.title)
        titleView.text = "Ready"
        val button = findViewById<Button>(R.id.action)
        button.setOnClickListener { titleView.text = "Clicked" }
    }
}
```

**迁移后（`1.2.0` 一行会话接入）**：

```kotlin
class NewActivity : Activity() {
    private lateinit var ui: UiScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = openMeguvizUi()
        setContentView(ui.root)

        lateinit var titleBinding: PropertyBinding<TextView, String>
        ui.scope.linear("container", frameParams()) {
            val title = text("title", linearParams()) { text = "Ready" }
            titleBinding = title.bind(Properties.plainText())
            val action = button("action", linearParams()) { text = "Go" }
            session.click(action) {
                titleBinding.submit("Clicked")
            }
        }
    }
}
```

- 普通 `Activity` 使用 `openMeguvizUi()` 即可自动跟随销毁关闭；非 `Activity` 场景（如 `Fragment` 视图或自定义容器）显式创建 `UiSession(CreationEnvironment(context))`，并在 `onDestroyView` / `onDestroy` 调用 `session.close()`。
- 不要在 `ParentScope` 上再包一层 `mountText` / `mountButton` 工具函数。

### 1.2 后台 `Handler.post` 频繁刷新 → `submitStateAsync` 帧内合流

**迁移前（每次后台结果都触发一次 `Handler.post` 派发）**：

```kotlin
executor.execute {
    repeat(100) { progress ->
        val text = "Progress: $progress%"
        mainHandler.post { progressView.text = text }
    }
}
```

**迁移后（有界 handoff + 单次主线程派发 + 帧内合并）**：

```kotlin
val progressRef = ui.scope.text("progress", linearParams()) { text = "0%" }
val token = progressRef.token()

executor.execute {
    repeat(100) { progress ->
        val latest = "Progress: $progress%"
        val result = ui.session.submitStateAsync(
            slot = progressRef,
            isValid = token::isCurrent,
        ) {
            progressRef.get().text = latest
        }
        if (result is SubmitResult.Rejected) {
            // 按 PAUSED / FAULTED / CLOSED / STATE_CAPACITY 处理背压，不静默吞掉
        }
    }
}
```

- **原因**：`PropertyBinding.submit()` 会校验主线程归属，不可在后台直接调用。`UiSession.submitStateAsync` / `submitCommandAsync` 在后台只写入有界 handoff；同一热槽的多次更新在合并前即已折叠，100 次后台更新只产生 1 次主线程派发。

### 1.3 存量 XML 渐进接管 → `session.inflate` + `session.borrow`

无法整屏重写为 Kotlin DSL 时，先保留原有 XML 和主题，用 `session.inflate` 与 `session.borrow` 接管动态更新节点（参考 `samples/xml-interop`）：

```kotlin
val themed = ContextThemeWrapper(this, R.style.PanelTheme)
val inflater = layoutInflater.cloneInContext(themed) // 保留 AppCompat Factory2
val panel = session.inflate(inflater, R.layout.native_panel, root, ViewKey("native-panel"))
val label = session.borrow(
    panel.get().findViewById<TextView>(R.id.xml_label),
    ViewKey("borrowed-label"),
    ComponentType("native.label", TextView::class.java),
)
val binding = label.bind(Properties.plainText())
```

- `borrow` 保留原生 View 树所有权；`session.release(label)` 或 `session.close()` 不会把借用的 View 从 XML 父容器移除。
- `inflate` 拒绝以 `<merge>` 作为根标签的布局文件。

### 1.4 列表后台 Diff → `UiListAdapter`

- **模型不可变（含 `val` 标识与 diff 字段）**：直接用 4 参构造器 `UiListAdapter(background, stableId, itemCallback, onDiffFailure)`，每次 `submitList` 在主线程只做一次数组拷贝。
- **模型包含可变字段**：使用 5 参构造器传入 `snapshotItem`，在主线程复制所有参与 ID 与 Diff 的字段。
- **异步绑定防错位**：在 `bindFull` 中捕获 `val isCurrent = bindingToken(holder)`，异步结果回到主线程先检查 `isCurrent()`；在 `onRebind(holder)` 释放该 holder 的旧会话绑定。
- **关闭顺序**：`recyclerView.adapter = null` 先解绑，再调用 `adapter.close()`。

### 1.5 状态恢复边界 → 模型归业务，`ViewRestoration` 管表现

- `ViewRestoration` 只保存并恢复焦点、滚动位置和 `EditText` 选区，**永远不恢复文本**。
- `EditText` 设置 `isSaveEnabled = false`，由应用恢复业务草稿后，按 `register(path, ref, root)` -> `restoreAfterLayout(root)` 顺序调用；用户在恢复触发前输入时调用 `restoration.userInteracted()` 撤销过期恢复。

## 2. 状态合并 vs 命令保次选型

| 更新特征 | 正确通道 | 推荐写法 | 误用后果 |
| --- | --- | --- | --- |
| 纯文本标签、颜色、可见性 | 状态通道（`coalescible = true`） | `Properties.plainText()`、`textColor()`、`visibility()`、`submitStates(...)` | — |
| 富文本 / `EditText` / 带 Filter 文本 | 命令通道（`coalescible = false`） | `Properties.richText()`、业务输入控制器 | 用 `plainText()` 会损坏 IME/span 状态 |
| 按钮点击、开关切换、焦点请求 | 命令通道（FIFO） | `session.click`、`Properties.checked()`、`submitCommand` | 状态合并会丢弃交互次数 |
| 多节点跨属性严格先后顺序 | 有序命令批次 | `session.scheduler.submitBatch(op1, op2)` | 仅用状态通道无跨槽严格次序 |

## 3. `1.0.0 -> 1.2.0` 升级对照

`1.2.0` 的公开 JVM 描述符是 `1.0.0` 的严格超集（由 `verifyLegacyAbi` 锁定），保留了 7 参 `UiScheduler` 次级构造器，旧二进制可直接链接。

| 能力 | `1.0.0` 写法 | `1.2.0` 推荐写法 | 变化说明 |
| --- | --- | --- | --- |
| 依赖管理 | 逐个声明 `:1.0.0` | `platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")` | 12 个 UI 制品已全量发布到 Central |
| Activity 入口 | 手工 `FrameLayout` + `UiSession` + `onDestroy` | `val ui = openMeguvizUi()` | 幂等托管 Activity 销毁生命周期 |
| Java/简洁挂载 key | `ViewKey("title")`（Java 无法调用） | `scope.text("title", params)` | 新增 `String` key `@JvmOverloads` 重载 |
| 属性绑定 | `session.bind(ref, spec)` | `ref.bind(spec)` | `ViewRef.bind` 纯委托简写 |
| 批量状态 / 命令 | 循环 `submitState` / `submitBatch(listOf(...))` | `submitStates(List<StateUpdate>)` / `submitBatch(op1, op2)` | 单次 owner 校验与批内去重 |
| 跨线程提交 | 业务自行 `Handler.post` | `ui.submitStateAsync` / `submitCommandAsync` | 有界 handoff + 一次主线程派发 |
| 多会话合流帧驱动 | 无此模块 | `meguviz-ui-frame-shared` (`SharedFrameDriver`) | 同主线程多 `UiSession` 可选合流 |
| Android 桥 / Ref 桥 | 1.0.0 无 Android 桥 | `meguviz-ui-android-bridge:1.2.0`、`meguviz-ui-ref-bridge:1.2.0` | 均已随 BOM 1.2.0 发布到 Central |

**行为差异提醒**：
- `ChoreographerFrameDriver` 在 API `33+` 改用 `postVsyncCallback` 获取真实帧 deadline（`estimated = false`），为 traversal/draw 预留 `1ms` 尾部。
- `SHARED_READ` / `ALWAYS_WRITE` 不再执行不被读取的写后读回或额外快照，减少无用开销。

## 4. 生命周期、所有权与关闭顺序铁律

1. **主线程独占**：除 `UiSession.requestClose()`、`submitStateAsync`、`submitCommandAsync` 外，所有会话、作用域、恢复与桥 API 只能在 Android 主线程调用。
2. **标准关闭顺序**（逆序释放，防止悬挂帧回调或上游已销毁）：
   - `RecyclerView`：`recyclerView.adapter = null` -> `adapter.close()`
   - `UiPlan`：`mountedPlan.close()` -> `session.close()`
   - 多会话共享驱动：`sessionA.close()` / `sessionB.close()` -> `sharedDriver.close()`
   - Android 桥：`androidUiBinding.close()` -> `windowSession.close()`
   - Lumen 桥（默认 `ownsSession = false`）：`registration.close()` -> `lumenBridge.close()` -> `session.close()`（外部 `LumenSurfaceSession` 由其实际创建者关闭）
3. **跨栈 `background` 互斥**：`LumenUiBridge.bind` 会占位 `"background"`，阻止同一 `UiSession` 再绑定 `"background"`；但 `MeguvizDrawable` 的 `RecipeBackground` 使用独立作用域，二者之间不要同时托管同一个 `View` 的背景。

## 5. 高频报错排查表

| 报错关键词 | 常见原因 | 解决方案 |
| --- | --- | --- |
| `UI operation requires the main thread` | 在后台线程调用 `UiSession` / `PropertyBinding.submit` | 改用主线程或使用 `ui.submitStateAsync` / `submitCommandAsync` |
| `Session is not active` | 会话已 `close()`、处于 `DETACHED` 或调度器 `FAULTED` | 检查生命周期；detach 后 `reattach()`；FAULTED 先核对状态再 `recover()` |
| `Duplicate key in parent` | 同一父 `ViewGroup` 下挂载了重复 `ViewKey` | 换用唯一 key，或先 `session.release(oldRef)` 再重新挂载 |
| `Property already owned` | 同一节点重复绑定同 `id` 属性（如混用 `plainText` 与 `PrecomputedTextBinding`） | 每个属性 `id` 仅保留一个绑定，先 `close()` 旧绑定 |
| `Conflicting visual property owner` | 在已有属性后占用相同槽（如先绑 `"background"` 再绑 Lumen） | 先调用 `reserveProperties` 或 `LumenUiBridge.bind` 占位 |
| `Explicit native ID must be managed by the caller's hierarchy restoration` | `View` 已手动设置 `id`，又交给 `ViewRestoration` 分配 | 移除手动 `view.id` 赋值，或完全由应用原生层级恢复管理该 id |
| `Detach the adapter from every RecyclerView before close` | 未先置空 `recyclerView.adapter` 即关闭 `UiListAdapter` | 先 `recyclerView.adapter = null`，再调 `adapter.close()` |
| `Could not find ...:meguviz-ui-codegen:` | 在 `ksp(...)` 配置中遗漏 BOM 或版本 | 在 `ksp(platform(...))` 与 `compileOnly(platform(...))` 中同时引入 BOM |
| `Could not find ...:meguviz-ui-lumen-bridge` | 尝试从 Maven Central 拉取未发布的 Lumen 桥 | 改用本地源码构建 `-PenableLumen=true publishCandidate` |

## 6. 安全回退方案

- **页面级回退**：通过 `session.inflate` / `session.borrow` 接入的存量 XML 页面，只需移除 `UiSession` 装配并恢复原 `findViewById` 赋值即可无损退回原实现。
- **能力降级**：
  - `PrecomputedTextBinding` 出现设备字体/方向兼容疑虑时，将 `enabled = false` 即可立刻回退为普通 `TextView.setText`。
  - `SharedFrameDriver` 多会话合流不需要时，只需改回 `UiSession(CreationEnvironment(context))` 默认构造即可恢复每会话独立 `ChoreographerFrameDriver`。
  - `1.2.0` 升级遇到异常时，原有 `1.0.0` API（`session.bind`、`submitBatch(List)`、7 参 `UiScheduler`）全部保留，可局部回退到旧调用形式。
