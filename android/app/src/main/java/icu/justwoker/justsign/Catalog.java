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
        {"AgentRouter",      "https://agentrouter.org",         "manual", "注册 $100 + 邀请 $50 + 签到 $25",      "AI Coding 公益站"},
        {"JustDoWork",       "https://api.justwoker.icu",       "manual", "注册 $70 + 签到 $22",                  "GitHub 一键登录，支持签到与绘图"},
        {"RawChat 公益站",   "https://new.sharedchat.cc/list/", "login",  "每日 $50 额度池（0 点重置）",          "Codex 公益站，需在控制台领取权益"},
        {"Matrix",           "https://matrix.mzsjai.com",       "login",  "邀请注册送 600 积分",                  "统一 API 网关 + 应用商店"},
        {"TaBiAI",           "https://tabitoken.com",           "manual", "注册 $100 + 邀请 $20",                 "专供 Claude Opus，按次计费"},
        {"GoRouter",         "https://gorouter.app",            "manual", "注册 $50 + 邀请 $20",                  "按次计费里最便宜的一档"},
        {"肖恩Ai",           "https://free.supxh.xin",          "manual", "注册 5000 点 + 签到 1000~3000 点/日",  "社区反馈存活率低"},
        {"SeekAI",           "https://seekai.cc",               "login",  "—",                                    "Cloudflare 防护较严，需浏览器注册"},
        {"NOFX",             "https://nofx.one",                "manual", "注册 20 积分 + 签到 5/日",             "任务型积分站"},
        {"kktoken",          "https://kktoken.cc",              "manual", "注册 $100 + 签到 $20",                 "专供 Claude Opus 全家桶"},
        {"维云",             "https://vsllm.com",               "login",  "—",                                    "67+ 模型，签到暂未开放"},
        {"DoCode",           "https://docode.cc",               "login",  "注册 $300 + 邀请 $60",                 "免费套餐仅 GPT"},
        {"TrueSOTA",         "https://true-sota.com",           "manual", "邀请 $20/人（周上限 10 人）",          "面板数据不公开"},
        {"幻城网安",         "https://api.hcnsec.cn",           "manual", "每日签到，余额可兑 SVIP",              "公益大模型安全网关"},
        {"Vyce AI",          "https://vyceai.com",              "login",  "邀请注册双方各得 $10",                 "AI API 代理"},
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