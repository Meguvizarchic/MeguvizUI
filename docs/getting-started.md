# 消费者快速上手

本指南面向**使用** MeguvizUI 的应用开发者，目标是约 5 分钟跑通第一个界面。它讲的是「怎么把库接进你的 App」，不是「怎么从源码构建库」——构建者视角见 [GETTING_STARTED.md](GETTING_STARTED.md) 与 [安装与接入](installation.md)。

## 先决条件

- Android 工程，`minSdk` 不低于 **27**（库的声明最低版本，见 `ui-core/build.gradle.kts`、`ui-widgets/build.gradle.kts`）。
- 消费已发布版本不需要准备源码工具链；如果要从源码构建本地候选，需要 JDK 17、Android SDK 37、Build Tools 37.0.0（见 [安装与接入](installation.md)）。
- 普通应用只需依赖 `meguviz-ui-widgets`，它会通过 `api(project(":ui-core"))` 传递依赖 `meguviz-ui-core`（见 `ui-widgets/build.gradle.kts`）。

## 五步跑通

1. [添加依赖](quickstart/01-add-dependency.md) —— Maven Central 坐标与版本选择。
2. [显示第一屏](quickstart/02-first-screen.md) —— 最小 Activity、挂载 TextView、绑定属性。
3. [生命周期与关闭](quickstart/03-lifecycle-and-close.md) —— 主线程、`close()`、detach/reattach。
4. [状态与命令](quickstart/04-state-and-commands.md) —— `PropertyBinding`、`UiScheduler`、批次。
5. [状态保存与恢复](quickstart/05-restoration.md) —— `ViewRestoration` 与 `Bundle`。

## 最小完整示例

```kotlin
package com.example.demo

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams

class MainActivity : Activity() {
    private lateinit var session: UiSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        setContentView(root)

        // 1. 会话绑定到当前 themed Context；必须在主线程创建。
        session = UiSession(CreationEnvironment(this))

        // 2. 用作用域 DSL 挂载一个 TextView。
        val title = FrameScope(session, root).text(ViewKey("title"), frameParams()) {
            text = "Hello, MeguvizUI"
        }

        // 3. 绑定属性并提交状态。
        title.bind(Properties.plainText()).submit("Updated")
    }

    override fun onDestroy() {
        // 4. 必须在主线程关闭，释放绑定、监听与受管子树。
        session.close()
        super.onDestroy()
    }
}
```

## 一行接入（可选）

不想自己组装根视图、会话与作用域时，`Activity.openMeguvizUi()` 一步到位（源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/UiScope.kt`）：

```kotlin
class MainActivity : Activity() {
    private lateinit var ui: UiScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = openMeguvizUi()      // 根 FrameLayout + UiSession + FrameScope
        setContentView(ui.root)
        ui.scope.text(ViewKey("title"), frameParams()) { text = "Hello" }
            .bind(Properties.plainText()).submit("Updated")
    }
    // 不需要 onDestroy：Activity 销毁时会话自动关闭。
}
```

- **幂等**：同一 Activity 再次调用返回同一个未关闭实例，`onResume` 之类重复路径不会叠出第二个会话。
- **误用报错**：非主线程或已销毁的 Activity 直接抛异常，不会返回一个没人关闭的会话。
- **不引入 AndroidX**：裸 `android.app.Activity` 即可用。
- 根视图仍归调用者所有：`setContentView(ui.root)` 之后继续用 `ui.scope` 构建；`ui.close()` 幂等，提前关闭后再调用 `openMeguvizUi()` 会开一个新会话。
- 需要 `WindowSession` 支撑的会话（Android 桥）改用 `AndroidUiBinding.open(window, root)`，见[桥接指南](guides/bridges-android.md)。

## 三条关键约定

1. **主线程独占**：`UiSession` 的构造、挂载、属性提交、释放和 `close()` 都必须在 Android 主线程调用，否则抛 `IllegalStateException("UI operation requires the main thread")`（源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:59`）。纯模型计算可以放后台，但不能据此推断 View 可以异步构造。
2. **`session.close()` 必须调用**：在 Activity/窗口所有者销毁时调用，否则绑定、监听和受管子树不会被释放。后台可以用 `session.requestClose()` 发起，返回的 `CloseRequest.completed` 表示 owner 已实际清理完成（源码：`UiSession.kt:261-269`）。
3. **状态保存/恢复交给 `ViewRestoration`**：它负责焦点、选择与滚动；业务模型（计数、草稿等）由应用自己保存，不要从 UI 快照里恢复文本（源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/state/ViewRestoration.kt:52`）。

## 推荐写法说明

- `ParentScope` 已经提供 `text` / `edit` / `button` / `image` / `linear` / `frame` 六个简洁方法（源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Widgets.kt:68-82`）。它们内部走 `session.mount`，是官方推荐的挂载方式；**不需要**再引入 `mountText` / `mountEdit` / `mountButton` 之类的冗余入口。
- `ViewRef.bind(PropertySpec)` 已存在（源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Identity.kt:89`），是 `session.bind(ref, property)` 的纯委托简写，可直接链式调用。已发布 `1.0.0` 保证 `session.bind(ref, property)`；`ref.bind(...)` 属于当前源码候选（`1.2.0`），消费 `1.0.0` 时请用 `session.bind(...)`。
- 常用属性优先复用 `Properties.plainText()` / `richText()` / `textColor()` / `visibility()` / `checked()`（源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/Properties.kt:10-43`），不要一上来就手写 10 参数的 `PropertySpec`。

## 常见报错速查

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `UI operation requires the main thread` | 在非主线程调用会话 API | 切回主线程 |
| `Session is not active` | 会话已 close、已 detach 或调度器 FAULTED/CLOSED | 检查生命周期与 `session.scheduler.state` |
| `Duplicate key in parent: <key>` | 同一父容器下重复 `ViewKey` | 换 key 或先 `session.release(ref)` |
| `Property already owned: <id>` | 同一 View 上重复绑定同一属性 id | 先 `binding.close()` 或换 id |
| `Conflicting visual property owner` | 与 Lumen/动画保留的属性冲突 | 用 `session.reserveProperties` 明确所有权 |
| `View reference released` | 引用已随节点/会话释放 | 不要跨释放持有 `ViewRef` |
| `Binding closed` | 绑定已关闭仍提交 | 重新绑定 |

更完整的异常目录见各 quickstart 的「常见报错」小节与 [核心合同](contracts.md)。

## 下一步

- [示例索引](examples.md)：`samples/` 下真实样例。
- [核心合同](contracts.md)、[调度与帧所有权](scheduling.md)、[属性与更新](property-semantics.md)。
- 可选桥与 P2 模块见 [可选桥](bridges.md) 与 [P2 可选能力](p2.md)。
