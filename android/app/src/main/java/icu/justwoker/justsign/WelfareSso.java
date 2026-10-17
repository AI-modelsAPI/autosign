package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.Profile;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 福利站 SSO 执行器：在真实 WebView 里让站点自己的前端跑完 SSO，取回 welfare_token。
 *
 * 与 OffscreenReauth 的区别：
 *   OffscreenReauth 复用 connect.linux.do 论坛会话（仅 linuxdo provider 有效）；
 *   本类复用**主站**会话走 {welfare}/auth/callback，适用于任意 provider。
 *
 * 安全：只读取站点自己写进 localStorage 的 welfare_token（站点登录成功的产物），
 * 不注入伪造凭据、不代答人机验证。拿不到就如实返回需要人工。
 */
public final class WelfareSso {

    private WelfareSso() {}

    public static final int SSO_OK = 0;
    public static final int SSO_NEEDS_UI = 1;   // 主站会话也没有，需要人工
    public static final int SSO_SKIP = 2;       // 前置条件不满足（无福利站地址等）

    public static String describe(int code) {
        switch (code) {
            case SSO_OK:       return "已取得福利站令牌";
            case SSO_NEEDS_UI: return "需要人工登录主站";
            default:           return "跳过";
        }
    }

    /**
     * 同步执行（内部切主线程建 WebView，调用方须在工作线程）。
     *
     * @param siteKey    站点 key（用于绑定账号专属分区，复用主站会话）
     * @param accountKey 账号 key
     * @return SSO_* 之一；成功时 welfare_token 已落库到账号
     */
    public static int attempt(Context ctx0, Store store, JSONObject site, String accountKey,
                              int timeoutSec) {
        if (ctx0 == null || store == null || site == null || accountKey == null) return SSO_SKIP;
        final String welfare = SiteProfile.welfareUrl(site);
        if (welfare.isEmpty()) return SSO_SKIP;
        if (!WebViewProfileUtil.multiProfileSupported()) return SSO_NEEDS_UI;

        final Context app = ctx0.getApplicationContext();
        final String siteKey = site.optString("key", "");
        final int to = timeoutSec > 0 ? timeoutSec : 40;

        store.sysLog(siteKey, accountKey, "福利站SSO", "info", "开始静默 SSO",
                "welfare=" + SiteProfile.hostOf(welfare) + "; timeout=" + to);

        final AtomicInteger result = new AtomicInteger(SSO_NEEDS_UI);
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() ->
                new Runner(app, store, siteKey, accountKey, welfare, to, code -> {
                    result.set(code);
                    latch.countDown();
                }).start());
        try {
            if (!latch.await(to + 6L, TimeUnit.SECONDS)) {
                result.set(SSO_NEEDS_UI);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.set(SSO_NEEDS_UI);
        }
        int code = result.get();
        store.sysLog(siteKey, accountKey, "福利站SSO",
                code == SSO_OK ? "ok" : "warn", "SSO 结束", describe(code) + "; code=" + code);
        return code;
    }

    private interface Done { void onDone(int code); }

    private static final class Runner {
        private final Context ctx;
        private final Store store;
        private final String siteKey, accountKey, welfare;
        private final int timeoutSec;
        private final Done done;
        private final Handler main = new Handler(Looper.getMainLooper());
        private WebView wv;
        private volatile boolean finished = false;
        private final Runnable watchdog;

        Runner(Context c, Store s, String siteKey, String accountKey, String welfare,
               int to, Done done) {
            this.ctx = c; this.store = s; this.siteKey = siteKey; this.accountKey = accountKey;
            this.welfare = welfare; this.timeoutSec = to; this.done = done;
            this.watchdog = () -> finish(SSO_NEEDS_UI);
        }

        void start() {
            try {
                wv = new WebView(ctx);
                /* 绑账号专属分区：里面已有主站会话（首次人工授权建立）。 */
                Profile profile = WebViewProfileUtil.bindProfile(wv,
                        WebViewProfileUtil.profileNameFor(siteKey, accountKey));
                try {
                    CookieManager pcm = WebViewProfileUtil.cookieManagerFor(profile);
                    pcm.setAcceptCookie(true);
                    pcm.setAcceptThirdPartyCookies(wv, true);
                } catch (Exception ignored) {}
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.setVisibility(WebView.INVISIBLE);
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        if (finished || url == null) return;
                        /* 每次导航都探一次令牌：SSO 换完后站点会把 welfare_token 写进 localStorage。 */
                        main.postDelayed(() -> probe(), 600);
                    }
                });
                main.postDelayed(watchdog, timeoutSec * 1000L);
                /* 直接打开福利站首页：它的前端会自动发起 SSO（站点自身行为），
                 * 我们只等它把 token 写进 localStorage。 */
                wv.loadUrl(welfare + "/");
            } catch (Exception e) {
                finish(SSO_NEEDS_UI);
            }
        }

        /** 读站点自己写的 welfare_token（站点登录成功的产物）。 */
        private void probe() {
            if (finished || wv == null) return;
            try {
                wv.evaluateJavascript(
                        "(function(){try{var t=localStorage.getItem('welfare_token');" +
                        "return t?String(t):'';}catch(e){return '';}})()",
                        value -> {
                            if (finished || value == null) return;
                            String t = value.replace("\"", "").trim();
                            if (t.isEmpty() || "null".equals(t)) return;
                            /* 只落库，不回显 —— 令牌绝不进日志。 */
                            try {
                                store.patchAccount(accountKey,
                                        new JSONObject().put("welfareToken", t));
                            } catch (Exception ignored) {}
                            finish(SSO_OK);
                        });
            } catch (Exception ignored) {}
        }

        private void finish(int code) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(watchdog);
            try { if (wv != null) { wv.stopLoading(); wv.destroy(); } } catch (Exception ignored) {}
            done.onDone(code);
        }

        private final class Bridge {
            /* 预留：目前只用 evaluateJavascript 轮询，不需要回调。 */
            @JavascriptInterface public void noop(String s) {}
        }
    }
}
