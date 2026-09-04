package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AuthActivity — GitHub 授权桥（新版 new-api，v0.1.2 修复）：
 *   1. GET /api/status → github_client_id（按站点动态）
 *   2. POST /api/oauth/state {provider:github,intent:login} → flow_token
 *   3. WebView 打开 github.com/login/oauth/authorize?client_id&state=flow_token&scope=user:email
 *   4. GitHub 回调 站点/oauth/github?code&state —— onPageFinished 检测到站点域 /oauth/ 路径后，
 *      注入脚本直接 GET /api/oauth/github?code&state 拿完整 bundle（access_token+user.username），
 *      通过 @JavascriptInterface 回传落盘 → 关窗。
 *   （新版前端 token 只存内存，靠 /api/user/auth/refresh 刷；localStorage['new-api:auth-session']
 *     只是跨页签广播事件，轮询它永远拿不到 token —— 这是 v0.1.1 授权失败的根因。）
 */
public class AuthActivity extends Activity {
    private WebView wv;
    private LinearLayout boot;
    private TextView bootText;
    private TextView tip;
    private FrameLayout root;
    private LinearLayout errorLayer;
    private TextView errMsg;
    private String siteKey, accountKey, alias, baseUrl, siteHost;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean done = false;
    private boolean proxyApplied = false;

    /** JS ↔ Java 桥：回调页注入脚本通过它回传交换结果 */
    public class Bridge {
        @JavascriptInterface public void onSession(String json) {
            if (done) return;
            try {
                JSONObject resp = new JSONObject(json);
                boolean ok = resp.optBoolean("success");
                JSONObject d = resp.optJSONObject("data");
                if (ok && d != null) {
                    String token = d.optString("access_token", d.optString("accessToken", null));
                    if (token != null && !token.isEmpty()) { finishOk(d, token); return; }
                }
                String msg = resp.optString("message", "交换失败");
                showTip("授权交换失败: " + msg);
            } catch (Exception ignored) {}
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        alias = getIntent().getStringExtra("alias");
        JSONObject site = new Store(this).findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl") : "https://api.justwoker.icu").replaceAll("/+$", "");
        try { siteHost = new java.net.URL(baseUrl).getHost(); } catch (Exception e) { siteHost = ""; }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        /* ---- WebView ---- */
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // GitHub ↔ 站点 全部留在本 WebView
            }
            @Override public void onPageFinished(WebView v, String url) {
                maybeExchange(v, url);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return; // 子资源失败不提示
                String d = err != null ? String.valueOf(err.getDescription()) : "unknown";
                h.post(() -> showLoadError("net::" + d));
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse rsp) {
                if (req == null || !req.isForMainFrame()) return;
                int code = rsp != null ? rsp.getStatusCode() : 0;
                h.post(() -> showLoadError("HTTP " + code));
            }
        });
        wv.setVisibility(View.GONE);
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        /* ---- 启动层：获取 state 中 ---- */
        boot = new LinearLayout(this);
        boot.setOrientation(LinearLayout.VERTICAL);
        boot.setGravity(Gravity.CENTER);
        boot.setBackgroundColor(Color.WHITE);
        ProgressBar pb = new ProgressBar(this);
        TextView title = new TextView(this);
        title.setText("正在准备 GitHub 授权…");
        title.setTextColor(0xFF0F172A); title.setTextSize(16); title.setTypeface(Typeface.DEFAULT_BOLD);
        bootText = new TextView(this);
        bootText.setText("与站点建立安全会话");
        bootText.setTextColor(0xFF64748B); bootText.setTextSize(13);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.addView(pb, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout pt = new LinearLayout(this);
        pt.setOrientation(LinearLayout.VERTICAL);
        pt.setGravity(Gravity.CENTER_HORIZONTAL);
        pt.setPadding(0, 36, 0, 0);
        pt.addView(title); pt.addView(bootText);
        wrap.addView(pt);
        boot.addView(wrap, new LinearLayout.LayoutParams(-2, -2));
        root.addView(boot, new FrameLayout.LayoutParams(-1, -1));

        /* ---- 失败重试层（v0.1.5）：GitHub 页加载失败时给出明确错误与重试入口 ---- */
        errorLayer = new LinearLayout(this);
        errorLayer.setOrientation(LinearLayout.VERTICAL);
        errorLayer.setGravity(Gravity.CENTER);
        errorLayer.setBackgroundColor(Color.WHITE);
        errorLayer.setVisibility(View.GONE);
        TextView errTitle = new TextView(this);
        errTitle.setText("页面加载失败");
        errTitle.setTextColor(0xFF0F172A); errTitle.setTextSize(16); errTitle.setTypeface(Typeface.DEFAULT_BOLD);
        errTitle.setGravity(Gravity.CENTER);
        errMsg = new TextView(this);
        errMsg.setTextColor(0xFFDC2626); errMsg.setTextSize(13); errMsg.setGravity(Gravity.CENTER);
        errMsg.setPadding(60, 24, 60, 0);
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextColor(Color.WHITE); retry.setTextSize(15); retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(80, 26, 80, 26);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xFF2563EB); rbg.setCornerRadius(30);
        retry.setBackground(rbg);
        retry.setOnClickListener(v -> { if (errorLayer != null) errorLayer.setVisibility(View.GONE); startAuthFlow(); });
        TextView backTip = new TextView(this);
        backTip.setText("返回键退出");
        backTip.setTextColor(0xFF64748B); backTip.setTextSize(12);
        backTip.setPadding(0, 30, 0, 0);
        LinearLayout ew = new LinearLayout(this);
        ew.setOrientation(LinearLayout.VERTICAL);
        ew.setGravity(Gravity.CENTER_HORIZONTAL);
        ew.addView(errTitle); ew.addView(errMsg);
        ew.addView(retry, new LinearLayout.LayoutParams(-2, -2)); ((LinearLayout.LayoutParams) retry.getLayoutParams()).topMargin = 46;
        ew.addView(backTip);
        errorLayer.addView(ew, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(errorLayer, new FrameLayout.LayoutParams(-1, -1));

        /* ---- 底部提示条（放下方，不遮挡站点页面内容） ---- */
        tip = new TextView(this);
        tip.setText("  GitHub 授权中 · 已登录 GitHub 将自动完成，成功后本窗口自动关闭  ");
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(12);
        tip.setBackgroundColor(0xE6111827);
        tip.setPadding(20, 24, 20, 24);
        root.addView(tip, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        startAuthFlow();
    }

    private void showTip(String text) {
        h.post(() -> { if (tip != null) tip.setText("  " + text + "  "); });
    }

    /** WebView 主帧加载失败：展示明确错误 + 重试按钮（v0.1.5） */
    private void showLoadError(String detail) {
        if (done) return;
        h.post(() -> {
            if (boot != null) boot.setVisibility(View.GONE);
            if (wv != null) wv.setVisibility(View.GONE);
            if (errMsg != null) errMsg.setText(detail + "\n\n常见原因：本地代理（127.0.0.1:10808）未开启，\n或代理未放行本应用。请开启后点重试。");
            if (errorLayer != null) errorLayer.setVisibility(View.VISIBLE);
            showTip("GitHub 页面加载失败 · 请检查代理后重试");
        });
    }

    /** WebView 挂本地 SOCKS 代理（系统 WebView 不吃 OkHttp 代理；github.com 直连会被墙断连 ERR_CONNECTION_ABORTED） */
    private void applyProxy() {
        if (proxyApplied) return;
        proxyApplied = true;
        try {
            JSONObject proxy = new Store(this).config().optJSONObject("proxy");
            final boolean enabled = proxy != null && proxy.optBoolean("enabled");
            final String host = proxy != null ? proxy.optString("host", "127.0.0.1") : "127.0.0.1";
            final int port = proxy != null ? proxy.optInt("port", 10808) : 10808;
            new Thread(() -> {
                boolean reachable = false;
                if (enabled) {
                    try { // 探测本地代理端口，避免挂上死代理全断
                        java.net.Socket s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress(host, port), 1500);
                        reachable = true; s.close();
                    } catch (Exception ignored) {}
                }
                final boolean use = reachable;
                h.post(() -> {
                    try {
                        if (use && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                            ProxyConfig pc = new ProxyConfig.Builder().addProxyRule("socks5://" + host + ":" + port).build();
                            ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {});
                            showTip("  已挂载本地代理 " + host + ":" + port + " · GitHub 授权中  ");
                        } else if (!use) {
                            showTip("  未检测到本地代理 · 若加载失败请开启 VPN 后重试  ");
                        }
                    } catch (Exception ignored) {}
                });
            }).start();
        } catch (Exception ignored) {}
    }

    /** 加载 GitHub authorize 页（先挂代理再加载） */
    private void loadAuthUrl(String clientId, String state) {
        applyProxy();
        String authUrl = "https://github.com/login/oauth/authorize?client_id=" + urlEncode(clientId)
                + "&state=" + urlEncode(state) + "&scope=user:email";
        wv.loadUrl(authUrl);
    }

    /** 第一步：拿 clientId + flow_token；第二步：打开官方 authorize URL */
    private void startAuthFlow() {
        new Thread(() -> {
            String state = null, err = null;
            try {
                JSONObject proxy = new Store(this).config().optJSONObject("proxy");
                OkHttpClient base = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
                OkHttpClient c = withProxy(base, proxy);

                // 1) 站点 clientId（失败不阻塞，用空串走通用流）
                String clientId = "";
                try {
                    Response rs = c.newCall(new Request.Builder().url(baseUrl + "/api/status")
                            .header("User-Agent", "Mozilla/5.0").build()).execute();
                    String sb = rs.body() != null ? rs.body().string() : "";
                    if (rs.code() == 200) {
                        JSONObject st = new JSONObject(sb).optJSONObject("data");
                        if (st != null) clientId = st.optString("github_client_id", "");
                    }
                } catch (Exception ignored) {}

                // 2) flow_token
                Response resp = c.newCall(new Request.Builder().url(baseUrl + "/api/oauth/state")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                        .post(RequestBody.create(
                                "{\"provider\":\"github\",\"intent\":\"login\"}".getBytes(),
                                MediaType.parse("application/json"))).build()).execute();
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject j = new JSONObject(body);
                if (resp.code() == 200 && j.optBoolean("success")) {
                    Object d = j.opt("data");
                    if (d instanceof String) state = (String) d;
                    else if (d instanceof JSONObject) state = ((JSONObject) d).optString("flow_token", null);
                }
                if (state == null || state.isEmpty()) err = j.optString("message", "state 获取失败 http=" + resp.code());

                final String cid = clientId, st2 = state, er = err;
                h.post(() -> {
                    if (done || isFinishing()) return;
                    if (st2 == null) {
                        showLoadError("state 获取失败: " + er + "（多为代理未通或站点不可达）");
                        return;
                    }
                    boot.setVisibility(View.GONE);
                    wv.setVisibility(View.VISIBLE);
                    AlphaAnimation a = new AlphaAnimation(0f, 1f);
                    a.setDuration(220);
                    wv.startAnimation(a);
                    loadAuthUrl(cid, st2);
                });
            } catch (Exception e) {
                final String er = "state 请求异常: " + e.getMessage();
                h.post(() -> { if (!isFinishing()) showLoadError(er); });
            }
        }).start();
    }

    private static OkHttpClient withProxy(OkHttpClient base, JSONObject proxy) {
        if (proxy != null && proxy.optBoolean("enabled")) {
            try {
                java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress(proxy.optString("host", "127.0.0.1"),
                                proxy.optInt("port", 10808)));
                return base.newBuilder().proxy(p).build();
            } catch (Exception ignored) {}
        }
        return base;
    }

    /** GitHub 回调落地站点域 /oauth/ 路径时，注入脚本主动交换拿 bundle */
    private void maybeExchange(WebView v, String url) {
        if (done || url == null) return;
        try {
            java.net.URL u = new java.net.URL(url);
            if (!u.getHost().equalsIgnoreCase(siteHost)) return;
            String path = u.getPath();
            if (path == null || !path.startsWith("/oauth/")) return;
            String provider = path.substring("/oauth/".length());
            if (provider.contains("/")) provider = provider.substring(0, provider.indexOf('/'));
            if (provider.isEmpty()) return;

            final String js =
                "(async()=>{try{" +
                "const p=new URLSearchParams(location.search);" +
                "const code=p.get('code'),state=p.get('state');" +
                "if(!code){window.JustSign.onSession(JSON.stringify({success:false,message:'回调缺少 code'}));return;}" +
                "const r=await fetch('/api/oauth/" + provider + "?code='+encodeURIComponent(code)+'&state='+encodeURIComponent(state||''),{headers:{'Accept':'application/json'},credentials:'include'});" +
                "const j=await r.json();" +
                "window.JustSign.onSession(JSON.stringify(j));" +
                "}catch(e){window.JustSign.onSession(JSON.stringify({success:false,message:String(e)}));}})()";
            h.postDelayed(() -> { if (!done) v.evaluateJavascript(js, null); }, 400);
        } catch (Exception ignored) {}
    }

    private static String urlEncode(String s2) {
        try { return URLEncoder.encode(s2 == null ? "" : s2, "UTF-8"); } catch (Exception e) { return s2; }
    }

    /** 新版: user.username；老版: user.login */
    private void finishOk(JSONObject bundle, String token) {
        done = true;
        Store store = new Store(this);
        String login = bundle.optJSONObject("user") == null ? null
                : bundle.optJSONObject("user").optString("username",
                    bundle.optJSONObject("user").optString("login", null));
        try {
            JSONObject rec = store.findAccount(accountKey);
            if (rec == null) rec = new JSONObject().put("key", accountKey);
            rec.put("siteKey", siteKey);
            if (alias != null && !alias.isEmpty()) rec.put("alias", alias);
            if (login != null && !login.isEmpty()) rec.put("githubAccount", login);
            rec.put("token", token);
            rec.put("updatedAt", System.currentTimeMillis());
            store.upsertAccount(siteKey, rec);
            /* GitHub 用户名全局持久化（v0.1.5）：添加其他站点账号时自动预填，免重复手输 */
            if (login != null && !login.isEmpty()) {
                try {
                    JSONObject cfg = store.config();
                    cfg.put("lastGithubUser", login);
                    store.saveConfig(cfg);
                } catch (Exception ignored) {}
            }
            store.appendLog(siteKey, accountKey, "auth", "via=android user=" + (login == null ? "?" : login));

            Intent out = new Intent();
            out.putExtra("ok", true);
            out.putExtra("user", login == null ? "" : login);
            setResult(RESULT_OK, out);
        } catch (Exception e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "保存失败: " + e.getMessage()));
        }
        finish();
    }

    @Override public void onBackPressed() {
        done = true;
        setResult(RESULT_CANCELED, new Intent().putExtra("error", "授权已取消"));
        finish();
    }

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
        if (wv != null) wv.destroy();
        super.onDestroy();
    }
}