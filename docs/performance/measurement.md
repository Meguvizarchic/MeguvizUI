# 测量方法

本文说明 MeguvizUI 的主机分配探针与真机 P2 A/B 性能基准测试的运行方式、统计口径与限制边界。

## 1. 测量总原则

- **主机 JVM 与真机 ART 分离**：HotSpot 逃逸分析、GC 与类加载机制与 ART 不同，主机探针仅用于分配与调度逻辑回归。
- **同轮比值优先**：不同轮次受设备温控、频率与调度影响，绝对耗时可能差异较大；对比时应比较同一轮内 A/B 比值。
- **设备前置条件**：真机基准只认物理设备（测试驱动拒绝模拟器 `ro.kernel.qemu=1`），运行前后热状态不得超过 `1`，且每个样本都必须校验生成的原生 `View` 结构。

---

## 2. Host 主机探针

### 2.1 运行命令

```sh
./gradlew :ui-core:schedulerProbe --offline --no-daemon --max-workers=2 --no-configuration-cache
./gradlew :ui-core:schedulerProbeV2 --offline --no-daemon --max-workers=2 --no-configuration-cache
# 可选参数：-PprobeWarmup=10000 -PprobeIterations=10000

# RecyclerView LatestWork 探针：
./gradlew -PenableRecycler=true :ui-recycler:latestWorkProbe --offline --no-daemon --no-configuration-cache
```

Windows 下可使用 `gradlew.bat`（或 `scripts/gradle-local.ps1`）。各任务固定使用 JDK `17`、固定堆 `256m`，运行前会先执行对应模块的单元测试。探针源码见 `scripts/performance/SchedulerProbe.java`、`SchedulerProbeV2.java` 与 `LatestWorkProbe.java`。

### 2.2 探针覆盖场景

- **`SchedulerProbe`（v1）**：覆盖 `states`、`grouped-states`、`commands`、`batch`（规模 `16` / `64` / `256`，默认 warmup `2000` / iterations `4000`），记录中位数、p95、p99 耗时与每轮分配字节；包含对 `vararg` 输入数组外部修改隔离的检查。
- **`SchedulerProbeV2`**：细分为「提交 + drain」与「仅 drain」，覆盖 `command-only`、`command-drain`、`state-full`、`state-drain`、`state-reentrant`、`state-partial`、`state-coalesce`、`state-batch`、`state-discard`、`command-discard`、`command-discard-stale`、`state-async` 与 `state-async-coalesce`；计量前先验证 FIFO、合并、公平轮转、故障恢复与关闭清空语义。
- **`LatestWorkProbe`**：默认 `storm = 2000`、`rounds = 5`，测量 `ui-recycler` 内部 `LatestWork` 在连续突发提交下的 owner 线程分配（不含 adapter 列表快照数组）。

---

## 3. 设备 P2 性能验收基准

### 3.1 运行命令

```sh
# 主机聚合检查（单测、lint、制品一致性、API 基线与独立消费样例）
./gradlew -PenableP2=true verifyP2 --no-daemon --max-workers=2

# 物理真机性能测量
./gradlew -PenableP2=true verifyP2Performance \
  -Pp2PerformanceSerial=<adb serial> -PallowDeviceInstall=true \
  --no-daemon --max-workers=2
```

该任务使用不可调试、`speed` AOT 编译的基准安装包，仅安装或替换基准自身两个包；结果输出至 `build/reports/gates/p2-performance.json`，原始日志保存至 `build/reports/device/p2-performance/`。

### 3.2 ABBA 交错与配对统计

实现位于 `samples/p2-benchmark/.../P2PerformanceInstrumentation.kt` 与 `build-logic/verification/p2_performance.py`：
- **ABBA 交错**：偶数轮按 `AB`、奇数轮按 `BA` 顺序执行，每个样本前执行 GC 与 `waitForIdleSync()`，默认 `warmup = 20`、`samples = 300`。
- **paired-ratio（配对比值）**：`plan`、`codegen`、`layouts`、`frame-shared` 对同轮 `A`/`B` 样本计算 `B_i / A_i` 中位数与 bootstrap 95% 置信区间（2000 次重采样，seed `20261027`），要求 CI 上界 `<= max_ratio`。
- **median-ratio（中位数比）**：`recycler`、`text`、`prewarm` 分别对两组独立重采样求中位数之比，要求 CI 上界 `< max_ratio`。

### 3.3 判定阈值（`build-logic/verification/p2_performance_criteria.toml`）

| profile | 测试场景 | 统计方法 | 类别 | `max_ratio` |
| --- | --- | --- | --- | --- |
| `plan` | `48-node-page-construction` | `paired-ratio` | `bounded-overhead` | `1.25` |
| `codegen` | `64-view-registry-mount` | `paired-ratio` | `bounded-overhead` | `1.10` |
| `recycler` | `16-submission-burst-2000-items` | `median-ratio` | `benefit` | `0.90` |
| `text` | `3000-char-plain-label-apply` | `median-ratio` | `benefit` | `0.90` |
| `layouts` | `8-row-construct-measure-layout` | `paired-ratio` | `bounded-overhead` | `1.10` |
| `prewarm` | `48-row-page-first-switch` | `median-ratio` | `benefit` | `0.50` |
| `frame-shared` | `32-session-shared-frame-dispatch` | `paired-ratio` | `bounded-overhead` | `1.25` |

---

## 4. 启动基准参考口径（外部消费工程脚本）

消费侧启动基准脚本（如 `collect_startup.py` / `summarize.py`）保存在消费工作区，不随本仓库提交。若需在应用侧自建同类采集，建议遵循以下口径：
- 仅测 `am start -W -S` 的 `TotalTime`，不清理应用数据，记录热状态并校验 APK SHA-256 与构建回执。
- 汇总时仅接受真实提交实测数据，禁止合成缺失样本；计算 `p95` 不少于 100 样本，`p99` 不少于 1000 样本。

---

## 5. 适用范围与限制说明

- 主机探针结果受 HotSpot 逃逸分析影响（部分临时对象会被标量替换），不含 Android 绘制、Choreographer 调度及设备渲染耗时，不能直接外推为设备表现。
- 仓库内 P2 基准记录（见 [docs/p2.md](../p2.md)）仅反映单台物理设备（PMA110，Android 17 / API 37）在无 R8 分离层级下的数据；各应用须根据自身业务负载、机型及 R8 配置在目标设备重新测量。
