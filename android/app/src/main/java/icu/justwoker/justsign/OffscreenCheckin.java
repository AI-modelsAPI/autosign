package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
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
 *   在离屏 WebView（不 attach 任何窗口、零 UI、零通知）中执行与可见兑底完全相同的签到 JS，
 *   真实浏览器环境 + 官方 invisible Turnstile 无感通过（不伪造、不答题、指纹真实）。
 *   离屏 WebView 从不 onPause，页面 JS 与人机验证全速运行。
 * 使用方：
 *   - MainActivity 立即签到（后台跑完 toast 结果；失败自动转 CheckinActivity 可见兑底）
 *   - Engine.runAllOnce（WorkManager 12h 调度，纯后台，结果写运行日志）
 */
public final class OffscreenCheckin {

    public interface Callback { void onResult(boolean ok, boolean already, double reward, String message); }

    private OffscreenCheckin() {}

    public static void run(Context ctx0, String siteKey, String accountKey, int timeoutSec, Callback cb) {
        new Runner(ctx0.getApplicationContext(), siteKey, accountKey, timeoutSec, cb).start();
    }

    private static final class Runner {
        private static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

        private final Context ctx;
        private final String siteKey, accountKey;
        private final int timeoutSec;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private HandlerThread ht;
        private Handler th;
        private WebView wv;
        private volatile boolean done = false;
        private boolean fired = false;

        Runner(Context ctx, String siteKey, String accountKey, int timeoutSec, Callback cb) {
            this.ctx = ctx; this.siteKey = siteKey; this.accountKey = accountKey;
            this.timeoutSec = timeoutSec; this.cb = cb;
        }

        void start() {
            ht = new HandlerThread("offcheckin");
            ht.start();
            th = new Handler(ht.getLooper());
            th.post(this::go);
        }

        private void go() {
            try {
                Store store = new Store(ctx);
                JSONObject site = store.findSite(siteKey);
                String base = site.optString("baseUrl").replaceAll("/+$", "");
                if (base.isEmpty()) { finish(false, false, 0, "站点 baseUrl 为空"); return; }
                try { applyProxy(store); } catch (Exception ignored) {}
                wv = new WebView(ctx); /* 离屏：不 attach 窗口，无 UI，JS 全速运行 */
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(UA);
                wv.addJavascriptInterface(new Bridge(), "JustSign");
                wv.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
                    @Override public void onPageFinished(WebView v, String url) {
                        if (!fired) { fired = true; v.evaluateJavascript(js(), null); }
                    }
                    @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                        if (r == null || !r.isForMainFrame()) return;
                        finish(false, false, 0, "net::" + (e != null ? String.valueOf(e.getDescription()) : "unknown"));
                    }
                });
                th.postDelayed(() -> finish(false, false, 0, "后台签到超时（人机验证未完成）"), timeoutSec * 1000L);
                wv.loadUrl(base + "/");
            } catch (Exception e) {
                finish(false, false, 0, "后台签到异常: " + e.getMessage());
            }
        }

        /** 进程级 WebView 代理（与 AuthActivity/CheckinActivity 同款；幂等） */
        private void applyProxy(Store store) {
            JSONObject p = store.config().optJSONObject("proxy");
            if (p != null && p.optBoolean("enabled") && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig pc = new ProxyConfig.Builder()
                        .addProxyRule("socks5://" + p.optString("host", "127.0.0.1") + ":" + p.optInt("port", 10808))
                        .build();
                ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {});
            }
        }

        private String token() {
            try { JSONObject r = new Store(ctx).findAccount(accountKey); return r == null ? "" : r.optString("token", ""); }
            catch (Exception e) { return ""; }
        }
        private String sitekey() {
            try { JSONObject r = new Store(ctx).findAccount(accountKey); return r == null ? "" : r.optString("turnstileSiteKey", ""); }
            catch (Exception e) { return ""; }
        }
        private String js() { String tk = token(); return CheckinJs.render(CheckinJs.extractSid(tk), sitekey(), tk); }

        private synchronized void finish(boolean ok, boolean already, double reward, String message) {
            if (done) return;
            done = true;
            final WebView w = wv; wv = null;
            final HandlerThread theHt = ht;
            if (th != null) th.post(() -> {
                try { if (w != null) { w.loadUrl("about:blank"); w.destroy(); } } catch (Exception ignored) {}
                if (theHt != null) theHt.quitSafely();
            });
            /* 成功（非重复签）→ 写本地徽章（今日已签/置灰/站点徽章立即生效） */
            if (ok && !already && reward > 0) {
                try {
                    Store store = new Store(ctx);
                    JSONObject rec = store.findAccount(accountKey);
                    if (rec != null) {
                        rec.put("lastCheckin", new JSONObject()
                                .put("date", Engine.todayStr())
                                .put("reward", reward)
                                .put("time", System.currentTimeMillis()));
                        store.upsertAccount(store.siteOfAccount(accountKey).optString("key"), rec);
                    }
                } catch (Exception ignored) {}
            }
            final boolean fok = ok; final boolean fal = already; final double frw = reward; final String fmsg = message == null ? "" : message;
            main.post(() -> { try { cb.onResult(fok, fal, frw, fmsg); } catch (Exception ignored) {} });
        }

        /** JS 与 Java 的桥：结果回传 + 续期新 token 落库（解决 15 分钟短时 token） */
        private class Bridge {
            @JavascriptInterface public void onProgress(String m) { /* 纯后台无 UI，忽略 */ }
            @JavascriptInterface public void onResult(String json) {
                main.post(() -> {
                    try {
                        JSONObject r = new JSONObject(json);
                        String newTk = r.optString("token", "");
                        if (newTk != null && newTk.length() > 20) {
                            try {
                                Store store = new Store(ctx);
                                JSONObject rec = store.findAccount(accountKey);
                                if (rec != null) {
                                    rec.put("token", newTk).put("updatedAt", System.currentTimeMillis());
                                    store.upsertAccount(store.siteOfAccount(accountKey).optString("key"), rec);
                                }
                            } catch (Exception ignored) {}
                        }
                        finish(r.optBoolean("ok", false), r.optBoolean("already", false),
                                r.optDouble("reward", 0), r.optString("message", ""));
                    } catch (Exception e) { finish(false, false, 0, "后台签到结果解析失败"); }
                });
            }
        }
    }
}
