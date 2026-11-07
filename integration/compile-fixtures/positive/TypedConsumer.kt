package fixtures

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.scheduler.ScheduledOperation
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import io.github.jichuo1.meguviz.ui.state.StateValue
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.UiScope
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import io.github.jichuo1.meguviz.ui.widgets.openMeguvizUi

fun title(session: UiSession, parent: LinearLayout): ViewRef<TextView> =
    LinearScope(session, parent).text(ViewKey("title"), linearParams()) { text = "Typed consumer" }

/** The one-line entry must stay reachable from a plain Activity with no AndroidX dependency. */
fun openUi(activity: Activity): UiScope = activity.openMeguvizUi()

fun pureSnapshot(): StateValue.Record = StateValue.Record(mapOf("title" to StateValue.Text("saved")))

fun orderedUpdate(session: UiSession, first: ScheduledOperation, second: ScheduledOperation): SubmitResult =
    session.scheduler.submitBatch(first, second)
