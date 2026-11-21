# 按需预热

`meguviz-ui-prewarm:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenablePrewarm=true` 启用。

`UiPrewarmer` 需由调用方通过 `UiPrewarmer.attach(session)` 显式装配，它复用已有 `UiScheduler`，每帧最多执行一个主线程工作单元，并受队列容量（默认 `4`）、单项步数上限（默认 `32`）和单单元耗时阈值（默认 `2ms`）约束。单单元超时只能在单元返回后检测并停止后续工作，不能抢占正在执行的构造器。

## 使用规则

- 任务通过 `PrewarmWork.isCurrent` 校验资源、主题与目标有效性；宿主必须在脱离窗口、低内存与资源变化时分别调用 `onDetached()`、`onLowMemory()`、`onResourcesChanged()`。
- 当共享 `UiScheduler` 因其他操作进入 `PAUSED` 或 `FAULTED` 时，调用 `checkScheduler()` 清理待预热任务；`UiPrewarmer.attach(session)` 会在会话 `close()` 时自动释放队列。
- `PrewarmWork.discard` 必须释放未完成任务的全部暂存引用，`ready` 负责接收预热完成的所有权；提交被拒绝时所有权仍归调用方。当用户打开未预热完毕的页面时，应用应先调用 `cancel()` 再在前台按正常路径构建。
- 原生 `View` 的构造只能在 owner 主线程执行，后台仅可准备纯数据模型。

启用前应结合目标页面的首次切换尾延迟、CPU 占用与内存峰值实测评估收益，参见 [P2 可选能力](../docs/p2.md)。
