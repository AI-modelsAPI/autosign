package icu.justwoker.justsign;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阿里云 ESA（acw_sc__v2）质询页的解析与求解。
 *
 * v1.1.6 抽取自 AuthActivity：原算法只存在于授权页自愈逻辑里，导致
 * Engine.attempt() 的 WAF 自愈「依赖 wafCookie 缓存字段」，而该字段只在授权
 * 成功后才写入 —— 授权又需要先过 WAF，形成死锁。现在算法下沉到公共类，
 * 原生请求自己就能过质询，不再依赖任何缓存。
 *
 * 算法要点（逆向定稿）：arg1 按固定置换表重排 → 与固定密钥逐字节 XOR → 40 位十六进制。
 * 实测：首次响应 200+质询页，带算出的 acw_sc__v2 重发即得真实业务响应（401/200）。
 * 纯 Java 计算，无需 JS 引擎。
 */
final class WafChallenge {

    private WafChallenge() {}

    private static final int[] PERM = {
            0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9,
            0x13, 0x1f, 0x28, 0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb,
            0x27, 0x12, 0x14, 0x8, 0xe, 0x15, 0x20, 0x1a, 0x2, 0x1e,
            0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24
    };
    private static final String KEY = "3000176000856006061501533003690027800375";

    private static final Pattern ARG1 = Pattern.compile("var\\s+arg1\\s*=\\s*'([0-9A-Fa-f]+)'");

    /** 响应体是否为 WAF 质询页（含 arg1 赋值即视为待解质询）。 */
    static boolean isChallenge(String body) {
        return body != null && ARG1.matcher(body).find();
    }

    /** 从质询页正文提取 arg1；无则返回 ""。 */
    static String arg1(String body) {
        if (body == null) return "";
        Matcher m = ARG1.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /**
     * arg1 → acw_sc__v2。任何异常都返回 ""（调用方据此放弃重试，不再空转）。
     */
    static String solve(String arg1) {
        if (arg1 == null || arg1.isEmpty()) return "";
        try {
            int n = arg1.length();
            char[] q = new char[n];
            for (int x = 0; x < n; x++) {
                for (int z = 0; z < PERM.length; z++) {
                    if (PERM[z] == x + 1) { q[z] = arg1.charAt(x); break; }
                }
            }
            String u = new String(q);
            int ln = Math.min(u.length(), KEY.length());
            StringBuilder v = new StringBuilder();
            for (int i = 0; i + 1 < ln; i += 2) {
                int a = Integer.parseInt(u.substring(i, i + 2), 16)
                        ^ Integer.parseInt(KEY.substring(i, i + 2), 16);
                v.append(String.format(Locale.US, "%02x", a));
            }
            return v.toString();
        } catch (Exception e) { return ""; }
    }

    /** 便捷入口：给定质询页正文，直接得到 acw_sc__v2；非质询页返回 ""。 */
    static String solveFromBody(String body) {
        return isChallenge(body) ? solve(arg1(body)) : "";
    }

    /** 从质询响应头捕获 acw_tc（质询会话标识）。
     * 沙箱对照实测：只回送 acw_sc__v2 仍会被拦，两者齐带才放行。 */
    static String acwTcFromResponse(Object resp) {
        try {
            if (resp == null) return "";
            java.lang.reflect.Method m = resp.getClass().getMethod("header", String.class, String.class);
            return pickCookieValue(String.valueOf(m.invoke(resp, "Set-Cookie", "")), "acw_tc");
        } catch (Exception e) { return ""; }
    }
    /** 从 Set-Cookie 串里取指定 cookie 的值（纯字符串解析）。 */
    static String pickCookieValue(String setCookie, String name) {
        if (setCookie == null || setCookie.isEmpty() || name == null) return "";
        Matcher m = Pattern.compile("(?:^|[;,\\s])" + Pattern.quote(name) + "=([^;,\\s]+)")
                .matcher(setCookie);
        return m.find() ? m.group(1) : "";
    }

    /** 兼容两参调用（无 acw_tc）。 */
    static String mergeCookie(String existing, String acwScV2) {
        return mergeAcwScV2(existing, acwScV2);
    }

    /** 合并 acw_sc__v2（必需）与 acw_tc（可选）进既有 Cookie；同键覆盖、异键追加。 */
    static String mergeCookie(String existing, String acwScV2, String acwTc) {
        String r = mergeAcwScV2(existing, acwScV2);
        if (acwTc == null || acwTc.isEmpty() || !acwTc.matches("[0-9A-Za-z._-]+")) return r;
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        if (r != null) {
            for (String part : r.split(";")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("acw_tc=")) {
                    if (!replaced) { sb.append("acw_tc=").append(acwTc); replaced = true; }
                } else {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(t);
                }
            }
        }
        if (!replaced) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("acw_tc=").append(acwTc);
        }
        return sb.toString();
    }

    /**
     * 把 acw_sc__v2 合并进既有 Cookie 串：同键覆盖、异键追加。
     * 只动 acw_sc__v2，其余原样保留（会话 Cookie 一律不碰）。
     */
    static String mergeAcwScV2(String existing, String acwScV2) {
        /* 防御：值里不得出现分隔符／空白 —— 否则会被解析成额外 cookie（header 注入）。
         * 求解结果恒为小写十六进制，出现其它字符即视为异常输入，原样返回不合并。 */
        if (acwScV2 == null || acwScV2.isEmpty() || !acwScV2.matches("[0-9a-f]+")) {
            return existing == null ? "" : existing;
        }
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        if (existing != null) {
            for (String part : existing.split(";")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("acw_sc__v2=")) {
                    if (!replaced) { sb.append("acw_sc__v2=").append(acwScV2); replaced = true; }
                } else {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(t);
                }
            }
        }
        if (!replaced) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("acw_sc__v2=").append(acwScV2);
        }
        return sb.toString();
    }
}
