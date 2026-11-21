# 05 · 状态保存与恢复

## 职责边界

`ViewRestoration` 只负责**表现层**状态：焦点、选区与滚动位置。业务模型（计数、草稿等）由应用自行保存；**文本内容永远不从 UI 快照恢复**（源码：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/state/ViewRestoration.kt`）。

正确的初始化顺序：

1. 先恢复应用权威模型（如 `count`、`draft`）。
2. 构建并挂载 `View`，用恢复的模型填充文本或状态。
3. 使用 `register(path, ref, root)` 登记需要恢复表现状态的节点。
4. 调用 `restoreAfterLayout(root)`，在布局完成后恢复焦点、选区和滚动。

## 完整示例

```kotlin
package com.example.demo

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.state.RestorationSnapshot
import io.github.jichuo1.meguviz.ui.state.ViewRestoration
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams

class MainActivity : Activity() {
    private lateinit var session: UiSession
    private lateinit var restoration: ViewRestoration
    private var count = 0
    private var draft = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 先恢复权威模型。
        count = savedInstanceState?.getInt("count") ?: 0
        draft = savedInstanceState?.getString("draft").orEmpty()

        val root = FrameLayout(this)
        setContentView(root)

        session = UiSession(CreationEnvironment(this))
        // 2. 传入已保存的表现快照（presentation）。
        restoration = ViewRestoration(
            session,
            savedInstanceState?.getBundle("presentation")?.let(RestorationSnapshot::fromBundle),
        )

        val title = FrameScope(session, root).text(ViewKey("count"), frameParams()) {
            text = "Count: $count"
        }
        val binding = title.bind(Properties.plainText())

        val input = FrameScope(session, root).edit(ViewKey("draft"), frameParams()) {
            isSaveEnabled = false
            setText(draft)
        }
        val nativeInput = input.get()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                restoration.userInteracted()          // 用户新交互撤销旧恢复意图
                draft = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        nativeInput.addTextChangedListener(watcher)
        session.onRelease(input) { nativeInput.removeTextChangedListener(watcher) }

        // 3. 登记需要恢复表现状态的引用；path 在同一快照内必须唯一。
        restoration.register("counter/title", title, root)
        restoration.register("counter/draft", input, root)

        // 4. 布局后恢复。
        restoration.restoreAfterLayout(root)

        binding.submit("Count: $count")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // 业务模型与表现快照分开保存。
        outState.putInt("count", count)
        outState.putString("draft", draft)
        outState.putBundle("presentation", restoration.snapshot().toBundle())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // ViewRestoration 通过 session.onClose 自动关闭，无需单独调 close()。
        session.close()
        super.onDestroy()
    }
}
```

## API 说明

| 方法 | 作用 | 源码 |
| --- | --- | --- |
| `ViewRestoration(session, restored = null)` | 构造；内部注册 `session.onClose { close() }` 自动清理 | `ViewRestoration.kt` |
| `register(path, ref, root)` | 登记 `path` 与 `ViewRef`，分配不冲突的稳定视图 `id` | `ViewRestoration.kt` |
| `restoreAfterLayout(root)` | 注册 `OnGlobalLayoutListener`，在首次布局后恢复滚动、选区与焦点 | `ViewRestoration.kt` |
| `snapshot()` | 采集当前已登记节点的表现状态 | `ViewRestoration.kt` |
| `userInteracted()` | 递增交互代际并取消尚未触发的旧恢复动作 | `ViewRestoration.kt` |
| `RestorationSnapshot.toBundle()` / `fromBundle(bundle)` | 纯数据序列化；当前 schema 版本为 `1` | `ViewRestoration.kt` |

注意事项：
- `register` 的 `path` 在同一快照内必须唯一，重复会抛出 `Duplicate restore path`。
- 如果控件已设置原生 `view.id`，该 `id` 必须完全交由调用方的 hierarchy restoration 管理，否则抛出 `Explicit native ID must be managed by the caller's hierarchy restoration`。
- `restoreAfterLayout` 仅在 `root.isLaidOut` 后执行一次；等待布局期间若触发 `userInteracted()`，待恢复动作会被跳过。
- 自定义控件私有的 `hierarchy state` 需要专门的 `StateCodec` 适配，`ViewRestoration` 不自动托管。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Duplicate restore path` | 同一快照内多次登记同一 `path` | 确保每个受管节点有唯一 `path` |
| `Duplicate restoration node` | 反序列化后的 `Bundle` 含重复节点 | 清理旧快照或核对序列化逻辑 |
| `Unknown restoration schema` | `Bundle` 的 `schema` 不是 `1` | 显式拒绝或升级迁移，不强行解析 |
| `Restoration node limit exceeded` | 节点数超过 `10000` 上限 | 缩减登记范围或拆分快照 |
| `Explicit native ID must be managed by the caller's hierarchy restoration` | 视图已有非 `NO_ID` 原生 `id` 且与快照不匹配 | 移除手工 `view.id` 设置，或交由原生层级恢复管理 |
| `Session is not active` | 会话已关闭或非活跃 | 检查生命周期 |
| `View reference released` | `register` 或 `snapshot` 时节点已释放 | 确保在 `release` 前采集快照 |

## 下一步

- [示例索引](../examples.md)：`samples/standalone` 提供了上述流程的可运行完整示例。
- [核心合同](../contracts.md) · [状态与命令](04-state-and-commands.md)
