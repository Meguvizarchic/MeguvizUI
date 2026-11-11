package fixtures;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.jichuo1.meguviz.ui.PropertyBinding;
import io.github.jichuo1.meguviz.ui.ViewRef;
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult;
import io.github.jichuo1.meguviz.ui.widgets.Properties;
import io.github.jichuo1.meguviz.ui.widgets.UiScope;
import io.github.jichuo1.meguviz.ui.widgets.UiScopeKt;
import kotlin.Unit;

/**
 * The Java path the getting-started guide promises: open with one call, then mount, bind and submit
 * through the scope. Compiled with javac against the published AARs; the ViewKey-typed scope calls
 * have mangled JVM names, so this only compiles while the String-key forms exist.
 */
final class JavaConsumer {
    private JavaConsumer() { }

    static SubmitResult firstScreen(Activity activity) {
        UiScope ui = UiScopeKt.openMeguvizUi(activity);
        activity.setContentView(ui.getRoot());
        ViewRef<TextView> title = ui.getScope().text("title", new FrameLayout.LayoutParams(-1, -2));
        PropertyBinding<TextView, String> label = title.bind(Properties.INSTANCE.plainText());
        return label.submit("Updated");
    }

    static ViewRef<LinearLayout> row(UiScope ui) {
        return ui.getScope().linear("row", new FrameLayout.LayoutParams(-1, -2), row -> {
            row.button("ok", new LinearLayout.LayoutParams(-2, -2), button -> {
                button.setText("OK");
                return Unit.INSTANCE;
            });
            row.image("icon", new LinearLayout.LayoutParams(-2, -2));
            row.edit("input", new LinearLayout.LayoutParams(-1, -2));
            return Unit.INSTANCE;
        });
    }
}
