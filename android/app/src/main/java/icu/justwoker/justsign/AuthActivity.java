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
 * AuthActivity — GitHub 授权桥（新版 new-api）：
 *   1. GET /api/status → github_client_id（按站点动态）
 *   2. POST /api/oauth/state {provider:github,intent:login} → flow_token
 *   3. WebView 打开 github.com/login/oauth/authorize?client_id&state=flow_token&scope=user:email
 *   4. GitHub 回调 站点/oauth/github?code&state —— onPageFinished 检测到站点域 /oauth/ 路径后，
 *      注入脚本直接 GET /api/oauth/github?code&state 拿完整 bundle（access_token+user.username），
 *      通过 @JavascriptInterface 回传落盘 → 关窗。
 *
 * v0.1.8 修复（全量审计）：
 *   1. state 请求失败不再只报 "End of ..."：区分 HTTP 429 限流 / 401 / 5xx / 网络异常，
 *      并对空响应体做保护（原来 new JSONObject("") 直接抛 "End of input at character 0"，
 *      正是你截图里那条报错的真实来源 —— 站点在限流我们，body 是空的）。
 *   2. 429 自动退避重试（读 Retry-After，最多等 8s ×2 次），仍失败给出人话提示。
 *   3. state 请求也走「代理探活 → 挂代理」，与 WebView 一致；探活失败直连（不再默认必挂代理）。
 *   4. proxyApplied 竞态修复：applyProxy 挂载完成后才 loadUrl（原来不等挂载完成）。
 *   5. Response 显式 close，避免连接泄漏。
 *   6. 授权成功后立刻拉一次 /api/status 缓存站点 turnstileSiteKey（新建账号即可直接后台签到，
 *      不必先手动点刷新 —— 这是「新账号签到报未配置 siteKey」的第二道保险）。
 *   7. onBackPressed / onDestroy 与 done 标志统一，回调不再在 Activity 销毁后写 UI。
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
    private volatile boolean proxyApplied = false;

    /** JS ↔ Java 桥：回调页注入脚本通过它回传交换结果 */
    public class Bridge {
        @JavascriptInterface public void onSession(String json) {
            if (done) return;
            h.post(() -> {
                if (done) return;
                try {
                    JSONObject resp = new JSONObject(json);
                    boolean ok = resp.optBoolean("success");
                    JSONObject d = resp.optJSONObject("data");
                    if (ok && d != null) {
                        String token = d.optString("access_token", d.optString("accessToken", ""));
                        if (!token.isEmpty()) { finishOk(d, token); return; }
                    }
                    String msg = resp.optString("message", "交换失败");
                    showTip("授权交换失败: " + msg);
                } catch (Exception e) {
                    showTip("授权交换失败: 响应解析异常");
                }
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        alias = getIntent().getStringExtra("alias");
        JSONObject site = new Store(this).findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl", "") : "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "站点不存在或 baseUrl 为空"));
            finish();
            return;
        }
        try { siteHost = new java.net.URL(baseUrl).getHost(); } catch (Exception e) { siteHost = ""; }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        /* ---- WebView ---- */
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
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
                if (code >= 400) h.post(() -> showLoadError("HTTP " + code));
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

        /* ---- 失败重试层：GitHub 页加载失败时给出明确错误与重试入口 ---- */
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
        retry.setOnClickListener(v -> {
            if (errorLayer != null) errorLayer.setVisibility(View.GONE);
            if (boot != null) boot.setVisibility(View.VISIBLE);
            startAuthFlow();
        });
        TextView backTip = new TextView(this);
        backTip.setText("返回键退出");
        backTip.setTextColor(0xFF64748B); backTip.setTextSize(12);
        backTip.setPadding(0, 30, 0, 0);
        LinearLayout ew = new LinearLayout(this);
        ew.setOrientation(LinearLayout.VERTICAL);
        ew.setGravity(Gravity.CENTER_HORIZONTAL);
        ew.addView(errTitle); ew.addView(errMsg);
        ew.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) retry.getLayoutParams()).topMargin = 46;
        ew.addView(backTip);
        errorLayer.addView(ew, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(errorLayer, new FrameLayout.LayoutParams(-1, -1));

        /* ---- 底部提示条 ---- */
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

    /** WebView 主帧加载失败 / 前置请求失败：展示明确错误 + 重试按钮 */
    private void showLoadError(String detail) {
        if (done || isFinishing()) return;
        h.post(() -> {
            if (done || isFinishing()) return;
            if (boot != null) boot.setVisibility(View.GONE);
            if (wv != null) wv.setVisibility(View.GONE);
            if (errMsg != null) errMsg.setText(detail
                    + "\n\n常见原因：\n· 站点限流（429），稍等几分钟再试\n· 本地代理未开启或未放行本应用\n· GitHub 直连被阻断（需开 VPN）");
            if (errorLayer != null) errorLayer.setVisibility(View.VISIBLE);
            showTip("授权准备失败 · 可点重试");
        });
    }

    /** WebView 挂本地 SOCKS 代理；挂载完成（或跳过）后回调 then */
    private void applyProxyThen(Runnable then) {
        if (proxyApplied) { then.run(); return; }
        JSONObject proxy;
        try { proxy = new Store(this).config().optJSONObject("proxy"); } catch (Exception e) { proxy = null; }
        final boolean enabled = proxy != null && proxy.optBoolean("enabled");
        if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyApplied = true;
            showTip("未启用代理 · 若 GitHub 打不开请到设置里配代理");
            then.run();
            return;
        }
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
                if (done || isFinishing()) return;
                proxyApplied = true;
                if (!use) {
                    showTip("未检测到本地代理 " + host + ":" + port + " · 若加载失败请开 VPN 后重试");
                    then.run();
                    return;
                }
                try {
                    ProxyConfig pc = new ProxyConfig.Builder()
                            .addProxyRule("socks5://" + host + ":" + port)
                            .addDirect()
                            .build();
                    ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {
                        showTip("已挂载本地代理 " + host + ":" + port + " · GitHub 授权中");
                        then.run();
                    });
                } catch (Exception e) { then.run(); }
            });
        }, "auth-proxy").start();
    }

    /** 加载 GitHub authorize 页 */
    private void loadAuthUrl(String clientId, String state) {
        String authUrl = "https://github.com/login/oauth/authorize?client_id=" + urlEncode(clientId)
                + "&state=" + urlEncode(state) + "&scope=user:email";
        if (wv != null) wv.loadUrl(authUrl);
    }

    /** 第一步：拿 clientId + flow_token；第二步：打开官方 authorize URL */
    private void startAuthFlow() {
        /* 先把 WebView 代理挂好，再跑 OkHttp 请求（两者独立，但顺序统一便于提示） */
        applyProxyThen(() -> new Thread(this::authFlowNetwork, "auth-flow").start());
    }

    private void authFlowNetwork() {
        String state = null, err = null, clientId = "";
        try {
            JSONObject proxy = new Store(this).config().optJSONObject("proxy");
            OkHttpClient base = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
            OkHttpClient c = withProxy(base, proxy);

            /* 1) 站点 clientId + 顺手缓存站点 turnstileSiteKey（失败不阻塞） */
            try {
                JSONObject st = getJson(c, baseUrl + "/api/status");
                JSONObject d = st == null ? null : st.optJSONObject("data");
                if (d != null) {
                    clientId = d.optString("github_client_id", "");
                    String tsk = d.optString("turnstile_site_key", "");
                    if (!tsk.isEmpty() && siteKey != null)
                        new Store(this).putSiteMeta(siteKey, "turnstileSiteKey", tsk);
                    long unit = d.optLong("quota_per_unit", 0);
                    if (unit > 0 && siteKey != null)
                        new Store(this).putSiteMeta(siteKey, "quotaPerUnit", unit);
                }
            } catch (Exception ignored) {}

            /* 2) flow_token（429 退避重试 2 次） */
            for (int attempt = 0; attempt < 3 && state == null; attempt++) {
                Response resp = null;
                try {
                    resp = c.newCall(new Request.Builder().url(baseUrl + "/api/oauth/state")
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                            .header("Accept", "application/json")
                            .post(RequestBody.create(
                                    "{\"provider\":\"github\",\"intent\":\"login\"}",
                                    MediaType.parse("application/json"))).build()).execute();
                    int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (code == 429) {
                        String ra = resp.header("Retry-After", "");
                        err = "站点限流（429）" + (ra.isEmpty() ? "" : "，服务端建议等待 " + ra + " 秒");
                        if (attempt < 2) { try { Thread.sleep(4000L * (attempt + 1)); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    if (body.trim().isEmpty()) {
                        err = "站点返回空响应（HTTP " + code + "）";
                        if (attempt < 2) { try { Thread.sleep(2000L); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    JSONObject j;
                    try { j = new JSONObject(body); }
                    catch (Exception pe) { err = "站点响应非 JSON（HTTP " + code + "）"; break; }
                    if (code == 200 && j.optBoolean("success")) {
                        Object d = j.opt("data");
                        if (d instanceof String) state = (String) d;
                        else if (d instanceof JSONObject) state = ((JSONObject) d).optString("flow_token", null);
                    }
                    if (state == null || state.isEmpty()) {
                        state = null;
                        err = j.optString("message", "");
                        if (err.isEmpty()) err = "state 获取失败（HTTP " + code + "）";
                        break;
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
                    if (attempt < 2) { try { Thread.sleep(1500L); } catch (Exception ignored) {} }
                } finally {
                    if (resp != null) try { resp.close(); } catch (Exception ignored) {}
                }
            }

            final String cid = clientId, st2 = state, er = err;
            h.post(() -> {
                if (done || isFinishing()) return;
                if (st2 == null) { showLoadError("获取授权会话失败: " + (er == null ? "未知原因" : er)); return; }
                boot.setVisibility(View.GONE);
                wv.setVisibility(View.VISIBLE);
                AlphaAnimation a = new AlphaAnimation(0f, 1f);
                a.setDuration(220);
                wv.startAnimation(a);
                loadAuthUrl(cid, st2);
            });
        } catch (Throwable t) {
            final String er = "授权准备异常: " + t.getMessage();
            h.post(() -> { if (!isFinishing()) showLoadError(er); });
        }
    }

    /** GET JSON（自动 close，失败返回 null） */
    private static JSONObject getJson(OkHttpClient c, String url) {
        Response rs = null;
        try {
            rs = c.newCall(new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json").build()).execute();
            if (rs.code() != 200) return null;
            String body = rs.body() != null ? rs.body().string() : "";
            if (body.trim().isEmpty()) return null;
            return new JSONObject(body);
        } catch (Exception e) { return null; }
        finally { if (rs != null) try { rs.close(); } catch (Exception ignored) {} }
    }

    private static OkHttpClient withProxy(OkHttpClient base, JSONObject proxy) {
        if (proxy != null && proxy.optBoolean("enabled")) {
            try {
                final String host = proxy.optString("host", "127.0.0.1");
                final int port = proxy.optInt("port", 10808);
                /* 代理端口不可达就直连，避免整条链路挂死 */
                java.net.Socket sk = new java.net.Socket();
                sk.connect(new java.net.InetSocketAddress(host, port), 1200);
                sk.close();
                java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress(host, port));
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
            if (siteHost == null || siteHost.isEmpty()) return;
            if (!u.getHost().equalsIgnoreCase(siteHost)) return;
            String path = u.getPath();
            if (path == null || !path.startsWith("/oauth/")) return;
            String provider = path.substring("/oauth/".length());
            if (provider.contains("/")) provider = provider.substring(0, provider.indexOf('/'));
            if (provider.isEmpty()) return;
            /* provider 来自站点自身回调路径，仍做白名单校验防注入 */
            if (!provider.matches("[a-zA-Z0-9_-]{1,32}")) return;

            final String js =
                "(async()=>{try{" +
                "const p=new URLSearchParams(location.search);" +
                "const code=p.get('code'),state=p.get('state');" +
                "if(!code){window.JustSign.onSession(JSON.stringify({success:false,message:'回调缺少 code'}));return;}" +
                "const r=await fetch('/api/oauth/" + provider + "?code='+encodeURIComponent(code)+'&state='+encodeURIComponent(state||''),{headers:{'Accept':'application/json'},credentials:'include'});" +
                "let j=null;try{j=await r.json()}catch(e){}" +
                "if(!j){window.JustSign.onSession(JSON.stringify({success:false,message:'交换响应异常 HTTP '+r.status}));return;}" +
                "window.JustSign.onSession(JSON.stringify(j));" +
                "}catch(e){window.JustSign.onSession(JSON.stringify({success:false,message:String(e)}));}})()";
            h.postDelayed(() -> { if (!done && wv != null) wv.evaluateJavascript(js, null); }, 400);
        } catch (Exception ignored) {}
    }

    private static String urlEncode(String s2) {
        try { return URLEncoder.encode(s2 == null ? "" : s2, "UTF-8"); } catch (Exception e) { return s2 == null ? "" : s2; }
    }

    /** 新版: user.username；老版: user.login */
    private void finishOk(JSONObject bundle, String token) {
        if (done) return;
        done = true;
        Store store = new Store(this);
        JSONObject userObj = bundle.optJSONObject("user");
        String login = null;
        if (userObj != null) {
            login = userObj.optString("username", "");
            if (login.isEmpty()) login = userObj.optString("login", "");
            if (login.isEmpty()) login = null;
        }
        try {
            JSONObject patch = new JSONObject()
                    .put("siteKey", siteKey)
                    .put("token", token)
                    .put("updatedAt", System.currentTimeMillis());
            if (alias != null && !alias.isEmpty()) patch.put("alias", alias);
            if (login != null) patch.put("githubAccount", login);

            JSONObject rec = store.findAccount(accountKey);
            if (rec == null) {
                /* 账号尚未创建（异常路径）：建全量记录 */
                rec = new JSONObject().put("key", accountKey);
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) { String k = it.next(); rec.put(k, patch.get(k)); }
                store.upsertAccount(siteKey, rec);
            } else {
                store.patchAccount(accountKey, patch);
            }
            /* GitHub 用户名全局持久化：添加其他站点账号时自动预填 */
            if (login != null) {
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
        if (!done) {
            done = true;
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "授权已取消"));
        }
        finish();
    }

    @Override protected void onPause() {
        super.onPause();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
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
}