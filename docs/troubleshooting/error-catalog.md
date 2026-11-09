# 异常消息目录

本目录把源码中逐字抛出的异常消息映射到触发条件、排查步骤与解决办法。

**口径**

- 消息**逐字**来自源码；`${...}`、`$...` 是源码中的插值占位符，实际消息会替换为具体值。
- 表里列的是消息的**开头**。部分消息在开头之后用冒号追加了出问题的对象、原因和处理提示（例如 `Session disposed: this UiSession is closed (...)`），请按开头匹配；追加部分不改变这里的触发条件与处理方向。
- 位置格式为 `文件:行号`，对应当前源码（1.2.0 源码候选）。
- 标注「源码候选」的模块/API 不在 `v1.0.0` 发布标签中，尚未发布。
- 上游库（MeguvizAndroid、MeguvizDrawable、Lumen、Ref runtime）抛出的异常不在本仓库，本文只列调用点，见末节。
- `check`/`require` 抛 `IllegalStateException`/`IllegalArgumentException`；`error` 抛 `IllegalStateException`。无消息的 `check` 会抛无消息异常，单独标注。

---

## 速查表

| 消息 | 位置 | 层 | 处理方向 |
| --- | --- | --- | --- |
| `UI operation requires the main thread` | `UiSession.kt` | 会话 | 切回主线程 |
| `Session is not active` | `UiSession.kt` | 会话 | 检查 close/detach/FAULTED |
| `Session disposed` | `UiSession.kt` | 会话 | 不复用已关闭会话 |
| `Factory type differs from component` | `UiSession.kt` | 挂载 | 修正工厂/类型配对 |
| `Factory returned an already parented View` | `UiSession.kt` | 挂载 | 工厂返回无 parent 的新 View |
| `View already managed` | `UiSession.kt`、`:89` | 挂载/借用 | 一个 View 只挂载一次 |
| `Duplicate root key: $key` | `UiSession.kt` | 借用 | 换 key 或改用 transfer |
| `Explicitly detach before transferring; UI never steals an external child` | `UiSession.kt` | 移交 | 先显式 detach |
| `Managed <merge> requires an explicit ownership protocol` | `UiSession.kt` | inflate | 改用具体根布局 |
| `Inflater returned an already owned View` | `UiSession.kt` | inflate | 检查 inflater/父容器 |
| `Duplicate key in parent: ${key.value}` | `UiSession.kt` | 挂载 | 换 key 或先 release |
| `Session node identities exhausted; create a new session` | `UiSession.kt` | 会话 | 新建会话 |
| `View reference released` | `UiSession.kt` | 引用 | 不跨释放持有 ViewRef |
| `Property already owned: ${property.id}` | `UiSession.kt` | 属性 | 一个属性一个 owner |
| `Conflicting visual property owner` | `UiSession.kt` | 属性 | 先装视觉 owner |
| `Invalidate the upstream resource authority` | `UiSession.kt` | 资源 | 让上游权威失效 |
| `Owner looper rejected close` | `UiSession.kt` | 关闭 | 检查主线程 looper |
| `Shared comparison requires a live getter` | `Properties.kt` | 属性 | SHARED_READ 必须给 read |
| `Binding closed` | `Properties.kt`、`:72`；`PrecomputedTextBinding.kt` | 属性 | 重新绑定 |
| `A frame driver must declare its owner` | `UiScheduler.kt` | 调度 | 自定义驱动给 owner |
| `Only a paused scheduler can resume` | `UiScheduler.kt` | 调度 | 先确认状态 |
| `Only a faulted scheduler can recover` | `UiScheduler.kt` | 调度 | 先核对/重建状态 |
| `UiFrameDriver must not invoke a requested callback synchronously` | `UiScheduler.kt` | 驱动 | 驱动必须异步投递 |
| `Nested frame delivery is not supported` | `UiScheduler.kt` | 驱动 | 不要重入 drain |
| `estimated=false requires a platform deadline; use estimated=true for an advisory budget` | `UiFrameDriver.kt` | 帧预算 | 按 estimated 语义传值 |
| `Frame driver requires the main thread` | `ChoreographerFrameDriver.kt`、`:85` | 驱动 | 切回主线程 |
| `No provider for ${type.id}` | `Registry.kt` | 扩展 | 注册对应 provider |
| `Component schema/type mismatch` | `Registry.kt` | 扩展 | 统一 schema/viewClass |
| `Duplicate provider id` | `Registry.kt` | 扩展 | 去掉重复 id |
| `Extension dependency cycle` | `Registry.kt` | 扩展 | 解开依赖环 |
| `Missing dependency: $id` | `Registry.kt` | 扩展 | 注册依赖 |
| `Unsupported SPI version` | `Registry.kt` | 扩展 | 使用 1.0 SPI |
| `Unsupported lifecycle/thread contract` | `Registry.kt` | 扩展 | thread=main、lifetime=session |
| `Missing capabilities for $id` | `Registry.kt` | 扩展 | 安装时声明能力 |
| `Invalid override` | `Registry.kt` | 扩展 | 修正 overrides |
| `Ambiguous provider priority` | `Registry.kt` | 扩展 | 优先级唯一 |
| `Provider conflict requires explicit override` | `Registry.kt` | 扩展 | 显式声明 override |
| `Override changes component contract` | `Registry.kt` | 扩展 | 保持 schema/viewClass |
| `Wrong state codec` | `StateCodec.kt` | 恢复 | codec id 一致 |
| `Stable IDs must be in the generated-ID range` | `StateCodec.kt` | 恢复 | 使用库分配 ID |
| `Duplicate native ID` | `StateCodec.kt` | 恢复 | 清理重复 ID |
| `Duplicate stable path` | `StateCodec.kt` | 恢复 | path 唯一 |
| `Component type/schema changed; explicitly rebuild its saved identity` | `StateCodec.kt` | 恢复 | 显式重建身份 |
| `Restored ID conflicts with native View` | `StateCodec.kt` | 恢复 | 解决原生树冲突 |
| `Stable View IDs exhausted` | `StateCodec.kt` | 恢复 | 减少登记数量 |
| `Unknown restoration schema` | `ViewRestoration.kt` | 恢复 | 明确拒绝/迁移 |
| `Restoration node limit exceeded` | `ViewRestoration.kt` | 恢复 | 减少节点数 |
| `Duplicate restoration node` | `ViewRestoration.kt` | 恢复 | 清理重复节点 |
| `Duplicate restore path` | `ViewRestoration.kt` | 恢复 | path 唯一 |
| `Explicit native ID must be managed by the caller's hierarchy restoration` | `ViewRestoration.kt` | 恢复 | 由调用方管理原生 id |
| `openMeguvizUi must be called on the main thread`（源码候选） | `UiScope.kt` | 一行入口 | 切回主线程 |
| `${javaClass.name} is destroyed`（源码候选） | `UiScope.kt` | 一行入口 | Activity 存活时调用 |
| `Frame driver closed or close listener already owned` | `AndroidFrameDriver.kt` | Android 桥 | 不重复注册/操作 |
| `Android frame channel closed` | `AndroidFrameDriver.kt` | Android 桥 | 关闭后不再请求 |
| `A UI session may have only one pending frame request` | `AndroidFrameDriver.kt` | Android 桥 | 一个会话一张票据 |
| `Window driver closed or frame already pending` | `AndroidWindowFrameDriver.kt` | Android 桥 | 通过会话提交 |
| `Lumen bridge closed` | `LumenUiBridge.kt` | Lumen 桥 | 关闭后不再 bind |
| `Surface registration closed` | `LumenUiBridge.kt` | Lumen 桥 | 关闭后不再 update |
| `CodeScope must be open` | `RefBindingSession.kt` | Ref 桥 | CodeScope 打开时创建 |
| `Ref binding session is closed` | `RefBindingSession.kt` | Ref 桥 | 关闭后不再用 |
| `UI session is not active` | `RefBindingSession.kt` | Ref 桥 | 检查会话状态 |
| `The borrowed CodeScope identity or epoch changed` | `RefBindingSession.kt` | Ref 桥 | 重建绑定会话 |
| `Dynamic factory type must belong to this CodeScope's actual loader` | `RefBindingSession.kt` | Ref 桥 | 类型属于该 loader |
| `Use attach for View bindings` | `RefBindingSession.kt` | Ref 桥 | 用 attach 装配 |
| `Dynamic factory is closed` | `RefBindingSession.kt` | Ref 桥 | 关闭后不再创建 |
| `Receiver lease is closed` | `RefBindingSession.kt`、`:178` | Ref 桥 | 关闭后不再取值 |
| `View binding token is stale` | `RefBindingSession.kt` | Ref 桥 | 重新获取 lease |
| `Method handle is closed` | `RefBindingSession.kt` | Ref 桥 | 关闭后不再调用 |
| `Shared frame driver closed`（源码候选） | `SharedFrameDriver.kt` | 共享帧 | 关闭后不再请求 |
| `Shared frame driver client capacity exceeded`（源码候选） | `SharedFrameDriver.kt` | 共享帧 | 减少 client 数 |
| `Shared frame driver request identities exhausted`（源码候选） | `SharedFrameDriver.kt` | 共享帧 | 重建驱动 |
| `FrameSource must deliver asynchronously and at most once per request`（源码候选） | `SharedFrameDriver.kt` | 共享帧 | 修正 FrameSource |
| `Shared frame driver requires the main thread`（源码候选） | `SharedFrameDriver.kt` | 共享帧 | 切回主线程 |
| `Frame source requires the main thread`（源码候选） | `ChoreographerFrameSource.kt`、`:90` | 共享帧 | 切回主线程 |
| `Unsupported plan schema` | `UiPlan.kt` | Plan | schema 1.0 |
| `Plan depth exceeds limit` | `UiPlan.kt` | Plan | 降低深度 |
| `Empty or duplicate sibling key: ${node.key}` | `UiPlan.kt` | Plan | 兄弟 key 唯一 |
| `Invalid component identity` | `UiPlan.kt` | Plan | 修正 componentId/schema |
| `Invalid width` | `UiPlan.kt` | Plan | -1/-2/≥0 |
| `Invalid height` | `UiPlan.kt` | Plan | -1/-2/≥0 |
| `Plan size exceeds limit` | `UiPlan.kt` | Plan | 减少节点 |
| `Children require a ViewGroup` | `UiPlan.kt` | Plan | 子组件用 ViewGroup |
| `Parent type mismatch` | `UiPlan.kt` | Plan | 父类型一致 |
| `Root parent kind mismatch` | `UiPlan.kt` | Plan | rootKind 一致 |
| `Root parent type mismatch` | `UiPlan.kt` | Plan | root 类型一致 |
| `Duplicate plan component and parent kind` | `UiPlan.kt` | Plan | 组件+父种类唯一 |
| `Component identity differs across parent kinds` | `UiPlan.kt` | Plan | 统一 schema/viewClass |
| `Unknown component or parent kind: ${node.componentId}/$parentKind` | `UiPlan.kt` | Plan | 注册对应适配器 |
| `Component schema mismatch` | `UiPlan.kt` | Plan | schema 一致 |
| `Parent kind mismatch for ${node.key}` | `UiPlan.kt` | Plan | parentKind 一致 |
| `Component cannot have children` | `UiPlan.kt` | Plan | 有子节点需 childKind |
| `A bound plan can be mounted only once` | `UiPlan.kt` | Plan | 一个 BoundUiPlan 挂一次 |
| `Actual parent type mismatch` | `UiPlan.kt` | Plan | 实际父类型一致 |
| `Plan is closed` | `UiPlan.kt` | Plan | 关闭后不再取 ref |
| `Unknown plan path` | `UiPlan.kt` | Plan | 检查 path |
| `Component type mismatch` | `UiPlan.kt` | Plan | 类型一致 |
| `Owner thread required` | `LatestWork.kt`；`LatestTextWork.kt` | Recycler/Text | 切回 owner 线程 |
| `List controller closed` | `LatestWork.kt` | Recycler | 关闭后不再提交 |
| `Text controller closed` | `LatestTextWork.kt` | Text | 关闭后不再提交 |
| `Main looper rejected list result` | `UiListAdapter.kt` | Recycler | owner 存活期提交 |
| `Stable IDs must be unique and not NO_ID` | `UiListAdapter.kt` | Recycler | 修正 stableId |
| `Diff identity must agree with stable IDs` | `UiListAdapter.kt` | Recycler | ID 与 itemCallback 一致 |
| `Holder has not been bound` | `UiListAdapter.kt` | Recycler | bind 后再取 token |
| `Detach the adapter from every RecyclerView before close` | `UiListAdapter.kt` | Recycler | 先 detach 再 close |
| `Main thread required` | `UiListAdapter.kt` | Recycler | 切回主线程 |
| `Main looper rejected text result` | `PrecomputedTextBinding.kt` | Text | owner 存活期提交 |
| `Text update rejected: ${result.reason}` | `PrecomputedTextBinding.kt`、`:129`、`:137` | Text | 按 SubmitResult 处理 |
| `A row must allow between 1 and 32 children` | `NativeRow.kt` | Layouts | maxChildren 1..32 |
| `Native row child count exceeds its declared bound` | `NativeRow.kt` | Layouts | 子节点数不超限 |
| `A native row must remain horizontal` | `NativeRow.kt` | Layouts | 保持 HORIZONTAL |
| `GenerateUiFactory requires a class` | `UiFactoryProcessor.kt` | Codegen | 注解用于类 |
| `GenerateUiFactory requires a public top-level concrete non-generic View with a public single non-null Context primary constructor` | `UiFactoryProcessor.kt` | Codegen | 修正被注解类 |
| `GenerateUiFactory requires a nonempty stable id containing letters, digits, dot, colon, underscore or dash and a positive schema` | `UiFactoryProcessor.kt` | Codegen | 修正 id/schema |
| `GenerateUiFactory requires a conventional class name in a named package` | `UiFactoryProcessor.kt` | Codegen | 修正类名/包名 |

---

## ui-core · UiSession

### `UI operation requires the main thread`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`
- **触发**：`UiSession` 任何公开 API 在非主线程调用；`assertOwner()` 还被 `ViewRestoration`、`LumenUiBridge`、`PrecomputedTextBinding`、Ref 桥等复用。
- **排查**：打印 `Looper.myLooper()`；检查调用栈上的线程池/协程/静态初始化。
- **解决**：切回主线程；`requestClose()` 是唯一可从后台调用的关闭入口。

### `Session is not active`

- **位置**：`UiSession.kt`
- **触发**：`state != ACTIVE`、`closeRequested` 已置位，或 `scheduler.state != ACTIVE`。
- **排查**：检查 `session.state`、`session.scheduler.state`、`session.environment` 是否已不可用。
- **解决**：关闭会话不要复用；detach 后 `reattach()`；FAULTED 先 `recover()`。

### `Session disposed`

- **位置**：`UiSession.kt`
- **触发**：`close()` 后访问 `session.environment`。
- **解决**：不再复用已关闭会话。

### `Factory type differs from component`

- **位置**：`UiSession.kt`
- **触发**：`factory.create()` 返回的 View 不是 `component.viewClass` 的实例。
- **排查**：检查 `ComponentType` 与 `ViewFactory` 是否配对。
- **解决**：修正配对；不同控件类型不能按名称互换。

### `Factory returned an already parented View`

- **位置**：`UiSession.kt`
- **触发**：工厂返回的 View 已有 `parent`。
- **解决**：工厂必须返回无 parent 的新 View；复用已有 View 用 `borrow`。

### `View already managed`

- **位置**：`UiSession.kt`、`:89`
- **触发**：同一 View 实例已在会话内受管，又调用 `mount`（`:74`）或 `borrow`（`:89`）。
- **解决**：一个 View 只挂载/借用一次。

### `Duplicate root key: $key`

- **位置**：`UiSession.kt`
- **触发**：`borrow` 无 parent 的 View 时，已有同 key 的 root 节点。
- **排查**：`$key` 会插值为 `ViewKey`；搜索同 key 的 root。
- **解决**：换 key，或改用 `transfer`。

### `Explicitly detach before transferring; UI never steals an external child`

- **位置**：`UiSession.kt`
- **触发**：`transfer(view, ...)` 的 View 已有 parent。
- **解决**：先显式从原 parent 移除，再 `transfer`。

### `Managed <merge> requires an explicit ownership protocol`

- **位置**：`UiSession.kt`
- **触发**：`inflate` 的资源根节点是 `<merge>`。
- **解决**：改用具体根布局，或逐个 `mount` 子节点。

### `Inflater returned an already owned View`

- **位置**：`UiSession.kt`
- **触发**：inflate 得到的 View 已有 parent，或已被本会话管理。
- **排查**：检查 `inflater` 的 `attachToRoot` 与 parent 传参；检查是否重复 inflate 同一资源。
- **解决**：使用 `inflate(resource, parent, false)` 语义；每个 View 只 inflate 一次。

### `Duplicate key in parent: ${key.value}`

- **位置**：`UiSession.kt`
- **触发**：同一父 `ViewGroup` 下已有相同 `ViewKey`。
- **排查**：key 是父容器命名域；搜索同父下的重复 key；动态列表检查兄弟唯一性。
- **解决**：换 key，或先 `release` 旧节点。挂载失败会自动释放 key。

### `Session node identities exhausted; create a new session`

- **位置**：`UiSession.kt`
- **触发**：节点 identity 达到 `Long.MAX_VALUE`。
- **解决**：新建会话；检查是否有无限挂载不释放的泄漏。

### `View reference released`

- **位置**：`UiSession.kt`
- **触发**：节点或会话已释放仍解析 `ViewRef`。
- **解决**：不跨 `release`/`close` 持有 `ViewRef`；异步回调用 `BindingToken.isCurrent()` 复核。

### `Property already owned: ${property.id}`

- **位置**：`UiSession.kt`
- **触发**：同节点同属性 id 已有受管绑定/保留租约。
- **排查**：检查同 ref 上是否重复绑定同 id；是否与 `click`/`Lumen`/`PrecomputedText` 冲突。
- **解决**：一个属性一个 owner；先 `close` 旧绑定；用 `reserveProperties` 显式声明。

### `Conflicting visual property owner`

- **位置**：`UiSession.kt`
- **触发**：`reserveProperties` 的 ids 与已占用属性冲突。
- **常见调用点**：Lumen `"background"`、`click` 的 `"event:click"`、`PrecomputedTextBinding` 的 `"text"`。
- **解决**：先安装视觉 owner，再安装 UI 绑定；或换 id。

### `Invalidate the upstream resource authority`

- **位置**：`UiSession.kt`
- **触发**：`invalidateResources()` 时发现 `CreationEnvironment.resourceAuthority != null`（资源权威已交给上游）。
- **解决**：让上游资源权威失效（例如 Android 桥的 `ResourceScope.invalidateTheme()`），不要由 UI 自行递增同一资源域。

### `Owner looper rejected close`

- **位置**：`UiSession.kt`
- **触发**：`requestClose()` 从后台调用时，主线程 looper 拒绝投递。
- **解决**：确保 owner looper 存活；必要时重建会话。

---

## ui-core · Properties

### `Shared comparison requires a live getter`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`
- **触发**：`PropertySpec` 声明 `ownership = SHARED_READ` 但没有提供 `read`。
- **解决**：提供 `read`；或改用 `EXCLUSIVE`/`ALWAYS_WRITE`。

### `Binding closed`

- **位置**：`Properties.kt`（`submit`）、`:72`（`operation`）；`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt`
- **触发**：绑定关闭后仍 `submit`/`operation`。
- **解决**：重新绑定；不要跨 `release`/`rebind` 持有旧绑定。

---

## ui-core · UiScheduler 与帧驱动

### `A frame driver must declare its owner`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiScheduler.kt`
- **触发**：自定义 `UiFrameDriver.owner` 为空。
- **解决**：提供非空 `owner`，用于诊断。

### `Only a paused scheduler can resume`

- **位置**：`UiScheduler.kt`
- **触发**：对非 PAUSED 调度器调用 `resume()`。
- **排查**：先读 `scheduler.state`；`ACTIVE` 不需要 resume，`FAULTED` 需要 `recover()`，`CLOSED` 只能重建。
- **解决**：按状态选择 `resume()`/`recover()`/重建。

### `Only a faulted scheduler can recover`

- **位置**：`UiScheduler.kt`
- **触发**：对非 FAULTED 调度器调用 `recover()`。
- **解决**：先读 `scheduler.failure`，核对/重建真实状态后再 `recover()`。

### `UiFrameDriver must not invoke a requested callback synchronously`

- **位置**：`UiScheduler.kt`
- **触发**：自定义驱动在 `requestFrame` 内同步调用 callback。
- **解决**：驱动必须异步投递，且每次请求至多投递一次。

### `Nested frame delivery is not supported`

- **位置**：`UiScheduler.kt`
- **触发**：帧回调重入 `drain`（例如在回调里再次触发同一驱动的同步投递）。
- **解决**：避免重入；重入提交会进入下一帧。

### `estimated=false requires a platform deadline; use estimated=true for an advisory budget`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/UiFrameDriver.kt`
- **触发**：`FrameBudget(estimated = false)` 但 `deadlineNanos == null`。
- **解决**：要么提供真实 deadline，要么用 `estimated = true`。

### `Frame driver requires the main thread`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/ChoreographerFrameDriver.kt`、`:85`
- **触发**：`ChoreographerFrameDriver` 构造或 `requestFrame`/取消在非主线程。
- **解决**：切回主线程。

---

## ui-core · Registry

以下消息均来自 `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/extensions/Registry.kt`，在 `RegistrySnapshot.install` 或 `resolve` 时抛出。通用排查：打印 `ExtensionDescriptor` 的 `id`/`version`/`dependencies`/`overrides`/`priority` 与 `ComponentType` 的 `id`/`schema`/`viewClass`。

| 消息 | 位置 | 触发 | 解决 |
| --- | --- | --- | --- |
| `No provider for ${type.id}` | `Registry.kt` | resolve 的 `ComponentType.id` 未注册 | 注册对应 provider |
| `Component schema/type mismatch` | `Registry.kt` | 注册项与 resolve 类型的 schema/viewClass 不一致 | 统一 schema 与 viewClass |
| `Duplicate provider id` | `Registry.kt` | 注册列表有重复 `descriptor.id` | 去重 |
| `Extension dependency cycle` | `Registry.kt` | `dependencies` 形成环 | 解开依赖环 |
| `Missing dependency: $id` | `Registry.kt` | 依赖未注册 | 注册依赖 |
| `Unsupported SPI version` | `Registry.kt` | spiMajor/spiMinor 不是 1.0 | 使用 1.0 SPI |
| `Unsupported lifecycle/thread contract` | `Registry.kt` | thread 不是 `main` 或 lifetime 不是 `session` | 按合同声明 |
| `Missing capabilities for $id` | `Registry.kt` | 安装时未声明所需能力 | `install(..., capabilities = ...)` |
| `Invalid override` | `Registry.kt` | overrides 含未知 id 或自身 | 修正 overrides |
| `Ambiguous provider priority` | `Registry.kt` | 同 type 多 provider 优先级相同 | 优先级唯一 |
| `Provider conflict requires explicit override` | `Registry.kt` | 同 type 多 provider 但未声明覆盖 | 显式声明 override |
| `Override changes component contract` | `Registry.kt` | 被覆盖 provider 的 schema/viewClass 不同 | 保持组件契约一致 |

---

## ui-core · StateCodec 与 ViewRestoration

以下消息均来自 `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/state/`。通用排查：打印 `NodeIdentity(path, component, schema)`、`bundle.getInt("schema")`、`view.id` 与 `ids.snapshot()`。

| 消息 | 位置 | 触发 | 解决 |
| --- | --- | --- | --- |
| `Wrong state codec` | `StateCodec.kt` | codec id 与注册 id 不一致 | 使用匹配的 codec |
| `Stable IDs must be in the generated-ID range` | `StateCodec.kt` | 外部传入 ID 不在 `1..0x00ffffff` | 使用库分配 ID |
| `Duplicate native ID` | `StateCodec.kt` | 同快照内 ID 重复 | 清理重复 ID |
| `Duplicate stable path` | `StateCodec.kt` | 同快照内 path 重复 | path 唯一 |
| `Component type/schema changed; explicitly rebuild its saved identity` | `StateCodec.kt` | 恢复时组件类型/schema 变化 | 显式重建身份 |
| `Restored ID conflicts with native View` | `StateCodec.kt` | 恢复 ID 与原生树已有 ID 冲突 | 解决原生树冲突 |
| `Stable View IDs exhausted` | `StateCodec.kt` | 分配 ID 达到上限 | 减少登记数量 |
| `Unknown restoration schema` | `ViewRestoration.kt` | Bundle `schema != 1` | 明确拒绝或迁移 |
| `Restoration node limit exceeded` | `ViewRestoration.kt` | 节点数 > 10000 | 减少节点 |
| `Duplicate restoration node` | `ViewRestoration.kt` | Bundle 内 `NodeIdentity` 重复 | 清理重复节点 |
| `Duplicate restore path` | `ViewRestoration.kt` | 同快照内 `register` path 重复 | path 唯一 |
| `Explicit native ID must be managed by the caller's hierarchy restoration` | `ViewRestoration.kt` | View 已有原生 id 但与 stable id 映射不一致 | 由调用方 hierarchy restoration 管理，或用库分配 id |

---

## ui-widgets（源码候选，不在 v1.0.0）

### `openMeguvizUi must be called on the main thread`

- **位置**：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/UiScope.kt`
- **触发**：非主线程调用 `Activity.openMeguvizUi()`。
- **解决**：在 Activity 主线程方法中调用。

### `${javaClass.name} is destroyed`

- **位置**：`UiScope.kt`
- **触发**：Activity 已 destroyed 仍调用 `openMeguvizUi()`。
- **解决**：在 Activity 存活期调用；已销毁时不要打开新会话。

---

## ui-android-bridge（未发布到 Central）

### `Frame driver closed or close listener already owned`

- **位置**：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidFrameDriver.kt`
- **触发**：驱动已关闭，或重复调用 `onClose`。
- **解决**：配对一次 `driver.onClose(uiSession::close)` 与 `uiSession.onClose(driver::close)`。

### `Android frame channel closed`

- **位置**：`AndroidFrameDriver.kt`
- **触发**：上游通道已关闭仍 `requestFrame`。
- **解决**：关闭后不再请求；检查上游 `FrameChannel.onClose` 是否已触发。

### `A UI session may have only one pending frame request`

- **位置**：`AndroidFrameDriver.kt`
- **触发**：已有 pending 请求时再次 `requestFrame`。
- **排查**：正常路径由 `UiScheduler` 去重；手工装配驱动时才可能触发。
- **解决**：一个会话一张 pending 票据；需要独立通道时用 `AndroidUiBinding.open`。

### `Window driver closed or frame already pending`

- **位置**：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidWindowFrameDriver.kt`
- **触发**：驱动已关闭或已有 pending 请求。
- **解决**：通过 `UiSession` 提交；关闭顺序先 binding 再窗口/scheduler。

### 文档前置条件（非异常消息）

- `The supplied root must not already be owned by another ViewScope in this window.` — `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt`
- 这是 `AndroidUiBinding.open` 的 KDoc 约束；违反时由上游 `window.scope(root)` 拒绝注册，异常消息来自 MeguvizAndroid，不在本仓库。

---

## ui-lumen-bridge（未发布到 Central）

### `Lumen bridge closed`

- **位置**：`ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt`
- **触发**：桥关闭后仍 `bind`。
- **解决**：关闭后不再绑定；重新创建桥。

### `Surface registration closed`

- **位置**：`LumenUiBridge.kt`
- **触发**：`SurfaceRegistration.update` 在注册关闭后调用。
- **解决**：关闭后不再 update。

---

## ui-ref-bridge（1.0.0 已发布；1.2.0 源码对应上游 1.2.0）

### `CodeScope must be open`

- **位置**：`ui-ref-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/ref/RefBindingSession.kt`
- **触发**：`RefBindingSession` 构造时 `CodeScope.state() != OPEN`。
- **解决**：在 CodeScope 打开时创建；关闭后重新创建。

### `Ref binding session is closed`

- **位置**：`RefBindingSession.kt`
- **触发**：绑定会话关闭后调用 `activeScope` 等内部入口。
- **解决**：关闭后不再使用。

### `UI session is not active`

- **位置**：`RefBindingSession.kt`
- **触发**：`currentUi` 返回 false（会话非 ACTIVE）或 codeScope 已变化。
- **排查**：检查 `ui.state` 与 `ui.scheduler.state`。
- **解决**：确认会话存活；关闭后重建绑定会话。

### `The borrowed CodeScope identity or epoch changed`

- **位置**：`RefBindingSession.kt`（`StalePlanException`）
- **触发**：借用的 `CodeScope` identity 或 code epoch 在会话存活期发生变化。
- **解决**：重建 `RefBindingSession` 与相关 plan/lease；不要跨 CodeScope 重建持有旧 lease。

### `Dynamic factory type must belong to this CodeScope's actual loader`

- **位置**：`RefBindingSession.kt`
- **触发**：`viewFactory(type)` 的 `type.classLoader` 不是该 CodeScope 实际使用的 loader。
- **解决**：使用该 CodeScope 的 loader 可加载的类型。

### `Use attach for View bindings`

- **位置**：`RefBindingSession.kt`
- **触发**：未通过 `RefBindingSession.attach(ui, code)` 装配就调用 `bindView`。
- **解决**：先 `attach`，再 `bindView`。

### `Dynamic factory is closed`

- **位置**：`RefBindingSession.kt`
- **触发**：`RefViewFactory.create` 在工厂关闭后调用。
- **解决**：关闭后不再用该工厂创建 View。

### `Receiver lease is closed`

- **位置**：`RefBindingSession.kt`、`:178`
- **触发**：receiver lease 关闭后调用 `value`。
- **解决**：关闭后不再取值。

### `View binding token is stale`

- **位置**：`RefBindingSession.kt`
- **触发**：lease 的 validity（`BindingToken.isCurrent()`）返回 false。
- **解决**：重新获取 lease；检查会话/theme/resource/attach 代际。

### `Method handle is closed`

- **位置**：`RefBindingSession.kt`
- **触发**：`RefMethodHandle.invoke` 在句柄关闭后调用。
- **解决**：关闭后不再调用。

---

## ui-frame-shared（源码候选，不在 v1.0.0）

### `Shared frame driver closed`

- **位置**：`ui-frame-shared/src/main/kotlin/io/github/jichuo1/meguviz/ui/frames/SharedFrameDriver.kt`
- **触发**：驱动关闭后仍 `requestFrame`。
- **解决**：关闭后不再请求。

### `Shared frame driver client capacity exceeded`

- **位置**：`SharedFrameDriver.kt`
- **触发**：待处理请求数达到 `MAX_CLIENTS = 256`（`SharedFrameDriver.kt`）。
- **排查**：是否有 client 泄漏（只 requestFrame 不 close/取消）。
- **解决**：配对 `close()`；减少并发 client。

### `Shared frame driver request identities exhausted`

- **位置**：`SharedFrameDriver.kt`
- **触发**：请求 id 达到 `Long.MAX_VALUE`。
- **解决**：重建驱动。

### `FrameSource must deliver asynchronously and at most once per request`

- **位置**：`SharedFrameDriver.kt`
- **触发**：自定义 `FrameSource` 同步投递，或同一请求投递多次。
- **解决**：修正 `FrameSource` 契约：异步且每次请求至多一次。

### `Shared frame driver requires the main thread`

- **位置**：`SharedFrameDriver.kt`
- **触发**：驱动方法在非主线程调用。
- **解决**：切回主线程。

### `Frame source requires the main thread`

- **位置**：`ui-frame-shared/src/main/kotlin/io/github/jichuo1/meguviz/ui/frames/ChoreographerFrameSource.kt`、`:90`
- **触发**：`ChoreographerFrameSource` 构造或请求在非主线程。
- **解决**：切回主线程。

---

## ui-plan

以下消息均来自 `ui-plan/src/main/kotlin/io/github/jichuo1/meguviz/ui/plan/UiPlan.kt`。通用排查：打印 `UiPlan.schemaMajor/schemaMinor/rootKind`、`PlanNode.key/componentId/componentSchema/width/height/children`、`PlanComponent.parentKind/childKind/type`。

| 消息 | 位置 | 触发 | 解决 |
| --- | --- | --- | --- |
| `Unsupported plan schema` | `UiPlan.kt` | schema 不是 1.0 | 使用 1.0 |
| `Plan depth exceeds limit` | `UiPlan.kt` | 深度超过 `maxDepth`（默认 64） | 降低深度 |
| `Empty or duplicate sibling key: ${node.key}` | `UiPlan.kt` | 同层 key 为空或重复 | key 唯一非空 |
| `Invalid component identity` | `UiPlan.kt` | componentId 为空或 schema ≤ 0 | 修正身份 |
| `Invalid width` | `UiPlan.kt` | width 不在 `-2/-1/≥0` | 修正 width |
| `Invalid height` | `UiPlan.kt` | height 不在 `-2/-1/≥0` | 修正 height |
| `Plan size exceeds limit` | `UiPlan.kt` | 节点数超过 `maxNodes`（默认 4096） | 减少节点 |
| `Children require a ViewGroup` | `UiPlan.kt` | 声明 childKind 但类型不是 ViewGroup | 改用 ViewGroup |
| `Parent type mismatch` | `UiPlan.kt` | `parentClass.cast(parent)` 失败 | 父类型一致 |
| `Root parent kind mismatch` | `UiPlan.kt` | bind 的 rootKind 与 plan 不一致 | rootKind 一致 |
| `Root parent type mismatch` | `UiPlan.kt` | root 不被根组件接受 | root 类型一致 |
| `Duplicate plan component and parent kind` | `UiPlan.kt` | 同 type.id+parentKind 重复注册 | 去重 |
| `Component identity differs across parent kinds` | `UiPlan.kt` | 同 type.id 下 schema/viewClass 不一致 | 统一契约 |
| `Unknown component or parent kind: ${node.componentId}/$parentKind` | `UiPlan.kt` | 节点没有对应适配器 | 注册适配器 |
| `Component schema mismatch` | `UiPlan.kt` | 节点 schema 与适配器不一致 | schema 一致 |
| `Parent kind mismatch for ${node.key}` | `UiPlan.kt` | 节点父种类与适配器不一致 | parentKind 一致 |
| `Component cannot have children` | `UiPlan.kt` | 节点有子但适配器无 childKind | 注册可承载子节点的适配器 |
| `A bound plan can be mounted only once` | `UiPlan.kt` | 同一 `BoundUiPlan` 多次 `mount()` | 一个 plan 只挂一次 |
| `Actual parent type mismatch` | `UiPlan.kt` | 挂载时实际父类型不被接受 | 父类型一致 |
| `Plan is closed` | `UiPlan.kt` | `MountedUiPlan.close()` 后调用 `ref` | 关闭后不再取 ref |
| `Unknown plan path` | `UiPlan.kt` | path 不存在 | 检查 path |
| `Component type mismatch` | `UiPlan.kt` | ref 的组件类型与请求类型不一致 | 类型一致 |

---

## ui-recycler

### `Owner thread required`

- **位置**：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/LatestWork.kt`
- **触发**：`submit` 不在创建 `LatestWork` 的 owner 线程。
- **解决**：切回 owner（主）线程。

### `List controller closed`

- **位置**：`LatestWork.kt`
- **触发**：`LatestWork.close()` 后仍 `submit`。
- **解决**：关闭后不再提交；检查 adapter 是否已 close。

### `Main looper rejected list result`

- **位置**：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/UiListAdapter.kt`
- **触发**：后台结果回投时主 looper 拒绝。
- **解决**：owner 存活期提交；关闭后不再提交。

### `Stable IDs must be unique and not NO_ID`

- **位置**：`UiListAdapter.kt`
- **触发**：`stableId` 返回 `RecyclerView.NO_ID`，或同批列表有重复 ID。
- **解决**：每个 item 返回唯一且非 `NO_ID` 的稳定 ID；ID 在后台 diff 时校验，失败走 `onDiffFailure`。

### `Diff identity must agree with stable IDs`

- **位置**：`UiListAdapter.kt`
- **触发**：`itemCallback.areItemsTheSame` 的判断与稳定 ID 是否相同不一致。
- **解决**：`areItemsTheSame` 必须与稳定 ID 语义一致。

### `Holder has not been bound`

- **位置**：`UiListAdapter.kt`
- **触发**：`bindingToken(holder)` 在该 holder 绑定前调用。
- **解决**：在 `bindFull`/`bindPartial` 之后再取 token。

### `Detach the adapter from every RecyclerView before close`

- **位置**：`UiListAdapter.kt`
- **触发**：adapter 仍 attach 在 RecyclerView 时调用 `close()`。
- **解决**：先从每个 RecyclerView detach，再 close。

### `Main thread required`

- **位置**：`UiListAdapter.kt`
- **触发**：adapter 的公开方法在非主线程调用。
- **解决**：切回主线程。

---

## ui-text

### `Owner thread required`

- **位置**：`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/LatestTextWork.kt`
- **触发**：`submit` 不在 owner 线程。
- **解决**：切回主线程。

### `Text controller closed`

- **位置**：`LatestTextWork.kt`
- **触发**：`close()` 后仍 `submit`。
- **解决**：关闭后不再提交。

### `Main looper rejected text result`

- **位置**：`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt`
- **触发**：后台结果回投时主 looper 拒绝。
- **解决**：owner 存活期提交。

### `Binding closed`

- **位置**：`PrecomputedTextBinding.kt`
- **触发**：绑定关闭后仍 `submit`。
- **解决**：重新绑定。

### `Text update rejected: ${result.reason}`

- **位置**：`PrecomputedTextBinding.kt`、`:129`、`:137`（`IllegalStateException`，交给 `onFailure`）
- **触发**：文本更新被调度器拒绝（`SubmitResult.Rejected`），原因见 `RejectionReason`（PAUSED/FAULTED/CLOSED/STATE_CAPACITY/COMMAND_CAPACITY/EMPTY_BATCH）。
- **排查**：检查 `scheduler.state` 与容量；不要忽略 `onFailure`。
- **解决**：处理背压；确认会话存活。

---

## ui-layouts

### `A row must allow between 1 and 32 children`

- **位置**：`ui-layouts/src/main/kotlin/io/github/jichuo1/meguviz/ui/layouts/NativeRow.kt`
- **触发**：`NativeRowConstraints(maxChildren)` 不在 `1..32`。
- **解决**：调整 `maxChildren`。

### `Native row child count exceeds its declared bound`

- **位置**：`NativeRow.kt`
- **触发**：`nativeRow` 的 children 数量超过 `constraints.maxChildren`。
- **解决**：减少子节点或提高 `maxChildren`（不超过 32）。

### `A native row must remain horizontal`

- **位置**：`NativeRow.kt`
- **触发**：`configure` 把 `orientation` 改成非 `HORIZONTAL`。
- **解决**：保持横向；需要纵向请用 `linear`。

---

## ui-codegen（KSP 编译期诊断）

以下消息由 KSP 处理器通过 `logger.error` 输出（`ui-codegen/src/main/kotlin/io/github/jichuo1/meguviz/ui/codegen/processor/UiFactoryProcessor.kt`），不是运行时异常。

| 消息 | 位置 | 触发 | 解决 |
| --- | --- | --- | --- |
| `GenerateUiFactory requires a class` | `UiFactoryProcessor.kt` | 注解目标不是类 | 用于类 |
| `GenerateUiFactory requires a public top-level concrete non-generic View with a public single non-null Context primary constructor` | `UiFactoryProcessor.kt` | 被注解类不满足构造条件 | 修正类声明 |
| `GenerateUiFactory requires a nonempty stable id containing letters, digits, dot, colon, underscore or dash and a positive schema` | `UiFactoryProcessor.kt` | id/schema 非法 | 修正 id 与 schema |
| `GenerateUiFactory requires a conventional class name in a named package` | `UiFactoryProcessor.kt` | 类名或包名不符合约定 | 修正命名 |

---

## 内部不变量（通常由误用触发，无用户消息）

这些 `check`/`error` 用于锁定内部状态，正常公开 API 不应触发。

| 消息 | 位置 | 说明 |
| --- | --- | --- |
| `group table is full` | `UiScheduler.kt` | 分组表超过负载，正常公开路径不可达 |
| `group table exceeded its bound` | `UiScheduler.kt` | 同上 |
| `unexpected command entry: ${entry::class.java.name}` | `UiScheduler.kt`、`:727` | 命令队列混入非预期类型，公开 API 不可达 |
| `Resolved component is not a ViewGroup` | `UiPlan.kt` | 有子节点的组件解析后不是 ViewGroup |
| `Owner dispatcher failed; controller is closed` | `LatestWork.kt`、`LatestTextWork.kt` | owner 投递失败，控制器已关闭 |
| `Background executor must run off the owner thread` | `LatestWork.kt`、`LatestTextWork.kt` | `background` Executor 在 owner 线程执行 |
| `Owner executor ran on the wrong thread` | `LatestWork.kt`、`LatestTextWork.kt` | `owner` Executor 不在 owner 线程执行 |

无消息的 `check`（抛无消息 `IllegalStateException`）：

- `UiSession.kt`：`check(ref.session === this)` — 用其他会话的 `ViewRef` 解析。
- `UiSession.kt`：`check(state != SessionState.DISPOSED)` — dispose 后 detach。
- `UiSession.kt`：`check(state == SessionState.DETACHED)` — 非 DETACHED 时 reattach。
- `UiSession.kt`：`check(ref.session === this)` — release 其他会话的 ref。
- `AndroidWindowFrameDriver.kt`：`check(!closed && closeAction == null)` — 重复注册 onClose。
- `UiPrewarmer.kt`：`check(queue.removeFirst() === entry)` — 内部队列一致性。

此外，构造参数校验大量使用无消息 `require`（空字符串、非正数等），例如 `ComponentType` 的 `require(id.isNotBlank())`/`require(schema > 0)`（`Identity.kt`）、`ViewKey` 的 `require(value.isNotBlank())`（`Identity.kt`）、`PropertySpec` 的 `require(id.isNotBlank())`（`Properties.kt`）、`UiScheduler` 的容量/预算校验（`UiScheduler.kt`）、`UiPrewarmer` 的容量校验（`UiPrewarmer.kt`）、`PrecomputedTextBinding` 的 `require(minimumLength > 0)`（`PrecomputedTextBinding.kt`）。遇到无消息的 `IllegalArgumentException` 时，优先检查这些构造参数。

---

## 上游异常（不在本仓库）

以下调用点会把上游库的异常直接抛给调用者；消息文本由上游库定义，需查对应库文档。

| 调用点 | 位置 | 上游异常来源 |
| --- | --- | --- |
| `AndroidUiBinding.open` | `AndroidUiBinding.kt` | `WindowSession.requirePermission` 权限不足；`window.scope(root)` 重复注册 |
| `LumenUiBridge.bind` | `LumenUiBridge.kt` | `LumenSurfaceSession.bind` |
| `RefBindingSession.viewFactory` / `method` | `RefBindingSession.kt`、`:184` | `CodeScope.resolve(...).requireSingle()`、`bindConstructor`、`bindMethod` |
| `RefBindingSession.activeScope` | `RefBindingSession.kt` | 本仓库抛 `StalePlanException`（消息见上） |
| `SharedFrameDriver.requestFrame` | `SharedFrameDriver.kt` | 自定义 `FrameSource.requestFrame` 抛错（驱动会回滚本次请求） |

---

## 相关文档

- [故障排查 FAQ](faq.md)
- [核心合同](../contracts.md)、[调度与帧所有权](../guides/scheduler.md)、[生命周期与失败恢复](../quickstart/03-lifecycle-and-close.md)
- [属性绑定与 PropertySpec](../guides/properties.md)、[调度器、帧驱动与背压](../guides/scheduler.md)
- [状态保存与恢复](../quickstart/05-restoration.md)、[可选桥](../bridges.md)
