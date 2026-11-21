# 示例索引

`samples/` 下的样例分为三类：可直接运行的 App、独立 Gradle root 的制品消费者、以及性能与设备验证工程。以下路径均相对仓库根目录。

## 可直接运行的 App

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| `standalone` | [`samples/standalone`](../samples/standalone) | `openMeguvizUi()` 一行接入、计数器命令、草稿输入模型、`ViewRestoration` 表现恢复、`session.click`、`onRelease` 配对清理（对应 [05 · 状态保存与恢复](quickstart/05-restoration.md)）。 |
| `xml-interop` | [`samples/xml-interop`](../samples/xml-interop) | AppCompat `Factory2`、`ContextThemeWrapper` 主题、`session.inflate` 与 `session.borrow` 既有 View，展示 XML 与直接 DSL 共存。 |
| `plan-consumer` | [`samples/plan-consumer`](../samples/plan-consumer) | `UiPlan` 分阶段验证、解析、绑定、挂载与按路径取 `ViewRef`（对应 `ui-plan`）。 |
| `generated-consumer` | [`samples/generated-consumer`](../samples/generated-consumer) | `@GenerateUiFactory` KSP 生成直接工厂、typed ref 与 `registration()` 注册（对应 `ui-codegen`）。 |

构建命令：

```sh
# standalone / xml-interop 默认包含在根工程 settings 中
./gradlew :samples:standalone:assembleDebug
./gradlew :samples:xml-interop:assembleDebug

# plan-consumer / generated-consumer 需开启对应 P2 属性
./gradlew -PenablePlan=true :samples:plan-consumer:assembleDebug
./gradlew -PenableCodegen=true :samples:generated-consumer:assembleDebug
```

上述样例直接依赖源码 `project(":ui-widgets")` 等模块，用于仓库开发自测。

## 独立制品消费者

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| `p2-consumer` | [`samples/p2-consumer`](../samples/p2-consumer) | 独立 Gradle root，只从根工程 `build/repo` 消费 `1.2.0` Maven 制品。集成 widgets、`UiPlan`、`UiListAdapter`、`PrecomputedTextBinding`、`nativeRow`、`UiPrewarmer`、KSP 生成器与 `SharedFrameDriver`，并在 release 开启 R8 与资源压缩。 |

运行步骤（详见 [p2-consumer 说明](../samples/p2-consumer/README.md)）：

```sh
./gradlew -PenableP2=true verifyP2 publishCandidate --no-daemon --max-workers=2
python samples/p2-consumer/prepare_artifacts.py
./gradlew -p samples/p2-consumer :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --max-workers=2
```

## 性能与设备验证工程

| 样例 | 路径 | 内容 |
| --- | --- | --- |
| `p2-benchmark` | [`samples/p2-benchmark`](../samples/p2-benchmark) | 7 个 P2 模块的同输入 A/B 基准测试宿主；保持前台窗口持续获取平台帧（参见 [P2 可选能力](p2.md)）。 |
| `p2-matrix` | [`samples/p2-matrix`](../samples/p2-matrix) | 设备行为矩阵宿主，覆盖旋转/重建恢复、进程死亡恢复、无障碍节点树、字号/RTL/深色模式、列表突发提交与文本预计算。 |

运行方式（需要物理真机与 `adb`）：

```sh
./gradlew -PenableP2=true verifyP2Performance -Pp2PerformanceSerial=<adb serial> -PallowDeviceInstall=true --no-daemon --max-workers=2
./gradlew -PenableP2=true verifyP2Matrix -Pp2MatrixSerial=<adb serial> -PallowDeviceInstall=true --no-daemon --max-workers=2
```

## 编译期类型案例

[`integration/compile-fixtures`](../integration/compile-fixtures) 提供 Kotlin 与 Java 正负编译用例，检验 `ParentScope`、`PropertySpec`、`RegistrySnapshot` 与各可选桥的编译期类型约束。

## 适用范围与相关文档

- 独立消费者与设备工程默认不参与核心构建；主机编译及独立消费构建通过仅证明模块集成与 R8 打包，具体运行性能需在物理真机实测。
- [消费者快速上手](getting-started.md) · [安装与接入](installation.md) · [核心合同](contracts.md) · [可选桥边界](bridges.md) · [P2 可选能力](p2.md)
