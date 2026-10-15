package com.openkhub.sensefield;

/**
 * 社区的七个板块。
 *
 * <p>刻意不 import 任何 Android 类：板块定义是纯数据，放进 {@code src/test} 就能直接单测，
 * 不必等真机或 Robolectric。key 是写进数据库的稳定值，改了会让老帖子找不到板块，
 * 所以 label 可以改、key 不要改。
 */
public enum CommunitySection {
    ACTIVITY("activity", "活动"),
    QUALIFIED_ACCOUNTS("qualified_accounts", "优质影响力公众号推荐"),
    HOTTEST("hottest", "最热度"),
    DISCOVERY("discovery", "探索发现"),
    HELP("help", "问题求助"),
    SHARING("sharing", "分享交流"),
    PARTNERS("partners", "合作伙伴");

    private final String key;
    private final String label;

    CommunitySection(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 稳定 key，与服务端 {@code SECTIONS} 一一对应。 */
    public String key() {
        return key;
    }

    /** 界面上显示的名字。 */
    public String label() {
        return label;
    }

    /**
     * 按 key 找板块。未知 key 一律返回 null。
     *
     * <p>这里不能「默认落到第一个」：认不出的 key 若被当成「活动」，用户的帖子会被放进
     * 一个他没选过的板块，而他看不见、也改不回来。
     */
    public static CommunitySection fromKey(String key) {
        if (key == null) return null;
        for (CommunitySection section : values()) {
            if (section.key.equals(key)) return section;
        }
        return null;
    }

    /** 全部板块名，顺序与 {@link #values()} 一致，供列表直接按顺序渲染。 */
    public static String[] labels() {
        CommunitySection[] all = values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
        return labels;
    }
}
