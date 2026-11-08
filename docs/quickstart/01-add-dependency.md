# 01 · 添加依赖

## 版本选择

- **已发布版本：`1.0.0`**，位于 Maven Central，groupId 为 `io.github.jichuo1.meguviz`（见 [安装与接入](../installation.md)）。
- **源码版本：`1.2.0`**（`gradle.properties` 中 `meguviz.version=1.2.0`），尚未发布到 Maven Central；只能通过源码构建出的本地候选仓库 `build/repo/` 消费。
- `meguviz-ui-lumen-bridge` 与 `meguviz-ui-android-bridge` 的上游尚未发布到 Maven Central，因此这两个桥**不在 Central 提供**，只能用源码构建并显式启用（`-PenableLumen=true` / `-PenableAndroid=true`）。

写依赖时请确认你引用的是 `1.0.0`（已发布）还是本地候选；不要写 `1.2.0` 到 Maven Central 的坐标上。
跨库联调可改用家族本地 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`（`/Volumes/DevEnvironment/meguviz-joint/local-maven`，
未发布、未签名），用 `implementation(platform(...))` 后家族坐标可省略版本号；注入方式见
`meguviz-joint/tools/meguviz-bom.init.gradle.kts`。该 BOM 只覆盖本地确有字节的 6 个 1.2.0 制品。

## 普通应用：Maven Central

```kotlin
// 消费模块的 build.gradle.kts
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets:1.0.0")
}
```

`meguviz-ui-widgets` 通过 `api(project(":ui-core"))` 传递依赖核心（见 `ui-widgets/build.gradle.kts`），所以不需要单独写 `meguviz-ui-core`。

已发布的 artifactId：

| artifactId | 用途 |
| --- | --- |
| `meguviz-ui-core` | 会话、引用、属性、调度、恢复与扩展合同 |
| `meguviz-ui-widgets` | Framework 工厂、父布局作用域、尺寸与常用属性 |
| `meguviz-ui-ref-bridge` | 对接真实 Ref 构造与调用计划 |
| `meguviz-ui-plan` | 纯数据计划（P2） |
| `meguviz-ui-recycler` | 可选 RecyclerView 绑定（P2） |
| `meguviz-ui-text` | 长文本预计算（P2） |
| `meguviz-ui-layouts` | 受限原生横向行（P2） |
| `meguviz-ui-prewarm` | 可取消的有界主线程预热（P2） |
| `meguviz-ui-codegen-api` / `meguviz-ui-codegen` | KSP 生成器声明与处理器（P2） |

可选 P2 模块按需添加，例如：

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets:1.0.0")
    implementation("io.github.jichuo1.meguviz:meguviz-ui-recycler:1.0.0")
    implementation("io.github.jichuo1.meguviz:meguviz-ui-text:1.0.0")
}
```

P2 模块默认关闭，启用方式与边界见 [P2 可选能力](../p2.md)。

## 本地候选仓库

用于验证未发布的源码改动。先在源码根目录执行：

```sh
./gradlew verifyHost publishCandidate --no-daemon --max-workers=2
```

生成的 Maven 仓库位于源码根目录的 `build/repo/`。消费项目需显式指向自己的实际目录：

```kotlin
// 消费项目的 settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        // getOrElse: 未传 -P 时不会因 Provider 缺失而抛异常，直接回退到源码构建产物目录。
        maven { url = uri(providers.gradleProperty("meguvizUiRepository")
            .getOrElse(file("../MeguvizUI/build/repo").absolutePath)) }
        google()
        mavenCentral()
    }
}
```

把 `meguvizUiRepository` 设置为 `build/repo` 的路径，依赖写法与 Central 相同（本地候选的版本号是 `1.2.0`）。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-widgets:1.0.0` | 仓库里没有 `mavenCentral()`，或版本号写错 | 确认 `repositories { mavenCentral() }` 与版本 `1.0.0` |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-widgets:1.2.0` | `1.2.0` 尚未发布到 Central | 消费 `1.0.0`，或改用本地候选仓库 |
| `Could not find io.github.jichuo1.meguviz:meguviz-ui-lumen-bridge:...` | 两个桥未发布到 Central | 从源码构建并启用对应 Gradle 属性 |
| `uses-sdk:minSdkVersion X cannot be smaller than version 27 declared in library` | 应用 `minSdk` 低于库声明 | 把 `minSdk` 提到 27 或以上 |
| `Compilation with Java 17` / `class file has wrong version 61` | 消费端 JDK 低于 17 | 使用 JDK 17 编译 |

## 下一步

[02 · 显示第一屏](02-first-screen.md)
