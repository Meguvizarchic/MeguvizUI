# MeguvizUI 候选 API 语义

源码候选版本为 `1.2.0`（尚未发布），已发布版本为 `1.0.0`，目标仍为 full_replacement。源码与签名已经实际构建；设备门禁未通过时不得据此声称完整候选验收完成。

## 构造与引用

`CreationEnvironment` 保留调用者的实际 themed Context 和资源归属，不换成 applicationContext。framework 工厂使用明确构造器，AppCompat/XML 示例使用自己的 inflater；BIL MaterialSwitch、LogSegmentScrubBar 使用应用专属工厂。

`UiSession.mount(parent, policy, key, component, params, factory, configure)` 先占用父命名域中的 key，再构造、配置、公开挂载和登记引用。重复 key 在构造前拒绝。挂载同步回调若关闭会话，本次挂载被移除，不登记晚到节点。构造、配置或原生回调已经发生的业务副作用无法普遍回滚。

`ViewRef<V>` 从自己的 session 按身份和组件类型解析，释放后失败。同 key 改类型需显式 release 后重建，不提供隐式通用树 reconcile。持有 raw View 的调用者仍可直接修改它；库不能撤销外部引用。

## 更新

`PropertySpec` 声明稳定属性 id、effect、ownership、snapshot、比较、getter、writer 和是否合并。默认 ALWAYS_WRITE、不合并、保守 effect；这些默认避免误删事件和未知副作用。EXCLUSIVE 允许确认值缓存；SHARED_READ 比较当前 View getter；未知或外部多写场景保持 ALWAYS_WRITE。

可变输入必须经 snapshot 转成独立值，或提供明确版本语义。`Properties.plainText()` 只适合无输入/过滤器副作用的不可变普通标签；`richText()` 保留每次 setter，span 对象的内部可变状态仍由调用者负责，不能在排队后修改。输入编辑使用命令/业务控制器，不使用字符串去重代替 IME 协议。

主题/资源派生值声明 `resourceSensitive=true`，提交捕获资源和主题 token 并在实际写入前复核。`textColor()` 已启用该标记；普通不依赖资源的模型值保持默认 false。原位换肤由应用显式 invalidateTheme 或上游资源权威失效，不能仅比较 Context/Theme 对象地址。

状态按绑定/属性合并，命令 FIFO 保留次数。有序 batch 在命令通道内保持相对顺序但不是事务，也不隔离其他通道。失败报告包含阶段、项位置与此前应用数量；effect 仅诊断，不压制 Android 的 invalidate/requestLayout。

可拒绝/回滚的控件在 `EXCLUSIVE` 下用 getter 读回最终权威值（`SHARED_READ` 每次现读、`ALWAYS_WRITE` 不读回，只在 `equivalent` 允许比较时读一次前值）；缓存不把请求值假定为接受值。属性保留槽阻止 UI 和 Lumen 等适配器同时声明同一属性。直接外部写入不经过该协议时，应用必须选择实时读取、显式 invalidate 或关闭比较。

## 生命周期

所有 View/会话 API 在主线程；纯数据状态与后台计算不改变此约束。detach 清理待更新引用并使旧 attach token 失效；reattach 恢复会话，已授权的点击监听保持绑定寿命。rebind 先失效旧 binding，再释放其监听与属性。

close 先禁止新提交、失效 token、关闭 scheduler，再释放节点监听/桥与受管子树。释放动作即使一项失败也继续，其异常合并报告。`requestClose()` 区分后台请求与 owner 清理完成；不抢占已经进入的用户回调。代际溢出替换域身份。theme/resource/attach/binding 分开；安装外部资源权威时，失效必须交给该权威，UI 不冒充同域计数所有者。

## 恢复与扩展

`StateValue` 是封闭纯数据类型；`StateCodec` id/version 与组件 schema、SPI 分离。未知 codec 版本由明确 decode/migration 拒绝。`StableIdStore` 保存映射并检查冲突、类型/schema变化；不依赖字符串 hash。

`ViewRestoration` 要求应用先恢复权威模型，再注册已建节点；保存焦点、选择和滚动，布局后带 token 恢复。应用输入控制器调用 `userInteracted()` 撤销旧恢复。它不伪装支持任意自定义 View 的私有 hierarchy state；该类控件须提供自己的纯数据 codec/公开恢复适配。

Registry 安装检查 SPI、能力、依赖环、重复和显式覆盖；一次 resolve 返回直接工厂 handle。稳态调用不再次遍历 provider，构造抛错不换 provider 重试。Registry 应归 session 所有；调用者提供的工厂闭包不得被自己存入全局 Context/receiver 缓存。

正负编译源码见 `integration/compile-fixtures`；唯一公开 API 基线见 `api/public-api.txt`。`api/public-api.txt` 只是当前候选的快照；与已发布 1.0.0 的二进制兼容由 `api/legacy/1.0.0/` 与 `verifyLegacyAbi` 单独检查（当前描述符必须是 1.0.0 描述符的超集，范围仅限公开 JVM 描述符，不含行为）。
