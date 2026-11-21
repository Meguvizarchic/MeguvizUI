# 可选共享帧驱动

`meguviz-ui-frame-shared:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenableFrameShared=true` 启用。

`SharedFrameDriver` 把同一主线程上多个 `UiSession` 的帧请求合并成**一次**平台帧回调，再把同一个 `FrameBudget` 扇出给各个会话。`ui-core` 不依赖该模块，单会话场景保持默认无开销。

## 适用场景

`Choreographer` 本身会通过 `mFrameScheduled` 合并同一帧的 vsync 请求，因此共享驱动节省的是多次 `CallbackRecord` 入队与分派开销，而非减少系统 vsync 次数。**单会话没有收益**，仅当同一主线程上多个窗口或页面会话并发存在时才考虑启用。

## 启用方式

```kotlin
val source = ChoreographerFrameSource()
val shared = SharedFrameDriver(source)
val sessionA = UiSession(CreationEnvironment(activity), shared)
val sessionB = UiSession(CreationEnvironment(activity), shared)
// 关闭顺序：先关闭各个 session，再关闭 shared driver
sessionA.close()
sessionB.close()
shared.close()
```

源码模块单测命令：

```sh
./gradlew -PenableFrameShared=true :ui-frame-shared:testDebugUnitTest :ui-frame-shared:lintDebug --offline --no-daemon
```

## 语义与边界

| 性质 | 行为说明 |
| --- | --- |
| 合流 | 同一帧内多个 client 仅产生 1 次底层 `FrameSource.requestFrame` |
| 请求粒度 | 每次 `requestFrame` 为独立一次性请求，返回独立 `FrameTicket` |
| 取消 | 票据只取消对应请求（含已捕获快照内的请求）；最后一个待处理请求取消时一并取消底层请求 |
| 源契约 | `FrameSource` 必须异步投递且每次请求至多投递一次，违规抛出 `IllegalStateException` |
| 异常隔离 | 单个 client 抛非 `Error` 不阻断其余 client；遇到 `Error` 会完成当前扇出并在状态恢复后重抛 |
| 请求失败处理 | 请求阶段抛异常不消耗容量、不保留回调；扇出后重挂阶段非 `Error` 记录到 `lastFailure` 供下次请求重试 |
| 诊断与容量 | `lastFailure` 仅保存文本消息；待处理请求上限为 `256`；回调内 `close()` 安全跳过剩余迭代并在 `finally` 清理 |
| 聚合预算 | 各 `UiScheduler` 共享同一 `FrameBudget` 但独立扣减工作预算；API 27–32 无系统 deadline 时建议按会话数量下调单会话 `maxFrameWorkNanos` |

## 验证范围与验收状态

- **主机验证**：`SharedFrameDriverTest`（19 项单测）与 `verifyP2`（`-PenableP2=true`）覆盖合流、票据取消、重入、异常隔离、容量上限与关闭语义。
- **设备仪器与性能**：`FrameSharedInstrumentation`（3 项）与 `p2-matrix` 覆盖真实 `Choreographer` 双会话合流；`verifyP2Performance` 的 `frame-shared` 配置采用 `bounded-overhead`（上界 `1.25`，允许“单场景不值得启用”的结论），尚缺物理真机对比数据，实际应用引入前应按业务多会话场景测量帧预算影响。
