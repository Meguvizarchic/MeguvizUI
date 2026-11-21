# 可选桥与依赖边界

三个桥接模块默认不参与核心构建，在源码仓库内通过 Gradle 属性按需开启：

```sh
./gradlew -PenableLumen=true -PenableRef=true -PenableAndroid=true verifyHost
```

三个桥均不进入 `meguviz-ui-core` 或 `meguviz-ui-widgets` 的传递依赖。

**独立运行是默认能力，桥接仅作增强**。MeguvizUI 不强依赖 MeguvizAndroid、MeguvizDrawable、MeguvizRef 或 Lumen；各兄弟库也可脱离 MeguvizUI 独立使用。仅在工程实际使用对应宿主能力时才添加对应的桥模块。

## Maven Central 与源码构建状态（`1.2.0`）

| 桥接模块 | Maven 制品 | 上游依赖 | 当前分发状态 |
| --- | --- | --- | --- |
| Ref 桥（`ui-ref-bridge`） | `meguviz-ui-ref-bridge:1.2.0` | `meguviz-ref-core:1.2.0` | 已发布到 Maven Central（含在 `meguviz-bom:1.2.0`） |
| Android 桥（`ui-android-bridge`） | `meguviz-ui-android-bridge:1.2.0` | `meguviz-android-runtime:1.2.0`、`meguviz-android-window:1.2.0` | 已发布到 Maven Central（含在 `meguviz-bom:1.2.0`） |
| Lumen 桥（`ui-lumen-bridge`） | `meguviz-ui-lumen-bridge:1.2.0` | JitPack `LumenCoacervationEngine:lumen-engine:1.5.5` | **尚未发布到 Central**，仅支持源码启用 `-PenableLumen=true` 本地构建 |

> 补充说明：`LumenCoacervationEngine` 本身已发布 `io.github.jichuo1:lumen-engine:1.6.2` 到 Maven Central 与 JitPack，但本仓库当前 `gradle/libs.versions.toml` 仍锁定 JitPack `1.5.5`（参见 [Lumen 桥接指南](guides/bridges-lumen.md)）。

## 源码联调本地上游 `-SNAPSHOT`

若在源码中联调尚未发布的上游 `-SNAPSHOT` 变更，`settings.gradle.kts` 仅将 `*-SNAPSHOT` 版本的 `meguviz-android-runtime` / `meguviz-android-window` 路由到 `build/upstream-repo/`。可通过脚本导入通过哈希验证的本地 Maven 仓库：

```sh
python scripts/import-upstream-candidates.py --repository <android-maven-repository> --artifact meguviz-android-runtime --artifact meguviz-android-window
```

常规已发布版本 `1.2.0` 直接从 `mavenCentral()` 解析并受 `gradle/upstream-candidates.toml` 与 Gradle 校验元数据保护，不得放宽哈希或版本号校验。

## 三个桥的真实边界

- **Lumen 桥**：使用真实 `LumenSurfaceSession` 与 `LumenSurfaceBinding`，为宿主保留 `"background"` 属性槽；材质、采样与动画由 Lumen 管理，是否随桥关闭由 `ownsSession` 决定（默认 `false`）。
- **Ref 桥**：使用真实 `CodeScope`、`QuerySpec`、`ConstructorPlan` 与 `MethodPlan`；UI 侧持有独立 receiver lease，同时检查代码域 epoch 与 `ViewRef` 生命周期，不伪造上游接口，也不维护全局 receiver 缓存。
- **Android 桥**：使用真实 `WindowSession`、`FrameScheduler.FrameChannel` 与 `ResourceState` 权威；在同一上游帧回调中执行有界 UI drain。必须先关闭 `AndroidUiBinding`，再关闭共享窗口或 `FrameScheduler`。

## 联合快路径与后台任务

- 普通 UI 属性继续通过 `PropertyBinding.submit` 更新，Lumen 材质由 Lumen 独立管理；不要把同一个 View 的 `"background"` 同时交给两个系统写入。
- 有 `WindowSession` 时使用 `AndroidUiBinding.open(window, root)` 复用上游帧通道，不在同一回调外另投额外的 `Handler` 消息或第二次 `Choreographer` 请求。
- `UiSession`、属性绑定与帧通道的创建及关闭都在主线程完成。后台线程只准备纯数据结果，再通过 `UiSession.submitStateAsync` / `submitCommandAsync` 或宿主有界 mailbox 投递回主线程。
- 状态通道只适合允许覆盖中间值的展示属性；需保留次数的交互与业务操作走命令通道。取消、过期与投递拒绝均应显式清理，不通过改大成无界队列掩盖背压。
- 与 Drawable 联动时，`Drawable` 的 single-flight 缓存共享的是已准备结果，多订阅者各自遵守安装与释放契约，不能销毁同一缓存实例。

## 适用范围与验证说明

验证新 runtime 实现与 UI 的兼容性应分两步执行：先运行上游 runtime 自身合同，再使用精确构建制品运行桥接合同；源码路径或签名一致不能替代实际制品联调。主机接口检查与单元测试用于验证协议与调度语义，不替代真实 Android 设备、ART 运行时或视觉渲染测试。

[返回文档目录](README.md)
