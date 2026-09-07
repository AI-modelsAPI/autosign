package icu.justwoker.justsign;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * OAuth 回调 URL 的统一、可测试解析器。
 *
 * 安全边界：只接受站点同域、带 code、且 state 与本次授权严格一致的 URL；
 * 日志只暴露 host/path/参数名，绝不记录 code/state 的值。
 */
final class OAuthCallback {
    private OAuthCallback() {}

    static final class Result {
        final boolean validUrl;
        final boolean sameHost;
        final boolean callbackPath;
        final boolean hasCode;
        final boolean hasState;
        final boolean stateMatches;
        final String code;
        final String state;
        final String error;
        final String path;
        final String safe;

        Result(boolean validUrl, boolean sameHost, boolean callbackPath,
               boolean hasCode, boolean hasState, boolean stateMatches,
               String code, String state, String error, String path, String safe) {
            this.validUrl = validUrl;
            this.sameHost = sameHost;
            this.callbackPath = callbackPath;
            this.hasCode = hasCode;
            this.hasState = hasState;
            this.stateMatches = stateMatches;
            this.code = code;
            this.state = state;
            this.error = error;
            this.path = path;
            this.safe = safe;
        }

        /** 同域且 state 严格匹配时，回调路径可以不是旧版固定的 /oauth/*。 */
        boolean shouldExchange() {
            return sameHost && hasCode && hasState && stateMatches;
        }

        /** OAuth 启动后首次进入站点域名就是回调落点；没有 code 即明确失败。 */
        boolean missingCode() {
            return sameHost && !hasCode;
        }

        /** 看起来是本次回调，但 state 缺失/不符；必须拒绝，防止串流或 CSRF。 */
        boolean badState() {
            return sameHost && hasCode && (callbackPath || hasState) && !stateMatches;
        }
    }

    static Result parse(String rawUrl, String siteHost, String expectedState) {
        try {
            URI u = new URI(rawUrl == null ? "" : rawUrl);
            String host = n(u.getHost());
            String path = n(u.getPath());
            if (path.isEmpty()) path = "/";
            boolean sameHost = !n(siteHost).isEmpty() && host.equalsIgnoreCase(siteHost);
            String lowPath = path.toLowerCase(java.util.Locale.US);
            boolean callbackPath = lowPath.equals("/oauth")
                    || lowPath.startsWith("/oauth/")
                    || lowPath.equals("/api/oauth")
                    || lowPath.startsWith("/api/oauth/")
                    || lowPath.contains("oauth/callback")
                    || lowPath.contains("oauth_callback");

            Map<String, String> params = new LinkedHashMap<>();
            Set<String> keys = new LinkedHashSet<>();
            readParams(u.getRawQuery(), params, keys);
            /* 某些前端路由会把参数放在 # 后；兼容读取，但仍执行同域 + state 校验。 */
            String frag = u.getRawFragment();
            if (frag != null) {
                int q = frag.indexOf('?');
                String fp = q >= 0 ? frag.substring(q + 1) : (frag.contains("=") ? frag : "");
                readParams(fp, params, keys);
            }

            String code = n(params.get("code"));
            String state = n(params.get("state"));
            String error = n(params.get("error"));
            String expected = n(expectedState);
            boolean stateMatches = !expected.isEmpty() && expected.equals(state);
            if (error.isEmpty()) error = n(params.get("error_description"));
            /* 参数名也可能含敏感业务字段，只记录 OAuth 诊断所需的固定布尔项。 */
            Set<String> safeKeys = new LinkedHashSet<>();
            for (String k : new String[]{"code", "state", "error", "error_description"}) {
                if (keys.contains(k)) safeKeys.add(k);
            }
            String safe = (u.getScheme() == null ? "" : u.getScheme() + "://")
                    + host + path + " oauthParams=" + safeKeys;
            return new Result(true, sameHost, callbackPath, !code.isEmpty(), !state.isEmpty(),
                    stateMatches, code, state, error, path, safe);
        } catch (Exception e) {
            return new Result(false, false, false, false, false, false,
                    "", "", "", "", "invalid-url:" + e.getClass().getSimpleName());
        }
    }

    private static void readParams(String raw, Map<String, String> out, Set<String> keys) {
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String key = dec(eq >= 0 ? part.substring(0, eq) : part);
            if (key.isEmpty()) continue;
            String value = dec(eq >= 0 ? part.substring(eq + 1) : "");
            keys.add(key);
            if (!out.containsKey(key)) out.put(key, value);
        }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    private static String n(String s) { return s == null ? "" : s.trim(); }
}
