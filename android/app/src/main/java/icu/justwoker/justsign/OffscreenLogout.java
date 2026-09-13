package icu.justwoker.justsign;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OffscreenLogout —— 登出链路的「备用方案」（主方案是 Engine.logoutSession 的 OkHttp 直连）。
 *
 * 【为什么需要它】
 * AgentRouter 前置阿里云 WAF，实测有两种模式：
 *   1. 看请求头特征放行 —— 补全浏览器头的 OkHttp 即可通过（主方案覆盖）；
 *   2. 强制 JS 质询 —— 返回 acw_sc__v2 / aliyun_waf 质询 HTML，必须真实执行 JS 才能拿到放行 Cookie。
 * 模式 2 下 OkHttp 无论如何伪装都拿不到 JSON，只能用真实浏览器内核跑一遍。
 * 本类在离屏 WebView（不 attach 窗口、零 UI、零通知，与 OffscreenCheckin 同款）里
 * 用 fetch(credentials:'include') 调 /api/user/logout，天然携带 WebView 侧 Cookie 与真实指纹。
 *
 * 【判据】只认响应体语义，不看状态码：
 *   - JSON 且 success=true            → LOGOUT_OK
 *   - JSON 且 success=false           → LOGOUT_REFUSED
 *   - HTTP 401                        → LOGOUT_ABSENT（会话本就不存在，幂等成功）
 *   - 非 JSON / WAF 质询 / 超时       → LOGOUT_UNKNOWN
 *
 * 【安全约束】全程后台无界面；不发起 OAuth；不写业务数据；日志只记 host/path/布尔/状态码，
 * 绝不记录 Cookie、token 或响应正文。
 */
public final class OffscreenLogout {
    private static final String LOG_SCHEMA = "auth-v2";
    private static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    /** 备用通道结果分级（与 ReauthManager 分级解耦，由调用方映射）。 */
    public static final int LOGOUT_OK = 0;
    public static final int LOGOUT_ABSENT = 1;
    public static final int LOGOUT_UNKNOWN = 2;
    public static final int LOGOUT_REFUSED = 3;

    private OffscreenLogout() {}

    /**
     * 同步执行备用登出（内部自动切主线程创建 WebView，调用方必须在工作线程）。
     *
     * @param baseUrl    站点根地址（不带尾斜杠）
     * @param cookie     业务侧已持有的会话 Cookie，注入 WebView CookieManager 以复用同一会话
     * @param siteUserId New-Api-User 头（New API 系必需，可为空）
     * @param timeoutSec 整体超时秒数
     * @return LOGOUT_* 之一
     */
    public static int logout(Context ctx0, Store store, String siteKey, String accountKey,
                             String baseUrl, String cookie, String siteUserId, int timeoutSec) {
        if (ctx0 == null || baseUrl == null || baseUrl.isEmpty()) return LOGOUT_UNKNOWN;
        final Context app = ctx0.getApplicationContext();
        final String base = baseUrl.replaceAll("/+$", "");
        final int to = timeoutSec > 0 ? timeoutSec : 45;
        final String trace = Long.toString(System.nanoTime(), 36);
        if (store != null) {
            store.opLog(siteKey, accountKey, "登出", "info", "启用备用登出通道（离屏浏览器）",
                    "schema=" + LOG_SCHEMA + "；trace=" + trace + "；host=" + hostOf(base)
                            + "；path=/api/user/logout；oauth=false", "auto");
        }
        final AtomicReference<Integer> result = new AtomicReference<>(LOGOUT_UNKNOWN);
        final AtomicReference<String> diag = new AtomicReference<>("timeout");
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() ->
                new Runner(app, store, base, cookie, siteUserId, to, (code, note) -> {
                    result.set(code);
                    diag.set(note);
                    latch.countDown();
                }).start());
        try {
            /* 多给 5s 缓冲，让 Runner 自己的看门狗先出结果，避免双超时竞态。 */
            if (!latch.await(to + 5L, TimeUnit.SECONDS)) {
                result.set(LOGOUT_UNKNOWN);
                diag.set("outer-timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.set(LOGOUT_UNKNOWN);
            diag.set("interrupted");
        }
        int code = result.get();
        if (store != null) {
            store.opLog(siteKey, accountKey, "登出", code == LOGOUT_OK || code == LOGOUT_ABSENT ? "ok" : "warn",
                    "备用登出通道结束", "schema=" + LOG_SCHEMA + "；trace=" + trace
                            + "；result=" + describe(code) + "；detail=" + diag.get() + "；oauth=false", "auto");
        }
        return code;
    }

    public static String describe(int code) {
        switch (code) {
            case LOGOUT_OK:      return "站点已接受注销";
            case LOGOUT_ABSENT:  return "会话已不存在";
            case LOGOUT_REFUSED: return "站点拒绝注销";
            default:             return "无法确认";
        }
    }

    private static String hostOf(String url) {
        try { return android.net.Uri.parse(url).getHost(); } catch (Exception e) { return "-"; }
    }

    private interface Done { void on(int code, String note); }

    /** WebView 生命周期全部锁在主线程（在非 UI 线程 new WebView 会直接抛异常崩进程）。 */
    private static final class Runner {
        private final Context ctx;
        private final Store store;
        private final String base, cookie, siteUserId;
        private final int timeoutSec;
        private final Done done;
        private final Handler main = new Handler(Looper.getMainLooper());
        private WebView wv;
        private boolean finished = false;
        private final Runnable watchdog;

        Runner(Context ctx, Store store, String base, String cookie, String siteUserId,
               int timeoutSec, Done done) {
            this.ctx = ctx;
            this.store = store;
            this.base = base;
            this.cookie = cookie == null ? "" : cookie;
            this.siteUserId = siteUserId == null ? "" : siteUserId;
            this.timeoutSec = timeoutSec;
            this.done = done;
            this.watchdog = () -> finish(LOGOUT_UNKNOWN, "wait-timeout");
        }

        void start() {
            try {
                injectCookies();
                wv = new WebView(ctx); /* 离屏：不 attach 任何窗口 */
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(UA);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                CookieManager.getInstance().setAcceptCookie(true);
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
                wv.addJavascriptInterface(new Bridge(), "JsBridge");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        /* 页面（含 WAF 质询）跑完后再发 fetch，此时放行 Cookie 已落地。 */
                        if (!finished) v.evaluateJavascript(js(), null);
                    }
                });
                main.postDelayed(watchdog, timeoutSec * 1000L);
                applyProxyThen(() -> {
                    if (wv != null) wv.loadUrl(base + "/");
                });
            } catch (Exception e) {
                finish(LOGOUT_UNKNOWN, "start-exception:" + e.getClass().getSimpleName());
            }
        }

        /** 把业务侧 Cookie 注入 WebView，使 fetch 复用同一个服务端会话。 */
        private void injectCookies() {
            if (cookie.isEmpty()) return;
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (String pair : cookie.split(";")) {
                String p = pair.trim();
                if (p.isEmpty() || p.indexOf('=') <= 0) continue;
                cm.setCookie(base, p + "; Path=/");
            }
            cm.flush();
        }

        /** 进程级 WebView 代理，与 OffscreenCheckin/AuthActivity 同款策略。 */
        private void applyProxyThen(Runnable then) {
            JSONObject proxy = null;
            try { proxy = store == null ? null : store.config().optJSONObject("proxy"); }
            catch (Exception ignored) {}
            boolean enabled = proxy != null && proxy.optBoolean("enabled");
            if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                then.run();
                return;
            }
            try {
                String host = proxy.optString("host", "127.0.0.1");
                int port = proxy.optInt("port", 0);
                if (port <= 0) { then.run(); return; }
                ProxyConfig cfg = new ProxyConfig.Builder()
                        .addProxyRule(host + ":" + port).addDirect().build();
                ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, then);
            } catch (Exception e) {
                then.run();
            }
        }

        /**
         * 只认响应体语义：拿 status + 原始文本回传 Java 侧判定。
         * credentials:'include' 保证带 Cookie；New-Api-User 头补齐 New API 中间件要求。
         */
        private String js() {
            String uidHeader = siteUserId.isEmpty() ? ""
                    : ",'New-Api-User':" + JSONObject.quote(siteUserId);
            return "(function(){try{"
                    + "fetch('/api/user/logout',{method:'GET',credentials:'include',"
                    + "headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'"
                    + uidHeader + "}})"
                    + ".then(function(r){return r.text().then(function(t){"
                    + "JsBridge.onLogout(JSON.stringify({status:r.status,body:t.slice(0,2048)}));});})"
                    + ".catch(function(e){JsBridge.onLogout(JSON.stringify({status:0,"
                    + "body:'',error:String(e&&e.message||e)}));});"
                    + "}catch(e){JsBridge.onLogout(JSON.stringify({status:0,body:'',"
                    + "error:String(e&&e.message||e)}));}})();";
        }

        private final class Bridge {
            @JavascriptInterface public void onLogout(String json) {
                /* JS 桥回调在 WebView 内部线程，动 WebView 必须切主线程。 */
                main.post(() -> handle(json));
            }
        }

        private void handle(String json) {
            int status = 0;
            String body = "", error = "";
            try {
                JSONObject o = new JSONObject(json);
                status = o.optInt("status", 0);
                body = o.optString("body", "");
                error = o.optString("error", "");
            } catch (Exception ignored) {}
            if (status == 401) { finish(LOGOUT_ABSENT, "http=401"); return; }
            if (status == 403) { finish(LOGOUT_REFUSED, "http=403"); return; }
            if (Engine.wafBlocked(body)) { finish(LOGOUT_UNKNOWN, "waf-challenge"); return; }
            if (status == 0) {
                finish(LOGOUT_UNKNOWN, "fetch-error:" + (error.isEmpty() ? "unknown" : safeNote(error)));
                return;
            }
            /* 只看响应体：success 字段是唯一权威语义。 */
            try {
                JSONObject o = new JSONObject(body);
                if (o.has("success")) {
                    finish(o.optBoolean("success", false) ? LOGOUT_OK : LOGOUT_REFUSED,
                            "http=" + status + "；success=" + o.optBoolean("success", false));
                    return;
                }
                finish(LOGOUT_UNKNOWN, "http=" + status + "；json-without-success");
            } catch (Exception e) {
                finish(LOGOUT_UNKNOWN, "http=" + status + "；non-json");
            }
        }

        /** 日志脱敏：只保留短异常类别，绝不外泄正文。 */
        private static String safeNote(String raw) {
            String s = raw.replaceAll("[^A-Za-z0-9 :._-]", "");
            return s.length() > 40 ? s.substring(0, 40) : s;
        }

        private synchronized void finish(int code, String note) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(watchdog);
            final WebView w = wv;
            wv = null;
            main.post(() -> {
                if (w != null) {
                    try { w.stopLoading(); w.removeJavascriptInterface("JsBridge"); w.destroy(); }
                    catch (Exception ignored) {}
                }
            });
            done.on(code, note);
        }
    }
}
