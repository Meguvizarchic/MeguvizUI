# 核心合同

## 构造与类型

`CreationEnvironment` 保留实际 themed Context。父作用域与 `ParentPolicy` 约束 LayoutParams 类型，`ViewRef<V>` 保留控件类型。挂载前检查 key，外部已有 parent 的 View 不会被自动拆走。构造或原生回调已经产生的业务副作用无法普遍回滚。

## 属性与更新

`PropertySpec` 声明属性标识、effect、所有权、快照、比较、getter、writer 及合并方式。默认采用保守写入语义；EXCLUSIVE 可使用确认值缓存，SHARED_READ 读取当前原生值。外部多方写入须选择实时读取、显式失效或关闭比较。

可变输入必须复制成独立快照或提供版本语义。`Properties.plainText()` 适用于没有编辑协议副作用的不可变标签；输入、过滤器与 IME 由明确业务控制器管理。资源敏感属性在实际写入前复核资源及主题 token。

状态按绑定与属性合并；命令 FIFO 保留次数。有序 batch 保持自身相对顺序，不提供事务或跨通道隔离。失败报告说明阶段及已应用数量；effect 不压制原生 invalidate/requestLayout。

一次绑定，多次提交；只有明确声明 `coalescible=true` 的状态才丢弃同槽中间值。点击、焦点、带业务监听器的写入等命令不得为性能而自动合并。需要显式先后关系时使用批次：

```kotlin
val title = session.bind(titleRef, Properties.plainText())
title.submit("Ready")

// 以下便利重载属于当前源码改动，尚未发布；已发布 1.0.0 仍使用 listOf(...) 形式。
session.scheduler.submitBatch(
    title.operation("Saved"),
    ScheduledOperation("focus") { editorRef.get().requestFocus(); Unit },
)
```

示例中的 `Properties` 来自 `io.github.jichuo1.meguviz.ui.widgets`，`ScheduledOperation` 来自 `io.github.jichuo1.meguviz.ui.scheduler`。原 `submitBatch(List<ScheduledOperation>)` 保留；两种入口都复制输入、整体准入或拒绝，并在每项执行前复核有效性。检查返回的 `SubmitResult`，不要忽略容量、暂停或关闭引起的拒绝。

状态组在帧开始建立索引，按组公平轮转，取项不再逐次扫描全部状态；命令按数组游标消费，已执行或失效项立即清空引用。快路径不绕过 validity、错误报告、容量、生命周期或时间预算。

## 生命周期与调度

View 与会话操作在主线程执行。detach 使旧 attach token 失效并清理待更新引用；rebind 先失效旧绑定。close 禁止新提交，关闭调度并释放绑定、监听与受管子树，清理失败仍继续释放其他资源。`requestClose()` 区分后台请求与 owner 清理完成。

借用的 View 保持调用者所有权。单 listener 槽需明确授权；清理闭包应捕获所需原生 View，避免在引用释放后重新解析。资源、主题、attach、binding 分别管理代际，外部资源权威由其实际所有者失效。

普通会话只有一个帧驱动；Android 桥将更新放入上游同一帧通道。Lumen 桥保留视觉属性，避免两个系统争写背景或动效状态。

## 恢复与扩展

`StateValue` 为封闭纯数据类型。codec 版本、组件 schema 与 SPI 分别演进；未知版本须明确拒绝或迁移。`StableIdStore` 保存映射并检查冲突，不使用字符串 hash 冒充稳定身份。

应用先恢复权威模型，再由 `ViewRestoration` 在布局后恢复焦点、选择与滚动。用户交互可撤销旧恢复。自定义控件的私有 hierarchy state 需要专门适配。应用应明确分配模型与 Android 原生保存的职责，避免恢复两次覆盖输入。

Registry 检查能力、版本、重复、覆盖与依赖环，一次解析取得直接工厂。构造异常不换 provider 重试，调用者闭包不应进入全局 Context 或 receiver 缓存。

[返回文档目录](README.md)
