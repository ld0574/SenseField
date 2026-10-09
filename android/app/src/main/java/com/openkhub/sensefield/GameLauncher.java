package com.openkhub.sensefield;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import android.content.pm.ResolveInfo;
import java.util.Locale;

/** Launch from the visible authorization activity after the capture acknowledgement. */
final class GameLauncher {
    static final String HONOR_OF_KINGS_PACKAGE = "com.tencent.tmgp.sgame";

    private GameLauncher() {}

    static boolean openHappyAnipop(Activity activity) {
        String selected = null;
        for (ResolveInfo entry : activity.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (entry.activityInfo == null) continue;
            String name = entry.activityInfo.packageName;
            String lower = name.toLowerCase(Locale.ROOT);
            CharSequence label = entry.loadLabel(activity.getPackageManager());
            boolean isAnipop = lower.startsWith("com.happyelements.androidanimal")
                    || (lower.contains("happyelements") && label != null && label.toString().contains("开心消消乐"));
            if (!isAnipop) continue;
            if (selected == null || "com.happyelements.AndroidAnimal".equals(name)) selected = name;
        }
        Intent launch = selected == null ? null : activity.getPackageManager().getLaunchIntentForPackage(selected);
        if (launch == null) {
            Toast.makeText(activity, "辅助已启动，未找到开心消消乐，请先安装游戏", Toast.LENGTH_LONG).show();
            Log.i("SenseFieldGameLaunch", "game=happy-anipop result=not_installed");
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            activity.startActivity(launch);
            Log.i("SenseFieldGameLaunch", "game=happy-anipop result=opened package=" + selected);
            return true;
        } catch (ActivityNotFoundException | SecurityException blocked) {
            Toast.makeText(activity, "辅助已启动，系统未能打开开心消消乐，请手动打开", Toast.LENGTH_LONG).show();
            Log.w("SenseFieldGameLaunch", "game=happy-anipop result=blocked");
            return false;
        }
    }

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
