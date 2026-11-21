# 03 · 生命周期与关闭

## 主线程独占

`UiSession` 的构造、挂载、属性提交、释放和 `close()` 都必须在 Android 主线程调用。`UiSession.assertOwner()` 校验当前线程是否为 `main` Looper 线程，违反时抛出：

```text
java.lang.IllegalStateException: UI operation requires the main thread
```

源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`。

纯模型计算可放在后台线程；若需从后台安全提交更新到主线程，使用 `session.submitStateAsync` / `submitCommandAsync`（参见 [04 · 状态与命令](04-state-and-commands.md)）。

## 在 `onDestroy` 关闭

使用 `openMeguvizUi()` 时由框架在 Activity 销毁时自动清理；手动创建的 `UiSession` 必须在宿主销毁处显式关闭：

```kotlin
override fun onDestroy() {
    session.close()          // 主线程；释放绑定、监听与受管子树
    super.onDestroy()
}
```

`close()` 按注册逆序释放所有受管节点，关闭调度器，并执行 `onClose` / `onRelease` 登记的清理动作。单个清理动作抛出异常不会中断其他资源的释放，全部异常合并报告。

## 后台请求关闭

后台线程不能直接调用 `close()`，应调用 `requestClose()`：

```kotlin
val request: CloseRequest = session.requestClose()
// request.completed：owner 主线程已实际清理完成
// request.failure：清理过程中的首个失败（无异常时为 null）
```

`requestClose()` 立即返回并置位关闭标记，`completed` 在主线程真正清理完毕后才变为 `true`。若主线程 looper 拒绝投递，将在 `request.failure` 记录 `IllegalStateException("Owner looper rejected close")`。

## `detach` / `reattach`

根 View 脱离窗口时，调用 `detach()` 失效旧 attach token 并暂停调度；重新挂回窗口时调用 `reattach()`：

```kotlin
session.detach()    // 旧 attach token 失效；scheduler 进入 PAUSED
// ... 根 View 重新 attach 后
session.reattach()  // scheduler 恢复 ACTIVE
```

`detach()` 不会清除调度器的 `FAULTED` 状态；`reattach()` 只恢复 `PAUSED`（源码：`UiSession.kt`）。使用 Android 桥时，`AndroidUiBinding.open` 会自动监听 `OnAttachStateChangeListener` 并转发调用。

## `rebind` 与 `release`

```kotlin
session.rebind(titleRef)   // 推进该节点 binding 代际，释放旧绑定与监听
session.release(titleRef)  // 释放该节点及其子树
```

- `rebind` 用于「保留同一 View，重装属性绑定」。
- `release` 用于「不再托管该 View」；对于 `CREATED` 与 `TRANSFERRED` 节点还会从父容器移除视图，`BORROWED` 节点保留原视图结构。释放后调用 `ViewRef.get()` 会抛出：

```text
java.lang.IllegalStateException: View reference released
```

## 外部资源的配对清理

在 `configure` 或外部安装的监听器、订阅与动画，库无法自动追踪，须通过 `onRelease` / `onClose` 显式配对释放：

```kotlin
val nativeTitle = title.get()
nativeTitle.addTextChangedListener(watcher)
session.onRelease(title) { nativeTitle.removeTextChangedListener(watcher) }

session.onClose { /* 会话级清理 */ }
```

清理闭包应直接捕获所需的原生 `View` 引用，避免在节点已释放后再调用 `ref.get()` 重新解析。

## 保留视觉属性

接入 Lumen 或外部动画前，先显式保留属性槽：

```kotlin
val lease = session.reserveProperties(titleRef, setOf("background"))
// ... 交由 Lumen 或外部动画控制
lease.close()   // 释放保留
```

若在同一节点已被占用同 `id` 属性后再保留或重复绑定，将抛出 `Conflicting visual property owner` 或 `Property already owned`。

## 调度器状态

`session.scheduler.state` 取值（源码：`ui-core/.../scheduler/UiScheduler.kt`）：

| 状态 | 含义 |
| --- | --- |
| `ACTIVE` | 正常接收提交 |
| `PAUSED` | `detach()` 或显式 `pause()` 后暂停，拒绝新提交 |
| `FAULTED` | 验证器、action 或驱动抛异常后进入故障状态，待处理队列已清空 |
| `CLOSED` | 会话或调度器已关闭 |

`pause()` 默认清空待处理队列（传入 `clearPending = false` 可保留）；`resume()` 只恢复 `PAUSED`；`recover()` 只恢复 `FAULTED`，且要求调用方先核对或重建真实 `View` 状态（失败操作不会自动重试）。诊断详情见 `session.scheduler.failure`（`SchedulerFailure`）与 `cancellationFailure`。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `UI operation requires the main thread` | 非主线程调用同步会话 API | 切回主线程，或使用 `submit*Async` |
| `Session is not active` | 会话 `close` / `detach` / `FAULTED` / `CLOSED` | 检查生命周期与 `scheduler.state` |
| `Session disposed` | `close()` 后访问 `session.environment` | 不要复用已关闭会话 |
| `View reference released` | 节点或会话释放后仍解析 `ViewRef` | 不要跨释放持有 `ViewRef` |
| `Binding closed` | 绑定 `close()` 后仍提交 | 重新绑定 |
| `Owner looper rejected close` | `requestClose()` 时主线程 looper 已退出 | 检查宿主存活周期 |
| `Conflicting visual property owner` | 与已保留属性冲突 | 先 `reserveProperties` 或更换属性 `id` |
| `Only a paused scheduler can resume` | 对非 `PAUSED` 调度器调用 `resume()` | 先核查 `scheduler.state` |
| `Only a faulted scheduler can recover` | 对非 `FAULTED` 调度器调用 `recover()` | 先核对状态后再恢复 |

## 下一步

[04 · 状态与命令](04-state-and-commands.md)
