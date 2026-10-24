package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.List;

/**
 * 养老场景风险话术规则目录（方案一 MVP）。纯数据、零 Android import。
 *
 * 匹配语义：一条规则由 1..N 个关键词组构成，每组任一关键词命中即该组命中，
 * 全部组同时命中才算规则命中（单组规则＝任一关键词命中）。文本先做去空白＋
 * 小写归一再匹配，防「公 检 法」「公检\n法」这类插字符 evasion。
 *
 * 档位取舍遵循落地纪律（docs/design/养老场景可信标签MVP.md §五）：
 * - RED 只收特征明确、正当服务文本不可能出现的组合话术；
 * - 单看「转账/付款」等资金词不足以定红，落入 REVIEW 由人工兜底；
 * - GREEN 必须有正向证据（官方服务内容签名或可信包名），没有证据＝NEUTRAL。
 */
public final class ElderRiskRules {

    /** 一条风险规则：全组命中才生效。 */
    public static final class Rule {
        public final String id;
        public final ElderRiskLabel.Tier tier;
        /** 每个元素是一组同义关键词，组内任一命中即该组过。 */
        public final String[][] groups;
        /** 命中后的语音解释模板（直接面向老人的完整句子）。 */
        public final String explanation;

        Rule(String id, ElderRiskLabel.Tier tier, String[][] groups, String explanation) {
            this.id = id;
            this.tier = tier;
            this.groups = groups;
            this.explanation = explanation;
        }
    }

    /** 高风险（红）：养老诈骗典型话术，按公安部反诈宣传与涉老案件高频模式归纳。 */
    public static final List<Rule> RED_RULES = List.of(
            new Rule("RED-01", ElderRiskLabel.Tier.RED,
                    new String[][]{{"公检法", "缉查", "逮捕令", "通缉令", "网安中心"},
                            {"冻结", "安全账户", "资金核查", "洗钱", "涉嫌犯罪"}},
                    "这条消息冒充公安或司法机构说您涉案。公检法机关不会在电话和短信里办案，请不要操作，先问家人或护理人员。"),
            new Rule("RED-02", ElderRiskLabel.Tier.RED,
                    new String[][]{{"医保", "社保", "养老金", "老年补贴", "待遇资格"},
                            {"异常", "停发", "将被冻结", "被冒用", "过期失效", "取消资格", "限期"}},
                    "这条消息说您的医保或养老金出了问题要处理。社保和医保部门不会这样联系您，请不要点链接，先问家人或拨打官方电话核实。"),
            new Rule("RED-03", ElderRiskLabel.Tier.RED,
                    new String[][]{{"根治", "包治百病", "祖传秘方", "神奇疗效", "药到病除", "三个疗程痊愈", "彻底根治", "抗癌神药"}},
                    "这条消息宣称能根治疾病。任何宣称包治、根治的药都是骗局，请不要购买，先咨询医生。"),
            new Rule("RED-04", ElderRiskLabel.Tier.RED,
                    new String[][]{{"稳赚不赔", "保本高息", "日返利", "高额返利", "内部消息", "涨停黑马", "一夜暴富", "养老项目投资返利"}},
                    "这条消息承诺稳赚不赔的高回报。凡是保本又高息的都是诈骗，请不要投钱，先和家人商量。"),
            new Rule("RED-05", ElderRiskLabel.Tier.RED,
                    new String[][]{{"解冻金", "保证金", "刷流水", "验证资信", "刷单返佣金", "垫付任务"}},
                    "这条消息让您先交钱再拿回报，这是刷单和解冻类骗局的典型手法，请不要转账。"),
            new Rule("RED-06", ElderRiskLabel.Tier.RED,
                    new String[][]{{"验证码告诉我", "把验证码", "提供验证码", "读一下验证码", "短信验证码发",
                            "屏幕共享", "共享屏幕", "开启共享"}},
                    "这条消息想要您的验证码或屏幕共享。验证码等于钱，任何时候都不要告诉别人，也不要开屏幕共享。"),
            new Rule("RED-07", ElderRiskLabel.Tier.RED,
                    new String[][]{{"我是你孙子", "我是你儿子", "我是你孙女", "换号了", "手机换号"},
                            {"住院", "车祸", "打钱", "汇款", "救急", "别告诉爸妈"}},
                    "这条消息冒充家人出急事要钱。请先挂断，用原来的号码打给家人本人核实，不要直接转账。"),
            new Rule("RED-08", ElderRiskLabel.Tier.RED,
                    new String[][]{{"恭喜您中奖", "幸运用户", "抽中", "中奖名额"},
                            {"点击链接领取", "先交手续费", "缴纳个税", "名额有限速领", "逾期作废"}},
                    "这条消息说您中奖了但要先点链接或先交钱。要先交费的中奖都是骗局，请不要理会。"));

    /** 第三方推广（黄）：营销内容，低频提示，不拦截不删除。 */
    public static final List<Rule> YELLOW_RULES = List.of(
            new Rule("YEL-01", ElderRiskLabel.Tier.YELLOW,
                    new String[][]{{"广告", "推广", "赞助内容", "含广告"}},
                    "这是广告推广内容，不是官方服务信息，买东西前建议先和家人商量。"),
            new Rule("YEL-02", ElderRiskLabel.Tier.YELLOW,
                    new String[][]{{"限时抢购", "秒杀", "优惠券", "立减", "满减", "充值返现",
                            "首单立减", "直播间专享", "立即抢购"}},
                    "这是促销推广信息。别着急，价格明天可能还是一样，想买先和家人商量。"),
            new Rule("YEL-03", ElderRiskLabel.Tier.YELLOW,
                    new String[][]{{"点击下载", "立即下载", "安装就送", "下载领红包", "开通会员享"}},
                    "这是诱导下载或开通会员的推广，不需要的话请不要点击。"));

    /** 待人工审核：涉资金/健康但特征不足以定红，不惊扰老人，进审核队列。 */
    public static final List<Rule> REVIEW_RULES = List.of(
            new Rule("REV-01", ElderRiskLabel.Tier.REVIEW,
                    new String[][]{{"请转账", "请汇款", "请付款", "向此账户转账", "转账至", "汇款至"}},
                    ""),
            new Rule("REV-02", ElderRiskLabel.Tier.REVIEW,
                    new String[][]{{"保健品", "营养补充剂特惠", "理疗仪", "磁疗", "频谱治疗", "量子疗愈"}},
                    ""),
            new Rule("REV-03", ElderRiskLabel.Tier.REVIEW,
                    new String[][]{{"点击链接", "短网址", "点此查看详情", "复制到浏览器打开"}},
                    ""));

    /** 官方服务内容签名（绿的正向证据之一）：正常政务/医疗/民生服务文本高频词。 */
    public static final String[] GREEN_SERVICE_SIGNATURES = {
            "预约成功", "挂号成功", "缴费成功", "缴费提醒", "报告已出", "体检报告",
            "取药提醒", "复诊提醒", "社区卫生服务中心", "国家反诈中心", "12345",
            "医保个账", "养老金已发放", "高龄津贴已发放", "电子医保凭证"};

    /** 可信来源包名白名单（MVP 只收 AOSP 系统包；政务/医院/机构包名由部署期配置补充，不臆造）。 */
    public static final List<String> TRUSTED_PACKAGES = List.of(
            "com.android.settings", "com.android.systemui", "com.android.dialer",
            "com.android.contacts", "com.android.deskclock", "com.android.calendar");

    private ElderRiskRules() {
    }

    /** 按红→黄→审核的优先级展平。 */
    public static List<Rule> inPriorityOrder() {
        return Arrays.asList(concat(RED_RULES.toArray(new Rule[0]),
                YELLOW_RULES.toArray(new Rule[0]), REVIEW_RULES.toArray(new Rule[0])));
    }

    private static Rule[] concat(Rule[] a, Rule[] b, Rule[] c) {
        Rule[] out = Arrays.copyOf(a, a.length + b.length + c.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }
}
