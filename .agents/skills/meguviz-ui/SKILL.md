---
name: meguviz-ui
description: "为 Android 工程接入 MeguvizUI 1.2.0 或迁移存量原生 View 代码与 1.0.0 旧用法。用于新界面搭建强类型布局作用域、UiSession 生命周期托管、PropertyBinding 属性合并、UiScheduler 调度、ViewRestoration 表现恢复、RecyclerView/长文本预计算/预热/KSP 工厂扩展；以及存量命令式 findViewById + setter + Handler.post 刷 UI 迁移或 1.0.0 升级到 1.2.0。边界转交：窗口权限与独立窗口帧通道转交 MeguvizAndroid；动态代码域与反射调用规划转交 MeguvizRef；图片/Drawable 解码缓存与配方转交 MeguvizDrawable；模糊材质表面配置转交 LumenCoacervationEngine。"
---

# MeguvizUI 接入与迁移

MeguvizUI 是面向 Android 原生 `View` 的强类型 Kotlin UI 库，通过父布局作用域、属性绑定和主线程有界调度器管理界面生命周期；不依赖其他兄弟库即可独立运行。

## 第 1 阶段：盘点工程现状

修改任何代码前，先读取目标工程的构建脚本与现有界面入口，向用户输出简短评估后再动代码：

1. **工具链与 SDK 基线**：检查 `build.gradle.kts`、`gradle/libs.versions.toml` 与 `gradle.properties`，确认 `compileSdk 37+`、`minSdk 27+`、JDK `17`、Kotlin `2.3+`（`MeguvizUI 1.2.0` 以 Kotlin `2.4.21` 编译，字节码元数据为 `2.4.0`）。
2. **存量界面与线程盘点**：梳理现有的 `findViewById`、手工 setter、后台线程 `Handler.post` 更新 UI、XML `LayoutInflater`、`RecyclerView.Adapter` 与 `onSaveInstanceState` 逻辑。
3. **版本与桥接需求**：确认是否正在使用 `1.0.0` 旧接口（`session.bind`、`submitBatch(listOf(...))`），以及是否真实存在 `WindowSession`、`CodeScope` 或 `LumenSurfaceSession` 宿主。
4. **输出简短评估后动工**：在修改代码前先向用户列出：
   - 工程基线检查结果（`compileSdk`、`minSdk`、Kotlin、KSP 版本）；
   - 待迁移页面 / 控件适配范围（如命令式 `findViewById`、后台 `Handler.post`、XML 节点或 `1.0.0` 旧用法）；
   - 拟选模块与明确不引入的桥接清单，确认最小变更范围后再执行改造。

## 第 2 阶段：选择模块与接入依赖

`io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 已发布到 Maven Central，统一管理 MeguvizUI 的 12 个已发布 `1.2.0` 制品。**默认保持无桥独立接入，严禁把未使用的可选桥接或 P2 模块写入依赖**。

| 使用场景 | 推荐模块坐标（`io.github.jichuo1.meguviz:*`） | 接入边界与说明 |
| --- | --- | --- |
| 常规原生界面与控件（必选） | `meguviz-ui-widgets` | 自动传递依赖 `meguviz-ui-core`；无桥独立可用 |
| 声明式数据计划 / 受限横向行 | `meguviz-ui-plan`、`meguviz-ui-layouts` | 动态计划拆分或受限横向行时按需添加 |
| 列表后台 Diff / 长文本 / 预热 | `meguviz-ui-recycler`、`meguviz-ui-text`、`meguviz-ui-prewarm` | 长列表、API 28+ 长纯文本标签或分帧预热时添加 |
| 多会话共享帧驱动 | `meguviz-ui-frame-shared` | 仅同线程多 `UiSession` 共存时选用；单会话无收益 |
| KSP 生成直接工厂 | `meguviz-ui-codegen-api` + `meguviz-ui-codegen` | 注解加 `compileOnly`，处理器加 `ksp` |
| 绑定 Ref 动态类 / Android 窗口 | `meguviz-ui-ref-bridge`、`meguviz-ui-android-bridge` | 仅当工程已有真实 `CodeScope` 或 `WindowSession` |
| 绑定 Lumen 视觉表面 | `meguviz-ui-lumen-bridge`（**未上 Central**） | 仅可源码构建启用 `-PenableLumen=true`，不可写进 Central 依赖 |

**`meguviz-ui-codegen` 的 BOM 必填提醒**：Gradle 的 `ksp` 和 `compileOnly` 配置不会自动继承 `implementation(platform(...))`，使用 KSP 代码生成时必须在 `compileOnly` 与 `ksp` 同时显式声明 `platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")`：

```kotlin
dependencies {
    val bom = platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")
    implementation(bom)
    compileOnly(bom)
    ksp(bom)
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")
    compileOnly("io.github.jichuo1.meguviz:meguviz-ui-codegen-api")
    ksp("io.github.jichuo1.meguviz:meguviz-ui-codegen")
}
```

## 第 3 阶段：执行最小迁移与生命周期对齐

### 最小核心骨架（Kotlin）

```kotlin
class MainActivity : Activity() {
    private lateinit var ui: UiScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = openMeguvizUi() // 普通 Activity 自动托管销毁关闭；Java 用 UiScopeKt.openMeguvizUi(this)
        setContentView(ui.root)

        val title = ui.scope.text("title", frameParams()) { text = "Ready" }
        val titleBinding = title.bind(Properties.plainText())
        titleBinding.submit("Updated") // 主线程状态合并；提交后检查 SubmitResult

        // 后台线程切回主线程合并提交（N 次后台更新只触发 1 次主线程派发）：
        val token = title.token()
        ui.session.submitStateAsync(slot = title, isValid = token::isCurrent) {
            title.get().text = "From background"
        }
    }
}
```

> 窗口与 Java 说明：
> - 若页面挂载在 `MeguvizAndroid` 的 `WindowSession` 下，不调用 `openMeguvizUi()`，改用 `AndroidUiBinding.open(windowSession, rootView)` 并在销毁前按「先 binding 后 window」顺序关闭；
> - 无 `Activity` 时直接构造 `UiSession(CreationEnvironment(themedContext))` 搭配 `FrameScope(session, root)`；
> - Java 调用方通过 `ParentScope` 的 `String` key 重载（如 `ui.getScope().text("title", params)`）直接挂载控件。

### 迁移与生命周期铁律

1. **主线程与关闭配对**：`UiSession` 的创建、挂载、`PropertyBinding.submit`、释放与 `close()` 必须在主线程执行；手动创建的 `UiSession` 必须在宿主 `onDestroy()` 调用 `session.close()`，后台仅可调用 `session.requestClose()`。
2. **状态合并 vs FIFO 命令**：纯展示状态使用 `coalescible = true`（如 `Properties.plainText()`、`textColor()`、`visibility()`）按槽保留最新值；点击（`session.click`）、焦点、开关（`Properties.checked()`、`richText()`、`submitCommand`、`submitBatch`）走 FIFO 命令通道保留每次调用。
3. **属性所有权隔离**：同一 `ViewRef` 上相同属性 `id` 只允许一个所有者；接入 Lumen（占用 `"background"`）、外部动画或 `PrecomputedTextBinding`（占用 `"text"`）前须先调用 `session.reserveProperties`。
4. **消费返回结果**：检查 `SubmitResult`，处理 `Rejected` 原因（`PAUSED` / `FAULTED` / `CLOSED` / `STATE_CAPACITY` / `COMMAND_CAPACITY` / `EMPTY_BATCH`）；进入 `FAULTED` 后须对齐真实 View 状态再调用 `scheduler.recover()`。
5. **状态恢复边界**：`ViewRestoration` 仅保存和恢复滚动、焦点与选区，**永远不恢复文本**；业务草稿由应用模型恢复后，再按 `register` -> `restoreAfterLayout` 顺序执行。
6. **XML 渐进迁移与回退**：存量 XML 页面用 `session.inflate` 与 `session.borrow` 接管局部控件，按页面逐步替换；不强制一次性整屏重写。

> **按需深入阅读**：完整 12 个已发布制品坐标、API 签名、Java 互操作与工具链基线见 [API 与模块速查](references/api-and-modules.md)；命令式旧代码与 `1.0.0 -> 1.2.0` 迁移对照、所有权清理顺序与排障表见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方验证**：在接入工程执行编译、lint 与单元测试（例如 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`），确认无 KSP 生成缺失、API 版本冲突、主线程违例或属性槽重复。
2. **本库源码自检（若涉及源码构建或本地修改验证）**：
   - 默认核心与示例：`./gradlew verifyHost --no-daemon --max-workers=2`
   - 全量 P2 模块：`./gradlew -PenableP2=true verifyP2 --no-daemon --max-workers=2`
   - 已发布可选桥：`./gradlew -PenableRef=true -PenableAndroid=true verifyHost`（验证 Lumen 桥追加 `-PenableLumen=true`）
3. **回退与异常核对**：确认 `UiListAdapter` 在 `close()` 前已从 `RecyclerView` 解绑；确认未迁移页面的原 XML / View 入口仍可独立回退；确认所有 `onRelease` / `onClose` 清理闭包直接捕获原生 View 引用而非重新解析已释放 `ViewRef`。
4. **交付摘要**：简明汇报改动模块、新增依赖坐标、执行过的验证命令及实际结果，并标明仍需在真机测试的交互或性能项。
