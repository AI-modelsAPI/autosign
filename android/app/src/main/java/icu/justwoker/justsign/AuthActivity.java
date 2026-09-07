package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
 * AuthActivity（v0.2.3）— GitHub 授权（仅在需要人工交互时由 MainActivity 拉起）。
 * 优先在后台静默完成（SilentAuth）；只有需要用户登录/2FA时才展示本界面。
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
    private volatile String authUrl = "";
    private volatile int reauthTries = 0;

    private String credAccount = "", credPassword = "", credOtp = "";
    /** true = 该站的 /api/oauth/state 只认 GET（AgentRouter 型）；由 404 探测得出并记入站点 meta */
    private boolean stateUseGet = false;
    private boolean credHasOtp = false;
    /* 本次授权绑定的「站点×账号」Profile（降级=Default 时为 null） */
    private androidx.webkit.Profile mProfile;

    public class Bridge {
        @JavascriptInterface public void onSession(String json) {
            if (done) return;
            h.post(() -> handleBundle(json, ""));
        }
        @JavascriptInterface public void onFill(String json) {
            h.post(() -> {
                try {
                    JSONObject r = new JSONObject(json);
                    if (!r.optBoolean("ok")) return;
                    String act = r.optString("action", "");
                    if (!act.isEmpty()) {
                        String label;
                        switch (act) {
                            case "submitLogin": label = "已自动点击登录"; break;
                            case "submit2fa":   label = "已自动提交 2FA 验证码"; break;
                            case "switch2fa":   label = "已切换到验证器 App 验证"; break;
                            case "expand2fa":   label = "已展开其他验证方式"; break;
                            default:            label = act; break;
                        }
                        showTip(label);
                        new Store(AuthActivity.this).opLog(siteKey, accountKey, "自动登录", "ok",
                                label, r.optString("label", ""), "auto");
                        if ("submitLogin".equals(act) || "switch2fa".equals(act) || "expand2fa".equals(act)) {
                            filled = false;
                        }
                        return;
                    }
                    Object f = r.opt("filled");
                    if (f == null) return;
                    String what = String.valueOf(f);
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

        if (credentialId == null || credentialId.isEmpty()) {
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null) credentialId = acc.optString("credentialId", "");
        }
        if (credentialId != null && !credentialId.isEmpty()) {
            JSONObject c = store.findCredential(credentialId);
            if (c != null) {
                /* 这里填的是 GitHub 登录页，必须优先用 githubUser；
                 * siteAccount 只是站内昵称，两者不同名时用它会登录失败。 */
                credAccount = c.optString("githubUser", "");
                if (credAccount.isEmpty()) credAccount = c.optString("siteAccount", "");
                credPassword = store.credPassword(credentialId);
                credHasOtp = store.credHasTwofa(credentialId);
                credOtp = credHasOtp ? store.credTwofaCode(credentialId) : "";
            }
        }
        /* 已探测过的站点形态直接复用，省掉一次注定 404 的 POST */
        if (siteKey != null) stateUseGet = "get".equals(store.siteMeta(siteKey, "stateMethod", ""));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        wv = new WebView(this);
        /* 需求3（opus4.8 审计）：绑定「站点×账号」专属 Profile —— 必须在任何
         * getSettings/loadUrl 之前。与离屏 SilentAuth 同名 Profile，授权一次
         * 后该账号会话长期保留在自己的分区里，刷新/签到自动交换不再重复授权。 */
        mProfile = WebViewProfileUtil.bindProfile(wv,
                WebViewProfileUtil.profileNameFor(siteKey, accountKey));
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (req == null || req.getUrl() == null) return false;
                return interceptCallback(req.getUrl().toString());
            }
            @Override public void onPageFinished(WebView v, String url) {
                maybeFill(url);
                if (url != null && interceptCallback(url)) return;
                /* OAuth 确认页自动授权（需求2）：800ms 后自动点 Authorize，
                 * 配合登录页自动填充+自动提交，实现授权全程无手动 */
                if (url != null && url.toLowerCase(java.util.Locale.US).contains("/login/oauth/authorize")) {
                    v.evaluateJavascript(AuthFillJs.authorizeJs(), null);
                }
                maybeResumeAuthorize(url);
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

        tip = Ui.tv(this, "  GitHub 授权中 · 已登录将自动完成  ", 12, Color.WHITE);
        tip.setBackgroundColor(0xE6111827);
        tip.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(tip, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        startAuthFlow();
    }

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
            return true;
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
            resp = c.newCall(rb.build()).execute();
            int http = resp.code();
            String body = resp.body() != null ? resp.body().string() : "";
            /* opus4.8 审计·B-02：New API 系真凭据是 Set-Cookie session，必须抓取 */
            final String sc = extractCookies(resp.headers("Set-Cookie"));
            if (body.trim().isEmpty()) err = "站点返回空响应（HTTP " + http + "）";
            else {
                final String fb = body;
                h.post(() -> handleBundle(fb, sc));
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

    private void handleBundle(String json, String setCookie) {
        if (done) return;
        try {
            JSONObject r = new JSONObject(json);
            JSONObject d = r.optJSONObject("data");
            if (r.optBoolean("success") && d != null) {
                /* opus4.8 审计：token 尽力而为（JSON null/缺失视为无，三字段回退）；
                 * cookie 型站（AgentRouter）以 setCookie 为真凭据。 */
                String token = "";
                for (String k : new String[]{"access_token", "accessToken", "token"}) {
                    if (d.has(k) && !d.isNull(k)) {
                        Object at = d.get(k);
                        if (at instanceof String) {
                            String sv = ((String) at).trim();
                            if (!sv.isEmpty() && !"null".equals(sv)) { token = sv; break; }
                        }
                    }
                }
                /* 身份标识：data 直接是用户对象（AgentRouter 型）或 data.user */
                String login = d.optString("username", "");
                if (login.isEmpty() || "null".equals(login)) login = "";
                if (login.isEmpty()) {
                    JSONObject usr = d.optJSONObject("user");
                    if (usr != null) {
                        login = usr.optString("username", "");
                        if (login.isEmpty() || "null".equals(login)) login = usr.optString("login", "");
                        if (login == null || "null".equals(login)) login = "";
                    }
                }
                if (!token.isEmpty() || !setCookie.isEmpty()) {
                    finishOk(d, token, setCookie, login);
                    return;
                }
                /* 两者皆无：诊断（data keys）帮助适配 */
                java.util.Iterator<String> ks = d.keys();
                StringBuilder kb = new StringBuilder();
                int kn = 0;
                while (ks.hasNext() && kn < 20) { kb.append(ks.next()).append(','); kn++; }
                exchanging = false;
                showLoadError("授权响应缺少会话凭据（data keys: " + kb + "…）");
            }
            String msg = r.optString("message", "交换失败");
            exchanging = false;
            showLoadError("授权失败: " + msg);
        } catch (Exception e) {
            exchanging = false;
            showLoadError("授权响应解析失败");
        }
    }

    private void maybeResumeAuthorize(String url) {
        if (done || exchanging || url == null || authUrl.isEmpty()) return;
        String u = url.toLowerCase(java.util.Locale.US);
        if (!u.startsWith("https://github.com")) return;
        if (u.contains("/login") || u.contains("/session") || u.contains("two-factor")
                || u.contains("/oauth/authorize") || u.contains("device")
                || u.contains("verified-device") || u.contains("sudo")) return;
        if (reauthTries >= 2) {
            showLoadError("GitHub 已登录但未跳回站点，请点重试。");
            return;
        }
        reauthTries++;
        showTip("GitHub 已登录，正在返回站点完成授权…");
        h.postDelayed(() -> { if (!done && wv != null) wv.loadUrl(authUrl); }, 400);
    }

    private void maybeFill(String url) {
        if (done || filled || url == null || url.startsWith("about:")) return;
        if (credAccount.isEmpty() && credPassword.isEmpty()) return;
        String u = url.toLowerCase(java.util.Locale.US);
        /* 授权确认页 URL 是 github.com/login/oauth/authorize，含 github.com/login
         * 前缀但不是登录页（无表单，填充 no-op 且污染 filled/reauth 时序）——显式排除 */
        if (u.contains("/oauth/authorize")) return;
        boolean loginish = u.contains("github.com/login") || u.contains("/sessions")
                || u.contains("two-factor") || u.contains("/signin")
                || u.contains("/register");
        if (!loginish) return;
        filled = true;
        try {
            boolean autoSubmit = new Store(this).uiPref("autoSubmitLogin", true);
            String otp = credHasOtp ? new Store(this).credTwofaCode(credentialId) : "";
            if (!otp.isEmpty()) credOtp = otp;
            /* 密码为空是常见故障（卸载重装后 Keystore 密钥销毁 → 密文失效被清空）。
             * 必须如实告知，否则用户只看到「已填入账号密码」却卡在登录页，无从判断。 */
            boolean noPwd = credPassword == null || credPassword.isEmpty();
            wv.evaluateJavascript(AuthFillJs.render(credAccount, credPassword, otp, autoSubmit && !noPwd), null);
            if (noPwd) {
                showTip("仅填入账号，未存密码 —— 请手动输入密码，或到「设置 → 凭据库」补录后重试");
                new Store(this).opLog(siteKey, accountKey, "自动填充", "err",
                        "凭据库无密码，仅填账号", "别名 " + alias + "：密码为空（卸载重装会清除已存密码，需重新录入）", "user");
            } else {
                showTip(credHasOtp
                        ? (autoSubmit ? "已填入账号密码与 2FA，正在自动登录…" : "已注入账号密码与 2FA 动态码")
                        : (autoSubmit ? "已填入账号密码，正在自动登录…" : "已注入账号密码（该账号无 2FA）"));
            }
        } catch (Exception ignored) {}
        h.postDelayed(() -> filled = false, 2500);
    }

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

            for (int attempt = 0; attempt < 3 && state == null; attempt++) {
                Response resp = null;
                try {
                    /* 两种站点形态：多数 New API 站用 POST，AgentRouter 一类只认
                     * GET ?mode=login（POST 直接 404 Invalid URL）。attempt 0 先 POST，
                     * 之后回退 GET；两者都返回合法 JSON，故不能只靠解析失败来判别。 */
                    boolean useGet = stateUseGet || attempt > 0;
                    Request.Builder rb = new Request.Builder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                            .header("Accept", "application/json");
                    if (useGet) {
                        rb.url(baseUrl + "/api/oauth/state?mode=login").get();
                    } else {
                        rb.url(baseUrl + "/api/oauth/state")
                                .post(RequestBody.create(
                                        "{\"provider\":\"github\",\"intent\":\"login\"}",
                                        MediaType.parse("application/json")));
                    }
                    resp = c.newCall(rb.build()).execute();
                    int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (code == 429) {
                        String ra = resp.header("Retry-After", "");
                        err = "站点限流（429）" + (ra.isEmpty() ? "" : "，建议等待 " + ra + " 秒");
                        if (attempt < 2) { try { Thread.sleep(4000L * (attempt + 1)); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    /* 该站不支持 POST 此路由：立即改用 GET 重试，并记住形态 */
                    if (code == 404 && !useGet) {
                        stateUseGet = true;
                        if (siteKey != null) store.putSiteMeta(siteKey, "stateMethod", "get");
                        err = "站点不支持 POST 取授权会话，已改用 GET 重试";
                        continue;
                    }
                    if (body.trim().isEmpty()) {
                        err = "站点返回空响应（HTTP " + code + "）";
                        if (attempt < 2) { try { Thread.sleep(2000L); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    JSONObject j;
                    try { j = new JSONObject(body); }
                    catch (Exception pe) {
                        err = "站点响应非 JSON（HTTP " + code + "）";
                        if (!useGet) { stateUseGet = true; continue; }
                        break;
                    }
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
                authUrl = "https://github.com/login/oauth/authorize?client_id=" + enc(cid)
                        + "&state=" + enc(st2) + "&scope=user:email"
                        + (credAccount.isEmpty() ? "" : ("&login=" + enc(credAccount)));
                reauthTries = 0;
                /* 会话切换策略（opus4.8 审计·需求3 修订版）：
                 * Profile 隔离后，本账号的 Cookie 分区只属于自己 ——
                 * · 首次授权（账号无 token / 无 githubAccount）：分区是空的，
                 *   无需清 Cookie（本来就没有旧会话），直接加载走登录页；
                 *   无密码凭据同样直接加载（靠 login 参数或人工选择账号）。
                 * · 已授权过：分区里就是本账号的会话，直接加载 → GitHub 自动
                 *   302 回站点 → exchange 自动完成，全程无需再输密码。
                 * 注意：绝不清全局 CookieManager（会破坏其他账号分区/Default）。 */
                showTip(mProfile != null ? "使用账号专属会话分区" : "正在准备授权…");
                wv.loadUrl(authUrl);
            });
        } catch (Throwable t) {
            final String er = "授权准备异常: " + t.getMessage();
            h.post(() -> { if (!isFinishing()) showLoadError(er); });
        }
    }

    /* 首次授权用户确认标志（v0.3.7 无锚点兜底） */
    private volatile boolean idConfirmed = false;

    private void finishOk(JSONObject bundle, String token, String setCookie, String loginFromResp) {
        if (done) return;
        Store store = new Store(this);
        /* 登录名优先用授权响应里的真实 username（AgentRouter 型 data 直层），
         * 回退旧结构 data.user，最后回退 bundle.username */
        String login = (loginFromResp != null && !loginFromResp.isEmpty()) ? loginFromResp : null;
        if (login == null) {
            JSONObject userObj = bundle.optJSONObject("user");
            if (userObj != null) {
                login = userObj.optString("username", "");
                if (login.isEmpty()) login = userObj.optString("login", "");
                if (login.isEmpty() || "null".equals(login)) login = null;
            }
        }
        if (login == null && bundle.has("username") && !bundle.isNull("username")) {
            String u0 = bundle.optString("username", "");
            if (!u0.isEmpty() && !"null".equals(u0)) login = u0;
        }
                /* 身份校验 B1（opus4.8 复审·锚点分层）：
         * 1) 强判据：站点响应含 github_id（New API 系 = GitHub 数字 ID，字符串）
         *    且凭据已缓存 githubId → 两者必须相等，不等即拒（不看 username，
         *    因为站内名是 github_<站内id> 与 GitHub 名无关）。
         * 2) 弱判据（fallback）：无 github_id 数据时回退 username 比对（token 型旧路径）。
         * 3) 都拿不到（无 github_id 数据且缓存缺失）→ 跳过校验，不误拒。
         * 拒绝后不置 done，用户可在授权页切换账号重试。 */
        String expect = expectGithubLogin();
        String respGhId = "";
        Object gidO = bundle.opt("github_id");
        if (gidO != null) {
            respGhId = String.valueOf(gidO).trim();
            if ("null".equals(respGhId)) respGhId = "";
        }
        /* 锚点链 v0.3.7：github_id → github_user_id → null（AgentRouter 老用户两者皆 null） */
        String respGhId2 = "";
        if (respGhId.isEmpty() && bundle.has("github_user_id") && !bundle.isNull("github_user_id")) {
            Object gid2 = bundle.opt("github_user_id");
            if (gid2 != null) {
                respGhId2 = String.valueOf(gid2).trim();
                if ("null".equals(respGhId2)) respGhId2 = "";
            }
        }
        boolean mismatch = false;
        String mmWhy = "";
        if (!respGhId.isEmpty()) {
            String cachedId = ensureGithubId(expect);
            if (!cachedId.isEmpty() && !cachedId.equals(respGhId)) {
                mismatch = true;
                mmWhy = "GitHub ID 不符：期望 " + expect + "(" + cachedId + ")，实际 " + respGhId;
            }
        } else if (expect != null && !expect.isEmpty()
                && login != null && !login.isEmpty()
                && !expect.trim().equalsIgnoreCase(login.trim())) {
            /* 站内名是 github_<站内id>，与 GitHub 名无必然关系：
             * 无可用锚点时改为「首次授权用户确认」而非直接拒（审计定稿）。 */
            boolean confirmed = idConfirmed;
            if (!confirmed && setCookie != null && !setCookie.isEmpty()) {
                /* cookie 型站首次授权：弹确认框让用户核对站内用户名 */
                lastBundle = bundle; lastToken = token; lastCookie = setCookie; lastLogin = login;
                showConfirmDialog(login, expect);
                return;   // 等用户确认后带 confirmed=true 重新进入
            }
            if (!confirmed) {
                mismatch = true;
                mmWhy = "GitHub 用户名不符：期望 " + expect + "，实际 " + login;
            }
        }
        if (mismatch) {
            exchanging = false;
            showLoadError("授权账号不符：" + mmWhy
                    + "。\n请在 GitHub 退出后用 " + expect + " 登录，或到「设置 → 凭据库」核对绑定。");
            try {
                store.opLog(siteKey, accountKey, "授权", "err",
                        "身份不符，拒绝落库防串号", mmWhy, "auto");
            } catch (Exception ignored) {}
            return;   // 关键：不写 token、不置 done
        }
        done = true;
        try {
            /* token 不再无条件落库（cookie 型站 token 为空，脏值拦截在下方） */
            JSONObject patch = new JSONObject()
                    .put("siteKey", siteKey)
                    .put("updatedAt", System.currentTimeMillis());
            if (alias != null && !alias.isEmpty()) patch.put("alias", alias);
            if (login != null) patch.put("githubAccount", login);
            if (credentialId != null && !credentialId.isEmpty()) patch.put("credentialId", credentialId);
        /* opus4.8 审计·B-02：cookie 型站点的真会话凭据（gin session） */
        if (setCookie != null && !setCookie.isEmpty()) patch.put("siteCookie", setCookie);
        /* v0.3.8：落 ghAnchor（确认过的站内名）+ siteUserId（站点数字ID，字符串防精度丢失）
         * 供后台静默比对与 New-Api-User 头使用 */
        if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
        Object sid = bundle.opt("id");
        if (sid != null) {
            String su = String.valueOf(sid).trim();
            if (!su.isEmpty() && !"null".equals(su)) patch.put("siteUserId", su);
        }
        /* v0.3.8：落 ghAnchor（确认过的站内名）+ siteUserId（站点数字ID，字符串防精度丢失）
         * 供后台静默比对与 New-Api-User 头使用 */
        if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
        Object sid2 = bundle.opt("id");
        if (sid != null) {
            String su = String.valueOf(sid2).trim();
            if (!su.isEmpty() && !"null".equals(su)) patch.put("siteUserId", su);
        }
        /* v0.3.8：落 ghAnchor（确认过的站内名）+ siteUserId（站点数字ID，字符串防精度丢失）
         * 供后台静默比对与 New-Api-User 头使用 */
        if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
        Object sid3 = bundle.opt("id");
        if (sid3 != null) {
            String su3 = String.valueOf(sid3).trim();
            if (!su3.isEmpty() && !"null".equals(su3)) patch.put("siteUserId", su3);
        }
        /* token 允许为空（cookie 型站）；脏值不落库 */
        if (token != null && !token.trim().isEmpty() && !"null".equals(token.trim())) patch.put("token", token.trim());

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
            /* 需求3：落盘本账号 Profile 会话 —— 授权一次后，该账号分区里的
             * GitHub 会话长期有效，刷新/签到后台自动交换，无需再手动授权 */
            WebViewProfileUtil.flush(mProfile);
            store.appendLog(siteKey, accountKey, "auth", "via=android user=" + (login == null ? "?" : login));
            store.opLog(siteKey, accountKey, "授权", "ok",
                    "授权成功" + (login == null ? "" : (" · " + login)), "", "user");

            Intent out = new Intent();
            out.putExtra("ok", true);
            out.putExtra("user", login == null ? "" : login);
            setResult(RESULT_OK, out);
        } catch (Exception e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "保存失败: " + e.getMessage()));
        }
        finish();
    }

    /* v0.3.7：无锚点时首次授权用户确认（审计定稿：仅交互式 AuthActivity 弹，SilentAuth 不弹） */
    private void showConfirmDialog(String siteUsername, String expectGithub) {
        Store store = new Store(this);
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("请核对授权身份")
                    .setMessage("站点返回的站内用户名是：" + siteUsername
                            + "\n\n请在站点网页的「个人设置」核对该用户名属于你的 GitHub 账号（"
                            + expectGithub + "）。\n\n确认无误请点「确认是本人」，否则点「取消授权」。")
                    .setPositiveButton("确认是本人", (dg, w) -> {
                        idConfirmed = true;
                        store.opLog(siteKey, accountKey, "授权", "info",
                                "用户确认站内身份", "站内名 " + siteUsername, "user");
                        finishOk(lastBundle, lastToken, lastCookie, lastLogin);
                    })
                    .setNegativeButton("取消授权", (dg, w) -> {
                        store.opLog(siteKey, accountKey, "授权", "err",
                                "用户取消：站内身份不符", "站内名 " + siteUsername, "user");
                        setResult(RESULT_CANCELED, new Intent().putExtra("error", "用户确认身份不符"));
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    /* showConfirmDialog 重入所需快照 */
    private JSONObject lastBundle; private String lastToken, lastCookie, lastLogin;

    /** org.json optString 对 JSON null 值返回字面 "null" 字符串（而非 fallback）——显式拦截 */
    static String jsonStr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        String v = o.optString(key, "");
        if (v == null) return "";
        v = v.trim();
        if (v.isEmpty() || v.equals("null") || v.equals("undefined")) return "";
        return v;
    }

    /** opus4.8 审计·B-02：从 OkHttp 响应头拼装 Cookie 头值（仅保留有值 cookie） */
    private static String extractCookies(java.util.List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String sc : setCookies) {
            int semi = sc.indexOf(';');
            String pair = (semi >= 0 ? sc.substring(0, semi) : sc).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String val = pair.substring(eq + 1).trim();
            if (val.isEmpty() || "deleted".equalsIgnoreCase(val)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(pair);
        }
        return sb.toString();
    }

    /** opus4.8 复审·B1 锚点强化：确保凭据缓存了 GitHub 数字 ID（githubId）。
     * New API 系站点 OAuth 响应里的 username 是站内名（github_<站内id>），
     * 不能当 GitHub 用户名比对；github_id 列存的是 GitHub API /user 的
     * 数字 id（字符串形式，官方源码 oauth/github.go 实锤），永久不变。
     * 已缓存 → 直接返回；未缓存 → 查 api.github.com 缓存（失败返回 ""，
     * 调用方跳过校验不误拒）。 */
    private String ensureGithubId(String githubUser) {
        if (githubUser == null || githubUser.isEmpty()) return "";
        try {
            Store store = new Store(this);
            if (credentialId != null && !credentialId.isEmpty()) {
                JSONObject c = store.findCredential(credentialId);
                if (c != null) {
                    String cached = c.optString("githubId", "");
                    if (!cached.isEmpty() && !"null".equals(cached)) return cached;
                }
            }
            /* 查询 GitHub 数字 ID（未认证 60 次/时/IP，缓存后只查一次） */
            OkHttpClient c2 = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
                    store.config().optJSONObject("proxy"));
            Response r = c2.newCall(new Request.Builder()
                    .url("https://api.github.com/users/" + githubUser)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "justsign-app").build()).execute();
            String body = r.body() != null ? r.body().string() : "";
            try { r.close(); } catch (Exception ignored) {}
            if (r.code() == 200) {
                JSONObject ju = new JSONObject(body);
                long id = ju.optLong("id", 0);
                if (id > 0 && credentialId != null && !credentialId.isEmpty()) {
                    store.patchCredential(credentialId,
                            new JSONObject().put("githubId", String.valueOf(id)));
                    return String.valueOf(id);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 本账号期望的 GitHub 用户名：凭据 githubUser 优先，回退账号别名；空=不校验 */
    private String expectGithubLogin() {
        try {
            Store store = new Store(this);
            if (credentialId != null && !credentialId.isEmpty()) {
                JSONObject c = store.findCredential(credentialId);
                if (c != null) {
                    String gu = c.optString("githubUser", "");
                    if (!gu.isEmpty()) return gu;
                }
            }
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null) {
                String cid = acc.optString("credentialId", "");
                if (!cid.isEmpty()) {
                    JSONObject c = store.findCredential(cid);
                    if (c != null) {
                        String gu = c.optString("githubUser", "");
                        if (!gu.isEmpty()) return gu;
                    }
                }
            }
            return alias == null ? "" : alias;
        } catch (Exception e) { return ""; }
    }

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