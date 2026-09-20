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
 * 离屏只读执行器（独立于 OffscreenSelfProbe）：
 * 1) 先请求 /api/user/self 校验账号身份；
 * 2) 身份一致才读目标只读路径；
 * 3) 全程不写回任何 Cookie / 令牌 / 账号数据。
 *
 * v1.1.16 关键修复：本执行器必须绑定「授权/签到/自探/重授权」同一个账号专属 Profile 分区
 * ——即 profileNameFor(siteKey, accountKey)。旧实现误用 profileNameFor(baseUrl, siteUserId)
 * 生成的是一个**从未登录过的空分区**，里面没有站点 session Cookie；离屏虽能过阿里云 WAF 质询
 * （空分区也能写 acw_sc__v2），但 /api/user/self 恒 success=false（未登录）→ 身份探测硬停 →
 * 回传 null，表现为「离屏未拿到可用数据」，只读兜底对 session-cookie 站（如 AnyRouter）从未成功过。
 * 这正是「签到能过盾、Key 列表 503 兜底失败」长期不对称的根因。
 * siteUserId 仍保留：仅供 JS 侧身份校验（data.id 必须相符），不参与分区名。
 */
public final class OffscreenReadRunner {

    private OffscreenReadRunner() {}

    public static JSONObject read(Context context, String baseUrl, String siteKey, String accountKey,
                                  String siteUserId, String path, String allowHost, int timeoutSec) {
        if (context == null || baseUrl == null || baseUrl.isEmpty() || path == null || path.isEmpty()) return null;
        final Context app = context.getApplicationContext();
        final String base = baseUrl.replaceAll("/+$", "");
        final int to = timeoutSec > 0 ? timeoutSec : 25;
        final AtomicReference<JSONObject> result = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            new Runner(app, base, siteKey, accountKey, siteUserId, path, allowHost == null ? "" : allowHost, to, res -> {
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

    private interface Callback { void onDone(JSONObject res); }

    private static final class Runner {
        private final Context ctx;
        private final String base, siteKey, accountKey, siteUserId, targetPath, allowHost;
        private final String nonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final int timeoutSec;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private WebView wv;
        private Profile profile;
        private volatile boolean done = false;
        private final Runnable watchdog;

        Runner(Context c, String base, String siteKey, String accountKey, String uid,
               String path, String host, int to, Callback cb) {
            this.ctx = c; this.base = base; this.siteKey = siteKey; this.accountKey = accountKey;
            this.siteUserId = uid; this.targetPath = path; this.allowHost = host;
            this.timeoutSec = to; this.cb = cb;
            this.watchdog = () -> finish(null);
        }

        void start() {
            try {
                wv = new WebView(ctx);
                /* v1.1.16：绑账号专属分区（含站点 session Cookie），不再用 baseUrl+uid 的空分区。 */
                profile = WebViewProfileUtil.bindProfile(wv, WebViewProfileUtil.profileNameFor(siteKey, accountKey));
                /* v1.1.13 关键修复：本 WebView 绑的是非 Default Profile，Cookie 接受必须开在
                 * 该分区的 CookieManager 上，否则阿里云 WAF 质询页写的放行 Cookie（acw_sc__v2）
                 * 被丢弃 → 离屏跟 Chrome 用同一内核本可过盾，却因 Cookie 存不下，每次过盾白过，
                 * 日志恒为「离屏未拿到可用数据」。OffscreenSelfProbe / AnyRouterCheckin 早已这样做，
                 * 唯独只读兜底漏了这步。 */
                try {
                    CookieManager pcm = WebViewProfileUtil.cookieManagerFor(profile);
                    pcm.setAcceptCookie(true);
                    pcm.setAcceptThirdPartyCookies(wv, true);
                } catch (Exception ignored) {}
                wv.getSettings().setJavaScriptEnabled(true);
                wv.getSettings().setDomStorageEnabled(true);
                wv.getSettings().setBlockNetworkImage(true);
                wv.setVisibility(WebView.INVISIBLE);
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        if (done || url == null) return;
                        if (AuthProbeJs.sameOrigin(url, base)) {
                            /* v1.1.6 修正参数顺序：原调用把 /api/user/self 当成了站点根，
                             * 于是 fetch('/api/user/self' + '/api/user/self') 必然 404，
                             * 身份判定永远 identity-mismatch —— 该兜底从未成功过。
                             * 正确顺序：站点根 / 目标路径 / 期望身份 ID / 关联号。 */
                            wv.evaluateJavascript(
                                    OffscreenReadJs.render(base, targetPath, siteUserId, nonce), null);
                        }
                    }
                });
                new Handler(Looper.getMainLooper()).postDelayed(watchdog, timeoutSec * 1000L);
                /* v1.1.6 关键修复：离屏 WebView 必须与原生请求走同一出口。
                 * 原实现完全没有代理设置 → 原生走 socks5、离屏走直连，
                 * 站点 WAF 质询必然把离屏挡死，日志表现为「离屏未拿到可用数据」。
                 * 代理不可达时退回直连，保持原行为不阻塞。 */
                applyProxyThen(() -> { if (!done && wv != null) wv.loadUrl(base + "/login"); });
            } catch (Exception e) {
                finish(null);
            }
        }

        /** 与 SilentAuth / OffscreenSelfProbe 同一套出口策略：可达才套代理，否则直连。 */
        private void applyProxyThen(Runnable next) {
            JSONObject p;
            try { p = new Store(ctx).config().optJSONObject("proxy"); } catch (Exception e) { p = null; }
            boolean enabled = p != null && p.optBoolean("enabled");
            if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                next.run();
                return;
            }
            final String host = p.optString("host", "127.0.0.1");
            final int port = p.optInt("port", 10808);
            final String scheme = "http".equals(p.optString("type")) ? "http" : "socks5";
            new Thread(() -> {
                boolean alive = ProxyDetect.reachable(host, port, 1500);
                main.post(() -> {
                    if (done) return;
                    if (!alive) { next.run(); return; }
                    try {
                        ProxyConfig pc = new ProxyConfig.Builder()
                                .addProxyRule(scheme + "://" + host + ":" + port)
                                .addDirect().build();
                        ProxyController.getInstance().setProxyOverride(pc, Runnable::run, next);
                    } catch (Exception e) { next.run(); }
                });
            }, "offscreen-read-proxy").start();
        }

        private void finish(JSONObject res) {
            if (done) return;
            done = true;
            main.removeCallbacks(watchdog);
            try { if (wv != null) { wv.stopLoading(); wv.destroy(); } } catch (Exception ignored) {}
            cb.onDone(res);
        }

        private final class Bridge {
            /** 只读结果：身份已校验，仅回传状态与响应文本。 */
            @JavascriptInterface public void onReadResult(String nonce, String payload) {
                main.post(() -> {
                    if (done) return;
                    try {
                        JSONObject o = new JSONObject(payload);
                        if (!o.optBoolean("ok")) { finish(null); return; }
                        JSONObject out = new JSONObject().put("http", o.optInt("status", 0));
                        out.put("data", o.optString("body", ""));
                        finish(out);
                    } catch (Exception e) {
                        finish(null);
                    }
                });
            }
        }
    }
}
