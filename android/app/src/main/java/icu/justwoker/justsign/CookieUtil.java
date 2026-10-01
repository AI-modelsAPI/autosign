package icu.justwoker.justsign;

/**
 * Cookie 工具（item6 收敛）。AuthActivity 与 SilentAuth 各有一份逐字相同的 extractCookies，
 * 提取到此公共位置，两处改调。行为严格等价：取每条 Set-Cookie 的 name=value（分号前），
 * 丢弃空值与 "deleted" 占位，用 "; " 拼接。
 */
final class CookieUtil {
    private CookieUtil() {}

    static String extractCookies(java.util.List<String> setCookies) {
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
}
