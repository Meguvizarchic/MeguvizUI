# Lumen 桥（消费者视角）

本文说明如何从源码启用并用 `ui-lumen-bridge` 把 Lumen 视觉表面绑定到 MeguvizUI 管理的 View 上。

**桥接可选、单库独立可用**。`ui-core` / `ui-widgets` 不依赖 Lumen；不使用 Lumen 表面特效时不需要本桥。本桥依赖真实 `LumenSurfaceSession`，材质、采样、动画与绘制继续由 Lumen 管理，桥仅管理注册登记与属性槽占用。

## 坐标、Central 状态与上游版本

| 项 | 值 | 来源 |
| --- | --- | --- |
| group | `io.github.jichuo1.meguviz` | 根 `build.gradle.kts` |
| version | `1.2.0` | `gradle.properties` |
| bridge artifactId | `meguviz-ui-lumen-bridge` | `ui-lumen-bridge/build.gradle.kts` |
| 当前源码锁定上游 | `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5` | `gradle/libs.versions.toml` |

**重要状态提示**：
- **`meguviz-ui-lumen-bridge` 目前尚未发布到 Maven Central**，也没有进入 `meguviz-bom:1.2.0`。仅支持在本仓库通过源码构建消费。
- `LumenCoacervationEngine` 本身已将 `1.6.2`（`io.github.jichuo1:lumen-engine:1.6.2` 等制品）发布到 Maven Central 与 JitPack；但 `MeguvizUI` 当前源码的 `gradle/libs.versions.toml` 仍锁定 JitPack 上的 `1.5.5`（此前 `1.6.1` AAR 要求消费者 `compileSdk 37.2`）。因此，当前源码构建启用 Lumen 桥时仍从内容过滤的 JitPack 仓库解析 `1.5.5`：

```kotlin
maven("https://jitpack.io") {
    content { includeGroup("com.github.jichuo1.LumenCoacervationEngine") }
}
```

## 启用源码构建与本地消费

```sh
./gradlew -PenableLumen=true verifyHost publishCandidate --no-daemon --max-workers=2
```

`publishCandidate` 会将候选制品发布到源码根目录的 `build/repo/`。消费工程在 `settings.gradle.kts` 中同时配置本地 `build/repo/` 与 JitPack（供传递解析 `lumen-engine:1.5.5`）：

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

源码：`ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt`

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

    fun pause()   // 要求 ownsSession = true
    fun resume()  // 要求 ownsSession = true
    override fun close()
}

class SurfaceRegistration : AutoCloseable {
    fun update(options: LumenSurfaceOptions)
    override fun close()
}
```

用法示例：

```kotlin
val bridge = LumenUiBridge(ui, lumenSession)          // ownsSession 默认 false
val options = LumenSurfacePresets.floating()          // LumenSurfaceOptions
val registration = bridge.bind(cardRef, options)
registration.update(options.copy(tintOpacity = 0.42f))
// ...
registration.close()
bridge.close()
```

### `ownsSession` 语义

- 默认 `ownsSession = false`：桥**不会**暂停或关闭外部共享的 `LumenSurfaceSession`；此时调用 `pause()` / `resume()` 会因 `check(ownsSession)` 抛出 `IllegalStateException`。
- `ownsSession = true`：桥允许调用 `pause()` / `resume()`，并在 `close()` 时一并关闭该 session。
- 构造时自动注册 `ui.onClose { close() }`，`UiSession` 关闭会连带关闭桥并逐个关闭所有已登记 `SurfaceRegistration`。

### `bind` 与 `"background"` 属性占用

1. `bind` 先执行 `ui.reserveProperties(ref, setOf("background"))`，再调用 `LumenSurfaceSession.bind(ref.get(), options, source)`。
2. 若同一节点已绑定 `id = "background"` 的 `PropertySpec` 或已保留该槽位，会立即抛出 `Conflicting visual property owner`（或 UI 侧抛出 `Property already owned`）。
3. 节点被 `session.release(ref)` 或 `rebind(ref)` 时会自动触发 `registration.close()`，释放 Lumen binding 与属性占位。

## 与 MeguvizDrawable 的跨栈互斥规则

混用 Lumen 与 MeguvizDrawable 时需遵守以下边界：

- MeguvizDrawable 的 `LumenSurfaceBridge` 使用宿主传入的 `BindingScope.claim(key)` 独占属性键，而 Drawable 侧的 `RecipeBackground` 使用独立创建的 `BindingScope` 走 `UiSession.bind`（默认占用 `"background"`）。
- 两套栈之间不共享同一属性注册表，因此 Lumen 的 `reserveProperties("background")` 与 Drawable `RecipeBackground` 不会在编译或运行时互相报错，但 `LumenSurfaceSession` 对同一 View 再次 `bind` 会静默覆盖旧绑定。
- **操作原则**：不要将同一个 View 的 `"background"` 同时交给 Lumen 表面与 Drawable 配方；请按组件或层级清晰切分归属。`alpha` / `translation` 等动画属性同理应显式保留，避免多方写入冲突。

## 来源

- `ui-lumen-bridge/src/main/kotlin/io/github/jichuo1/meguviz/ui/lumen/LumenUiBridge.kt`
- `ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/UiSession.kt`（`reserveProperties`）
- `settings.gradle.kts`、`gradle/libs.versions.toml`、`docs/bridges.md`
