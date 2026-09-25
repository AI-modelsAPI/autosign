package icu.justwoker.justsign;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 纯 HTTP 业务引擎。
 *
 * 授权生命周期：
 *   - 旧式站点复用 session Cookie；
 *   - 新版站点用 new_api_refresh Cookie 调 /api/user/auth/refresh 续期短期访问令牌；
 *   - 刷新、签到和定时任务绝不触发 GitHub OAuth。OAuth 只属于首次授权和用户手动重授权。
 */
public class Engine {
    public static final long QUOTA_PER_UNIT_DEFAULT = 500000L;
    /* v1.0.6：去掉 UA 中的 "; wv" 与 Version/4.0 标记。
     * 实测阿里云 WAF 对 WebView 特征 UA 更易触发 JS 质询；改为标准 Chrome Mobile UA，
     * 与 OffscreenLogout/OffscreenCheckin 的 UA 保持同族，避免同账号 UA 前后跳变。 */
    private static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private final Store store;
    private final Context ctx;
    private final OkHttpClient plain = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    /** 最近一次网络失败原因（供错误提示带出真实原因） */
    private volatile String lastError = "";
    /** 429（WAF/限流）自动换端口的冷却：60s 内不重复切换，避免来回横跳 */
    private static final long PROXY_SWITCH_COOLDOWN_MS = 60000L;
    private volatile long lastProxySwitchAt = 0L;
    public Engine(Context c) { store = new Store(c); ctx = c.getApplicationContext(); }
    /* ================= HTTP ================= */
    private JSONObject call(JSONObject site, String token, String method, String path) throws Exception {
        return call(site, token, "", "", method, path);
    }
    /* opus4.8 审计·B-03：cookie 型站点（AgentRouter 等 New API 变体）支持。
     * cookie 为空时走旧签名（纯 Bearer），完全向后兼容。 */
    private JSONObject call(JSONObject site, String token, String cookie, String accSiteUserId, String method, String path) throws Exception {
        return call(site, token, cookie, accSiteUserId, method, path, null);
    }
    /** v0.6.0：带 JSON body 版 */
    private JSONObject call(JSONObject site, String token, String cookie, String accSiteUserId, String method, String path, String jsonBody) throws Exception {
        JSONObject proxy = store.config().optJSONObject("proxy");
        boolean useProxy = proxy != null && proxy.optBoolean("enabled");
        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) throw new Exception("站点 baseUrl 为空");
        String url = base + path;
        lastError = "";
        String siteUserId = "";
        try {
            /* 站内用户ID 从账号读（授权响应 data.id 落库），随请求透传 */
            if (accSiteUserId != null) siteUserId = accSiteUserId;
        } catch (Exception ignored) {}
        JSONObject r = attempt(url, token, cookie, siteUserId, method, buildClient(useProxy, proxy), jsonBody);
        // A transport failure may have happened AFTER a write reached the server.
        // Never replay writes (including side-effecting GETs), nor evade a 429 by switching exits.
        if (r == null && useProxy && ReadFallback.allowed(method, path))
            r = attempt(url, token, cookie, siteUserId, method, plain, jsonBody);
        if (r == null) throw new Exception("网络请求失败（代理与直连均不可达）"
                + (lastError.isEmpty() ? "" : ": " + lastError));
        return r;
    }
    /** 429 后探测其它存活本地代理端口并持久化切换；无可用备用返回 null */
    private JSONObject switchToBackupProxy(JSONObject cur) {
        long now = System.currentTimeMillis();
        if (now - lastProxySwitchAt < PROXY_SWITCH_COOLDOWN_MS) return null;
        lastProxySwitchAt = now;
        try {
            String curHost = cur.optString("host", "127.0.0.1");
            int curPort = cur.optInt("port", 10808);
            java.util.ArrayList<JSONObject> list = ProxyDetect.scan(ctx);
            for (JSONObject p : list) {
                String h = p.optString("host", "");
                int port = p.optInt("port", 0);
                if (port <= 0 || !p.optBoolean("alive", false)) continue;
                if (h.equals(curHost) && port == curPort) continue;  // 不是备用
                JSONObject cfg = store.config();
                JSONObject pc = cfg.optJSONObject("proxy");
                if (pc == null) pc = new JSONObject();
                pc.put("enabled", true).put("type", "socks5")
                        .put("host", h).put("port", port);
                cfg.put("proxy", pc);
                store.saveConfig(cfg);
                return pc;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static final String AUTH_LOG_SCHEMA = "auth-v2";
    /** 每账号独立续期锁；Refresh Cookie 会轮换，禁止并发消费同一个旧值。 */
    private static final ConcurrentHashMap<String, Object> REFRESH_LOCKS = new ConcurrentHashMap<>();
    private static Object refreshLock(String accountKey) {
        return REFRESH_LOCKS.computeIfAbsent(accountKey, k -> new Object());
    }

    /**
     * v1.1.14 每账号「操作级」事务锁（类似 git 的 index.lock）。
     * 真机实证：手动刷新与定时签到/定时刷新并发跑，各自独立走「会话续期」，
     * 而站点用一次性轮换 Refresh Cookie —— 一个流程刚轮换、旧 Cookie 即作废，
     * 另一个仍拿旧值请求 → 自伤式 401 → 误报「登录已过期」（00:52:01 刷新成功 $410，
     * 00:52:03 又被并发的定时刷新打出 401 覆盖）。refreshLock 只锁「换 token」那一小步，
     * 覆盖不到整条 status/checkin/logout 操作。此锁把「同一账号的一次完整业务操作」串行化：
     * 锁获取顺序恒为 opLock → refreshLock，绝不反向，无死锁；同时天然消除并发重复请求。
     */
    private static final ConcurrentHashMap<String, Object> OP_LOCKS = new ConcurrentHashMap<>();
    private static Object opLock(String accountKey) {
        return OP_LOCKS.computeIfAbsent(accountKey == null ? "" : accountKey, k -> new Object());
    }

    /**
     * 带凭据的业务请求。短期令牌过期时只续期当前站点会话，绝不发起 OAuth。
     * 网络错误、403、429、5xx 等保持原错误，不得误判成登录失效。
     */
    public JSONObject callWithAuth(JSONObject site, String accountKey, String method, String path) throws Exception {
        return callWithAuth(site, accountKey, method, path, null, true);
    }
    public JSONObject callWithAuth(JSONObject site, String accountKey, String method, String path, String jsonBody) throws Exception {
        return callWithAuth(site, accountKey, method, path, jsonBody, true);
    }
    /**
     * @param allowReauth 只读兜底仍救不回时，是否允许对 linuxdo 站尝试静默重授权重建会话后重试一次。
     *   重试时传 false 以防循环。所有业务调用（Key 列表/新建/删除/划转/日志/刷新）默认 true——
     *   v1.1.16 修复：此前静默重授权只写在 statusLocked（刷新链），Key 列表等走 callWithAuth 的
     *   业务请求在会话失效时只有「只读离屏」一层兜底，而只读救不回已过期的会话 → 恒 503。
     *   真机日志实证 05:40~05:51 AnyRouter Key 列表连续 503、只读兜底「未拿到可用数据」，同期
     *   05:54 刷新链靠静默重授权成功重建会话刷出 $154.62——正是这条不对称。现把重授权收敛到
     *   callWithAuth 这个所有业务请求的必经点，Key 列表等一并获得「原生→只读→静默重授权→重试」完整自愈链。
     */
    public JSONObject callWithAuth(JSONObject site, String accountKey, String method, String path,
                                   String jsonBody, boolean allowReauth) throws Exception {
        JSONObject response = null;
        Exception failure = null;
        try {
            response = callWithAuthNative(site, accountKey, method, path, jsonBody);
        } catch (Exception e) {
            failure = e;
        }
        /* v1.1.24：瞬时 WAF-503 快速原生重试。真机实证——同一会话同一套原生 OkHttp，
         * /api/token/ 拿到 HTTP 200、13 秒后 /api/user/self 却 503，且 08:54 self 原生直接 200——
         * 证明这是「按端点/瞬时」的 WAF 抖动，不是会话失效。此前一遇 503 就跳到离屏(23s)+
         * 静默重授权(30s)，既慢又无谓（会话明明活着，Key 列表 200 就是铁证）。现对幂等 GET
         * 先做至多 2 次廉价原生重试（短退避），一旦拿到「无需重试的返回」（非 503/非 WAF，
         * 如 200 成功或确定性错误）立即停止；仍是 503/WAF 才落到离屏兜底。这直接回答用户三问：
         * ①key成功/刷新失败无代码差异，是瞬时WAF ②会话不需重建 ③慢就是这条重试链、现按需短路。 */
        if (allowReauth && failure == null && response != null
                && ReadFallback.allowed(method, path) && "GET".equalsIgnoreCase(method)) {
            int h0 = response.optInt("http");
            boolean wafish = h0 == 503 || Engine.wafBlocked(response.optString("body", ""));
            long[] backoff = {600L, 1000L};
            for (int i = 0; wafish && i < backoff.length; i++) {
                try { Thread.sleep(backoff[i]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                JSONObject retry;
                try { retry = callWithAuthNative(site, accountKey, method, path, jsonBody); }
                catch (Exception e) { break; }   // 传输层异常：交给下方兜底/抛出，不在此消化
                response = retry;
                int rh = retry.optInt("http");
                wafish = rh == 503 || Engine.wafBlocked(retry.optString("body", ""));
                if (!wafish) {   // 拿到无需重试的返回：立即停止
                    store.opLog(site.optString("key"), accountKey, "瞬时重试", "info",
                            "原生重试第" + (i + 1) + "次已越过瞬时防护",
                            "path=" + path.split("\\?", 2)[0] + "; http=" + rh, "auto");
                    break;
                }
            }
        }
        if (ReadFallback.shouldTry(method, path, response, failure)) {
            JSONObject acc = store.findAccount(accountKey);
            String uid = acc == null ? "" : SessionFence.userIdOf(acc);
            String reason = failure == null ? "http=" + (response == null ? 0 : response.optInt("http"))
                    : ReadFallback.category(failure);
            store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "info",
                    "原生请求失败，转离屏验证", "path=" + path.split("\\?", 2)[0]
                    + "; reason=" + reason + "; oauth=false; maxAttempts=1", "auto");
            try {
                JSONObject recovered = OffscreenReadRunner.read(ctx, site.optString("baseUrl"),
                        site.optString("key"), accountKey, uid,
                        path, site.optString("baseUrl"), 25);
                if (ReadFallback.usableResponse(path, recovered, uid)) {
                    store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "info",
                            "离屏读取成功", "path=" + path.split("\\?", 2)[0] + "; oauth=false", "auto");
                    return recovered;
                }
                store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "warn",
                        "离屏未拿到可用数据，保留原始错误", "path=" + path.split("\\?", 2)[0], "auto");
            } catch (Exception e) {
                store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "err",
                        "离屏执行异常，保留原始错误", "reason=" + ReadFallback.category(e), "auto");
            }
        }
        /* 只读兜底救不回「已失效的会话」（WAF 归一化 503 / 401 / 403）时，对 linuxdo 站复用论坛会话
         * 静默重建站点会话，成功后重试一次（禁止再次静默避免循环）。仅响应型会话失效信号触发，
         * 传输层异常（网络断）不触发——WebView 走同一出口也救不了网络问题。 */
        if (allowReauth && sessionLikelyDead(response, failure)) {
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null && "linuxdo".equals(SiteProtocol.provider(site, acc))) {
                int rc = OffscreenReauth.attempt(ctx, store, site, accountKey, 30);
                if (rc == OffscreenReauth.REAUTH_OK) {
                    resetTokenCache();
                    return callWithAuth(site, accountKey, method, path, jsonBody, false);
                }
            }
        }
        if (failure != null) throw failure;
        return response;
    }

    /** 响应是否呈现「会话失效/被拦」信号（可被静默重授权自愈）；传输层异常不算。 */
    private boolean sessionLikelyDead(JSONObject response, Exception failure) {
        if (failure != null || response == null) return false;
        int http = response.optInt("http");
        return http == 401 || http == 403 || http == 503
                || Engine.wafBlocked(response.optString("body", ""));
    }

    private JSONObject callWithAuthNative(JSONObject site, String accountKey, String method, String path, String jsonBody) throws Exception {
        JSONObject acc = store.findAccount(accountKey);
        String token = cachedToken(accountKey);
        if (token.isEmpty() && acc != null) token = clean(acc.optString("token", ""));
        String cookie = acc == null ? "" : clean(acc.optString("siteCookie", ""));
        String siteUserId = acc == null ? "" : clean(acc.optString("siteUserId", ""));

        /* 只有持有 Refresh Cookie 的新版站点才按 JWT 到期时间主动续期。
         * session Cookie 站点（如 AgentRouter）直接复用 Cookie，不因 token 为空而授权。 */
        if (hasRefreshCookie(cookie) && SilentAuth.needsExchange(token)) {
            String refreshed = refreshAccessToken(site, accountKey, null);
            if (!refreshed.isEmpty()) token = refreshed;
            acc = store.findAccount(accountKey);
            cookie = acc == null ? cookie : clean(acc.optString("siteCookie", cookie));
        }

        JSONObject result = call(site, token, cookie, siteUserId, method, path, jsonBody);
        if (result.optInt("http") != 401 || !hasRefreshCookie(cookie)
                || !ReadFallback.allowed(method, path)) return result;

        /* 401 表示本次使用的 token 已被服务端拒绝。锁内仅当存储 token 仍等于
         * failedToken 时真正续期；若已变化，说明并发线程已经完成续期，直接复用。 */
        String refreshed = refreshAccessToken(site, accountKey, token);
        if (refreshed.isEmpty()) return result;
        acc = store.findAccount(accountKey);
        String freshCookie = acc == null ? cookie : clean(acc.optString("siteCookie", cookie));
        cacheToken(accountKey, refreshed);
        JSONObject retried = call(site, refreshed, freshCookie, siteUserId, method, path, jsonBody);
        try { retried.put("refreshed", true); } catch (Exception ignored) {}
        return retried;
    }

    /**
     * 在现有长期会话内轮换短期访问令牌。锁内重新读取凭据，避免多个任务并发消费
     * 同一个一次性 Refresh Cookie。token 与轮换后的 Cookie 通过一次 patch 原子落库。
     * v1.0.5：开放为 public，供登出前"用 refresh cookie 换新 token 再销毁会话"复用。
     */
    public String refreshAccessToken(JSONObject site, String accountKey, String failedToken) {
        synchronized (refreshLock(accountKey)) {
            JSONObject acc = store.findAccount(accountKey);
            if (acc == null) return "";
            String storedToken = clean(acc.optString("token", ""));
            String cached = cachedToken(accountKey);
            String current = !cached.isEmpty() ? cached : storedToken;
            boolean forcedBy401 = failedToken != null;
            if (forcedBy401) {
                /* 另一个线程已经替换了失败 token：复用其结果，不能再消费轮换后的Cookie。 */
                if (!current.isEmpty() && !current.equals(failedToken)) return current;
            } else {
                if (!current.isEmpty() && !SilentAuth.needsExchange(current)) {
                    cacheToken(accountKey, current);
                    return current;
                }
            }
            String cookie = clean(acc.optString("siteCookie", ""));
            if (!hasRefreshCookie(cookie)) return "";

            String siteKey = site.optString("key", "");
            String base = site.optString("baseUrl", "").replaceAll("/+$", "");
            String trace = Long.toString(System.nanoTime(), 36);
            store.opLog(siteKey, accountKey, "会话续期V2", "info", "开始短期令牌续期",
                    "schema=" + AUTH_LOG_SCHEMA + "；trace=" + trace + "；trigger="
                            + (forcedBy401 ? "http401" : "jwt-exp") + "；model=refresh-cookie；oauth=false", "auto");
            Response response = null;
            try {
                Request request = new Request.Builder().url(base + "/api/user/auth/refresh")
                        .header("User-Agent", UA).header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Origin", base).header("Referer", base + "/")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Cookie", cookie)
                        .post(RequestBody.create("{}", MediaType.parse("application/json"))).build();
                response = buildClientFromConfig().newCall(request).execute();
                int http = response.code();
                String body = response.body() == null ? "" : response.body().string();
                JSONObject json;
                try { json = new JSONObject(body); } catch (Exception e) { json = new JSONObject(); }
                JSONObject data = json.optJSONObject("data");
                String newToken = data == null ? "" : clean(data.optString("access_token", ""));
                String rotated = mergeCookies(cookie, response.headers("Set-Cookie"));
                boolean rotatedCookie = !rotated.equals(cookie);
                String code = clean(json.optString("code", ""));
                store.opLog(siteKey, accountKey, "会话续期V2", http == 200 && !newToken.isEmpty() ? "ok" : "warn",
                        "短期访问令牌续期结束", "schema=" + AUTH_LOG_SCHEMA + "；trace=" + trace
                                + "；HTTP=" + http + "；code=" + (code.isEmpty() ? "-" : code)
                                + "；token=" + !newToken.isEmpty() + "；refreshCookieRotated=" + rotatedCookie
                                + "；oauth=false", "auto");
                if (http != 200 || !json.optBoolean("success", false) || newToken.isEmpty()) return "";
                JSONObject patch = new JSONObject().put("token", newToken).put("siteCookie", rotated);
                store.patchAccount(accountKey, patch);
                cacheToken(accountKey, newToken);
                return newToken;
            } catch (Exception e) {
                store.opLog(siteKey, accountKey, "会话续期V2", "warn", "短期访问令牌续期异常",
                        "schema=" + AUTH_LOG_SCHEMA + "；trace=" + trace + "；exception="
                                + e.getClass().getSimpleName() + "；oauth=false", "auto");
                return "";
            } finally {
                if (response != null) response.close();
            }
        }
    }

    /**
     * 会话销毁前的登出：先用 refresh cookie 续期拿有效 token（token 15min 过期后
     * 直接 logout 会被判 401 而误以为已登出，实际会话还活着——just 站会话卡死根因），
     * 再带有效 token+cookie 调 GET /api/user/logout，最后用 /api/user/self 复核。
     * 返回 ReauthManager 的分级码：CONFIRMED / ABSENT / UNCONFIRMED / DENIED。
     */
    public int logoutSession(String accountKey) {
        synchronized (opLock(accountKey)) { return logoutSessionLocked(accountKey); }
    }
    private int logoutSessionLocked(String accountKey) {
        JSONObject site = store.siteOfAccount(accountKey);
        JSONObject acc = store.findAccount(accountKey);
        if (site == null || acc == null) return ReauthManager.UNCONFIRMED;
        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) return ReauthManager.UNCONFIRMED;
        String sKey = site.optString("key", "");
        String token = clean(acc.optString("token", ""));
        String cookie = clean(acc.optString("siteCookie", ""));
        if (token.isEmpty() && cookie.isEmpty()) return ReauthManager.ABSENT; // 无凭据 = 无会话
        String uid = clean(acc.optString("siteUserId", ""));
        /* 1. 登出前续期，拿到服务端当下认得的有效凭据。两种续期模型：
         *    a) Refresh Cookie 站（New API 标准）：POST /api/user/auth/refresh；
         *    b) session Cookie 站（AgentRouter 等变体，实测无 auth/refresh，404）：
         *       GET /api/user/self 的响应体自带 data.access_token，即其真实续期产物。
         *    旧版只实现了 a)，b) 类站点因 hasRefreshCookie==false 从未续期过。 */
        if (hasRefreshCookie(cookie) && SilentAuth.needsExchange(token)) {
            String fresh = refreshAccessToken(site, accountKey, null);
            if (!fresh.isEmpty()) {
                token = fresh;
                JSONObject acc2 = store.findAccount(accountKey);
                if (acc2 != null) cookie = clean(acc2.optString("siteCookie", cookie));
                store.opLog(sKey, accountKey, "登出", "info", "登出前已续期短期令牌",
                        "model=refresh-cookie；确保服务端认得该会话", "auto");
            }
        } else if (!cookie.isEmpty()) {
            String fresh = renewSessionToken(site, accountKey, base, cookie, uid);
            if (!fresh.isEmpty()) {
                token = fresh;
                store.opLog(sKey, accountKey, "登出", "info", "登出前已续期短期令牌",
                        "model=session-self；确保服务端认得该会话", "auto");
            }
        }
        /* 2. 主通道：补全浏览器请求头的 OkHttp（实测可过阿里云 WAF 的请求头特征检测）。 */
        JSONObject res = httpLogout(base, token, cookie, uid);
        int code = res.optInt("http", 0);
        String body = res.optString("body", "");
        boolean waf = Engine.wafBlocked(body);
        store.opLog(sKey, accountKey, "登出", "info", "主登出通道响应",
                "channel=okhttp；HTTP=" + code + "；json=" + isJson(body) + "；waf=" + waf, "auto");
        Integer decided = decideLogout(code, body);
        /* 3. WAF 质询或非 JSON → 降级备用通道（离屏 WebView，真实执行 JS 质询，全后台无界面）。 */
        if (decided == null) {
            int alt = OffscreenLogout.logout(ctx, store, sKey, accountKey, base, cookie, uid, 45);
            switch (alt) {
                case OffscreenLogout.LOGOUT_OK:      decided = ReauthManager.CONFIRMED; break;
                case OffscreenLogout.LOGOUT_ABSENT:  decided = ReauthManager.ABSENT; break;
                case OffscreenLogout.LOGOUT_REFUSED: decided = ReauthManager.DENIED; break;
                default:                             decided = ReauthManager.UNCONFIRMED;
            }
        }
        return decided;
    }
    /**
     * session Cookie 站（AgentRouter 等）的真实续期：GET /api/user/self 返回体内的
     * data.access_token 即可独立作 Bearer 使用（实测不带 Cookie 单独请求 self 仍返回本人数据）。
     * 取到后落库，供登出与后续业务请求复用；取不到返回空串，不影响原有流程。
     */
    private String renewSessionToken(JSONObject site, String accountKey, String base,
                                     String cookie, String uid) {
        String sKey = site == null ? "" : site.optString("key", "");
        try {
            JSONObject r = httpGetJson(base + "/api/user/self", "", cookie, uid);
            String body = r.optString("body", "");
            if (!isJson(body)) {
                store.opLog(sKey, accountKey, "会话续期V2", "warn", "session 续期未取到令牌",
                        "schema=" + AUTH_LOG_SCHEMA + "；model=session-self；HTTP=" + r.optInt("http", 0)
                                + "；waf=" + Engine.wafBlocked(body) + "；oauth=false", "auto");
                return "";
            }
            JSONObject json = new JSONObject(body);
            JSONObject data = json.optJSONObject("data");
            JSONObject user = data == null ? null : data.optJSONObject("user");
            String fresh = "";
            if (user != null) fresh = clean(user.optString("access_token", ""));
            if (fresh.isEmpty() && data != null) fresh = clean(data.optString("access_token", ""));
            if (fresh.isEmpty() || "null".equals(fresh)) return "";
            store.patchAccount(accountKey, new JSONObject().put("token", fresh));
            cacheToken(accountKey, fresh);
            store.opLog(sKey, accountKey, "会话续期V2", "ok", "session 会话续期成功",
                    "schema=" + AUTH_LOG_SCHEMA + "；model=session-self；token=true；oauth=false", "auto");
            return fresh;
        } catch (Exception e) {
            store.opLog(sKey, accountKey, "会话续期V2", "warn", "session 续期异常",
                    "schema=" + AUTH_LOG_SCHEMA + "；model=session-self；exception="
                            + e.getClass().getSimpleName() + "；oauth=false", "auto");
            return "";
        }
    }
    /**
     * 登出结果判定——只认响应体语义，不靠状态码一票定生死。
     * 返回 null 表示「无法从本次响应下结论」，由调用方降级到备用通道。
     */
    private static Integer decideLogout(int code, String body) {
        if (code == 401) return ReauthManager.ABSENT;   // 凭据已不被承认 = 会话不存在
        if (code == 403) return ReauthManager.DENIED;
        if (code == 0 || code == 429 || code >= 500) return null; // 网络/限流/服务端故障 → 降级
        if (Engine.wafBlocked(body) || !isJson(body)) return null; // WAF 质询页 → 降级
        try {
            JSONObject json = new JSONObject(body);
            if (!json.has("success")) return null;
            /* AgentRouter 实测：logout 返回 success:true 但既不作废 token 也不作废 session。
             * 站点不提供可验证的销毁语义时，"success:true" 就是站点能给出的最强注销信号，
             * 再对 self 复核只会永远判"会话仍活"→ 永久阻断重登（v1.0.5 AG 签不了的根因）。
             * 真正的会话超限防线放在 OAuth 交换阶段的 409 AUTH_SESSION_LIMIT，那时不会建新会话。 */
            return json.optBoolean("success", false) ? ReauthManager.CONFIRMED : ReauthManager.DENIED;
        } catch (Exception e) {
            return null;
        }
    }
    /** 带完整浏览器请求头的 logout（WAF 只看请求头特征时可直接放行）。 */
    private JSONObject httpLogout(String base, String token, String cookie, String uid) {
        return httpGetJson(base + "/api/user/logout", token, cookie, uid);
    }
    /**
     * 统一的浏览器化 GET：补齐 Origin、Referer、Sec-Fetch 系列、Sec-CH-UA 系列与
     * X-Requested-With，实测可通过阿里云 WAF 的请求头特征检测（缺这些头时返回 JS 质询 HTML）。
     * 返回 {http, body}；网络异常时 http=0。
     */
    private JSONObject httpGetJson(String url, String token, String cookie, String uid) {
        JSONObject out = new JSONObject();
        Response r = null;
        try {
            String origin = url.substring(0, url.indexOf('/', 8));
            Request.Builder b = new Request.Builder().url(url).get()
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Origin", origin)
                    .header("Referer", origin + "/")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-CH-UA-Mobile", "?1")
                    .header("Sec-CH-UA-Platform", "\"Android\"")
                    .header("X-Requested-With", "XMLHttpRequest");
            if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
            if (cookie != null && !cookie.isEmpty()) b.header("Cookie", cookie);
            if (uid != null && !uid.isEmpty()) b.header("New-Api-User", uid);
            r = buildClientFromConfig().newCall(b.build()).execute();
            out.put("http", r.code());
            out.put("body", r.body() != null ? r.body().string() : "");
        } catch (Exception e) {
            try { out.put("http", 0).put("body", ""); } catch (Exception ignored) {}
        } finally {
            if (r != null) r.close();
        }
        return out;
    }
    /** 响应体是否为 JSON 对象/数组（WAF 质询页与前端 HTML 路由都会在此被排除）。 */
    private static boolean isJson(String body) {
        if (body == null) return false;
        String s = body.trim();
        return s.startsWith("{") || s.startsWith("[");
    }
    /**
     * 从 /api/user/self 响应中捕获 access_token 并落库（session Cookie 站的续期产物）。
     * 值未变化时不写库，避免每次刷新都触发一次磁盘写入。
     */
    private void captureSessionToken(String accountKey, JSONObject selfUser) {
        if (selfUser == null || accountKey == null || accountKey.isEmpty()) return;
        try {
            String fresh = clean(selfUser.optString("access_token", ""));
            if (fresh.isEmpty() || "null".equals(fresh)) return;
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null && fresh.equals(clean(acc.optString("token", "")))) {
                cacheToken(accountKey, fresh);
                return;
            }
            store.patchAccount(accountKey, new JSONObject().put("token", fresh));
            cacheToken(accountKey, fresh);
        } catch (Exception ignored) {}
    }

    private OkHttpClient buildClientFromConfig() {
        JSONObject proxy = store.config().optJSONObject("proxy");
        return buildClient(proxy != null && proxy.optBoolean("enabled"), proxy);
    }
    private static String clean(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }
    private static boolean hasRefreshCookie(String cookie) {
        if (cookie == null) return false;
        for (String pair : cookie.split(";")) {
            if (pair.trim().startsWith("new_api_refresh=")) return true;
        }
        return false;
    }
    /** 保留未轮换的 Cookie，并用 Set-Cookie 中同名的新值覆盖。 */
    private static String mergeCookies(String oldCookie, java.util.List<String> setCookies) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (oldCookie != null) for (String pair : oldCookie.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) values.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        if (setCookies != null) for (String raw : setCookies) {
            String pair = raw.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim(), value = pair.substring(eq + 1).trim();
            if (value.isEmpty() || "deleted".equalsIgnoreCase(value)) values.remove(name);
            else values.put(name, value);
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : values.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

        /** 合并两个 Cookie 头值（"a=1; b=2" 形式），后者覆盖同名项；空值/deleted 视为删除。 */
    public static String mergeCookieHeaders(String a, String b) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (String raw : new String[]{a, b}) {
            if (raw == null) continue;
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).trim(), v = pair.substring(eq + 1).trim();
                if (v.isEmpty() || "deleted".equalsIgnoreCase(v)) m.remove(k);
                else m.put(k, v);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
    /**
     * v1.0.7：阿里云 WAF 放行 Cookie 白名单。只有这几个键是 JS 质询产物，
     * 值得跨请求长期复用；业务 session 绝不从缓存回填（避免旧会话互相污染）。
     */
    private static final String[] WAF_COOKIE_KEYS = {"acw_sc__v2", "acw_tc", "cdn_sec_tc"};
    /** 从 Cookie 头值里只挑 WAF 相关键（用于落库缓存）。 */
    public static String pickWafCookies(String cookie) {
        StringBuilder sb = new StringBuilder();
        if (cookie == null) return "";
        for (String pair : cookie.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            for (String k : WAF_COOKIE_KEYS) {
                if (k.equals(name)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(p);
                    break;
                }
            }
        }
        return sb.toString();
    }
    /** 某站点缓存的 WAF 放行 Cookie（授权时由真实 WebView 过质询取得）。 */
    public String wafCookieForSite(String siteKey) {
        if (siteKey == null || siteKey.isEmpty()) return "";
        try { return clean(store.siteMeta(siteKey, "wafCookie", "")); } catch (Exception e) { return ""; }
    }
    /** URL → 站点 key（与 Store.siteKeyOf 同规则），用于查该站的 WAF 缓存。 */
    private static String siteKeyOfUrl(String url) {
        try {
            int i = url.indexOf("//");
            if (i < 0) return "";
            String rest = url.substring(i + 2);
            int s = rest.indexOf('/');
            String host = (s < 0 ? rest : rest.substring(0, s)).toLowerCase(java.util.Locale.US);
            return Store.siteKeyOf("https://" + host);
        } catch (Exception e) { return ""; }
    }
    /** 供WebView签到入口预先取得可用Token；只续期现有会话，绝不OAuth。 */
    public String ensureBusinessToken(JSONObject site, String accountKey) {
        JSONObject acc = store.findAccount(accountKey);
        String token = acc == null ? "" : clean(acc.optString("token", ""));
        String cookie = acc == null ? "" : clean(acc.optString("siteCookie", ""));
        if (hasRefreshCookie(cookie) && SilentAuth.needsExchange(token)) {
            String fresh = refreshAccessToken(site, accountKey, null);
            return fresh.isEmpty() ? token : fresh;
        }
        return token;
    }

    /** 一轮刷新/签到开始时清缓存，保证读到最新 token */
    public void resetTokenCache() {
        synchronized (tokenCache) { tokenCache.clear(); }
    }

    /* 单次刷新周期内的 token 缓存（避免同一批请求各自触发 OAuth） */
    private final java.util.HashMap<String, String> tokenCache = new java.util.HashMap<>();

    private String cachedToken(String accountKey) {
        synchronized (tokenCache) {
            String t = tokenCache.get(accountKey);
            return t == null ? "" : t;
        }
    }

    private void cacheToken(String accountKey, String token) {
        synchronized (tokenCache) { tokenCache.put(accountKey, token); }
    }

    /** v1.1.11：按 host:port 缓存代理客户端，杜绝「每次请求 new 一个 OkHttpClient」
     * 带来的连接池 / Dispatcher 线程池 / 后台清理线程叠加。此前每刷一次额度、每查一次
     * Key 都新建客户端（各自独立连接池，永不 evict），高频刷新下句柄/线程持续累积，
     * 是真实的资源叠加隐患。复用 = 站点侧看到的是同一连接池的少量长连接，也更像浏览器。 */
    private final java.util.concurrent.ConcurrentHashMap<String, OkHttpClient> proxyClients =
            new java.util.concurrent.ConcurrentHashMap<>();
    private OkHttpClient buildClient(boolean useProxy, JSONObject proxy) {
        if (!useProxy || proxy == null) return plain;
        String host = proxy.optString("host", "127.0.0.1");
        int port = proxy.optInt("port", 10808);
        String key = host + ":" + port;
        OkHttpClient cached = proxyClients.get(key);
        if (cached != null) return cached;
        try {
            OkHttpClient built = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                    // Bounded pool so a stuck egress can't fan out unbounded sockets.
                    .connectionPool(new okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
                    .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port)))
                    .build();
            OkHttpClient prev = proxyClients.putIfAbsent(key, built);
            if (prev != null) { built.dispatcher().executorService().shutdown(); return prev; }
            return built;
        } catch (Exception e) { return plain; }
    }

    /** v1.1.25：WAF 就地解 JS 的递归上限。旧实现用「本次 cookie 是否已带 acw_sc__v2」
     * 当递归判据，只要 stored cookie 里残留一个过期 acw_sc__v2，就误判「已过盾」直接跳过
     * 求解 → 业务恒 503，而质询页里明明带着可当场算的新 arg1（真机 09:33 self 偶发 503
     * 而 token 200 的机制层根因）。现改为「有界求解深度」：每层都从质询页当前 arg1 现解、
     * 覆盖旧 acw_sc__v2，最多 MAX_WAF_SOLVE 层，仍质询才落 503 兜底。2 层足够覆盖
     * 「过期 cookie 重解一次」+「极端下二次质询」，且严格有界不会递归爆栈。 */
    private static final int MAX_WAF_SOLVE = 2;

    private JSONObject attempt(String url, String token, String cookie, String siteUserId, String method, OkHttpClient client) {
        return attempt(url, token, cookie, siteUserId, method, client, null, 0);
    }
    /** v0.6.0：带 JSON body 的请求（aff_transfer 等写操作用） */
    private JSONObject attempt(String url, String token, String cookie, String siteUserId, String method, OkHttpClient client, String jsonBody) {
        return attempt(url, token, cookie, siteUserId, method, client, jsonBody, 0);
    }
    /** v1.1.25：内部实现，solveDepth = 本请求已就地解 JS 的次数（入口恒 0）。 */
    private JSONObject attempt(String url, String token, String cookie, String siteUserId, String method, OkHttpClient client, String jsonBody, int solveDepth) {
        Response resp = null;
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Accept", "application/json, text/plain, */*")
                    /* v1.0.6：全业务请求统一浏览器化请求头。
                     * 实测依据：日志中 agentrouter 刷新被阿里云 WAF 拦截 11 次、定时刷新 2 次，
                     * 远多于登出（4 次）——WAF 按请求头特征识别非浏览器客户端，
                     * 只给登出补头治标不治本。这些头对不设 WAF 的站点无副作用。 */
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Origin", url.substring(0, url.indexOf('/', 8)))
                    .header("Referer", url.substring(0, url.indexOf('/', 8)) + "/")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
                    .header("Sec-CH-UA-Mobile", "?1")
                    .header("Sec-CH-UA-Platform", "\"Android\"")
                    .header("X-Requested-With", "XMLHttpRequest");
            if (token != null && !token.isEmpty() && !"null".equals(token)) rb.header("Authorization", "Bearer " + token);
            /* opus4.8 审计·B-03：cookie 型站点会话头（New API gin session）。
             * 服务端 UserAuth 先查 session 再回退 Authorization，两者同带无害。 */
            if (cookie != null && !cookie.isEmpty()) rb.header("Cookie", cookie);
            /* v0.3.8（审计确认）：新版 New API 的 UserAuth 中间件要求
             * New-Api-User: <站内用户ID> 头，缺失则 session 被忽略 → 401。
             * 仅在账号有 siteUserId 时追加（按站点特性，不影响非 New API 站）。 */
            if (siteUserId != null && !siteUserId.isEmpty() && !"null".equals(siteUserId))
                rb.header("New-Api-User", siteUserId);
            if ("POST".equalsIgnoreCase(method)) {
                rb.post(RequestBody.create(jsonBody == null ? "{}" : jsonBody, MediaType.parse("application/json")));
            } else if ("DELETE".equalsIgnoreCase(method)) {
                /* v0.6.5：必须显式设置 DELETE。旧实现只处理 POST，其余默认 GET，
                 * 导致“删除 Key”实际请求成 GET /api/token/{id}，站点报 unrelated message。 */
                rb.delete();
            } else if ("PUT".equalsIgnoreCase(method)) {
                rb.put(RequestBody.create(jsonBody == null ? "{}" : jsonBody, MediaType.parse("application/json")));
            } else if ("PATCH".equalsIgnoreCase(method)) {
                rb.patch(RequestBody.create(jsonBody == null ? "{}" : jsonBody, MediaType.parse("application/json")));
            } else {
                rb.get();
            }
            resp = client.newCall(rb.build()).execute();
            String txt = resp.body() != null ? resp.body().string() : "";
            /* v0.4.3（glm-5.3 审计方案1）：WAF 假 200——HTTP 200 但 body 是拦截页 HTML。
             * 归一化为 http=503 + waf=true + 通俗 message，上层据此不覆盖额度、
             * toast 提示换代理节点；技术细节只进日志。 */
                        if (resp.code() == 200 && wafBlocked(txt)) {
                /* v1.1.6：WAF 自愈不再依赖缓存字段（就地求解质询页 arg1，纯 OkHttp 自愈）。
                 * v1.1.25：递归判据从「本次 cookie 是否已带 acw_sc__v2」（存在性）改为
                 * 「已就地求解次数 < MAX_WAF_SOLVE」（有界深度）。根因：存在性判据下，只要
                 * stored cookie 残留一个【过期】acw_sc__v2，就被误判「已过盾」→ 跳过求解 →
                 * 直接 503，而质询页里带着可当场算的【新】arg1。改深度后：每层都从当前质询页
                 * arg1 现解、用 mergeCookie【覆盖】旧 acw_sc__v2，acw_sc__v2 过期即当场重解，
                 * 业务请求秒过，不再落 23s 离屏 + 30s 重授权慢链。 */
                if (solveDepth < MAX_WAF_SOLVE) {
                    if (WafChallenge.isChallenge(txt)) {
                        String v = WafChallenge.solve(WafChallenge.arg1(txt));
                        if (!v.isEmpty()) {
                            /* 关键：acw_tc 是质询的会话标识，必须与 acw_sc__v2 同时回送。
                             * 沙箱对照实测：只带 acw_sc__v2 仍被拦，两者齐带才放行。
                             * acw_tc 由站点在质询响应里下发，此处从响应头捕获后合并。
                             * mergeCookie 原地【替换】旧 acw_sc__v2/acw_tc（不追加重复）。 */
                            String tc = WafChallenge.acwTcFromResponse(resp);
                            try {
                                String sk = siteKeyOfUrl(url);
                                store.opLog(sk == null ? "" : sk, "", "WAF 就地求解", "info",
                                        "第" + (solveDepth + 1) + "次现解质询 arg1，覆盖旧盾 Cookie 后重发",
                                        "acw_sc__v2 过期即当场重解，避免落离屏慢链", "sys");
                            } catch (Exception ignored) {}
                            return attempt(url, token, WafChallenge.mergeCookie(cookie, v, tc),
                                    siteUserId, method, client, jsonBody, solveDepth + 1);
                        }
                    }
                    /* 兜底：授权阶段真实浏览器过质询拿到的放行 Cookie（若已缓存）。 */
                    String cached = pickWafCookies(wafCookieForSite(siteKeyOfUrl(url)));
                    if (!cached.isEmpty()) {
                        return attempt(url, token, mergeCookieHeaders(cookie, cached),
                                siteUserId, method, client, jsonBody, solveDepth + 1);
                    }
                }
                lastError = "WAF200: " + txt.substring(0, Math.min(120, txt.length()));
                return new JSONObject().put("http", 503).put("waf", true)
                        .put("message", "站点防护拦截了本次请求，请更换代理节点后重试");
            }
            JSONObject out = new JSONObject().put("http", resp.code());
            try { out.put("data", new JSONObject(txt)); }
            catch (Exception e) { out.put("data", new JSONObject()); }
            /* v1.1.6：本次请求带了本地解出的 acw_sc__v2 且拿到了真实业务响应，
             * 说明放行有效 —— 回存到本站 wafCookie，后续请求直接复用，省一次往返。
             * 只回存「确实带来放行」的值（走到这里即证明已通过），不存中间态。 */
            if (cookie != null && cookie.contains("acw_sc__v2") && resp.code() != 401) {
                try {
                    String sk = siteKeyOfUrl(url);
                    if (sk != null && !sk.isEmpty()) {
                        store.putSiteMeta(sk, "wafCookie", pickWafCookies(cookie));
                    }
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (" " + e.getMessage()));
            return null;
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
    }

    /** WAF 拦截页特征（v0.4.3，glm-5.3 审计）：只认明确标记，宁缺勿滥——
     * 普通 HTML 404/错误页不算，避免误伤。只扫前 4KB，防超大 body。 */
    public static boolean wafBlocked(String body) {
        if (body == null || body.isEmpty()) return false;
        String b = body.length() > 4096 ? body.substring(0, 4096) : body;
        String l = b.toLowerCase(java.util.Locale.US);
        return l.contains("aliyun_waf")
                || (l.contains("<script") && (l.contains("arg1=") || l.contains("arg1 =")))
                || l.contains("acw_sc__v2")
                || l.contains("errors.aliyun.com")
                || (l.contains("cloudflare") && (l.contains("just a moment") || l.contains("attention required")))
                || l.contains("waf.tencent-cloud.com")
                || l.contains("safedog");
    }
    private static JSONObject dd(JSONObject resp) {
        if (resp == null) return new JSONObject();
        JSONObject d = resp.optJSONObject("data");
        if (d == null) return new JSONObject();
        JSONObject inner = d.optJSONObject("data");
        return inner != null ? inner : d;
    }
    /* New API 变体字段回退链：部分站（如 agentrouter）把用户数据放在
     * data.user 而非 data 直层。逐级回退，取到哪个用哪个。 */
    private static JSONObject userOf(JSONObject resp) {
        JSONObject d = dd(resp);
        JSONObject u = d.optJSONObject("user");
        if (u != null && (u.has("quota") || u.has("used_quota"))) return u;
        if (d.has("quota") || d.has("used_quota")) return d;
        return u != null ? u : d;
    }

    private static String httpHint(int code) {
        if (code == 401) return "登录已过期，请重新授权";
        if (code == 403) return "站点拒绝访问，请稍后重试或更换代理节点";
        if (code == 429) return "请求太频繁被限制，请稍等片刻再试，或更换代理节点";
        if (code >= 500) return "站点服务暂时不可用，请稍后重试";
        if (code == 404) return "站点接口不存在，请联系开发者";
        if (code == 0) return "网络连接失败，请检查网络或代理设置";
        return "站点响应异常，请稍后重试";
    }

    /* ================= 站点类型 ================= */

    public static String siteKind(JSONObject site) {
        String t = site == null ? "" : site.optString("checkinType", "login");
        if ("manual".equals(t) || "newapi".equals(t)) return "newapi";
        if ("web".equals(t)) return "web";
        return "login";
    }

    public static boolean isAutoCheckin(JSONObject site) { return "newapi".equals(siteKind(site)); }
    public static boolean isWebOnly(JSONObject site) { return "web".equals(siteKind(site)); }
    /* ================= 统一账号状态判据（v1.0.3） =================
     * 全 App（顶栏摘要 / 一键签到队列 / 卡片胶囊）必须共用同一套判据，
     * 否则会出现「顶栏显示 3 待签，点一键签到却说无待签账号」的自相矛盾。 */
    public static final int ST_NEED_AUTH = 0;   // 未授权：无 token 也无 siteCookie
    public static final int ST_CHECKED   = 1;   // 今日已签
    public static final int ST_PENDING   = 2;   // 已授权且今日未签（可签目标）
    public static final int ST_WEB       = 3;   // 网页手动站（后台无法自动签）
    /** 账号状态四态判据。authed 判定同时看 token 与 siteCookie（cookie 型站 token 为空）。 */
    public static int acctState(JSONObject site, JSONObject acc) {
        if (acc == null) return ST_NEED_AUTH;
        String tok = acc.optString("token", "");
        String ck = acc.optString("siteCookie", "");
        if ("null".equals(tok)) tok = "";
        if ("null".equals(ck)) ck = "";
        boolean authed = !(tok == null || tok.isEmpty()) || !(ck == null || ck.isEmpty());
        if (!authed) return ST_NEED_AUTH;
        if (isCheckedToday(acc)) return ST_CHECKED;
        if (isWebOnly(site)) return ST_WEB;
        return ST_PENDING;
    }
    /**
     * 需要「登出旧会话 → 重新登录」才发放当日奖励的站点（实测确认：AgentRouter、JustDoWork）。
     * 这类站的当日奖励只在发生一次新登录时由服务端发放，已有会话刷新不触发
     * （刷新日志显示「非今日奖励」）。因此它们的「签到」动作 ≡ 登出 + 重登。
     * 其他站（含 GoRouter）会话有效时点签到/刷新即可，不走此流程。
     */
    public static boolean needsReloginCheckin(JSONObject site) {
        String k = site == null ? "" : site.optString("key", "").toLowerCase(java.util.Locale.US);
        return k.contains("agentrouter") || k.contains("justwoker");
    }

    public static String kindLabel(JSONObject site) {
        switch (siteKind(site)) {
            case "newapi": return "每日签到";
            case "web":    return "网页手动";
            default:       return "登录即得";
        }
    }

    public static String kindLabelOf(String checkinType) {
        try { return kindLabel(new JSONObject().put("checkinType", checkinType == null ? "" : checkinType)); }
        catch (Exception e) { return "登录即得"; }
    }

    /* ================= 业务：刷新（读取三大额度 + 签到奖励） ================= */

    public JSONObject status(String key) throws Exception {
        return status(key, true);
    }

    /**
     * v1.1.1：logSummary=false 供「同一轮刷新内的二次状态获取」（如邀请划转后的复核）
     * 使用——不再重复写「额度已更新」操作日志（该场景曾导致同账号一轮出现两条额度文案）。
     */
    public JSONObject status(String key, boolean logSummary) throws Exception {
        synchronized (opLock(key)) { return statusLocked(key, logSummary, true); }
    }
    private JSONObject statusLocked(String key, boolean logSummary, boolean allowSilentReauth) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        final String sKey = site.optString("key", "");

        /* 每轮刷新开头清一次缓存，读取本轮最新 token */
        resetTokenCache();

        /* 统一走 callWithAuth：过期先换、401 再换，全程后台无弹窗。
         * 这里传 allowReauth=false：刷新链的静默重授权由下方 statusLocked 自己那段处理
         * （已真机验证，且成功后需重跑整条 status 重算额度），不让 callWithAuth 再触发一次，避免双重重授权。 */
        JSONObject self = callWithAuth(site, key, "GET", "/api/user/self", null, false);
        int selfHttp = self.optInt("http");

        /*
         * v1.1.15 静默重授权自愈（仅 linuxdo 站，一轮刷新最多一次）：
         * 真机实证——旧会话失效后，原生被 WAF 挡成 503、离屏只读也救不回已过期的会话，
         * 但用户手动「重新授权」在同节点 ~2s 成功（关键是账号专属分区里 connect.linux.do
         * 论坛会话仍活着，SPA 复用它直通确认页）。此处把那条路搬到离屏：self 不合法且像
         * 「会话失效/被拦」（401 或 WAF 归一化的 503）时，尝试 OffscreenReauth 复用论坛会话
         * 重建站点会话，成功即用新 Cookie 重跑一次 status。论坛会话也失效/需人机验证时
         * OffscreenReauth 返回 needUi，这里不再重试、按原错误返回，行为不比现状差。
         */
        if (allowSilentReauth
                && !SiteProtocol.validSelf(self, tk.optString("siteUserId", ""))
                && "linuxdo".equals(SiteProtocol.provider(site, tk))
                && (selfHttp == 401 || selfHttp == 403 || selfHttp == 503
                    || Engine.wafBlocked(self.optString("body", "")))) {
            int rc = OffscreenReauth.attempt(ctx, store, site, key, 30);
            if (rc == OffscreenReauth.REAUTH_OK) {
                resetTokenCache();
                return statusLocked(key, logSummary, false); // 用新会话重跑一次，禁止再次静默避免循环
            }
        }

        if (!SiteProtocol.validSelf(self, tk.optString("siteUserId", ""))) {
            String message = self.optString("message", "");
            if (message.isEmpty()) message = selfHttp == 200
                    ? "站点业务响应或账号身份异常，保留上次额度" : httpHint(selfHttp);
            store.opLog(sKey, key, "刷新", "err", message,
                    "GET /api/user/self；HTTP " + selfHttp + "；businessValid=false", "user");
            return new JSONObject().put("ok", false).put("http", selfHttp)
                    .put("message", message).put("account", key).put("site", site.optString("name"));
        }

        JSONObject stat;
        try { stat = call(site, null, "GET", "/api/status"); }
        catch (Exception e) { stat = new JSONObject(); }

        long unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT);
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;

        if (!sKey.isEmpty()) {
            String tsk = dd(stat).optString("turnstile_site_key", "");
            if (!tsk.isEmpty()) store.putSiteMeta(sKey, "turnstileSiteKey", tsk);
            store.putSiteMeta(sKey, "quotaPerUnit", unit);
        }

        /* userOf：data.user.quota -> data.quota 回退（agentrouter 等变体站字段在 user 内层） */
        JSONObject selfU = userOf(self);
        /* session Cookie 站（AgentRouter 等）的真实续期产物就在 self 响应体里：
         * data(.user).access_token 可独立作 Bearer 使用。旧版只读额度字段、丢弃该令牌，
         * 导致登出/业务请求始终无 token 可用。此处顺手落库，零额外请求。 */
        captureSessionToken(key, selfU);
        double quota = selfU.optDouble("quota", 0);
        double used = selfU.optDouble("used_quota", 0);
        String user = selfU.optString("display_name", null);
        if (user == null || user.isEmpty()) user = selfU.optString("username", null);

        JSONObject out = new JSONObject()
                .put("ok", selfHttp == 200)
                .put("account", key).put("site", site.optString("name"))
                .put("http", selfHttp)
                .put("authorized", selfHttp == 200)
                .put("availableUSD", Math.round(quota / unit * 100.0) / 100.0)
                .put("usedUSD", Math.round(used / unit * 100.0) / 100.0)
                .put("user", (user == null || user.isEmpty()) ? JSONObject.NULL : user)
                .put("statusDate", todayStr());
        if (selfHttp != 200) {
            /* v0.4.3：WAF 归一化响应自带通俗 message，优先透传 */
            String wm = self.optString("message", "");
            out.put("message", wm.isEmpty() ? httpHint(selfHttp) : wm);
        }

        /* 今日消耗 */
        double todayUsed = -1;
        if (selfHttp == 200) {
            try { todayUsed = todayUsage(site, key, unit); } catch (Exception ignored) {}
        }
        if (todayUsed >= 0) out.put("todayUsed", Math.round(todayUsed * 100.0) / 100.0);
        store.appendLog(sKey, key, "status", "http=" + selfHttp);

        if (selfHttp == 200) {
            /* v1.1.1：同轮复核不重复写额度文案（logSummary 控制） */
            if (logSummary) store.opLog(sKey, key, "刷新", "ok", "额度已更新",
                    "可用 $" + Ui.usd(Math.round(quota / unit * 100.0) / 100.0)
                            + (todayUsed >= 0 ? (" · 今日消耗 $" + Ui.usd(todayUsed)) : ""), "user");
        } else {
            store.opLog(sKey, key, "刷新", "err", httpHint(selfHttp), "GET /api/user/self", "user");
        }

        /* 签到状态与奖励检测 */
        if (selfHttp == 200) {
            boolean loginKind = "login".equals(siteKind(site));
            JSONObject cs = null;
            if (SiteProtocol.isAnyRouter(site)) {
                boolean receipt = SiteProtocol.hasSignInReceipt(tk, System.currentTimeMillis());
                cs = new JSONObject().put("checked", receipt).put("rewardKnown", false);
                out.put("checkinStateKnown", receipt);
                if (receipt) out.put("checkinSource", "api_sign_in");
            } else {
                try { cs = checkinStatus(key, unit); } catch (Exception ignored) {}
            }
            if ((cs == null || !cs.optBoolean("checked", false)) && loginKind && !SiteProtocol.isAnyRouter(site)) {
                /* 登录即得站以「今日系统奖励记录」为最终判据。checkinStatus 已按北京时间
                 * 严格比对 checkin_date；这里再用今日奖励日志兜底（日志接口偶发被 WAF 拦）。
                 * todayBonus 内部已用 isToday(北京时间) 过滤，只会命中当日记录。 */
                JSONObject probe = null;
                try { probe = todayBonus(key); } catch (Exception ignored) {}
                if (probe != null) cs = new JSONObject().put("checked", true)
                        .put("rewardUSD", probe.optDouble("rewardUSD", 0))
                        .put("rewardKnown", probe.optBoolean("rewardKnown", false));
            }
            /* v1.0.5：checked_in 布尔仅作「非 login 型站」的兜底信号。
             * login 型站（AgentRouter/JustDoWork）当日奖励靠登出重登发放，
             * self.checked_in 可能因站点缓存/时区滞后为 true，单凭它会把
             * 「今日实际未签」误判成已签（账号1误判根因）——因此 login 型站
             * 绝不采信 checked_in，只认 checkinStatus/todayBonus 的当日记录。 */
            if (cs == null && !loginKind && selfU.has("checked_in")) {
                cs = new JSONObject().put("checked", selfU.optBoolean("checked_in", false))
                        .put("rewardUSD", 0).put("rewardKnown", false);
            }
            if (cs != null && cs.optBoolean("checked")) {
                out.put("todayChecked", true);
                out.put("todayRewardUSD", cs.optDouble("rewardUSD", 0));
                out.put("todayRewardKnown", cs.optBoolean("rewardKnown", false));
            } else {
                out.put("todayChecked", false);
                out.put("todayRewardKnown", false);
            }
        } else {
            out.put("todayChecked", false);
            out.put("todayRewardKnown", false);
        }
        return out;
}
    /**
     * v0.6.0：邀请额度自动划转（AgentRouter 实测：POST /api/user/aff_transfer，
     * body {"quota": N}，N 为 quota 原始值；站点最小划转 $1，不足报「邀请额度不足」）。
     * 流程：读 self 的 aff_quota → ≥1$ 就全额划转 → 返回划转结果（成功金额/失败原因）。
     * 全程走 callWithAuth（token 过期自动换、cookie 型站带会话头）。
     */
    public JSONObject affTransfer(String key) throws Exception {
        JSONObject acc = store.findAccount(key);
        if (acc == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        String sKey = site.optString("key", "");
        /* 1. 读当前邀请额度 */
        JSONObject self = callWithAuth(site, key, "GET", "/api/user/self");
        JSONObject selfU = userOf(self);
        long aff = (long) selfU.optDouble("aff_quota", 0);
        long unit = store.siteMetaLong(sKey, "quotaPerUnit", 0);
        if (unit <= 0) {
            JSONObject stat;
            try { stat = call(site, null, "GET", "/api/status"); }
            catch (Exception e) { stat = new JSONObject(); }
            unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT);
            if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        }
        JSONObject out = new JSONObject().put("unit", unit);
        if (aff <= 0) {
            return out.put("ok", false).put("skipped", true)
                    .put("message", "无可划转的邀请额度");
        }
        if (aff < unit) {
            /* 站点最小划转 $1：不足 $1 划了必失败，直接跳过并说明 */
            return out.put("ok", false).put("skipped", true)
                    .put("affQuota", aff)
                    .put("message", "邀请额度不足 $1（当前 " + String.format("%.2f", aff / (double) unit) + "$），暂不能划转");
        }
        /* 2. 全额划转 */
        JSONObject r = callWithAuth(site, key, "POST", "/api/user/aff_transfer",
                "{\"quota\":" + aff + "}");
        int http = r.optInt("http");
        JSONObject rd = r.optJSONObject("data");
        boolean ok = http == 200 && rd != null && rd.optBoolean("success", false);
        String msg = rd != null ? rd.optString("message", "") : "";
        out.put("ok", ok).put("affQuota", aff).put("http", http);
        if (!ok) {
            /* 站点原文透传（如「转移额度最小为$1」「邀请额度不足」），不猜原因 */
            if (msg.isEmpty()) msg = "站点返回 HTTP " + http;
            return out.put("message", msg);
        }
        /* 3. 划转成功：读新余额 */
        long newQuota = -1;
        try {
            JSONObject self2 = callWithAuth(site, key, "GET", "/api/user/self");
            newQuota = (long) userOf(self2).optDouble("quota", -1);
        } catch (Exception ignored) {}
        return out.put("message", "已划转 $" + String.format("%.2f", aff / (double) unit))
                .put("newQuota", newQuota);
    }
    /**
     * v0.6.0：API Key 管理（New API 系 /api/token/ 三件套）。
     * list: GET /api/token/?p=1&size=100 → data.items[]
     * create: POST /api/token/ {"name":...} → 返回含完整 key，自动复制
     * delete: DELETE /api/token/{id}
     */
    public JSONObject tokenList(String key) throws Exception {
        JSONObject acc = store.findAccount(key);
        if (acc == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        String sKey = site.optString("key", "");
        /* 调试日志（排查取不到 key）：记录请求前状态，不记敏感值 */
        boolean hasToken = !acc.optString("token", "").isEmpty();
        boolean hasCookie = !acc.optString("siteCookie", "").isEmpty();
        String uid = acc.optString("siteUserId", "");
        try {
            store.opLog(sKey, key, "Key 管理", "info", "开始获取 Key 列表",
                    "token=" + hasToken + " cookie=" + hasCookie + " uid=" + (uid.isEmpty() ? "无" : "有"), "auto");
        } catch (Exception ignored) {}
        JSONObject r = callWithAuth(site, key, "GET", SiteProtocol.tokenListPath(site));
        int http = r.optInt("http");
        JSONObject rd = r.optJSONObject("data");
        /* 调试日志：HTTP 状态 + data 结构形态 */
        Object dataObj = r.opt("data");
        String dataShape = dataObj == null ? "null"
                : (dataObj instanceof org.json.JSONArray ? "array[" + ((org.json.JSONArray) dataObj).length() + "]"
                : (dataObj instanceof JSONObject ? "object{" + ((JSONObject) dataObj).length() + "keys}" : "other"));
        try {
            store.opLog(sKey, key, "Key 管理", "info", "Key 列表响应",
                    "HTTP " + http + " data=" + dataShape + " success=" + (rd != null && rd.optBoolean("success", false)), "auto");
        } catch (Exception ignored) {}
        if (http != 200) throw new Exception("站点返回 HTTP " + http
                + (rd != null && !rd.optString("message", "").isEmpty() ? "：" + rd.optString("message") : ""));
        if (rd == null) throw new Exception("响应无 data 字段（" + dataShape + "）");
        if (!rd.optBoolean("success", false)) {
            String msg = rd.optString("message", "");
            throw new Exception(msg.isEmpty() ? "站点返回 success=false" : msg);
        }
        /* 注意层级：attempt 把整个响应包在 r.data 里，即 r.data = {success,message,data:{...}}。
         * items 的真实位置是 r.data.data.items（标准 New API）。
         * 兼容三种形态：data.items / data 是数组 / data.data 是数组。 */
        Object inner = rd.opt("data");
        org.json.JSONArray items = null;
        if (inner instanceof org.json.JSONArray) {
            items = (org.json.JSONArray) inner;                       // data 直接是数组
        } else if (inner instanceof JSONObject) {
            JSONObject id = (JSONObject) inner;
            Object it = id.opt("items");
            if (it instanceof org.json.JSONArray) items = (org.json.JSONArray) it;  // data.data.items
            else if (id.opt("records") instanceof org.json.JSONArray) items = id.optJSONArray("records");
        } else if (rd.opt("items") instanceof org.json.JSONArray) {
            items = rd.optJSONArray("items");                          // data.items（极少变体）
        }
        /* 调试日志：解析出的条目数 */
        try {
            store.opLog(sKey, key, "Key 管理", "info", "Key 列表解析",
                    "items=" + (items == null ? 0 : items.length()), "auto");
        } catch (Exception ignored) {}
        return new JSONObject().put("items", items == null ? new org.json.JSONArray() : items);
    }
    public JSONObject tokenCreate(String key, String name) throws Exception {
        JSONObject acc = store.findAccount(key);
        if (acc == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        JSONObject r = callWithAuth(site, key, "POST", "/api/token/",
                "{\"name\":" + org.json.JSONObject.quote(name) + "}");
        int http = r.optInt("http");
        JSONObject rd = r.optJSONObject("data");
        boolean ok = http == 200 && rd != null && rd.optBoolean("success", false);
        String msg = rd != null ? rd.optString("message", "") : "";
        if (!ok) throw new Exception(msg.isEmpty() ? ("站点返回 HTTP " + http) : msg);
        /* 建成功后部分站不回 key，需再拉一次列表 */
        return new JSONObject().put("ok", true);
    }
    public JSONObject tokenDelete(String key, long tokenId) throws Exception {
        JSONObject acc = store.findAccount(key);
        if (acc == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        String sKey = site.optString("key", "");
        String path = "/api/token/" + tokenId;
        try {
            store.opLog(sKey, key, "Key 管理", "info", "开始删除 API Key",
                    "method=DELETE path=/api/token/{id} id=" + tokenId
                            + " token=" + !acc.optString("token", "").isEmpty()
                            + " cookie=" + !acc.optString("siteCookie", "").isEmpty()
                            + " uid=" + !acc.optString("siteUserId", "").isEmpty(), "auto");
        } catch (Exception ignored) {}
        JSONObject r = callWithAuth(site, key, "DELETE", path);
        int http = r.optInt("http");
        JSONObject rd = r.optJSONObject("data");
        boolean ok = http == 200 && rd != null && rd.optBoolean("success", false);
        String msg = rd != null ? rd.optString("message", "") : "";
        try {
            store.opLog(sKey, key, "Key 管理", ok ? "ok" : "err", "删除 API Key 响应",
                    "method=DELETE HTTP=" + http + " success=" + ok
                            + " message=" + (msg.isEmpty() ? "(空)" : msg), "auto");
        } catch (Exception ignored) {}
        if (!ok) throw new Exception(msg.isEmpty() ? ("站点返回 HTTP " + http) : msg);
        return new JSONObject().put("ok", true);
    }
    public JSONObject checkinStatus(String key, long unit) throws Exception {
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (SiteProtocol.isAnyRouter(site)) {
            boolean receipt = SiteProtocol.hasSignInReceipt(store.findAccount(key), System.currentTimeMillis());
            return new JSONObject().put("checked", receipt).put("rewardKnown", false)
                    .put("stateUnknown", !receipt).put("message", receipt ? "本应用今日签到请求已确认" : "站点无只读签到状态接口");
        }
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(new java.util.Date());
        JSONObject r = callWithAuth(site, key, "GET",
                "/api/user/checkin?month=" + URLEncoder.encode(month, "UTF-8"));
        if (r.optInt("http") != 200) return null;
        JSONObject d = dd(r);
        JSONObject stats = d.optJSONObject("stats");
        if (stats == null) return null;
        String today = todayStr();
        boolean checked = stats.optBoolean("checked_in_today", false);
        double reward = 0;
        boolean rewardKnown = false;
        JSONArray recs = stats.optJSONArray("records");
        if (recs == null) recs = d.optJSONArray("records");
        if (recs != null) for (int i = 0; i < recs.length(); i++) {
            JSONObject o = recs.optJSONObject(i);
            if (o == null) continue;
            String date = o.optString("checkin_date", o.optString("date", ""));
            if (today.equals(date)) {
                double raw = o.optDouble("quota_awarded", o.optDouble("quota", 0));
                reward = raw >= 1000 ? Math.round(raw / (double) unit * 100.0) / 100.0 : raw;
                rewardKnown = true;
                if (!checked) checked = true;
                break;
            }
        }
        return new JSONObject().put("checked", checked)
                .put("rewardUSD", reward)
                .put("rewardKnown", rewardKnown)
                .put("checkedDate", today);
    }

    public JSONObject checkin(String key) throws Exception {
        synchronized (opLock(key)) { return checkinLocked(key); }
    }
    private JSONObject checkinLocked(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        JSONObject site = store.siteOfAccount(key);
        if (tk == null || site == null) throw new Exception("账号或站点不存在");
        JSONObject out = new JSONObject();
        if (isAutoCheckin(site)) {
            out.put("ok", false)
               .put("needWebview", true)
               .put("message", "该站启用人机验证，需在后台签到窗口完成");
            return out;
        }
        if (isWebOnly(site)) {
            out.put("ok", false).put("webOnly", true).put("rewardKnown", false)
               .put("message", "该站不开放签到接口，请点站点名打开网页手动操作");
            return out;
        }
        long unit = store.siteMetaLong(site.optString("key", ""), "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT);

        JSONObject cs = null;
        try { cs = checkinStatus(key, unit); } catch (Exception ignored) {}
        if (cs != null && cs.optBoolean("checked")) {
            double rw = cs.optDouble("rewardUSD", 0);
            boolean known = cs.optBoolean("rewardKnown", false);
            out.put("ok", true).put("already", true)
               .put("reward", rw).put("rewardKnown", known)
               .put("message", known && rw > 0 ? "今日已签到" : "今日已签到（本站无奖励）");
            markChecked(key, rw, known);
            return out;
        }

        JSONObject tb = null;
        try { tb = todayBonus(key); } catch (Exception ignored) {}
        if (tb != null) {
            /* 金额来自 content 文案（quota 恒为 0，不能用它折算）。
             * 解析不到金额时按「无奖励」处理，不编造数字。 */
            double rw = tb.optDouble("rewardUSD", 0);
            boolean known = tb.optBoolean("rewardKnown", false);
            out.put("ok", true).put("already", true)
               .put("reward", rw).put("rewardKnown", known)
               .put("message", (known && rw > 0)
                       ? "登录即签到 · 今日奖励已到账"
                       : "登录即签到 · 今日已签（无奖励）");
            markChecked(key, rw, known);
            return out;
        }

        JSONObject self = null;
        try { self = callWithAuth(site, key, "GET", "/api/user/self"); } catch (Exception ignored) {}
        int code = self == null ? 0 : self.optInt("http");
        if (code != 200) {
            out.put("ok", false).put("already", false).put("rewardKnown", false)
               .put("message", httpHint(code));
            return out;
        }
        /* AgentRouter 一类站在 /api/user/self 里直接给 checked_in 布尔。
         * 但重登型站（AG/Just）的 checked_in 可能因站点缓存/时区滞后为 true，
         * 而当日奖励实际要靠登出重登才发放——因此重登型站绝不采信 checked_in，
         * 一律走下方 needsRelogin 分支，由重登流程核对当日真实奖励后再标已签。 */
        JSONObject su = dd(self);
        if (su != null && su.has("checked_in") && !Engine.needsReloginCheckin(site)) {
            boolean ci = su.optBoolean("checked_in", false);
            out.put("ok", ci).put("already", ci).put("reward", 0).put("rewardKnown", false)
               .put("message", ci ? "登录即签到 · 站点已标记今日已签"
                                  : "站点显示今日未签到，请打开网页登录一次以触发发放");
            if (ci) markChecked(key, 0, false);
            return out;
        }
        /* v1.0.3 修复：绝不能在无任何签到信号时标「已签」！
         * 此处旧代码返回「已保活」并 markChecked——导致 AgentRouter 等站
         * 今日实际没签到（服务端无今日记录）却被本地标记已签，之后定时/一键
         * 签到全部「今日已签，跳过」，当日奖励永远领不到。 */
        out.put("ok", false).put("already", false).put("reward", 0).put("rewardKnown", false)
           .put("needsRelogin", Engine.needsReloginCheckin(site))
           .put("message", Engine.needsReloginCheckin(site)
                   ? "站点无今日签到记录，需重新登录触发奖励发放"
                   : "登录保活完成（未检测到今日签到记录，未标记已签）");
        if (!Engine.needsReloginCheckin(site)) {
            /* 仅非重登型站保活：只刷新会话，不写 lastCheckin */
            try { callWithAuth(site, key, "GET", "/api/user/self"); } catch (Exception ignored) {}
        }
        return out;
    }

    private double todayUsage(JSONObject site, String accountKey, long unit) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            long start = c.getTimeInMillis() / 1000L;
            long end = System.currentTimeMillis() / 1000L;
            JSONObject r = callWithAuth(site, accountKey, "GET",
                    "/api/data/self?start_timestamp=" + start + "&end_timestamp=" + end + "&default_time=hour");
            if (r.optInt("http") != 200) return -1;
            JSONArray list = null;
            JSONObject wrap = r.optJSONObject("data");
            if (wrap != null) list = wrap.optJSONArray("data");
            if (list == null) return -1;
            double sum = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) sum += o.optDouble("quota", 0);
            }
            if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
            return sum / unit;
        } catch (Exception e) { return -1; }
    }

    private static double quotaToUSD(long q, long unit) {
        if (q <= 0) return 0;
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        return q >= 1000 ? Math.round(q / (double) unit * 100.0) / 100.0 : q;
    }

    private void markChecked(String accountKey, double reward, boolean rewardKnown) {
        try {
            JSONObject lc = new JSONObject()
                    .put("date", todayStr())
                    .put("time", System.currentTimeMillis());
            if (rewardKnown) lc.put("reward", reward);
            store.patchAccount(accountKey, new JSONObject().put("lastCheckin", lc));
        } catch (Exception ignored) {}
    }

    /**
     * 今日是否有「每日签到」日志记录，并带出奖励金额。
     * 注意 quota 字段恒为 0，金额只在 content 文案里（row.usd 已解析好）。
     * 返回的对象额外带 rewardUSD / rewardKnown 两个字段。
     */
    public JSONObject todayBonus(String key) throws Exception {
        JSONObject site = store.siteOfAccount(key);
        String sKey = site == null ? "" : site.optString("key", "");
        JSONObject lg = logs(key, "系统", 30);
        /* v0.4.4（审计方案A）：失败原因显式写 opLog，用户能从悬浮日志看到为何奖励没显示 */
        if (!lg.optBoolean("ok")) {
            store.opLog(sKey, key, "奖励检测", "warn", "签到奖励未显示：日志接口失败",
                    "GET /api/log/self http=" + lg.optInt("http", 0), "user");
            return null;
        }
        JSONObject lb = lg.optJSONObject("lastBonus");
        if (lb == null || !lb.has("time")) {
            store.opLog(sKey, key, "奖励检测", "warn", "签到奖励未显示：近30条系统日志无签到记录",
                    "接口返回正常但无「每日签到」类文案（站点可能当日未发奖励）", "user");
            return null;
        }
        long t = parseTimeMs(lb.optString("time"));
        if (t <= 0 || !isToday(t)) {
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(t > 0 ? t : 0));
            store.opLog(sKey, key, "奖励检测", "warn", "签到奖励未显示：最近签到记录非今日",
                    "最近一条签到时间：" + ts, "user");
            return null;
        }
        double usd = lb.optDouble("usd", -1);
        lb.put("rewardUSD", usd >= 0 ? usd : 0);
        lb.put("rewardKnown", usd >= 0);
        return lb;
    }

    private static long parseTimeMs(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            long v = Long.parseLong(s.trim());
            if (v > 100000000000L) return v;
            if (v > 1000000000L) return v * 1000L;
        } catch (Exception ignored) {}
        String[] pats = { "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss" };
        for (String p : pats) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                java.util.Date d = f.parse(s.length() > 19 ? s.substring(0, 19) : s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    public static boolean isToday(long ms) {
        java.util.TimeZone cst = java.util.TimeZone.getTimeZone("Asia/Shanghai");
        java.util.Calendar a = java.util.Calendar.getInstance(cst);
        a.setTimeInMillis(ms);
        java.util.Calendar b = java.util.Calendar.getInstance(cst);
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    public static String todayStr() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new java.util.Date());
    }

    public static boolean isCheckedToday(JSONObject acc) {
        if (acc == null) return false;
        String sk = acc.optString("siteKey", "");
        if ("anyrouter-top".equals(sk) || sk.contains("anyrouter"))
            return SiteProtocol.hasSignInReceipt(acc, System.currentTimeMillis());
        JSONObject lc = acc.optJSONObject("lastCheckin");
        return lc != null && todayStr().equals(lc.optString("date", ""));
    }

    /**
     * 查用户日志。
     * 实测（justworker / kktoken / agentrouter 均为 New API 系）：
     *   - 过滤签到记录必须用 type=4，category=系统 这个参数站点根本不认（返回全部日志）；
     *   - 签到条目的 quota 字段恒为 0，金额只写在 content 文案里，
     *     形如「用户签到，获得额度 ＄20.642880 额度」（全角 ＄）；
     *   - 响应结构为 {data:{page,page_size,total,items:[...]}}。
     * @param category 传 "系统" 时自动改用 type=4（签到/系统额度变动）
     */
    public JSONObject logs(String key, String category, int limit) throws Exception {
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (limit <= 0 || limit > 200) limit = 20;
        boolean sysCat = "系统".equals(category);
        String path = "/api/log/self?" + (sysCat ? "type=4" : ("category=" + URLEncoder.encode(category, "UTF-8")))
                + "&limit=" + limit + "&page=1";
        JSONObject r = callWithAuth(site, key, "GET", path);
        int code = r.optInt("http");
        /* v0.4.4（审计方案A）：WAF 间歇拦截（实测 aliyun_waf 连拦数分钟后放行）——退避 2.5s 重试一次 */
        if (code == 503) {
            try { Thread.sleep(2500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            r = callWithAuth(site, key, "GET", path);
            code = r.optInt("http");
        }
        JSONObject out = new JSONObject().put("ok", code == 200).put("http", code);
        if (code != 200) out.put("message", httpHint(code));
        JSONArray rows = new JSONArray();
        JSONObject lastBonus = null;
        if (code == 200) {
            JSONObject d = dd(r);
            JSONArray list = d.optJSONArray("items");
            if (list == null) list = d.optJSONArray("list");
            if (list == null) list = d.optJSONArray("data");
            if (list == null) {
                JSONObject wrap = r.optJSONObject("data");
                if (wrap != null) list = wrap.optJSONArray("data");
            }
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String text = o.optString("content", o.optString("description", ""));
                long q = o.optLong("quota", 0);
                double usdFromText = parseUsdInText(text);
                JSONObject row = new JSONObject()
                        .put("time", o.optString("created_at", o.optString("time", "")))
                        .put("category", o.optString("category", category))
                        .put("type", o.optInt("type", -1))
                        .put("text", text)
                        .put("quota", q)
                        .put("usd", usdFromText);
                rows.put(row);
                /* 只认「签到」类文案：同为 type=4 的还有注册赠送、邀请赠送，
                 * 那些不是每日签到，不能拿来当今日已签的依据。
                 * v0.4.5 根因修复：接口按时间倒序（最新在前），旧代码取
                 * 「最后一条匹配」=最旧记录（实测 08-28 覆盖了 09-07），
                 * 导致今日已签却判定「非今日」。改为命中第一条（最新）即停。 */
                if (isCheckinText(text) && lastBonus == null) lastBonus = row;
            }
        }
        out.put("rows", rows);
        out.put("lastBonus", lastBonus == null ? JSONObject.NULL : lastBonus);
        return out;
    }
    /** 是否为「每日签到」类文案（排除注册赠送/邀请赠送等同类型条目） */
    static boolean isCheckinText(String text) {
        if (text == null || text.isEmpty()) return false;
        if (!(text.contains("签到") || text.toLowerCase(java.util.Locale.US).contains("check-in")
                || text.toLowerCase(java.util.Locale.US).contains("checkin"))) return false;
        return !(text.contains("注册") || text.contains("邀请") || text.contains("兑换"));
    }
    /** 从日志文案里取美元金额：「获得额度 ＄20.642880 额度」→ 20.64（全角/半角 $ 均可） */
    static double parseUsdInText(String text) {
        if (text == null || text.isEmpty()) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[＄$]\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        if (m.find()) {
            try { return Math.round(Double.parseDouble(m.group(1)) * 100.0) / 100.0; }
            catch (Exception ignored) {}
        }
        return -1;
    }

    public void runAllOnce() { runScheduled(false); }

    /** 八点专属任务只能触达AnyRouter，不牵连其他站点的授权流程。 */
    public void runAnyRouterOnce() { runScheduled(true); }

    private void runScheduled(boolean anyOnly) {
        if (!scheduleAllowsToday()) {
            store.opLog("", "", "定时签到", "info", "今日跳过（仅工作日执行）", "", "cron");
            return;
        }
        JSONArray sites = store.config().optJSONArray("sites");
        if (sites == null) return;
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            if (anyOnly && !SiteProtocol.isAnyRouter(site)) continue;
            if (SiteProtocol.isAnyRouter(site) && !SiteProtocol.rewardWindowOpen(System.currentTimeMillis())) {
                store.opLog(site.optString("key"), "", "定时签到", "info", "AnyRouter等待北京时间08:00后领取", "未请求领取，未标记本日完成", "cron");
                continue;
            }
            final String sKey = site.optString("key", "");
            JSONArray accs = site.optJSONArray("accounts");
            if (accs == null) continue;
            boolean auto = isAutoCheckin(site);
            boolean webOnly = isWebOnly(site);
            for (int j = 0; j < accs.length(); j++) {
                JSONObject tk = accs.optJSONObject(j);
                if (tk == null) continue;
                final String key = tk.optString("key");
                if (key.isEmpty()) continue;
                if (isCheckedToday(tk)) {
                    store.appendLog(sKey, key, "cron-skip", "今日已签");
                    /* v0.4.5：跳过也写 opLog，用户能看到定时任务确实跑了（只是跳过） */
                    store.opLog(sKey, key, "定时签到", "info", "今日已签，跳过", "", "auto");
                    continue;
                }
                try {
                    if (webOnly) {
                        store.appendLog(sKey, key, "cron-skip", "网页手动型站点，需人工操作");
                        store.opLog(sKey, key, "定时签到", "info",
                                "跳过（该站需网页手动签到）", "", "cron");
                    } else if (auto) {
                        final CountDownLatch latch = new CountDownLatch(1);
                        final String[] ev = { "cron-checkin-fail" };
                        final String[] dt = { "未返回" };
                        OffscreenCheckin.run(ctx, sKey, key, 100, (ok, already, reward, rewardKnown, msg) -> {
                            ev[0] = ok ? (already ? "cron-checkin-already" : "cron-checkin-ok") : "cron-checkin-fail";
                            if (!ok) dt[0] = msg == null ? "" : msg;
                            else if (already) dt[0] = (msg == null || msg.isEmpty()) ? "今日已签" : msg;
                            else dt[0] = rewardKnown ? ("奖励 $" + Ui.usd(reward)) : "签到成功";
                            latch.countDown();
                        });
                        if (!latch.await(110, TimeUnit.SECONDS)) { ev[0] = "cron-checkin-fail"; dt[0] = "等待超时"; }
                        store.appendLog(sKey, key, ev[0], dt[0]);
                        store.opLog(sKey, key, "定时签到",
                                ev[0].endsWith("fail") ? "err" : "ok", dt[0], "", "cron");
                        /* v0.4.6：定时签到完成后刷新三额度（签到奖励到账后的变化要反映到界面）。
                         * 与手动签到 applyCheckinResult→refreshOne 对齐。 */
                        if (!ev[0].endsWith("fail")) {
                            try {
                                JSONObject st = status(key);
                                if (SiteProtocol.canStoreStatus(st)) {
                                    JSONObject patch = new JSONObject().put("lastStatus", st);
                                    if (st.optBoolean("todayChecked", false) && !SiteProtocol.isAnyRouter(store.findSite(sKey))) {
                                        JSONObject lc2 = new JSONObject()
                                                .put("date", todayStr())
                                                .put("time", System.currentTimeMillis());
                                        if (st.optBoolean("todayRewardKnown", false))
                                            lc2.put("reward", st.optDouble("todayRewardUSD", 0));
                                        if ("api_sign_in".equals(st.optString("checkinSource", ""))) lc2.put("source", "api_sign_in");
                                        patch.put("lastCheckin", lc2);
                                    }
                                    store.patchAccount(key, patch);
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        /* v1.0.3：重登型站（AgentRouter/JustDoWork）定时签到 =
                         * 先 status() 检测今日奖励；无今日奖励则登出→SilentAuth 重登→再探测。
                         * 非重登型 login 站保持原保活逻辑。 */
                        JSONObject r = status(key);
                        int hc = r == null ? 0 : r.optInt("http");
                        boolean reloginSite = needsReloginCheckin(site);
                        if (reloginSite && SiteProtocol.canStoreStatus(r) && !r.optBoolean("todayChecked", false)) {
                            store.appendLog(sKey, key, "cron-relogin", "今日无奖励，执行登出重登签到");
                            store.opLog(sKey, key, "定时签到", "info",
                                    "今日无签到奖励，登出旧会话并重新登录触发发放", "", "cron");
                            /* 登出必须确认结果——logoutSession 先续期再登出并复核，无法确认则中止，
                             * 绝不建新会话（防会话数超限）。 */
                            int lo = logoutSession(key);
                            store.appendLog(sKey, key, "cron-relogin-logout", ReauthManager.describe(lo));
                            if (!ReauthManager.mayProceed(lo)) {
                                store.opLog(sKey, key, "定时签到", "err",
                                        "旧会话注销未确认，已跳过重登", ReauthManager.describe(lo) + "；不建立新会话", "cron");
                                continue;
                            }
                            final CountDownLatch rl = new CountDownLatch(1);
                            final String[] rlEv = { "cron-relogin-fail" };
                            final String[] rlDt = { "未返回" };
                            SilentAuth.run(ctx, sKey, key, (ok, needUi, user, msg) -> {
                                if (ok) rlEv[0] = "cron-relogin-ok";
                                else if (needUi) { rlEv[0] = "cron-relogin-ui"; rlDt[0] = msg == null ? "需人工授权" : msg; }
                                else { rlEv[0] = "cron-relogin-fail"; rlDt[0] = msg == null ? "" : msg; }
                                rl.countDown();
                            });
                            if (!rl.await(110, TimeUnit.SECONDS)) { rlEv[0] = "cron-relogin-fail"; rlDt[0] = "等待超时"; }
                            store.appendLog(sKey, key, rlEv[0], rlDt[0]);
                            store.opLog(sKey, key, "定时签到",
                                    rlEv[0].endsWith("ok") ? "ok" : "err", rlDt[0], "", "cron");
                            /* 重登后重新探测一次，成功则落库今日已签+额度 */
                            if ("cron-relogin-ok".equals(rlEv[0])) {
                                JSONObject r2 = status(key);
                                int hc2 = r2 == null ? 0 : r2.optInt("http");
                                if (SiteProtocol.canStoreStatus(r2)) {
                                    JSONObject patch = new JSONObject().put("lastStatus", r2);
                                    if (r2.optBoolean("todayChecked", false)) {
                                        JSONObject lc2 = new JSONObject()
                                                .put("date", todayStr())
                                                .put("time", System.currentTimeMillis());
                                        if (r2.optBoolean("todayRewardKnown", false))
                                            lc2.put("reward", r2.optDouble("todayRewardUSD", 0));
                                        patch.put("lastCheckin", lc2);
                                    }
                                    store.patchAccount(key, patch);
                                }
                                boolean verified = SiteProtocol.canStoreStatus(r2) && r2.optBoolean("todayChecked", false);
                                store.opLog(sKey, key, "定时签到", verified ? "ok" : "err",
                                        verified ? "北京时间当日奖励已核验" : "重登成功但未发现北京时间当日奖励记录",
                                        "刷新成功不等于奖励到账", "cron");
                                store.appendLog(sKey, key, "cron-login-refresh", "http=" + hc2 + ";verified=" + verified);
                            }
                        } else {
                            /* v0.4.6：登录保活改走 status()，额度同时写入 lastStatus */
                            store.appendLog(sKey, key, "cron-login-refresh", "http=" + hc);
                            boolean valid = SiteProtocol.canStoreStatus(r);
                            store.opLog(sKey, key, "定时刷新",
                                    valid ? "ok" : "err",
                                    valid ? "登录保活成功" : (r == null ? httpHint(hc) : r.optString("message", httpHint(hc))), "", "cron");
                            if (valid) {
                                /* v1.1.23：保活分支此前只写 lastStatus、不写 lastCheckin，导致：
                                 * (1) 服务端已 todayChecked 的账号(如 AgentRouter L站)标签永远停在「待签」；
                                 * (2) isCheckedToday 恒 false → 打开 App 的补跑判定 ranToday 恒 false →
                                 *     「定时签到」每次开 App 都重复触发。现补齐 lastCheckin 落库(与
                                 * MainActivity.buildStatusPatch 同款受保护逻辑)：todayChecked 则置已签，
                                 * 服务端明确未签则清脏徽章；AnyRouter 回执(api_sign_in/状态未知)绝不改写。 */
                                try {
                                    JSONObject patch = new JSONObject().put("lastStatus", r);
                                    boolean receiptOnly = "api_sign_in".equals(r.optString("checkinSource", ""))
                                            || !r.optBoolean("checkinStateKnown", true);
                                    if (!receiptOnly) {
                                        if (r.optBoolean("todayChecked", false)) {
                                            JSONObject lc = new JSONObject()
                                                    .put("date", todayStr())
                                                    .put("time", System.currentTimeMillis());
                                            if (r.optBoolean("todayRewardKnown", false))
                                                lc.put("reward", r.optDouble("todayRewardUSD", 0));
                                            patch.put("lastCheckin", lc);
                                        } else if (r.optBoolean("checkinStateKnown", true)) {
                                            patch.put("lastCheckin", JSONObject.NULL);   // 服务端明确未签：清脏徽章
                                        }
                                    }
                                    store.patchAccount(key, patch);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception e) {
                    store.appendLog(sKey, key, "cron-error", String.valueOf(e.getMessage()));
                    store.opLog(sKey, key, "定时任务", "err", "执行异常", String.valueOf(e.getMessage()), "cron");
                } catch (Throwable t) {
                    store.appendLog(sKey, key, "cron-error", "fatal: " + t);
                    store.opLog(sKey, key, "定时任务", "err", "严重异常", String.valueOf(t), "cron");
                }
            }
        }
        /* v1.1.23：完整跑完一轮（非 AnyRouter 专属 8 点任务）→ 标记今日已运行。
         * 补跑判定用它「一天只触发一次」，不再因某个死会话账号签不上而反复触发。 */
        if (!anyOnly) store.markCronRun(todayStr());
    }

    public static class CheckWorker extends Worker {
        public CheckWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
        @NonNull @Override public Result doWork() {
            try { new Engine(getApplicationContext()).runAllOnce(); }
            catch (Throwable t) { return Result.retry(); }
            return Result.success();
        }
    }

    public static void schedule(Context c) {
        JSONObject sch;
        try { sch = new Store(c).schedule(); } catch (Exception e) { sch = new JSONObject(); }
        boolean enabled = sch.optBoolean("enabled", true);
        WorkManager wm = WorkManager.getInstance(c);
        AnyRouterWorker.sync(c);
        if (!enabled) { wm.cancelUniqueWork("justsign-check"); return; }

        String mode = sch.optString("mode", "daily");
        long periodMin;
        long initialDelayMin = 0;
        if ("interval".equals(mode)) {
            int h = Math.max(1, Math.min(24, sch.optInt("intervalHours", 12)));
            periodMin = h * 60L;
        } else {
            periodMin = 24 * 60L;
            initialDelayMin = minutesUntil(sch.optInt("hour", 8), sch.optInt("minute", 30));
        }
        if (periodMin < 15) periodMin = 15;

        PeriodicWorkRequest.Builder b = new PeriodicWorkRequest.Builder(
                CheckWorker.class, periodMin, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build());
        if (initialDelayMin > 0) b.setInitialDelay(initialDelayMin, TimeUnit.MINUTES);

        /* v0.4.4（审计方案C）：CANCEL_AND_REENQUEUE 每次打开 App 都重置 initialDelay，
         * 频繁开 App 会把任务永远推迟。改为：配置指纹变化才重排，否则 KEEP 保持原计划。 */
        String fp = sch.optString("mode", "daily") + ":" + sch.optInt("hour", 8)
                + ":" + sch.optInt("minute", 30) + ":" + sch.optInt("intervalHours", 12);
        android.content.SharedPreferences sp = c.getSharedPreferences("sched", 0);
        boolean fpChanged = !fp.equals(sp.getString("fp", ""));
        wm.enqueueUniquePeriodicWork("justsign-check",
                fpChanged ? ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                        : ExistingPeriodicWorkPolicy.KEEP, b.build());
        if (fpChanged) sp.edit().putString("fp", fp).apply();
    }

    private static long minutesUntil(int hour, int minute) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.set(java.util.Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour)));
        t.set(java.util.Calendar.MINUTE, Math.max(0, Math.min(59, minute)));
        t.set(java.util.Calendar.SECOND, 0);
        t.set(java.util.Calendar.MILLISECOND, 0);
        if (!t.after(now)) t.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long diff = (t.getTimeInMillis() - now.getTimeInMillis()) / 60000L;
        return Math.max(1, diff);
    }

    private boolean scheduleAllowsToday() {
        try {
            JSONObject sch = store.schedule();
            if (!"weekday".equals(sch.optString("mode", "daily"))) return true;
            int dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY;
        } catch (Exception e) { return true; }
    }
}