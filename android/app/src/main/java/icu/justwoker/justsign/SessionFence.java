package icu.justwoker.justsign;

import org.json.JSONObject;

/**
 * 会话栅栏：只有「响应确实属于本地保存的账号」且「本地凭据未被其它流程更新」时，
 * 离屏或原生结果才允许回填。用于阻止错号 Cookie 覆盖、以及过期结果覆盖新授权。
 */
public final class SessionFence {

    private SessionFence() {}

    /** 站点ID必须是纯十进制且非0，禁止换行/引号等可注入HTTP头的字符。 */
    public static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 18) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return !"0".equals(id);
    }

    /** 响应身份必须精确等于本地保存的站点ID。缺少任一侧一律拒绝。 */
    public static boolean identity(JSONObject self, JSONObject acc) {
        if (self == null || acc == null) return false;
        if (self.optInt("http") != 200) return false;
        String saved = acc.optString("siteUserId", "").trim();
        if (!validId(saved)) return false;
        JSONObject env = self.optJSONObject("data");
        if (env == null || env.opt("success") != Boolean.TRUE) return false;
        Object payload = env.opt("data");
        if (!(payload instanceof JSONObject)) return false;
        Object id = ((JSONObject) payload).opt("id");
        return id != null && saved.equals(String.valueOf(id).trim()) && validId(String.valueOf(id).trim());
    }

    public static boolean identityMatches(JSONObject self, String siteUserId) {
        if (self == null || siteUserId == null) return false;
        if (self.optInt("http") != 200) return false;
        String saved = siteUserId.trim();
        if (!validId(saved)) return false;
        JSONObject env = self.optJSONObject("data");
        if (env == null || env.opt("success") != Boolean.TRUE) return false;
        Object payload = env.opt("data");
        if (!(payload instanceof JSONObject)) return false;
        Object id = ((JSONObject) payload).opt("id");
        return id != null && saved.equals(String.valueOf(id).trim());
    }

    /** 凭据字段是否与快照一致（并发写回栅栏）。 */
    public static boolean sameSecret(JSONObject before, JSONObject after) {
        return unchanged(before, after);
    }

    /** 本地保存的站点账号ID（去空白），供离屏身份校验使用。 */
    public static String userIdOf(JSONObject acc) {
        if (acc == null) return "";
        String id = acc.optString("siteUserId", "").trim();
        return validId(id) ? id : "";
    }

    /**
     * 快照比较：账号被删除、或任何凭据字段在等待期间变化，都拒绝回填。
     * 非凭据字段（额度文案、状态缓存等）解析失败一律按“已变化”处理，宁可跳过也不越权覆盖。
     */
    public static boolean unchanged(JSONObject before, JSONObject after) {
        if (before == null || after == null) return false;
        for (String f : new String[]{"key", "siteCookie", "token", "siteUserId",
                "credentialId", "githubUser", "siteAccount", "authProvider"}) {
            if (!String.valueOf(before.optString(f, "")).equals(String.valueOf(after.optString(f, "")))) return false;
        }
        return true;
    }
}
