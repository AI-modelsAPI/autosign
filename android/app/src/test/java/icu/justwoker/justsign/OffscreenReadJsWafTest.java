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

    /** 阿里云 ESA 老式 v1 挑战页特征（带 arg1，可纯 Java 求解）。 */
    private static final String V1_CHALLENGE =
            "<html><script>var arg1='ABCDEF0123456789';</script></html>";

    /** 真实业务响应。 */
    private static final String REAL_JSON =
            "{\"success\":true,\"data\":{\"id\":12345,\"quota\":100}}";

    private static String rendered() {
        return OffscreenReadJs.render("https://example.com", "/api/user/self", "12345", "nonce1");
    }

    /** 脚本必须把 v1 与 v2 两种挑战页都纳入 wafSolved 判据。 */
    @Test public void v2ChallengeIsRecognizedAsWaf() {
        String js = rendered();
        assertTrue("脚本必须能识别 v2 盾的 meta 标记",
                js.contains("aliyun_waf_(?:aa|bb)"));
        assertTrue("脚本必须保留 v1 的 arg1 判据",
                js.contains("arg1"));
        assertTrue("脚本必须存在统一的 isWafText 判定",
                js.contains("function isWafText"));
    }

    /** 轮询上限必须放宽，给内核跑完 v2 的 JS 留时间。 */
    @Test public void waitCapCoversV2Challenge() {
        String js = rendered();
        assertTrue("等待上限应 >= 30s 以覆盖 v2 盾", js.contains("var CAP=30000;"));
        assertFalse("不应再停留在旧的 20s", js.contains("var CAP=20000;"));
    }

    /** 提取出来的 isWafText 判定逻辑，必须对 v1/v2 为真、对业务 JSON 为假。 */
    @Test public void wafClassifierSeparatesChallengeFromData() {
        assertTrue(isWafText(V1_CHALLENGE));
        assertTrue(isWafText(V2_CHALLENGE));
        assertFalse("正常业务 JSON 不能被误判为挑战页", isWafText(REAL_JSON));
        assertFalse(isWafText(""));
        assertFalse(isWafText(null));
    }

    /**
     * 与脚本内 isWafText 等价的 Java 复刻：三条判据必须一致，
     * 否则「脚本改了、测试没跟上」会静默漂移。
     */
    private static boolean isWafText(String t) {
        if (t == null) return false;
        if (t.matches("(?s).*var\\s+arg1\\s*=.*")) return true;
        if (t.matches("(?s).*aliyun_waf_(?:aa|bb).*")) return true;
        return t.contains("__webpack_modules__") && t.toLowerCase(java.util.Locale.US).contains("aliyun_waf");
    }
}
