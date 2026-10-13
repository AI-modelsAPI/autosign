package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * WafChallenge 的「需人工交互」判据回归（v1.3.2）。
 *
 * 真机实证（agentrouter.org）：该站对部分出口 IP 下发阿里云滑块验证码，
 * 设备 Chrome 打开也显示「请按住滑块，拖动到最右边」——必须人工拖动，静默无法通过。
 *
 * 旧行为：一律回「请更换代理节点后重试」。用户换节点后仍是滑块，属误导；
 * 且掩盖了「本站要求人工验证」这一事实。本测试锁死「如实识别」。
 */
public class WafHumanChallengeTest {

    /** 真机抓取的真实滑块页特征（已去除任何凭据）。 */
    private static final String SLIDER_PAGE =
            "<!doctype html><meta name=\"aliyun_waf_aa\" content=\"x\">"
            + "<script>/* v2.0.1 */!function(){var __webpack_modules__={};}();"
            + "initAliyunCaptcha(captchaOptions);</script>"
            + "<div id=\"captcha-element\" class=\"nc-container\"></div>"
            + "<div class=\"ha-sliding-slider\"></div>"
            + "<p>请按住滑块，拖动到最右边</p>";

    /** 老式 v1 质询页：可由纯 Java 求解，无需人工。 */
    /** 老式 v1 质询页：arg1 为 40 位十六进制（算法置换表长度即 40），可由纯 Java 求解。 */
    private static final String ARG1_HEX =
            "7F1C3A9B0D5E28469CA1F3B70E5D2C8A194B6F30";
    private static final String ARG1_PAGE =
            "<html><script>var arg1='" + ARG1_HEX + "';</script></html>";

    /** 正常业务 JSON。 */
    private static final String REAL_JSON = "{\"success\":true,\"data\":{\"id\":1}}";

    @Test public void sliderChallengeNeedsHuman() {
        assertTrue("滑块验证码必须被判为需人工",
                WafChallenge.needsHumanInteraction(SLIDER_PAGE));
    }

    @Test public void solvableChallengeDoesNotNeedHuman() {
        assertFalse("可纯 Java 求解的 v1 质询页不应判为需人工",
                WafChallenge.needsHumanInteraction(ARG1_PAGE));
    }

    @Test public void businessJsonIsNotAChallenge() {
        assertFalse(WafChallenge.needsHumanInteraction(REAL_JSON));
        assertFalse(WafChallenge.needsHumanInteraction(""));
        assertFalse(WafChallenge.needsHumanInteraction(null));
    }

    /** 只有「验证码 + 滑块容器」同时成立才算需人工，避免误伤。 */
    @Test public void captchaWithoutSliderIsNotHuman() {
        String noSlider = "<script>initAliyunCaptcha({});</script><div id=\"captcha-element\"></div>";
        assertFalse("无滑块容器不应判为需人工", WafChallenge.needsHumanInteraction(noSlider));
    }

    /** 既有能力不能被破坏：v1 质询页仍可解析 arg1 并求解。 */
    @Test public void v1SolvingStillWorks() {
        assertTrue(WafChallenge.isChallenge(ARG1_PAGE));
        String arg1 = WafChallenge.arg1(ARG1_PAGE);
        assertTrue("应能提取 arg1", ARG1_HEX.equals(arg1));
        assertTrue("应能算出放行值", WafChallenge.solve(arg1).length() > 0);
    }
}
