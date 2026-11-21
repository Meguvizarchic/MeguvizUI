# Android 桥（消费者视角）

本文面向应用开发者，说明如何引入并用 `ui-android-bridge` 将 MeguvizUI 挂载到真实 `MeguvizAndroid` 窗口上。

**桥接可选、单库独立可用**。`ui-core` / `ui-widgets` 不依赖 `MeguvizAndroid`；标准 `Activity` / `Fragment` 直接使用 `openMeguvizUi()` 或 `UiSession(CreationEnvironment(context))` 即可。本桥仅负责将 UI 更新并入上游同一帧通道，并提供真实资源权威。

## 坐标与版本

`meguviz-ui-android-bridge:1.2.0` 及其上游依赖 `meguviz-android-runtime:1.2.0`、`meguviz-android-window:1.2.0` 均已发布到 Maven Central，并纳入家族 BOM `io.github.jichuo1.meguviz:meguviz-bom:1.2.0`。

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-ui-android-bridge")
}
```

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-android-bridge` | `ui-android-bridge/build.gradle.kts` |
| 上游 runtime | `io.github.jichuo1.meguviz:meguviz-android-runtime:1.2.0` | `gradle/libs.versions.toml` |
| 上游 window | `io.github.jichuo1.meguviz:meguviz-android-window:1.2.0` | `gradle/libs.versions.toml` |

在源码仓库中开发或验证该模块时，需传入 Gradle 属性 `-PenableAndroid=true`：

```sh
./gradlew -PenableAndroid=true verifyHost --no-daemon --max-workers=2
```

## AndroidUiBinding.open

源码：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt`

```kotlin
class AndroidUiBinding private constructor(
    val ui: UiSession,
    root: View,
    scope: ViewScope,
    driver: AndroidWindowFrameDriver,
) : AutoCloseable {
    override fun close()

    companion object {
        fun open(window: WindowSession, root: View): AndroidUiBinding
    }
}
```

典型用法：

```kotlin
val binding = AndroidUiBinding.open(windowSession, rootView)
val ui = binding.ui
// 通过 FrameScope(ui, rootView) 等挂载控件
// ...
binding.close() // 必须先于 WindowSession 关闭
```

`open` 的行为与约束：

1. 要求窗口具备 `WindowSession.Permission.APPLY_LOCAL` 权限。
2. 内部调用 `window.scope(root)` 创建新的 `ViewScope`；**`root` 不得已被同一窗口内另一个 `ViewScope` 接管**，否则上游拒绝重复注册。
3. 使用 `root.context` 构造 `CreationEnvironment`，并用真实 `ViewScope.resources()` 与 `androidResourceAuthority(resources)` 作为资源权威，避免主题 wrapper 与不同资源域混用。
4. 安装 `OnAttachStateChangeListener`，把根 View 的 detach / reattach 转发给 `UiSession.detach()` / `reattach()`：detach 使旧 token 失效并清理队列，reattach 恢复 `PAUSED` 调度器；`FAULTED` 状态须显式调用 `recover()` 或重建会话。
5. 关闭时只释放本桥创建的 `ViewScope`、帧通道、注册与受管子节点；调用方继续持有 `WindowSession` 与 `root` View。

**关闭顺序铁律：先关 `binding`，再关共享 `WindowSession` 或 `FrameScheduler`**。上游 `WindowFrameChannel` 没有暴露 `onClose` / `isClosed`，桥无法提前观测“保持窗口存活却独立关闭共享 scheduler”的错误顺序。

## 直接使用 AndroidFrameDriver

若无 `WindowSession` 而需对接已有 `FrameScheduler`，可使用底层 `AndroidFrameDriver`：

```kotlin
class AndroidFrameDriver(
    private val dispatcher: OwnerDispatcher,
    scheduler: FrameScheduler,
    identity: Any,
) : UiFrameDriver, AutoCloseable {
    override val owner: String = "meguviz-android.runtime"
    var isClosed: Boolean; private set
    val lastFrameSequence: Long? // 只读；尚未交付过帧时为 null
    fun onClose(action: () -> Unit)
    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
    override fun close()
}
```

手工装配时必须双向配对关闭监听：

```kotlin
driver.onClose { uiSession.close() }
uiSession.onClose { driver.close() }
```

## 资源权威

```kotlin
fun androidResourceAuthority(scope: ResourceScope): ResourceAuthority
fun androidResourceAuthority(state: ResourceState): ResourceAuthority
```

资源 token 来自上游 `ResourceState.token(ALL)`，校验调用该域的 `isCurrent`。主题原位变化应通过上游 `ResourceScope` 触发失效，UI 不自行递增同一资源域。

## 场景选型

| 场景 | 建议 |
| --- | --- |
| 普通 `Activity` / `Fragment`，无 `MeguvizAndroid` 窗口 | 直接用 `openMeguvizUi()` 或 `UiSession(CreationEnvironment(context))`，不引入本桥 |
| 已有 `WindowSession`，需要 UI 更新与窗口同帧并共享资源域 | 使用 `AndroidUiBinding.open(window, root)` |
| 已有独立 `FrameScheduler`，不需要窗口生命周期装配 | 使用 `AndroidFrameDriver` 并配对关闭 |

## 来源

- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidFrameDriver.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidWindowFrameDriver.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidResourceAuthority.kt`
- [可选桥边界](../bridges.md) · [安装与接入](../installation.md)
