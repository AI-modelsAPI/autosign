package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
 * SilentAuth（v0.2.3）— 全后台静默交换凭据（获取新 token），绝不弹任何窗口。
 *
 * 【核心设计（按用户要求）】
 *   完全抛弃并删除无效的 /api/user/auth/refresh 续期逻辑。
 *   凡是需要 token 的地方（刷新/签到），只要 token 过期或请求遇 401，
 *   直接在纯后台（离屏 WebView）重新走一次 GitHub OAuth code 交换，
 *   拿到新鲜有效的 access_token。
 *   因为系统已存有 GitHub 会话 Cookie，整个 OAuth 重定向过程无需人工介入，
 *   完全在后台几百毫秒内静默完成。
 */
public final class SilentAuth {

    public interface Callback {
        void onResult(boolean ok, boolean needUi, String user, String msg);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    /* ================= 并发合并 / 失败冷却 =================
     * 为什么必须有：status() 一次刷新会连打 4 个接口（self / checkin?month / data/self / log/self）。
     * token 过期时若每个 401 都各自跑一次完整 OAuth，就是 4 次 flow_token 申请 + 4 次 30s 阻塞，
     * 站点直接 429，用户看到的是「刷新卡住然后全失败」。
     *   - 同账号串行（lockOf）：第二个请求等第一个换完，进来发现刚换过就直接复用新 token
     *   - 刚成功 REUSE_MS 内不再交换
     *   - 失败后 FAIL_COOLDOWN_MS 内不再自动交换（用户手动点授权不受此限）
     */
    private static final long REUSE_MS = 8 * 1000L;
    private static final long FAIL_COOLDOWN_MS = 90 * 1000L;
    private static final java.util.HashMap<String, Object> LOCKS = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> LAST_OK = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> FAIL_UNTIL = new java.util.HashMap<>();

    private SilentAuth() {}

    private static Object lockOf(String key) {
        synchronized (LOCKS) {
            Object o = LOCKS.get(key);
            if (o == null) { o = new Object(); LOCKS.put(key, o); }
            return o;
        }
    }

    /** 自动交换是否处于失败冷却期（供 UI 决定要不要直接提示重新授权） */
    public static boolean inCooldown(String accountKey) {
        synchronized (FAIL_UNTIL) {
            Long until = FAIL_UNTIL.get(accountKey);
            return until != null && System.currentTimeMillis() < until;
        }
    }

    /** 用户手动触发授权时清掉冷却，让他立刻能再试一次 */
    public static void clearCooldown(String accountKey) {
        synchronized (FAIL_UNTIL) { FAIL_UNTIL.remove(accountKey); }
    }

    /** 线程安全入口 */
    public static void run(Context ctx0, String siteKey, String accountKey, Callback cb) {
        if (ctx0 == null || cb == null) return;
        final Context app = ctx0.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> new Runner(app, siteKey, accountKey, cb).start());
    }

    /**
     * 同步/阻塞版凭据交换（只能在子线程调用 —— 内部要等主线程跑 WebView）。
     * @return 新 token；被冷却拦下或失败时返回空串
     */
    public static String exchangeSync(Context ctx, Store store, JSONObject site, String accountKey) {
        if (ctx == null || store == null || site == null || accountKey == null) return "";
        if (Looper.myLooper() == Looper.getMainLooper()) return "";   // 主线程调用会自锁，直接拒绝
        final String sKey = site.optString("key", "");

        synchronized (lockOf(accountKey)) {
            /* 刚刚已有别的请求换过 → 直接复用，不再打扰站点 */
            Long okAt;
            synchronized (LAST_OK) { okAt = LAST_OK.get(accountKey); }
            if (okAt != null && System.currentTimeMillis() - okAt < REUSE_MS) {
                JSONObject acc = store.findAccount(accountKey);
                return acc == null ? "" : acc.optString("token", "");
            }
            if (inCooldown(accountKey)) return "";

            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] result = { "" };
            run(ctx, sKey, accountKey, (ok, needUi, user, msg) -> {
                if (ok) {
                    JSONObject acc = store.findAccount(accountKey);
                    if (acc != null) result[0] = acc.optString("token", "");
                }
                latch.countDown();
            });
            try { latch.await(35, TimeUnit.SECONDS); } catch (Exception ignored) {}

            if (result[0].isEmpty()) {
                synchronized (FAIL_UNTIL) {
                    FAIL_UNTIL.put(accountKey, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
                }
            } else {
                synchronized (LAST_OK) { LAST_OK.put(accountKey, System.currentTimeMillis()); }
                clearCooldown(accountKey);
            }
            return result[0];
        }
    }

    public static boolean githubLoggedIn() {
        try {
            String ck = CookieManager.getInstance().getCookie("https://github.com");
            if (ck == null) return false;
            return ck.contains("user_session=") || ck.contains("logged_in=yes");
        } catch (Exception e) { return false; }
    }

    /* ================= JWT 剩余期判定 =================
     * TokenKeeper 已删除，但「这个 token 还能用多久」仍需判断：
     * 签到/刷新前若发现剩余不足 SKEW，直接先换新的，别等 401 再补救（省一次往返）。 */
    private static final long SKEW_MS = 60 * 1000L;

    /** JWT exp（毫秒）；解析失败返回 0（视为无法判断，不主动换） */
    public static long expMs(String jwt) {
        try {
            if (jwt == null) return 0;
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            String pl = parts[1];
            int pad = (4 - pl.length() % 4) % 4;
            for (int i = 0; i < pad; i++) pl += "=";
            byte[] raw = android.util.Base64.decode(pl,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            long exp = new JSONObject(new String(raw, "UTF-8")).optLong("exp", 0);
            return exp > 0 ? exp * 1000L : 0;
        } catch (Exception e) { return 0; }
    }

    /** 剩余分钟（UI/日志用；无 exp 返回 -1） */
    public static double remainMinutes(String jwt) {
        long exp = expMs(jwt);
        if (exp <= 0) return -1;
        return (exp - System.currentTimeMillis()) / 60000.0;
    }

    /** token 空 / 已过期 / 即将过期 ⇒ 需要先换凭据 */
    public static boolean needsExchange(String jwt) {
        if (jwt == null || jwt.isEmpty()) return true;
        long exp = expMs(jwt);
        if (exp <= 0) return false;                 // 解不出 exp：交给 401 兜底
        return System.currentTimeMillis() + SKEW_MS >= exp;
    }

    /**
     * 保证账号 token 可用（子线程调用）：过期就先在后台换一次。
     * @return 可用 token；换不到返回原值（可能为空）
     */
    public static String ensureToken(Context ctx, Store store, JSONObject site, String accountKey) {
        if (store == null || site == null || accountKey == null) return "";
        JSONObject acc = store.findAccount(accountKey);
        String token = acc == null ? "" : acc.optString("token", "");
        if (!needsExchange(token)) return token;
        String fresh = exchangeSync(ctx, store, site, accountKey);
        return fresh.isEmpty() ? token : fresh;
    }

    private static final class Runner {
        private final Context ctx;
        private final String siteKey, accountKey;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Store store;

        private WebView wv;
        private String baseUrl = "", siteHost = "";
        private volatile boolean done = false;
        private volatile boolean exchanging = false;
        private Runnable watchdog;

        Runner(Context ctx, String siteKey, String accountKey, Callback cb) {
            this.ctx = ctx; this.siteKey = siteKey; this.accountKey = accountKey; this.cb = cb;
            this.store = new Store(ctx);
        }

        void start() {
            JSONObject site = store.findSite(siteKey);
            if (site == null) { finish(false, false, null, "站点不存在"); return; }
            baseUrl = site.optString("baseUrl", "").replaceAll("/+$", "");
            if (baseUrl.isEmpty()) { finish(false, false, null, "站点 baseUrl 为空"); return; }
            try { siteHost = new java.net.URL(baseUrl).getHost(); } catch (Exception e) { siteHost = ""; }

            new Thread(() -> {
                String[] pair = fetchStateAndClient();
                if (pair == null) {
                    main.post(() -> finish(false, true, null, "获取授权会话失败"));
                    return;
                }
                final String cid = pair[0], state = pair[1];
                main.post(() -> startOffscreen(cid, state));
            }, "silent-auth-pre").start();
        }

        private void startOffscreen(String clientId, String state) {
            if (done) return;
            try {
                wv = new WebView(ctx); // 离屏运行，零 UI
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(UA);
                try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true); } catch (Exception ignored) {}
                wv.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        if (req == null || req.getUrl() == null) return false;
                        return intercept(req.getUrl().toString());
                    }
                    @Override public void onPageFinished(WebView v, String url) {
                        if (done || url == null) return;
                        if (intercept(url)) return;
                        String u = url.toLowerCase(java.util.Locale.US);
                        if (u.startsWith("https://github.com/login")
                                || u.contains("/session")
                                || u.contains("two-factor")
                                || u.contains("verified-device")
                                || u.contains("sudo")) {
                            finish(false, true, null, "GitHub 会话过期，需要手动登录一次");
                        }
                    }
                });
                watchdog = () -> { if (!done) finish(false, true, null, "后台交换凭据超时"); };
                main.postDelayed(watchdog, 30000);

                applyProxyThen(() -> {
                    if (done || wv == null) return;
                    /* 关键：GitHub OAuth 支持 login 参数 —— 强制以期望账号授权。
                     * 若 WebView 里当前登录的是别的 GitHub 账号，GitHub 会要求
                     * 切换/重新登录到该账号，从根上避免「选 AI-modelsAPI 却用
                     * zgj19810121 授权」导致的站点年龄校验失败。 */
                    String want = expectGithubLogin();
                    String authUrl = "https://github.com/login/oauth/authorize?client_id=" + enc(clientId)
                            + "&state=" + enc(state) + "&scope=user:email"
                            + (want.isEmpty() ? "" : ("&login=" + want));
                    wv.loadUrl(authUrl);
                });
            } catch (Throwable t) {
                finish(false, true, null, "后台交换凭据异常: " + t.getMessage());
            }
        }

        private boolean intercept(String url) {
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
                String code = param(u.getQuery(), "code");
                String st = param(u.getQuery(), "state");
                if (code.isEmpty()) return false;
                exchanging = true;
                final String fp = provider, fc = code, fs = st;
                new Thread(() -> exchange(fp, fc, fs), "silent-auth-exchange").start();
                return true;
            } catch (Exception e) { return false; }
        }

        private void exchange(String provider, String code, String state) {
            Response resp = null;
            try {
                OkHttpClient c = client();
                Request.Builder rb = new Request.Builder()
                        .url(baseUrl + "/api/oauth/" + provider
                                + "?code=" + enc(code) + "&state=" + enc(state))
                        .header("Accept", "application/json")
                        .header("User-Agent", UA);
                resp = c.newCall(rb.build()).execute();
                String body = resp.body() != null ? resp.body().string() : "";
                if (!body.trim().isEmpty()) {
                    JSONObject r = new JSONObject(body);
                    JSONObject d = r.optJSONObject("data");
                    if (r.optBoolean("success") && d != null) {
                        String token = d.optString("access_token", d.optString("accessToken", ""));
                        String login = null;
                        JSONObject usr = d.optJSONObject("user");
                        if (usr != null) {
                            login = usr.optString("username", "");
                            if (login.isEmpty()) login = usr.optString("login", "");
                        }
                        if (!token.isEmpty()) {
                            saveToken(token, login);
                            final String fl = login;
                            main.post(() -> finish(true, false, fl, "凭据自动交换成功"));
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (resp != null) try { resp.close(); } catch (Exception ignored) {}
            }
            main.post(() -> { exchanging = false; finish(false, true, null, "凭据交换响应失败"); });
        }

        private String[] fetchStateAndClient() {
            String clientId = "";
            try {
                OkHttpClient c = client();
                Response st = null;
                try {
                    st = c.newCall(new Request.Builder().url(baseUrl + "/api/status")
                            .header("Accept", "application/json").header("User-Agent", UA).build()).execute();
                    if (st.code() == 200) {
                        String b = st.body() != null ? st.body().string() : "";
                        if (!b.trim().isEmpty()) {
                            JSONObject d = new JSONObject(b).optJSONObject("data");
                            if (d != null) {
                                clientId = d.optString("github_client_id", "");
                                String tsk = d.optString("turnstile_site_key", "");
                                if (!tsk.isEmpty()) store.putSiteMeta(siteKey, "turnstileSiteKey", tsk);
                                long unit = d.optLong("quota_per_unit", 0);
                                if (unit > 0) store.putSiteMeta(siteKey, "quotaPerUnit", unit);
                            }
                        }
                    }
                } finally { if (st != null) try { st.close(); } catch (Exception ignored) {} }
                if (clientId.isEmpty()) return null;

                String state = postState(c);
                if (state == null) state = getState(c);
                if (state == null || state.isEmpty()) return null;
                return new String[]{ clientId, state };
            } catch (Exception e) { return null; }
        }

        private String postState(OkHttpClient c) {
            Response r = null;
            try {
                r = c.newCall(new Request.Builder().url(baseUrl + "/api/oauth/state")
                        .header("Accept", "application/json").header("User-Agent", UA)
                        .post(RequestBody.create("{\"provider\":\"github\",\"intent\":\"login\"}",
                                MediaType.parse("application/json"))).build()).execute();
                if (r.code() != 200) return null;
                return pickState(r.body() != null ? r.body().string() : "");
            } catch (Exception e) { return null; }
            finally { if (r != null) try { r.close(); } catch (Exception ignored) {} }
        }

        private String getState(OkHttpClient c) {
            Response r = null;
            try {
                r = c.newCall(new Request.Builder().url(baseUrl + "/api/oauth/state?mode=login")
                        .header("Accept", "application/json").header("User-Agent", UA).build()).execute();
                if (r.code() != 200) return null;
                return pickState(r.body() != null ? r.body().string() : "");
            } catch (Exception e) { return null; }
            finally { if (r != null) try { r.close(); } catch (Exception ignored) {} }
        }

        private static String pickState(String body) {
            try {
                if (body == null || body.trim().isEmpty()) return null;
                JSONObject j = new JSONObject(body);
                if (!j.optBoolean("success")) return null;
                Object d = j.opt("data");
                if (d instanceof String) return (String) d;
                if (d instanceof JSONObject) return ((JSONObject) d).optString("flow_token", null);
            } catch (Exception ignored) {}
            return null;
        }

        private void saveToken(String token, String login) {
            try {
                JSONObject patch = new JSONObject()
                        .put("siteKey", siteKey)
                        .put("token", token)
                        .put("updatedAt", System.currentTimeMillis());
                if (login != null && !login.isEmpty()) patch.put("githubAccount", login);
                store.patchAccount(accountKey, patch);
            } catch (Exception ignored) {}
        }

        /** 该账号期望的 GitHub 登录名：账号绑定凭据的 githubUser，回退别名；空=无法确定 */
        private String expectGithubLogin() {
            try {
                JSONObject acc = store.findAccount(accountKey);
                if (acc == null) return "";
                String cid = acc.optString("credentialId", "");
                if (!cid.isEmpty()) {
                    JSONObject c = store.findCredential(cid);
                    if (c != null) {
                        String gu = c.optString("githubUser", "");
                        if (!gu.isEmpty()) return gu;
                    }
                }
                String al = acc.optString("alias", "");
                return al == null ? "" : al;
            } catch (Exception e) { return ""; }
        }

        private synchronized void finish(boolean ok, boolean needUi, String user, String msg) {
            if (done) return;
            done = true;
            if (watchdog != null) main.removeCallbacks(watchdog);
            final WebView w = wv; wv = null;
            main.post(() -> {
                try {
                    if (w != null) { w.stopLoading(); w.loadUrl("about:blank"); w.destroy(); }
                } catch (Exception ignored) {}
            });
            try {
                store.opLog(siteKey, accountKey, "后台凭据交换",
                        ok ? "ok" : (needUi ? "info" : "err"), msg, "", "auto");
            } catch (Exception ignored) {}
            final String fu = user == null ? "" : user;
            final String fm = msg == null ? "" : msg;
            main.post(() -> { try { cb.onResult(ok, needUi, fu, fm); } catch (Exception ignored) {} });
        }

        private OkHttpClient client() {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS);
            try {
                JSONObject p = store.config().optJSONObject("proxy");
                if (p != null && p.optBoolean("enabled")) {
                    String host = p.optString("host", "127.0.0.1");
                    int port = p.optInt("port", 10808);
                    if (ProxyDetect.reachable(host, port, 1200)) {
                        b.proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                new java.net.InetSocketAddress(host, port)));
                    }
                }
            } catch (Exception ignored) {}
            return b.build();
        }

        private void applyProxyThen(Runnable then) {
            JSONObject p;
            try { p = store.config().optJSONObject("proxy"); } catch (Exception e) { p = null; }
            boolean enabled = p != null && p.optBoolean("enabled");
            if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                then.run();
                return;
            }
            final String host = p.optString("host", "127.0.0.1");
            final int port = p.optInt("port", 10808);
            new Thread(() -> {
                boolean alive = ProxyDetect.reachable(host, port, 1500);
                main.post(() -> {
                    if (done) return;
                    if (!alive) { then.run(); return; }
                    try {
                        ProxyConfig pc = new ProxyConfig.Builder()
                                .addProxyRule("socks5://" + host + ":" + port)
                                .addDirect().build();
                        ProxyController.getInstance().setProxyOverride(pc, Runnable::run, then);
                    } catch (Exception e) { then.run(); }
                });
            }, "silent-auth-proxy").start();
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

        private static String enc(String s) {
            try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
            catch (Exception e) { return s == null ? "" : s; }
        }
    }
}