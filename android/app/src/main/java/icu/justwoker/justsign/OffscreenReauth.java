package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
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
 * OffscreenReauth（v1.1.15）—— linuxdo 站「静默重授权」。
 *
 * 【为什么需要它 / 真机依据】
 * 真机实证（2026-09-20 01:20~01:27）：旧会话失效后，原生 OkHttp 刷新被阿里云 WAF 挡成 503
 * （native 跑不了 WAF 的 JS 质询），离屏只读兜底也救不回「已过期的会话」——因为只读只能过盾、
 * 不能重建会话。用户随后手动「重新授权」在**同一节点**上 ~2 秒成功：关键在于账号专属 Profile
 * 分区里 connect.linux.do 的**论坛会话 Cookie 仍然活着**，站点 SPA 直接复用它一路走到确认页、
 * 点「允许」拿回新的站点会话——全程无需人机验证。
 *
 * 本类把那条「复用论坛会话」的可见授权流程搬到**离屏**（零 UI、零通知）：
 *   1) 绑定账号专属 Profile（继承论坛会话 + WAF 放行 Cookie）；
 *   2) 加载 /login，与原生同一代理出口；
 *   3) 真实浏览器内核跑 WAF 质询 + 站点 SPA；Java 侧定时器注入三段脚本：
 *      a) AuthFillJs.linuxdoObserverJs —— 若停在登录页，像人一样点一次「Linux DO」按钮；
 *      b) AuthFillJs.authorizeJs       —— 若到确认页，精确点 approve 链接（绝不点 decline）；
 *      c) AuthProbeJs.render           —— 探测 /api/user/self，成功即回传身份+响应体；
 *   4) self 返回 success 且 **data.id == 本账号 siteUserId**（身份必须相符，防串号）→
 *      抓取该 Profile 的最新 Cookie 落库，重授权完成。
 *
 * 【安全约束】
 *   · 仅 linuxdo provider、且该 Profile 论坛会话仍在（linuxdoLoggedIn）才尝试；否则直接 needUi，
 *     绝不在离屏里碰 Cloudflare/人机验证（那必须可见交互）。
 *   · 身份不符绝不落库。全程不写 GitHub 会话、不点 decline、不记 Cookie/token/正文。
 *   · 有总超时看门狗；失败即回退到既有「可见重授权」路径，行为不比现状差。
 */
public final class OffscreenReauth {

    private static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    public static final int REAUTH_OK = 0;       // 会话已重建并落库
    public static final int REAUTH_NEEDS_UI = 1; // 静默不成，需可见授权（论坛会话也失效/人机验证）
    public static final int REAUTH_SKIP = 2;     // 前置条件不满足（非 linuxdo / 无专属分区），不算失败

    private OffscreenReauth() {}

    /**
     * 同步执行静默重授权（内部自动切主线程创建 WebView，调用方必须在工作线程）。
     * @return REAUTH_* 之一
     */
    public static int attempt(Context ctx0, Store store, JSONObject site, String accountKey, int timeoutSec) {
        if (ctx0 == null || store == null || site == null || accountKey == null) return REAUTH_SKIP;
        if (!WebViewProfileUtil.multiProfileSupported()) return REAUTH_SKIP;
        final Context app = ctx0.getApplicationContext();
        final String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) return REAUTH_SKIP;
        JSONObject acc = store.findAccount(accountKey);
        if (acc == null) return REAUTH_SKIP;
        String provider = SiteProtocol.provider(site, acc);
        if (!"linuxdo".equals(provider)) return REAUTH_SKIP; // 仅 linuxdo 复用论坛会话可静默
        final String siteKey = site.optString("key", "");
        final String expectId = clean(acc.optString("siteUserId", ""));
        final int to = timeoutSec > 0 ? timeoutSec : 30;

        store.opLog(siteKey, accountKey, "静默重授权", "info", "尝试离屏复用论坛会话重建站点会话",
                "provider=linuxdo；host=" + hostOf(base) + "；oauth=false", "auto");

        final AtomicReference<Integer> result = new AtomicReference<>(REAUTH_NEEDS_UI);
        final AtomicReference<String> diag = new AtomicReference<>("timeout");
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() ->
                new Runner(app, store, siteKey, accountKey, base, expectId, to, (code, note) -> {
                    result.set(code);
                    diag.set(note);
                    latch.countDown();
                }).start());
        try {
            if (!latch.await(to + 6L, TimeUnit.SECONDS)) {
                result.set(REAUTH_NEEDS_UI);
                diag.set("outer-timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.set(REAUTH_NEEDS_UI);
            diag.set("interrupted");
        }
        int code = result.get();
        store.opLog(siteKey, accountKey, "静默重授权", code == REAUTH_OK ? "ok" : "warn",
                code == REAUTH_OK ? "离屏重授权成功，站点会话已刷新"
                        : "离屏静默未成，转可见授权", "result=" + describe(code)
                        + "；detail=" + diag.get() + "；oauth=false", "auto");
        return code;
    }

    public static String describe(int code) {
        switch (code) {
            case REAUTH_OK:       return "已重建会话";
            case REAUTH_NEEDS_UI: return "需可见授权";
            default:              return "跳过";
        }
    }

    private static String hostOf(String url) {
        try { return android.net.Uri.parse(url).getHost(); } catch (Exception e) { return "-"; }
    }
    private static String clean(String s) { return s == null || "null".equals(s) ? "" : s.trim(); }

    private interface Done { void on(int code, String note); }

    /** WebView 生命周期锁主线程；Java 侧定时器驱动脚本注入（SPA 路由跳转不触发 onPageFinished）。 */
    private static final class Runner {
        private final Context ctx;
        private final Store store;
        private final String siteKey, accountKey, base, expectId;
        private final int timeoutSec;
        private final Done done;
        private final Handler main = new Handler(Looper.getMainLooper());
        private WebView wv;
        private Profile profile;
        private volatile boolean finished = false;
        private volatile boolean forumChecked = false;
        private final long startMs = System.currentTimeMillis();
        private final Runnable watchdog;
        private final Runnable tick;

        Runner(Context ctx, Store store, String siteKey, String accountKey, String base,
               String expectId, int timeoutSec, Done done) {
            this.ctx = ctx; this.store = store; this.siteKey = siteKey; this.accountKey = accountKey;
            this.base = base; this.expectId = expectId; this.timeoutSec = timeoutSec; this.done = done;
            this.watchdog = () -> finish(REAUTH_NEEDS_UI, "wait-timeout");
            this.tick = new Runnable() {
                @Override public void run() {
                    if (finished || wv == null) return;
                    String cur = wv.getUrl();
                    if (cur != null && AuthProbeJs.sameOrigin(cur, base)) {
                        /* 站点自身域：先探身份，再（若停在登录/确认页）点按钮推进。 */
                        try { wv.evaluateJavascript(AuthProbeJs.render(base, expectId), null); } catch (Exception ignored) {}
                        try { wv.evaluateJavascript(AuthPageGuard.wrap(cur, AuthFillJs.linuxdoObserverJs()), null); } catch (Exception ignored) {}
                    }
                    if (cur != null && cur.toLowerCase(java.util.Locale.US).contains("linux.do")) {
                        /* connect.linux.do 确认页：精确点 approve（AuthPageGuard 保证质询态不执行）。 */
                        try { wv.evaluateJavascript(AuthPageGuard.wrap(cur, AuthFillJs.authorizeJs()), null); } catch (Exception ignored) {}
                    }
                    main.postDelayed(this, 1500);
                }
            };
        }

        void start() {
            try {
                wv = new WebView(ctx);
                profile = WebViewProfileUtil.bindProfile(wv, WebViewProfileUtil.profileNameFor(siteKey, accountKey));
                /* 关键：论坛会话若已失效，静默必然过不了人机验证——直接 needUi，绝不在离屏碰 CF。 */
                if (!WebViewProfileUtil.linuxdoLoggedIn(profile)) {
                    finish(REAUTH_NEEDS_UI, "forum-session-absent");
                    return;
                }
                forumChecked = true;
                try {
                    CookieManager pcm = WebViewProfileUtil.cookieManagerFor(profile);
                    pcm.setAcceptCookie(true);
                    pcm.setAcceptThirdPartyCookies(wv, true);
                } catch (Exception ignored) {}
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(UA);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                wv.setVisibility(WebView.INVISIBLE);
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        if (finished || url == null) return;
                        if (AuthProbeJs.sameOrigin(url, base)) {
                            try { v.evaluateJavascript(AuthProbeJs.render(base, expectId), null); } catch (Exception ignored) {}
                        }
                    }
                });
                main.postDelayed(watchdog, timeoutSec * 1000L);
                main.postDelayed(tick, 2500);
                applyProxyThen(() -> { if (!finished && wv != null) wv.loadUrl(base + "/login"); });
            } catch (Exception e) {
                finish(REAUTH_NEEDS_UI, "start-exception:" + e.getClass().getSimpleName());
            }
        }

        private void applyProxyThen(Runnable then) {
            JSONObject proxy = null;
            try { proxy = store.config().optJSONObject("proxy"); } catch (Exception ignored) {}
            boolean enabled = proxy != null && proxy.optBoolean("enabled");
            if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { then.run(); return; }
            final String host = proxy.optString("host", "127.0.0.1");
            final int port = proxy.optInt("port", 0);
            final String scheme = "http".equals(proxy.optString("type")) ? "http" : "socks5";
            if (port <= 0) { then.run(); return; }
            new Thread(() -> {
                boolean alive = ProxyDetect.reachable(host, port, 1500);
                main.post(() -> {
                    if (finished) return;
                    if (!alive) { then.run(); return; }
                    try {
                        ProxyConfig pc = new ProxyConfig.Builder()
                                .addProxyRule(scheme + "://" + host + ":" + port)
                                .addDirect().build();
                        ProxyController.getInstance().setProxyOverride(pc, Runnable::run, then);
                    } catch (Exception e) { then.run(); }
                });
            }, "offscreen-reauth-proxy").start();
        }

        private final class Bridge {
            /** 站点会话探测结果（AuthProbeJs 回调）。 */
            @JavascriptInterface public void onSelfResult(int status, String body) {
                main.post(() -> handleSelf(status, body));
            }
            @JavascriptInterface public void onSelfFailed(String error) { /* 网络瞬断：等下一轮 tick 再探 */ }
            /** 观察者/授权脚本的动作回报（此处仅忽略，动作事实由 self 探测收敛，不落敏感数据）。 */
            @JavascriptInterface public void onFill(String json) { /* no-op：避免脚本报错，动作结果以 self 为准 */ }
        }

        private void handleSelf(int status, String body) {
            if (finished) return;
            if (status != 200 || body == null || body.isEmpty()) return; // 未就绪/质询中：等下一轮
            JSONObject j;
            try { j = new JSONObject(body); } catch (Exception e) { return; } // 非 JSON（质询页）：继续等
            if (!j.optBoolean("success", false)) return; // 未登录：继续等（论坛会话在，SPA 仍在推进）
            /* data 可能是直层用户对象，也可能嵌 data.data */
            JSONObject d = j.optJSONObject("data");
            if (d == null) return;
            JSONObject u = d.optJSONObject("data");
            if (u == null) u = d;
            String gotId = clean(String.valueOf(u.opt("id")));
            /* 身份必须相符：防止分区串号把别的账号会话落到本账号。expectId 为空时不放行。 */
            if (expectId.isEmpty() || gotId.isEmpty() || !gotId.equals(expectId)) {
                finish(REAUTH_NEEDS_UI, "identity-mismatch-or-unknown");
                return;
            }
            /* 抓取该 Profile 的最新 Cookie（含刚重建的站点会话 + WAF 放行）落库。 */
            String cookie = "";
            try {
                CookieManager.getInstance().flush();
                cookie = WebViewProfileUtil.cookieHeader(profile, base);
            } catch (Exception ignored) {}
            if (cookie == null || cookie.trim().isEmpty()) {
                finish(REAUTH_NEEDS_UI, "cookie-empty");
                return;
            }
            /* 站点会话型（linuxdo）：token 尽力而为，可空。 */
            String token = "";
            for (String k : new String[]{"access_token", "accessToken", "token"}) {
                Object v = u.opt(k);
                if (v != null) {
                    String sv = String.valueOf(v).trim();
                    if (!sv.isEmpty() && !"null".equals(sv)) { token = sv; break; }
                }
            }
            try {
                long nowMs = System.currentTimeMillis();
                JSONObject patch = new JSONObject()
                        .put("siteKey", siteKey)
                        .put("updatedAt", nowMs)
                        .put("lastLogin", nowMs)
                        .put("authProvider", "linuxdo")
                        .put("siteCookie", cookie);
                if (!token.isEmpty()) patch.put("token", token);
                store.patchAccount(accountKey, patch);
                store.opLog(siteKey, accountKey, "静默重授权", "info", "新会话凭据指纹",
                        "cookieFP=" + SilentAuth.sessionFingerprint(cookie), "auto");
            } catch (Exception e) {
                finish(REAUTH_NEEDS_UI, "persist-exception");
                return;
            }
            finish(REAUTH_OK, "self-ok；id-matched");
        }

        private synchronized void finish(int code, String note) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(watchdog);
            main.removeCallbacks(tick);
            final WebView w = wv;
            wv = null;
            main.post(() -> {
                if (w != null) {
                    try { w.stopLoading(); w.removeJavascriptInterface("JustSign"); w.destroy(); }
                    catch (Exception ignored) {}
                }
            });
            done.on(code, note);
        }
    }
}
