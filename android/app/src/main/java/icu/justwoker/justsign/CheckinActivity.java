package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

/**
 * CheckinActivity（v0.1.8）— WebView 真实浏览器环境签到（后台失败时的可见兜底）：
 *   站点启用了 Cloudflare Turnstile 人机验证（turnstile_check=true），
 *   按用户要求：不伪造验证、不答题，用真实 WebView 无感自动完成（指纹真实）。
 * 流程：
 *   1. 挂 WebView SOCKS5 代理（探测端口可达后再挂，挂完才加载 —— v0.1.8 修复竞态）
 *   2. 加载站点主页建立会话（同源 + 真实 Cookie）
 *   3. 注入 CheckinJs：续期 → 签到 →（需验证时）官方 invisible Turnstile → 带 token 重试
 *   4. 结果（reward/already/message/newToken/siteKey）回传 MainActivity
 *
 * v0.1.8 修复：
 *   - onPageFinished 过滤 about:blank 并单次触发；补 onReceivedHttpError（主帧 4xx/5xx 也算失败）
 *   - siteKey 三级回退（站点 meta → 账号旧字段 → JS 现场 /api/status），并把结果落库站点 meta
 *   - token/siteKey 落库改 patchAccount（锁内合并，不覆盖并发写入）
 *   - 结果回传前先 removeCallbacks 看门狗，避免超时与成功双触发
 *   - onDestroy 里 WebView 清理顺序修正（stopLoading → about:blank → remove 接口 → destroy）
 *   - 删除未使用的 sessionSid()（重复实现，统一走 CheckinJs.extractSid）
 */
public class CheckinActivity extends Activity {
    private Handler h;
    private WebView wv;
    private TextView tip;
    private LinearLayout maskBox;          // 顶层白色遮罩（人机验证需要交互时移除）
    private String baseUrl, accountKey, siteKey;
    private volatile boolean fired = false, finished = false;
    private volatile boolean unmasked = false;
    private Runnable watchdog;

    /* 账号快照：注入前读一次 */
    private String snapToken = "", snapSiteKey = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        h = new Handler(Looper.getMainLooper());
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        if (siteKey == null || accountKey == null) { finishErr("参数缺失"); return; }

        Store store = new Store(this);
        JSONObject site = store.findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl", "") : "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) { finishErr("站点不存在"); return; }

        JSONObject acc = store.findAccount(accountKey);
        snapToken = acc == null ? "" : acc.optString("token", "");
        String accLevel = acc == null ? "" : acc.optString("turnstileSiteKey", "");
        String siteLevel = store.siteMeta(siteKey, "turnstileSiteKey", "");
        snapSiteKey = !siteLevel.isEmpty() ? siteLevel : accLevel;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // 全部留在本 WebView
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (finished || fired) return;
                if (url == null || url.startsWith("about:")) return;
                fired = true;
                runCheckin();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = err != null ? String.valueOf(err.getDescription()) : "unknown";
                h.post(() -> finishErr("net::" + d));
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req,
                                                      android.webkit.WebResourceResponse rsp) {
                if (req == null || !req.isForMainFrame()) return;
                int code = rsp != null ? rsp.getStatusCode() : 0;
                if (code >= 400) h.post(() -> finishErr("站点返回 HTTP " + code));
            }
        });
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1)); // 底层：真实渲染，人机验证需要

        /* 顶层进度遮罩（白色不透明盖住 WebView，但 WebView 仍真实运行） */
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        box.addView(new ProgressBar(this), new LinearLayout.LayoutParams(-2, -2));
        tip = new TextView(this);
        tip.setText("准备签到环境…");
        tip.setTextColor(0xFF0F172A); tip.setTextSize(15); tip.setTypeface(Typeface.DEFAULT_BOLD);
        tip.setGravity(Gravity.CENTER);
        TextView sub = new TextView(this);
        sub.setText("人机验证将自动完成，无需任何操作\n若需要手动确认会自动显示验证框");
        sub.setTextColor(0xFF64748B); sub.setTextSize(13); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 36, 0, 0);
        LinearLayout tw = new LinearLayout(this);
        tw.setOrientation(LinearLayout.VERTICAL);
        tw.setGravity(Gravity.CENTER_HORIZONTAL);
        tw.setPadding(0, 36, 0, 0);
        tw.addView(tip); tw.addView(sub);
        box.addView(tw, new LinearLayout.LayoutParams(-2, -2));
        maskBox = box;
        root.addView(box, new FrameLayout.LayoutParams(-1, -1)); // 顶层遮罩
        setContentView(root);

        /* 100 秒看门狗：人机验证卡死不至于永远挂着 */
        watchdog = () -> { if (!finished) finishErr("签到超时（人机验证未完成），请重试"); };
        h.postDelayed(watchdog, 100000);
        applyProxyThen(() -> { if (!finished && wv != null) wv.loadUrl(baseUrl + "/"); });
    }

    /* ================= WebView 代理（挂载完成后才回调 then） ================= */
    private void applyProxyThen(Runnable then) {
        JSONObject proxy;
        try { proxy = new Store(this).config().optJSONObject("proxy"); } catch (Exception e) { proxy = null; }
        final boolean enabled = proxy != null && proxy.optBoolean("enabled");
        if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { then.run(); return; }
        final String host = proxy.optString("host", "127.0.0.1");
        final int port = proxy.optInt("port", 10808);
        new Thread(() -> {
            boolean reachable = false;
            try { // 探测本地代理端口，避免挂上死代理全断
                java.net.Socket sk = new java.net.Socket();
                sk.connect(new java.net.InetSocketAddress(host, port), 1500);
                reachable = true; sk.close();
            } catch (Exception ignored) {}
            final boolean use = reachable;
            h.post(() -> {
                if (finished) return;
                if (!use) { then.run(); return; }
                try {
                    ProxyConfig pc = new ProxyConfig.Builder()
                            .addProxyRule("socks5://" + host + ":" + port)
                            .addDirect()
                            .build();
                    ProxyController.getInstance().setProxyOverride(pc, Runnable::run, then);
                } catch (Exception e) { then.run(); }
            });
        }, "checkin-proxy").start();
    }

    /* ================= JS 注入签到 ================= */
    private void runCheckin() {
        if (wv == null || finished) return;
        /* 注入前把库里最新 token 读一次（SilentAuth 可能刚在后台换过） */
        try {
            JSONObject acc = new Store(this).findAccount(accountKey);
            String latest = acc == null ? "" : acc.optString("token", "");
            if (latest.length() > 20) snapToken = latest;
        } catch (Exception ignored) {}
        String js = CheckinJs.render(CheckinJs.extractSid(snapToken), snapSiteKey, snapToken);
        try { wv.evaluateJavascript(js, null); }
        catch (Exception e) { finishErr("注入失败: " + e.getMessage()); }
    }

    private void finishErr(String msg) {
        if (finished) return;
        finished = true;
        if (h != null && watchdog != null) h.removeCallbacks(watchdog);
        Intent out = new Intent();
        out.putExtra("ok", false);
        out.putExtra("message", msg == null ? "签到失败" : msg);
        out.putExtra("accountKey", accountKey);
        out.putExtra("siteKey", siteKey);
        setResult(RESULT_OK, out);
        finish();
    }

    @Override protected void onDestroy() {
        finished = true;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.removeJavascriptInterface("JustSign");
                wv.destroy();
            } catch (Exception ignored) {}
            wv = null;
        }
        super.onDestroy();
    }

    /** JS ↔ Java 桥：进度与最终结果回传 */
    public class Bridge {
        @JavascriptInterface
        public void onProgress(String msg) {
            if (msg == null) return;
            /* 人机验证可能需要手动点一下 → 掀开白色遮罩，把 Turnstile 交给用户 */
            if (msg.contains(CheckinJs.SIGNAL_NEED_UI)) {
                h.post(CheckinActivity.this::unmask);
                return;
            }
            h.post(() -> { if (tip != null && !finished) tip.setText(msg); });
        }

        @JavascriptInterface
        public void onResult(String json) {
            h.post(() -> {
                if (finished) return;
                finished = true;
                if (watchdog != null) h.removeCallbacks(watchdog);
                try {
                    JSONObject r = new JSONObject(json);
                    Store store = new Store(CheckinActivity.this);

                    /* 续期到的新 token 存回账号（解决 15 分钟短时 token 过期问题） */
                    String newTk = r.optString("token", "");
                    if (newTk.length() > 20 && !newTk.equals(snapToken)) {
                        try {
                            store.patchAccount(accountKey, new JSONObject()
                                    .put("token", newTk)
                                    .put("updatedAt", System.currentTimeMillis()));
                        } catch (Exception ignored) {}
                    }
                    /* JS 现场取到的 turnstile siteKey / quota 单位 → 落库站点 meta（站点级共享） */
                    String sk = r.optString("siteKey", "");
                    if (!sk.isEmpty()) store.putSiteMeta(siteKey, "turnstileSiteKey", sk);
                    long unit = r.optLong("unit", 0);
                    if (unit > 0) store.putSiteMeta(siteKey, "quotaPerUnit", unit);

                    boolean ok = r.optBoolean("ok", false);
                    double reward = r.optDouble("reward", 0);
                    boolean rewardKnown = r.optBoolean("rewardKnown", false);
                    /* 成功（含今日已签）→ 写当日徽章；奖励未知时不写 reward，避免显示成 $0 */
                    if (ok) {
                        try {
                            JSONObject lc = new JSONObject()
                                    .put("date", Engine.todayStr())
                                    .put("time", System.currentTimeMillis());
                            if (rewardKnown) lc.put("reward", reward);
                            store.patchAccount(accountKey, new JSONObject().put("lastCheckin", lc));
                        } catch (Exception ignored) {}
                    }

                    String msg = r.optString("message", "");
                    String detail = r.optString("detail", "");
                    String tsErr = r.optString("tsError", "");
                    if (!ok) {
                        if (!detail.isEmpty()) msg = msg + " · " + detail;
                        else if (!tsErr.isEmpty()) msg = msg + " · " + tsErr;
                    }

                    Intent out = new Intent();
                    out.putExtra("ok", ok);
                    out.putExtra("already", r.optBoolean("already", false));
                    out.putExtra("reward", reward);
                    out.putExtra("rewardKnown", rewardKnown);
                    out.putExtra("auth", r.optBoolean("auth", false));
                    out.putExtra("message", msg);
                    out.putExtra("accountKey", accountKey);
                    out.putExtra("siteKey", siteKey);
                    setResult(RESULT_OK, out);
                    finish();
                } catch (Exception e) {
                    finished = false;           // 允许 finishErr 正常走完
                    finishErr("结果解析失败");
                }
            });
        }
    }

    /** 掀开遮罩，让用户看到并点击 Turnstile 挂件 */
    private void unmask() {
        if (unmasked || finished) return;
        unmasked = true;
        if (maskBox != null) maskBox.setVisibility(android.view.View.GONE);
        /* 需要人工点一下时给足时间：看门狗延长到 3 分钟 */
        if (h != null && watchdog != null) {
            h.removeCallbacks(watchdog);
            h.postDelayed(watchdog, 180000);
        }
    }
}