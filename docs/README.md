# MeguvizUI 文档

本目录分为三类：**消费者文档**（将库接入应用）、**主题指南**（按专题深入）、**维护者文档**（构建、测量与校验本库）。桥接模块一律可选：单独使用 MeguvizUI 不需要任何桥接依赖，按需启用方式见[桥接说明](#桥接)。

## 消费者文档

| 文档 | 内容 |
| --- | --- |
| [AI Skill 接入与迁移](../.agents/skills/meguviz-ui/SKILL.md) | 四阶段接入与迁移工作流（含模块速查与排障参考） |
| [快速上手](getting-started.md) | 约 5 分钟跑通第一个界面；一行接入 `openMeguvizUi()` |
| [01 · 添加依赖](quickstart/01-add-dependency.md) | Maven Central `1.2.0`、`meguviz-bom`、KSP BOM 注意事项 |
| [02 · 第一屏与挂载](quickstart/02-first-screen.md) | 最小 `Activity`、挂载 `TextView`、绑定属性 |
| [03 · 生命周期与关闭](quickstart/03-lifecycle-and-close.md) | 主线程约束、`close()`、`detach` / `reattach` |
| [04 · 状态与命令](quickstart/04-state-and-commands.md) | `PropertyBinding`、`UiScheduler`、批次操作 |
| [05 · 状态保存与恢复](quickstart/05-restoration.md) | `ViewRestoration` 与 `Bundle` |
| [示例索引](examples.md) | `samples/` 下各类样例的用途与构建入口 |

## 主题指南

| 文档 | 内容 |
| --- | --- |
| [DSL 与作用域](guides/dsl.md) | `ParentScope` / `FrameScope` / `LinearScope` / `nativeRow` 与布局参数 |
| [属性绑定](guides/properties.md) | `PropertySpec` 字段、`PropertyOwnership` 与内置 `Properties` 选型 |
| [调度与背压](guides/scheduler.md) | `UiScheduler` / `UiFrameDriver` / `FrameBudget` / 跨线程异步提交 |
| [核心合同](contracts.md) | 类型、属性、更新、生命周期与恢复规范 |
| [安装与接入](installation.md) | 工具链基线、Maven Central、BOM 与本地源码构建 |

### 桥接

桥接模块默认不参与源码构建，需显式 Gradle 开关启用；单独使用 core / widgets 时完全不需要引入桥。

| 文档 | 内容 |
| --- | --- |
| [可选桥边界](bridges.md) | 三座桥的接口边界、Central 上线现状与跨库协同规则 |
| [Android 桥](guides/bridges-android.md) | `meguviz-ui-android-bridge:1.2.0` 与 `AndroidUiBinding.open` |
| [Ref 桥](guides/bridges-ref.md) | `meguviz-ui-ref-bridge:1.2.0`、`RefBindingSession.attach` 与 `CodeScope` |
| [Lumen 桥](guides/bridges-lumen.md) | 仅源码构建的 `ui-lumen-bridge`、`LumenUiBridge` 与 `background` 互斥 |

### 性能

| 文档 | 内容 |
| --- | --- |
| [性能调优](performance/tuning.md) | 何时启用各 P2 模块、容量与帧预算取值、后台线程与背压 |
| [测量方法](performance/measurement.md) | 复现 host 探针、ABBA 交错、配对统计与适用范围说明 |
| [P2 可选能力](p2.md) | 各 P2 模块的验收基准数据与阈值说明 |

### 故障排查

| 文档 | 内容 |
| --- | --- |
| [常见问题 FAQ](troubleshooting/faq.md) | 主线程、key 冲突、属性冲突、`FAULTED`、恢复与窗口权限 |
| [异常消息目录](troubleshooting/error-catalog.md) | 源码逐字异常消息 → 触发条件 → 排查与解决 |

### 迁移

| 文档 | 内容 |
| --- | --- |
| [1.0 → 1.2 迁移指南](migration/1.0-to-1.2.md) | `1.0.0` → `1.2.0` 的 API 增量变化、行为调整与升级步骤 |

## 维护者文档

| 文档 | 内容 |
| --- | --- |
| [构建、验证与贡献](development.md) | Host 验证、调度器探针、隔离候选验证与设备边界 |
| [API 语义](api-semantics.md) | `1.2.0` API 语义细节与兼容性基线 |

## 版本与 API 基线说明

- **当前发布版本**：`1.2.0`（Maven Central 统一通过 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 提供 12 个 MeguvizUI 制品，包含 `ui-android-bridge`、`ui-ref-bridge` 与 `ui-frame-shared`）。
- **未上 Central 例外**：`meguviz-ui-lumen-bridge` 仍仅支持源码构建。
- **API 与编译基线**：公开 JVM 描述符基线见 [api](../api)，可编译正负类型案例见 [compile-fixtures](../integration/compile-fixtures)。

[返回项目首页](../README.md)
