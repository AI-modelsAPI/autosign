package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OffscreenReadJs 的「证据驱动」判定回归（v1.3.2）。
 *
 * 设计原则（用户明确要求，不许靠等秒数）：
 *   - 唯一成功判据 = 业务接口返回真实 JSON（放行已确认）；
 *   - 超时 = 失败（waf-not-cleared），绝不当成功放行；
 *   - 重试由上层按 retryable 决定，不在脚本内空等。
 *
 * 这些断言锁死上述属性，防止再退回「等够 30s 就当过了」的猜法。
 */
public class OffscreenReadJsEvidenceTest {

    private static String js() {
        return OffscreenReadJs.render("https://example.com", "/api/user/self", "12345", "n1");
    }

    /** 超时路径必须直接判失败，不得继续往下走。 */
    @Test public void timeoutIsFailureNotSuccess() {
        String s = js();
        assertTrue("超时必须上报 waf-not-cleared", s.contains("waf-not-cleared"));
        assertTrue("超时必须标可重试（一次没成 ≠ 永远不成）", s.contains("retryable:true"));
        // 旧的「超时也继续跑」写法必须消失
        assertFalse("不得再存在『超时后照常 run()』的写法",
                s.contains("if(pageReady()||Date.now()>=gateCap){run();"));
    }

    /** 成功路径必须由真实业务 JSON 驱动。 */
    @Test public void successRequiresRealBusinessPayload() {
        String s = js();
        assertTrue("成功必须要求 success===true", s.contains("j.success===true"));
        assertTrue("成功必须校验身份 id", s.contains("String(d.id)===ID"));
        assertTrue("被 WAF 换页时必须判失败", s.contains("reason:'waf-challenge'"));
        assertTrue("非 2xx 必须判失败", s.contains("reason:'http-'+r.status"));
    }

    /** 必须能识别 v1 与 v2 两种挑战页。 */
    @Test public void bothWafVariantsAreDetected() {
        String s = js();
        assertTrue(s.contains("aliyun_waf_(?:aa|bb)"));
        assertTrue(s.contains("arg1"));
    }

    /** 只读脚本的安全属性：绝不代答、不伪造、不提交。 */
    @Test public void readOnlyScriptNeverActs() {
        String s = js();
        assertFalse("绝不代答：不得出现点击验证码", s.contains(".click()"));
        assertFalse("绝不伪造：不得写 cookie", s.contains("document.cookie="));
        assertFalse("不得提交表单", s.contains(".submit("));
    }

    /** 轮询节拍与上限必须显式可见（便于审查，而不是散落的魔法数字）。 */
    @Test public void pollingUsesExplicitEvidenceLoop() {
        String s = js();
        assertTrue("必须存在统一的 CAP 上限", s.contains("var CAP=30000;"));
        assertTrue("轮询必须基于 probeIdentity 的结果", s.contains("function pollIdentity"));
        assertTrue("入口 gate 必须基于 pageReady 证据", s.contains("if(pageReady()){run();return;}"));
    }
}
