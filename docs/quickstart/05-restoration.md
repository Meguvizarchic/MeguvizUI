# 05 · 状态保存与恢复

## 职责边界

`ViewRestoration` 只负责**表现层**状态：焦点、选择、滚动。业务模型（计数、草稿等）由应用自己保存；**文本永远不从 UI 快照恢复**（源码注释：`ui-core/src/main/kotlin/io/github/jichuo1/meguviz/ui/state/ViewRestoration.kt`）。

正确顺序是：

1. 先恢复权威模型（例如 `count`、`draft`）。
2. 再挂载 View，并用模型填充。
3. 用 `register` 登记需要恢复的 `ViewRef`。
4. 最后 `restoreAfterLayout(root)`，在布局后恢复焦点/选择/滚动。

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
        val binding = session.bind(title, Properties.plainText())

        val input = FrameScope(session, root).edit(ViewKey("draft"), frameParams()) {
            isSaveEnabled = false
            setText(draft)
        }
        val nativeInput = input.get()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                restoration.userInteracted()          // 用户交互可撤销旧恢复
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
        // ViewRestoration 通过 session.onClose 自动 close，不需要单独调用。
        session.close()
        super.onDestroy()
    }
}
```

## API 说明

| 方法 | 作用 | 源码 |
| --- | --- | --- |
| `ViewRestoration(session, restored = null)` | 创建；内部用 `session.onClose { close() }` 自动关闭 | `ViewRestoration.kt` |
| `register(path, ref, root)` | 登记 path 与 `ViewRef`，并分配稳定 id | `ViewRestoration.kt` |
| `restoreAfterLayout(root)` | 注册全局布局监听，布局后恢复滚动/选择/焦点 | `ViewRestoration.kt` |
| `snapshot()` | 采集当前表现状态 | `ViewRestoration.kt` |
| `userInteracted()` | 记录用户已交互，撤销待恢复 | `ViewRestoration.kt` |
| `RestorationSnapshot.toBundle()` / `fromBundle(bundle)` | 纯数据序列化；schema 当前为 1 | `ViewRestoration.kt` |

注意：

- `register` 的 `path` 是字符串路径，同一快照内重复会抛 `Duplicate restore path`。
- 如果 View 已有原生 `id`，该 id 必须由调用方的 hierarchy restoration 管理，否则抛 `Explicit native ID must be managed by the caller's hierarchy restoration`。
- `restoreAfterLayout` 只在 `root.isLaidOut` 后触发一次；如果期间发生新的 `userInteracted()`，旧恢复会被取消。
- 自定义控件的私有 hierarchy state 需要专门适配，`ViewRestoration` 不负责。

## 常见报错

| 报错 | 通常原因 | 处理 |
| --- | --- | --- |
| `Duplicate restore path` | 同一快照内 `register` 的 path 重复 | 换 path |
| `Duplicate restoration node` | 保存的 Bundle 里有重复节点 | 清理旧快照或升级 schema |
| `Unknown restoration schema` | Bundle 的 `schema` 不是 1 | 明确拒绝或迁移，不要强行解析 |
| `Restoration node limit exceeded` | 节点数超过上限 | 减少登记数量 |
| `Explicit native ID must be managed by the caller's hierarchy restoration` | View 已有原生 id，但未纳入调用方恢复 | 由 hierarchy restoration 管理该 id，或改用库分配的 id |
| `Session is not active` | 会话已 close/detach/FAULTED | 检查生命周期 |
| `View reference released` | `register`/`snapshot` 时引用已释放 | 确保注册发生在释放前 |

## 下一步

- [示例索引](../examples.md)：`samples/standalone` 是这份代码的完整可运行版本。
- [核心合同](../contracts.md) · [状态与编解码](../state-codec.md)
