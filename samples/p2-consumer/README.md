# 独立 P2 制品消费者

这个独立 Gradle 工程只从根仓库的 `build/repo` 读取 `1.2.0` Maven 制品（不引用根工程的 `project()` 源码依赖），同时集成 `widgets`、`UiPlan`、`RecyclerView`、文本预计算、`nativeRow`、预热、`SharedFrameDriver` 与 KSP 代码生成器，并在 release 构建开启 R8 混淆与资源压缩。

## 运行步骤

先在根仓库启用全部 P2 模块并发布本地制品，再执行准备脚本与独立构建：

```sh
./gradlew -PenableP2=true verifyP2 publishCandidate --no-daemon --max-workers=2
python samples/p2-consumer/prepare_artifacts.py
./gradlew -p samples/p2-consumer :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --max-workers=2
```

`prepare_artifacts.py` 校验本地产物与 POM 坐标，并更新本样例的校验元数据；每次重新运行 `publishCandidate` 后都应重新执行该脚本。

也可以直接运行一键验证脚本：

```sh
python samples/p2-consumer/verify_consumer.py
```

脚本会将输入指纹、制品校验与构建日志写入 `build/reports/gates/p2-consumer.json`（其中 `test_count` 为 `0`，用于证明独立制品接入与 R8 编译通过，具体交互及运行性能需在设备端另行测试）。
