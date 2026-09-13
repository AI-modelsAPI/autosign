package icu.justwoker.justsign;

import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ReauthManager — 重新授权事务管理。
 *
 * 【职责】
 * 1. 账号级授权事务锁：同一「站点×账号」任何时刻只允许一条授权链路
 *    （可见授权 / 后台静默授权互斥），防止并行取 state → 并行建站点会话，
 *    触发会话数受限站点（AgentRouter / JustDoWork 等）的 AUTH_SESSION_LIMIT。
 *    锁带 5 分钟 TTL，崩溃/泄漏后可自愈。
 * 2. 旧会话确认注销：重新授权前必须先用旧凭据调站点 logout 并分级确认，
 *    只有「已确认注销」或「旧会话确认不存在」才允许取新 state 建新会话；
 *    无法确认或明确失败时禁止授权，绝不盲建新会话。
 */
public final class ReauthManager {
    private ReauthManager() {}

    private static final ConcurrentHashMap<String, Long> LOCKS = new ConcurrentHashMap<>();
    private static final long LOCK_TTL_MS = 5 * 60 * 1000L;

    /* ---------- 事务锁 ---------- */

    private static String key(String siteKey, String accountKey) {
        return (siteKey == null ? "" : siteKey) + "|" + (accountKey == null ? "" : accountKey);
    }

    /** 尝试持有授权事务锁；已有活跃持锁者（未超 TTL）返回 false。 */
    public static boolean acquire(String siteKey, String accountKey) {
        String k = key(siteKey, accountKey);
        long now = System.currentTimeMillis();
        for (;;) {
            Long old = LOCKS.get(k);
            if (old != null && now - old < LOCK_TTL_MS) return false;
            if (LOCKS.put(k, now) == null || old == null) return true;
            if (LOCKS.get(k) == Long.valueOf(now)) return true; // 覆盖了过期锁
        }
    }

    /** 锁当前是否被持有（含 TTL 判定）。调用方用于判断"锁已由我方上游持有"。 */
    public static boolean isHeld(String siteKey, String accountKey) {
        Long old = LOCKS.get(key(siteKey, accountKey));
        return old != null && System.currentTimeMillis() - old < LOCK_TTL_MS;
    }

    public static void release(String siteKey, String accountKey) {
        LOCKS.remove(key(siteKey, accountKey));
    }

    /* ---------- 旧会话注销分级 ---------- */

    /** 注销结果分级。 */
    public static final int CONFIRMED = 0;    // 已确认注销，可建新会话
    public static final int ABSENT = 1;       // 旧会话确认不存在（幂等成功），可建新会话
    public static final int UNCONFIRMED = 2;  // 网络/超时/429/5xx，无法确认 → 禁止授权
    public static final int DENIED = 3;       // 站点明确拒绝注销 → 禁止授权

    public static String describe(int result) {
        switch (result) {
            case CONFIRMED:  return "旧会话已注销";
            case ABSENT:     return "旧会话已不存在";
            case UNCONFIRMED: return "无法确认旧会话状态（网络/限流）";
            default:         return "站点拒绝了注销请求";
        }
    }

    /** 是否允许在注销结果为 r 时继续建立新会话。 */
    public static boolean mayProceed(int result) {
        return result == CONFIRMED || result == ABSENT;
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /** 浏览器化 UA：WAF 对纯 OkHttp 默认 UA 会直接返回 JS 质询页。 */
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; PHZ110) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();

    /**
     * 用旧凭据调用已实测的 New API 登出端点：GET /api/user/logout。
     * 判据只看响应体语义：JSON success=true 视为站点已接受注销；401 表示旧会话已不存在（幂等成功）。
     * 非 JSON（WAF 质询页/前端 HTML 路由）绝不当作成功。
     *
     * 注意：本方法仅用于「删除账号/站点」的尽力注销，不承担重登前置校验。
     * 重登链路请用 Engine.logoutSession（含续期、浏览器化请求头与离屏 WebView 兜底）。
     */
    public static int logoutVerified(JSONObject site, JSONObject account) {
        if (site == null || account == null) return UNCONFIRMED;
        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) return UNCONFIRMED;
        String token = clean(account.optString("token", ""));
        String cookie = clean(account.optString("siteCookie", ""));
        if (token.isEmpty() && cookie.isEmpty()) return ABSENT;
        String uid = clean(account.optString("siteUserId", ""));
        try {
            Request.Builder b = new Request.Builder().url(base + "/api/user/logout")
                    .get()
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Origin", base)
                    .header("Referer", base + "/")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("User-Agent", BROWSER_UA);
            if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
            if (!cookie.isEmpty()) b.header("Cookie", cookie);
            if (!uid.isEmpty()) b.header("New-Api-User", uid);
            try (Response r = HTTP.newCall(b.build()).execute()) {
                int code = r.code();
                String body = r.body() != null ? r.body().string() : "";
                if (code == 401) return ABSENT;
                if (code == 403) return DENIED;
                if (code == 429 || code >= 500) return UNCONFIRMED;
                if (code < 200 || code >= 300) return UNCONFIRMED;
                if (Engine.wafBlocked(body)) return UNCONFIRMED; // WAF 质询页，请求未达后端
                try {
                    JSONObject json = new JSONObject(body);
                    return json.optBoolean("success", false) ? CONFIRMED : DENIED;
                } catch (Exception ignored) {
                    return UNCONFIRMED; // HTML/非 JSON 绝不能当作注销成功
                }
            }
        } catch (Exception e) {
            return UNCONFIRMED;
        }
    }

    /** 清除账号旧站点会话凭据（token/siteCookie 置空，保留身份锚点字段）。 */
    public static void clearOldSession(Store store, String accountKey) {
        try {
            store.patchAccount(accountKey, new JSONObject()
                    .put("token", "").put("siteCookie", "")
                    .put("updatedAt", System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    private static String clean(String s) { return s == null || "null".equals(s) ? "" : s.trim(); }
}
