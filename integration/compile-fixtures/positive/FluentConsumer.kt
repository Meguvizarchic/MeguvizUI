package fixtures

import android.view.View
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.PropertyBinding
import io.github.jichuo1.meguviz.ui.PropertyOwnership
import io.github.jichuo1.meguviz.ui.PropertySpec
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.widgets.Properties

/** Fluent ViewRef.bind consumer; the call delegates to UiSession.bind, so validation is unchanged. */
fun bindFluentText(ref: ViewRef<TextView>): PropertyBinding<TextView, String> =
    ref.bind(
        PropertySpec<TextView, String>(
            id = "fluent.text",
            ownership = PropertyOwnership.SHARED_READ,
            read = { it.text.toString() },
            write = { view, text -> view.text = text },
        ),
    )

fun bindFluentVisibility(ref: ViewRef<View>): PropertyBinding<View, Int> =
    ref.bind(Properties.visibility())
