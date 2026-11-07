# 示例索引

`samples/` 下的样例分为三类：可直接运行的 App、独立 Gradle root 的制品消费者、以及性能/设备验证工程。以下路径均相对仓库根目录。

## 可直接运行的 App

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| standalone | [`samples/standalone`](../samples/standalone) | 计数器命令、草稿输入模型、`ViewRestoration` 表现恢复、`session.click`、`onRelease` 配对清理。**快速上手首选**，对应 [05 · 状态保存与恢复](quickstart/05-restoration.md) 的完整实现。 |
| xml-interop | [`samples/xml-interop`](../samples/xml-interop) | AppCompat `Factory2`、`ContextThemeWrapper` 主题、`session.inflate` 与 `session.borrow` 已有 View。展示 XML 与直接 DSL 共存。 |
| plan-consumer | [`samples/plan-consumer`](../samples/plan-consumer) | `UiPlan` 分期验证、解析、绑定、挂载；`mounted.ref(listOf("title"), Framework.text)` 按路径与 `ComponentType` 取引用。对应 P2 的 `ui-plan`。 |
| generated-consumer | [`samples/generated-consumer`](../samples/generated-consumer) | `@GenerateUiFactory` KSP 生成直接工厂、typed ref 与 `registration()`；展示 registry 解析。对应 P2 的 `ui-codegen`。 |

构建命令：

```sh
# standalone / xml-interop 已包含在默认 settings
./gradlew :samples:standalone:assembleDebug
./gradlew :samples:xml-interop:assembleDebug

# plan-consumer / generated-consumer 需要显式启用对应 P2 开关
./gradlew -PenablePlan=true :samples:plan-consumer:assembleDebug
./gradlew -PenableCodegen=true :samples:generated-consumer:assembleDebug
```

这些样例直接依赖 `project(":ui-widgets")` 等源码模块，用于验证库本身。

## 独立制品消费者

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| p2-consumer | [`samples/p2-consumer`](../samples/p2-consumer) | 独立 Gradle root，只从根工程 `build/repo` 读取已发布 Maven 制品，不引用 `project()` 输出。同时使用 widgets、`UiPlan`、`UiListAdapter`、`PrecomputedTextBinding`、`nativeRow`、`UiPrewarmer`、KSP 生成器与 `SharedFrameDriver`；release 构建启用 R8 与资源压缩。 |

使用步骤（见该目录 `README.md`）：

```sh
# 1. 在根工程启用完整 P2 并发布本地候选
./gradlew -PenableP2=true verifyP2 publishCandidate --no-daemon --max-workers=2

# 2. 准备独立消费者所需制品与校验元数据
python samples/p2-consumer/prepare_artifacts.py

# 3. 在独立 root 构建
./gradlew -p samples/p2-consumer :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --max-workers=2
```

构建通过只证明独立制品接入与 R8 编译，不代表设备交互或性能结论。

## 性能与设备验证工程

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| p2-benchmark | [`samples/p2-benchmark`](../samples/p2-benchmark) | P2 模块同输入 A/B 基准宿主；保持前台窗口以持续获得平台帧。准入准则与测量方式见 [P2 可选能力](p2.md)。 |
| p2-matrix | [`samples/p2-matrix`](../samples/p2-matrix) | 设备行为矩阵宿主，覆盖旋转/重建恢复、进程死亡恢复、无障碍节点树、字号/RTL/深色模式、列表突发提交、文本预计算等。 |

运行方式（需要物理机与 adb，详见 [P2 可选能力](p2.md)）：

```sh
./gradlew -PenableP2=true verifyP2Performance -Pp2PerformanceSerial=<adb serial> -PallowDeviceInstall=true --no-daemon --max-workers=2
./gradlew -PenableP2=true verifyP2Matrix -Pp2MatrixSerial=<adb serial> -PallowDeviceInstall=true --no-daemon --max-workers=2
```

这些工程只在显式启用 P2 时纳入构建，不接入默认核心构建；模拟器测量不构成性能准入证据。

## 编译期类型案例

[`integration/compile-fixtures`](../integration/compile-fixtures) 提供可编译的正负类型案例，覆盖 `ParentScope`、`PropertySpec`、`RegistrySnapshot` 等公共入口的编译期约束，适合作为 API 用法的补充参考。

## 相关文档

- [消费者快速上手](getting-started.md) · [quickstart](quickstart/01-add-dependency.md)
- [安装与接入](installation.md) · [核心合同](contracts.md) · [可选桥](bridges.md) · [P2 可选能力](p2.md)
