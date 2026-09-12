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
 * GitHub OAuth 授权器。
 *
 * 只用于首次授权和用户明确触发的重新授权。业务刷新、签到和定时任务的短期
 * Token 续期由 Engine 使用现有站点会话完成，不得调用本类建立新会话。
 */
public final class SilentAuth {

    public interface Callback {
        void onResult(boolean ok, boolean needUi, String user, String msg);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

    /* OAuth code 交换门禁。业务续期不经过这里。 */
    /* v0.6.7：全局「凭据交换」并发闸门——保证一次授权动作只交换一次 session。
     * IN_FLIGHT：某 accountKey 是否已有交换在途（跨 SilentAuth↔AuthActivity 共享）。
     * USED_CODES：已消费过的 OAuth code（一次性；重复回调直接丢弃，绝不二次交换）。 */
    private static final java.util.Set<String> IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> USED_CODES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 尝试为 accountKey + code 抢占交换权；成功=本次可交换，失败=已有在途或 code 已用。 */
    public static boolean acquireExchange(String accountKey, String code) {
        if (accountKey == null) accountKey = "";
        if (code == null) code = "";
        // code 一次性：已消费直接拒绝（幂等，防同一 code 二次交换）
        if (!code.isEmpty() && !USED_CODES.add(code)) return false;
        // 同一账号同时只允许一条交换在途
        if (!IN_FLIGHT.add(accountKey)) return false;
        return true;
    }
    /** 交换结束（成功/失败/超时）后释放账号在途标记；code 仍保留在 USED_CODES 中不可复用。 */
    public static void releaseExchange(String accountKey) {
        if (accountKey == null) accountKey = "";
        IN_FLIGHT.remove(accountKey);
    }
    /** v0.6.7：会话指纹——cookie 串的 SHA-256 前 8 位十六进制（脱敏，绝不输出 cookie 值）。 */
    public static String sessionFingerprint(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "(空)";
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) { return "(err)"; }
    }

    private SilentAuth() {}
    /** 线程安全入口，仅由首次授权或用户明确的重新授权调用。 */
    public static void run(Context ctx0, String siteKey, String accountKey, Callback cb) {
        if (ctx0 == null || cb == null) return;
        final Context app = ctx0.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> new Runner(app, siteKey, accountKey, cb).start());
    }

    public static boolean githubLoggedIn() {
        try {
            String ck = CookieManager.getInstance().getCookie("https://github.com");
            if (ck == null) return false;
            return ck.contains("user_session=") || ck.contains("logged_in=yes");
        } catch (Exception e) { return false; }
    }

    /** opus4.8 审计·B-02：从 OkHttp 响应头拼装 Cookie 头值。
     * 只保留有值的 cookie（删除态 Max-Age=0/空值跳过），形如 "session=xxx; other=yyy"。 */
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

    /* ================= JWT 剩余期判定 =================
     * 仅用于判断何时应通过现有 Refresh Cookie 续期短期 Token，不会触发 OAuth。 */
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

    /** token 空 / 已过期 / 即将过期 ⇒ 需要续期短期 Token。 */
    public static boolean needsExchange(String jwt) {
        if (jwt == null || jwt.isEmpty()) return true;
        long exp = expMs(jwt);
        if (exp <= 0) return false; // 非 JWT 或无法判断时交给业务接口的401判定
        return System.currentTimeMillis() + SKEW_MS >= exp;
    }

    private static final class Runner {
        private final Context ctx;
        private final String siteKey, accountKey;
        private final Callback cb;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Store store;

        private WebView wv;
        private String baseUrl = "", siteHost = "";
        /** 本轮 state；同域回调必须严格匹配后才允许交换。 */
        private volatile String expectedOauthState = "";
        private volatile boolean done = false;
        private volatile boolean exchanging = false;
        private Runnable watchdog;
        /* 当前离屏授权绑定的「站点×账号」Profile（降级=Default 时为 null） */
        private androidx.webkit.Profile mProfile;

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
                    /* v0.4.3（审计方案3）：WAF 拦截必须明确告知用户换节点，禁技术术语 */
                    String why = wafHit()
                            ? "站点防护拦截，请更换代理节点后重试；多次失败请稍后再试"
                            : "获取授权会话失败";
                    final String msg = why;
                    main.post(() -> finish(false, true, null, msg));
                    return;
                }
                final String cid = pair[0], state = pair[1];
                main.post(() -> startOffscreen(cid, state));
            }, "silent-auth-pre").start();
        }

        private void startOffscreen(String clientId, String state) {
            if (done) return;
            expectedOauthState = state == null ? "" : state;
            try {
                wv = new WebView(ctx); // 离屏运行，零 UI
                /* 需求3（opus4.8 审计）：绑定「站点×账号」专属 Profile。
                 * 必须在任何 getSettings/loadUrl 之前调用。该 Profile 的
                 * Cookie 与其他账号完全隔离且持久化 —— 账号授权一次后，
                 * 后台刷新/签到永远用自己的会话自动交换，无需再手动授权。 */
                mProfile = WebViewProfileUtil.bindProfile(wv,
                        WebViewProfileUtil.profileNameFor(siteKey, accountKey));
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
                    @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                        intercept(url);
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
                /* v0.6.6：交换请求一旦在途（exchanging=true），watchdog 不得判超时打断——
                 * justwoker 交换端点 <1s 返回，提前 finish 会让"其实已成功建 session"
                 * 的交换被误判超时→转手动→用新 code 再建一个 session→撞 AUTH_SESSION_LIMIT。
                 * 已在途则再宽限一轮，交给 exchange() 自己出成功/失败结果。 */
                watchdog = new Runnable() {
                    private int extends_ = 0;
                    @Override public void run() {
                        if (done) return;
                        if (exchanging && extends_ < 1) {
                            extends_++;
                            main.postDelayed(this, 12000);
                            return;
                        }
                        finish(false, true, null, "后台交换凭据超时");
                    }
                };
                /* C1（opus4.8 审计）：会话已登录且期望账号明确时，若卡在授权确认页
                 *（没人点击）说明后台无法推进 —— 8s 快速转人工，不空等 30s；
                 * 未登录/无法判定时保留 30s（登录跳转链路更长）。
                 * 需求3：会话判定按本账号 Profile（隔离分区），不再看全局。 */
                boolean fastFail = !expectGithubLogin().isEmpty()
                        && WebViewProfileUtil.githubLoggedIn(mProfile);
                main.postDelayed(watchdog, fastFail ? 8000 : 30000);

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
            OAuthCallback.Result r = OAuthCallback.parse(url, siteHost, expectedOauthState);
            if (r.shouldExchange()) {
                /* v0.6.7：全局闸门——同一 accountKey/同一 code 只允许一次交换。
                 * 抢占失败（已有在途或 code 已消费）说明本回调是重复触发，直接丢弃，
                 * 绝不二次建 session（just 站 AUTH_SESSION_LIMIT 根因）。 */
                if (!acquireExchange(accountKey, r.code)) {
                    try {
                        store.opLog(siteKey, accountKey, "后台凭据交换", "info",
                                "跳过重复交换", "本轮已有交换在途或该授权码已消费", "auto");
                    } catch (Exception ignored) {}
                    return true;
                }
                exchanging = true;
                try {
                    store.opLog(siteKey, accountKey, "后台凭据交换", "info",
                            "已拦截授权回调", r.safe + "；state 校验通过", "auto");
                } catch (Exception ignored) {}
                final String fc = r.code, fs = r.state;
                new Thread(() -> exchange("github", fc, fs), "silent-auth-exchange").start();
                return true;
            }
            if (r.badState()) {
                try {
                    store.opLog(siteKey, accountKey, "后台凭据交换", "err",
                            "拒绝异常授权回调", r.safe + "；state 缺失或不匹配", "auto");
                } catch (Exception ignored) {}
                finish(false, true, null, "授权回调校验失败，需要重新授权");
                return true;
            }
            if (r.missingCode()) {
                try {
                    store.opLog(siteKey, accountKey, "后台凭据交换", "err",
                            "未获取到授权码", r.safe, "auto");
                } catch (Exception ignored) {}
                finish(false, true, null, "未获取到授权码，需要重新授权");
                return true;
            }
            return false;
        }

        private void exchange(String provider, String code, String state) {
            Response resp = null;
            try {
                /* v0.6.7 会话指纹（旧）：交换前落库的 siteCookie 指纹，用于与交换后的新指纹对比。
                 * 判据：指纹仅用于确认新凭据是否变化，不证明旧会话已销毁。 */
                try {
                    JSONObject accF = store.findAccount(accountKey);
                    String oldCk = accF == null ? "" : accF.optString("siteCookie", "");
                    store.opLog(siteKey, accountKey, "后台凭据交换", "info",
                            "交换前凭据指纹", "cookieFP=" + sessionFingerprint(oldCk), "auto");
                } catch (Exception ignored) {}
                OkHttpClient c = client();
                Request.Builder rb = new Request.Builder()
                        .url(baseUrl + "/api/oauth/" + provider
                                + "?code=" + enc(code) + "&state=" + enc(state))
                        .header("Accept", "application/json")
                        .header("User-Agent", UA);
                resp = c.newCall(rb.build()).execute();
                int http = resp.code();
                String body = resp.body() != null ? resp.body().string() : "";
                /* opus4.8 审计·B-02：New API 系登录凭据是 Set-Cookie session（gin），
                 * access_token 是可选系统令牌（未生成为 JSON null）。必须抓 Set-Cookie。 */
                final String setCookie = extractCookies(resp.headers("Set-Cookie"));
                /* v0.6.6 诊断：SilentAuth 后台交换此前不记 HTTP 状态，导致 justwoker
                 * 的失败真因（如 409 AUTH_SESSION_LIMIT）在后台链路完全不可见。
                 * 记状态+body长度+setCookie布尔；非 2xx 记站点 code/message（不记 code/state/token/cookie 值）。 */
                boolean ok2xx = http >= 200 && http < 300;
                try {
                    String diag = "HTTP " + http + "；body=" + body.length() + "B；setCookie=" + !setCookie.isEmpty();
                    if (!ok2xx) {
                        String ec = "", em = "";
                        try {
                            JSONObject ej = new JSONObject(body);
                            ec = ej.optString("code", "");
                            em = ej.optString("message", "");
                        } catch (Exception ignored) {}
                        diag += "；code=" + (ec.isEmpty() ? "(无)" : ec)
                                + "；message=" + (em.isEmpty() ? "(无)" : em);
                    }
                    store.opLog(siteKey, accountKey, "后台凭据交换", ok2xx ? "info" : "err",
                            "后台交换响应", diag, "auto");
                } catch (Exception ignored) {}
                if (!body.trim().isEmpty()) {
                    JSONObject r = new JSONObject(body);
                    JSONObject d = r.optJSONObject("data");
                    if (r.optBoolean("success") && d != null) {
                        /* token 尽力而为：仅当为非空字符串才用（JSON null/缺失一律视为无）。
                         * 兼容三字段（New API 新旧版本）。 */
                        String token = "";
                        if (d.has("access_token") && !d.isNull("access_token")) {
                            Object at = d.get("access_token");
                            if (at instanceof String) {
                                String s = ((String) at).trim();
                                if (!s.isEmpty() && !"null".equals(s)) token = s;
                            }
                        }
                        if (token.isEmpty() && d.has("accessToken") && !d.isNull("accessToken")) {
                            Object at = d.get("accessToken");
                            if (at instanceof String) {
                                String s = ((String) at).trim();
                                if (!s.isEmpty() && !"null".equals(s)) token = s;
                            }
                        }
                        if (token.isEmpty() && d.has("token") && !d.isNull("token")) {
                            Object at = d.get("token");
                            if (at instanceof String) {
                                String s = ((String) at).trim();
                                if (!s.isEmpty() && !"null".equals(s)) token = s;
                            }
                        }
                        /* 身份标识：data 直接是用户对象（AgentRouter 型），
                         * 旧版在 data.user 里——两层都试。 */
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
                        /* B1 身份校验（opus4.8 复审·锚点分层）：
                         * 1) 强判据：响应含 github_id 且凭据已缓存 githubId → 不等即拒
                         *    （站内名 github_<站内id> 与 GitHub 名无关，不可比）。
                         * 2) 弱判据：无 github_id 数据 → 回退 username 比对（token 型旧路径）。
                         * 3) 都拿不到 → 跳过校验（不误拒）。 */
                        String want = expectGithubLogin();
                        String respGhId = "";
                        if (d.has("github_id") && !d.isNull("github_id")) {
                            Object gid = d.get("github_id");
                            respGhId = String.valueOf(gid).trim();
                            if ("null".equals(respGhId)) respGhId = "";
                        }
                        /* v0.3.8：锚点链扩至 github_user_id，再空则无锚点 */
                        if (respGhId.isEmpty() && d.has("github_user_id") && !d.isNull("github_user_id")) {
                            Object g2 = d.get("github_user_id");
                            if (g2 != null) {
                                respGhId = String.valueOf(g2).trim();
                                if ("null".equals(respGhId)) respGhId = "";
                            }
                        }
                        boolean mm = false;
                        String mmWhy = "";
                        if (!respGhId.isEmpty()) {
                            /* 静默路径无法查 GitHub API（主线程约束+限流），
                             * 仅用凭据已缓存的 githubId 判定；无缓存 → 跳过 */
                            String cachedId = "";
                            try {
                                JSONObject accX = store.findAccount(accountKey);
                                if (accX != null) {
                                    String cid = accX.optString("credentialId", "");
                                    if (!cid.isEmpty()) {
                                        JSONObject credJ = store.findCredential(cid);
                                        if (credJ != null) {
                                            cachedId = credJ.optString("githubId", "");
                                            if ("null".equals(cachedId)) cachedId = "";
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                            if (!cachedId.isEmpty() && !cachedId.equals(respGhId)) {
                                mm = true;
                                mmWhy = "GitHub ID 不符：期望 " + want + "(" + cachedId + ")，实际 " + respGhId;
                            }
                        } else if (!want.isEmpty() && !login.isEmpty()
                                && !want.trim().equalsIgnoreCase(login.trim())) {
                            /* v0.3.8（审计定稿）：无锚点时不再按用户名比对拒绝。
                             * Profile 隔离 + 首次授权确认框已保证身份；此处以
                             * siteUserId 静默比对兜底（响应 data.id vs 账号已存值），
                             * 一致（或双方皆无可比值）才接受，且不弹任何页面。 */
                            String accUid = "";
                            try {
                                JSONObject accU = store.findAccount(accountKey);
                                if (accU != null) {
                                    accUid = accU.optString("siteUserId", "");
                                    if ("null".equals(accUid)) accUid = "";
                                }
                            } catch (Exception ignored) {}
                            String respUid = "";
                            Object sidO = d.opt("id");
                            if (sidO != null) {
                                respUid = String.valueOf(sidO).trim();
                                if ("null".equals(respUid)) respUid = "";
                            }
                            boolean idOk = accUid.isEmpty() || respUid.isEmpty()
                                    || accUid.equals(respUid);
                            if (!idOk) {
                                mm = true;
                                mmWhy = "站内用户ID 不符：已存 " + accUid + "，本次 " + respUid;
                            }
                            /* idOk 时 mm=false，静默接受（不弹页面） */
                        }
                        if (mm) {
                            try {
                                store.opLog(siteKey, accountKey, "后台凭据交换", "err",
                                        "GitHub 身份不符，已拒绝写入防串号", mmWhy, "auto");
                            } catch (Exception ignored) {}
                            final String gl = (login == null || login.isEmpty()) ? respGhId : login;
                            main.post(() -> finish(false, true, gl,
                                    "后台会话账号与所选不符，请手动授权切换"));
                            return;
                        }
                        /* 凭据有效性：cookie 型站必须有 Set-Cookie；token 型站有 token。
                         * 两者皆无 → 失败（不再把 "null" 假成功）。 */
                        final String fLogin = login;
                        if (setCookie.isEmpty() && token.isEmpty()) {
                            try {
                                store.opLog(siteKey, accountKey, "后台凭据交换", "err",
                                        "授权响应无 Cookie 也无 token",
                                        "data keys 见授权页日志", "auto");
                            } catch (Exception ignored) {}
                            main.post(() -> finish(false, true, fLogin, "授权响应缺少会话凭据"));
                            return;
                        }
                        /* 落库：token（可空）+ siteCookie（cookie 型站真凭据） */
                        try {
                            /* v0.6.7 会话指纹：落库 cookie 的 SHA-256 前 8 位（脱敏，不含值）。
                             * 测试判据：指纹变化仅表示获得不同凭据，不证明旧会话已销毁。 */
                            if (!setCookie.isEmpty()) {
                                String fp = sessionFingerprint(setCookie);
                                store.opLog(siteKey, accountKey, "后台凭据交换", "info",
                                        "新会话凭据指纹", "cookieFP=" + fp, "auto");
                            }
                            JSONObject patch = new JSONObject()
                                    .put("siteKey", siteKey)
                                    .put("updatedAt", System.currentTimeMillis());
                            if (!token.isEmpty()) patch.put("token", token);
                            if (!setCookie.isEmpty()) patch.put("siteCookie", setCookie);
                            if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
                            Object sidO2 = d.opt("id");
                            if (sidO2 != null) {
                                String su2 = String.valueOf(sidO2).trim();
                                if (!su2.isEmpty() && !"null".equals(su2)) patch.put("siteUserId", su2);
                            }
                            if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
                            Object sidO3 = d.opt("id");
                            if (sidO2 != null) {
                                String su2 = String.valueOf(sidO3).trim();
                                if (!su2.isEmpty() && !"null".equals(su2)) patch.put("siteUserId", su2);
                            }
                            if (login != null && !login.isEmpty()) patch.put("githubAccount", login);
                            store.patchAccount(accountKey, patch);
                        } catch (Exception ignored) {}
                        /* 需求3：落盘本账号 Profile 会话 */
                        WebViewProfileUtil.flush(mProfile);
                        final String fl = login;
                        main.post(() -> finish(true, false, fl, "凭据自动交换成功"));
                        return;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (resp != null) try { resp.close(); } catch (Exception ignored) {}
            }
            /* v0.6.7：交换失败不再把 exchanging 置回——code 已消费，退回会让后续
             * 回调重新交换、二次建 session。失败由 finish 收尾并释放在途标记。 */
            main.post(() -> finish(false, true, null, "凭据交换响应失败"));
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
                if (clientId.isEmpty()) {
                    if (lastBodyWaf) fetchWafBlocked = true;
                    return null;
                }
                String state = fetchStateProbe(c);
                if (state == null || state.isEmpty()) {
                    if (lastBodyWaf) fetchWafBlocked = true;
                    return null;
                }
                return new String[]{ clientId, state };
            } catch (Exception e) { return null; }
        }

        /* v0.4.3（glm-5.3 审计方案5）：gorouter 等站 /api/oauth/state 404，
         * 按候选路径探测，成功即缓存到站点 meta（oauthStatePath），下次直接用。 */
        private static final String[] STATE_PATHS = {
                "/api/oauth/state", "/v1/oauth/state", "/api/user/oauth/state" };
        private volatile boolean lastBodyWaf = false;
        private volatile boolean fetchWafBlocked = false;
        boolean wafHit() { return fetchWafBlocked; }
        private String fetchStateProbe(OkHttpClient c) {
            String cached = store.siteMeta(siteKey, "oauthStatePath", "");
            java.util.ArrayList<String> order = new java.util.ArrayList<>();
            if (!cached.isEmpty()) order.add(cached);
            for (String pth : STATE_PATHS) if (!order.contains(pth)) order.add(pth);
            for (String pth : order) {
                lastBodyWaf = false;
                String s = postState(c, pth);
                if (s == null) s = getState(c, pth);
                if (s != null && !s.isEmpty()) {
                    if (!pth.equals(cached)) store.putSiteMeta(siteKey, "oauthStatePath", pth);
                    return s;
                }
                /* WAF 拦截：立即中止探测，避免放大请求触发更严限流（审计风险边界） */
                if (lastBodyWaf) { fetchWafBlocked = true; return null; }
            }
            return null;
        }
        private String postState(OkHttpClient c, String path) {
            Response r = null;
            try {
                r = c.newCall(new Request.Builder().url(baseUrl + "/api/oauth/state")
                        .header("Accept", "application/json").header("User-Agent", UA)
                        .post(RequestBody.create("{\"provider\":\"github\",\"intent\":\"login\"}",
                                MediaType.parse("application/json"))).build()).execute();
                if (r.code() != 200) return null;
                String body = r.body() != null ? r.body().string() : "";
                if (Engine.wafBlocked(body)) { lastBodyWaf = true; return null; }
                return pickState(body);
            } catch (Exception e) { return null; }
            finally { if (r != null) try { r.close(); } catch (Exception ignored) {} }
        }

        private String getState(OkHttpClient c, String path) {
            Response r = null;
            try {
                r = c.newCall(new Request.Builder().url(baseUrl + path + "?mode=login")
                        .header("Accept", "application/json").header("User-Agent", UA).build()).execute();
                if (r.code() != 200) return null;
                String body = r.body() != null ? r.body().string() : "";
                if (Engine.wafBlocked(body)) { lastBodyWaf = true; return null; }
                return pickState(body);
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

        /** 带身份校验的落库（opus4.8 审计方案 B1）：通过校验并写入返回 true；
         * 会话账号与所选账号不符（串号风险）返回 false 且绝不写库。 */
        private boolean saveTokenChecked(String token, String login) {
            String expect = expectGithubLogin();
            if (expect != null && !expect.isEmpty()
                    && login != null && !login.isEmpty()
                    && !expect.trim().equalsIgnoreCase(login.trim())) {
                try {
                    store.opLog(siteKey, accountKey, "后台凭据交换", "err",
                            "GitHub 身份不符，已拒绝写入防串号",
                            "期望 " + expect + "，实际 " + login + "；需手动授权切换账号", "auto");
                } catch (Exception ignored) {}
                return false;
            }
            try {
                JSONObject patch = new JSONObject()
                        .put("siteKey", siteKey)
                        .put("token", token)
                        .put("updatedAt", System.currentTimeMillis());
                if (login != null && !login.isEmpty()) patch.put("githubAccount", login);
                store.patchAccount(accountKey, patch);
                return true;
            } catch (Exception e) { return false; }
        }

        private void saveToken(String token, String login) {
            saveTokenChecked(token, login);
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
            /* v0.6.7：交换链路结束——无论成功/失败/超时，释放该账号的在途标记。
             * 注意：不清 USED_CODES，本轮 code 已一次性消费，永不复用。 */
            releaseExchange(accountKey);
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