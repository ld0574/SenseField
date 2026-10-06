package com.openkhub.sensefield;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Brief, user-triggered native motion. Never schedules a loop or animates a game overlay. */
final class UiMotion {
    static final long PANEL_DURATION_MS = 220;
    private UiMotion() { }

    static void reveal(View view, boolean side) {
        reset(view);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0f);
        if (side) view.setTranslationX(UiKit.dp(view.getContext(), 12));
        else view.setTranslationY(UiKit.dp(view.getContext(), 12));
        view.animate().alpha(1f).translationX(0).translationY(0)
                .setDuration(PANEL_DURATION_MS).setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f)).start();
    }

    static void dismiss(View view, boolean side, Runnable finished) {
        reset(view);
        if (!ValueAnimator.areAnimatorsEnabled()) { finished.run(); return; }
        view.animate().alpha(0f).translationX(side ? UiKit.dp(view.getContext(), 12) : 0)
                .translationY(side ? 0 : UiKit.dp(view.getContext(), 12))
                .setDuration(PANEL_DURATION_MS).setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f))
                .withEndAction(finished).start();
    }

    static void reset(View view) {
        view.animate().cancel();
        view.animate().withEndAction(null);
        view.setAlpha(1f);
        view.setTranslationX(0);
        view.setTranslationY(0);
    }
}
