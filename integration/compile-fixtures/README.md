# Kotlin 与 Java 消费编译门禁

先在根目录发布当前本地候选制品，再执行编译门禁脚本：

```sh
./gradlew publishCandidate --no-daemon --max-workers=2
python integration/compile-fixtures/verify.py
```

脚本从 `build/repo` 精确选取发布的 AAR，校验 SHA-256 与模块构建产物一致后提取 `classes.jar`，使用版本目录锁定的 Kotlin 编译器（`2.4.21`）与 Android 37 SDK 编译全部正负用例：

- **正例（`positive/`、`positive-java/` 等）**：覆盖 Kotlin 与 Java 消费路径，包含 `openMeguvizUi`、`String` key 重载、`ViewRef.bind` 与可选桥入口，必须全部编译通过。
- **反例（`negative-parent/`、`negative-ref/`、`negative-scope/`、`negative-state/`）**：验证错误父容器 `LayoutParams`、错误类型 `ViewRef`、作用域越界及向纯数据接口传入非法参数均会被编译器拒绝。

`api/public-api.txt` 记录当前 `1.2.0` 的 JVM 公开描述符基线，仅在经过人工 API 审查后通过 `--update-api` 显式更新。向后兼容性由 `build-logic/verification/legacy_abi.py check` 核验当前 API 是否为 `api/legacy/1.0.0/` 的严格超集（仅覆盖公开 JVM 签名）。

脚本依赖本地配置好的 Kotlin 编译器依赖缓存、Android 37 SDK 与 `javac`（可通过 `--gradle-cache` 与 `--android-jar` 显式指定）；任一环境前置缺失会标记为 blocked 而非放行通过。执行记录写入 `build/reports/compile-fixtures/receipt.json`。
