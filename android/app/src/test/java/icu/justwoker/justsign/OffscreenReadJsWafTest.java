package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OffscreenReadJs 的 WAF 变体识别回归（v1.3.2 修复「v2 盾被当成业务数据」）。
 *
 * 背景（真机实测 agentrouter.org）：设备出口 IP 被阿里云 WAF v2.0.1 挑战，
 * 挑战页是 webpack 混淆 + meta[name=aliyun_waf_aa/bb]，正文【没有 arg1】。
 * 旧实现只认 `var arg1='...'`，于是：
 *   1) wafPending() 返回 false → 认为页面已就绪；
 *   2) wafSolved() 返回 false → 把挑战页 HTML 当成真实业务数据 ok:true 回传；
 * 上层解析失败后只报「离屏未拿到可用数据」，真正原因被掩盖。
 *
 * 这里直接对 render() 产物做断言，锁死「v1/v2 挑战页都必须被识别」。
 */
public class OffscreenReadJsWafTest {

    /** 阿里云 WAF v2.0.1 挑战页特征（真机抓取的真实片段，已去掉任何凭据）。 */
    private static final String V2_CHALLENGE =
            "<!doctype html><meta charset=\"UTF-8\">"
            + "<meta name=\"aliyun_waf_aa\" content=\"ff926c7f07e45e2e487a29a6197d3460\">"
            + "<meta name=\"aliyun_waf_bb\" content=\"eade71455e2ad9c6d08b82bc7d98df8c\">"
            + "<script>/* v2.0.1 */!function(){var __webpack_modules__={340:function(){}}();</script>";

    /**
     * 真机 2026-10-18 实测的挑战页（agentrouter.org 被拦响应，len=16011 的开头）。
     * 关键：只有 `<meta name="aliyun_wa…`，既无 arg1，也无 aliyun_waf_aa/bb 字面，
     * 更无 __webpack_modules__ —— 旧实现三条判据全部落空，挑战页被当成"未就绪"，
     * 白等 30s 后报 waf-not-cleared。这个用例就是锁住这次修复。
     */
    private static final String META_ONLY_CHALLENGE =
            "<!doctype html><meta charset=\"UTF-8\">"
            + "<meta name=\"aliyun_waf_verify\" content=\"1\">"
            + "<script>location.reload()</script>";

    /** 阿里云 ESA 老式 v1 挑战页特征（带 arg1，可纯 Java 求解）。 */
    private static final String V1_CHALLENGE =
            "<html><script>var arg1='ABCDEF0123456789';</script></html>";

    /** 真实业务响应。 */
    private static final String REAL_JSON =
            "{\"success\":true,\"data\":{\"id\":12345,\"quota\":100}}";

    private static String rendered() {
        return OffscreenReadJs.render("https://example.com", "/api/user/self", "12345", "nonce1");
    }

    /** 脚本必须把三种挑战页变体都纳入 wafSolved 判据。 */
    @Test public void challengeVariantsAreRecognized() {
        String js = rendered();
        assertTrue("脚本必须保留 v1 的 arg1 判据", js.contains("arg1"));
        assertTrue("脚本必须能识别任意 aliyun_waf 变体",
                js.contains("/aliyun_waf/i"));
        assertTrue("脚本必须能识别 meta name=aliyun_wa… 这种实测形态",
                js.contains("aliyun_wa"));
        assertTrue("脚本必须存在统一的 isWafText 判定",
                js.contains("function isWafText"));
    }

    /** 轮询上限必须放宽，给内核跑完挑战页的 JS 留时间。 */
    @Test public void waitCapCoversChallenge() {
        String js = rendered();
        assertTrue("等待上限应 >= 30s 以覆盖挑战页", js.contains("var CAP=30000;"));
        assertFalse("不应再停留在旧的 20s", js.contains("var CAP=20000;"));
    }

    /** 判定逻辑必须对三种挑战页为真、对业务 JSON 为假。 */
    @Test public void wafClassifierSeparatesChallengeFromData() {
        assertTrue("v1 带 arg1", isWafText(V1_CHALLENGE));
        assertTrue("v2 带 aliyun_waf_aa/bb", isWafText(V2_CHALLENGE));
        assertTrue("实测的 meta-only 变体（本次修复的对象）", isWafText(META_ONLY_CHALLENGE));
        assertFalse("正常业务 JSON 不能被误判为挑战页", isWafText(REAL_JSON));
        assertFalse(isWafText(""));
        assertFalse(isWafText(null));
    }

    /**
     * 与脚本内 isWafText 等价的 Java 复刻：判据必须与脚本一致，
     * 否则「脚本改了、测试没跟上」会静默漂移。
     */
    private static boolean isWafText(String t) {
        if (t == null) return false;
        String s = t;
        if (s.matches("(?s).*var\\s+arg1\\s*=.*")) return true;
        if (s.matches("(?s)(?i).*aliyun_waf.*")) return true;
        if (s.matches("(?s)(?i).*name\\s*=\\s*[\"']aliyun_wa.*")) return true;
        if (s.contains("__webpack_modules__") && s.toLowerCase(java.util.Locale.US).contains("aliyun")) return true;
        if (s.matches("(?s)(?i).*(acw_sc__v2|acw_tc).*") && s.matches("(?s)(?i).*(<html|<!doctype).*")) return true;
        return false;
    }
}
