# 01 · 添加依赖

## 版本与 BOM（`1.2.0`）

- **Maven Central 已发布版本**：`1.2.0`（groupId 为 `io.github.jichuo1.meguviz`）。
- **家族 BOM**：`io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 已同步发布到 Maven Central，集中对齐 MeguvizUI 的 12 个已发布 `1.2.0` 制品及家族其他库。
- **未上 Central 的唯一例外**：`meguviz-ui-lumen-bridge` 尚未发布到 Maven Central，仅可源码构建启用 `-PenableLumen=true` 并通过本地 Maven 消费（参见 [Lumen 桥接指南](../guides/bridges-lumen.md)）。

> 环境底线：`compileSdk 37+`、`minSdk 27`、JDK `17`、Kotlin `2.3+`（库源码以 `2.4.21` 编译）。

## 常规应用：通过 BOM 接入 Maven Central

在消费模块的 `build.gradle.kts` 中启用 `google()` 与 `mavenCentral()` 后引入 BOM 与 `meguviz-ui-widgets`（它通过 `api` 传递依赖 `meguviz-ui-core`）：

```kotlin
dependencies {
    val meguvizBom = platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")
    implementation(meguvizBom)
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")
}
```

如果不使用 BOM，也可以在每条依赖末尾显式写出 `:1.2.0` 版本号。

### 已发布到 Maven Central 的 12 个制品

| artifactId | 用途 |
| --- | --- |
| `meguviz-ui-core` | 会话、引用、属性绑定、调度、状态恢复与扩展注册表 |
| `meguviz-ui-widgets` | `openMeguvizUi()`、Framework 工厂、父布局作用域、尺寸与常用 `Properties` |
| `meguviz-ui-plan` | 纯数据计划（`UiPlan`） |
| `meguviz-ui-codegen-api` / `meguviz-ui-codegen` | KSP 注解声明与代码生成处理器 |
| `meguviz-ui-recycler` | 可选 `RecyclerView` 后台有界 Diff 列表适配 |
| `meguviz-ui-text` | 长纯文本异步预计算 |
| `meguviz-ui-layouts` | 受限原生横向行 `nativeRow` |
| `meguviz-ui-prewarm` | 可取消的有界主线程分帧预热 |
| `meguviz-ui-frame-shared` | 多会话共享帧驱动 `SharedFrameDriver` |
| `meguviz-ui-ref-bridge` | 对接真实 `MeguvizRef` `CodeScope` |
| `meguviz-ui-android-bridge` | 对接真实 `MeguvizAndroid` `WindowSession` 与资源权威 |

### 使用 `meguviz-ui-codegen` 的 BOM 必填提醒

Gradle 的 `ksp` 和 `compileOnly` 配置不会自动继承 `implementation(platform(...))`。如果启用 KSP 工厂生成，必须在 `compileOnly` 与 `ksp` 中同时传入 BOM 平台（或直接写出 `:1.2.0`）：

```kotlin
dependencies {
    val meguvizBom = platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")
    implementation(meguvizBom)
    compileOnly(meguvizBom)
    ksp(meguvizBom)

    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")
    compileOnly("io.github.jichuo1.meguviz:meguviz-ui-codegen-api")
    ksp("io.github.jichuo1.meguviz:meguviz-ui-codegen")
}
```

不需要 Ref、Android 窗口运行时或 Lumen 时，保持无桥独立引入即可，无需提前添加未使用的可选模块。

## 本地候选仓库（用于验证本地修改或 `ui-lumen-bridge`）

在源码根目录构建并发布到本地 Maven 目录 `build/repo/`：

```sh
./gradlew verifyHost publishCandidate --no-daemon --max-workers=2
# 若需一并构建未上 Central 的 Lumen 桥，追加 -PenableLumen=true
```

在消费工程的 `settings.gradle.kts` 指向该本地目录：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri(
                providers.gradleProperty("meguvizUiRepository")
                    .getOrElse(file("../MeguvizUI/build/repo").absolutePath),
            )
        }
        google()
        mavenCentral()
    }
}
```

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-widgets:1.2.0` | 仓库未配置 `mavenCentral()` | 在 `repositories` 添加 `mavenCentral()` 与 `google()` |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-codegen:` | `ksp(...)` 未传入 BOM 或版本 | 在 `ksp(...)` 与 `compileOnly(...)` 同处添加 `platform(bom)` |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-lumen-bridge` | Lumen 桥未发布到 Central | 从源码仓库执行 `-PenableLumen=true publishCandidate` 后走本地消费 |
| `uses-sdk:minSdkVersion X cannot be smaller than version 27` | 应用 `minSdk` 低于 `27` | 将消费模块 `minSdk` 提升至 `27` 及以上 |
| `Metadata version 2.4.0 ...` / `class file has wrong version 61` | Kotlin < `2.3` 或 JDK < `17` | 升级至 JDK `17` 与 Kotlin `2.3+`（推荐 `2.4.21`） |

## 下一步

[02 · 显示第一屏](02-first-screen.md)
