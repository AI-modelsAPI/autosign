package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AuthPageGuard 业务脚本放行守卫的回归测试。
 *
 * 核心事实约束：
 * 1. connect.linux.do 与 github.com 的 OAuth 授权确认页绝不能被 WAF 规则误杀；
 * 2. 同源 SPA 页面（带有参数或 hash 跳转）不能因为 URL 细微差异返回 'blocked'；
 * 3. 真正的 WAF 挑战页必须继续被识别为 'not-ready'。
 */
public class AuthPageGuardTest {

    @Test
    public void wrapAllowsLinuxDoOAuthPage() {
        String script = "console.log('approve');";
        String wrapped = AuthPageGuard.wrap("https://connect.linux.do/oauth2/authorize?client_id=123", script);

        assertNotNull(wrapped);
        // 必须包含对 linux.do 域名的特殊放行逻辑
        assertTrue("必须放行 linux.do 授权页", wrapped.contains("linux.do"));
        assertTrue("必须放行 github.com 授权页", wrapped.contains("github.com"));
    }

    @Test
    public void wrapAllowsUrlWithHashOrParamTolerance() {
        String script = "return 1;";
        String wrapped = AuthPageGuard.wrap("https://agentrouter.org/login", script);

        // 必须按 origin 和 pathname 比对，不能被 search/hash 严格相等掐死
        assertTrue("必须比较 origin 与 pathname", wrapped.contains("t.origin!==p.origin||t.pathname!==p.pathname"));
    }

    @Test
    public void readyExpressionRejectsWafChallenge() {
        String expr = AuthPageGuard.readyExpression();
        assertTrue("必须拦截 aliyun_waf", expr.contains("aliyun_waf"));
        assertTrue("必须拦截 acw_sc", expr.contains("acw_sc"));
    }
}
