package icu.justwoker.justsign;

import android.content.Context;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * TokenKeeper（v0.2.0）— access_token 自动续期。
 *
 * 【为什么需要】实测该站点 JWT TTL 只有 15 分钟：
 *   iat 21:57:42 → exp 22:12:42（TTL = 15.0 min）
 * 之前 App 无续期层，放置十几分钟后任何 API 调用都会 401，
 * UI 的状态 chip 就变成红色"异常"——这不是 UI bug，是缺续期。
 *
 * 【续期机制（来自站点前端源码实测）】
 *   POST /api/user/auth/refresh
 *   headers: X-Auth-Session: <sid>   （站点前端源码里该头是可选的：
 *                                      `headers: e ? {"X-Auth-Session": e} : void 0`）
 *   cookie:  站点会话 cookie（从 WebView CookieManager 取，授权时已落地）
 *   响应:    { data: { access_token, ... } } 多种包裹形态都兼容
 *
 * 【触发时机】Engine 每次发请求前调 ensureFresh()：
 *   剩余有效期 < SKEW_MS(3min) 才续期，否则直接返回原 token（避免打扰站点、触发限流）。
 */
public final class TokenKeeper {

    /** 提前续期窗口：剩余不足 3 分钟就换新 */
    private static final long SKEW_MS = 3 * 60 * 1000L;
    /** 同一账号最短续期间隔，防止并发风暴 */
    private static final long MIN_GAP_MS = 20 * 1000L;

    private static final java.util.HashMap<String, Long> lastTry = new java.util.HashMap<>();

    private TokenKeeper() {}

    /* ================= JWT ================= */

    /** JWT exp（毫秒）；解析失败返回 0 */
    public static long expMs(String jwt) {
        JSONObject p = payload(jwt);
        if (p == null) return 0;
        long exp = p.optLong("exp", 0);
        return exp > 0 ? exp * 1000L : 0;
    }

    /** JWT sid（续期头用）；解析失败返回空串 */
    public static String sid(String jwt) {
        JSONObject p = payload(jwt);
        return p == null ? "" : p.optString("sid", "");
    }

    private static JSONObject payload(String jwt) {
        try {
            if (jwt == null) return null;
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String pl = parts[1];
            int pad = (4 - pl.length() % 4) % 4;
            for (int i = 0; i < pad; i++) pl += "=";
            byte[] raw = android.util.Base64.decode(pl,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            return new JSONObject(new String(raw, "UTF-8"));
        } catch (Exception e) { return null; }
    }

    /** token 是否已过期或即将过期（无 exp 字段时按"未知不续期"处理，返回 false） */
    public static boolean needsRefresh(String jwt) {
        long exp = expMs(jwt);
        if (exp <= 0) return false;
        return System.currentTimeMillis() + SKEW_MS >= exp;
    }

    /** 剩余分钟（UI 展示用；无 exp 返回 -1） */
    public static double remainMinutes(String jwt) {
        long exp = expMs(jwt);
        if (exp <= 0) return -1;
        return (exp - System.currentTimeMillis()) / 60000.0;
    }

    /* ================= 续期 ================= */

    /**
     * 确保账号 token 新鲜：需要时续期并落库。
     * @return 可用 token（续期成功=新 token；不需要或失败=原 token）
     */
    public static String ensureFresh(Context ctx, Store store, JSONObject site, String accountKey) {
        if (store == null || site == null || accountKey == null) return "";
        JSONObject acc = store.findAccount(accountKey);
        if (acc == null) return "";
        String token = acc.optString("token", "");
        if (token.isEmpty()) return "";
        if (!needsRefresh(token)) return token;

        /* 并发/频率保护 */
        synchronized (lastTry) {
            Long t = lastTry.get(accountKey);
            long now = System.currentTimeMillis();
            if (t != null && now - t < MIN_GAP_MS) return token;
            lastTry.put(accountKey, now);
        }

        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) return token;

        String fresh = refresh(ctx, store, base, token);
        if (fresh == null || fresh.isEmpty() || fresh.equals(token)) {
            store.opLog(site.optString("key", ""), accountKey, "续期",
                    "err", "token 续期失败", "剩余 " + fmt(remainMinutes(token)) + " 分钟，请重新授权", "auto");
            return token;
        }
        try {
            store.patchAccount(accountKey, new JSONObject()
                    .put("token", fresh)
                    .put("updatedAt", System.currentTimeMillis()));
        } catch (Exception ignored) {}
        store.opLog(site.optString("key", ""), accountKey, "续期",
                "ok", "登录状态已自动续期", "新 token 有效 " + fmt(remainMinutes(fresh)) + " 分钟", "auto");
        return fresh;
    }

    private static String fmt(double m) {
        return m < 0 ? "?" : String.format(java.util.Locale.US, "%.0f", m);
    }

    /** 调 /api/user/auth/refresh 换新 token；失败返回 null */
    private static String refresh(Context ctx, Store store, String base, String oldToken) {
        Response resp = null;
        try {
            OkHttpClient c = client(store);
            Request.Builder rb = new Request.Builder()
                    .url(base + "/api/user/auth/refresh")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                    .post(RequestBody.create("{}", okhttp3.MediaType.parse("application/json")));
            String s = sid(oldToken);
            if (!s.isEmpty()) rb.header("X-Auth-Session", s);
            if (!oldToken.isEmpty()) rb.header("Authorization", "Bearer " + oldToken);
            /* WebView 授权时落下的站点 cookie —— 续期的关键凭证 */
            String cookie = cookieOf(base);
            if (!cookie.isEmpty()) rb.header("Cookie", cookie);

            resp = c.newCall(rb.build()).execute();
            String body = resp.body() != null ? resp.body().string() : "";
            if (resp.code() != 200 || body.trim().isEmpty()) return null;
            JSONObject j = new JSONObject(body);
            JSONObject d = j.optJSONObject("data");
            if (d == null) d = j;
            String tk = d.optString("access_token", "");
            if (tk.isEmpty()) tk = d.optString("accessToken", "");
            if (tk.isEmpty()) {
                JSONObject sess = d.optJSONObject("session");
                if (sess != null) {
                    tk = sess.optString("access_token", "");
                    if (tk.isEmpty()) tk = sess.optString("token", "");
                }
            }
            if (tk.isEmpty()) {
                JSONObject tb = d.optJSONObject("token_bundle");
                if (tb != null) tk = tb.optString("access_token", "");
            }
            return tk.isEmpty() ? null : tk;
        } catch (Exception e) {
            return null;
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
    }

    private static String cookieOf(String base) {
        try {
            String ck = android.webkit.CookieManager.getInstance().getCookie(base);
            return ck == null ? "" : ck;
        } catch (Exception e) { return ""; }
    }

    /** 与 Engine 同款：代理可达则走代理，否则直连 */
    private static OkHttpClient client(Store store) {
        OkHttpClient base = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).build();
        try {
            JSONObject p = store.config().optJSONObject("proxy");
            if (p == null || !p.optBoolean("enabled")) return base;
            String host = p.optString("host", "127.0.0.1");
            int port = p.optInt("port", 10808);
            java.net.Socket sk = new java.net.Socket();
            sk.connect(new java.net.InetSocketAddress(host, port), 1200);
            sk.close();
            return base.newBuilder().proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress(host, port))).build();
        } catch (Exception e) { return base; }
    }
}