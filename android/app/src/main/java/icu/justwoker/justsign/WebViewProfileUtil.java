package icu.justwoker.justsign;

import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * WebViewProfileUtil（v0.3.2，opus4.8 审计方案·需求3）— 多 Profile 会话隔离。
 *
 * 【解决什么】CookieManager 全局单例 + user_session HttpOnly 无法快照恢复，
 * 导致多账号轮流授权/刷新时「WebView 会话账号 ≠ 所选账号」：
 *   - 授权被站点按错误账号校验拒绝（age 限制）
 *   - A 的 token 写进 B（串号）
 *   - 每次授权都要清 Cookie 重新登录（体验差）
 *
 * 【机制】每个「站点 × 账号」映射到一个独立命名 Profile（Cookie 分区独立
 * 且持久化）。授权页与离屏 SilentAuth WebView 都绑定同一 Profile：
 *   - 该账号授权一次后，Profile 内会话长期有效
 *   - SilentAuth 后台交换在该 Profile 上跑，会话永远是自己 → 全自动
 *   - B1 身份校验继续兜底防串号
 *
 * 【硬约束（审计确认）】
 * - WebViewCompat.setProfile 必须在 WebView 触发任何加载（loadUrl/
 *   evaluateJavascript）之前调用，否则 IllegalStateException
 * - 非 Default Profile 的 Cookie 操作必须走 profile.getCookieManager()，
 *   不能用 CookieManager.getInstance()（那是 Default 分区）
 * - 降级（设备 WebView 不支持 MULTI_PROFILE）：所有调用方回落 Default
 *   分区，行为与 v0.3.1 一致
 */
public final class WebViewProfileUtil {

    private WebViewProfileUtil() {}

    /** 设备/WebView 是否支持多 Profile。不支持时全部走 Default 分区降级路径。 */
    public static boolean multiProfileSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) { return false; }
    }

    /**
     * 生成稳定且合法的 Profile 名。
     * Profile name 官方字符/长度限制未公开明确，用 SHA-256 摘要前 16 位
     * 十六进制（产物仅 [0-9a-f]，前缀 p_，全长 18 字符），对任何限制都安全，
     * 且对同一 (siteKey, accountKey) 稳定可复现（跨离屏/前台一致）。
     */
    /** v0.4.7：删除账号时同步删除其 Profile（清掉该账号的 GitHub 会话与站点 Cookie 分区）。
     * 不删除会残留孤儿数据（隐私泄漏 + 存储占用）。多 Profile 不支持时无分区可删，安全跳过。 */
    public static void deleteProfileFor(@Nullable String siteKey, @Nullable String accountKey) {
        if (!multiProfileSupported()) return;
        try {
            String name = profileNameFor(siteKey, accountKey);
            ProfileStore.getInstance().deleteProfile(name);
        } catch (Exception ignored) {}
    }

    @NonNull
    public static String profileNameFor(@Nullable String siteKey, @Nullable String accountKey) {
        String raw = (siteKey == null ? "" : siteKey) + "|" + (accountKey == null ? "" : accountKey);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p_");
            sb.append(Crypto.hex(java.util.Arrays.copyOfRange(d, 0, 8)));
            return sb.toString();
        } catch (Exception e) {
            String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
            return "p_" + (safe.length() > 40 ? safe.substring(0, 40) : safe);
        }
    }

    /**
     * 在 WebView 加载任何内容前绑定 Profile。
     * 必须紧跟 new WebView 之后、getSettings()/loadUrl 之前调用。
     * 不支持多 Profile 时返回 null（保持 Default 分区）。
     */
    @Nullable
    public static Profile bindProfile(@NonNull WebView wv, @NonNull String name) {
        if (!multiProfileSupported()) return null;
        try {
            Profile p = ProfileStore.getInstance().getOrCreateProfile(name);
            WebViewCompat.setProfile(wv, name);
            return p;
        } catch (Throwable t) { return null; }
    }

    /** 取得已有命名 Profile（未创建则返回 null，不新建）。 */
    @Nullable
    public static Profile profileOf(@Nullable String siteKey, @Nullable String accountKey) {
        if (!multiProfileSupported()) return null;
        try {
            return ProfileStore.getInstance().getProfile(profileNameFor(siteKey, accountKey));
        } catch (Throwable t) { return null; }
    }

    /** 取得应对该 Profile 生效的 CookieManager（降级时返回全局 = Default 分区）。 */
    @NonNull
    public static CookieManager cookieManagerFor(@Nullable Profile profile) {
        if (profile != null) return profile.getCookieManager();
        return CookieManager.getInstance();
    }

    /**
     * v1.0.7：读取该 Profile 下指定 URL 的 Cookie 头值（脱敏用途之外的内部判断用）。
     * 用于 WAF 放行判断（是否已拿到 acw_sc__v2/acw_tc）与 linuxdo 会话探测。
     * 返回值仅供内部包含性检查，禁止写入日志或外传。
     */
    @Nullable
    public static String cookieHeader(@Nullable Profile profile, @NonNull String url) {
        try {
            return cookieManagerFor(profile).getCookie(url);
        } catch (Throwable t) { return null; }
    }
    /**
     * v1.0.7：该 Profile 下 Linux DO（connect.linux.do）是否已有会话。
     * Discourse 的登录 Cookie 是 _linuxdo_session / _forum_session / logged_in=yes，
     * 不同部署命名不同，故做宽松包含性探测；cf_clearance 只代表防护挑战通过，
     * 不能单独当作论坛登录态。
     */
    public static boolean linuxdoLoggedIn(@Nullable Profile profile) {
        try {
            CookieManager cm = cookieManagerFor(profile);
            String c1 = cm.getCookie("https://connect.linux.do");
            String c2 = cm.getCookie("https://linux.do");
            String c = (c1 == null ? "" : c1) + ";" + (c2 == null ? "" : c2);
            if (c.trim().isEmpty()) return false;
            String l = c.toLowerCase(java.util.Locale.US);
            return l.contains("session") || l.contains("logged_in");
        } catch (Throwable t) { return false; }
    }
    /** 该 Profile（或 Default）下是否已有 GitHub 会话 Cookie。 */
    public static boolean githubLoggedIn(@Nullable Profile profile) {
        try {
            CookieManager cm = cookieManagerFor(profile);
            String c = cm.getCookie("https://github.com");
            return c != null && (c.contains("user_session") || c.contains("logged_in=yes"));
        } catch (Throwable t) { return false; }
    }

    /** v1.0.7：只清指定域的 Cookie（保留其他域如 linux.do 的登录态）。
     * 用于站点 session 失效（半登录态导致 /login 被 302 到 /console）时重置。 */
    public static void clearCookiesForUrl(@Nullable Profile profile, @NonNull String url) {
        try {
            CookieManager cm = cookieManagerFor(profile);
            String ck = cm.getCookie(url);
            if (ck == null || ck.isEmpty()) return;
            /* v1.1.10：会话 Cookie 常带 Domain=.host（域 Cookie），而 host-only 的删除
             * 写入不会覆盖域 Cookie（内核视为两条不同 Cookie）。故对每个名字同时下发
             * host-only 与 Domain=.host / Domain=host 三种作用域的过期指令，确保真正清掉。 */
            String host = "";
            try {
                String h = java.net.URI.create(url).getHost();
                host = h == null ? "" : h;
            } catch (Exception ignored) {}
            boolean any = false;
            for (String pair : ck.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                if (name.isEmpty()) continue;
                String expire = "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                cm.setCookie(url, name + expire);                                  // host-only
                if (!host.isEmpty()) {
                    cm.setCookie(url, name + expire + "; Domain=" + host);          // domain (host)
                    cm.setCookie(url, name + expire + "; Domain=." + host);         // domain (.host)
                }
                any = true;
            }
            if (any) cm.flush();
        } catch (Throwable ignored) {}
    }

    /** 主动落盘该 Profile（或 Default）Cookie。 */
    public static void flush(@Nullable Profile profile) {
        try { cookieManagerFor(profile).flush(); } catch (Throwable ignored) {}
    }

    /**
     * 把已落库的 Cookie 串注入该 Profile（或 Default）分区的 CookieManager 中。
     * 确保离屏 WebView 在发起请求时具备已保存的会话态，避免裸奔被 WAF 当成恶意爬虫拦截。
     */
    public static void injectCookies(@Nullable Profile profile, @NonNull String url, @Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;
        try {
            CookieManager cm = cookieManagerFor(profile);
            for (String pair : cookieHeader.split(";")) {
                String p = pair.trim();
                if (!p.isEmpty() && p.contains("=")) {
                    cm.setCookie(url, p + "; Path=/");
                }
            }
            cm.flush();
        } catch (Throwable ignored) {}
    }
    /**
     * 把该 Profile 当前持有的 Cookie（含 WAF 放行 Cookie）合并进已落库的 cookie 串。
     *
     * 为什么必须做（真机日志实证的根因）：
     *   原生请求（OkHttp）用的是**落库的 siteCookie 字符串**，不是 WebView 的 Cookie 库。
     *   离屏 WebView 过盾后，放行 Cookie（acw_sc__v2 / acw_tc 等）只写进了 Profile 的
     *   CookieManager，落库串里没有它 —— 于是「离屏已跑过质询，重发原生请求」仍然被
     *   WAF 拦回 503，日志恒为 waf-not-cleared，看起来像"质询永远过不去"，
     *   实际是"过了，但放行凭证没搬到原生请求手上"。
     *
     * 规则：
     *   · 只补充/覆盖同名键，绝不丢弃落库串里已有的键（session Cookie 必须保留）；
     *   · 同名时以 WebView 的值为准（WAF 放行值会轮换，WebView 里是最新的）。
     *
     * @param existing 已落库的 Cookie 头值，可为 null/空
     * @param profile  离屏用过的 Profile
     * @param url      取 Cookie 的目标站点根
     * @return 合并后的 Cookie 头值；取不到任何东西时原样返回 existing
     */
    @NonNull
    public static String mergeProfileCookies(@Nullable String existing, @Nullable Profile profile,
                                             @NonNull String url) {
        String base = existing == null ? "" : existing.trim();
        String live;
        try {
            WebViewProfileUtil.flush(profile);
            live = cookieManagerFor(profile).getCookie(url);
        } catch (Throwable t) { return base; }
        if (live == null || live.isEmpty()) return base;

        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        for (String pair : base.split(";")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            merged.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
        for (String pair : live.split(";")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            merged.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
    /**
     * 诊断用：列出该 Profile 在若干域下持有的 Cookie **名称**（不含值），
     * 以及论坛登录态判定结果。
     *
     * 为什么要它：真机上应用私有分区不可读，`forum-session-absent` 到底是
     * 「人工登录后会话没落到这个分区」还是「落到了但域/名字与判据不符」，
     * 从外部完全看不出来 —— 只能让应用自己如实报出名字。
     * 安全：只输出 Cookie 名与布尔，绝不输出任何值。
     */
    @NonNull
    public static String describeCookieNames(@Nullable Profile profile) {
        String[] urls = {"https://connect.linux.do", "https://linux.do", "https://anyrouter.top"};
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            String names = "";
            try {
                String c = cookieManagerFor(profile).getCookie(u);
                if (c != null && !c.isEmpty()) {
                    StringBuilder n = new StringBuilder();
                    for (String pair : c.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) continue;
                        if (n.length() > 0) n.append(',');
                        n.append(pair.substring(0, eq).trim());
                    }
                    names = n.toString();
                }
            } catch (Throwable ignored) {}
            String host;
            try { host = java.net.URI.create(u).getHost(); } catch (Exception e) { host = u; }
            sb.append(host).append("=[").append(names).append("] ");
        }
        sb.append("linuxdoLoggedIn=").append(linuxdoLoggedIn(profile));
        return sb.toString();
    }
}
