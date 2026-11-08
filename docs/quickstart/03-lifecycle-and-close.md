# 03 · 生命周期与关闭

## 主线程独占

`UiSession` 的构造、挂载、属性提交、释放和 `close()` 都必须在 Android 主线程调用。`UiSession` 内部用 `Looper.myLooper() === Looper.getMainLooper()` 校验，违反时抛：

```text
java.lang.IllegalStateException: UI operation requires the main thread
```

源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`。

纯模型计算与不可变数据可以放后台，但**不能**据此推断任意 View 可以异步构造（见 [调度与帧所有权 / 失败恢复](../guides/scheduler.md)）。

## 在 `onDestroy` 关闭

```kotlin
override fun onDestroy() {
    session.close()          // 主线程；释放绑定、监听与受管子树
    super.onDestroy()
}
```

`close()` 会按后进先出顺序释放所有受管节点，关闭调度器，并执行 `onClose` / `onRelease` 登记的清理。清理失败不会中断其他资源的释放；首个失败会通过 `CloseRequest.failure` 或异常暴露。

## 后台请求关闭

后台线程不能直接 `close()`，用 `requestClose()`：

```kotlin
val request: CloseRequest = session.requestClose()
// request.completed：owner 已实际清理完成
// request.failure：清理过程中的首个失败（可能为 null）
```

`requestClose()` 返回的 `CloseRequest` 立即表示「已受理」，`completed` 才表示 owner 清理完成（源码：`UiSession.kt`）。如果 owner looper 拒绝投递，会记录 `IllegalStateException("Owner looper rejected close")`。

## detach / reattach

根 View 真正 detach 时，让会话失效旧 attach token 并暂停调度：

```kotlin
session.detach()    // 旧 attach token 失效；scheduler 进入 PAUSED
// ... 根 View 重新 attach 后
session.reattach()  // scheduler 恢复 ACTIVE
```

`detach()` 不会自动清除 scheduler 的 FAULTED；`reattach()` 只恢复 PAUSED（源码：`UiSession.kt`）。Android 桥会配对安装 `OnAttachStateChangeListener` 自动转发这两个调用，见 [可选桥](../bridges.md)。

## rebind 与 release

```kotlin
session.rebind(titleRef)   // 先提升 binding 域，再释放旧绑定与监听
session.release(titleRef)  // 释放该节点及其子树
```

`rebind` 用于「同一 View 换一套属性绑定」；`release` 用于「这个 View 不再受管」。释放后 `ViewRef.get()` 会失败：

```text
java.lang.IllegalStateException: View reference released
```

源码：`UiSession.kt`、`212-217`、`228-258`。

## 外部资源的配对清理

`configure` 里的原生 listener、订阅、动画等副作用库无法自动发现，需要显式配对：

```kotlin
val nativeTitle = title.get()
nativeTitle.addTextChangedListener(watcher)
session.onRelease(title) { nativeTitle.removeTextChangedListener(watcher) }

session.onClose { /* 会话级清理 */ }
```

`onRelease` / `onClose` 的清理闭包应捕获所需原生 View，避免在引用释放后重新解析（源码：`UiSession.kt`）。

## 保留视觉属性

在接入 Lumen 或动画前，先保留对应属性槽：

```kotlin
val lease = session.reserveProperties(titleRef, setOf("background"))
// ... 交给 Lumen/动画接管
lease.close()   // 释放保留
```

未保留就绑定同 id 属性会抛 `IllegalStateException("Conflicting visual property owner")`（源码：`UiSession.kt`）。

## 调度器状态

`session.scheduler.state` 取值（源码：`ui-core/.../scheduler/UiScheduler.kt`）：

| 状态 | 含义 |
| --- | --- |
| `ACTIVE` | 正常接收提交 |
| `PAUSED` | `detach()` 或显式 `pause()`；拒绝新提交 |
| `FAULTED` | 验证或 action 抛异常；队列已清空 |
| `CLOSED` | 会话关闭 |

`pause()` 默认清空队列；`pause(clearPending = false)` 才保留队列。`resume()` 只恢复 PAUSED，`recover()` 只恢复 FAULTED，且要求调用者先核对/重建真实状态（源码：`UiScheduler.kt`）。诊断信息在 `session.scheduler.failure`（`SchedulerFailure`）与 `cancellationFailure`。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `UI operation requires the main thread` | 非主线程调用会话 API | 切回主线程 |
| `Session is not active` | 会话 close / detach / FAULTED / CLOSED | 检查生命周期与 `scheduler.state` |
| `Session disposed` | `close()` 后访问 `session.environment` | 不要复用已关闭会话 |
| `View reference released` | 节点或会话已释放仍解析引用 | 不要跨释放持有 `ViewRef` |
| `Binding closed` | 绑定已 `close()` 仍提交 | 重新绑定 |
| `Owner looper rejected close` | `requestClose()` 时 owner looper 无法投递 | 检查主线程 looper 状态 |
| `Conflicting visual property owner` | 与已保留属性冲突 | 先 `reserveProperties` 或换属性 id |
| `Only a paused scheduler can resume` | 对非 PAUSED 调度器调 `resume()` | 先确认 `scheduler.state` |
| `Only a faulted scheduler can recover` | 对非 FAULTED 调度器调 `recover()` | 先核对/重建真实状态 |

## 下一步

[04 · 状态与命令](04-state-and-commands.md)
