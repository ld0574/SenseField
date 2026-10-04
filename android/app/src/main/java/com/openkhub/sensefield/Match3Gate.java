package com.openkhub.sensefield;

import java.util.regex.Pattern;

/** 判定层纯逻辑工具（阶段一/二）：敏感闸门、ABSTAIN 话术映射、图标消歧候选。全部可 JVM 测试。 */
final class Match3Gate {
    private Match3Gate() {
    }

    private static final Pattern[] SENSITIVE_PATTERNS = {
            Pattern.compile("密码|password", Pattern.CASE_INSENSITIVE),
            Pattern.compile("验证码|verification|captcha|短信码", Pattern.CASE_INSENSITIVE),
            Pattern.compile("身份证|身份證|身份证号", Pattern.CASE_INSENSITIVE),
            Pattern.compile("银行卡|卡号|card.?number", Pattern.CASE_INSENSITIVE),
            Pattern.compile("支付|付款|转账|金额|确认支付", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\d{6}(?!\\d)"),                      // 6 位数字（疑似验证码）
            Pattern.compile("\\d{15,19}")                          // 长数字（疑似卡号）
    };

    private static final String[] SENSITIVE_NAMES = {
            "密码或凭证", "验证码", "身份证信息", "银行卡信息", "支付或金额", "疑似验证码", "疑似卡号"
    };

    /**
     * 本地敏感闸门（阶段一采用本地规则而非上云询问——上云问「是否敏感」自相矛盾）。
     * 命中返回类型标签，未命中返回 null。规则基于压缩状态文本的确定性扫描。
     */
    static String sensitiveHit(String state) {
        if (state == null) return null;
        for (int i = 0; i < SENSITIVE_PATTERNS.length; i++) {
            if (SENSITIVE_PATTERNS[i].matcher(state).find()) return SENSITIVE_NAMES[i];
        }
        return null;
    }

    /** ABSTAIN/置信度 → 播报话术（门控三档 + ABSTAIN 强制拒答）。 */
    static String gatedSpeech(String content, double confidence, double high, double mid) {
        if (confidence >= high) return content + "。";
        if (confidence >= mid) return "可能是「" + content + "」，这个我不太确定。";
        return "这一屏我没看清楚，要我从上往下逐条读吗？";
    }

    /** 图标消歧候选（文档 §5 固定候选集 + ABSTAIN）。 */
    static final String[] ICON_CANDIDATES = {
            "返回", "关闭", "更多菜单", "搜索", "分享", "收藏", "设置",
            "刷新", "购物车", "消息", "筛选", "无法判断", "ABSTAIN"
    };

    /** 从压缩状态里找第一个「无文本图标且可点击」的节点行（图标消歧的判定对象）。 */
    static String firstIconOnlyNode(String state) {
        if (state == null) return null;
        for (String line : state.split("\n")) {
            if (line.contains("ImageView") && line.contains("可点") && !line.contains("\"")) {
                return line;
            }
        }
        return null;
    }
}
