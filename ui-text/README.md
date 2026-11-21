# 文本预计算可选适配

`meguviz-ui-text:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenableText=true` 启用。它仅依赖 Android SDK 与 `ui-core`。

## 工作机制与边界

- **显式开启与回退规则**：`PrecomputedTextBinding` 独占所属节点的 `"text"` 属性槽，需传入真正的后台异步 `Executor`。默认 `enabled = false`；仅当显式开启、系统为 `API 28+`、输入为纯 `String`、长度不小于 `minimumLength`（默认 `256`）、且目标控件不是 `EditText` 也不含 `filters` 时，才会在后台调用 `PrecomputedText.create`。其余情况自动回退普通 setter（富文本会拷贝为 `SpannedString`，span 内部对象仍由调用方保持不可变）。
- **参数漂移重算与兜底**：每次提交都会记录 `PrecomputedText.Params`、`Configuration`、屏幕密度、资源 token 与绑定代际。结果回到主线程写入前再次比对参数；若期间布局方向（如 RTL）解析完毕导致参数变化，会使用最新参数重新计算最多 `2` 次，仍不一致或重试耗尽时改用普通 setter 写入文本，确保内容不会为空。
- **外部失效与关闭**：外部动态修改字体或主题后应先调用 `invalidate()` 或触发上游资源权威失效。`rebind`、节点释放或主动 `close()` 都会自动释放 `"text"` 属性槽。
- **通道差异**：纯文本更新走可合并状态通道，带 span 或编辑控件走命令通道；混用时应由调用方控制顺序。

参考性能基准数据见 [P2 性能验收基准](../docs/p2.md#性能验收基准)。
