package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

/**
 * OffscreenCheckin - 纯后台无界面签到（用户核心目标：全后台、不弹窗）：
 *   在离屏 WebView（不 attach 任何窗口、零 UI、零通知）中执行与可见兜底完全相同的签到 JS，
 *   真实浏览器环境 + 官方 invisible Turnstile 无感通过（不伪造、不答题、指纹真实）。
 * 使用方：
 *   - MainActivity 立即签到（后台跑完 toast 结果；失败自动转 CheckinActivity 可见兜底）
 *   - Engine.runAllOnce（WorkManager 调度，纯后台，结果写运行日志）
 *
 * v0.1.8 修复（全量审计）：
 *   1. WebView 生命周期全部锁死主线程（v0.1.7 已把创建搬回主线程，本版把 destroy/超时/回调统一到主线程）；
 *      彻底移除 HandlerThread（WebView 在非 UI 线程 new 会抛 IllegalStateException 直接崩进程）。
 *   2. 代理挂载改为「先探活、探活完成后再 loadUrl」：原实现探活在子线程、loadUrl 不等它，
 *      导致代理还没挂上页面就已经开始加载 → GitHub/CF 资源直连被 RST。
 *   3. onPageFinished 可能多次触发（重定向），fired 用 volatile + 单次判定；
 *      并新增 onReceivedHttpError（主帧 5xx/403 也算失败，原来只处理 net error）。
 *   4. token/siteKey 只读一次快照，避免签到过程中的库写入引起前后不一致。
 *   5. siteKey 三级回退：站点 meta → 账号旧字段（兼容 v0.1.6 数据）→ JS 现场 /api/status；
 *      JS 拿到后回传，Java 落库到站点 meta（站点级共享，新增账号无需重新刷新）。
 *   6. 结果落库统一走 Store.patchAccount（锁内合并），不再整对象覆盖导致并发丢字段。
 *   7. 徽章写入条件放宽：已签(already) 也写当日徽章（原来仅 reward>0 才写，导致"今日已签"不置灰）。
 *   8. finish 幂等 + 超时看门狗 removeCallbacks，避免超时回调在成功后又触发一次。
 */
public final class OffscreenCheckin {
    private static final String LOG_SCHEMA = "auth-v2";

    /**
     * @param rewardKnown 服务端是否给出了明确的奖励数额
     *                    （false = 查不到记录，UI 不应展示金额；true + reward==0 = 本站签到无奖励）
     */
    public interface Callback {
        void onResult(boolean ok, boolean already, double reward, boolean rewardKnown, String message);
    }

    private OffscreenCheckin() {}

    /** 线程安全入口：可从任意线程调用（内部自动切主线程） */
    public static void run(Context ctx0, String siteKey, String accountKey, int timeoutSec, Callback cb) {
        if (ctx0 == null || cb == null) return;
        final Context app = ctx0.getApplicationContext();
        final int to = timeoutSec > 0 ? timeoutSec : 100;
        final String trace = Long.toString(System.nanoTime(), 36);
        final Store startStore = new Store(app);
        startStore.opLog(siteKey, accountKey, "后台签到V2", "info", "签到链路开始",
                "schema=" + LOG_SCHEMA + "；trace=" + trace + "；preRefresh=true；oauth=false", "auto");
        /* WebView签到先通过Engine续期现有站点会话，绝不运行OAuth；完成后再读取快照。 */
        new Thread(() -> {
            try {
                Store st = new Store(app);
                JSONObject site = st.findSite(siteKey);
                if (site != null) new Engine(app).ensureBusinessToken(site, accountKey);
            } catch (Exception ignored) {}
            new Handler(Looper.getMainLooper()).post(() ->
                    runAttempt(app, siteKey, accountKey, to, trace, 0, cb));
        }, "offcheckin-refresh").start();
    }

    /** Turnstile 仅对明确 wait-timeout 做一次安全重试；全程同一 trace，避免重复任务失控。 */
    private static void runAttempt(Context app, String siteKey, String accountKey, int timeoutSec,
                                   String trace, int attempt, Callback cb) {
        new Runner(app, siteKey, accountKey, timeoutSec, (ok, already, reward, known, message) -> {
            String safe = message == null ? "" : message;
            boolean turnstileTimeout = !ok && safe.contains("wait-timeout");
            Store store = new Store(app);
            store.opLog(siteKey, accountKey, "后台签到V2", ok ? "ok" : "warn", "签到尝试结束",
                    "schema=" + LOG_SCHEMA + "；trace=" + trace + "；attempt=" + (attempt + 1)
                            + "；ok=" + ok + "；already=" + already + "；turnstileTimeout=" + turnstileTimeout
                            + "；network=" + networkClass(safe) + "；oauth=false", "auto");
            if (turnstileTimeout && attempt == 0) {
                store.opLog(siteKey, accountKey, "后台签到V2", "info", "Turnstile 超时，安全重试一次",
                        "schema=" + LOG_SCHEMA + "；trace=" + trace + "；siteKeyPresent=true；attempt=2", "auto");
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> runAttempt(app, siteKey, accountKey, timeoutSec, trace, 1, cb), 1200L);
                return;
            }
            cb.onResult(ok, already, reward, known, safe);
        }).start();
    }

    private static String networkClass(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.US);
        if (m.contains("timeout")) return "timeout";
        if (m.contains("net::") || m.contains("加载失败")) return "network";
        if (m.contains("http 403") || m.contains("cf-")) return "challenge";
        return "none";
    }

    private static final class Runner {
        private static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

        private final Context ctx;
        private final String siteKey, accountKey;
        private final int timeoutSec;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Runnable timeoutTask;

        private WebView wv;
        private volatile boolean done = false;
        private volatile boolean fired = false;
        /** JS 报「人机验证可能需要交互」→ 后台跑不通，通知调用方转可见兜底 */
        private volatile boolean needUi = false;

        Runner(Context ctx, String siteKey, String accountKey, int timeoutSec, Callback cb) {
            this.ctx = ctx; this.siteKey = siteKey; this.accountKey = accountKey;
            this.timeoutSec = timeoutSec; this.cb = cb;
            this.timeoutTask = () -> finish(false, false, 0, false,
                    needUi ? "人机验证需要手动确认" : "后台签到超时");
        }

        /** 必须在主线程调用 */
        void start() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::start); return; }
            try {
                Store store = new Store(ctx);
                JSONObject site = store.findSite(siteKey);
                if (site == null) { finish(false, false, 0, false, "站点不存在"); return; }
                final String base = site.optString("baseUrl", "").replaceAll("/+$", "");
                if (base.isEmpty()) { finish(false, false, 0, false, "站点 baseUrl 为空"); return; }
                if (accountKey == null || accountKey.isEmpty()) { finish(false, false, 0, false, "账号缺失"); return; }

                wv = new WebView(ctx); /* 离屏：不 attach 窗口，无 UI，JS 全速运行 */
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(UA);
                /* 第三方 Cookie（Turnstile 需要 challenges.cloudflare.com 的 cookie） */
                try { android.webkit.CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
                    @Override public void onPageFinished(WebView v, String url) {
                        if (done || fired) return;
                        /* 只在站点主页加载完成后注入（避免 about:blank / 跳转中间页触发） */
                        if (url == null || url.startsWith("about:")) return;
                        fired = true;
                        try { v.evaluateJavascript(js(), null); }
                        catch (Exception e) { finish(false, false, 0, false, "注入失败: " + e.getMessage()); }
                    }
                    @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                        if (r == null || !r.isForMainFrame()) return;
                        finish(false, false, 0, false,
                                "net::" + (e != null ? String.valueOf(e.getDescription()) : "unknown"));
                    }
                    @Override public void onReceivedHttpError(WebView v, WebResourceRequest r,
                                                              android.webkit.WebResourceResponse rsp) {
                        if (r == null || !r.isForMainFrame()) return;
                        int code = rsp != null ? rsp.getStatusCode() : 0;
                        if (code >= 400) finish(false, false, 0, false, "站点返回 HTTP " + code);
                    }
                });
                main.postDelayed(timeoutTask, timeoutSec * 1000L);
                /* 代理探活完成后才加载（v0.1.8 修复竞态：原来不等探活就 loadUrl） */
                applyProxyThen(new Store(ctx), () -> {
                    if (done) return;
                    try { if (wv != null) wv.loadUrl(base + "/"); }
                    catch (Exception e) { finish(false, false, 0, false, "加载失败: " + e.getMessage()); }
                });
            } catch (Throwable t) {
                finish(false, false, 0, false, "后台签到异常: " + t.getMessage());
            }
        }

        /**
         * 进程级 WebView 代理（与 AuthActivity/CheckinActivity 同款）。
         * 关键：探活在子线程，结果回主线程挂载，挂载完成（或跳过）后才执行 then。
         */
        private void applyProxyThen(Store store, Runnable then) {
            JSONObject p;
            try { p = store.config().optJSONObject("proxy"); } catch (Exception e) { p = null; }
            final boolean enabled = p != null && p.optBoolean("enabled");
            if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { then.run(); return; }
            final String host = p.optString("host", "127.0.0.1");
            final int port = p.optInt("port", 10808);
            new Thread(() -> {
                boolean alive = false;
                try { /* 探测代理端口，防挂死代理全断 */
                    java.net.Socket sk = new java.net.Socket();
                    sk.connect(new java.net.InetSocketAddress(host, port), 1500);
                    alive = true; sk.close();
                } catch (Exception ignored) {}
                final boolean use = alive;
                main.post(() -> {
                    if (done) return;
                    if (!use) { then.run(); return; }
                    try {
                        ProxyConfig pc = new ProxyConfig.Builder()
                                .addProxyRule("socks5://" + host + ":" + port)
                                /* 站点与 CF 挑战都走代理，本地回环直连 */
                                .addDirect()
                                .build();
                        ProxyController.getInstance().setProxyOverride(pc, Runnable::run, then);
                    } catch (Exception e) { then.run(); }
                });
            }, "offcheckin-proxy").start();
        }

        /* ---------- 账号快照（只读一次，避免过程中被并发写入影响） ---------- */
        private String snapToken, snapSiteKey, snapSiteUserId;

        private void loadSnapshot() {
            if (snapToken != null) return;
            String tk = "", sk = "", su = "";
            try {
                Store st = new Store(ctx);
                JSONObject r = st.findAccount(accountKey);
                if (r != null) {
                    tk = r.optString("token", "");
                    /* 兼容 v0.1.6 存在账号里的旧值 */
                    sk = r.optString("turnstileSiteKey", "");
                   su = r.optString("siteUserId", "");
                    if ("null".equals(su)) su = "";
                }
                /* 站点级 meta 优先（v0.1.8 新增，账号间共享） */
                String siteLevel = st.siteMeta(siteKey, "turnstileSiteKey", "");
                if (siteLevel != null && !siteLevel.isEmpty()) sk = siteLevel;
            } catch (Exception ignored) {}
            snapToken = tk == null ? "" : tk;
            snapSiteKey = sk == null ? "" : sk;
            snapSiteUserId = su == null ? "" : su;
        }

        private String js() {
            loadSnapshot();
            return CheckinJs.render(CheckinJs.extractSid(snapToken), snapSiteKey, snapToken, snapSiteUserId);
        }

        private synchronized void finish(boolean ok, boolean already, double reward,
                                        boolean rewardKnown, String message) {
            if (done) return;
            done = true;
            main.removeCallbacks(timeoutTask);

            final WebView w = wv; wv = null;
            /* destroy 必须与创建线程一致（主线程） */
            main.post(() -> {
                try { if (w != null) { w.stopLoading(); w.loadUrl("about:blank"); w.removeJavascriptInterface("JustSign"); w.destroy(); } }
                catch (Exception ignored) {}
            });

            /* 成功（含"今日已签"）→ 写本地当日徽章，置灰立即生效。
               rewardKnown=false 时不写 reward 字段，避免把「未知」显示成 $0 */
            if (ok) {
                try {
                    JSONObject lc = new JSONObject()
                            .put("date", Engine.todayStr())
                            .put("time", System.currentTimeMillis());
                    if (rewardKnown) lc.put("reward", reward);
                    new Store(ctx).patchAccount(accountKey, new JSONObject().put("lastCheckin", lc));
                } catch (Exception ignored) {}
            }

            final boolean fok = ok, fal = already, frk = rewardKnown;
            final double frw = reward;
            final String fmsg = message == null ? "" : message;
            main.post(() -> { try { cb.onResult(fok, fal, frw, frk, fmsg); } catch (Exception ignored) {} });
        }

        /** JS 与 Java 的桥：结果回传 + 续期新 token / 站点 siteKey 落库 */
        private class Bridge {
            @JavascriptInterface public void onProgress(String m) {
                /* 纯后台无 UI；只关心「需要人机交互」这个信号 */
                if (m == null || !m.contains(CheckinJs.SIGNAL_NEED_UI)) return;
                if (needUi) return;
                needUi = true;
                /* 宽限必须长于 JS 内 Turnstile 的等待上限（25s），否则会在
                 * JS 走到「日志兜底」之前就把它掐掉 —— 这批站登录即发奖励，
                 * 日志兜底才是真正能判成功的一步。留到 30s 让 JS 自己收尾；
                 * 真超时了再由 Java 兜底报错。 */
                main.postDelayed(() -> {
                    if (!done) finish(false, false, 0, false, "人机验证需要手动确认");
                }, 30000);
            }

            @JavascriptInterface public void onResult(String json) {
                /* JS 桥回调在 WebView 内部线程，必须切主线程再动 WebView/Store */
                main.post(() -> {
                    if (done) return;
                    try {
                        JSONObject r = new JSONObject(json);
                        Store store = new Store(ctx);

                        String newTk = r.optString("token", "");
                        if (newTk.length() > 20 && !newTk.equals(snapToken)) {
                            try {
                                store.patchAccount(accountKey, new JSONObject()
                                        .put("token", newTk)
                                        .put("updatedAt", System.currentTimeMillis()));
                            } catch (Exception ignored) {}
                        }
                        /* JS 现场从 /api/status 取到的 siteKey → 落库站点 meta（站点级共享） */
                        String sk = r.optString("siteKey", "");
                        if (!sk.isEmpty()) store.putSiteMeta(siteKey, "turnstileSiteKey", sk);
                        long unit = r.optLong("unit", 0);
                        if (unit > 0) store.putSiteMeta(siteKey, "quotaPerUnit", unit);

                        /* 失败原因里带上 JS 给的细节（tsError / detail），便于日志定位 */
                        String msg = r.optString("message", "");
                        String detail = r.optString("detail", "");
                        String tsErr = r.optString("tsError", "");
                        if (!r.optBoolean("ok", false)) {
                            if (!detail.isEmpty()) msg = msg + " · " + detail;
                            else if (!tsErr.isEmpty()) msg = msg + " · " + tsErr;
                        }

                        finish(r.optBoolean("ok", false), r.optBoolean("already", false),
                                r.optDouble("reward", 0), r.optBoolean("rewardKnown", false), msg);
                    } catch (Exception e) {
                        finish(false, false, 0, false, "后台签到结果解析失败");
                    }
                });
            }
        }
    }
}