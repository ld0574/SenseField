package com.openkhub.sensefield;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import androidx.core.view.ViewCompat;

/** Presentation state only. Persisted preferences and gameplay services keep their own state. */
abstract class UiActivity extends Activity {
    private Bundle pendingUi;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingUi = state.getBundle("sensefield_ui");
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        UiKit.configureWindow(this);
        if (pendingUi == null) return;
        Bundle restore = pendingUi;
        pendingUi = null;
        View root = findViewById(android.R.id.content);
        visit(root, "root", restore, false);
        SettingHelpPanel.restoreState(this, restore);
    }

    @Override protected void onPause() {
        pendingUi = captureUi();
        SettingHelpPanel.finishMotion(this);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBundle("sensefield_ui", captureUi());
        super.onSaveInstanceState(state);
    }

    private Bundle captureUi() {
        Bundle snapshot = new Bundle();
        visit(findViewById(android.R.id.content), "root", snapshot, true);
        SettingHelpPanel.saveState(this, snapshot);
        return snapshot;
    }

    private static void visit(View view, String key, Bundle state, boolean save) {
        if (view == null) return;
        // Help temporarily reparents the page; use the original tree's stable path.
        if (SettingHelpPanel.PANEL_TAG.equals(view.getTag()) && view instanceof ViewGroup) {
            ViewGroup host = (ViewGroup) view;
            for (int i = 0; i < host.getChildCount(); i++) {
                View child = host.getChildAt(i);
                if (!SettingHelpPanel.PANEL_VIEW_TAG.equals(child.getTag())) visit(child, key, state, save);
            }
            return;
        }
        if (view instanceof EditText && view.isSaveEnabled()) {
            EditText input = (EditText) view;
            int variation = input.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
            boolean password = variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
            if (!password) {
                if (save) {
                    state.putString(key + ":text", input.getText().toString());
                    state.putInt(key + ":cursor", input.getSelectionStart());
                } else if (state.containsKey(key + ":text")) {
                    input.setText(state.getString(key + ":text"));
                    input.setSelection(Math.max(0, Math.min(input.length(), state.getInt(key + ":cursor"))));
                }
            }
        } else if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            if (save) state.putInt(key + ":selection", spinner.getSelectedItemPosition());
            else if (state.containsKey(key + ":selection")) {
                int selected = state.getInt(key + ":selection");
                if (selected >= 0 && selected < spinner.getCount()) spinner.setSelection(selected);
            }
        }
        if (view instanceof ScrollView) {
            ScrollView scroll = (ScrollView) view;
            if (save) state.putInt(key + ":scroll", scroll.getScrollY());
            else if (state.containsKey(key + ":scroll")) {
                int position = state.getInt(key + ":scroll");
                // Text and cursor restoration request another layout. Restore after that
                // layout has settled, so a focused input cannot jump the page to its bottom.
                scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        if (scroll.getViewTreeObserver().isAlive())
                            scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        scroll.scrollTo(0, position);
                        return true;
                    }
                });
            }
        }
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("ui_details:")
                && view instanceof ViewGroup) {
            ViewGroup section = (ViewGroup) view;
            View body = section.getChildAt(1);
            String detailKey = (String) view.getTag();
            if (save) state.putBoolean(detailKey, body.getVisibility() == View.VISIBLE);
            else if (state.containsKey(detailKey)) {
                boolean open = state.getBoolean(detailKey);
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                ViewCompat.setStateDescription(section.getChildAt(0), open ? "已展开" : "已折叠");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                visit(group.getChildAt(i), key + "/" + i, state, save);
        }
    }
}
