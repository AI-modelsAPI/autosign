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
    @NonNull
    public static String profileNameFor(@Nullable String siteKey, @Nullable String accountKey) {
        String raw = (siteKey == null ? "" : siteKey) + "|" + (accountKey == null ? "" : accountKey);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p_");
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
            return "p_" + (safe.length() > 40 ? safe.substring(0, 40) : safe);
        }
    }

    /** 确保 Profile 存在（不支持多 Profile 时返回 null，调用方走 Default 路径）。 */
    @Nullable
    public static Profile ensureProfile(@NonNull String name) {
        if (!multiProfileSupported()) return null;
        try {
            return ProfileStore.getInstance().getOrCreateProfile(name);
        } catch (Throwable t) { return null; }
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

    /** 取得应对该 Profile 生效的 CookieManager（降级时返回全局 = Default 分区）。 */
    @NonNull
    public static CookieManager cookieManagerFor(@Nullable Profile profile) {
        if (profile != null) return profile.getCookieManager();
        return CookieManager.getInstance();
    }

    /** 该 Profile（或 Default）下是否已有 GitHub 会话 Cookie。 */
    public static boolean githubLoggedIn(@Nullable Profile profile) {
        try {
            CookieManager cm = cookieManagerFor(profile);
            String c = cm.getCookie("https://github.com");
            return c != null && (c.contains("user_session") || c.contains("logged_in=yes"));
        } catch (Throwable t) { return false; }
    }

    /** 清空该 Profile（或 Default）的所有 Cookie 并落盘。仅用于首次授权。 */
    public static void clearCookies(@Nullable Profile profile) {
        try {
            CookieManager cm = cookieManagerFor(profile);
            cm.removeAllCookies(ok -> {
                try { cm.flush(); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 主动落盘该 Profile（或 Default）Cookie。 */
    public static void flush(@Nullable Profile profile) {
        try { cookieManagerFor(profile).flush(); } catch (Throwable ignored) {}
    }
}