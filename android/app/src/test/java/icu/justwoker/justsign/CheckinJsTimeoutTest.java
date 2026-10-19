package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CheckinJs 脚本的「不许挂死」回归（v1.3.6）。
 *
 * 真机故障（SeekAI，2026-10-19）：脚本注入成功（probe=js-alive 有日志），
 * 但零回调、连第一句 onProgress 都没出现，Java 侧干等 100s 报「后台签到超时」。
 * 根因：WebView 里的 fetch 在网络异常/被 WAF 挂起时不会 reject，而是一直 pending，
 * 整个 async 函数永久停住。
 *
 * 这些断言锁死：所有网络请求都必须走带超时的 fetchT，不能直接用裸 fetch。
 */
public class CheckinJsTimeoutTest {

    private static String js() {
        return CheckinJs.render("", "", "tok-123456789012345678901", "12345", "NEWAPI");
    }

    /** 必须存在带超时的 fetchT 包装。 */
    @Test public void hasTimeoutWrapper() {
        String s = js();
        assertTrue("必须定义 fetchT", s.contains("function fetchT("));
        assertTrue("超时必须 reject", s.contains("fetch-timeout"));
    }

    /** 脚本里不得残留裸 await fetch( —— 那正是挂死的来源。 */
    @Test public void noBareAwaitFetch() {
        String s = js();
        assertFalse("不得直接 await fetch(（会被 WebView 永久挂起）",
                s.contains("await fetch("));
    }

    /** 签到动作必须真的发出 POST（这是「按钮型站点签不上」的回归锁）。 */
    @Test public void postsCheckinEndpoint() {
        String s = js();
        assertTrue("必须 POST /api/user/checkin", s.contains("'/api/user/checkin'"));
        assertTrue("POST 必须带超时", s.contains("fetchT(u,"));
    }

    /** 幂等门：同一文档重复注入不得重复签到。 */
    @Test public void idempotentGuard() {
        String s = js();
        assertTrue("必须有 __jsCheckin 幂等门", s.contains("window.__jsCheckin"));
    }

    /**
     * 脚本必须以 IIFE 包裹，不得出现顶层 return。
     *
     * 真机故障（SeekAI，2026-10-19）：幂等门写成 `if(window.__jsCheckin)return;`
     * 使整段脚本在编译阶段失败（Illegal return statement）。WebView 静默丢弃，
     * 不抛异常、不走结果回调 → 永远零回调，100s 后报「后台签到超时」。
     * 这类缺陷用 new Function 查不出来（它自动包函数体），
     * 必须用 vm.Script 的脚本模式；见 tools/check_js_syntax.mjs。
     */
    @Test public void wrappedInIifeNoTopLevelReturn() {
        String s = js().trim();
        assertTrue("必须以 IIFE 开头", s.startsWith("(function(){"));
        assertTrue("IIFE 必须闭合", s.endsWith("})();"));
        assertFalse("不得以顶层 if(...)return 开头（会导致整段脚本编译失败）",
                s.startsWith("if("));
    }
}
