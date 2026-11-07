# Lumen 桥（消费者视角）

本文面向应用开发者，讲如何依赖、启用并用 `ui-lumen-bridge` 把 Lumen 表面绑到 MeguvizUI 管理的 View 上。

**桥接可选、单库独立可用。** `ui-core`/`ui-widgets` 不依赖 Lumen；不用 Lumen 时完全不需要本桥。启用需要真实 `LumenSurfaceSession`，材质、采样、动画与绘制仍由 Lumen 负责，桥只管理登记与属性占用。

## 坐标与版本

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `meguviz.version`，当前 `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-lumen-bridge` | `ui-lumen-bridge/build.gradle.kts` |
| 上游 | `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5` | `gradle/libs.versions.toml` |

上游 Lumen 只发布在 JitPack。`settings.gradle.kts` 为它配置了内容过滤仓库：

```kotlin
maven("https://jitpack.io") {
    content { includeGroup("com.github.jichuo1.LumenCoacervationEngine") }
}
```

**Central 状态：** `meguviz-ui-lumen-bridge` 不在 Maven Central；Lumen 引擎本身也只走 JitPack。消费项目需要同时能解析 JitPack 与本地候选仓库。

## 启用开关

```sh
./gradlew -PenableLumen=true verifyHost
./gradlew -PenableLumen=true publishCandidate
```

`enableLumen` 是 `settings.gradle.kts` 中的真实 Gradle 属性名。不启用时 `ui-lumen-bridge` 不参与构建，core/widgets 的传递依赖不受影响。

## 本地消费

与 [Android 桥](bridges-android.md) 的“本地消费”一节相同：`publishCandidate` 把候选发布到 `build/repo/`，消费项目用 `meguvizUiRepository` 指向该目录，并额外保留 JitPack 仓库解析 `lumen-engine`：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("meguvizUiRepository").get()) }
        maven("https://jitpack.io") {
            content { includeGroup("com.github.jichuo1.LumenCoacervationEngine") }
        }
        google()
        mavenCentral()
    }
}
```

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-lumen-bridge:1.2.0")
}
```

## LumenUiBridge

来源：`ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt`

```kotlin
class LumenUiBridge(
    private val ui: UiSession,
    lumen: LumenSurfaceSession,
    private val ownsSession: Boolean = false,
) : AutoCloseable {

    fun <V : View> bind(
        ref: ViewRef<V>,
        options: LumenSurfaceOptions,
        source: View? = null,
    ): SurfaceRegistration

    fun pause()   // 要求 ownsSession
    fun resume()  // 要求 ownsSession
    override fun close()
}
```

```kotlin
class SurfaceRegistration : AutoCloseable {
    fun update(options: LumenSurfaceOptions)
    override fun close()
}
```

用法：

```kotlin
val bridge = LumenUiBridge(ui, lumenSession)          // ownsSession 默认 false
val options = LumenSurfacePresets.floating()          // LumenSurfaceOptions
val registration = bridge.bind(cardRef, options)
registration.update(options.copy(tintOpacity = 0.42f))
// ...
registration.close()
bridge.close()
```

### ownsSession 语义

- 默认 `ownsSession = false`：桥**不**暂停、不关闭调用方共享的 `LumenSurfaceSession`；`pause()`/`resume()` 会因 `check(ownsSession)` 抛错。
- `ownsSession = true`：桥可以暂停/恢复并在 `close()` 时关闭该 session。
- 共享会话时误传 `true` 会关掉调用方的会话。会话归属必须由实际所有者决定。

桥在构造时注册 `ui.onClose { close() }`，UI 关闭会连带关闭桥；`close()` 逐个关闭登记，异常合并报告。

### bind 与属性占用

```kotlin
fun <V : View> bind(ref: ViewRef<V>, options: LumenSurfaceOptions, source: View? = null): SurfaceRegistration {
    ui.assertOwner()
    val engine = checkNotNull(lumen) { "Lumen bridge closed" }
    val ownership = ui.reserveProperties(ref, setOf("background"))
    val binding = try { engine.bind(ref.get(), options, source) } catch (failure: Throwable) { ownership.close(); throw failure }
    val registration = SurfaceRegistration(ui, binding, ownership) { registrations.remove(it) }
    registrations += registration
    ui.onRelease(ref) { registration.close() }
    return registration
}
```

关键点：

1. 先 `ui.reserveProperties(ref, setOf("background"))`，再调用真实 `LumenSurfaceSession.bind`。也就是说 **Lumen 拥有该 View 的 `background` 属性槽**。
2. 如果同一 View 已经用 `session.bind(ref, ...)` 声明了 `id = "background"` 的属性，或已经有人 `reserveProperties(ref, setOf("background"))`，会抛 `Conflicting visual property owner`。
3. 反之，如果 UI 先占用了 `background`，再调用本桥的 `bind` 也会在 `reserveProperties` 处冲突。两种顺序都由 MeguvizUI 自己的注册表拒绝。
4. 节点释放（`session.release`/`rebind`）会触发 `registration.close()`，释放 Lumen binding 与占用。

`source` 是可选的内容来源 View；`options` 是每表面的外观参数，`update` 用同一 binding 更新选项。

### LumenSurfaceOptions

`com.lumen.coacervation.engine.host.LumenSurfaceOptions` 是数据类，主要字段：

```kotlin
data class LumenSurfaceOptions(
    val enabled: Boolean = true,
    val material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED,
    val role: SurfaceRole = SurfaceRole.FLOATING,
    val radiusDp: Float = 24f,
    val opacity: Float = 1f,
    val color: Int? = null,
    val tintEnabled: Boolean = true,
    val tintOpacity: Float = 0.20f,
    val fallbackTintOpacity: Float = 0.80f,
    val edgeEnabled: Boolean = true,
    val edgeWidthDp: Float = 1f,
    val edgeIntensity: Float = 1f,
    val clipBackground: Boolean = true,
    val sampling: LumenSurfaceSampling = LumenSurfaceSampling(),
)
```

底层真实 API 是 `LumenSurfaceSession.bind(view, surface, source)`；桥只是把 `ViewRef<V>` 解析成真实 View 后调用它。材质与动画决策不经过 MeguvizUI。

## 与 MeguvizDrawable 侧 background 登记的互斥

这是混用两套桥时最容易踩的边界。事实依据见 `D:\DevArea\MeguvizDrawable\docs\engineering\BRIDGES.md`（Lumen / MeguvizUI 两节）：

- MeguvizDrawable 的 `LumenSurfaceBridge` 用**宿主传入的** `BindingScope.claim(key)` 独占属性 key；`claim` 只存在于未发布的 drawable core 1.2.0。
- Drawable 侧 `RecipeBackground` 用**每次新建的独立** `BindingScope` 走 `UiSession.bind`，把配方作为一个 MeguvizUI 属性（id 默认 `background`）。
- 两个 `BindingScope` 之间只在**同一 scope 内**才互斥，跨栈没有共同注册表。也就是说：Lumen 侧的 `reserveProperties("background")` 与 Drawable 侧的 `RecipeBackground` 登记**不会互相拒绝**。
- `LumenSurfaceSession` 对同一 View 再次 `bind` 会使旧绑定失效（静默替换），不报错。

结论与建议：

1. 不要把同一个 View 的 `background` 同时交给 Lumen 表面和 Drawable 配方。二选一，或由应用明确划分（例如不同 View、不同层级）。
2. 仅靠 MeguvizUI 的属性注册表无法发现跨栈冲突；混用两套桥时建议引入一个可选的共享登记处，**默认关闭**。
3. `alpha`/`translation` 等属性不会因为 View 被 UI 挂载就自动成为 UI 独占状态；动画 owner 需要显式保留对应属性槽。

## 来源

- `ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`（`reserveProperties`）
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/Properties.kt`（属性槽语义）
- `settings.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`
- `docs/bridges/lumen.md`、`docs/bridges.md`
- 上游 API：`lumen-engine/src/main/java/com/lumen/coacervation/engine/host/LumenSurfaceSession.kt`、`LumenSurfaceOptions.kt`
- 互斥依据：`D:\DevArea\MeguvizDrawable\docs\engineering\BRIDGES.md`
