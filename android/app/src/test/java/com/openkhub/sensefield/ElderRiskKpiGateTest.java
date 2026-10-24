package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 方案一 MVP 的 KPI 门禁（docs/design/养老场景可信标签MVP.md §五）：
 * 高风险内容识别准确率 ≥95%、高危误报率 <2%。
 *
 * 语料口径：全部为本文件内维护的脱敏合成话术（含插字符绕过变体）与日常无害文本，
 * 不收真实案件原文、不收隐私截图内容。REVIEW 档是白名单+人工兜底的承接层：
 * 涉资金但无诈骗特征的文本允许落 REVIEW，不计入高危误报。
 */
public class ElderRiskKpiGateTest {

    private static final double RECALL_FLOOR = 0.95;
    private static final double FALSE_RED_CEILING = 0.02;

    private final ElderRiskLabelEngine engine = new ElderRiskLabelEngine();

    /** 高风险语料：养老诈骗典型话术＋插字符/换行绕过变体（合成，非真实案件原文）。 */
    private static final List<String> HIGH_RISK_CORPUS = Collections.unmodifiableList(Arrays.asList(
            "公检法办案：您涉嫌洗钱，需将资金转入安全账户核查",
            "这 是 缉 查 通知，您的账户将被冻结",
            "逮捕令已经发出，您涉嫌犯罪，联系安全账户处理",
            "网安中心提示您的账户资金核查，公检法联合办案",
            "公 检 法 提 醒：您的银行账户涉 嫌 犯 罪",
            "医保通知：您的待遇资格异常，限期认证，将被停发",
            "社 保 卡 被 冒 用，养老金停发，点此处理",
            "老年补贴过期失效，请立即点击认证",
            "医保\n资格\n过期失效，请速处理",
            "社保待遇资格取消，医保将被停发",
            "祖传秘方，三个疗程痊愈，糖尿病彻底根治",
            "抗癌神药，包治百病，今天订购有优惠",
            "神奇疗效，药到病除，只需三个疗程痊愈",
            "高 血 压 彻底根治，不再吃药",
            "保本高息，稳赚不赔，养老项目投资返利",
            "内部消息，涨停黑马，日返利百分之五",
            "高额返利！稳赚不赔，加入就赚",
            "解冻金到账需先刷流水验证资信",
            "刷单返佣金，垫付任务即刻回款",
            "刷 流 水 就能解冻账户",
            "把验证码告诉我，帮您开通尊享服务",
            "请开启屏幕共享，工作人员指导您操作",
            "短信验证码发我一下就给您退款",
            "验证码告诉我，马上帮您办理",
            "我是你孙子，我出车祸了，快打钱救急，别告诉爸妈",
            "我是你儿子，手机换号了，住院需要汇款",
            "我是你孙女，换号了，住院缴费救急",
            "恭喜您中奖！名额有限速领，先交手续费",
            "您已被抽中为幸运用户，点击链接领取，逾期作废",
            "恭喜您中奖，缴纳个税后即可领取"));

    /** 无害语料：日常政务、医疗、家庭、生活通知，以及涉资金但正当的文本。 */
    private static final List<String> BENIGN_CORPUS = Collections.unmodifiableList(Arrays.asList(
            "今日多云转晴，最高气温22度，适合外出散步",
            "您的血压测量记录已保存，收缩压135，舒张压85",
            "医生嘱咐：按时服用降压药，饭后半小时",
            "社区通知：下周三上午免费体检，请携带身份证",
            "孙女发来照片：爷爷生日快乐",
            "儿子：爸，周末我回来吃饭，想吃什么提前说",
            "您的账户于10月24日支出人民币200.00元，余额3500元",
            "护士站提醒：明早8点测空腹血糖，请勿进食",
            "天气预报：明天有雨，出门记得带伞",
            "药品说明：一日三次，每次一片，饭后服用",
            "社区卫生服务中心：流感疫苗接种安排在下周二",
            "家庭群：妈，降温了记得加衣服",
            "您的快递已到驿站，凭取件码8888领取",
            "物业公司通知：本月水费已出账，请及时缴纳",
            "图书馆提醒：您借的书还有3天到期",
            "孙子：爷爷我考了100分！",
            "健身计划：每天散步30分钟，每周三次太极",
            "菜谱推荐：清蒸鲈鱼，少油少盐",
            "交管提醒：您的车辆年检即将到期，请及时办理",
            "养老保险缴费成功，本年度缴费已到账",
            "医院公众号：门诊排班表更新，周三张医生停诊",
            "街道办：60岁以上老人免费体检登记开始",
            "药盒提醒：晚上9点服用氨氯地平一片",
            "医生建议可以适量补充钙片，多晒太阳",
            "女儿：爸我已经把生活费转到您卡上了",
            "社区讲堂：周四下午讲高血压的日常管理",
            "您预约的骨科门诊是下周四上午10点",
            "退休金到账提醒：本月养老金已入账",
            "社区活动：重阳节文艺汇演欢迎报名",
            "护工小李明天上午来家里做康复训练",
            "公交卡充值成功，余额56元",
            "本店今日已消毒，欢迎光临",
            "药盒剩余量：阿司匹林还有14片",
            "家里水管修好了，费用一共80元",
            "明天幼儿园停课一天，孩子请在家",
            "象棋比赛报名从下周一开始",
            "您的体检报告已出，请到二楼导诊台领取",
            "水费缴费提醒：本月应缴38元",
            "医生：这个药饭后吃，刺激小一些",
            "公园晨练队早上7点在东门集合",
            "社区医院周六可打流感疫苗，不用预约",
            "老张家的猫生了四只小猫",
            "日历提醒：今天是结婚纪念日",
            "手机系统更新已完成，重启后生效",
            "单元门维修通知：周六上午施工，出行注意",
            "天气预报显示本周持续晴好，适合晾晒",
            "孙女的画得了全校一等奖",
            "您的公交卡年审可以在社区服务站办理",
            "老伴儿：晚饭我已经做好了，你热一下就能吃",
            "诊所提醒：您上次看病的发票可以来领取"));

    private ElderRiskLabel evaluateOn(List<String> texts) {
        // 模拟读屏服务的形态：一个窗口的多条节点文本一起判。
        return engine.evaluate("com.elder.scenario", texts);
    }

    @Test
    public void highRiskRecallMeetsFloor() {
        List<String> missed = new ArrayList<>();
        int hit = 0;
        for (String sample : HIGH_RISK_CORPUS) {
            ElderRiskLabel label = evaluateOn(Collections.singletonList(sample));
            if (label.tier == ElderRiskLabel.Tier.RED) hit++;
            else missed.add(sample + " -> " + label.tier + "/" + label.ruleId);
        }
        double recall = (double) hit / HIGH_RISK_CORPUS.size();
        assertTrue("高风险召回必须 ≥" + RECALL_FLOOR + "，实际 " + recall + "，漏报：" + missed,
                recall >= RECALL_FLOOR);
    }

    @Test
    public void benignCorpusStaysUnderFalseRedCeiling() {
        List<String> falseReds = new ArrayList<>();
        for (String sample : BENIGN_CORPUS) {
            ElderRiskLabel label = evaluateOn(Collections.singletonList(sample));
            if (label.tier == ElderRiskLabel.Tier.RED) falseReds.add(sample);
        }
        double rate = (double) falseReds.size() / BENIGN_CORPUS.size();
        assertTrue("高危误报必须 <" + FALSE_RED_CEILING + "，实际 " + rate
                        + "，误报：" + falseReds,
                rate < FALSE_RED_CEILING);
    }

    @Test
    public void benignMoneyAndHealthTextsRouteToReviewNotSilentConfidence() {
        // 白名单+人工兜底的承接证据：涉资金文本不允许悄悄当无害，也不允许直接吓老人。
        ElderRiskLabel transfer = evaluateOn(Collections.singletonList("请向此账户转账，学费共计3000元"));
        assertEquals(ElderRiskLabel.Tier.REVIEW, transfer.tier);
        ElderRiskLabel supplement = evaluateOn(Collections.singletonList("保健品特惠活动，第二件半价"));
        assertEquals(ElderRiskLabel.Tier.REVIEW, supplement.tier);
        assertEquals(ElderRiskLabel.Announce.NONE, transfer.announceKind());
        assertEquals(ElderRiskLabel.Announce.NONE, supplement.announceKind());
    }

    @Test
    public void kpiConstantsMatchThePlanBook() {
        // 防止有人把指标放宽还不改文档：门禁常数必须与落地书一致。
        assertEquals(0.95, RECALL_FLOOR, 0.0);
        assertEquals(0.02, FALSE_RED_CEILING, 0.0);
    }

    @Test
    public void everyRedHitCarriesExplanationAndBoundedEvidence() {
        for (String sample : HIGH_RISK_CORPUS) {
            ElderRiskLabel label = evaluateOn(Collections.singletonList(sample));
            if (label.tier != ElderRiskLabel.Tier.RED) continue;
            assertTrue("红档必须带老人可懂的解释：" + sample, label.explanation.length() >= 10);
            assertTrue("证据窗口必须有界：" + sample, label.evidence.length() <= 80);
        }
    }
}
