package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.Profile;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 离屏探测 /api/user/self：当 OkHttp 遭遇阿里云 WAF JS 算力质询（acw_sc__v2）时，
 * 在账号专属 Profile 的离屏 WebView 中加载站点根/status触发质询脚本执行，
 * 取得放行 Cookie 并执行一次真实的 self 请求，自愈 WAF 拦截并回填最新凭据。
 */
public final class OffscreenSelfProbe {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    private OffscreenSelfProbe() {}

    public static JSONObject probe(Context context, String siteKey, String accountKey, String baseUrl, String siteUserId, int timeoutSec) {
        if (context == null || baseUrl == null || baseUrl.isEmpty()) return null;
        final Context app = context.getApplicationContext();
        final String base = baseUrl.replaceAll("/+$", "");
        final int to = timeoutSec > 0 ? timeoutSec : 25;
        final AtomicReference<JSONObject> result = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            new Runner(app, siteKey, accountKey, base, siteUserId, to, res -> {
                result.set(res);
                latch.countDown();
            }).start();
        });

        try {
            latch.await(to + 5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private interface Callback {
        void onDone(JSONObject res);
    }

    private static final class Runner {
        private final Context ctx;
        private final String siteKey, accountKey, base, siteUserId;
        private final int timeoutSec;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Store store;
        private WebView wv;
        private Profile profile;
        private JSONObject accSnapshot;
        private volatile boolean done = false;
        private final Runnable watchdog;

        Runner(Context c, String sk, String ak, String base, String uid, int to, Callback cb) {
            this.ctx = c; this.siteKey = sk; this.accountKey = ak;
            this.base = base; this.siteUserId = uid; this.timeoutSec = to;
            this.cb = cb; this.store = new Store(c);
            this.watchdog = () -> finish(null);
        }

        void start() {
            try {
                wv = new WebView(ctx);
                profile = WebViewProfileUtil.bindProfile(wv, WebViewProfileUtil.profileNameFor(siteKey, accountKey));
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setUserAgentString(UA);
                wv.addJavascriptInterface(this, "JustSign");

                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        if (done || url == null) return;
                        if (AuthProbeJs.sameOrigin(url, base)) {
                            // 注入探测脚本
                            wv.evaluateJavascript(AuthProbeJs.render(base, siteUserId), null);
                        }
                    }
                });

                main.postDelayed(watchdog, timeoutSec * 1000L);

                // 预注入账号已有 Cookie（同时记录快照，供身份通过后的写回做并发栅栏）
                JSONObject acc = store.findAccount(accountKey);
                accSnapshot = acc;
                String cookie = acc == null ? "" : acc.optString("siteCookie", "");
                CookieManager cm = WebViewProfileUtil.cookieManagerFor(profile);
                cm.setAcceptCookie(true);
                if (!cookie.isEmpty()) {
                    for (String part : cookie.split(";")) {
                        String p = part.trim();
                        if (!p.isEmpty()) cm.setCookie(base, p + "; Path=/; Secure; HttpOnly");
                    }
                    WebViewProfileUtil.flush(profile);
                }

                applyProxyThen(() -> wv.loadUrl(base + "/api/status"));
            } catch (Exception e) {
                finish(null);
            }
        }

        private void applyProxyThen(Runnable next) {
            JSONObject proxy = store.config().optJSONObject("proxy");
            if (proxy != null && proxy.optBoolean("enabled") && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    String scheme = "http".equals(proxy.optString("type")) ? "http" : "socks5";
                    String address = scheme + "://" + proxy.optString("host", "127.0.0.1") + ":" + proxy.optInt("port", 10808);
                    ProxyController.getInstance().setProxyOverride(
                            new ProxyConfig.Builder().addProxyRule(address).build(),
                            main::post, next);
                    return;
                } catch (Exception ignored) {}
            }
            next.run();
        }

        @JavascriptInterface public void onSelfResult(int status, String body) {
            main.post(() -> {
                if (done) return;
                try {
                    JSONObject out = new JSONObject().put("http", status);
                    out.put("data", new JSONObject(body));
                    if (SessionFence.identityMatches(out, siteUserId)) {
                        /* 仅当身份校验通过，且等待期间凭据未被其他流程改写时，才把放行 Cookie 落库。
                           （快照比较失败说明已有更新的凭据存在，此时用旧结果覆盖是危险的） */
                        JSONObject current = store.findAccount(accountKey);
                        if (current != null && SessionFence.sameSecret(accSnapshot, current)) {
                            String ck = WebViewProfileUtil.cookieHeader(profile, base);
                            if (ck != null && !ck.isEmpty()) {
                                String waf = Engine.pickWafCookies(ck);
                                if (!waf.isEmpty()) store.putSiteMeta(siteKey, "wafCookie", waf);
                                store.patchAccount(accountKey, new JSONObject().put("siteCookie", ck));
                            }
                        }
                    }
                    finish(out);
                } catch (Exception e) {
                    finish(null);
                }
            });
        }

        @JavascriptInterface public void onSelfFailed(String err) {
            main.post(() -> finish(null));
        }

        private synchronized void finish(JSONObject res) {
            if (done) return;
            done = true;
            main.removeCallbacks(watchdog);
            final WebView w = wv;
            wv = null;
            if (w != null) {
                try {
                    w.stopLoading();
                    w.removeJavascriptInterface("JustSign");
                    w.destroy();
                } catch (Exception ignored) {}
            }
            cb.onDone(res);
        }
    }
}
