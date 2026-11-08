# 02 · 显示第一屏

## 目标

创建一个 `FrameLayout` 根容器，挂载一个 `TextView`，并提交一次状态更新。

## 最小 Activity

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

        // 挂载一个 TextView。
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

## 每一步在做什么

| 代码 | 作用 | 源码 |
| --- | --- | --- |
| `CreationEnvironment(this)` | 保留 Activity 的 themed Context，不换成 `applicationContext` | `ui-core/.../Identity.kt` |
| `UiSession(environment)` | 创建会话；默认帧驱动是 `ChoreographerFrameDriver()` | `ui-core/.../UiSession.kt` |
| `ViewKey("title")` | 父容器内的稳定 key，重复会拒绝 | `ui-core/.../Identity.kt` |
| `FrameScope(session, root)` | 绑定 `FrameLayout` 父容器与 `FramePolicy` | `ui-widgets/.../Widgets.kt` |
| `text(key, params) { ... }` | 挂载 `TextView`，返回 `ViewRef<TextView>` | `ui-widgets/.../Widgets.kt` |
| `frameParams()` | 生成 `FrameLayout.LayoutParams`（默认 `MATCH_PARENT` × `WRAP_CONTENT`） | `ui-widgets/.../Widgets.kt` |
| `title.bind(Properties.plainText())` | `session.bind(title, spec)` 的流式简写 | `ui-core/.../Identity.kt` |
| `.submit("Updated")` | 提交一次状态；返回 `SubmitResult` | `ui-core/.../Properties.kt` |

`Properties.plainText()` 只适合**没有编辑协议副作用的不可变标签**（源码注释：`ui-widgets/.../Properties.kt`）。输入框、IME、过滤器请交给业务控制器。

## 推荐写法：作用域 DSL

`ParentScope` 已经提供六个简洁方法，内部统一走 `session.mount`，**这就是推荐写法**（源码：`ui-widgets/.../Widgets.kt`）：

| 方法 | 挂载类型 | 说明 |
| --- | --- | --- |
| `text(key, params) { }` | `TextView` | 纯文本、颜色等标签 |
| `edit(key, params) { }` | `EditText` | 输入由业务控制器管理 |
| `button(key, params) { }` | `Button` | 配合 `session.click` |
| `image(key, params) { }` | `ImageView` | 图片 |
| `linear(key, params, configure) { children }` | `LinearLayout` | 同时挂载子树 |
| `frame(key, params, configure) { children }` | `FrameLayout` | 同时挂载子树 |

**不要**再包一层 `mountText` / `mountEdit` / `mountButton`：这些方法已经覆盖了 Framework 控件，冗余入口只会增加维护面。

示例：在 `FrameScope` 里放一个竖向 `LinearLayout`，再在 `LinearScope` 里放行内容：

```kotlin
val column = FrameScope(session, root).linear(
    ViewKey("column"),
    frameParams(),
    configure = { orientation = LinearLayout.VERTICAL },
) {
    text(ViewKey("row1"), linearParams()) { text = "Row 1" }
    button(ViewKey("action"), linearParams()) { setText("Action") }
}
```

外层 `FrameScope` 的 `LP` 是 `FrameLayout.LayoutParams`，所以用 `frameParams()`；内层 `LinearScope` 的 `LP` 是 `LinearLayout.LayoutParams`，所以用 `linearParams()`。类型不匹配会在编译期报错，这是库的设计意图。

## 自定义工厂：`view`

需要自定义 `View` 或 AppCompat/Material 控件时，用 `ParentScope.view` 直接传 `ComponentType` 与 `ViewFactory`：

```kotlin
val label = FrameScope(session, root).view(
    ViewKey("custom"),
    ComponentType("custom.label", TextView::class.java),
    frameParams(),
    ViewFactory { env -> TextView(env.context) },
) { text = "Custom" }
```

预置工厂见 `Framework`（`ui-widgets/.../Widgets.kt`）：`textFactory` / `editFactory` / `buttonFactory` / `imageFactory` / `linearFactory` / `frameFactory` / `scrollFactory`。Framework、AppCompat、Material 控件**不能按名称互换**，必须使用各自准确的直接工厂（见 [安装与接入](../installation.md)）。

## 尺寸值

```kotlin
val padding = Dp(24f).pixels(context)   // Int，随 displayMetrics 换算
val textSize = Sp(16f).pixels(context)  // Float
```

源码：`ui-widgets/.../Widgets.kt`。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Duplicate key in parent: <key>` | 同一父容器下重复 `ViewKey` | 换 key，或先 `session.release(ref)` 再挂载 |
| `Duplicate root key: <key>` | 无父容器的根节点 key 重复 | 换 key |
| `Factory type differs from component` | 工厂返回类型与 `ComponentType.viewClass` 不一致 | 修正工厂或 `ComponentType` |
| `Factory returned an already parented View` | 工厂返回的 View 已有 parent | 工厂只创建，不挂载 |
| `View already managed` | 同一 View 重复登记 | 每个原生 View 只能有一个 `ViewRef` |
| `UI operation requires the main thread` | 在非主线程创建会话或挂载 | 切回主线程 |
| `Session is not active` | 会话已 close / detach / FAULTED | 检查生命周期与 `session.scheduler.state` |
| `Session disposed` | 会话已 `close()` 后访问 `session.environment` | 不要复用已关闭会话 |
| `Managed <merge> requires an explicit ownership protocol` | `session.inflate` inflate 了 `<merge>` 根布局 | 换成显式所有权协议或普通根布局 |

## 下一步

[03 · 生命周期与关闭](03-lifecycle-and-close.md)
