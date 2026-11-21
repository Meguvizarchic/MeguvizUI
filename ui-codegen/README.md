# KSP 生成器（可选）

`meguviz-ui-codegen-api:1.2.0` 与 `meguviz-ui-codegen:1.2.0` 已发布到 Maven Central（包含在 `meguviz-bom:1.2.0`），源码构建时通过 `-PenableCodegen=true` 启用。

## 能力与依赖配置

`ui-codegen-api` 提供编译期源码注解 `@GenerateUiFactory(id, schema)`；`ui-codegen` 是 KSP 符号处理器。使用 `meguviz-bom:1.2.0` 时，`ksp` 与 `compileOnly` 配置不会自动继承 `implementation(platform(...))`，必须同时传入 BOM 平台：

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

## 生成规则与约束

- 仅当目标类为 `public`、顶层、非泛型、非抽象 `View` 子类，且主构造器只有单个非空 `android.content.Context` 参数时，才会生成 `*UiGenerated` 工厂对象、`*ViewRef` 类型别名和 `registration()` 扩展注册入口。
- 生成代码的 ABI major 为 `1`；组件 schema 由注解显式指定。生成代码直接调用构造器和类型引用，无需额外的反射混淆规则。
- 处理器以单个源文件为增量输入，仅检查声明与类型，不分析任意函数体；普通 `core` / `widgets` 消费方不依赖 KSP。完整用例见 `samples/generated-consumer`（生成产物位于其 `build/generated/ksp/`）。

## 验证命令

```sh
./gradlew -PenableCodegen=true :samples:generated-consumer:assembleDebug :samples:generated-consumer:assembleRelease :samples:generated-consumer:assembleDebugAndroidTest :samples:generated-consumer:lintDebug
python ui-codegen/verification/verify_codegen.py --workspace build/p2-codegen-validation/first
```

验证脚本在隔离目录检查首次严格校验构建、注解 id/schema 增量变更与非法构造器报错。本仓库基线使用 KSP `2.3.12`、Kotlin `2.4.21`、AGP `9.4.1` 与 Gradle `9.8.2`；生产工程的构建时长、体积与 R8 运行表现需在目标项目内实际测量。
