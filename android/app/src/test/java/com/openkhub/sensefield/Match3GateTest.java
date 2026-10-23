package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 判定层纯逻辑测试：敏感闸门、门控话术、图标消歧对象定位。 */
public class Match3GateTest {

    @Test
    public void sensitiveGateHitsAllCategories() {
        assertEquals("密码或凭证", Match3Gate.sensitiveHit("n1 [顶栏] EditText \"请输入密码\""));
        assertEquals("验证码", Match3Gate.sensitiveHit("n2 TextView \"短信验证码\""));
        assertEquals("身份证信息", Match3Gate.sensitiveHit("n3 TextView \"身份证号\""));
        assertEquals("银行卡信息", Match3Gate.sensitiveHit("n4 TextView \"银行卡号\""));
        assertEquals("支付或金额", Match3Gate.sensitiveHit("n5 Button \"确认支付 ¥199\""));
        assertEquals("疑似验证码", Match3Gate.sensitiveHit("n6 TextView \"123456\""));
        assertNull(Match3Gate.sensitiveHit("n7 TextView \"今天天气不错\""));
    }

    @Test
    public void gatedSpeechFollowsThreeTiers() {
        String hi = Match3Gate.gatedSpeech("主内容列表", 0.90, 0.85, 0.60);
        assertEquals("主内容列表。", hi);
        String mid = Match3Gate.gatedSpeech("主内容列表", 0.70, 0.85, 0.60);
        assertEquals("可能是「主内容列表」，这个我不太确定。", mid);
        String low = Match3Gate.gatedSpeech("主内容列表", 0.40, 0.85, 0.60);
        assertEquals("这一屏我没看清楚，要我从上往下逐条读吗？", low);
    }

    @Test
    public void iconOnlyClickableNodeIsLocated() {
        String state = "app=com.example\n"
                + "n0 [顶栏] TextView \"标题\" @0,0\n"
                + "n1 [顶栏] ImageView 可点 @900,0\n"
                + "n2 [主内容] Button 可点 \"下一步\" @100,500\n";
        String hit = Match3Gate.firstIconOnlyNode(state);
        assertEquals(true, hit != null && hit.startsWith("n1"));
    }

    @Test
    public void noIconNodeReturnsNull() {
        String state = "app=com.example\nn0 [顶栏] TextView \"标题\" @0,0\n";
        assertEquals(null, Match3Gate.firstIconOnlyNode(state));
    }
}
