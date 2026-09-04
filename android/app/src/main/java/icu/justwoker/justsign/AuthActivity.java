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
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AuthActivity — GitHub 授权桥（新版 new-api 流程）：
 *   1. 先 POST /api/oauth/state {provider:'github',intent:'login'} 拿 flow_token（服务端绑定会话）
 *   2. WebView 打开 https://github.com/login/oauth/authorize?client_id=..&state=flow_token&scope=user:email
 *   3. GitHub 授权后回调 /oauth/github?code=..&state=..，站点前端拿 flow_token 交换 session
 *   4. 前端把完整 bundle 写入 localStorage['new-api:auth-session'] → 轮询读取 → 落盘 → 关窗
 *   兼容：老版直接 {access_token}，新版 {access_token, token_type, access_expires_at, session{..}, user{username}}
 */
public class AuthActivity extends Activity {
    private WebView wv;
    private LinearLayout boot;          // 启动层（拿 flow_token 时显示进度）
    private TextView bootText;
    private FrameLayout root;
    private String siteKey, accountKey, baseUrl, clientId;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean done = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        baseUrl = stripTail(findBaseUrl());
        clientId = findClientId();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        /* ---- WebView（隐藏，直到拿到 state 开始加载） ---- */
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // GitHub ↔ 站点 跳转全部留在本 WebView
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

        /* ---- 顶部提示条（WebView 阶段） ---- */
        TextView tip = new TextView(this);
        tip.setText("  GitHub 授权中 · 已登录 GitHub 将自动完成，成功后本窗口自动关闭  ");
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(12);
        tip.setBackgroundColor(0xE6111827);
        tip.setPadding(20, 26, 20, 26);
        root.addView(tip, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        setContentView(root);
        startAuthFlow();
    }

    /** 第一步：拿 flow_token（服务端 state），第二步：打开官方 authorize URL */
    private void startAuthFlow() {
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();
        new Thread(() -> {
            String state = null, err = null;
            try {
                JSONObject proxy = new Store(this).config().optJSONObject("proxy");
                String url = baseUrl + "/api/oauth/state";
                Request.Builder rb = new Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                        .post(RequestBody.create(
                                "{\"provider\":\"github\",\"intent\":\"login\"}".getBytes(),
                                MediaType.parse("application/json")));
                OkHttpClient c = client;
                if (proxy != null && proxy.optBoolean("enabled")) {
                    try {
                        java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                new java.net.InetSocketAddress(proxy.optString("host", "127.0.0.1"),
                                        proxy.optInt("port", 10808)));
                        c = client.newBuilder().proxy(p).build();
                    } catch (Exception ignored) {}
                }
                Response resp = c.newCall(rb.build()).execute();
                String body = resp.body() != null ? resp.body().string() : "";
                JSONObject j = new JSONObject(body);
                if (resp.code() == 200 && j.optBoolean("success")) {
                    Object d = j.opt("data");
                    if (d instanceof String) state = (String) d;
                    else if (d instanceof JSONObject) state = ((JSONObject) d).optString("flow_token", null);
                }
                if (state == null || state.isEmpty()) err = j.optString("message", "state 获取失败 http=" + resp.code());
            } catch (Exception e) { err = "state 请求异常: " + e.getMessage(); }

            final String st = state, er = err;
            h.post(() -> {
                if (done || isFinishing()) return;
                if (st == null) {
                    bootText.setText("准备失败: " + er + "\n返回可重试");
                    return;
                }
                boot.setVisibility(View.GONE);
                wv.setVisibility(View.VISIBLE);
                AlphaAnimation a = new AlphaAnimation(0f, 1f);
                a.setDuration(220);
                wv.startAnimation(a);
                String authUrl;
                if (clientId != null && !clientId.isEmpty()) {
                    authUrl = "https://github.com/login/oauth/authorize?client_id=" + clientId
                            + "&state=" + urlEncode(st) + "&scope=user:email";
                } else {
                    // 兜底：老版本站点端点（自带 state 生成）
                    authUrl = baseUrl + "/api/oauth/github";
                }
                wv.loadUrl(authUrl);
                h.postDelayed(poll, 1500);
            });
        }).start();
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String stripTail(String u) { return u.replaceAll("/+$", ""); }

    private String findBaseUrl() {
        Store store = new Store(this);
        try {
            JSONArray sites = store.config().getJSONArray("sites");
            for (int i = 0; i < sites.length(); i++) {
                JSONObject st = sites.getJSONObject(i);
                if (siteKey != null && siteKey.equals(st.optString("key"))) return st.optString("baseUrl");
            }
        } catch (Exception ignored) {}
        return "https://api.justwoker.icu";
    }

    /** github_client_id 来自 /api/status 缓存（MainActivity 写入），缺省用已知值 */
    private String findClientId() {
        try {
            JSONObject cfg = new Store(this).config();
            String id = cfg.optString("github_client_id", "");
            if (!id.isEmpty()) return id;
        } catch (Exception ignored) {}
        return "Ov23liBGecTYSePKpXQC";
    }

    /** 1.5s 轮询 localStorage['new-api:auth-session']（新版含 token_type/session，老版仅 access_token） */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (done || wv == null) return;
            wv.evaluateJavascript("localStorage.getItem('new-api:auth-session')", v -> {
                try {
                    if (v != null && !"null".equals(v) && v.length() > 4) {
                        String raw = v;
                        if (raw.startsWith("\"")) // evaluateJavascript 返回的是 JS 字符串字面量
                            raw = raw.substring(1, raw.length() - 1)
                                    .replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
                        JSONObject sess = new JSONObject(raw);
                        String token = tokenOf(sess);
                        if (token != null && !token.isEmpty()) { finishOk(sess, token); return; }
                    }
                } catch (Exception ignored) {}
                h.postDelayed(this, 1500);
            });
        }
    };

    /** 新版: access_token / user.username；老版: accessToken / user.login */
    private static String tokenOf(JSONObject sess) {
        String t = sess.optString("access_token", null);
        if (t == null || t.isEmpty()) t = sess.optString("accessToken", null);
        return t;
    }

    private void finishOk(JSONObject sess, String token) {
        done = true;
        Store store = new Store(this);
        try {
            JSONObject rec = store.findToken(accountKey);
            if (rec == null) rec = new JSONObject().put("key", accountKey);
            rec.put("siteKey", siteKey);
            String login = null;
            try { login = sess.getJSONObject("user").optString("username", null); } catch (Exception ignored) {}
            if (login == null || login.isEmpty()) try {
                login = sess.getJSONObject("user").optString("login", null);
            } catch (Exception ignored) {}
            if (login == null || login.isEmpty()) login = guessLogin(sess);
            if (login != null) rec.put("githubAccount", login);
            rec.put("token", token);
            rec.put("updatedAt", System.currentTimeMillis());
            store.upsertToken(rec);
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

    /** 兜底：从 bundle 任意层级找 login 类字段 */
    private static String guessLogin(JSONObject sess) {
        try {
            JSONObject u = sess.getJSONObject("user");
            for (String k : new String[]{"username", "login", "display_name", "name"})
                if (u.has(k) && !u.isNull(k) && !u.optString(k).isEmpty()) return u.optString(k);
        } catch (Exception ignored) {}
        return null;
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