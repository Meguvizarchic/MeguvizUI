# Android 桥（消费者视角）

本文面向应用开发者，讲如何依赖、启用并用 `ui-android-bridge` 把 MeguvizUI 挂到真实 MeguvizAndroid 窗口上。

**桥接可选、单库独立可用。** `ui-core`/`ui-widgets` 不依赖 MeguvizAndroid；没有窗口运行环境时，用 `UiSession(CreationEnvironment(context))` 独立运行即可。本桥只是把 UI 更新并入上游同一帧通道，并提供真实资源权威。

## 坐标与版本

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `meguviz.version`，当前 `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-android-bridge` | `ui-android-bridge/build.gradle.kts` |
| 上游 runtime | `io.github.jichuo1.meguviz:meguviz-android-runtime:1.2.0` | `gradle/libs.versions.toml` |
| 上游 window | `io.github.jichuo1.meguviz:meguviz-android-window:1.2.0` | `gradle/libs.versions.toml` |

上游两个制品的 SHA-256 固定在 `gradle/upstream-candidates.toml`。`settings.gradle.kts` 中已发布的 `x.y.z` 版本从 Maven Central 解析并按哈希校验；只有 `-SNAPSHOT` 版本才走 `build/upstream-repo` 镜像。

**Central 状态：** `meguviz-ui-android-bridge` 的上游 1.2.0 尚未发布到 Maven Central，`meguviz-ui-lumen-bridge` 依赖 JitPack 上的 Lumen 1.5.5，因此这两个桥在默认仓库下无法解析，Central 上暂不提供（见 `docs/installation.md`）。`meguviz-ui-core`/`meguviz-ui-widgets` 的 `1.0.0` 已在 Central。

## 启用开关

桥模块默认不参与构建，必须在 `settings.gradle.kts` 中显式启用：

```sh
./gradlew -PenableAndroid=true verifyHost
./gradlew -PenableAndroid=true publishCandidate
```

`enableAndroid` 是 `settings.gradle.kts` 里的真实 Gradle 属性名；对应还有 `enableLumen`、`enableRef`。P2 模块另有 `enablePlan`/`enableRecycler`/`enableText`/`enableLayouts`/`enablePrewarm`/`enableFrameShared`/`enableCodegen`，与本桥无关。

## 本地消费（桥未上 Central）

`publishCandidate` 会把候选制品发布到源码根目录的 `build/repo/`。消费项目按 `docs/installation.md` 的方式指向该仓库：

```kotlin
// 消费项目的 settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("meguvizUiRepository").get()) }
        google()
        mavenCentral()
    }
}
```

```kotlin
// 消费项目的 build.gradle.kts
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-android-bridge:1.2.0")
}
```

桥会传递依赖 `meguviz-android-runtime`/`meguviz-android-window`。上游 1.2.0 发布到 Central 后，可直接从 Central 解析；发布前需要本地 Maven 仓库或已按 pins 校验的候选镜像。不要把本机绝对路径写进共享 Gradle 配置。

## AndroidUiBinding.open

来源：`ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt`

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

用法：

```kotlin
val binding = AndroidUiBinding.open(windowSession, rootView)
val ui = binding.ui
// 用 FrameScope(ui, rootView) 等挂载控件
// ...
binding.close()   // 必须与 WindowSession 关闭顺序配对
```

`open` 的行为与约束：

1. 要求窗口具备 `WindowSession.Permission.APPLY_LOCAL` 权限。
2. 自行调用 `window.scope(root)` 创建一个新的 `ViewScope`；**root 不得已被同一窗口内另一个 `ViewScope` 接管**，否则上游拒绝重复注册。
3. 用 `root.context` 构造 `CreationEnvironment`，并用真实 `ViewScope.resources()` 与 `androidResourceAuthority(resources)` 作为资源权威，避免主题 wrapper 与另一个资源域混用。
4. 安装 `OnAttachStateChangeListener`，把根 View 的 detach/reattach 转发给 `UiSession.detach()`/`reattach()`：detach 使旧 token 失效并清理队列，reattach 恢复 PAUSED 调度器；FAULTED 状态需要显式重建。
5. 关闭时只释放本桥创建的 `ViewScope`、帧通道、注册与子节点；调用者继续拥有 `WindowSession` 和 root View。

关闭顺序：**先关 binding，再关共享窗口或 scheduler**。上游 `WindowFrameChannel` 没有公开 `onClose/isClosed`，桥不能及时观察“独立关闭共享 scheduler 但保持 window 活着”的异常顺序。若使用外部共享 `FrameScheduler`，其所有者必须遵守同样的顺序。

## 直接使用 AndroidFrameDriver

如果不走 `AndroidUiBinding`，可以把 UI 接到已有真实 `FrameScheduler`：

```kotlin
class AndroidFrameDriver(
    private val dispatcher: OwnerDispatcher,
    scheduler: FrameScheduler,
    identity: Any,
) : UiFrameDriver, AutoCloseable {
    override val owner: String = "meguviz-android.runtime"
    var isClosed: Boolean; private set
    val lastFrameSequence: Long?   // 只读；尚未交付过帧时为 null
    fun onClose(action: () -> Unit)
    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
    override fun close()
}
```

它只开一个 `FrameChannel`，通过上游 `onClose` 得知共享调度器独立关闭。手工装配时必须配对所有权：

```kotlin
driver.onClose { uiSession.close() }
uiSession.onClose { driver.close() }
```

低层 driver 本身不假定持有某个 UI 会话；`AndroidUiBinding.open` 已经替你完成双向关闭装配。

## 资源权威

```kotlin
fun androidResourceAuthority(scope: ResourceScope): ResourceAuthority
fun androidResourceAuthority(state: ResourceState): ResourceAuthority
```

资源 token 来自上游 `ResourceState.token(ALL)`，校验只调用该域的 `isCurrent`。主题原位变化通过上游 `ResourceScope.applyStyle/invalidateTheme` 通知，UI 不自行递增同一资源域。

## 与单库方式的选择

| 场景 | 建议 |
| --- | --- |
| 普通 Activity/Fragment，没有 MeguvizAndroid 窗口 | 直接用 `UiSession(CreationEnvironment(context))`，不需要本桥 |
| 已有 `WindowSession`，希望 UI 更新与上游同一帧 | 用 `AndroidUiBinding.open(window, root)` |
| 已有真实 `FrameScheduler`，但不需要窗口装配 | 用 `AndroidFrameDriver` 并自行配对关闭 |

## 来源

- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidUiBinding.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidFrameDriver.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidWindowFrameDriver.kt`
- `ui-android-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/android/AndroidResourceAuthority.kt`
- `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/upstream-candidates.toml`
- `docs/bridges.md`、`docs/installation.md`
