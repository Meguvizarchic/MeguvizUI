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

`verifyP2` 是聚合门禁，只接受 `-PenableP2=true`（七个模块全开）；单模块验证用上面的 task 路径。

## 语义

| 性质 | 行为 |
| --- | --- |
| 合流 | 同一帧内 N 个 client 只产生 1 次底层 `FrameSource.requestFrame` |
| 请求粒度 | 每次 `requestFrame` 都是一次独立的一次性请求，各自一张票据；驱动不维护 client 身份，两次调用就是两个请求 |
| 票据 | 票据只取消自己的请求；已投递/过期的旧票据不能取消后续请求 |
| 取消 | 票据只取消自己的请求（含已进入本帧快照的请求）；最后一个待处理请求被取消时，底层 source 请求一并取消 |
| 源契约 | `FrameSource` 必须异步投递且每次请求至多投递一次；违反时抛 `IllegalStateException`，不会递归或破坏扇出 |
| 异常隔离 | 单个 client 抛非 `Error` 不阻断其他 client；`Error` 记录后仍跑完其余 client，再在扇出与状态恢复后重抛 |
| 关闭 | 回调内 `close()` 不抛 `ConcurrentModificationException`：扇出在下一条前停止，映射在 `finally` 清理 |
| source 失败（请求时） | 抛出异常的那次 `requestFrame` 什么都没接受：回调永不投递、不占容量，调用方可直接重试而不会累积重复请求；此前已接受、持有票据的请求保持待处理 |
| source 失败（扇出后重挂） | 发生在平台帧回调内，非 `Error` 不再抛给平台（否则主线程崩溃）：记录 `lastFailure`、保留待处理请求，下一次 `requestFrame` 重试 source；`Error` 仍重抛 |
| 诊断 | `lastFailure` 只保存文本（异常类名 + message），不持有 Throwable 或 client 引用；取消失败不会覆盖 client 的根因文本 |
| 重入 | 回调内再次请求进入下一帧，不递归扇出 |
| 关闭 | `close()` 丢弃全部待处理请求并取消底层请求；之后 `requestFrame` 抛错 |
| 容量 | 待处理请求上限 256，防止泄漏的 client 无限增长 |

## 边界

- 所有方法都在主线程断言（与 `UiSession` 一致）；`ChoreographerFrameSource` 另有自己的主线程检查。
- 不自动回收被遗弃的 client：调用方必须配对 `close()`；`pendingCount` 可用于诊断，但它只统计下一帧的待处理请求，扇出进行中读数为 0。
- 异常隔离与单 session 驱动的「`Error` 向上传播并 FAULTED」语义不同：共享驱动选择隔离，因为一个会话的故障不应冻结其他会话。注意 `Error` 最终仍会抛回平台，而平台 `Choreographer` 不隔离回调异常，因此一次 `Error` 会中断该帧其余平台动画回调并跳过 traversal。
- **聚合预算**：所有 client 共享同一个 `FrameBudget`，但每个 `UiScheduler` 各自计算工作预算。API 27–32 没有平台 deadline（`estimated = true`），因此 N 个 session 在同一帧的聚合主线程工作上界是 `N × maxFrameWorkNanos`，与帧 deadline 无关。session 越多越应下调各自的 `maxFrameWorkNanos`。
- 本模块不声称吞吐或帧耗时收益；host 测试只验证合流、票据、取消、重入、异常隔离与关闭语义。

## 验证

```sh
./gradlew -PenableFrameShared=true :ui-frame-shared:testDebugUnitTest :ui-frame-shared:lintDebug --offline --no-daemon
```

`SharedFrameDriverTest`（19 项）覆盖：多 client 合流为一次 source 请求、旧票据不取消新请求、全部取消时撤销 source 请求、单 client 失败不阻断其他、`Error` 扇出后重抛、重入进入下一帧、回调内取消跳过该 client、回调内 `close()` 不抛 CME、扇出后重挂失败不抛给平台且保留请求并由下一次请求重试、重挂时的 `Error` 仍重抛、抛错的 `requestFrame` 回滚且重试不累积、回滚不影响此前已接受的请求、同步 source 被拒绝而非递归、单请求二次投递被拒绝、容量上限、`close()` 幂等、取消失败不覆盖根因文本。

`FrameSharedInstrumentation`（3 项，设备）用真实 `Choreographer` 验证：两个 session 在一帧内共同排空且只产生一次平台请求、跨帧持续投递、关闭其中一个 session 不影响另一个。`p2-matrix` 另有一条 `frame_shared_two_sessions_one_platform_frame` 用例把合流次数断言为精确整数。

## 准入状态

本模块已进入 `verifyP2`/`publishCandidate` 的主机聚合门禁，并已纳入 release 准入范围（`build-logic/verification/gates.py` 的 `ALLOWED_P2`、`p2_gates.py` 的模块表与 `verifyReleaseCandidate` 同步声明）。准入要求四类证据同时有效：

| 证据 | 来源 | 状态 |
| --- | --- | --- |
| 主机聚合 | `verifyP2`（7 profiles） | 通过 |
| 独立消费者 | `samples/p2-consumer`（R8 release，含共享驱动双 session 路径） | 需重新生成回执 |
| 设备行为矩阵 | `verifyP2Matrix`（新增 `frame_shared_two_sessions_one_platform_frame`） | 需重新生成回执 |
| 性能准入 | `verifyP2Performance` 的 `frame-shared` profile（`32-session-shared-frame-dispatch`，`bounded-overhead`，上界 1.25） | 需在物理机上重新测量 |

性能准则是 `bounded-overhead` 而非 `benefit`：共享驱动可能比它替换掉的原生回调更贵，准则必须允许这个否定结论。模拟器测量不构成准入证据（`p2_performance.py` 直接拒绝）。

`gradle.lockfile` 已提交。
