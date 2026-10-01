package icu.justwoker.justsign;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 锁定 AgentRouter 等 login 站重登后「系统」类别日志的真实到账文案判定。
 * 真实文案（git / linuxdo 账号一致）：「每日签到成功，增加额度 ＄25.000000 额度」。
 * 该文案必须被识别为签到到账、并解析出金额，否则卡片不会置「已签 +$25」。
 */
public class CheckinTextTest {

    private static final String REAL = "每日签到成功，增加额度 ＄25.000000 额度";

    @Test public void realAgentRouterCreditIsCheckin() {
        assertTrue("真实到账文案应命中签到判据", Engine.isCheckinText(REAL));
    }

    @Test public void realAgentRouterCreditParsesAmount() {
        // 全角 ＄ 也要能解析出 25
        assertEquals(25.0, Engine.parseUsdInText(REAL), 0.001);
    }

    @Test public void halfWidthDollarAlsoParses() {
        assertEquals(25.0, Engine.parseUsdInText("每日签到成功，增加额度 $25.000000 额度"), 0.001);
    }

    @Test public void oneOffGrantsAreNotCheckin() {
        // 注册/邀请/兑换赠送不是每日签到，不能被当成今日已签
        assertFalse(Engine.isCheckinText("注册赠送，增加额度 ＄100.000000 额度"));
        assertFalse(Engine.isCheckinText("邀请奖励，增加额度 ＄10.000000 额度"));
        assertFalse(Engine.isCheckinText("兑换码，增加额度 ＄5.000000 额度"));
    }

    @Test public void nonCreditTextIsNotCheckin() {
        assertFalse(Engine.isCheckinText("登录成功"));
        assertEquals(-1.0, Engine.parseUsdInText("登录成功"), 0.001);
    }
}
