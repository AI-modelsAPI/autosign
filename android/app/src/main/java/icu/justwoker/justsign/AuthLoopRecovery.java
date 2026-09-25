package icu.justwoker.justsign;

import java.net.URI;
import java.util.Locale;

/**
 * v1.1.9：把「授权页反复加载」从死胡同变成一次自愈。
 *
 * 真机实测（1.1.9）：站点会话过期（/api/user/self 401）后点授权，带着失效的站点
 * Cookie 打开 /login，站点在 /login↔/console 之间反复 302，或阿里云 WAF 质询页
 * 因放行 Cookie 未落分区而无限自刷 —— 导航熔断 10s 内触发，用户永远见不到登录页。
 *
 * 策略：熔断**第一次**发生在「站点自身域」时，不直接报错，而是清一次该站点域的
 * Cookie（保留 connect.linux.do 论坛登录态，OAuth 仍一键），重置计数并重新加载
 * /login —— 让真正可交互的登录页显示出来。若清理后再次熔断，才按硬失败报错。
 *
 * 该类只做「要不要自愈」的纯判断，方便无 Android 依赖地做真实回归；实际清 Cookie /
 * reload 由 AuthActivity 执行。
 */
public final class AuthLoopRecovery {
    private boolean used = false;

    /** 熔断触发时调用：返回 true = 本次应先自愈（清站点 Cookie + 重载），不要报错。 */
    public boolean shouldSelfHeal(String trippedUrl, String siteBaseUrl) {
        if (used) return false;
        if (!sameHost(trippedUrl, siteBaseUrl)) return false;   // 只对站点自身域自愈
        used = true;
        return true;
    }

    public boolean consumed() { return used; }

    /** 显式重试时复位，给用户新的一次自愈额度。 */
    public void reset() { used = false; }

    /**
     * Cookie 头里是否含「非 WAF」的站点 Cookie —— 用于区分「真的有登录会话」和
     * 「只有阿里云 WAF 防护 Cookie」。acw_sc__v2 / acw_tc / cdn_sec_tc / ssxmod_itna
     * 等在登录前就会下发，若把它们当作已登录会话，就会在会话已过期（401）时仍显示
     * 「检测到已保存的登录会话，正在自动完成授权」并复用死会话。
     */
    public static boolean hasNonWafSiteCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return false;
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            String name = (eq > 0 ? pair.substring(0, eq) : pair).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (name.startsWith("acw_") || name.startsWith("cdn_sec_") || name.startsWith("ssxmod_")
                    || name.equals("aliyungf_tc") || name.startsWith("waf")) continue;
            return true;
        }
        return false;
    }

    static boolean sameHost(String a, String b) {
        String ha = host(a), hb = host(b);
        return !ha.isEmpty() && ha.equals(hb);
    }

    private static String host(String url) {
        if (url == null) return "";
        try {
            URI u = URI.create(url.trim());
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) { return ""; }
    }
}
