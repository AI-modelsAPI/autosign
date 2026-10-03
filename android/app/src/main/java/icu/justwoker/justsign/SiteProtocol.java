package icu.justwoker.justsign;

import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;

/** 仅封装已实测协议；不从账号别名/HTTP200推断身份或业务成功。 */
public final class SiteProtocol {
    private SiteProtocol() {}

    public static boolean isAnyRouter(JSONObject site) {
        if (site == null) return false;
        String url = site.optString("baseUrl", site.optString("homeUrl", ""));
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme())
                    && "anyrouter.top".equalsIgnoreCase(u.getHost())
                    && (u.getPort() == -1 || u.getPort() == 443) && u.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }

    public static String tokenListPath(JSONObject site) {
        return "/api/token/?p=" + (isAnyRouter(site) ? "0" : "1") + "&size=100";
    }

    public static JSONObject selfUser(JSONObject response) {
        JSONObject envelope = response == null ? null : response.optJSONObject("data");
        if (envelope == null) return null;
        JSONObject data = envelope.optJSONObject("data");
        if (data == null) data = envelope;
        JSONObject user = data.optJSONObject("user");
        return user == null ? data : user;
    }

    private static boolean finiteField(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false;
        Object v = obj.opt(key);
        if (!(v instanceof Number) && !(v instanceof String)) return false;
        try { return Double.isFinite(Double.parseDouble(String.valueOf(v))); }
        catch (Exception e) { return false; }
    }

    public static boolean validSelf(JSONObject response, String expectedId) {
        if (response == null || response.optInt("http") != 200) return false;
        JSONObject envelope = response.optJSONObject("data");
        if (envelope == null || !envelope.optBoolean("success", false)) return false;
        JSONObject user = selfUser(response);
        if (!finiteField(user, "quota") || !finiteField(user, "used_quota")) return false;
        if (expectedId != null && !expectedId.isEmpty() && !"null".equals(expectedId)) {
            if (!expectedId.matches("[0-9]+") || user == null
                    || !expectedId.equals(user.optString("id", ""))) return false;
        }
        return true;
    }

    public static boolean canStoreStatus(JSONObject status) {
        return status != null && status.optBoolean("ok", false) && status.optInt("http") == 200
                && finiteField(status, "availableUSD") && finiteField(status, "usedUSD");
    }

    public static String provider(JSONObject site, JSONObject account) {
        String p = account == null ? "" : account.optString("authProvider", "").trim().toLowerCase(Locale.US);
        if (p.equals("github") || p.equals("linuxdo")) return p;
        p = site == null ? "" : site.optString("oauthProvider", "").trim().toLowerCase(Locale.US);
        return p.equals("linuxdo") ? "linuxdo" : "github";
    }

    /**
     * 「添加账号」可选身份列表：GitHub + Linux DO 全部列出，与站点自身声明无关。
     * v1.3.2：不再按 oauthProviders 裁剪——站点是否真开着某一路 OAuth 由站点在授权时自行判定，
     * 列表的职责只是把账号选出来。此前只有 AgentRouter/AnyRouter 声明了双 provider，
     * 于是 SeekAI、KKtoken 等站点的 L 站账号在「添加账号」里根本不出现。
     * 顺序：站点主 provider 在前（沿用 oauthProvider，新账号推荐），另一个随后。
     */
    public static String[] providers(JSONObject site) {
        String main = provider(site, null);
        return "linuxdo".equals(main) ? new String[]{"linuxdo", "github"}
                : new String[]{"github", "linuxdo"};
    }

    /** 账号列表里该 provider 的中文名（与设置页一致，避免同一身份两种叫法）。 */
    public static String providerLabel(String provider) {
        return "linuxdo".equals(provider) ? "Linux DO" : "GitHub";
    }

    /**
     * 统一账号选择列表：把「站点可用的每个 provider」×「支持该 provider 的每条凭据」展开成扁平选项，
     * 让卡片添加账号时一次性列出所有登录身份，用户选谁就走谁的授权流程，不再先选 provider 再选账号。
     * 返回元素为 [credentialId, provider]，顺序稳定（凭据外层、provider 内层），
     * 因此同一凭据在 AnyRouter 上的 Linux DO / GitHub 两个身份会相邻出现。
     */
    public static java.util.List<String[]> accountOptions(JSONObject site, org.json.JSONArray credentials) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        if (credentials == null) return out;
        String[] provs = providers(site);
        for (int i = 0; i < credentials.length(); i++) {
            JSONObject c = credentials.optJSONObject(i);
            if (c == null) continue;
            for (String prov : provs) {
                if (credentialSupports(c, prov)) out.add(new String[]{ c.optString("id"), prov });
            }
        }
        return out;
    }

    public static String credentialUser(JSONObject credential, String provider) {
        if (credential == null) return "";
        String field = "linuxdo".equals(provider) ? "siteAccount" : "githubUser";
        String value = credential.optString(field, "").trim();
        return "null".equals(value) ? "" : value;
    }

    public static boolean credentialSupports(JSONObject credential, String provider) {
        return !credentialUser(credential, provider).isEmpty();
    }

    public static boolean isSessionLimit(int http, JSONObject body) {
        if (http == 409) return true;
        if (body == null) return false;
        if ("AUTH_SESSION_LIMIT".equalsIgnoreCase(body.optString("code", ""))) return true;
        JSONObject error = body.optJSONObject("error");
        return error != null && "AUTH_SESSION_LIMIT".equalsIgnoreCase(error.optString("code", ""));
    }

    public static boolean shouldOpenAuthUi(boolean ok, boolean needUi) { return !ok && needUi; }

    private static final long DAY_MS = 86_400_000L;
    /**
     * 北京时间08:00恰为UTC零点；
     * 当前时间所属奖励周期的08:00时间戳（毫秒）。
     * 例如 2026-09-18 07:59:59 所属周期为 2026-09-17 08:00:00；
     * 到了 2026-09-18 08:00:00，周期立即切换为 2026-09-18 08:00:00。
     */
    public static long rewardEpochMs(long now) {
        return Math.floorDiv(now, DAY_MS) * DAY_MS;
    }

    /** 北京时间08:00恰为UTC零点；日期代表奖励周期开始日，不是手机日历日。 */
    public static String rewardDate(long now) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(now));
    }
    /** 八点前不主动把一次请求记成当天领取，避免跨周期误跳过。 */
    public static boolean rewardWindowOpen(long now) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        c.setTimeInMillis(now);
        return c.get(java.util.Calendar.HOUR_OF_DAY) >= 8;
    }
    /** 首次可补跑当日；日常续排固定北京时间08:05，不因应用反复打开而顺延。 */
    public static long nextAnyRouterRun(long now, boolean catchUp) {
        long target = Math.floorDiv(now, DAY_MS) * DAY_MS + 5 * 60_000L;
        if (now < target) return target;
        return catchUp && rewardWindowOpen(now) ? now : target + DAY_MS;
    }
    /**
     * 判定 AnyRouter 是否在当前周期（即最近已过去的北京时间 08:00 之后）完成过签到/重登。
     * 支持显式 api_sign_in 回执，也支持 08:00 之后成功登入/重登的时间戳（lastLogin）。
     */
    public static boolean hasSignInReceipt(JSONObject account, long now) {
        if (account == null) return false;
        long epoch = rewardEpochMs(now);
        long lastLogin = account.optLong("lastLogin", 0);
        if (lastLogin >= epoch && lastLogin <= now) return true;
        if (!hasSignInReceipt(account, rewardDate(now))) return false;
        return account.optJSONObject("lastCheckin").optLong("time", 0) <= now;
    }
    public static boolean hasSignInReceipt(JSONObject account, String period) {
        JSONObject receipt = account == null ? null : account.optJSONObject("lastCheckin");
        if (receipt == null || period == null || !period.equals(receipt.optString("date"))
                || !"api_sign_in".equals(receipt.optString("source"))) return false;
        long time = receipt.optLong("time", 0);
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); f.setLenient(false);
            java.text.ParsePosition p = new java.text.ParsePosition(0);
            java.util.Date parsed = f.parse(period, p);
            if (parsed == null || p.getIndex() != period.length()) return false;
            long start = parsed.getTime();
            return time >= start && time < start + DAY_MS;
        } catch (Exception ignored) { return false; }
    }
}
