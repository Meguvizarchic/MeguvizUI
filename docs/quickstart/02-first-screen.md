# 02 · 显示第一屏

## 目标

创建一个 `FrameLayout` 根容器，挂载一个 `TextView`，并绑定属性提交一次状态更新。

## 最小 `Activity`

### 方式 A：一行接入（普通 `Activity` 推荐）

```kotlin
package com.example.demo

import android.app.Activity
import android.os.Bundle
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.openMeguvizUi

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = openMeguvizUi()
        setContentView(ui.root)

        val title = ui.scope.text("title", frameParams()) {
            text = "Hello, MeguvizUI"
        }
        title.bind(Properties.plainText()).submit("Updated")
    }
    // openMeguvizUi() 自动随 Activity 销毁关闭会话
}
```

### 方式 B：显式构造 `UiSession`（适用自定义宿主/Fragment）

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

        // 保留实际 themed Context；必须在主线程创建。
        session = UiSession(CreationEnvironment(this))

        // 用作用域 DSL 挂载一个 TextView。
        val title = FrameScope(session, root).text(ViewKey("title"), frameParams()) {
            text = "Hello, MeguvizUI"
        }

        // 绑定不可变纯文本属性并提交状态。
        title.bind(Properties.plainText()).submit("Updated")
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}
```

## 每一步的作用

| 代码 | 作用 | 源码 |
| --- | --- | --- |
| `openMeguvizUi()` | 一次创建 root、`UiSession` 与 `FrameScope`，销毁自动关闭 | `ui-widgets/.../UiScope.kt` |
| `CreationEnvironment(this)` | 保留 `Activity` 的 themed `Context`，不替换为 `applicationContext` | `ui-core/.../Identity.kt` |
| `UiSession(environment)` | 创建会话；默认帧驱动为 `ChoreographerFrameDriver()` | `ui-core/.../UiSession.kt` |
| `"title"` / `ViewKey("title")` | 父容器内的稳定 key，同父重复会拒绝；Java 使用字符串重载 | `ui-core/.../Identity.kt` |
| `FrameScope(session, root)` | 绑定 `FrameLayout` 父容器与 `FramePolicy` | `ui-widgets/.../Widgets.kt` |
| `text(key, params) { ... }` | 挂载 `TextView`，返回 `ViewRef<TextView>` | `ui-widgets/.../Widgets.kt` |
| `frameParams()` | 生成 `FrameLayout.LayoutParams`（默认 `MATCH_PARENT` × `WRAP_CONTENT`） | `ui-widgets/.../Widgets.kt` |
| `title.bind(Properties.plainText())` | `session.bind(title, spec)` 的流式简写 | `ui-core/.../Identity.kt` |
| `.submit("Updated")` | 提交一次状态；返回 `SubmitResult` | `ui-core/.../Properties.kt` |

`Properties.plainText()` 只适合**没有编辑协议副作用的不可变纯标签**（源码：`ui-widgets/.../Properties.kt`）。输入框、IME 与过滤器请交由业务控制器管理。

## 推荐写法：作用域 DSL

`ParentScope` 提供六个标准挂载方法（内部统一调用 `session.mount`），不建议在其外层再包一层 `mountText` / `mountEdit` / `mountButton`：

| 方法 | 挂载类型 | 说明 |
| --- | --- | --- |
| `text(key, params) { }` | `TextView` | 纯文本、颜色等标签 |
| `edit(key, params) { }` | `EditText` | 输入由业务控制器管理 |
| `button(key, params) { }` | `Button` | 配合 `session.click` |
| `image(key, params) { }` | `ImageView` | 图片控件 |
| `linear(key, params, configure) { children }` | `LinearLayout` | 同时挂载子作用域 |
| `frame(key, params, configure) { children }` | `FrameLayout` | 同时挂载子作用域 |

嵌套示例：在 `FrameScope` 内放置竖向 `LinearLayout`，再在 `LinearScope` 内放置子控件：

```kotlin
val column = FrameScope(session, root).linear(
    "column",
    frameParams(),
    configure = { orientation = LinearLayout.VERTICAL },
) {
    text("row1", linearParams()) { text = "Row 1" }
    button("action", linearParams()) { setText("Action") }
}
```

外层 `FrameScope` 接受 `frameParams()`，内层 `LinearScope` 接受 `linearParams()`；如果错传参数类型，编译器会在编译期直接报错。

## 自定义工厂：`view`

使用自定义 `View` 或 AppCompat / Material 控件时，通过 `ParentScope.view` 显式传入 `ComponentType` 与 `ViewFactory`：

```kotlin
val label = FrameScope(session, root).view(
    "custom",
    ComponentType("custom.label", TextView::class.java),
    frameParams(),
    ViewFactory { env -> TextView(env.context) },
) { text = "Custom" }
```

Framework、AppCompat、Material 控件**不能按名称互换**，各自使用对应的准确直接工厂。

## 尺寸值

```kotlin
val padding = Dp(24f).pixels(context)   // Int，随 displayMetrics 换算
val textSize = Sp(16f).pixels(context)  // Float
```

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Duplicate key in parent: <key>` | 同一父容器下重复 `ViewKey` | 换 key，或先 `session.release(ref)` 再挂载 |
| `Duplicate root key: <key>` | 无父容器的根节点 key 重复 | 换 key |
| `Factory type differs from component` | 工厂返回类型与 `ComponentType.viewClass` 不一致 | 修正工厂或 `ComponentType` |
| `Factory returned an already parented View` | 工厂返回的 View 已有 parent | 工厂只负责创建，不要自己 `addView` |
| `View already managed` | 同一 View 实例重复登记 | 每个原生 View 只对应一个 `ViewRef` |
| `UI operation requires the main thread` | 在非主线程创建会话或挂载 | 切回主线程 |
| `Session is not active` | 会话已 `close` / `detach` / `FAULTED` | 检查生命周期与 `session.scheduler.state` |
| `Session disposed` | 会话已 `close()` 后访问 `session.environment` | 不要复用已关闭会话 |
| `Managed <merge> requires an explicit ownership protocol` | `session.inflate` 加载了 `<merge>` 根布局 | 改用具体根布局，或逐个挂载子节点 |

## 下一步

[03 · 生命周期与关闭](03-lifecycle-and-close.md)
