# 测量方法

> 证据分级与 [tuning.md](tuning.md) 一致：**host 已验证** / **准入记录** / **需真机验证**。
>
> 核心口径：**host JVM 不等于 ART，也不等于真机**。主机探针用于分配与语义回归，真机数据才用于参数与准入。

---

## 1. 总原则

- 只比较**同一轮内**的比值。不同轮次之间，A 组绝对耗时可能相差两倍以上（设备状态不同），应只比较同一轮内的比值（[docs/p2.md](../p2.md) 明确说明）。
- 设备热状态不得超过 1（`p2_performance_criteria.toml` 的 `max_thermal_status = 1`）。
- 模拟器测量不构成性能准入证据（`p2_performance.py` 直接拒绝 `ro.kernel.qemu=1`）。
- 每个样本都要校验产出的原生结构，再记录耗时。
- `verifyHost` 是主机聚合门禁（合同测试、lint、制品一致性、API 基线、生成器消费应用），**不是性能门禁**。

---

## 2. Host 探针

### 2.1 运行方式

```sh
./gradlew :ui-core:schedulerProbe --offline --no-daemon --max-workers=2 --no-configuration-cache
./gradlew :ui-core:schedulerProbeV2 --offline --no-daemon --max-workers=2 --no-configuration-cache
# 可选参数（仅 schedulerProbe / schedulerProbeV2）：
#   -PprobeWarmup=10000 -PprobeIterations=10000
```

`ui-recycler` 的探针需要先启用模块：

```sh
./gradlew -PenableRecycler=true :ui-recycler:latestWorkProbe --offline --no-daemon --no-configuration-cache
```

- 任务注册见 `ui-core/build.gradle.kts:30-70` 与 `ui-recycler/build.gradle.kts:25-42`。
- 任务先运行对应单元测试，再编译探针；堆固定 256m；JDK 17。
- Windows 可用 `gradlew.bat`；遇到 JDK Unix-domain socket 临时目录问题时，把相同参数传给 `scripts/gradle-local.ps1`。
- 探针源码：`scripts/performance/SchedulerProbe.java`、`scripts/performance/SchedulerProbeV2.java`、`scripts/performance/LatestWorkProbe.java`。

### 2.2 SchedulerProbe（v1）

- 场景：`states`、`grouped-states`、`commands`、`batch`；size 取 16 / 64 / 256。
- CSV 列：`scenario,size,iterations,median_nanos,p95_nanos,p99_nanos,bytes_per_round`。
- 默认 warmup 2000 / iterations 4000。
- 每轮包含提交加一次 drain；不包含原生 setter、绘制、跨线程传输或设备调度延迟。
- Java 侧检查 vararg 输入数组在准入后被调用者修改不会改变队列。

### 2.3 SchedulerProbeV2

- 场景：`command-only`、`command-drain`、`state-full`、`state-drain`、`state-reentrant`、`state-partial`、`state-coalesce`、`state-batch`、`state-discard`、`command-discard`、`command-discard-stale`；size 取 16 / 64 / 256。
- 计量前先运行语义检查：命令 FIFO 与合并、重入提交下一帧可见、分组轮转、故障恢复、`close()` 清空队列。
- 分别计时「提交 + drain」与「仅 drain」；部分 drain 场景用 `size/2` 作为操作上限；`state-batch` 的 `StateUpdate` 构造成本计入测量，因此不与逐次 `submitState` 直接比较。
- 默认 warmup 2000 / iterations 4000。
- 完整数据表与口径说明记录在 [docs/development.md](../development.md) 与 [docs/scheduling.md](../scheduling.md)。

### 2.4 LatestWorkProbe

- 默认 `storm = 2000`、`rounds = 5`；测量 owner 线程在连续突发提交下的分配。
- 语义检查：最多一个 running + 一个 pending、只有最新 pending 值运行、后台计算失败不破坏 pending、owner 派发拒绝会关闭控制器并阻止后续提交。
- 该探针只覆盖 `ui-recycler` 的 `LatestWork`，不含 adapter 必需的列表快照数组，也不能外推为 Android 帧性能。

### 2.5 Host 口径限制

- `ThreadMXBean` 只计当前线程分配；HotSpot 逃逸分析可能消除临时对象，因此某些「改前」对照会被 JIT 标量替换，需要改用推算口径（见 `docs/scheduling.md` 的 `state-coalesce` 说明）。
- 不含 Android 绘制、Choreographer、ART、设备调度延迟。
- 主机结果是探索性证据，不是 ART、帧耗时或设备性能承诺。

---

## 3. 设备 P2 性能准入

### 3.1 运行命令

```sh
# 主机聚合门禁（单测、lint、制品一致性、API 基线、生成器消费应用）
./gradlew -PenableP2=true verifyP2 --no-daemon --max-workers=2

# 设备性能准入（需要物理机）
./gradlew -PenableP2=true verifyP2Performance \
  -Pp2PerformanceSerial=<adb serial> -PallowDeviceInstall=true \
  --no-daemon --max-workers=2
```

- `verifyP2Performance` 只安装或替换基准自身的两个包。
- 设备必须是物理机；热状态前后都不得超过 1。
- 基准构建为不可调试、`speed` AOT 编译，组间按 ABBA 交替，默认每组 20 次预热、300 个样本。
- 结果写入 `build/reports/gates/p2-performance.json`，原始日志写入 `build/reports/device/p2-performance/`。
- 仪器 APK 的编译不等于设备测试。

### 3.2 ABBA 交错与采样

实现见 `samples/p2-benchmark/src/androidTest/kotlin/io/github/jichuo1/meguviz/samples/p2benchmark/P2PerformanceInstrumentation.kt`：

- 每次迭代内按 `AB` 或 `BA` 交替（偶数迭代 `AB`，奇数迭代 `BA`），消除首位置偏差。
- 每个样本前执行 `Runtime.getRuntime().gc()` 与 `waitForIdleSync()`。
- 默认 warmup 20、samples 300；warmup 样本丢弃；每个样本都会校验产出的原生结构。
- 前台 Activity 保持进程不被后台冻结、vsync 持续运行。

### 3.3 配对统计

实现见 `build-logic/verification/p2_performance.py`：

- **paired-ratio**（`plan`、`codegen`、`layouts`、`frame-shared`）：同一 ABBA 迭代中的 A、B 样本组成一对，取 `r_i = B_i / A_i`，点估计为中位数，按样本对重采样求 bootstrap 95% CI。`bounded-overhead` 要求 CI 上界 ≤ `max_ratio`。配对能抵消一对样本共同经历的设备漂移（调频、大小核迁移）。
- **median-ratio**（`recycler`、`text`、`prewarm`）：两组中位数之比，两组各自独立重采样。`benefit` 要求 CI 上界 < `max_ratio`。
- 准则文件 `build-logic/verification/p2_performance_criteria.toml`：`minimum_samples = 300`、`bootstrap_resamples = 2000`、`bootstrap_seed = 20261027`、`max_thermal_status = 1`。阈值是测量前定下的设计阈值，计入源码指纹；修改阈值或统计方法是评审过的决定，不能用来把失败运行变绿。

### 3.4 Profile 与阈值

| profile | 场景 | 统计 | kind | max_ratio |
| --- | --- | --- | --- | --- |
| plan | 48-node-page-construction | paired-ratio | bounded-overhead | 1.25 |
| codegen | 64-view-registry-mount | paired-ratio | bounded-overhead | 1.10 |
| recycler | 16-submission-burst-2000-items | median-ratio | benefit | 0.90 |
| text | 3000-char-plain-label-apply | median-ratio | benefit | 0.90 |
| layouts | 8-row-construct-measure-layout | paired-ratio | bounded-overhead | 1.10 |
| prewarm | 48-row-page-first-switch | median-ratio | benefit | 0.50 |
| frame-shared | 32-session-shared-frame-dispatch | paired-ratio | bounded-overhead | 1.25 |

### 3.5 准入记录的读法

- [docs/p2.md](../p2.md) 的「性能准入」表是 2026-10-27 按源码指纹 `431d13…` 在 PMA110（Android 17 / API 37，arm64）上的实测记录，属于**单台物理机、无 R8、分离层级**（不含 draw、掉帧和堆）的准入记录。
- `frame-shared` 尚无实测数据；它的准则是 `bounded-overhead` 而非 `benefit`，允许「不值得启用」的否定结论。
- 这些数字不是普适值；每个应用仍需以自己的负载重新准入。

---

## 4. 启动基准（benchmarks/）

### 4.1 采集

`benchmarks/collect_startup.py`：

- 只测 `am start -W -S` 的 `TotalTime`；不测量 frameOverrun、导航、堆或 UI 正确性。
- 需要 `adb` 与显式 `--allow-restart`；校验 APK SHA 与构建回执、源码 commit；不清理应用数据。
- 默认 `--samples 100`、`--warmup 10`；记录热状态前后；可选 Perfetto 归因（单独的不定时启动）。
- 实验组 A–E 见 `benchmarks/scenarios.json`（BIL 原始实现 / 独立验证策略 / MeguvizUI 直接构造 / 同策略 / 启用 Lumen 协作）；当前 `status = blocked_no_device`。

### 4.2 汇总

`benchmarks/summarize.py`：

- 只接受 `schema_version = 1`、`record_kind = measured`、真实 40 位 commit 的记录。
- 校验原始制品 SHA；**绝不合成缺失样本**。
- 分位数方法为 nearest rank；`p95` 需 ≥100 样本，`p99` 需 ≥1000 样本。
- 单位限 `ns` / `us` / `ms` / `bytes` / `count` / `ratio`。

---

## 5. 口径限制清单

- **host JVM ≠ ART/真机**：HotSpot 逃逸分析、GC、类加载与 ART 不同，主机分配与延迟不能外推。
- **分离层级**：现有设备基准不含 draw、frame overrun、堆、R8 混淆与 OEM 兼容性。
- **单设备 / 单 API**：其他设备、OEM 与 API 版本未测量；声明最低 SDK 不等于每个版本均已通过设备认证。
- **热状态**：准入要求 ≤1；后台负载会影响 p95/p99。
- **模拟器不构成准入证据**。
- **跨轮绝对时间不可比**：只比较同一轮内的比值。
- **尾延迟需要足够样本**：p95 ≥100、p99 ≥1000；样本不足时不要下结论。
- 每个应用仍需以自己的负载、设备和性能目标重新准入。
