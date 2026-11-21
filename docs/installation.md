# 安装与接入

## 通过 Maven Central 消费（推荐）

`1.2.0` 已发布到 Maven Central，groupId 为 `io.github.jichuo1.meguviz`。家族提供统一的 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`，集中对齐 33 个 `1.2.0` 制品（其中 MeguvizUI 占 12 个）。

常规应用只需引入 BOM 与 `meguviz-ui-widgets`（传递依赖 `meguviz-ui-core`），不需要额外引入 Ref、Android 窗口运行时或 Lumen：

```kotlin
// 消费模块的 build.gradle.kts
dependencies {
    val meguvizBom = platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0")
    implementation(meguvizBom)
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets")

    // 若启用 KSP 代码生成，compileOnly 与 ksp 必须各自显式引入 BOM：
    // compileOnly(meguvizBom)
    // ksp(meguvizBom)
    // compileOnly("io.github.jichuo1.meguviz:meguviz-ui-codegen-api")
    // ksp("io.github.jichuo1.meguviz:meguviz-ui-codegen")
}
```

### 已发布与未发布制品边界

- **已在 Maven Central 发布的 12 个 `1.2.0` 制品**：
  - 核心与控件：`meguviz-ui-core`、`meguviz-ui-widgets`
  - 可选 P2 扩展：`meguviz-ui-plan`、`meguviz-ui-codegen-api`、`meguviz-ui-codegen`、`meguviz-ui-recycler`、`meguviz-ui-text`、`meguviz-ui-layouts`、`meguviz-ui-prewarm`、`meguviz-ui-frame-shared`
  - 可选桥接：`meguviz-ui-ref-bridge`（依赖已上 Central 的 `meguviz-ref-core:1.2.0`）、`meguviz-ui-android-bridge`（依赖已上 Central 的 `meguviz-android-runtime:1.2.0` 与 `meguviz-android-window:1.2.0`）
- **尚未发布到 Maven Central 的唯一例外**：
  - `meguviz-ui-lumen-bridge` 暂未发布到 Maven Central，仅支持从源码构建启用 `-PenableLumen=true` 并通过本地候选仓库消费（参见 [Lumen 桥接指南](guides/bridges-lumen.md)）。
- 已发布版本遵循不可变原则，不会以不同内容覆盖同一版本号。

### 消费方编译器与 SDK 要求

- **Android SDK**：`compileSdk 37` 或更高，声明最低 `minSdk 27`，Java 字节码目标 `17`（JDK `17`）。
- **Kotlin 版本**：`1.2.0` 模块以 Kotlin `2.4.21` 编译，类文件内的 Kotlin 元数据版本为 `2.4.0`。Kotlin `2.2` 编译器无法读取 `2.4.0` 元数据；按 Kotlin「高一个大次版本」兼容规则，消费工程至少需要 **Kotlin `2.3+`**（源码及示例统一在 Kotlin `2.4.21` 下验证）。
- **KSP 版本**：使用 `meguviz-ui-codegen` 时，仓库验证的 KSP 插件版本为 `2.3.12`。

## 从源码构建与本地 Maven 消费

当需要修改库源码或使用未上 Central 的 `ui-lumen-bridge` 时，可在本地构建并发布到 `build/repo/`。

### 源码构建工具链

- JDK `17`、Android SDK `37`、Build Tools `37.0.0`
- Gradle Wrapper 固定为 `9.8.2`，AGP 为 `9.4.1`，Kotlin 编译器为 `2.4.21`（Android 模块使用 AGP 内置 Kotlin）
- 在仓库根目录的 `local.properties` 中配置本机 `sdk.dir`（不要提交此文件）

执行主机校验并发布到本地仓库：

```sh
./gradlew verifyHost publishCandidate --no-daemon --max-workers=2
```

如需一并打包 `ui-lumen-bridge`，追加 `-PenableLumen=true`（需能访问 JitPack 解析 `lumen-engine`）。

### 消费本地候选仓库

构建生成的 Maven 仓库位于源码根目录的 `build/repo/`，在消费工程指向该路径即可：

```kotlin
// 消费项目的 settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("meguvizUiRepository").get()) }
        google()
        mavenCentral()
    }
}
```

将 `meguvizUiRepository` 配置为本地 `build/repo/` 目录路径，依赖写法与 Central 一致。

## 示例与主题

- [standalone](../samples/standalone) 展示 Framework 控件、输入模型与 `ViewRestoration` 状态恢复。
- [xml-interop](../samples/xml-interop) 展示 AppCompat、`Factory2` 与 `ContextThemeWrapper` 主题（AppCompat 依赖仅属于该示例）。

构造或初始化时传入 `Activity` 或对应的 themed `Context`，应用负责窗口和原生 root 的生命周期。自定义 View 应传入准确的直接工厂；Framework、AppCompat、Material 控件不能按类名互换。各可选桥的配置细节见 [可选桥边界](bridges.md)。

[返回文档目录](README.md)
