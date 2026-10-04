package com.openkhub.sensefield;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/** Launch from the visible authorization activity after the capture acknowledgement. */
final class GameLauncher {
    static final String HONOR_OF_KINGS_PACKAGE = "com.tencent.tmgp.sgame";

    private GameLauncher() {}

    static boolean openHonorOfKings(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(HONOR_OF_KINGS_PACKAGE);
        if (launch == null) {
            Toast.makeText(activity, "辅助已启动，未找到王者荣耀，请先安装游戏", Toast.LENGTH_LONG).show();
            Log.i("SenseFieldGameLaunch", "game=honor-of-kings result=not_installed");
            return false;
        }
        // Restore the existing game task as a launcher tap would, rather than restarting a match.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            activity.startActivity(launch);
            Log.i("SenseFieldGameLaunch", "game=honor-of-kings result=opened");
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, "辅助已启动，系统未能打开王者荣耀，请手动打开", Toast.LENGTH_LONG).show();
            Log.w("SenseFieldGameLaunch", "game=honor-of-kings result=blocked");
            return false;
        }
    }
}
