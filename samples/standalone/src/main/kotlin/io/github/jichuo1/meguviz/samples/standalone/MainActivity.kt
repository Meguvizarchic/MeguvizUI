package io.github.jichuo1.meguviz.samples.standalone

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.LinearLayout
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import io.github.jichuo1.meguviz.ui.state.RestorationSnapshot
import io.github.jichuo1.meguviz.ui.state.ViewRestoration
import io.github.jichuo1.meguviz.ui.widgets.Dp
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.UiScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import io.github.jichuo1.meguviz.ui.widgets.openMeguvizUi

/** Counter and draft model belong to the application; restoration only owns presentation. */
class MainActivity : Activity() {
    private lateinit var ui: UiScope
    private lateinit var restoration: ViewRestoration
    private var count = 0
    private var draft = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        count = savedInstanceState?.getInt("count") ?: 0
        draft = savedInstanceState?.getString("draft").orEmpty()
        // One line: root FrameLayout + UiSession + FrameScope, closed with this Activity.
        ui = openMeguvizUi()
        setContentView(ui.root)
        val session = ui.session
        restoration = ViewRestoration(session, savedInstanceState?.getBundle("presentation")?.let(RestorationSnapshot::fromBundle))
        ui.scope.linear(ViewKey("counter"), frameParams(), configure = {
            orientation = LinearLayout.VERTICAL
            val padding = Dp(24f).pixels(context)
            setPadding(padding, padding, padding, padding)
        }) {
            val title = text(ViewKey("count"), linearParams()) {
                text = getString(R.string.count_value, count)
                textSize = 24f
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val binding = session.bind(title, Properties.plainText())
            val input = edit(ViewKey("draft"), linearParams()) {
                // The application model owns text; ViewRestoration owns focus and selection.
                isSaveEnabled = false
                hint = getString(R.string.draft_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(draft)
            }
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    restoration.userInteracted()
                    draft = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            val nativeInput = input.get()
            nativeInput.addTextChangedListener(watcher)
            session.onRelease(input) { nativeInput.removeTextChangedListener(watcher) }
            val increment = button(ViewKey("increment"), linearParams()) { setText(R.string.increment) }
            session.click(increment) {
                restoration.userInteracted()
                count++
                check(binding.submit(getString(R.string.count_value, count)) !is SubmitResult.Rejected)
            }
            restoration.register("counter/title", title, ui.root)
            restoration.register("counter/draft", input, ui.root)
            restoration.register("counter/increment", increment, ui.root)
        }
        restoration.restoreAfterLayout(ui.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("count", count)
        outState.putString("draft", draft)
        outState.putBundle("presentation", restoration.snapshot().toBundle())
        super.onSaveInstanceState(outState)
    }
}
