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
        // name, homeUrl, checkinType, reward, note, affUrl（邀请链接，卡片站名跳转用）
        // checkinType 说明：
        //   newapi  = New API 系（有 GET/POST /api/user/checkin），可全自动签到
        //   login   = 登录即发额度，无独立签到接口（刷新即取奖励并置已签）
        //   web     = 非 New API 或接口不通，只能开站点主页人工处理
        {"AgentRouter",  "https://agentrouter.org",    "login",  "注册 $175 + 每日签到 $25", "GPT5.6SoL / Claude Opus 4.8 / Claude Opus 5", "https://agentrouter.org/register?aff=nc7C"},
        {"JustDoWork",   "https://api.justwoker.icu",  "newapi", "注册 $90 + 每日签到 $20",  "Claude Opus 4.8 / Claude Opus 5",              "https://api.justwoker.icu/sign-up?aff=wFQu"},
        {"GoRouter",     "https://gorouter.app",       "login",  "注册 $70 + 每日签到 $10",  "Claude Opus 4.8 / Claude Opus 5",              "https://gorouter.app/sign-up?aff=Dr35"},
        {"KKtoken AI",   "https://kktoken.cc",         "newapi", "注册 $75 + 每日签到 $25",  "Claude Opus 4.8 / Claude Opus 5",              "https://kktoken.cc/sign-up?aff=BpD"},
    };

    public static ArrayList<JSONObject> all() {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (String[] s : SITES) {
            try {
                out.add(new JSONObject()
                        .put("key", Store.siteKeyOf(s[1]))
                        .put("name", s[0]).put("homeUrl", s[1])
                        .put("checkinType", s[2]).put("reward", s[3]).put("note", s[4])
                        .put("affUrl", s[5]));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static JSONObject byKey(String key) {
        if (key == null) return null;
        for (JSONObject c : all()) if (key.equals(c.optString("key"))) return c;
        return null;
    }
}