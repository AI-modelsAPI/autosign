package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.Profile;
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

        /** v1.3.2：诊断日志用（只记状态与布尔，不含凭据）。 */
        private final Store store;

        Runner(Context c, String base, String siteKey, String accountKey, String uid,
               String path, String allowHostIn, int to, Callback cb) {
            this.ctx = c; this.base = base; this.siteKey = siteKey; this.accountKey = accountKey;
            this.store = new Store(c);
            this.siteUserId = uid; this.targetPath = path; this.allowHost = allowHostIn;
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
                /* v1.3.2：离屏不 attach 窗口。
                 * 曾试过用 1×1 透明宿主窗口承载（想复刻可见授权页的渲染环境），
                 * 真机实测证伪：给不给窗口都过不了本类遇到的新版阿里云滑块验证码
                 * （该验证码要求人工拖动，连设备 Chrome 打开也显示「请按住滑块，拖动到最右边」）。
                 * 宿主方案既无收益、又留下空壳 Activity 卡在前台，故撤销。 */
                wv.setVisibility(WebView.INVISIBLE);
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        if (done || url == null) return;
                        /* v1.3.2 诊断：只记「是否同源 / 是否已注入」，不含 URL 细节与凭据。
                         * 没有这行时，离屏失败完全看不到内部进展（排查 v2 盾时无据可依）。 */
                        boolean same = AuthProbeJs.sameOrigin(url, base);
                        if (store != null) {
                            try {
                                store.opLog(siteKey, accountKey, "只读离屏兜底", "info",
                                        "离屏页面加载完成", "sameOrigin=" + same + "; injected=" + same, "auto");
                            } catch (Exception ignored) {}
                        }
                        if (same) {
                            wv.evaluateJavascript(
                                    OffscreenReadJs.render(base, targetPath, siteUserId, nonce), null);
                        }
                    }
                    @Override public void onReceivedHttpError(WebView v, WebResourceRequest r,
                                                              android.webkit.WebResourceResponse resp) {
                        if (r == null || !r.isForMainFrame()) return;
                        int code = resp == null ? 0 : resp.getStatusCode();
                        if (store != null) {
                            try {
                                store.opLog(siteKey, accountKey, "只读离屏兜底", "warn",
                                        "离屏主帧 HTTP 错误", "http=" + code, "auto");
                            } catch (Exception ignored) {}
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
                    ProxyMount.apply(scheme + "://" + host + ":" + port, next, next);
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
                        /* v1.3.2：失败原因必须透传（旧实现直接 finish(null)，把 reason 整个丢掉，
                         * 上层只能报「离屏未拿到可用数据」——真机排查 v2 盾时完全看不到线索）。
                         * reason 是脚本内的固定类别词（waf-challenge / identity-mismatch /
                         * http-NNN / identity-not-ready / missing-expected-id），已经过 redact，
                         * 不含 URL、Cookie 或正文。 */
                        if (!o.optBoolean("ok")) {
                            JSONObject fail = new JSONObject()
                                    .put("http", o.optInt("status", 0))
                                    .put("reason", o.optString("reason", ""))
                                    /* v1.3.2：透传「是否值得重试」。
                                     * 未过盾/未就绪 → true（放行 Cookie 可能刚落地，重发原生请求即可）
                                     * 需人工交互/身份不符 → false（重试无意义，必须如实上报） */
                                    .put("retryable", o.optBoolean("retryable", false))
                                    .put("offscreenFailed", true);
                            finish(fail);
                            return;
                        }
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
