# 可选桥与依赖边界

所有桥均默认关闭，通过 Gradle 属性分别启用：

```sh
./gradlew -PenableLumen=true -PenableRef=true -PenableAndroid=true verifyHost
```

这条命令仅在对应依赖已经准备好时适用。三个桥均不进入普通 core/widgets 的传递依赖。

**独立运行是默认能力，联动只是增强。** MeguvizUI 不要求 MeguvizAndroid、MeguvizDrawable、Ref 或 Lumen；反过来，两兄弟库也无需 UI 才能使用。只开启实际需要的桥，任一方缺席时使用各库自己的调度、属性或绘制入口。核心优化不以启用桥为前提；联合合同和性能工具也不挂到默认核心构建，不自动连接设备或外部服务。

## Lumen

`ui-lumen-bridge` 使用 `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5`，从限定 group 的 JitPack 仓库解析。桥使用真实 `LumenSurfaceSession` 与 binding，为视觉属性保留所有权；材质、动画和绘制仍由 Lumen 负责。会话是否随桥关闭由明确的所有权参数决定。

## Ref 与 Android

Ref 桥依赖 `io.github.jichuo1.meguviz:meguviz-ref-core:1.2.0`，Android 桥依赖 `meguviz-android-runtime`/`meguviz-android-window` 1.2.0。三者都是正式版本号，从 Maven Central 解析；[upstream-candidates.toml](../gradle/upstream-candidates.toml) 与 Gradle 校验元数据固定的是各自本地 1.2.0 候选的 SHA-256。

上游 1.2.0 发布到 Central 之前，这两个桥在默认仓库下无法解析；发布后必须确认 Central 字节与 pins 一致，不一致时重新审阅并更新 pins，不能直接放宽校验。

`build/upstream-repo/` 镜像只用于 SNAPSHOT 形式的 pin（`settings.gradle.kts` 只把 `-SNAPSHOT` 版本交给它）。改用 SNAPSHOT 候选时，先取得与 pins 一致的 Maven 仓库再导入：

```sh
python scripts/import-upstream-candidates.py --repository <android-maven-repository> --artifact meguviz-android-runtime --artifact meguviz-android-window
```

脚本验证所有输入后导入 `build/upstream-repo/`，不构建或修改上游项目。版本或哈希不一致会拒绝；没有这些制品时仍可构建默认的核心与示例。

Ref 桥使用真实 `CodeScope`、`QuerySpec`、`ConstructorPlan` 与 `MethodPlan`。UI 自己的 receiver lease 同时检查代码身份与 View 生命周期，不伪造上游接口，也不维护全局 receiver 缓存。

Android 桥使用真实 `WindowSession`、`FrameScheduler.FrameChannel` 与资源状态权威。在同一帧通道内执行 UI 更新，避免额外调度器。窗口所有者必须先关闭 binding，再关闭共享窗口或 scheduler；外部关闭的可观测性受上游公开 API 限制。

## 联合快路径与后台任务

- 普通 UI 属性仍通过 `PropertyBinding.submit` 提交，Lumen 的材质与动画仍由 Lumen 管理；不要把共享背景同时交给两个库写入。
- 有窗口时优先用 `AndroidUiBinding.open(window, root)` 复用上游帧通道。桥在同一次上游帧回调中执行有界 UI drain，不再投递一个 Handler 消息或第二次 Choreographer 帧。
- `UiSession`、属性绑定、帧通道的建立/关闭都在 owner 主线程。后台线程只计算脱离 View/Context 的结果，再通过宿主的有界 mailbox 或 owner dispatcher 投递；后台直接调用 UI setter/`UiScheduler` 不受支持。
- mailbox 的 state 通道只适合允许丢弃中间值的状态，command 通道保留调用次数。投递拒绝、任务取消、资源失效和过期结果都必须明确释放，不把队列扩成无界以掩盖背压。
- Drawable 的 single-flight 缓存共享的是已准备结果，不共享可变 Drawable 实例。多订阅者不能各自销毁同一个缓存值；安装与销毁继续遵守 Drawable 自己的所有权契约。

新 runtime 实现与 UI 的兼容性应分两步验证：先运行 runtime 自身合同，再用精确制品运行 UI 桥合同。源码路径、相同版本字符串或 API 签名一致都不能代替实际候选制品的联合测试；不得为了试跑覆盖 pins 或已发布的 1.0.0。

Host 接口与合同检查不等于 Android/ART、视觉或完整设备认证。

[返回文档目录](README.md)
