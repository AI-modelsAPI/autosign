package icu.justwoker.justsign;

/** JVM 直接运行的 OAuth 回调解析回归测试。 */
public final class OAuthCallbackTest {
    private static int ok = 0;

    public static void main(String[] args) {
        expect("旧路径", OAuthCallback.parse(
                "https://agentrouter.org/oauth/github?code=abc&state=s1", "agentrouter.org", "s1").shouldExchange());
        expect("非旧路径也可按同域+state拦截", OAuthCallback.parse(
                "https://agentrouter.org/?code=abc&state=s1", "agentrouter.org", "s1").shouldExchange());
        expect("API callback 路径", OAuthCallback.parse(
                "https://agentrouter.org/api/oauth/github/callback?code=abc&state=s1", "agentrouter.org", "s1").shouldExchange());
        expect("hash 参数", OAuthCallback.parse(
                "https://agentrouter.org/#/oauth/callback?code=abc&state=s1", "agentrouter.org", "s1").shouldExchange());
        expect("拒绝跨域", !OAuthCallback.parse(
                "https://evil.example/?code=abc&state=s1", "agentrouter.org", "s1").shouldExchange());
        expect("拒绝错误 state", OAuthCallback.parse(
                "https://agentrouter.org/oauth/github?code=abc&state=bad", "agentrouter.org", "s1").badState());
        expect("识别站点根页缺 code", OAuthCallback.parse(
                "https://agentrouter.org/?error=missing_code", "agentrouter.org", "s1").missingCode());
        expect("识别旧回调缺 code", OAuthCallback.parse(
                "https://agentrouter.org/oauth/github?state=s1", "agentrouter.org", "s1").missingCode());
        OAuthCallback.Result safe = OAuthCallback.parse(
                "https://agentrouter.org/callback?code=SECRET_CODE&state=SECRET_STATE&token=SECRET", "agentrouter.org", "SECRET_STATE");
        expect("日志不含 code 值", !safe.safe.contains("SECRET_CODE"));
        expect("日志不含 state 值", !safe.safe.contains("SECRET_STATE"));
        expect("日志不含未知敏感参数名", !safe.safe.contains("token"));
        System.out.println("PASS OAuthCallbackTest (" + ok + "通过/0失败)");
    }

    private static void expect(String name, boolean pass) {
        if (!pass) throw new AssertionError("FAIL " + name);
        ok++;
    }
}