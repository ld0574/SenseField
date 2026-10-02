package com.openkhub.sensefield;

import android.app.Application;

/** Fresh processes recover orphaned local records before the diagnostic IO queue opens new ones. */
public final class SenseFieldApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // This runs only at process creation, before any CaptureService start.
        android.content.SharedPreferences settings = GameProfile.settings(this);
        if (settings.getBoolean("capture_active", false)) {
            settings.edit().putBoolean("capture_active", false)
                    .putBoolean("capture_paused", false)
                    .putBoolean("capture_waiting_for_image", false)
                    .putString("last_capture_status", "上次辅助已结束，请重新开始").apply();
        }
        DiagnosticRecorder.recoverIncomplete(this);
    }
}
