# 故障排查 FAQ

本文按「现象 → 原因 → 排查 → 解决」处理 MeguvizUI 自身抛出的异常。完整的逐字消息清单见[错误目录](error-catalog.md)。

**口径**

- 只覆盖本仓库源码中的异常。MeguvizAndroid、MeguvizDrawable、Lumen 等上游库抛出的消息不在本仓库，本文只标注调用点与排查方向。
- 消息均为源码逐字引用；源码 1.2.0 尚未发布，标注「源码候选」的行为以当前源码为准。
- 单元测试通过不等于设备行为已验收；涉及真实 View、Choreographer、窗口与 OEM 的结论需要设备证据。

---

## 1. 主线程

### Q: 调用会话 API 崩溃：`UI operation requires the main thread`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:59`
- **触发**：`UiSession` 的任何公开 API（`mount`/`borrow`/`transfer`/`inflate`/`bind`/`release`/`detach`/`reattach`/`click`/`close` 等）在非主线程调用。`UiSession` 构造时也要求主线程（`UiSession.kt:57`）。
- **排查**：
  1. 确认调用线程：`Looper.myLooper() === Looper.getMainLooper()`。
  2. 检查是否从 `Executor`、线程池、协程 `Dispatchers.Default/IO`、`Thread {}` 或静态初始化器直接调用。
  3. 检查单元测试是否没有主线程 `Looper`（Instrumentation 或 Robolectric 之外）。
- **解决**：切回主线程。`Handler(Looper.getMainLooper()).post { ... }`、`Activity.runOnUiThread { ... }`、协程 `withContext(Dispatchers.Main) { ... }`。后台只计算纯数据，再把结果投递回主线程。
- **例外**：`UiSession.requestClose()` 可从后台调用（`UiSession.kt:261-269`），内部 post 到主线程；返回的 `CloseRequest.completed` 才表示 owner 已实际清理。其他会话 API 没有跨线程入口（`state`/`scheduler` 属性本身可读，但调度器方法仍要求主线程）。

### Q: 构造驱动或提交帧时：`Frame driver requires the main thread` / `Frame source requires the main thread`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/scheduler/ChoreographerFrameDriver.kt:46`、`:85`；`ui-frame-shared/src/main/kotlin/io/github/jichuo1/meguviz/ui/frames/ChoreographerFrameSource.kt:54`、`:90`（源码候选）
- **触发**：`ChoreographerFrameDriver` 构造或 `requestFrame`、`SharedFrameDriver`/`ChoreographerFrameSource` 在非主线程调用。
- **排查**：同上一问；注意 `UiSession(environment, driver)` 的 driver 也在主线程构造。
- **解决**：在主线程创建会话与驱动。

### Q: P2 模块报 `Main thread required` / `Owner thread required`

- **位置**：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/UiListAdapter.kt:169`（`Main thread required`）；`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/LatestWork.kt:22`、`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/LatestTextWork.kt:22`（`Owner thread required`）
- **触发**：`UiListAdapter` 的 `submitList`/`bind`/生命周期回调，或 `LatestWork`/`LatestTextWork` 的提交不在创建它们的 owner 线程。
- **排查**：确认 `background` Executor 只做计算；`submitList` 必须在主线程调用。
- **解决**：把提交移回主线程；后台结果通过 `owner` Executor 投递。

### Q: 源码候选 `openMeguvizUi must be called on the main thread`

- **位置**：`ui-widgets/src/main/kotlin/io/github/jichuo1/meguviz/ui/widgets/UiScope.kt:69`（未提交源码候选，不在 1.0.0）
- **触发**：在非主线程调用 `Activity.openMeguvizUi()`。
- **解决**：在 Activity 主线程方法（如 `onCreate`）中调用。

### Q: `Main looper rejected list result` / `Main looper rejected text result`

- **位置**：`ui-recycler/src/main/kotlin/io/github/jichuo1/meguviz/ui/recycler/UiListAdapter.kt:52`；`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt:58`
- **触发**：后台计算完成后，向主线程 `Handler.post` 投递结果时被拒绝（looper 正在退出或已退出）。
- **排查**：通常意味着 Activity/窗口正在销毁，或进程处于异常状态；检查是否仍在 `onDestroy` 之后提交。
- **解决**：确保在 owner 存活期间提交；在 `close()` 后不再提交（`List controller closed` / `Text controller closed`）。

---

## 2. key 冲突

### Q: `Duplicate key in parent: ${key.value}`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:140`
- **触发**：同一个父 `ViewGroup` 下已经存在相同 `ViewKey` 的受管节点。key 的命名域是**父容器**，不是全局。
- **排查**：
  1. 找到消息里的 key 值，搜索同一父容器下的重复 `ViewKey(...)`。
  2. 动态列表场景：确认同一时刻兄弟节点 key 唯一；item 移动/删除后 key 是否被错误复用。
  3. 检查是否有两次挂载用了同一个 key（例如 `onCreate` 与恢复流程各挂一次）。
- **解决**：换 key，或先 `session.release(oldRef)` 再挂载同 key。挂载失败时 key 会被自动释放（`UiSession.kt:80-84`、`:108-112`、`:132-136`），不需要手动清理半成功状态。

### Q: `Duplicate root key: $key`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:93`
- **触发**：`borrow(view, key, component)` 借用一个**没有 parent** 的 View 时，会话里已经存在同 key 的 root 节点。
- **排查**：检查是否重复 borrow 同一个 root，或 root key 与某个子节点 key 撞名。
- **解决**：换 key；或改用 `transfer`（要求 View 无 parent，见 `UiSession.kt:97-103`）。

### Q: `Session node identities exhausted; create a new session`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:145`
- **触发**：会话内节点 identity 自增到 `Long.MAX_VALUE`。正常应用不可能达到；出现说明会话被极端滥用或存在未释放的节点泄漏。
- **排查**：检查是否在一个长生命周期的会话里无限挂载而不 release。
- **解决**：新建会话；不要在一个会话里承载无限增长的节点。

### Q: 释放后能复用 key 吗？

可以。`release` 会撤销 key 占用（`UiSession.kt:250-252`），`rebind` 只提升 binding 代际、不改变 key。key 只在节点存活期间保留。

---

## 3. 属性冲突

### Q: `Property already owned: ${property.id}`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:177`
- **触发**：同一节点上，相同属性 id 已经存在受管绑定或保留租约，再次 `session.bind(ref, spec)`。
- **排查**：
  1. 检查同一 `ViewRef` 是否被绑定了两个相同 id 的 `PropertySpec`（例如两次 `Properties.plainText()`，id 都是 `"text"`）。
  2. 检查是否同时使用 `Properties.plainText()` 和 `PrecomputedTextBinding`（后者保留 `"text"`，见 `PrecomputedTextBinding.kt:64`）。
  3. 检查是否与 Lumen 桥的 `"background"` 保留冲突。
- **解决**：一个属性只保留一个 owner。先 `close()` 旧绑定再绑新的；或用 `reserveProperties` 显式声明归属。

### Q: `Conflicting visual property owner`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:187`
- **触发**：`reserveProperties(ref, ids)` 要保留的 id 中，已有任意一个被别的绑定/租约占用。
- **常见调用点**：
  - `LumenUiBridge.bind` 保留 `"background"`（`ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt:19`）
  - `UiSession.click` 保留 `"event:click"`（`UiSession.kt:200`）
  - `PrecomputedTextBinding` 保留 `"text"`（`PrecomputedTextBinding.kt:64`）
- **排查**：确认同一节点上是否同时安装了上述入口；`background` 只能由 Lumen 或 UI 一方写。
- **解决**：先安装视觉 owner（Lumen/动画），再安装 UI 绑定；或换属性 id。`ALWAYS_WRITE` 只是比较策略，不是绕过其他 owner 的权限。

### Q: `Binding closed`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt:59`、`:72`；`ui-text/src/main/kotlin/io/github/jichuo1/meguviz/ui/text/PrecomputedTextBinding.kt:75`
- **触发**：`PropertyBinding.submit` / `operation` / `PrecomputedTextBinding.submit` 在绑定关闭后调用。
- **排查**：检查绑定句柄是否被提前 `close()`，或节点/会话释放后仍持有旧绑定。
- **解决**：重新绑定；不要跨 `release`/`rebind` 持有旧 `PropertyBinding`。

### Q: 哪些入口会占用属性槽？

| 入口 | 占用的 id | 源码 |
| --- | --- | --- |
| `session.bind(ref, spec)` | `spec.id` | `UiSession.kt:177` |
| `session.reserveProperties(ref, ids)` | 调用方给出的 ids | `UiSession.kt:184-193` |
| `session.click(ref, action)` | `"event:click"` | `UiSession.kt:200` |
| `LumenUiBridge.bind` | `"background"` | `LumenUiBridge.kt:19` |
| `PrecomputedTextBinding` 构造 | `"text"` | `PrecomputedTextBinding.kt:64` |

---

## 4. FAULTED 与调度器状态

### Q: 提交被拒绝，或调度器进入 FAULTED

- **相关消息/枚举**：`SubmitResult.Rejected(FAULTED)`（`UiScheduler.kt:7`、`:469-474`）；`Only a faulted scheduler can recover`（`UiScheduler.kt:381`）；`Only a paused scheduler can resume`（`UiScheduler.kt:373`）
- **触发**：`isValid` 或 action 抛异常（`UiScheduler.kt:451-457`、`:734-755`），或帧请求失败（`:499-501`）。fault 会记录 `SchedulerFailure` 并清空队列（`:757-761`）。
- **排查**：
  1. 读取 `session.scheduler.failure`（`SchedulerFailure`，字段见 `UiScheduler.kt:25-32`）：`operation`（标签）、`operationIndex`、`appliedInBatch`、`phase`、`exceptionType`、`message`。
  2. `phase` 取值：`"validation"`（isValid 抛错）、`"action"`（action 抛错）、`"driver"`（帧请求失败）。
  3. 看日志里该异常的原始堆栈；`SchedulerFailure` 只保存类型名与 message，不持有 Throwable。
- **解决**：
  1. 先让**应用模型与真实 View 状态一致**（读回实际值、释放/重建受控范围）。
  2. 再调用 `scheduler.recover()`。`recover()` 不重试失败操作（`UiScheduler.kt:378-384`）。
  3. 完整恢复路径是关闭并重建会话。

### Q: detach/reattach 能清除 FAULTED 吗？

不能。`detach()` 暂停调度器并清队列（`UiSession.kt:219-223`），`reattach()` 只恢复 PAUSED（`:224`）；两者都不会清除 FAULTED。Android 桥在 attach 时只对 PAUSED 调 `reattach`（`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt:38`），FAULTED 需要显式 `recover()` 或重建。

### Q: Android 桥 attach 后不恢复？

如果 attach 时会话处于 `DETACHED` 且调度器为 `PAUSED`，桥会自动 `reattach()`（`AndroidUiBinding.kt:32-39`）。如果调度器是 FAULTED，桥不会自动恢复；先 `recover()`，或重建会话。

### Q: 如何避免 fault？

- `isValid` 只做纯校验，不抛异常、不做副作用。
- action 里避免调用可能抛错的外部代码；业务校验放提交前。
- 自定义驱动必须异步投递且每次请求至多投递一次，否则 `UiFrameDriver must not invoke a requested callback synchronously`（`UiScheduler.kt:492`）或 `Nested frame delivery is not supported`（`:508`）会进入 fault。

---

## 5. 状态恢复

### Q: `Unknown restoration schema`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/state/ViewRestoration.kt:33`
- **触发**：`RestorationSnapshot.fromBundle(bundle)` 读到的 `schema` 不是 `1`（当前唯一版本，写入见 `ViewRestoration.kt:19`）。
- **排查**：Bundle 是否来自旧版本或外部数据；是否手工改过 schema。
- **解决**：明确拒绝或实现显式迁移；不要强行解析未知 schema。

### Q: `Duplicate restoration node` / `Duplicate restore path`

- **位置**：`ViewRestoration.kt:40`（Bundle 内重复 `NodeIdentity`）、`:68`（同一快照内重复 path）
- **触发**：保存的 Bundle 有重复节点；或 `register(path, ref, root)` 被同一 path 调用两次。
- **排查**：`NodeIdentity` 由 `(path, component.id, component.schema)` 组成（`StateCodec.kt:33`）；检查是否同一逻辑节点用了不同 path，或不同节点用了同 path。
- **解决**：path 在同一快照内唯一；清理旧快照或升级 schema。

### Q: `Explicit native ID must be managed by the caller's hierarchy restoration`

- **位置**：`ViewRestoration.kt:76`
- **触发**：View 已经有原生 `id`（非 `View.NO_ID`），但它与保存的 stable id 映射不一致。库只接受「由调用方 hierarchy restoration 管理的原生 id」。
- **排查**：检查是否同时在用 `onSaveInstanceState`/`onRestoreInstanceState` 的原生 id 分配，又用 `ViewRestoration` 分配 stable id。
- **解决**：二选一。要么让调用方的 hierarchy restoration 完全管理原生 id，要么让 `ViewRestoration` 通过 `view.id = ids.idFor(...)` 分配（`ViewRestoration.kt:77`）。

### Q: `Restoration node limit exceeded`

- **位置**：`ViewRestoration.kt:37`
- **触发**：Bundle 内节点数超过 10000。
- **解决**：减少登记数量，或拆分快照。

### Q: 恢复不生效 / 被撤销

- **原因 1**：在 `register`/`restoreAfterLayout` 之后又调用了 `userInteracted()`。`userInteracted()` 会递增交互代际并取消待恢复（`ViewRestoration.kt:63`）；`restoreAfterLayout` 只在代际一致时恢复（`:104-118`）。输入控制器应在记录新意图前调用它。
- **原因 2**：`restoreAfterLayout(root)` 只在 `root.isLaidOut` 后触发一次（`:104-106`）；布局未完成时不会恢复。
- **原因 3**：`ref.token(resources = true)` 已失效（theme/resource/attach 代际变化），恢复被跳过（`:107-108`）。
- **原因 4**：保存的是 `ViewPresentation`（focus/scroll/selection），不包含文本内容；文本必须来自应用权威模型（`ViewRestoration.kt:52` 注释、`docs/state-codec.md`）。

### Q: 正确的恢复顺序

1. 应用先恢复权威模型（包括编辑框文本）。
2. 构建节点并挂载。
3. `ViewRestoration.register(path, ref, root)` 登记稳定 id。
4. `restoreAfterLayout(root)`，布局后恢复焦点、选择、滚动。
5. 输入控制器在用户交互时调用 `userInteracted()` 撤销旧恢复。

详见 [状态保存与恢复](../quickstart/05-restoration.md)。

---

## 6. 窗口权限与 Android 桥

### Q: `AndroidUiBinding.open` 抛权限错误

- **位置**：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt:72`
- **触发**：`open` 首先调用 `window.requirePermission(WindowSession.Permission.APPLY_LOCAL)`；窗口权限不足时由上游抛错。**上游异常消息不在本仓库**，需查 MeguvizAndroid 的 `WindowSession` 文档。
- **排查**：确认 `WindowSession.open(...)` 时传入的权限包含 `APPLY_LOCAL`；确认窗口 owner 与 dispatcher 一致。
- **解决**：用具备 `APPLY_LOCAL` 权限的 `WindowSession`。

### Q: root 已被占用

- **文档前置条件**（非异常消息）：`The supplied root must not already be owned by another ViewScope in this window.` — `AndroidUiBinding.kt:70`
- **触发**：同一个 root 已被同一窗口内另一个 `ViewScope` 接管；上游拒绝重复注册。
- **排查**：检查是否对同一 root 调用了两次 `AndroidUiBinding.open`，或 root 已被其他 `ViewScope` own。
- **解决**：一个 root 对应一个 `ViewScope`/`AndroidUiBinding`；先关闭旧 binding。

### Q: `Window driver closed or frame already pending`

- **位置**：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidWindowFrameDriver.kt:53`
- **触发**：驱动已关闭，或同一时刻已有 pending 帧请求。会话内帧请求由调度器保证至多一个；出现该消息通常意味着驱动被重复请求或已关闭。
- **排查**：检查是否绕过 `UiSession` 直接调用驱动；是否关闭后仍提交。
- **解决**：通过 `UiSession` 提交；关闭顺序必须是先 binding，再窗口/scheduler。

### Q: `A UI session may have only one pending frame request`

- **位置**：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidFrameDriver.kt:45`
- **触发**：`AndroidFrameDriver.requestFrame` 被调用时已有 pending 请求。
- **排查**：检查是否手工装配了驱动并重复 `requestFrame`；正常路径由调度器去重。
- **解决**：一个会话一张 pending 帧票据；需要独立通道时用 `AndroidUiBinding.open` 或正确配对 `onClose`。

### Q: `Frame driver closed or close listener already owned` / `Android frame channel closed`

- **位置**：`AndroidFrameDriver.kt:38`、`:44`
- **触发**：驱动关闭后仍操作；或重复注册 `onClose`。
- **解决**：配对一次 `driver.onClose(uiSession::close)` 与 `uiSession.onClose(driver::close)`；不要重复注册。

### Q: 关闭顺序

**先关 binding，再关共享窗口或 scheduler。** `WindowFrameChannel` 没有公开 `onClose/isClosed`，桥无法及时观察「独立关闭共享 scheduler 但保持 window 活着」的异常顺序。详见 [Android 桥（消费者视角）](../guides/bridges-android.md)。

---

## 7. 生命周期

### Q: `Session is not active`

- **位置**：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt:60`
- **触发**：会话 `state != ACTIVE`、`closeRequested` 已置位，或 `scheduler.state != ACTIVE`（PAUSED/FAULTED/CLOSED）时调用 `requireActive()` 的 API。
- **排查**：检查是否 close/detach 后仍提交；是否调度器 FAULTED；是否 `requestClose()` 已受理但 `completed` 还为 false。
- **解决**：不要复用已关闭会话；detach 后先 `reattach()`；FAULTED 先 `recover()`。

### Q: `Session disposed`

- **位置**：`UiSession.kt:28`
- **触发**：`close()` 后访问 `session.environment`。
- **解决**：不要复用已关闭会话；`environment` 只在存活期有效。

### Q: `View reference released`

- **位置**：`UiSession.kt:154`
- **触发**：`ref.get()` / `ref.token()` / `bind` 等解析时，节点或会话已释放。
- **排查**：检查是否跨 `release`/`close` 持有旧 `ViewRef`；是否在异步回调里延迟解析。
- **解决**：不要跨释放持有 `ViewRef`；异步回调使用 `BindingToken.isCurrent()` 或 `ref.token()` 复核。

### Q: `View already managed` / `Factory returned an already parented View` / `Inflater returned an already owned View`

- **位置**：`UiSession.kt:74`、`:89`；`:73`；`:127`
- **触发**：
  - `View already managed`：同一 View 实例已被本会话管理，又调用 `mount`/`borrow`。
  - `Factory returned an already parented View`：工厂返回的 View 已有 parent。
  - `Inflater returned an already owned View`：inflate 出来的 View 已有 parent 或已被管理。
- **排查**：检查工厂是否复用了已有 View；是否对同一 View 重复挂载/借用。
- **解决**：每个受管 View 只挂载一次；借用已有 View 用 `borrow`；移交无 parent 的 View 用 `transfer`。

### Q: `Explicitly detach before transferring; UI never steals an external child`

- **位置**：`UiSession.kt:101`
- **触发**：`transfer(view, ...)` 的 View 已有 parent。
- **解决**：先显式从原 parent 移除，再 `transfer`；库不会自动拆走外部 child。

### Q: `Managed <merge> requires an explicit ownership protocol`

- **位置**：`UiSession.kt:124`
- **触发**：`inflate` 的资源根节点是 `<merge>`。
- **解决**：`<merge>` 没有独立 View，需要调用方明确的归属协议；改用具体根布局或通过 `mount` 逐个挂载。

### Q: `Factory type differs from component`

- **位置**：`UiSession.kt:72`
- **触发**：工厂返回的 View 不是 `ComponentType.viewClass` 的实例。
- **排查**：检查 `ComponentType` 与工厂是否配对（例如 `Framework.text` 配 `textFactory`）。
- **解决**：修正配对；Framework、AppCompat、Material 控件不能按名称互换。

### Q: `Owner looper rejected close`

- **位置**：`UiSession.kt:265`
- **触发**：`requestClose()` 从后台调用时，主线程 looper 拒绝投递关闭任务。
- **排查**：检查主线程 looper 是否正在退出或已退出。
- **解决**：确保 owner 存活；必要时重建会话。

### Q: close 与 requestClose 的区别？

- `close()`：必须在主线程；幂等；立即标记 DISPOSED 并清理（`UiSession.kt:271-282`）。
- `requestClose()`：可后台调用；返回 `CloseRequest`；`accepted` 立即可见，`completed` 表示 owner 清理完成；失败保留在 `failure`（`UiSession.kt:260-269`）。
- 两者都不抢占已进入的用户回调。

### Q: detach / reattach / rebind / release 的语义？

| 操作 | 行为 | 源码 |
| --- | --- | --- |
| `detach()` | 状态 DETACHED，attach 代际递增，调度器暂停并清队列 | `UiSession.kt:219-223` |
| `reattach()` | 要求当前为 DETACHED；attach 代际递增，状态 ACTIVE，调度器 `resume()` | `UiSession.kt:224` |
| `rebind(ref)` | 提升 binding 代际，释放旧监听/属性，清空属性槽，`discardInvalid()` | `UiSession.kt:212-217` |
| `release(ref)` | 递归释放子树；CREATED/TRANSFERRED 移除 View，BORROWED 保留外部结构 | `UiSession.kt:228-258` |

`reattach()` 的 `check(state == SessionState.DETACHED)`（`UiSession.kt:224`）与 `resolve` 的 `check(ref.session === this)`（`:153`）**没有异常消息**，误用会抛无消息的 `IllegalStateException`。

### Q: 桥的生命周期所有权？

| 桥 | 所有权 | 源码 |
| --- | --- | --- |
| Lumen | 默认 `ownsSession = false`，不关闭 Lumen session；只有显式 `true` 才关 | `LumenUiBridge.kt:11`、`:38` |
| Android | 关闭自己创建的 `ViewScope`、帧通道、注册；调用者继续拥有 `WindowSession` 与 root | `AndroidUiBinding.kt:12`、`:60-66` |
| Ref | 借用共享 `CodeScope`，不关闭它 | `RefBindingSession.kt:18`、`:101-117` |

---

## 8. 其他模块速查

| 模块 | 常见消息 | 目录 |
| --- | --- | --- |
| Registry | `No provider for ${type.id}`、`Duplicate provider id`、`Extension dependency cycle`、`Ambiguous provider priority`、`Provider conflict requires explicit override` | [错误目录](error-catalog.md) |
| Plan | `Unsupported plan schema`、`Plan depth exceeds limit`、`A bound plan can be mounted only once`、`Unknown plan path` | [错误目录](error-catalog.md) |
| Recycler | `List controller closed`、`Stable IDs must be unique and not NO_ID`、`Detach the adapter from every RecyclerView before close` | [错误目录](error-catalog.md) |
| Text | `Text controller closed`、`Main looper rejected text result`、`Text update rejected: ${result.reason}` | [错误目录](error-catalog.md) |
| Layouts | `A row must allow between 1 and 32 children`、`A native row must remain horizontal` | [错误目录](error-catalog.md) |
| Ref | `CodeScope must be open`、`UI session is not active`、`View binding token is stale`、`The borrowed CodeScope identity or epoch changed` | [错误目录](error-catalog.md) |
| Lumen | `Lumen bridge closed`、`Surface registration closed` | [错误目录](error-catalog.md) |
| frame-shared（源码候选） | `Shared frame driver closed`、`FrameSource must deliver asynchronously and at most once per request` | [错误目录](error-catalog.md) |

## 相关文档

- [错误目录](error-catalog.md)：逐字消息 → 触发条件 → 排查 → 解决
- [核心合同](../contracts.md)、[属性与更新](../property-semantics.md)、[生命周期与失败恢复](../lifecycle.md)
- [调度器、帧驱动与背压](../guides/scheduler.md)、[属性绑定与 PropertySpec](../guides/properties.md)
- [生命周期与关闭](../quickstart/03-lifecycle-and-close.md)、[状态保存与恢复](../quickstart/05-restoration.md)
- [可选桥](../bridges.md)、[Android 桥（消费者视角）](../guides/bridges-android.md)
