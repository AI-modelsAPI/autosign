package icu.justwoker.justsign;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Catalog — 内置公益站点清单（v0.1.3）。
 * 数据来源：wynx1123/ai-welfare-hub data/sites.json（快照 2026-09-04）。
 * 添加站点 = 从本清单下拉选择（取消自定义输入）；站点名可点击跳转主页（homeUrl）。
 * checkinType：manual = 点「立即签到」POST /api/user/checkin 领奖励；
 *              login  = 登录即签到（额度每日自动发放，点签到 = 查当日奖励记录）。
 */
public final class Catalog {
    private Catalog() {}

    private static final String[][] SITES = {
        // name, homeUrl, checkinType, reward, note, affUrl（邀请链接，卡片站名跳转用）, provider, [oauthProviders]
        //   第8列 oauthProviders（可选，逗号分隔）：站点同时支持的多种登录方式，供「添加账号」列出多身份；
        //   缺省则单 provider(第7列)。首项为主 provider（新账号推荐）。
        // checkinType 说明：
        //   newapi  = New API 系（有 GET/POST /api/user/checkin），可全自动签到
        //   login   = 登录即发额度，无独立签到接口（刷新即取奖励并置已签）
        //   web     = 非 New API 或接口不通，只能开站点主页人工处理
        // provider 说明（缺省 github）：站点使用的 OAuth 方式，决定授权 URL 与兑换端点
        //   github  = https://github.com/login/oauth/authorize + /api/oauth/github
        //   linuxdo = https://connect.linux.do/oauth2/authorize + /api/oauth/linuxdo
        {"AgentRouter",  "https://agentrouter.org",    "login",  "注册 $175 + 每日签到 $25", "GPT5.6SoL / Claude Opus 4.8 / Claude Opus 5", "https://agentrouter.org/register?aff=nc7C", "github", "github,linuxdo"},
        {"JustDoWork",   "https://api.justwoker.icu",  "newapi", "注册 $90 + 每日签到 $20",  "Claude Opus 4.8 / Claude Opus 5",              "https://api.justwoker.icu/sign-up?aff=wFQu", "github"},
        {"GoRouter",     "https://gorouter.app",       "login",  "注册 $70 + 每日签到 $10",  "Claude Opus 4.8 / Claude Opus 5",              "https://gorouter.app/sign-up?aff=Dr35", "github"},
        {"SeekAI",      "https://seekai.cc",          "newapi", "每日签到奖励",               "New API 系，支持无感人机验证签到",               "https://seekai.cc/sign-up?aff=sxto", "github"},
        {"KKtoken AI",   "https://kktoken.cc",         "newapi", "注册 $75 + 每日签到 $25",  "Claude Opus 4.8 / Claude Opus 5",              "https://kktoken.cc/sign-up?aff=BpDr", "github"},
        /* AnyRouter 2026-09-18 官方前端：登录后 POST /api/user/sign_in；
         * 禁止将通用 /api/user/checkin 的HTTP200权限错误当作接口存在。
         * GitHub只停止新注册，旧用户仍可登录；新账号默认Linux DO。
         * newapi在此表示由OffscreenCheckin后台执行，实际协议按精确origin分流。 */
        {"AnyRouter",    "https://anyrouter.top",      "newapi", "每日签到 $25",              "Linux DO 登录（首次需人工答题），Claude / GPT 系列", "https://anyrouter.top/register?aff=GWxP", "linuxdo"},
    };
    public static ArrayList<JSONObject> all() {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (String[] s : SITES) {
            try {
                out.add(new JSONObject()
                        .put("key", Store.siteKeyOf(s[1]))
                        .put("name", s[0]).put("homeUrl", s[1])
                        .put("checkinType", s[2]).put("reward", s[3]).put("note", s[4])
                        .put("affUrl", s[5])
                        .put("oauthProvider", s.length > 6 ? s[6] : "github")
                        .put("oauthProviders", s.length > 7 ? s[7] : ""));
            } catch (Exception ignored) {}
        }
        return out;
    }
    /** 站点 OAuth 方式（缺省 github）。读站点对象，兼容自定义站无该字段的情况。 */
    public static String providerOf(JSONObject site) {
        String p = site == null ? "" : site.optString("oauthProvider", "").trim().toLowerCase(java.util.Locale.US);
        return p.isEmpty() ? "github" : p;
    }

    public static JSONObject byKey(String key) {
        if (key == null) return null;
        for (JSONObject c : all()) if (key.equals(c.optString("key"))) return c;
        return null;
    }
}