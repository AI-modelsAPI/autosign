package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
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
 * CheckinActivity（v0.1.6）— WebView 真实浏览器环境签到：
 *   站点启用了 Cloudflare Turnstile 人机验证（turnstile_check=true），
 *   按用户要求：不伪造验证、不答题，用真实 WebView 无感自动完成（指纹真实）。
 * 流程：
 *   1. 挂 WebView SOCKS5 代理（与 AuthActivity 同款，探测端口可达后再挂，防死代理全断）
 *   2. 加载站点主页建立会话（同源 + 真实 Cookie）
 *   3. 注入 JS：先尝试 POST /api/user/checkin（顺带用 Cookie+sid 续期拿新 token 存回）；
 *      若 message 含 "Turnstile" → 动态挂官方 invisible 挂件自动出 token → 带 token 重试
 *   4. 结果（reward/already/message/newToken）回传 MainActivity，进度实时提示
 */
public class CheckinActivity extends Activity {
    private Handler h;
    private WebView wv;
    private TextView tip;
    private String baseUrl, accountKey, siteKey;
    private volatile boolean fired = false, finished = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        h = new Handler(Looper.getMainLooper());
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        JSONObject site = new Store(this).findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl") : "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) { finishErr("站点不存在"); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // 全部留在本 WebView
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (!fired) { fired = true; runCheckin(); }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = err != null ? String.valueOf(err.getDescription()) : "unknown";
                h.post(() -> finishErr("net::" + d));
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
        sub.setText("人机验证将自动完成，无需任何操作\n完成后自动返回");
        sub.setTextColor(0xFF64748B); sub.setTextSize(13); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 36, 0, 0);
        LinearLayout tw = new LinearLayout(this);
        tw.setOrientation(LinearLayout.VERTICAL);
        tw.setGravity(Gravity.CENTER_HORIZONTAL);
        tw.setPadding(0, 36, 0, 0);
        tw.addView(tip); tw.addView(sub);
        box.addView(tw, new LinearLayout.LayoutParams(-2, -2));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1)); // 顶层遮罩
        setContentView(root);
        /* 90 秒看门狗：人机验证卡死不至于永远挂着 */
        h.postDelayed(() -> { if (!finished) finishErr("签到超时（人机验证未完成），请重试"); }, 90000);
        applyProxy(() -> wv.loadUrl(baseUrl + "/"));
    }

    /* ================= WebView 代理（同 AuthActivity） ================= */
    private void applyProxy(Runnable then) {
        try {
            JSONObject proxy = new Store(this).config().optJSONObject("proxy");
            final boolean enabled = proxy != null && proxy.optBoolean("enabled");
            final String host = proxy != null ? proxy.optString("host", "127.0.0.1") : "127.0.0.1";
            final int port = proxy != null ? proxy.optInt("port", 10808) : 10808;
            new Thread(() -> {
                boolean reachable = false;
                if (enabled) {
                    try { // 探测本地代理端口，避免挂上死代理全断
                        java.net.Socket sk = new java.net.Socket();
                        sk.connect(new java.net.InetSocketAddress(host, port), 1500);
                        reachable = true; sk.close();
                    } catch (Exception ignored) {}
                }
                final boolean use = reachable;
                h.post(() -> {
                    try {
                        if (use && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                            ProxyConfig pc = new ProxyConfig.Builder().addProxyRule("socks5://" + host + ":" + port).build();
                            ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {});
                        }
                    } catch (Exception ignored) {}
                    then.run();
                });
            }).start();
        } catch (Exception e) { then.run(); }
    }

    /* ================= JS 注入签到 ================= */
    private void runCheckin() {
        String tk = token();
        String js = CheckinJs.render(CheckinJs.extractSid(tk), turnstileSiteKey() == null ? "" : turnstileSiteKey(), tk);
        wv.evaluateJavascript(js, null);
    }
    /** 已存 access_token（seed token；过期由 JS 内 Cookie+sid 续期接管） */
    private String token() {
        try {
            JSONObject rec = new Store(this).findAccount(accountKey);
            return rec == null ? "" : rec.optString("token", "");
        } catch (Exception e) { return ""; }
    }

    /** 从已存 JWT payload 解出会话 sid（X-Auth-Session 续期用） */
    private String sessionSid() {
        try {
            JSONObject rec = new Store(this).findAccount(accountKey);
            String tk = rec == null ? "" : rec.optString("token", "");
            String[] p = tk.split("\\.");
            if (p.length < 2) return "";
            String pl = p[1];
            int pad = (4 - pl.length() % 4) % 4;
            for (int i = 0; i < pad; i++) pl += "=";
            byte[] raw = Base64.decode(pl, Base64.URL_SAFE | Base64.NO_WRAP);
            return new JSONObject(new String(raw, "UTF-8")).optString("sid", "");
        } catch (Exception e) { return ""; }
    }

    /** /api/status 的 turnstile_site_key（添加站点时缓存或现场取） */
    private String turnstileSiteKey() {
        try {
            JSONObject rec = new Store(this).findAccount(accountKey);
            if (rec != null && rec.has("turnstileSiteKey")) return rec.optString("turnstileSiteKey", "");
        } catch (Exception ignored) {}
        return "";
    }

    private void finishErr(String msg) {
        if (finished) return;
        finished = true;
        Intent out = new Intent();
        out.putExtra("ok", false);
        out.putExtra("message", msg);
        setResult(RESULT_OK, out);
        finish();
    }

    @Override protected void onDestroy() {
        if (wv != null) {
            wv.loadUrl("about:blank");
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }

    /** JS ↔ Java 桥：进度与最终结果回传 */
    public class Bridge {
        @JavascriptInterface
        public void onProgress(String msg) {
            h.post(() -> { if (tip != null && !finished) tip.setText(msg); });
        }
        @JavascriptInterface
        public void onResult(String json) {
            h.post(() -> {
                if (finished) return;
                finished = true;
                try {
                    JSONObject r = new JSONObject(json);
                    /* 续期到的新 token 存回账号（解决 15 分钟短时 token 过期问题） */
                    String newTk = r.optString("token", "");
                    if (newTk != null && newTk.length() > 20) {
                        try {
                            Store store = new Store(CheckinActivity.this);
                            JSONObject rec = store.findAccount(accountKey);
                            if (rec != null) {
                                rec.put("token", newTk).put("updatedAt", System.currentTimeMillis());
                                store.upsertAccount(siteKey, rec);
                            }
                        } catch (Exception ignored) {}
                    }
                    Intent out = new Intent();
                    out.putExtra("ok", r.optBoolean("ok", false));
                    out.putExtra("already", r.optBoolean("already", false));
                    out.putExtra("reward", r.optDouble("reward", 0));
                    out.putExtra("message", r.optString("message", ""));
                    setResult(RESULT_OK, out);
                    finish();
                } catch (Exception e) { finishErr("结果解析失败"); }
            });
        }
    }

}