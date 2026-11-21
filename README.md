<div align="center">
<img src="docs/brand/MeguvizUI.svg" width="96" height="96" alt="MeguvizUI">

# MeguvizUI

**面向 Android 原生 View 的强类型 Kotlin UI 库**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz/meguviz-ui-widgets) · [AI Skill 接入指南](.agents/skills/meguviz-ui/SKILL.md) · [文档目录](docs/README.md) · [示例](samples)

</div>

MeguvizUI 用 Kotlin 创建和管理 Android 原生 View，也能绑定 XML 加载的现有 View。布局作用域约束父容器与布局参数，属性绑定负责更新控件，`UiSession` 管理引用和监听的生命周期。

## 快速开始

在 Gradle 仓库配置中启用 `google()` 与 `mavenCentral()`。Android 项目使用 `compileSdk 37` 或更新版本，最低支持 API `27`。源码与示例使用 Kotlin `2.4.21`（消费工程至少需 Kotlin `2.3+`）及 JDK `17`。

推荐通过家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 对齐版本，常规界面引入 `meguviz-ui-widgets`（会自动传递依赖 `meguviz-ui-core`）：

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")
}
```

```kotlin
import android.app.Activity
import android.os.Bundle
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.openMeguvizUi

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = openMeguvizUi() // 创建根 FrameLayout、UiSession 与 FrameScope，随 Activity 销毁关闭
        setContentView(ui.root)

        val title = ui.scope.text("title", frameParams()) { text = "Ready" }
        title.bind(Properties.plainText()).submit("Updated")
    }
}
```

界面与会话操作均在主线程执行。不使用 `openMeguvizUi()` 时，请在所有者销毁时调用 `session.close()`。状态属性按槽合并最新值，命令则按 FIFO 顺序保留每次执行。

## 按需添加能力（Maven Central `1.2.0`）

以下 12 个 `1.2.0` 制品（groupId 均为 `io.github.jichuo1.meguviz`）已发布到 Maven Central，并由 `meguviz-bom:1.2.0` 统一管理版本：

| 需求 | Maven 制品 |
| --- | --- |
| 生命周期、属性绑定与调度 | `meguviz-ui-core` |
| 原生控件与类型化布局 | `meguviz-ui-widgets`、`meguviz-ui-layouts` |
| 界面计划与 KSP 代码生成 | `meguviz-ui-plan`、`meguviz-ui-codegen-api`、`meguviz-ui-codegen` |
| 列表、文本预计算与控件预热 | `meguviz-ui-recycler`、`meguviz-ui-text`、`meguviz-ui-prewarm` |
| 多会话共享帧驱动 | `meguviz-ui-frame-shared` |
| 与 MeguvizRef 或 MeguvizAndroid 集成 | `meguviz-ui-ref-bridge`、`meguviz-ui-android-bridge` |

- 使用 KSP 代码生成时，必须在 `compileOnly` 与 `ksp` 配置同时声明 `platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")`（参见 [依赖配置说明](docs/quickstart/01-add-dependency.md)）。
- **未上 Central 例外**：`meguviz-ui-lumen-bridge` 尚未发布到 Maven Central，仅支持从 [Lumen 桥接模块源码](ui-lumen-bridge) 启用 `-PenableLumen=true` 本地构建（参见 [Lumen 桥接指南](docs/guides/bridges-lumen.md)）。

## 文档与 AI Skill

- **AI Agent 接入**：[MeguvizUI 接入与迁移 Skill](.agents/skills/meguviz-ui/SKILL.md)
- **消费者上手**：[快速上手](docs/getting-started.md) · [安装与接入](docs/installation.md) · [状态与命令](docs/quickstart/04-state-and-commands.md) · [生命周期](docs/quickstart/03-lifecycle-and-close.md)
- **示例工程**：[独立界面示例](samples/standalone) · [XML 互操作示例](samples/xml-interop) · [全部示例索引](docs/examples.md)
- **进阶与迁移**：[1.0 → 1.2 迁移指南](docs/migration/1.0-to-1.2.md) · [可选桥接边界](docs/bridges.md) · [P2 可选能力](docs/p2.md)
- **许可声明**：[Apache License 2.0](LICENSE) · [版权与第三方声明](NOTICE)
