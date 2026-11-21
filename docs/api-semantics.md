# MeguvizUI API 语义

本文说明 MeguvizUI `1.2.0` 的核心 API 语义与约束边界。公开 JVM 签名快照保存在 [api/public-api.txt](../api/public-api.txt)，正负编译用例见 [integration/compile-fixtures](../integration/compile-fixtures)。

## 构造与引用

`CreationEnvironment` 保留调用方的实际 themed `Context` 和资源归属，不替换为 `applicationContext`。Framework 工厂使用明确构造器，AppCompat / XML 示例使用各自 `LayoutInflater`，定制控件应传入应用专属工厂。

`UiSession.mount(parent, policy, key, component, params, factory, configure)` 先占用父命名域中的 key，再执行构造、配置、挂载和登记引用。重复 key 在构造前即被拒绝。挂载同步回调若关闭会话，本次挂载将被移除且不登记晚到节点。构造、配置或原生回调已经产生的业务副作用无法普遍回滚。

`ViewRef<V>` 从所属会话按身份和组件类型解析，释放后即失败。同一 key 更换组件类型须先显式 `release` 再重建，不提供隐式通用树 reconcile。外部仍持有原生 View 引用时仍可直接修改它，库不能撤销外部引用。

## 更新

`PropertySpec` 声明稳定属性 `id`、`effect`、所有权、`snapshot`、比较函数、`read`、`write` 和是否可合并。默认采用 `ALWAYS_WRITE`、`coalescible = false` 与保守 `effect`，避免误删事件和未知副作用。`EXCLUSIVE` 允许缓存确认值；`SHARED_READ` 每次比较当前 View getter；未知或外部多写场景保持 `ALWAYS_WRITE`。

可变输入必须经 `snapshot` 转为独立副本，或提供明确版本语义。`Properties.plainText()` 只适合无输入或过滤器副作用的不可变普通标签；`richText()` 保留每次 setter，span 对象的内部可变状态仍由调用方保证排队后不修改。输入编辑使用命令或业务控制器，不使用字符串去重代替 IME 协议。

主题或资源派生值需声明 `resourceSensitive = true`，提交时捕获资源和主题 token 并在实际写入前复核。`textColor()` 已启用该标记；不依赖资源的模型值保持默认 `false`。原位换肤须由应用显式调用 `session.invalidateTheme()` 或让上游资源权威失效，不能仅比较 `Context` / `Theme` 对象地址。

状态按绑定与属性槽合并，命令按 FIFO 保留调用次数。有序 batch 在命令通道内保持相对顺序，但不是事务，也不隔离其他通道。失败报告包含阶段、项位置与此前应用数量；`effect` 仅作声明，不压制 Android 原生 `invalidate` / `requestLayout`。

对于可能拒绝或改写输入值的控件：`EXCLUSIVE` 下通过 getter 读回最终权威值缓存；`SHARED_READ` 在写入前现场读取当前值用于相等比较；`ALWAYS_WRITE` 不读回也不比较。属性保留槽（`reserveProperties`）阻止 UI 与 Lumen 等适配器同时声明同一属性。直接绕过协议的外部写入发生时，应用须选用实时读取、显式 `invalidate()` 或关闭比较。

## 生命周期

所有 View 与会话 API 均在主线程调用（后台可使用 `requestClose()` 或 `submit*Async`）。`detach` 清理待更新引用并使旧 attach token 失效；`reattach` 恢复会话，已授权的点击监听保持绑定寿命。`rebind` 先失效旧绑定，再释放其监听与属性槽。

`close` 先禁止新提交、失效 token、关闭调度器，再释放节点监听、桥接注册与受管子树。某一项释放失败时其余资源仍继续清理，全部异常合并报告。`requestClose()` 区分后台受理与 owner 实际清理完成，不抢占已经进入的用户回调。代际计数溢出时替换域身份对象而非复用旧 token。theme、resource、attach、binding 分别独立管理代际；安装外部资源权威时，失效必须交由该权威控制，UI 不在同域重复计数。

## 恢复与扩展

`StateValue` 是封闭纯数据类型；`StateCodec` 的 id/version 与组件 schema、SPI 各自演进。未知 codec 版本须在 `decode` 中明确拒绝或迁移。`StableIdStore` 保存映射并检查 id 冲突及类型/schema 变化，不依赖字符串 hash。

`ViewRestoration` 要求应用先恢复权威模型，再注册已建节点；仅保存焦点、选区和滚动，并在布局完成后带 token 恢复。应用输入控制器在用户交互时调用 `userInteracted()` 撤销旧恢复。自定义控件的私有 hierarchy state 须自行提供纯数据 codec 或公开恢复适配。

`RegistrySnapshot` 安装时检查 SPI、能力集、依赖环、重复 id 与显式覆盖；一次 `resolve` 返回直接工厂 handle。稳态调用不再次遍历 provider，构造抛错也不切换其他 provider 重试。Registry 归会话所有，工厂闭包不得放入全局 `Context` 或 receiver 缓存。

## 兼容性基线与适用范围说明

- `1.2.0` 的公开 JVM 描述符是 `1.0.0` 的超集（由 `build-logic/verification/legacy_abi.py check` 与 `verifyLegacyAbi` 在 `verifyHost` 中强制校验），范围覆盖公开 JVM 描述符（含 Kotlin 合成的默认参数入口）。
- 公开 JVM 描述符兼容与主机单元测试通过不替代实际 Android/ART 设备、视觉渲染及性能测试；跨版本升级或涉及真实 View 行为的功能须经消费工程或设备测试复核。
