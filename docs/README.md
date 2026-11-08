# MeguvizUI 文档

本目录分三类：**消费者文档**（把库接进你的 App）、**指南**（按主题深入）、**维护者文档**（构建与验证本库）。
桥接模块一律可选：单库使用不需要它们，启用方式见[桥接](#桥接)。

## 消费者文档

| 文档 | 内容 |
| --- | --- |
| [快速上手](getting-started.md) | 5 分钟跑通第一个界面；一行接入 `openMeguvizUi()` |
| [依赖与第一屏](quickstart/01-add-dependency.md) | Maven Central / 本地候选 / 版本选择 |
| [第一屏与挂载](quickstart/02-first-screen.md) | 最小 Activity、挂载 TextView、绑定属性 |
| [生命周期与关闭](quickstart/03-lifecycle-and-close.md) | 主线程、`close()`、detach/reattach |
| [状态与命令](quickstart/04-state-and-commands.md) | `PropertyBinding`、`UiScheduler`、批次 |
| [状态保存与恢复](quickstart/05-restoration.md) | `ViewRestoration` 与 `Bundle` |
| [示例索引](examples.md) | `samples/` 下真实样例的用途与入口 |

## 指南

| 文档 | 内容 |
| --- | --- |
| [DSL 与作用域](guides/dsl.md) | `ParentScope` / `FrameScope` / `LinearScope` / `nativeRow` 与布局参数 |
| [属性](guides/properties.md) | `PropertySpec` 字段、`PropertyOwnership`、现有 `Properties` 的适用场景 |
| [调度](guides/scheduler.md) | `UiScheduler` / `UiFrameDriver` / `FrameBudget` / 容量与背压 |
| [核心合同](contracts.md) | 类型、属性、更新、生命周期与恢复（完整合同） |
| [属性与更新语义](guides/properties.md) | 属性 ownership、读回与失效语义 |
| [安装与接入](installation.md) | 工具链、本地 Maven、普通应用与示例 |

### 桥接

桥接模块默认不进构建，需要显式 Gradle 开关；「桥接可选、单库独立可用」是硬约束。

| 文档 | 内容 |
| --- | --- |
| [可选桥](bridges.md) | 三座桥的真实接口与依赖准备（维护者视角） |
| [Android 桥](guides/bridges-android.md) | 消费者视角：坐标、版本、本地消费、`AndroidUiBinding.open` |
| [Lumen 桥](guides/bridges-lumen.md) | 消费者视角：`LumenUiBridge`、属性占用与 background 登记互斥 |
| [Ref 桥](guides/bridges-ref.md) | 消费者视角：`RefBindingSession.attach`、`CodeScope`、`viewFactory` |

### 性能

| 文档 | 内容 |
| --- | --- |
| [性能调优](performance/tuning.md) | 何时启用各 P2 模块、容量与帧预算取值、后台线程与背压 |
| [性能测量](performance/measurement.md) | 复现 host 探针、ABBA 交错、配对统计与口径限制 |
| [P2 可选能力](p2.md) | 各 P2 模块的准入数据与阈值（记录，非调参建议） |

### 故障排查

| 文档 | 内容 |
| --- | --- |
| [常见问题](troubleshooting/faq.md) | 主线程、key 冲突、属性冲突、FAULTED、恢复、窗口权限 |
| [异常目录](troubleshooting/error-catalog.md) | 异常消息 → 触发条件 → 排查步骤 → 解决 |

### 迁移

| 文档 | 内容 |
| --- | --- |
| [1.0 → 1.2](migration/1.0-to-1.2.md) | 已发布 `1.0.0` → 源码候选 `1.2.0` 的 API 变化与升级路径 |

## 维护者文档

| 文档 | 内容 |
| --- | --- |
| [构建与贡献](development.md) | Host 验证、调度器专项测量、隔离 runtime 候选验证与设备边界 |
| [API 语义](api-semantics.md) | API 稳定性与版本口径 |

已发布版本为 `1.0.0`，源码版本为 `1.2.0`（尚未发布，与 Meguviz 家族统一），尚无稳定历史发行版。
标注为「源码候选」的 API 属于 `1.2.0`，消费 `1.0.0` 时不可用。
API 基线见 [api](../api)，可编译的正负类型案例见 [compile-fixtures](../integration/compile-fixtures)。

[返回项目首页](../README.md)
