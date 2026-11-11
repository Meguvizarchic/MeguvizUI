# 真实 Kotlin 消费编译门禁

先构建并发布当前候选，再执行 `python integration/compile-fixtures/verify.py`。脚本从隔离 `build/repo` 的 Maven snapshot metadata 精确选择两个候选 AAR，核对其 SHA 与本次 release 输出一致，提取真正 `classes.jar`，使用版本目录指定的 Kotlin 编译器和公开 Android SDK 编译消费代码。

`positive-java/` 是 Java 消费者，由 `javac`（须在 PATH 上）针对同一组候选 `classes.jar` 编译：`ViewKey` 参数的作用域方法在 JVM 上是带 `-` 的 mangled 名，Java 只能调用字符串 key 的那一组，这个正例保证 Java 的一行入口之后确实能挂载、绑定和提交。

正例必须成功；错误父 LP、错误 typed ref、向纯数据快照塞 View 的反例必须由编译器报告类型不匹配。任意网络错误、缺类、编译器启动失败均不能充当反例通过。每项命令、退出码、日志、输入哈希与候选坐标保存在 `build/reports/compile-fixtures/receipt.json`。

`api/public-api.txt` 是初次候选的 JVM 公开描述符基线。只有经过人工审查 API 变化后才显式执行 `--update-api`；普通验证不重写基线。它包含 Kotlin 编译生成的公开 ABI，因此同时保留真实 Kotlin 正负消费编译。这份基线是当前候选自己的快照，本身证明不了历史二进制兼容；与已发布 1.0.0 的比较由 `build-logic/verification/legacy_abi.py check` 完成（基线在 `api/legacy/1.0.0/`，取自 Maven Central 的制品）。两者都只覆盖公开 JVM 描述符，不覆盖行为。

脚本需要已有精确 Kotlin compiler POM 与依赖缓存、公开 Android 37 SDK、JDK 的 `java/javap`。未解析到正确依赖时 fail closed 并记录 blocked，不下载替代版本。`--gradle-cache` 与 `--android-jar` 可显式配置环境。
