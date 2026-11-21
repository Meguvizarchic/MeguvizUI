# Ref 桥（消费者视角）

本文说明如何引入并用 `ui-ref-bridge` 从真实 `CodeScope` 动态构造 View 并安全调度方法调用。

**桥接可选、单库独立可用**。静态已知的 View 类型直接使用 `Framework` 工厂、自定义 `ViewFactory` 或 `@GenerateUiFactory` 即可，不需要 `CodeScope` 也不需要本桥。仅当视图类来自某个动态打开的 `CodeScope` ClassLoader 时才引入 Ref 桥。

## 坐标与版本

`meguviz-ui-ref-bridge:1.2.0` 及其上游依赖 `meguviz-ref-core:1.2.0` 均已发布到 Maven Central，并纳入家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`。

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-ui-ref-bridge")
}
```

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-ref-bridge` | `ui-ref-bridge/build.gradle.kts` |
| 上游 | `io.github.jichuo1.meguviz:meguviz-ref-core:1.2.0` | `gradle/libs.versions.toml` |

在源码仓库中验证该模块时传入 `-PenableRef=true`：

```sh
./gradlew -PenableRef=true verifyHost --no-daemon --max-workers=2
```

## 核心 API

源码：`ui-ref-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/ref/RefBindingSession.kt`

### `attach`

```kotlin
class RefBindingSession internal constructor(/* ... */) : AutoCloseable {
    companion object {
        fun attach(ui: UiSession, code: CodeScope): RefBindingSession
    }
}
```

`attach` 是唯一公开构造入口，它要求：
- 在主线程调用（`ui.assertOwner()`）；
- `code.state() == CodeScope.State.OPEN`，否则抛 `CodeScope must be open`；
- 记录 `scope.identity()` 与 `scope.codeEpoch()`，之后每次创建或调用均校验两者未变更；
- 注册 `ui.onClose(binding::close)`，UI 会话关闭时自动释放自身资源。

本桥只**借用** `CodeScope`，绝不关闭共享代码域；只清理 UI 侧创建的工厂、receiver lease 与方法句柄。

### `viewFactory`

```kotlin
fun <V : View> viewFactory(
    type: Class<V>,
    parameterTypes: Array<Class<*>> = arrayOf(Context::class.java),
    arguments: (CreationEnvironment) -> Array<out Any?> = { arrayOf(it.context) },
): RefViewFactory<V>
```

返回的 `RefViewFactory<V>` 实现了 `ViewFactory<V>` 与 `AutoCloseable`，可直接传入 `ParentScope.view(...)`：
- 仅在调用 `viewFactory` 时解析一次 `ConstructorPlan`；`create` 使用调用方实际 `CreationEnvironment.context`。
- `type` 必须属于该 `CodeScope` 的真实 ClassLoader，否则抛出 `Dynamic factory type must belong to this CodeScope's actual loader`。
- 构造异常原样传播一次，不尝试回退其他构造器或 provider。

### `bindView` 与方法调用

```kotlin
fun <V : View> bindView(ref: ViewRef<V>, resources: Boolean = false): RefViewLease<V>
```

`RefViewLease<V>` 与 `RefMethodHandle` 每次执行均同时校验：
1. `CodeScope` 的 `state`、`identity` 与 `codeEpoch` 未发生变化；
2. UI `ViewRef` 的 binding / attach / resource token 仍有效（`resources = true` 时一并检查资源域）。

`bindView` 通过 `ui.onRelease(ref, lease::close)` 绑定节点释放生命周期。`RefMethodHandle.operation` 将方法调用包装为 `ScheduledOperation`，放入所属 `UiSession` 的命令队列或有序批次（按 FIFO 保次执行，不作状态合并）。

## 典型流程

```kotlin
val codeBinding = RefBindingSession.attach(ui, codeScope)

// 1. 解析构造计划
val factory = codeBinding.viewFactory(MyCustomView::class.java)

// 2. 挂载到父容器
val ref = FrameScope(ui, root).view(
    ViewKey("custom"),
    ComponentType("custom", MyCustomView::class.java),
    frameParams(),
    factory,
)

// 3. 取得接收者租约并在有序批次中执行命令
val lease = codeBinding.bindView(ref)
ui.scheduler.submitBatch(
    lease.operation("focus") { it.requestFocus() },
    lease.method(QuerySpec.builder(MyCustomView::class.java, MemberKind.METHOD).name("refresh").build())
        .operation("refresh"),
)

// 4. 释放
lease.close()
codeBinding.close()
```

## 常见错误

| 错误 | 原因 | 处理 |
| --- | --- | --- |
| `CodeScope must be open` | `attach` 时 `CodeScope` 已关闭 | 先打开或重建 `CodeScope` |
| `Dynamic factory type must belong to this CodeScope's actual loader` | 目标类来自别的 ClassLoader | 确认类由该 scope 的 loader 加载 |
| `Ref binding session is closed` / `View binding token is stale` | 句柄已关闭或 View token 已失效 | 检查 `isCurrent()`，不缓存旧租约 |
| `The borrowed CodeScope identity or epoch changed` | 共享 `CodeScope` 已被替换或重建 | 关闭旧桥并重新 `attach` |

## 来源

- `ui-ref-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/ref/RefBindingSession.kt`
- `integration/compile-fixtures/positive-ref/Consumer.kt`
- [可选桥边界](../bridges.md) · [安装与接入](../installation.md)
