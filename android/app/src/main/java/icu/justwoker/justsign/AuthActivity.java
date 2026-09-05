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
 * AuthActivity（v0.2.0）— GitHub 授权桥 + 凭据自动填充。
 *
 * 【v0.2.0 修复 state 竞速（第二次授权必失败的根因）】
 * 实测：首次授权时 WebView 缓存冷，站点 3.4MB SPA 加载慢，我们延迟注入的脚本抢先消费
 * flow_token → 成功；第二次资源已缓存，SPA 秒起并**自己先消费掉单次有效的 flow_token**
 * → 我们再调 /api/oauth/github 得到「State parameter is empty or mismatched」。
 * 修法：shouldOverrideUrlLoading 拦截站点 /oauth/ 回调 URL 并 return true（页面根本不加载），
 * 由 OkHttp 直接完成 code 交换。SPA 没有机会启动，不再赛跑。
 *
 * 【自动填充】GitHub 登录页 / 站点登录页出现账号密码框时注入 AuthFillJs 填入凭据。
 *   2FA：凭据配了才填并聚焦；没配则完全跳过（用户明确要求）。
 */
public class AuthActivity extends Activity {

    private WebView wv;
    private LinearLayout boot, errorLayer;
    private TextView bootText, tip, errMsg;
    private FrameLayout root;

    private String siteKey, accountKey, alias, credentialId, baseUrl, siteHost;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean done = false;
    private volatile boolean proxyApplied = false;
    private volatile boolean exchanging = false;
    private volatile boolean filled = false;

    /* 凭据快照（用于自动填充） */
    private String credAccount = "", credPassword = "", credOtp = "";
    private boolean credHasOtp = false;

    /** JS ↔ Java 桥 */
    public class Bridge {
        @JavascriptInterface public void onSession(String json) {
            if (done) return;
            h.post(() -> handleBundle(json));
        }
        @JavascriptInterface public void onFill(String json) {
            h.post(() -> {
                try {
                    JSONObject r = new JSONObject(json);
                    if (!r.optBoolean("ok")) return;
                    Object f = r.opt("filled");
                    String what = f == null ? "" : String.valueOf(f);
                    showTip("已自动填充 " + what.replace("[", "").replace("]", "").replace("\"", ""));
                    new Store(AuthActivity.this).opLog(siteKey, accountKey, "自动填充", "ok",
                            "已填充 " + what, credHasOtp ? "含 2FA 动态码" : "该账号无 2FA", "auto");
                } catch (Exception ignored) {}
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.initIcons(this);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        alias = getIntent().getStringExtra("alias");
        credentialId = getIntent().getStringExtra("credentialId");

        Store store = new Store(this);
        JSONObject site = store.findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl", "") : "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "站点不存在或 baseUrl 为空"));
            finish();
            return;
        }
        try { siteHost = new java.net.URL(baseUrl).getHost(); } catch (Exception e) { siteHost = ""; }

        /* 凭据快照：账号 / 密码 / 2FA（没配 2FA 则 credOtp 为空串，脚本整体跳过） */
        if (credentialId == null || credentialId.isEmpty()) {
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null) credentialId = acc.optString("credentialId", "");
        }
        if (credentialId != null && !credentialId.isEmpty()) {
            JSONObject c = store.findCredential(credentialId);
            if (c != null) {
                credAccount = c.optString("siteAccount", "");
                if (credAccount.isEmpty()) credAccount = c.optString("githubUser", "");
                credPassword = store.credPassword(credentialId);
                credHasOtp = store.credHasTwofa(credentialId);
                credOtp = credHasOtp ? store.credTwofaCode(credentialId) : "";
            }
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            /* 关键：拦截站点 /oauth/ 回调，不让 SPA 加载抢消费 flow_token */
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (req == null || req.getUrl() == null) return false;
                return interceptCallback(req.getUrl().toString());
            }
            @Override public void onPageFinished(WebView v, String url) {
                maybeFill(url);
                /* 兜底：若拦截未命中（部分重定向不过 shouldOverride），页面已落地时再交换一次 */
                if (url != null) interceptCallback(url);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
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

        /* 启动层 */
        boot = Ui.col(this);
        boot.setGravity(Gravity.CENTER);
        boot.setBackgroundColor(Color.WHITE);
        boot.addView(new ProgressBar(this));
        TextView title = Ui.tv(this, "正在准备 GitHub 授权…", 16, Ui.TXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 18);
        boot.addView(title, tlp);
        bootText = Ui.tv(this, "与站点建立安全会话", 13, Ui.SUB);
        boot.addView(bootText);
        root.addView(boot, new FrameLayout.LayoutParams(-1, -1));

        /* 错误层 */
        errorLayer = Ui.col(this);
        errorLayer.setGravity(Gravity.CENTER);
        errorLayer.setBackgroundColor(Color.WHITE);
        errorLayer.setVisibility(View.GONE);
        errorLayer.addView(Ui.tv(this, "授权准备失败", 16, Ui.TXT, true));
        errMsg = Ui.tv(this, "", 13, Ui.RED);
        errMsg.setGravity(Gravity.CENTER);
        errMsg.setPadding(Ui.dp(this, 30), Ui.dp(this, 12), Ui.dp(this, 30), 0);
        errorLayer.addView(errMsg);
        TextView retry = Ui.btn(this, "重试", 15, Ui.white(), Ui.BLUE, 40, 12);
        retry.setOnClickListener(v -> {
            errorLayer.setVisibility(View.GONE);
            boot.setVisibility(View.VISIBLE);
            startAuthFlow();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = Ui.dp(this, 22);
        errorLayer.addView(retry, rlp);
        TextView backTip = Ui.tv(this, "返回键退出", 12, Ui.SUB);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-2, -2);
        blp2.topMargin = Ui.dp(this, 14);
        errorLayer.addView(backTip, blp2);
        root.addView(errorLayer, new FrameLayout.LayoutParams(-1, -1));

        /* 底部提示条 */
        tip = Ui.tv(this, "  GitHub 授权中 · 已登录将自动完成  ", 12, Color.WHITE);
        tip.setBackgroundColor(0xE6111827);
        tip.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(tip, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        startAuthFlow();
    }

    /* ================= 回调拦截（修 state 竞速） ================= */

    /** @return true = 已拦截（阻止页面加载并自行交换） */
    private boolean interceptCallback(String url) {
        if (done || exchanging || url == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            if (siteHost == null || siteHost.isEmpty()) return false;
            if (!u.getHost().equalsIgnoreCase(siteHost)) return false;
            String path = u.getPath();
            if (path == null || !path.startsWith("/oauth/")) return false;
            String provider = path.substring("/oauth/".length());
            if (provider.contains("/")) provider = provider.substring(0, provider.indexOf('/'));
            if (!provider.matches("[a-zA-Z0-9_-]{1,32}")) return false;

            String q = u.getQuery();
            String code = param(q, "code"), state = param(q, "state");
            if (code.isEmpty()) return false;

            exchanging = true;
            showTip("正在交换授权凭证…");
            final String fp = provider, fc = code, fs = state;
            new Thread(() -> exchange(fp, fc, fs), "oauth-exchange").start();
            return true;    // 阻止 WebView 加载该 URL —— SPA 不会启动，不会抢消费 flow_token
        } catch (Exception e) { return false; }
    }

    private static String param(String query, String key) {
        if (query == null) return "";
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            if (kv.substring(0, i).equals(key)) {
                try { return java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"); }
                catch (Exception e) { return kv.substring(i + 1); }
            }
        }
        return "";
    }

    /** OkHttp 直接换 bundle（带上 WebView 的站点 Cookie，等价于同源 fetch） */
    private void exchange(String provider, String code, String state) {
        Response resp = null;
        String err = null;
        try {
            OkHttpClient c = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
                    new Store(this).config().optJSONObject("proxy"));
            Request.Builder rb = new Request.Builder()
                    .url(baseUrl + "/api/oauth/" + provider
                            + "?code=" + URLEncoder.encode(code, "UTF-8")
                            + "&state=" + URLEncoder.encode(state == null ? "" : state, "UTF-8"))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36");
            String ck = cookieOf(baseUrl);
            if (!ck.isEmpty()) rb.header("Cookie", ck);
            resp = c.newCall(rb.build()).execute();
            int http = resp.code();
            String body = resp.body() != null ? resp.body().string() : "";
            if (body.trim().isEmpty()) err = "站点返回空响应（HTTP " + http + "）";
            else {
                final String fb = body;
                h.post(() -> handleBundle(fb));
                return;
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
        final String fe = err;
        h.post(() -> {
            exchanging = false;
            showLoadError("授权交换失败: " + fe);
        });
    }

    private void handleBundle(String json) {
        if (done) return;
        try {
            JSONObject r = new JSONObject(json);
            JSONObject d = r.optJSONObject("data");
            if (r.optBoolean("success") && d != null) {
                String token = d.optString("access_token", d.optString("accessToken", ""));
                if (!token.isEmpty()) { finishOk(d, token); return; }
            }
            String msg = r.optString("message", "交换失败");
            exchanging = false;
            showLoadError("授权失败: " + msg);
        } catch (Exception e) {
            exchanging = false;
            showLoadError("授权响应解析失败");
        }
    }

    /* ================= 自动填充 ================= */

    private void maybeFill(String url) {
        if (done || filled || url == null || url.startsWith("about:")) return;
        if (credAccount.isEmpty() && credPassword.isEmpty()) return;   // 无凭据不注入
        /* 只在登录相关页注入（GitHub 登录/2FA 页、站点登录页） */
        String u = url.toLowerCase(java.util.Locale.US);
        boolean loginish = u.contains("github.com/login") || u.contains("/sessions")
                || u.contains("two-factor") || u.contains("/login") || u.contains("/signin")
                || u.contains("/register") || u.contains("/oauth/authorize");
        if (!loginish) return;
        filled = true;
        try {
            wv.evaluateJavascript(AuthFillJs.render(credAccount, credPassword, credOtp), null);
            showTip(credHasOtp ? "已注入账号密码与 2FA 动态码" : "已注入账号密码（该账号无 2FA）");
        } catch (Exception ignored) {}
        /* 允许后续页面（如 2FA 页）再次注入 */
        h.postDelayed(() -> filled = false, 2500);
    }

    /* ================= 授权流程 ================= */

    private void startAuthFlow() {
        applyProxyThen(() -> new Thread(this::authFlowNetwork, "auth-flow").start());
    }

    private void authFlowNetwork() {
        String state = null, err = null, clientId = "";
        try {
            Store store = new Store(this);
            OkHttpClient c = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
                    store.config().optJSONObject("proxy"));

            /* 1) clientId + 顺手缓存站点 turnstileSiteKey / quota 单位 */
            try {
                JSONObject st = getJson(c, baseUrl + "/api/status");
                JSONObject d = st == null ? null : st.optJSONObject("data");
                if (d != null) {
                    clientId = d.optString("github_client_id", "");
                    String tsk = d.optString("turnstile_site_key", "");
                    if (!tsk.isEmpty() && siteKey != null) store.putSiteMeta(siteKey, "turnstileSiteKey", tsk);
                    long unit = d.optLong("quota_per_unit", 0);
                    if (unit > 0 && siteKey != null) store.putSiteMeta(siteKey, "quotaPerUnit", unit);
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
                        err = "站点限流（429）" + (ra.isEmpty() ? "" : "，建议等待 " + ra + " 秒");
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
                String authUrl = "https://github.com/login/oauth/authorize?client_id=" + enc(cid)
                        + "&state=" + enc(st2) + "&scope=user:email";
                wv.loadUrl(authUrl);
            });
        } catch (Throwable t) {
            final String er = "授权准备异常: " + t.getMessage();
            h.post(() -> { if (!isFinishing()) showLoadError(er); });
        }
    }

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
            if (credentialId != null && !credentialId.isEmpty()) patch.put("credentialId", credentialId);

            JSONObject rec = store.findAccount(accountKey);
            if (rec == null) {
                rec = new JSONObject().put("key", accountKey);
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) { String k = it.next(); rec.put(k, patch.get(k)); }
                store.upsertAccount(siteKey, rec);
            } else {
                store.patchAccount(accountKey, patch);
            }
            if (login != null) {
                try {
                    JSONObject cfg = store.config();
                    cfg.put("lastGithubUser", login);
                    store.saveConfig(cfg);
                } catch (Exception ignored) {}
            }
            store.appendLog(siteKey, accountKey, "auth", "via=android user=" + (login == null ? "?" : login));
            store.opLog(siteKey, accountKey, "授权", "ok",
                    "授权成功" + (login == null ? "" : (" · " + login)),
                    "token 有效 " + fmtMin(TokenKeeper.remainMinutes(token)) + " 分钟", "user");

            Intent out = new Intent();
            out.putExtra("ok", true);
            out.putExtra("user", login == null ? "" : login);
            setResult(RESULT_OK, out);
        } catch (Exception e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "保存失败: " + e.getMessage()));
        }
        finish();
    }

    private static String fmtMin(double m) {
        return m < 0 ? "?" : String.format(java.util.Locale.US, "%.0f", m);
    }

    /* ================= 代理 / 工具 ================= */

    private void applyProxyThen(Runnable then) {
        if (proxyApplied) { then.run(); return; }
        JSONObject proxy;
        try { proxy = new Store(this).config().optJSONObject("proxy"); } catch (Exception e) { proxy = null; }
        final boolean enabled = proxy != null && proxy.optBoolean("enabled");
        if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyApplied = true;
            then.run();
            return;
        }
        final String host = proxy.optString("host", "127.0.0.1");
        final int port = proxy.optInt("port", 10808);
        new Thread(() -> {
            boolean reachable = ProxyDetect.reachable(host, port, 1500);
            h.post(() -> {
                if (done || isFinishing()) return;
                proxyApplied = true;
                if (!reachable) {
                    showTip("未检测到代理 " + host + ":" + port + " · 若加载失败请开 VPN");
                    then.run();
                    return;
                }
                try {
                    ProxyConfig pc = new ProxyConfig.Builder()
                            .addProxyRule("socks5://" + host + ":" + port)
                            .addDirect()
                            .build();
                    ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {
                        showTip("已挂载代理 " + host + ":" + port + " · GitHub 授权中");
                        then.run();
                    });
                } catch (Exception e) { then.run(); }
            });
        }, "auth-proxy").start();
    }

    private static OkHttpClient withProxy(OkHttpClient base, JSONObject proxy) {
        if (proxy != null && proxy.optBoolean("enabled")) {
            try {
                String host = proxy.optString("host", "127.0.0.1");
                int port = proxy.optInt("port", 10808);
                if (!ProxyDetect.reachable(host, port, 1200)) return base;
                return base.newBuilder().proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress(host, port))).build();
            } catch (Exception ignored) {}
        }
        return base;
    }

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

    private static String cookieOf(String base) {
        try {
            String ck = CookieManager.getInstance().getCookie(base);
            return ck == null ? "" : ck;
        } catch (Exception e) { return ""; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s == null ? "" : s; }
    }

    private void showTip(String text) {
        h.post(() -> { if (tip != null) tip.setText("  " + text + "  "); });
    }

    private void showLoadError(String detail) {
        if (done || isFinishing()) return;
        h.post(() -> {
            if (done || isFinishing()) return;
            if (boot != null) boot.setVisibility(View.GONE);
            if (wv != null) wv.setVisibility(View.GONE);
            if (errMsg != null) errMsg.setText(detail
                    + "\n\n常见原因：\n· 站点限流（429），稍等几分钟\n· 代理未开或未放行本应用\n· GitHub 直连被阻断（需开 VPN）");
            if (errorLayer != null) errorLayer.setVisibility(View.VISIBLE);
            showTip("授权准备失败 · 可点重试");
            try {
                new Store(this).opLog(siteKey, accountKey, "授权", "err", detail, "", "user");
            } catch (Exception ignored) {}
        });
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