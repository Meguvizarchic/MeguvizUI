# 可选共享帧驱动

`SharedFrameDriver` 把同一 owner 线程上多个 `UiSession` 的帧请求合并成**一次**平台帧回调，再把同一个 `FrameBudget` 扇出给各会话。它是可选模块，`ui-core` 不依赖它；不启用时对单库用户零负担。

## 为什么不默认启用

`Choreographer` 本身已用 `mFrameScheduled` 把同一帧的 vsync 请求合并，所以共享驱动减少的是 N−1 次 `CallbackRecord` 入队/出队/分派，而不是 N−1 次 vsync。**单 session 场景没有收益**，只有多 session（多窗口、多页面共存）才值得启用。

## 启用方式

```kotlin
// 显式构造；默认的 ChoreographerFrameDriver 路径完全不变。
val source = ChoreographerFrameSource()
val shared = SharedFrameDriver(source)
val sessionA = UiSession(CreationEnvironment(activity), shared)
val sessionB = UiSession(CreationEnvironment(activity), shared)
// 关闭顺序：先关闭各 session，再关闭 shared。
sessionA.close(); sessionB.close(); shared.close()
```

构建时需显式启用模块：

```sh
./gradlew -PenableFrameShared=true :ui-frame-shared:testDebugUnitTest
```

## 语义

| 性质 | 行为 |
| --- | --- |
| 合流 | 同一帧内 N 个 client 只产生 1 次底层 `FrameSource.requestFrame` |
| 单飞 | 每个 client 最多一个待处理请求；重复请求被拒绝 |
| 票据 | 票据只取消自己的请求；已投递/过期的旧票据不能取消后续请求 |
| 取消 | 票据只取消自己的请求（含已进入本帧快照的请求）；最后一个待处理请求被取消时，底层 source 请求一并取消 |
| 异常隔离 | 单个 client 抛非 `Error` 不阻断其他 client；`Error` 记录后仍跑完其余 client，再在扇出与状态恢复后重抛 |
| 关闭 | 回调内 `close()` 不抛 `ConcurrentModificationException`：扇出在下一条前停止，映射在 `finally` 清理 |
| source 失败 | 记录 `lastFailure`、置 faulted、保留已接受请求；后续 `requestFrame` 快速失败，避免静默冻结 |
| 诊断 | `lastFailure` 只保存文本（异常类名 + message），不持有 Throwable 或 client 引用 |
| 重入 | 回调内再次请求进入下一帧，不递归扇出 |
| 关闭 | `close()` 丢弃全部待处理请求并取消底层请求；之后 `requestFrame` 抛错 |
| 容量 | 待处理 client 上限 256，防止泄漏的 client 无限增长 |

## 边界

- 所有方法必须在 owner 线程调用（`ChoreographerFrameSource` 会检查主线程）。
- 不自动回收被遗弃的 client：调用方必须配对 `close()`；`pendingCount` 可用于诊断。
- 异常隔离与单 session 驱动的「`Error` 向上传播并 FAULTED」语义不同：共享驱动选择隔离，因为一个会话的故障不应冻结其他会话。
- 本模块不声称吞吐或帧耗时收益；host 测试只验证合流、单飞、取消、重入、异常隔离与关闭语义。

## 验证

```sh
./gradlew -PenableFrameShared=true :ui-frame-shared:testDebugUnitTest :ui-frame-shared:lintDebug --offline --no-daemon
```

`SharedFrameDriverTest` 覆盖：多 client 合流为一次 source 请求、旧票据不取消新请求、全部取消时撤销 source 请求、单 client 失败不阻断其他、`Error` 扇出后重抛、重入进入下一帧、回调内取消跳过该 client、回调内 `close()` 不抛 CME、source 失败记录诊断并快速失败、关闭丢弃待处理。

本模块已进入 `verifyP2`/`publishCandidate` 的**主机聚合门禁**（`-PenableP2=true` 或 `-PenableFrameShared=true`）；设备、性能与独立消费者准入尚未覆盖，因此**不在** `verifyReleaseCandidate` 的 release 准入范围内。`gradle.lockfile` 已提交。
