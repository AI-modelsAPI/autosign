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
        // name, homeUrl, checkinType, reward, note
        // checkinType 说明：
        //   newapi  = New API 系（有 GET/POST /api/user/checkin），可全自动签到
        //   login   = 登录即发额度，无独立签到接口（点签到 = 查当日奖励记录）
        //   web     = 非 New API 或接口不通，只能开站点主页人工处理
        {"AgentRouter",      "https://agentrouter.org",         "login",  "注册 $100 + 邀请 $50",                 "New API 变体：无 checkin POST，登录即发额度"},
        {"JustDoWork",       "https://api.justwoker.icu",       "newapi", "注册 $70 + 每日签到 $22 起",           "New API 原生，Turnstile 人机验证"},
        {"RawChat",          "https://new.sharedchat.cc/list/", "web",    "每日 $50 额度池（0 点重置）",          "独立网关，需在控制台手动领取"},
        {"Matrix",           "https://matrix.mzsjai.com",       "web",    "邀请注册送 600 积分",                  "非 New API，接口未开放"},
        {"TaBiAI",           "https://tabitoken.com",           "web",    "注册 $100 + 邀请 $20",                 "Cloudflare 拦截接口访问"},
        {"GoRouter",         "https://gorouter.app",            "web",    "注册 $50 + 邀请 $20",                  "Cloudflare 拦截接口访问"},
        {"肖恩Ai",           "https://free.supxh.xin",          "web",    "注册 5000 点 + 每日签到",              "非标准路由，存活率低"},
        {"SeekAI",           "https://seekai.cc",               "newapi", "—",                                    "New API 原生，Turnstile 人机验证"},
        {"NOFX",             "https://nofx.one",                "web",    "注册 20 积分 + 签到 5/日",             "非 New API，任务型积分站"},
        {"KKtoken AI",       "https://kktoken.cc",              "newapi", "注册 $100 + 每日签到 $20",             "New API 原生，Turnstile 人机验证"},
        {"维云",             "https://vsllm.com",               "login",  "—",                                    "New API 变体，签到未开放"},
        {"DoCode",           "https://docode.cc",               "login",  "注册 $300 + 邀请 $60",                 "New API 变体，签到未开放；无 GitHub 登录"},
        {"TrueSOTA",         "https://true-sota.com",           "web",    "邀请 $20/人（周上限 10 人）",          "非 New API"},
        {"幻城网安",         "https://api.hcnsec.cn",           "newapi", "每日签到，余额可兑 SVIP",              "New API 变体，无人机验证；无 GitHub 登录"},
        {"Vyce AI",          "https://vyceai.com",              "web",    "邀请注册双方各得 $10",                 "非 New API"},
    };

    public static ArrayList<JSONObject> all() {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (String[] s : SITES) {
            try {
                out.add(new JSONObject()
                        .put("key", Store.siteKeyOf(s[1]))
                        .put("name", s[0]).put("homeUrl", s[1])
                        .put("checkinType", s[2]).put("reward", s[3]).put("note", s[4]));
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