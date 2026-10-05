package com.openkhub.sensefield;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Button;
import android.widget.LinearLayout;

/** Adds a large, directly reachable explanation entry beside a setting. */
final class SettingHelp {
    static final String EXTRA_KEY = "setting_help_key";

    private SettingHelp() {}

    static Button add(Activity activity, LinearLayout parent, String key, float bottomDp) {
        Button button = UiKit.button(activity, SettingHelpContent.title(key) + "：说明", false);
        button.setTextSize(22);
        button.setMinHeight(UiKit.dp(activity, 72));
        button.setMinimumHeight(UiKit.dp(activity, 72));
        button.setContentDescription(SettingHelpContent.title(key) + "的说明");
        button.setOnClickListener(view -> activity.startActivity(new Intent(activity,
                SettingHelpActivity.class).putExtra(EXTRA_KEY, key)));
        View previous = parent.getChildCount() == 0 ? null : parent.getChildAt(parent.getChildCount() - 1);
        if (previous instanceof CompoundButton || (previous != null && previous.isAccessibilityHeading())) {
            // Keep the explanation beside its setting, including after scrolling
            // or large-font reflow. Reparenting does not change listeners/state.
            parent.removeView(previous);
            LinearLayout row = UiKit.horizontal(activity);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.setMarginEnd(UiKit.dp(activity, 12));
            row.addView(previous, labelParams);
            button.setText("说明");
            row.addView(button, new LinearLayout.LayoutParams(UiKit.dp(activity, 120),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            UiKit.add(parent, row, bottomDp);
        } else UiKit.add(parent, button, bottomDp);
        return button;
    }
}
