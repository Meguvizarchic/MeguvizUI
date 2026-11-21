# 核心合同

## 构造与类型

`CreationEnvironment` 保留调用方的实际 themed `Context`。父作用域与 `ParentPolicy` 约束 `LayoutParams` 类型，`ViewRef<V>` 保留控件类型。挂载前会预先占用父容器命名域中的 key，外部已有 `parent` 的 View 不会被自动拆走。构造或原生回调已经产生的业务副作用无法普遍回滚。

## 属性与更新

`PropertySpec` 声明属性标识、effect、所有权、快照、比较、getter、writer 及合并方式。默认采用保守写入语义：`ALWAYS_WRITE` 不做比较和缓存；`EXCLUSIVE` 允许使用确认值缓存；`SHARED_READ` 在应用前读取当前原生值。存在外部多方写入时，必须选用实时读取（`SHARED_READ`）、显式 `invalidate()` 或关闭比较。

可变输入必须复制成独立快照或提供版本语义。`Properties.plainText()` 适用于没有编辑协议副作用的不可变标签；输入、过滤器与 IME 交由明确业务控制器管理。资源敏感属性（`resourceSensitive = true`）在实际写入前复核资源及主题 token。

状态按绑定与属性槽合并（latest wins）；命令按 FIFO 保留每次调用。有序 batch 保持自身相对顺序，不提供事务或跨通道隔离。失败报告包含阶段、项位置及已应用数量；`effect` 仅用于诊断合同，不压制原生 `invalidate` / `requestLayout`。

一次绑定、多次提交；只有明确声明 `coalescible = true` 的状态才会合并同槽中间值。点击、焦点、带业务监听器的写入等命令不得为性能而自动合并。需要严格因果次序时使用批次：

```kotlin
val title = titleRef.bind(Properties.plainText())
title.submit("Ready")

session.scheduler.submitBatch(
    title.operation("Saved"),
    ScheduledOperation("focus") { editorRef.get().requestFocus(); Unit },
)
```

示例中的 `Properties` 来自 `io.github.jichuo1.meguviz.ui.widgets`，`ScheduledOperation` 来自 `io.github.jichuo1.meguviz.ui.scheduler`。`ViewRef.bind(property)` 是 `session.bind(ref, property)` 的纯委托成员，Java 与 Kotlin 均可直接调用，属性冲突、关闭或失效引用仍由同一入口拒绝；`1.0.0` 旧用户可继续使用 `session.bind(...)` 与 `submitBatch(List<ScheduledOperation>)`（该 `List` 重载在 `1.2.0` 完整保留）。两种 `submitBatch` 入口都会复制输入、整批入队或整批拒绝，并在每项执行前复核有效性。务必检查返回的 `SubmitResult`，不可忽略容量、暂停或关闭引起的拒绝。

状态组在帧开始建立索引并按组公平轮转，取项不再逐次扫描全部状态；命令按数组游标消费，已执行或失效项立即清空引用。所有快路径均不绕过 validity 检查、错误报告、容量限制、生命周期校验或时间预算。

## 生命周期与调度

View 与会话操作在主线程执行（跨线程后台投递见 `submitStateAsync` / `submitCommandAsync`，后台关闭入口为 `requestClose()`）。`detach` 使旧 attach token 失效并清理待更新引用；`rebind` 先失效旧绑定再清理槽位。`close` 禁止新提交、关闭调度器并释放绑定、监听与受管子树；即使某项清理抛出异常，其余资源仍继续释放。`requestClose()` 区分后台请求受理与 owner 实际清理完成。

借用的 View 保持调用方所有权。单 listener 槽（如 `session.click` 占用的 `"event:click"`）需明确授权；清理闭包应捕获所需原生 View，避免在引用释放后重新解析。资源、主题、attach、binding 分别管理代际，安装外部资源权威时必须由上游权威触发失效。

普通会话只有一个帧驱动；Android 桥将 UI 更新并入上游同一帧通道。Lumen 桥保留 `"background"` 等视觉属性槽，避免两个系统争写背景或动效状态。

## 恢复与扩展

`StateValue` 为封闭纯数据类型。codec 版本、组件 schema 与 SPI 分别演进；未知版本须明确拒绝或迁移。`StableIdStore` 保存稳定映射并检查冲突，不使用字符串 hash 冒充稳定身份。

应用应先恢复权威模型，再由 `ViewRestoration` 在布局后恢复焦点、选区与滚动。用户交互可调用 `userInteracted()` 撤销旧恢复。自定义控件的私有 hierarchy state 需要专门适配。应用须明确划分业务模型与 Android 原生状态保存职责，避免恢复两次覆盖输入。

`RegistrySnapshot` 安装时检查 SPI、能力、依赖环、重复 id 与显式覆盖，一次 `resolve` 取得直接工厂 handle。构造异常不会切换 provider 重试；调用者闭包不应存入全局 `Context` 或 receiver 缓存。

[返回文档目录](README.md)
