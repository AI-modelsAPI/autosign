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
    public static final String UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    /** v0.x：WebView/离屏通道历史上用的是 Chrome/131.0（无 .0.0 后缀）变体，与上面 UA 一字之差。
     * WAF 对 UA 敏感，两者不可互换，故单独保留此常量，仅收敛 5 处离屏类里逐字相同的副本。 */
    public static final String UA_OFFSCREEN = "Mozilla/5.0 (Linux; Android 16; PHZ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36";

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
    /**
     * 指定主机基址的请求（福利站等子站点用）。
     *
     * 背景（站点实测）：福利站是主站的子站点，接口在另一台主机上
     * （主站 api.fengwind.com，福利站 api-welfalre.fengwind.com），
     * 令牌也各自独立（主站 auth_token / 福利站 welfare_token）。
     * 这里只替换 base，其余（代理、超时、WAF 处理）与原路径完全一致。
     */
    private JSONObject callAt(String baseUrl, String token, String cookie, String accSiteUserId,
                              String method, String path, String jsonBody) throws Exception {
        String b = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (b.isEmpty()) throw new Exception("子站点地址为空");
        JSONObject proxy = store.config().optJSONObject("proxy");
        boolean useProxy = proxy != null && proxy.optBoolean("enabled");
        if (useProxy && !ProxyDetect.reachable(proxy.optString("host", "127.0.0.1"),
                proxy.optInt("port", 10808), 1200)) useProxy = false;
        lastError = "";
        return attempt(b + path, token, cookie == null ? "" : cookie,
                accSiteUserId == null ? "" : accSiteUserId,
                method, buildClient(useProxy, proxy), jsonBody);
    }

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
        return callInternal(site, token, cookie, accSiteUserId, method, path, jsonBody);
    }

    /** 原 call 的实现体（改名以便在其前面插入 callAt）。 */
    private JSONObject callInternal(JSONObject site, String token, String cookie, String accSiteUserId, String method, String path, String jsonBody) throws Exception {
        JSONObject proxy = store.config().optJSONObject("proxy");
        boolean useProxy = proxy != null && proxy.optBoolean("enabled");
        /* v1.3.1（真机现场 2026-10-02）：代理没在跑就直接走直连，别先把请求塞进死代理等 10 秒超时。
         * 与 WebView 路径（先探活再决定挂不挂代理）对齐；失败后再靠下面的白名单回退太窄——
         * 邀请划转、会话续期这类 POST 都不在白名单里，只会留下一条「代理与直连均不可达」的
         * 误导性报错（直连其实没试过）。 */
        if (useProxy && !ProxyDetect.reachable(proxy.optString("host", "127.0.0.1"),
                proxy.optInt("port", 10808), 1200)) useProxy = false;
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
        // v1.3.0（用户实证 SeekAI 令牌签到挂在 127.0.0.1:10808）：代理未连通时，签到/刷新类
        // 幂等请求(POST /api/user/checkin、GET/POST /api/user/sign_in)也需直连回退——
        // 原白名单只含只读 GET，令牌签到 POST 不在内，代理一挂就报"网络请求失败"。
        // 回退判据：ReadFallback.allowed（安全 GET） 或 幂等签到端点(白名单+sign_in/checkin 精确匹配)。
        boolean checkinLike = ("/api/user/checkin".equals(path) || "/api/user/sign_in".equals(path))
                && (jsonBody == null || "{}".equals(jsonBody) || jsonBody.isEmpty());
        if (r == null && useProxy && (ReadFallback.allowed(method, path) || checkinLike))
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
                /* v1.3.2：超时 40s（原 25s）。脚本内部对 v2 盾的等待上限是 30s，
                 * 外层若仍按 25s 掐，JS 永远跑不完 → 必然「未拿到可用数据」。
                 * 内外层必须满足：外层 > 内层，否则再放宽内层也无效。 */
                JSONObject recovered = OffscreenReadRunner.read(ctx, site.optString("baseUrl"),
                        site.optString("key"), accountKey, uid,
                        path, site.optString("baseUrl"), 40);
                if (ReadFallback.usable(path, recovered, uid)) {
                    store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "info",
                            "离屏读取成功", "path=" + path.split("\\?", 2)[0] + "; oauth=false", "auto");
                    return recovered;
                }
                /* v1.3.2：把离屏失败的具体 reason 写进日志（旧实现只写「未拿到可用数据」，
                 * 真机排查 v2 盾时完全没有线索）。reason 为固定类别词，不含 URL/Cookie/正文。 */
                String offReason = recovered == null ? "no-result"
                        : recovered.optString("reason", recovered.optString("http", ""));
                String offDiag = recovered == null ? "" : recovered.optString("diag", "");
                store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "warn",
                        "离屏未拿到可用数据，保留原始错误",
                        "path=" + path.split("\\?", 2)[0]
                                + "; offscreenReason=" + (offReason.isEmpty() ? "-" : offReason)
                                + (offDiag.isEmpty() ? "" : "; diag=" + offDiag), "auto");
                /* v1.3.2 重试（有依据，不是猜时间）：
                 * 离屏用的是与本请求同一个「站点×账号」分区（P0-1 修复后），
                 * 若离屏已把 WAF 质询跑完，放行 Cookie 就已落在该分区里 ——
                 * 此时重发一次原生请求即可通过。判据是「离屏确实跑过质询」这一可观测事实，
                 * 而不是「等了多久」。仅对幂等只读 GET、且只重试一次。 */
                /* v1.3.3：删掉 v1.3.2 那条「需人工则重试无意义」——前提（滑块）已被
                 * 第一手复测证伪。未过盾/未就绪一律视为可重试（放行 Cookie 可能刚落地）。 */
                boolean offscreenRanChallenge = recovered != null
                        && (recovered.optBoolean("retryable", false)
                            || "waf-not-cleared".equals(offReason)
                            || "waf-need-navigate".equals(offReason)
                            || "identity-not-ready".equals(offReason)
                            || "no-result".equals(offReason));
                if (offscreenRanChallenge && ReadFallback.allowed(method, path)) {
                    store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "info",
                            "离屏已跑过质询，重发原生请求（放行 Cookie 应已落在本账号分区）",
                            "path=" + path.split("\\?", 2)[0] + "; retry=1", "auto");
                    JSONObject afterOffscreen = null;
                    try { afterOffscreen = callWithAuthNative(site, accountKey, method, path, jsonBody); }
                    catch (Exception ignored) {}
                    if (afterOffscreen != null && ReadFallback.usable(path, afterOffscreen, uid)) {
                        store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "info",
                                "离屏过盾后原生请求成功", "path=" + path.split("\\?", 2)[0], "auto");
                        return afterOffscreen;
                    }
                }
            } catch (Exception e) {
                store.opLog(site.optString("key"), accountKey, "只读离屏兜底", "err",
                        "离屏执行异常，保留原始错误", "reason=" + ReadFallback.category(e), "auto");
            }
        }
        /* 只读兜底救不回「已失效的会话」（WAF 归一化 503 / 401 / 403）时，对 linuxdo 站复用论坛会话
         * 静默重建站点会话，成功后重试一次（禁止再次静默避免循环）。仅响应型会话失效信号触发，
         * 传输层异常（网络断）不触发——WebView 走同一出口也救不了网络问题。
         * v1.3.0 修复（用户实证「any站签到时保活没修好」）：静默重授权=保活，会把失效会话重建。
         * 对「登录/重登才发奖」的站（AnyRouter / needsReloginCheckin），今日未签时绝不保活——
         * 否则会话被永久续着，登出重登领奖的前提被破坏（永远登不出、领不到奖）。
         * 今日已签后才允许保活（维持会话供查询）。 */
        if (allowReauth && sessionLikelyDead(response, failure)) {
            JSONObject acc = store.findAccount(accountKey);
            boolean rewardSite = SiteProtocol.isAnyRouter(site) || Engine.needsReloginCheckin(site);
            /* 解开循环依赖：原判据里 keepAliveOk 依赖 isCheckedToday（本地「已签」标记），
             * 而「是否已签」本身又要读站点——端点失效时读不到 → 永远判定「未签」→
             * 永不重建会话 → 永久签不上（真机复现）。
             * 现在只用【响应证据】：能走到这里说明响应已是失效信号（401/403/503/WAF），
             * 不存在「保活把会话续着」的情况，因此直接允许重建。
             * 「别把活会话续着」的原意图，改由 statusLocked 在【会话可读】时判断。 */
            if (acc != null && "linuxdo".equals(SiteProtocol.provider(site, acc))) {
                store.sysLog(site.optString("key", ""), accountKey, "静默重授权", "info",
                        "会话失效，尝试重建",
                        "rewardSite=" + rewardSite + "; method=" + method
                                + "; path=" + path.split("\\?", 2)[0]);
                int rc = OffscreenReauth.attempt(ctx, store, site, accountKey, 30);
                store.sysLog(site.optString("key", ""), accountKey, "静默重授权", "info",
                        "重建结果", "rc=" + rc + "; ok=" + (rc == OffscreenReauth.REAUTH_OK));
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
        return callWithAuthNative(site, accountKey, method, path, jsonBody, false);
    }

    /**
     * @param ignoreSystemToken true = 忽略账号的系统令牌，只按站点会话凭据（token/siteCookie）请求。
     *   仅供「重新授权」预检使用（见 {@link #sessionStatus}）。
     */
    private JSONObject callWithAuthNative(JSONObject site, String accountKey, String method, String path, String jsonBody, boolean ignoreSystemToken) throws Exception {
        JSONObject acc = store.findAccount(accountKey);
        String token = cachedToken(accountKey);
        if (token.isEmpty() && acc != null) token = clean(acc.optString("token", ""));
        String cookie = acc == null ? "" : clean(acc.optString("siteCookie", ""));
        String siteUserId = acc == null ? "" : clean(acc.optString("siteUserId", ""));

        /* 功能1+2：「系统 token」分流（v1.3.0 迁账号级）。用户在账号的系统令牌区填了 systemToken，
         * 则该账号所有业务请求直接用它当 Bearer（systemUserId 非空时作 New-Api-User），
         * 旁路 refresh-cookie 续期链与 401 再换 token。
         * 老数据兼容：账号未填时回退读站点级 site.systemToken（site 只读，不再写）。
         * systemToken 为空时下面原逻辑一字不动。 */
        String sysToken = acc == null ? "" : clean(acc.optString("systemToken", ""));
        if (sysToken.isEmpty()) sysToken = site == null ? "" : clean(site.optString("systemToken", ""));
        if (!sysToken.isEmpty() && !ignoreSystemToken) {
            token = sysToken;
            String sysUid = acc == null ? "" : clean(acc.optString("systemUserId", ""));
            if (sysUid.isEmpty()) sysUid = site == null ? "" : clean(site.optString("systemUserId", ""));
            if (!sysUid.isEmpty()) siteUserId = sysUid;   // 否则沿用账号 siteUserId
            return call(site, token, cookie, siteUserId, method, path, jsonBody);
        }

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
        boolean on = proxy != null && proxy.optBoolean("enabled");
        /* v1.3.1（真机现场 2026-10-02）：代理没在跑时别再往死代理里塞请求。
         * 此前这里无条件挂 SOCKS，代理一停（127.0.0.1:10808 ECONNREFUSED）所有原生请求
         * 报 SocketException —— 其中最要命的是 POST /api/user/auth/refresh（会话续期），
         * 它没有 call() 那层只读直连回退，于是「页面打得开（WebView 会先探活再决定挂不挂代理）、
         * 会话续期却永远失败」。WebView 路径一向先探活（OffscreenCheckin/SilentAuth/OffscreenReauth），
         * 这里对齐同一套行为：探不通就直接走直连。 */
        if (on && !ProxyDetect.reachable(proxy.optString("host", "127.0.0.1"),
                proxy.optInt("port", 10808), 1200)) on = false;
        return buildClient(on, proxy);
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
                /* v1.3.3 回退 v1.3.2 的错误结论：曾据「真机 Chrome 打开裸 /api/user/self
                 * 出现滑块」推断该站要求人工验证。第一手复测证明那是**测试姿势造成的**
                 * ——只有直接导航裸 API 路径（无有效会话、无站点页面上下文）才触发；
                 * 正常流程（站点页面内 fetch / 有会话）不触发，用户也从没见过滑块。
                 * 故不再区分「需人工」，回到如实描述。 */
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
    /**
     * v1.3.5：fengwind 系（非 New API）站点的会话读取回退。
     *
     * 背景（真机 + 站点前端 bundle 实证）：这一族站点根本没有 /api/user/self（三套子域全 404），
     * 会话端点是 /api/me（福利站/捐赠站）与 /api/v1/auth/me（主站），
     * 响应信封是 {code:0,message:"success",data:{...}}，USD 字段名也与 New API 不同。
     *
     * 本方法把结果归一化成 New API 形状回传，使 statusLocked 的下游逻辑一字不改即可复用：
     *   {http:200, data:{success:true, data:{id, username, quota, used_quota}}}
     * 其中 quota/used_quota 由站点给出的 USD 值乘 unit 反算（与 New API 的 quota 同单位）。
     *
     * 诚实原则：字段名认不出就返回 null（保留上次额度），绝不编造数字。
     * 诊断日志只记【字段名与是否命中】，不记字段值 —— 值可能含凭据。
     */
    private JSONObject foreignSelf(JSONObject site, String key, long unit) {
        /* 福利站（子站点）用独立主机 + 独立令牌 welfare_token；
         * 主站/捐赠站用站点 baseUrl + 账号 token。 */
        String welfare = SiteProfile.welfareUrl(site);
        String host = welfare.isEmpty() ? "" : welfare;
        String token;
        if (!welfare.isEmpty()) {
            JSONObject acc0 = store.findAccount(key);
            token = SiteProfile.welfareToken(acc0);
            if (token.isEmpty()) {
                /* 首登人工、后续静默：这里复用主站会话走 SSO 拿 welfare_token。
                 * 主站会话也没有时才如实报「需要人工」。 */
                store.sysLog(site.optString("key", ""), key, "刷新", "info",
                        "福利站令牌缺失，尝试静默 SSO",
                        "welfare=" + SiteProfile.hostOf(welfare));
                int sso = WelfareSso.attempt(ctx, store, site, key, 40);
                if (sso == WelfareSso.SSO_OK) {
                    token = SiteProfile.welfareToken(store.findAccount(key));
                }
                if (token.isEmpty()) {
                    store.sysLog(site.optString("key", ""), key, "刷新", "warn",
                            "福利站令牌仍缺失", WelfareSso.describe(sso)
                                    + "; welfare=" + SiteProfile.hostOf(welfare));
                    return null;
                }
            }
        } else {
            token = cachedToken(key);
            if (token.isEmpty()) {
                JSONObject acc = store.findAccount(key);
                if (acc != null) token = clean(acc.optString("token", ""));
            }
            if (token.isEmpty()) return null;
        }
        long u = unit > 0 ? unit : QUOTA_PER_UNIT_DEFAULT;

        /* 端点与字段全部来自协议表（SiteProfile），此处不再有站点字面量。 */
        SiteProfile.Kind kind = SiteProfile.kindOf(site);
        String hit = "";
        JSONObject mapped = null;
        long t0 = System.currentTimeMillis();
        for (String p : SiteProfile.selfPaths(site)) {
            JSONObject r = null;
            long s0 = System.currentTimeMillis();
            try {
                r = welfare.isEmpty()
                        ? call(site, token, "", "", "GET", p)
                        : callAt(host, token, "", "", "GET", p, null);
            } catch (Exception ignored) {}
            int code = r == null ? 0 : r.optInt("http");
            store.sysLog(site.optString("key", ""), key, "刷新", "info",
                    "会话端点探测",
                    "kind=" + SiteProfile.kindName(kind) + "; path=" + p + "; http=" + code
                            + "; ms=" + (System.currentTimeMillis() - s0));
            if (code != 200) continue;
            JSONObject m = mapForeignSelf(r, u, kind);
            if (m == null) continue;
            mapped = m; hit = p; break;
        }
        if (mapped == null) return null;

        String uid = mapped.optString("_id", "");
        mapped.remove("_id");
        JSONObject norm = mapped.optJSONObject("data");
        store.sysLog(site.optString("key", ""), key, "刷新", "info",
                "非 New API 会话读取成功",
                "kind=" + SiteProfile.kindName(kind) + "; path=" + hit
                        + "; avail=" + (norm != null && norm.has("quota"))
                        + "; used=" + (norm != null && norm.has("used_quota"))
                        + "; id=" + !uid.isEmpty()
                        + "; totalMs=" + (System.currentTimeMillis() - t0));
        return mapped;
    }

    /**
     * 把非 New API 站的会话响应归一化成 New API 形状：
     *   {http:200, data:{success:true, data:{id, username, quota, used_quota}}}
     * 其中 quota/used_quota 由站点给出的 USD 值乘 unit 反算（与 New API 同单位）。
     *
     * 抽成 static 纯函数是为了能被单测直接覆盖 —— 这段映射写错的症状是
     * 「卡片三个额度全空」，只在运行时暴露，编译期看不出来。
     * 认不出任何金额字段时返回 null（保留上次额度），绝不编造数字。
     *
     * @return 归一化响应；识别不出金额时 null。附带 "_id" 键供调用方记日志。
     */
    static JSONObject mapForeignSelf(JSONObject envelope, long unit) {
        return mapForeignSelf(envelope, unit, SiteProfile.Kind.SUB2API);
    }

    /**
     * 按站点形态归一化。字段候选来自协议表（SiteProfile.balanceFields/usedFields），
     * 这里只负责「解开信封 + 取第一个有限值」，不再内嵌任何站点字段字面量。
     */
    static JSONObject mapForeignSelf(JSONObject envelope, long unit, SiteProfile.Kind kind) {
        if (kind == null) kind = SiteProfile.Kind.SUB2API;
        JSONObject body = envelope == null ? null : envelope.optJSONObject("data");
        if (body == null) return null;
        Object innerRaw = body.opt("data");
        JSONObject user = innerRaw instanceof JSONObject ? (JSONObject) innerRaw : body;
        JSONObject nested = user.optJSONObject("user");
        if (nested != null) user = nested;
        /* 福利站把金额放在 /api/level 的 profile 子对象下（真机实证）。 */
        JSONObject profile = user.optJSONObject("profile");
        if (profile != null) user = profile;

        Double avail = firstFinite(user, SiteProfile.balanceFields(kind));
        Double used = firstFinite(user, SiteProfile.usedFields(kind));
        /* 必须真的读到「可用额」才接受这次结果：卡片的头条数字就是它，
         * 拿一个凑出来的 0 顶上，等于替站点断言「你的余额是 0」——
         * 比空白更有害。读不到就返回 null，保留上次额度并如实报错。
         * （「累计已用」缺失仍按 0 显示，与既有 New API 站行为一致。） */
        if (avail == null) return null;
        double a = avail;
        double b = used == null ? 0 : used;
        String uid = String.valueOf(user.opt("id"));
        if (uid.isEmpty() || "null".equals(uid)) {
            Object alt = user.opt("sub2api_user_id");
            if (alt == null) alt = user.opt("user_id");
            if (alt != null) uid = String.valueOf(alt);
        }
        String nm = user.optString("display_name", "");
        if (nm.isEmpty()) nm = user.optString("username", "");
        try {
            return new JSONObject().put("http", 200)
                    .put("_id", uid)
                    .put("data", new JSONObject()
                            .put("success", true)
                            .put("data", new JSONObject()
                                    .put("id", uid)
                                    .put("username", nm)
                                    .put("quota", a * unit)
                                    .put("used_quota", b * unit)));
        } catch (Exception e) { return null; }
    }


    /** 取第一个存在且有限的数值字段；全部缺失返回 null（不编造）。 */
    private static Double firstFinite(JSONObject o, String... names) {
        for (String n : names) {
            if (o == null || !o.has(n) || o.isNull(n)) continue;
            Object v = o.opt(n);
            if (!(v instanceof Number) && !(v instanceof String)) continue;
            try {
                double d = Double.parseDouble(String.valueOf(v));
                if (Double.isFinite(d)) return d;
            } catch (Exception ignored) {}
        }
        return null;
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
        /* 判据已下沉到协议表（SiteProfile.CheckinMode），不再靠 key 字符串猜。
         * 保留此方法是为了让既有调用点语义不变；新代码请直接用 SiteProfile。 */
        return SiteProfile.marksCheckedOnCredit(SiteProfile.checkinMode(site));
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

    /* ================= 重新授权预检（只认站点会话） ================= */

    /**
     * v1.3.1（用户现场 2026-10-02）：「是否需要重新授权」只看站点会话凭据，与系统令牌无关。
     *
     * 此前预检复用 status()，而 status() 在账号填了系统令牌时拿它当 Bearer（v1.3.0 分流），
     * 于是：系统令牌有效 → 200 → 弹「无需重新授权」；但签到走的是浏览器会话（另一套凭据，
     * 09-22 就已过期）→ 恒报「授权已过期」。用户被夹在两条互相矛盾的提示之间，站点会话死了
     * 也修不回来（真机现场：该账号 09-10 之后再无一次成功授权）。
     *
     * 语义与 status() 对齐（ok/http/message），供 MainActivity 做三态判定：
     * valid → 无需授权；http=401 → 确定失效 → 重新授权；其余 → 交给用户决定。
     */
    public JSONObject sessionStatus(String key) throws Exception {
        JSONObject acc = store.findAccount(key);
        if (acc == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        resetTokenCache();
        JSONObject self;
        try {
            self = callWithAuthNative(site, key, "GET", "/api/user/self", null, true);
        } catch (Exception e) {
            String m = e.getMessage();
            return new JSONObject().put("ok", false).put("valid", false).put("http", 0)
                    .put("message", m == null || m.isEmpty() ? "网络异常" : m);
        }
        int http = self.optInt("http");
        boolean valid = SiteProtocol.validSelf(self, clean(acc.optString("siteUserId", "")));
        return new JSONObject().put("ok", valid && http == 200).put("valid", valid)
                .put("http", http).put("message", valid ? "" : self.optString("message", ""))
                .put("account", key).put("site", site.optString("name"));
    }

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
        /* 端点来自协议表：New API 站走 /api/user/self，非 New API 站由 foreignSelf 逐个探测。
         * 这里先用协议表的首个端点，避免对 fengwind 系发注定 404 的请求。 */
        SiteProfile.Kind selfKind = SiteProfile.kindOf(site);
        String primarySelfPath = SiteProfile.selfPaths(site)[0];
        JSONObject self = callWithAuth(site, key, "GET", primarySelfPath, null, false);
        int selfHttp = self.optInt("http");

        /*
         * v1.1.15 静默重授权自愈（仅 linuxdo 站，一轮刷新最多一次）：
         * ……（见下）
         * v1.3.0 修复（用户实证）：静默重授权是「保活」——它会把失效会话重建。
         * 但对「登录/重登才发奖」的站（AnyRouter / needsReloginCheckin），若今日尚未签到就保活，
         * 旧会话被永久续着，永远走不到「登出→重登领奖」那一步（用户：没签也保活，永远登不出）。
         * 因此：仅当「今日已签」后才允许保活式静默重授权；今日未签的发奖站不保活，留给重登流程登出。
         */
        boolean rewardSite = SiteProtocol.isAnyRouter(site) || Engine.needsReloginCheckin(site);
        /* v1.3.2（真机实测 agentrouter.org/L站-1 死结）：原守卫对「重登型发奖站」一律
         * 禁止静默重授权，本意是「别把活着的会话续着，否则永远走不到登出→重登领奖」。
         * 但当会话已经**读不出来**（503/401/403/WAF）时，根本不存在「保活」这回事：
         *   status() 读不到 → 重登流程中止（会话状态无法确认）→ 而唯一能重建会话的
         *   OffscreenReauth 又被本守卫挡死 → 该账号永久签不上（真机复现 3 次，稳定）。
         * 因此把守卫精确化：只在「能读到会话且今日未签」时才阻止保活；
         * 会话读不出来时放行重建 —— 这是重登流程的前置条件，不是保活。
         * 读得出来（HTTP 200 且业务 success=true）说明会话活着，原意图完整保留。 */
        boolean selfReadable = selfHttp == 200
                && self.optJSONObject("data") != null
                && self.optJSONObject("data").optBoolean("success", false);
        boolean keepAliveOk = !rewardSite || Engine.isCheckedToday(tk) || !selfReadable;
        /* v1.3.3：删掉 v1.3.2 那个基于错误结论的「需人工则跳过静默重授权」。
         * 真机第一手复测证明：正常流程没有滑块；离屏重授权失败的真实原因曾是
         * window.open 被 WebView 丢弃（已在本版修复），不是「必须人工拖动」。
         * 因此恢复静默重授权——这正是死会话自愈的唯一自动路径。 */
        if (allowSilentReauth && keepAliveOk
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

        /* v1.3.5 根因修复（真机 + 站点前端实证）：fengwind 系站点不是 New API，
         * /api/user/self 恒 404（三套子域实测全 404），真实会话端点各异：
         *   api.fengwind.com          → GET /api/v1/auth/me
         *   api-donation.fengwind.com → GET /api/me
         *   api-welfalre.fengwind.com → GET /api/me（用量在 /api/level）
         * 响应形状为 {code:0,message:"success",data:{...}}，USD 字段名也与 New API 不同。
         * 此前只认 /api/user/self + quota/used_quota → 这类站 validSelf 恒 false →
         * 卡片三个额度全空（用户实证「三个额度一个都没显示」）。
         * 仅在 New API 端点【不存在】（404/405）时回退，既有站点一字不动。 */
        /* 福利站配置存在时，额度在福利站（主站无额度接口，实测 /api/user/self 404）；
         * 其它非 New API 站按 404/405 回退。 */
        boolean needForeign = SiteProfile.hasWelfare(site)
                || (!SiteProtocol.validSelf(self, tk.optString("siteUserId", ""))
                    && (selfHttp == 404 || selfHttp == 405));
        if (needForeign) {
            JSONObject alt = foreignSelf(site, key,
                    store.siteMetaLong(sKey, "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT));
            if (alt != null) { self = alt; selfHttp = self.optInt("http"); }
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
                .put("availableUSD", Ui.round2(quota / unit))
                .put("usedUSD", Ui.round2(used / unit))
                .put("grantedUSD", Ui.round2((quota + used) / unit))
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
        if (todayUsed >= 0) out.put("todayUsed", Ui.round2(todayUsed));
        store.appendLog(sKey, key, "status", "http=" + selfHttp);

        if (selfHttp == 200) {
            /* v1.1.1：同轮复核不重复写额度文案（logSummary 控制） */
            if (logSummary) store.opLog(sKey, key, "刷新", "ok", "额度已更新",
                    "可用 $" + Ui.usd(Ui.round2(quota / unit))
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
    /* ================= 功能5：获取模型 + 模型测试 ================= */

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
        SiteProfile.Kind kind = SiteProfile.kindOf(site);
        SiteProfile.CheckinMode mode = SiteProfile.checkinMode(site);
        final String sKey = site.optString("key", "");
        /* 重登类：没有只读签到状态接口，以「本周期回执」为状态（原代码同一判据）。 */
        if (SiteProfile.marksCheckedOnCredit(mode) || SiteProtocol.isAnyRouter(site)) {
            boolean receipt = SiteProtocol.hasSignInReceipt(
                    store.findAccount(key), System.currentTimeMillis());
            return new JSONObject().put("checked", receipt).put("rewardKnown", false)
                    .put("stateUnknown", !receipt)
                    .put("message", receipt ? "本应用今日签到请求已确认" : "站点无只读签到状态接口");
        }
        String statusPath = SiteProfile.checkinStatusPath(kind);
        if (statusPath.isEmpty()) {
            store.sysLog(sKey, key, "签到状态", "info", "该站无只读签到状态接口",
                    "kind=" + SiteProfile.kindName(kind) + "; mode=" + SiteProfile.modeName(mode));
            return null;
        }
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
                .format(new java.util.Date());
        String fullPath = "/api/user/checkin".equals(statusPath)
                ? statusPath + "?month=" + URLEncoder.encode(month, "UTF-8")
                : statusPath;
        long cs0 = System.currentTimeMillis();
        JSONObject r = callWithAuth(site, key, "GET", fullPath);
        store.sysLog(sKey, key, "签到状态", "info", "查询签到状态",
                "path=" + statusPath + "; http=" + r.optInt("http")
                        + "; ms=" + (System.currentTimeMillis() - cs0));
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
                reward = raw >= 1000 ? Ui.round2(raw / (double) unit) : raw;
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

    /**
     * v1.3.0：系统令牌直连签到。POST /api/user/checkin（Bearer 由 callWithAuthNative 分流自动带）。
     * 仅在账号持有 systemToken（或老数据回退站点级）时调用，有令牌不回退 WebView，失败如实报错。
     * 返回 {ok, already, reward, rewardKnown, message}。
     */
    /** 账号是否持有系统令牌（账号级优先，老数据回退站点级）——有则签到/查询/一键/定时一律先走系统令牌 API。 */
    public static boolean hasSystemToken(JSONObject site, JSONObject acc) {
        if (acc != null && !acc.optString("systemToken", "").trim().isEmpty()) return true;
        return site != null && !site.optString("systemToken", "").trim().isEmpty();
    }

    public JSONObject systemTokenCheckin(String accountKey) throws Exception {
        JSONObject acc = store.findAccount(accountKey);
        JSONObject site = store.siteOfAccount(accountKey);
        if (acc == null || site == null) throw new Exception("账号或站点不存在");
        /* 校验令牌：账号优先、站点回退（与 callWithAuthNative 分流读取口径一致） */
        String sysToken = clean(acc.optString("systemToken", ""));
        if (sysToken.isEmpty()) sysToken = clean(site.optString("systemToken", ""));
        if (sysToken.isEmpty()) {
            return new JSONObject().put("ok", false).put("already", false)
                    .put("rewardKnown", false).put("message", "无系统令牌");
        }
        synchronized (opLock(accountKey)) {
            JSONObject r = callWithAuth(site, accountKey, "POST", "/api/user/checkin", "{}");
            int http = r.optInt("http");
            JSONObject d = dd(r);
            String msg = d == null ? "" : d.optString("message", "");
            boolean success = d != null && d.optBoolean("success", false);
            String lower = msg == null ? "" : msg.toLowerCase(java.util.Locale.US);
            boolean already = lower.contains("今日已签") || lower.contains("已签到")
                    || lower.contains("already checked") || lower.contains("already");
            if (http == 200 && (success || already)) {
                /* 成功/已签：尽量带出今日奖励金额（拿不到则 rewardKnown=false）落 lastCheckin */
                double reward = 0;
                boolean rewardKnown = false;
                try {
                    long unit = store.siteMetaLong(site.optString("key", ""), "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT);
                    JSONObject cs = checkinStatus(accountKey, unit);
                    if (cs != null) { reward = cs.optDouble("rewardUSD", 0); rewardKnown = cs.optBoolean("rewardKnown", false); }
                } catch (Exception ignored) {}
                markChecked(accountKey, reward, rewardKnown);
                return new JSONObject().put("ok", true).put("already", already)
                        .put("reward", reward).put("rewardKnown", rewardKnown)
                        .put("message", already ? (msg.isEmpty() ? "今日已签到" : msg)
                                : (rewardKnown && reward > 0 ? "签到成功" : (msg.isEmpty() ? "签到成功" : msg)));
            }
            return new JSONObject().put("ok", false).put("already", false).put("rewardKnown", false)
                    .put("message", !msg.isEmpty() ? msg : httpHint(http));
        }
    }

    public JSONObject checkin(String key) throws Exception {
        synchronized (opLock(key)) { return checkinLocked(key); }
    }
    private JSONObject checkinLocked(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        JSONObject site = store.siteOfAccount(key);
        if (tk == null || site == null) throw new Exception("账号或站点不存在");
        JSONObject out = new JSONObject();
        /* 签到方式来自协议表，不再靠散落的 if（isAutoCheckin/isWebOnly/needsRelogin…）。
         * 用户明确的三类：点按钮 / 重登即得 / 早八点重登入账无提示。 */
        SiteProfile.CheckinMode mode = SiteProfile.checkinMode(site);
        SiteProfile.Kind kind = SiteProfile.kindOf(site);
        final String sKey0 = site.optString("key", "");
        store.sysLog(sKey0, key, "签到", "info", "签到方式判定",
                "mode=" + SiteProfile.modeName(mode) + "; kind=" + SiteProfile.kindName(kind)
                        + "; site=" + sKey0);

        if (mode == SiteProfile.CheckinMode.WEB_ONLY) {
            out.put("ok", false).put("webOnly", true).put("rewardKnown", false)
               .put("message", "该站不开放签到接口，请点站点名打开网页手动操作");
            return out;
        }
        /* 需要真实浏览器上下文（福利站 Turnstile 等）：交后台签到窗口。 */
        if (SiteProfile.needsBrowserContext(mode, kind)) {
            out.put("ok", false).put("needWebview", true)
               .put("message", "该站签到需要浏览器环境，正在用后台窗口完成");
            return out;
        }
        /* 重登类：交由重登流程（余额增量核对到账）。 */
        if (SiteProfile.marksCheckedOnCredit(mode)) {
            return reloginCheckin(key, site, mode);
        }
        long unit = store.siteMetaLong(sKey0, "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT);


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

        /* BUTTON：点签到按钮 ≡ POST 签到动作端点。
         *
         * 真实浏览器实测（同源请求，带阴性对照；见 SiteProfile 类注释）：
         *   seekai.cc / kktoken.cc / api.justwoker.icu
         *     POST /api/user/checkin          → 401  端点存在，需鉴权
         *     POST /api/user/<不存在的路径>    → 404  证明 404 才是「不存在」
         *   agentrouter.org
         *     POST /api/user/checkin          → 404  端点不存在
         *     （该站签到在登录响应的 checked_in 里完成，前端代码无签到调用）
         *
         * 旧实现此处只查状态、从不 POST，于是「点按钮」这一整类站点永远签不上。
         * 现在必须真的 POST，并按响应如实判定；404 不得假装成功。 */
        String actionPath = SiteProfile.checkinActionPath(site);
        if (actionPath.isEmpty()) {
            out.put("ok", false).put("already", false).put("rewardKnown", false)
               .put("message", "该站没有签到接口，签到在登录时完成");
            return out;
        }
        long ta = System.currentTimeMillis();
        JSONObject act = callWithAuth(site, key, "POST", actionPath, "{}");
        int ahttp = act.optInt("http");
        JSONObject ad = dd(act);
        String amsg = ad.optString("message", "");
        if (amsg.isEmpty()) amsg = act.optString("message", "");
        boolean asuccess = ad.optBoolean("success", false) || act.optBoolean("success", false);
        String alower = amsg == null ? "" : amsg.toLowerCase(java.util.Locale.US);
        boolean aalready = alower.contains("今日已签") || alower.contains("已签到")
                || alower.contains("already");
        store.sysLog(sKey0, key, "签到", "info", "签到动作",
                "path=" + actionPath + "; http=" + ahttp + "; success=" + asuccess
                        + "; already=" + aalready + "; ms=" + (System.currentTimeMillis() - ta));
        if (ahttp == 200 && (asuccess || aalready)) {
            /* 金额优先取响应里的 quota（New API 各站放的位置不一），
             * 取不到再用只读签到状态补，最后才按「金额未知」如实报，不编造数字。 */
            double reward = 0;
            boolean rewardKnown = false;
            JSONObject inner = ad.optJSONObject("data");
            double raw = inner != null ? inner.optDouble("quota", 0) : ad.optDouble("quota", 0);
            if (raw > 0) {
                reward = raw >= 1000 ? Ui.round2(raw / (double) unit) : raw;
                rewardKnown = true;
            }
            if (!rewardKnown) {
                try {
                    JSONObject cs2 = checkinStatus(key, unit);
                    if (cs2 != null) {
                        reward = cs2.optDouble("rewardUSD", 0);
                        rewardKnown = cs2.optBoolean("rewardKnown", false);
                    }
                } catch (Exception ignored) {}
            }
            markChecked(key, reward, rewardKnown);
            out.put("ok", true).put("already", aalready)
               .put("reward", reward).put("rewardKnown", rewardKnown)
               .put("message", !amsg.isEmpty() ? amsg
                       : (aalready ? "今日已签到"
                       : (rewardKnown && reward > 0 ? "签到成功 +$" + Ui.usd(reward) : "签到成功")));
            return out;
        }
        /* 404 = 该站确实没有签到接口（agentrouter 实测）：签到在登录时完成。
         * 如实报出，绝不标记已签 —— 旧代码正是在这里凭空写 lastCheckin 才导致
         * 「本地显示已签、服务端无记录、当日奖励永远领不到」。 */
        if (ahttp == 404) {
            out.put("ok", false).put("already", false).put("rewardKnown", false)
               .put("needsRelogin", false)
               .put("message", "该站无签到接口（签到在登录时完成），未标记已签");
            return out;
        }
        out.put("ok", false).put("already", false).put("rewardKnown", false)
           .put("message", !amsg.isEmpty() ? amsg : httpHint(ahttp));
        return out;
    }

    /**
     * 重登类签到（RELOGIN / RELOGIN_SILENT）。
     *
     * 判据完整沿用原 anyRouterReloginLocked 的实证逻辑，不重写：
     *   ① 时间门禁：北京 08:00 后（早八点入账的站点靠它）
     *   ② 本周期回执：已领过就不再打扰
     *   ③ 登出 → 重登 → 余额增量 > $15 才算到账
     * 用户明确要求：**到账就置已签**。
     *
     * 提示策略：RELOGIN_SILENT 成功不打扰（日志照实记录），失败照实上报。
     */
    private JSONObject reloginCheckin(String key, JSONObject site, SiteProfile.CheckinMode mode)
            throws Exception {
        JSONObject out = new JSONObject();
        final String sKey = site.optString("key", "");
        final boolean silentOk = SiteProfile.silentOnSuccess(mode);

        long now = System.currentTimeMillis();
        if (!SiteProtocol.rewardWindowOpen(now)) {
            store.sysLog(sKey, key, "签到", "info", "未到奖励时间窗",
                    "mode=" + SiteProfile.modeName(mode) + "; 北京 08:00 前不请求领取");
            return out.put("ok", false).put("already", false).put("needUi", false)
                    .put("message", "未到北京08:00，未领取");
        }
        JSONObject acc = store.findAccount(key);
        if (SiteProtocol.hasSignInReceipt(acc, now)) {
            store.sysLog(sKey, key, "签到", "info", "本周期已有回执",
                    "mode=" + SiteProfile.modeName(mode) + "; 不重复请求");
            return out.put("ok", true).put("already", true).put("needUi", false)
                    .put("silent", silentOk).put("message", "本周期已签到(金额未知)");
        }

        /* ① 签到前余额（缓存优先，避免多余请求） */
        double before = anyRouterBalance(key, false);

        /* ② 登出（必须确认，否则不建新会话，防会话数超限） */
        int lo = logoutSession(key);
        boolean loggedOut = lo == ReauthManager.CONFIRMED || lo == ReauthManager.ABSENT;
        store.sysLog(sKey, key, "签到", "info", "登出对照",
                "code=" + lo + "; loggedOut=" + loggedOut);

        /* ③ 重登（离屏复用论坛会话；这是原代码验证过的静默通道） */
        int rc = OffscreenReauth.attempt(ctx, store, site, key, 30);
        store.sysLog(sKey, key, "签到", "info", "重登结果",
                "rc=" + rc + "; ok=" + (rc == OffscreenReauth.REAUTH_OK));
        if (rc != OffscreenReauth.REAUTH_OK) {
            store.opLog(sKey, key, "签到", "err", "需要重新登录",
                    "重登未成功(rc=" + rc + ")", "user");
            return out.put("ok", false).put("already", false).put("needUi", true)
                    .put("message", "需要在应用内重新登录");
        }

        /* ④ 补发 sign_in（best-effort，不作判据） */
        anyRouterPostSignIn(key);

        /* ⑤ 签到后余额（强制 fresh），增量是唯一到账证据 */
        double after = anyRouterBalance(key, true);
        double delta = (before >= 0 && after >= 0) ? (after - before) : -999;
        boolean credited = delta > 15;

        store.sysLog(sKey, key, "签到", "info", "到账核对",
                "before=" + before + "; after=" + after + "; delta=" + delta
                        + "; credited=" + credited + "; threshold=15");

        if (credited) {
            /* 用户要求：到账就置已签。 */
            markChecked(key, Ui.round2(delta), true);
            store.opLog(sKey, key, "签到", "ok", "重登到账 +$" + Ui.usd(Ui.round2(delta)),
                    "before=" + before + "; after=" + after + "; delta=" + delta
                            + "; loggedOut=" + loggedOut, "user");
            return out.put("ok", true).put("already", false).put("needUi", false)
                    .put("reward", Ui.round2(delta)).put("rewardKnown", true)
                    .put("silent", silentOk)
                    .put("message", "重登成功，+$" + Ui.usd(Ui.round2(delta)) + " 已到账"
                            + (loggedOut ? "" : "（登出未确认）"));
        }

        /* 未到账：不写回执，如实报出实际发生了什么 */
        String why = (before < 0 || after < 0)
                ? "余额读取失败，无法确认到账"
                : ("余额增量 $" + Ui.usd(Ui.round2(delta < -900 ? 0 : delta))
                        + " 未达 $15，可能今日已领或正被消耗");
        store.opLog(sKey, key, "签到", "warn", "已重登但未确认到账", why, "user");
        return out.put("ok", false).put("already", false).put("needUi", false)
                .put("message", "已重登（登出" + (loggedOut ? "确认" : "未确认") + "），"
                        + why + "，请刷新核对");
    }

    /**
     * AnyRouter 签到 ≡ 重登（北京 08:00 后重新登录即发放当日 $25）。后台工作线程调用。
     * 返回 {ok, already, needUi, message}。CF 逃生梯 L1：OffscreenReauth 离屏复用论坛会话静默重登。
     *   REAUTH_OK      → 重登成功=已领奖，补发一次原生 sign_in(best-effort)，写 relogin 回执。
     *   REAUTH_NEEDS_UI/SKIP → needUi=true，交由上层拉起可见授权(L2)。
     */
    public JSONObject anyRouterRelogin(String accountKey) {
        /* v1.3.2（P1-4）：整条「登出→重登→余额对照」必须原子。此前只对子步骤加锁
         * （logoutSession/status 各自 synchronized），组合起来不是原子的 —— 手动签到
         * （MainActivity.doCheckin）与定时任务（runScheduled）可同时进来，同账号并行
         * 跑两条重登：一个刚登出、另一个正在重登，互相把对方的会话打掉。
         * 统一走 opLock(accountKey)，与 status/checkin/logout 同一把锁、同一顺序。 */
        synchronized (opLock(accountKey == null ? "" : accountKey)) {
            return anyRouterReloginLocked(accountKey);
        }
    }

    private JSONObject anyRouterReloginLocked(String accountKey) {
        JSONObject out = new JSONObject();
        try {
            JSONObject site = store.siteOfAccount(accountKey);
            JSONObject acc = store.findAccount(accountKey);
            if (!SiteProtocol.isAnyRouter(site) || acc == null
                    || !acc.optString("siteUserId", "").matches("[1-9][0-9]{0,14}")) {
                return out.put("ok", false).put("already", false).put("needUi", false)
                        .put("message", "站点或账号不匹配，请重新授权此账号");
            }
            long now = System.currentTimeMillis();
            if (!SiteProtocol.rewardWindowOpen(now)) {
                return out.put("ok", false).put("already", false).put("needUi", false)
                        .put("message", "未到北京08:00，未领取");
            }
            if (SiteProtocol.hasSignInReceipt(acc, now)) {
                return out.put("ok", true).put("already", true).put("needUi", false)
                        .put("message", "本周期已签到(金额未知)");
            }
            final String sKey = site.optString("key", "");

            /* 真实到账判据（用户实证）：AnyRouter 必须「登出→重登」才发奖，奖励直接到账；
             * sign_in 响应不可靠。成功 = 登入对照(REAUTH_OK) + 余额增量 > $15
             * （阈值取 15 而非 25：签到时可能正在用服务消耗余额）。登出对照记入日志。 */
            double before = anyRouterBalance(accountKey, false);   // 签到前余额（缓存优先）

            /* 1. 登出对照：真实服务端注销确认（CONFIRMED/ABSENT 为确认登出）。 */
            int lo = logoutSession(accountKey);
            boolean loggedOut = lo == ReauthManager.CONFIRMED || lo == ReauthManager.ABSENT;
            store.opLog(sKey, accountKey, "AnyRouter重登", "info",
                    loggedOut ? "登出已确认" : "登出未确认(" + ReauthManager.describe(lo) + ")",
                    "code=" + lo + "；oauth=false", "auto");

            /* 2. 登入对照：OffscreenReauth 复用论坛会话重建站点会话（身份 id 校验 + 抓新 cookie）。 */
            int rc = OffscreenReauth.attempt(ctx, store, site, accountKey, 30);
            if (rc != OffscreenReauth.REAUTH_OK) {
                /* 论坛会话也失效或需人机 → 可见授权(L2)。登出已执行，不写回执。 */
                return out.put("ok", false).put("already", false).put("needUi", true)
                        .put("message", "需要在应用内重新登录");
            }

            /* 3. 补发 sign_in（best-effort，不作判据）。 */
            anyRouterPostSignIn(accountKey);

            /* 4. 签到后余额（强制 fresh 读），对比增量。 */
            double after = anyRouterBalance(accountKey, true);
            double delta = (before >= 0 && after >= 0) ? (after - before) : -999;
            boolean credited = delta > 15;

            store.opLog(sKey, accountKey, "AnyRouter重登", credited ? "ok" : "warn",
                    credited ? ("重登到账 +$" + Ui.usd(Ui.round2(delta)))
                             : "重登完成但未检测到≥$15 到账",
                    "before=" + before + "；after=" + after + "；delta=" + delta
                            + "；loggedOut=" + loggedOut + "；oauth=false", "user");

            if (credited) {
                /* 确认到账才写回执（避免把未到账误标为今日已签）。 */
                anyRouterWriteReceipt(accountKey);
                return out.put("ok", true).put("already", false).put("needUi", false)
                        .put("reward", Ui.round2(delta)).put("rewardKnown", true)
                        .put("message", "重登成功，+$" + Ui.usd(Ui.round2(delta)) + " 已到账"
                                + (loggedOut ? "" : "（登出未确认）"));
            }
            /* 未检测到到账：不写回执，提示刷新核对。 */
            String why = (before < 0 || after < 0)
                    ? "余额读取失败，无法确认到账"
                    : ("余额增量 $" + Ui.usd(Ui.round2(delta < -900 ? 0 : delta)) + " 未达 $15，可能今日已领或正被消耗");
            return out.put("ok", false).put("already", false).put("needUi", false)
                    .put("message", "已重登（登出" + (loggedOut ? "确认" : "未确认") + "），" + why + "，请刷新核对");
        } catch (Exception e) {
            try {
                return out.put("ok", false).put("already", false).put("needUi", true)
                        .put("message", "需要在应用内重新登录");
            } catch (Exception ignored) { return out; }
        }
    }

    /** AnyRouter 当前可用余额(USD)。fresh=false 时优先用卡片缓存 lastStatus，避免多余请求；取不到或 fresh 时拉一次 status。读不到返回 -1。 */
    private double anyRouterBalance(String accountKey, boolean fresh) {
        if (!fresh) {
            JSONObject acc = store.findAccount(accountKey);
            JSONObject ls = acc == null ? null : acc.optJSONObject("lastStatus");
            if (ls != null && ls.optBoolean("ok")) {
                double v = ls.optDouble("availableUSD", -1);
                if (v >= 0) return v;
            }
        }
        try {
            JSONObject st = status(accountKey);
            if (st != null && st.optBoolean("ok")) return st.optDouble("availableUSD", -1);
        } catch (Exception ignored) {}
        return -1;
    }

    /** best-effort 原生 POST /api/user/sign_in（带 Cookie+New-Api-User）；仅日志，不作成功判据。 */
    private boolean anyRouterPostSignIn(String accountKey) {
        try {
            JSONObject site = store.siteOfAccount(accountKey);
            JSONObject acc = store.findAccount(accountKey);
            if (site == null || acc == null) return false;
            JSONObject r = call(site, null, acc.optString("siteCookie", ""),
                    acc.optString("siteUserId", ""), "POST", "/api/user/sign_in", "{}");
            JSONObject d = dd(r);
            boolean ok = r.optInt("http") == 200 && d.optBoolean("success", true);
            store.opLog(site.optString("key"), accountKey, "AnyRouter重登", "info",
                    ok ? "sign_in 已发送" : "sign_in 未确认", "http=" + r.optInt("http"), "auto");
            return ok;
        } catch (Exception e) { return false; }
    }

    /** 写 AnyRouter 当周期签到回执（lastCheckin{source:relogin}+lastLogin）；siteCookie 由重登流程更新，勿覆盖。 */
    private void anyRouterWriteReceipt(String accountKey) {
        try {
            long now = System.currentTimeMillis();
            JSONObject lc = new JSONObject()
                    .put("date", SiteProtocol.rewardDate(now))
                    .put("time", now)
                    .put("source", "relogin");
            store.patchAccount(accountKey, new JSONObject().put("lastCheckin", lc).put("lastLogin", now));
        } catch (Exception ignored) {}
    }

    /**
     * AnyRouter 可见授权成功后的「领奖落账」子逻辑（best-effort sign_in + 写回执）。
     * 供 MainActivity 可见授权成功(L2/L3)路径复用——可见登录即视为已签。
     * （L1 后台重登主路径不走这里，而是在 anyRouterRelogin 内按余额增量判据决定是否写回执。）
     */
    public void anyRouterSignInReceipt(String accountKey) {
        anyRouterPostSignIn(accountKey);
        anyRouterWriteReceipt(accountKey);
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
        return q >= 1000 ? Ui.round2(q / (double) unit) : q;
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
        if (lb != null && lb.has("time")) {
            long t = parseTimeMs(lb.optString("time"));
            if (t > 0 && isToday(t)) {
                double usd = lb.optDouble("usd", -1);
                lb.put("rewardUSD", usd >= 0 ? usd : 0);
                lb.put("rewardKnown", usd >= 0);
                return lb;
            }
        }
        /* v0.4.6：严格签到文案未命中今日时，回退到「今日系统到账」lastSysCredit。
         * logs 已保证它是 type=4、今日、有额度、且非注册/邀请/兑换的最新一条——
         * 覆盖 AgentRouter 等到账文案不含「签到」二字的 login 站。 */
        JSONObject sc = lg.optJSONObject("lastSysCredit");
        if (sc != null && sc.has("time")) {
            double usd = sc.optDouble("usd", -1);
            sc.put("rewardUSD", usd >= 0 ? usd : 0);
            sc.put("rewardKnown", usd >= 0);
            return sc;
        }
        if (lb == null || !lb.has("time")) {
            store.opLog(sKey, key, "奖励检测", "warn", "签到奖励未显示：近30条系统日志无签到记录",
                    "接口返回正常但无「每日签到」类文案（站点可能当日未发奖励）", "user");
            return null;
        }
        long t = parseTimeMs(lb.optString("time"));
        {
            String ts = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(t > 0 ? t : 0));
            store.opLog(sKey, key, "奖励检测", "warn", "签到奖励未显示：最近签到记录非今日",
                    "最近一条签到时间：" + ts, "user");
            return null;
        }
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
        /* 静态方法拿不到 site 对象，无法查协议表；AnyRouter 用「本周期回执」判定，
         * 其余站用 lastCheckin 日期。这里是唯一保留的站点特判，且语义与协议表一致
         * （AnyRouter = RELOGIN_SILENT = 靠回执）。 */
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
        /* 端点来自协议表：New API 是 /api/log/self，福利站是 /api/ledger，
         * 其它形态没有该接口（如实返回，不假装成功）。 */
        SiteProfile.Kind kind = SiteProfile.kindOf(site);
        String logPath = SiteProfile.rewardLogPath(kind);
        if (logPath.isEmpty()) {
            store.sysLog(site.optString("key", ""), key, "奖励检测", "info",
                    "该站无奖励流水接口",
                    "kind=" + SiteProfile.kindName(kind) + "; category=" + category);
            return new JSONObject().put("ok", false).put("http", 0)
                    .put("noLogApi", true).put("message", "本站无奖励流水接口");
        }
        boolean sysCat = "系统".equals(category);
        String path = "/api/log/self".equals(logPath)
                ? logPath + "?" + (sysCat ? "type=4"
                        : ("category=" + URLEncoder.encode(category, "UTF-8")))
                        + "&limit=" + limit + "&page=1"
                : logPath + "?limit=" + limit;
        long lg0 = System.currentTimeMillis();
        JSONObject r = callWithAuth(site, key, "GET", path);
        store.sysLog(site.optString("key", ""), key, "奖励检测", "info", "查询奖励流水",
                "kind=" + SiteProfile.kindName(kind) + "; path=" + logPath
                        + "; http=" + r.optInt("http")
                        + "; ms=" + (System.currentTimeMillis() - lg0));
        int code = r.optInt("http");
        /* WAF 间歇拦截：只对真正的 WAF 页面退避，且退避 600ms（原 2500ms 无条件等，
         * 拖慢整条刷新链）。判据用 wafBlocked，而不是「凡是 503 就睡」。 */
        if (code == 503 && Engine.wafBlocked(r.optString("body", ""))) {
            try { Thread.sleep(600L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            r = callWithAuth(site, key, "GET", path);
            code = r.optInt("http");
        }
        JSONObject out = new JSONObject().put("ok", code == 200).put("http", code);
        if (code != 200) out.put("message", httpHint(code));
        JSONArray rows = new JSONArray();
        JSONObject lastBonus = null;
        JSONObject lastSysCredit = null;
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
                /* v0.4.6：AgentRouter 等 login 站的到账文案可能不含「签到」二字，
                 * 严格 isCheckinText 会漏判。额外取「最新一条今日系统到账」作兜底：
                 * type=4、今日(北京时区)、quota>0 或文案含正 USD、且文案不含 注册/邀请/兑换
                 * （排除一次性赠送，不误伤）。接口按时间倒序，命中第一条（最新）即停。 */
                if (lastSysCredit == null) {
                    long rt = parseTimeMs(row.optString("time"));
                    boolean hasCredit = q > 0 || usdFromText > 0;
                    boolean oneOff = text.contains("注册") || text.contains("邀请") || text.contains("兑换");
                    if (rt > 0 && isToday(rt) && hasCredit && !oneOff) lastSysCredit = row;
                }
            }
        }
        out.put("rows", rows);
        out.put("lastBonus", lastBonus == null ? JSONObject.NULL : lastBonus);
        out.put("lastSysCredit", lastSysCredit == null ? JSONObject.NULL : lastSysCredit);
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
            try { return Ui.round2(Double.parseDouble(m.group(1))); }
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
                        /* v1.3.0（用户决策）：令牌只管查询/Key管理，签到一律走原流程。
                         * newapi 站 → OffscreenCheckin.run（内部对 isAnyRouter 短路到重登流程）。 */
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