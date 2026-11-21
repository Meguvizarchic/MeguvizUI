# RecyclerView 可选适配

`meguviz-ui-recycler:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenableRecycler=true` 启用。该模块依赖 `androidx.recyclerview:recyclerview:1.4.0`，核心模块 `ui-core` / `ui-widgets` 不引入 `RecyclerView`。

## 核心行为与约束

- **构造与快照策略**：`UiListAdapter` 要求提供后台 `Executor`、稳定 ID 提取器、`DiffUtil.ItemCallback` 与失败回调 `onDiffFailure`。
  - 模型包含可变字段时，传入 `snapshotItem` 函数，在主线程复制所有参与 ID 与 Diff 的字段。
  - 模型完全不可变（所有参与 ID 与 Diff 的字段均为 `val` 且不被修改）时，使用不带 `snapshotItem` 的构造器：每次 `submitList` 在主线程仅做一次整体列表数组拷贝，不再逐项回调。
- **后台 Diff 与合并机制**：最多保留 1 个运行中的后台 Diff 任务与 1 个最新待办（latest-wins），不中断运行中的计算并丢弃过期结果。ID 唯一性及非 `NO_ID` 校验在后台随 Diff 一并执行，校验失败时通过 `onDiffFailure` 报告并跳过应用（待办中被新列表覆盖的中间列表不做校验）。
- **绑定生命期与载荷**：每次绑定和回收均推进 `ViewHolder` 的内部代际；异步绑定结果回到主线程前须用 `bindingToken(holder)` 核验是否仍有效，并在 `onRebind` 释放前一次的 `UiSession` 绑定。若重写 `bindPartial`，须能正确处理系统合并后的 `payloads` 且在回退完整绑定时恢复全部视图状态。
- **关闭顺序**：调用 `adapter.close()` 前必须先从 `RecyclerView` 解绑（`recyclerView.adapter = null`）；`close()` 会对仍绑定的 holder 执行 `onRebind`。
- **通道边界**：列表突发提交采用覆盖最新值语义，不可将必须保留执行次数的业务操作放入列表提交。

参考性能基准数据见 [P2 性能验收基准](../docs/p2.md#性能验收基准)；具体业务列表的流畅度与内存占用仍需在目标设备上实测验证。
