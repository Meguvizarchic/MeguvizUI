<div align="center">
<img src="docs/brand/MeguvizUI.svg" width="96" height="96" alt="MeguvizUI">

# MeguvizUI

**面向 Android 原生 View 的强类型 Kotlin UI 库**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz/meguviz-ui-widgets) · [属性绑定](docs/guides/properties.md) · [示例](samples)

</div>

MeguvizUI 用 Kotlin 创建和管理 Android 原生 View，也能绑定 XML 加载的现有 View。布局作用域约束父容器与布局参数，属性绑定负责更新控件，`UiSession` 管理引用和监听的生命周期。

## 快速开始

在 Gradle 的仓库配置中启用 `google()` 和 `mavenCentral()`。Android 项目使用 `compileSdk` 37 或更新版本，设备最低 API 27。示例使用 Kotlin 2.4.21。常规界面使用 `meguviz-ui-widgets`，它会引入核心库。

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets:1.2.0")
}
```

```kotlin
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams

val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
val title = FrameScope(session, root).text(ViewKey("title"), frameParams()) {
    text = "Ready"
}
session.bind(title, Properties.plainText()).submit("Updated")
activity.setContentView(root)
```

在主线程创建界面和操作会话。所有者销毁时调用 `session.close()`。属性可以显式合并状态更新，命令则按顺序执行。

## 按需添加能力

| 需求 | Maven 制品 |
| --- | --- |
| 生命周期、属性绑定与调度 | `meguviz-ui-core` |
| 原生控件与类型化布局 | `meguviz-ui-widgets`、`meguviz-ui-layouts` |
| 界面计划与 KSP 代码生成 | `meguviz-ui-plan`、`meguviz-ui-codegen-api`、`meguviz-ui-codegen` |
| 列表、文本预计算与控件预热 | `meguviz-ui-recycler`、`meguviz-ui-text`、`meguviz-ui-prewarm` |
| 会话共享帧驱动 | `meguviz-ui-frame-shared` |
| 与 MeguvizRef 或 MeguvizAndroid 集成 | `meguviz-ui-ref-bridge`、`meguviz-ui-android-bridge` |

上表制品使用 groupId `io.github.jichuo1.meguviz`。Lumen 接入可参考 [桥接模块源码](ui-lumen-bridge)。

## 文档

- [独立界面示例](samples/standalone) · [XML 示例](samples/xml-interop)
- [状态与命令](docs/quickstart/04-state-and-commands.md) · [生命周期](docs/quickstart/03-lifecycle-and-close.md)
- [Apache License 2.0](LICENSE) · [版权与第三方声明](NOTICE)
