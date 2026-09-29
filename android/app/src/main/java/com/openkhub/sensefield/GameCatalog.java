package com.openkhub.sensefield;

import android.content.Context;
import android.content.Intent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Single registry for games shown on the first screen; new adapters can be added here. */
final class GameCatalog {
    static final String EXTRA_GAME_ID = "com.openkhub.sensefield.extra.GAME_ID";

    static final List<GameEntry> GAMES = Collections.unmodifiableList(Arrays.asList(
            new GameEntry("honor-of-kings", "王者荣耀", "地图感知辅助",
                    "当前可用", "识别可见局势，提供本地语音与触觉提示。", true,
                    MainActivity.class),
            new GameEntry("match-three", "消消乐", "休闲益智辅助",
                    "即将适配", "正在准备识别与提示功能。", false, null)
    ));

    private GameCatalog() {}

    static final class GameEntry {
        final String id;
        final String name;
        final String subtitle;
        final String availability;
        final String description;
        final boolean available;
        final Class<?> activityClass;

        GameEntry(String id, String name, String subtitle, String availability,
                  String description, boolean available, Class<?> activityClass) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.availability = availability;
            this.description = description;
            this.available = available;
            this.activityClass = activityClass;
        }

        boolean open(Context context) {
            if (!available || activityClass == null) return false;
            context.startActivity(new Intent(context, activityClass).putExtra(EXTRA_GAME_ID, id));
            return true;
        }
    }
}
