# 消费者快速上手

本指南面向使用 MeguvizUI 的应用开发者，目标是在约 5 分钟内跑通第一个界面。如果要从源码构建或修改本库，请查看[构建、验证与贡献](development.md)与[安装与接入](installation.md)。

## 先决条件

- Android 工程：`compileSdk 37+`、`minSdk 27+`、JDK `17`、Kotlin `2.3+`（库源码以 Kotlin `2.4.21` 编译）。
- 仓库配置包含 `google()` 与 `mavenCentral()`。
- 常规应用接入 `meguviz-ui-widgets` 即可，它会传递依赖 `meguviz-ui-core`（参见 `ui-widgets/build.gradle.kts`）。

## 五步跑通

1. [01 · 添加依赖](quickstart/01-add-dependency.md) —— `meguviz-bom:1.2.0` 与模块坐标选择。
2. [02 · 显示第一屏](quickstart/02-first-screen.md) —— 最小 `Activity`、挂载 `TextView`、绑定属性。
3. [03 · 生命周期与关闭](quickstart/03-lifecycle-and-close.md) —— 主线程约束、`close()`、`detach` / `reattach`。
4. [04 · 状态与命令](quickstart/04-state-and-commands.md) —— `PropertyBinding`、`UiScheduler`、批次与异步提交。
5. [05 · 状态保存与恢复](quickstart/05-restoration.md) —— `ViewRestoration` 与 `Bundle`。

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

## 一行接入（`1.2.0` 推荐）

不想手工装配根视图、会话与作用域时，`Activity.openMeguvizUi()` 可一步完成装配（源码：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/UiScope.kt`）：

```kotlin
class MainActivity : Activity() {
    private lateinit var ui: UiScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = openMeguvizUi()      // 根 FrameLayout + UiSession + FrameScope
        setContentView(ui.root)
        ui.scope.text("title", frameParams()) { text = "Hello" }
            .bind(Properties.plainText()).submit("Updated")
    }
    // 无需手写 onDestroy：Activity 销毁时会自动关闭会话。
}
```

- **幂等**：同一 `Activity` 再次调用返回同一个未关闭实例，在 `onResume` 等重复路径调用也不会叠出第二个会话。
- **误用报错**：非主线程或已销毁的 `Activity` 会直接抛异常，不会返回一个无人释放的会话。
- **无 AndroidX 依赖**：裸 `android.app.Activity` 即可使用。
- **调用方持有根视图**：调用 `setContentView(ui.root)` 后继续通过 `ui.scope` 构建；`ui.close()` 幂等，主动关闭后再次调用 `openMeguvizUi()` 会建立新会话。
- **窗口集成场景**：需要 `WindowSession` 支撑时，不调用 `openMeguvizUi()`，改用 Android 桥的 `AndroidUiBinding.open(window, root)`（参见 [Android 桥](guides/bridges-android.md)）。

> 若工程尚未从 `1.0.0` 升级到 `1.2.0`，旧版没有 `openMeguvizUi()`、`ViewRef.bind(...)` 与 `String` key 重载，需沿用 `session.bind(view, Properties.plainText())` 与 `ViewKey("title")`。升级对照见 [1.0 → 1.2 迁移指南](migration/1.0-to-1.2.md)。

### Java 一行接入

Java 调用方使用静态入口 `UiScopeKt.openMeguvizUi(this)`：

```java
import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;
import io.github.jichuo1.meguviz.ui.ViewRef;
import io.github.jichuo1.meguviz.ui.widgets.Properties;
import io.github.jichuo1.meguviz.ui.widgets.UiScope;
import io.github.jichuo1.meguviz.ui.widgets.UiScopeKt;

public final class MainActivity extends Activity {
    private UiScope ui;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = UiScopeKt.openMeguvizUi(this);
        setContentView(ui.getRoot());
        // Java 端调用 String key 重载挂载控件
        ViewRef<TextView> title = ui.getScope().text("title", new FrameLayout.LayoutParams(-1, -2));
        title.bind(Properties.INSTANCE.plainText()).submit("Updated");
    }
    // Activity 销毁时自动托管释放；若需提前释放可调用 ui.close()（幂等）。
}
```

**Java 必须使用 `String` key 的挂载重载**：`ViewKey` 是 Kotlin value class，以它为参数的挂载方法在 JVM 上会生成带 `-` 的混淆名（如 `text-p42mYIk`），Java 源码无法拼写。`ParentScope` 提供的一组以 `String` 为 key 的 `@JvmOverloads` 重载只在内部包装 `ViewKey` 后委托原方法，具有与 `ViewKey` 版本一致的非空校验且不增加额外对象分配。Java 侧可省略末尾的 `configure` 回调；若传 lambda 需返回 `Unit.INSTANCE`。`integration/compile-fixtures/positive-java/JavaConsumer.java` 已纳入 `javac` 编译门禁以保证该路径长期可用。

直接使用 `UiScope` / `WindowSession` 组合时，必须按[生命周期与关闭](quickstart/03-lifecycle-and-close.md)顺序先关 binding，再关窗口会话。

## 三条关键约定

1. **主线程独占**：`UiSession` 的构造、挂载、属性提交、释放和 `close()` 都必须在 Android 主线程调用，否则抛出 `IllegalStateException("UI operation requires the main thread")`（源码：`ui-core/.../UiSession.kt`）。后台可做纯模型计算或通过 `submitStateAsync` / `submitCommandAsync` 入队，不可在后台直接构造 View。
2. **`session.close()` 必须调用**：未使用 `openMeguvizUi()` 时，必须在宿主销毁时调用 `session.close()` 释放绑定、监听和受管子树。后台线程可调用 `session.requestClose()`，通过返回的 `CloseRequest.completed` 观察 owner 清理是否完成。
3. **状态保存/恢复交给 `ViewRestoration`**：它负责焦点、选择与滚动；业务模型（计数、草稿等）由应用自行保存，不要从 UI 快照恢复文本（源码：`ui-core/.../state/ViewRestoration.kt`）。

## 推荐写法说明

- `ParentScope` 已提供 `text`、`edit`、`button`、`image`、`linear`、`frame` 六个挂载方法（源码：`ui-widgets/.../Widgets.kt`），内部均走 `session.mount`，不建议再封装 `mountText` / `mountEdit` / `mountButton` 之类的重复入口。
- `1.2.0` 中挂载方法的 key 可直接传字符串（如 `ui.scope.text("title", frameParams())`），它与 `ViewKey("title")` 共享同一父容器命名域冲突检查。
- `ViewRef.bind(PropertySpec)`（源码：`ui-core/.../Identity.kt`）是 `session.bind(ref, property)` 的纯委托简写，与原写法享有完全相同的生命周期和所有权检查。
- 常用属性优先复用 `Properties.plainText()`、`richText()`、`textColor()`、`visibility()` 与 `checked()`（源码：`ui-widgets/.../Properties.kt`），无需重复手写完整 `PropertySpec`。

## 常见报错速查

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `UI operation requires the main thread` | 在非主线程调用会话 API | 切回主线程，或使用 `submit*Async` |
| `Session is not active` | 会话已 `close`、`detach` 或调度器 `FAULTED` / `CLOSED` | 检查生命周期与 `session.scheduler.state` |
| `Duplicate key in parent: <key>` | 同一父容器下重复 `ViewKey` | 换 key 或先 `session.release(ref)` |
| `Property already owned: <id>` | 同一 View 上重复绑定同一属性 id | 先 `binding.close()` 或换 id |
| `Conflicting visual property owner` | 与 Lumen / 动画保留的属性冲突 | 用 `session.reserveProperties` 明确所有权 |
| `View reference released` | 引用已随节点/会话释放 | 不要跨释放持有 `ViewRef` |
| `Binding closed` | 绑定已关闭仍提交 | 重新绑定 |

完整异常目录见[异常消息目录](troubleshooting/error-catalog.md)与[核心合同](contracts.md)。

## 下一步

- [示例索引](examples.md)：查看 `samples/` 下的完整示例工程。
- [核心合同](contracts.md)、[调度与帧驱动](guides/scheduler.md)、[属性绑定](guides/properties.md)。
- 可选扩展与桥接见 [可选桥边界](bridges.md) 与 [P2 可选能力](p2.md)。
