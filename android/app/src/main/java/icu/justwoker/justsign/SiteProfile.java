package icu.justwoker.justsign;

import org.json.JSONObject;

/**
 * 站点协议表：把「刷新额度 / 签到状态 / 签到动作 / 奖励判据」四件事的端点与字段，
 * 从散落各处的硬编码 if 里抽成一份可配置的数据。加站 = 加一份数据，不动引擎。
 *
 * 端点与签到方式全部来自**真实浏览器同源实测 + 阴性对照**（不是猜的）：
 *
 *   对照实验的做法：对每个站同时请求「肯定不存在的路径」。
 *     GET  /api/user/<不存在>  → 401   说明 GET 的 401 是通配鉴权，**没有区分度**
 *     POST /api/user/<不存在>  → 404   说明 POST 的 404 才是「端点不存在」
 *   因此判定端点是否存在，只认 **POST 的 404/非 404**。
 *
 *   站点                 POST /api/user/checkin   结论
 *   seekai.cc            401                     端点存在 → 按钮式签到
 *   kktoken.cc           401                     端点存在 → 按钮式签到
 *   api.justwoker.icu    401                     端点存在 → 按钮式签到
 *   gorouter.app         502（上游挂）            无法验证；/api/status 200 且 checkin_enabled=true
 *   agentrouter.org      404                     端点不存在 → 签到在登录响应 checked_in 里完成
 *                                                  （其前端代码只有 checked_in 判定，无签到调用）
 *
 *   fengwind 主站        所有签到路径 404          无签到；会话在 /api/v1/auth/me
 *   fengwind 福利站      GET 404 / POST 401       签到只在子站点：POST /api/checkin
 *                        /api/me /api/level /api/checkin/status /api/ledger 均 401（存在）
 *
 * 设计原则：
 *   1. 端点与字段名是数据，不是代码分支。
 *   2. 未识别时回落 New API（历史行为不变，不回归）。
 *   3. 字段认不出就如实报「未知」，绝不编造数字；端点 404 绝不标记已签。
 */
public final class SiteProfile {

    private SiteProfile() {}

    /** 站点形态。决定四个功能各自走哪个端点、认哪些字段。 */
    public enum Kind {
        /** New API / One API 系：四个端点齐全。 */
        NEWAPI,
        /** sub2api 系（fengwind 主站/捐赠站）：会话在 /api/v1/auth/me 或 /api/me。 */
        SUB2API,
        /** 福利站（fengwind 福利子域）：签到与额度是独立端点，签到需 Turnstile。 */
        WELFARE
    }

    /**
     * 签到方式。不同站点规则不同，这是站点的属性，不是引擎里的 if。
     *
     * 用户明确的三类（缺一不可）：
     *   · 有重登即得的
     *   · 有点签到按钮的
     *   · 有早八点重登直接入账、无任何提示的
     */
    public enum CheckinMode {
        /** 点签到按钮：POST 签到端点，按响应判定。 */
        BUTTON,
        /** 重登即得：登出→重登→按余额增量核对到账。 */
        RELOGIN,
        /**
         * 早八点重登直接入账、无任何提示（用户实证的第三类）。
         * 判据与 RELOGIN 相同（时间门禁 + 回执 + 余额增量），区别在【提示】：
         * 成功不打扰用户，但日志照实记录；失败照实上报。
         */
        RELOGIN_SILENT,
        /** 登录即得：登录时已发放，只需核验当日是否有到账记录。 */
        LOGIN_GRANT,
        /** 只能网页手动：明确告知，不假装成功。 */
        WEB_ONLY
    }

    /* ================= 各形态的端点与字段 ================= */

    /** 刷新额度用的会话端点（按顺序探测，第一个返回 200 的即用）。 */
    public static String[] selfPaths(Kind k) {
        switch (k) {
            case WELFARE: return new String[]{"/api/me", "/api/level"};
            /* 顺序按「最可能命中」排：捐赠站是 /api/me，主站是 /api/v1/auth/me。
             * 具体站点用 selfPaths(site) 按主机名调整，这里给默认序。 */
            case SUB2API: return new String[]{"/api/v1/auth/me", "/api/me"};
            default:      return new String[]{"/api/user/self"};
        }
    }

    /**
     * 按具体站点排序的会话端点候选：捐赠站（donation）用 /api/me，
     * 主站用 /api/v1/auth/me。省掉一次注定 404 的探测（真机日志实测）。
     */
    public static String[] selfPaths(JSONObject site) {
        Kind k = kindOf(site);
        if (k != Kind.SUB2API) return selfPaths(k);
        String h = hostOf(site == null ? "" : site.optString("baseUrl",
                site.optString("homeUrl", "")));
        if (h.contains("donation")) return new String[]{"/api/me", "/api/v1/auth/me"};
        return new String[]{"/api/v1/auth/me", "/api/me"};
    }

    /** 可用额字段名候选（按顺序取第一个有限的数值）。 */
    public static String[] balanceFields(Kind k) {
        switch (k) {
            case WELFARE:
            case SUB2API:
                return new String[]{"balance", "usd_balance", "available_balance",
                        "credit_balance", "available_usd", "balance_usd", "available"};
            default:
                return new String[]{"quota"};
        }
    }

    /** 累计已用字段名候选。 */
    public static String[] usedFields(Kind k) {
        switch (k) {
            case WELFARE:
            case SUB2API:
                return new String[]{"usage_total_cost", "total_cost", "actual_cost",
                        "account_cost", "used_usd", "total_used", "spent_usd", "used"};
            default:
                return new String[]{"used_quota"};
        }
    }

    /** 签到状态端点；空串表示该形态没有只读签到状态接口。 */
    public static String checkinStatusPath(Kind k) {
        switch (k) {
            case WELFARE: return "/api/checkin/status";
            case SUB2API: return "";
            default:      return "/api/user/checkin";
        }
    }

    /**
     * 签到动作端点（按具体站点判定）。空串 = 该站没有签到接口。
     *
     * 真实浏览器实测（同源请求 + 阴性对照，见类注释）：
     *   seekai.cc / kktoken.cc / api.justwoker.icu → POST /api/user/checkin 存在（401）
     *   agentrouter.org                            → POST /api/user/checkin 不存在（404）
     * 后者签到在登录响应 checked_in 里完成，前端无签到调用，因此不能发 POST。
     */
    public static String checkinActionPath(JSONObject site) {
        Kind k = kindOf(site);
        if (k == Kind.SUB2API) return "";
        if (k == Kind.WELFARE) return "/api/checkin";
        /* New API 系里，实测只有 agentrouter 没有签到端点（登录即签）。 */
        String h = hostOf(site == null ? "" : site.optString("baseUrl",
                site.optString("homeUrl", "")));
        if (h.contains("agentrouter")) return "";
        return "/api/user/checkin";
    }

    /** 签到动作端点（按形态；站点级判定见 checkinActionPath(JSONObject)）。 */
    public static String checkinActionPath(Kind k) {
        switch (k) {
            case WELFARE: return "/api/checkin";
            case SUB2API: return "";
            default:      return "/api/user/checkin";
        }
    }

    /** 奖励/流水判据端点；空串表示没有该接口。 */
    public static String rewardLogPath(Kind k) {
        switch (k) {
            case WELFARE: return "/api/ledger";
            case SUB2API: return "";
            default:      return "/api/log/self";
        }
    }

    /** 签到动作是否需要 Turnstile 令牌（福利站 POST /api/checkin 要求 X-Turnstile-Token）。 */
    public static boolean checkinNeedsTurnstile(Kind k) { return k == Kind.WELFARE; }

    /**
     * 该形态能否用「离屏 WebView + 站点自身 JS」签到。
     * 需要 Turnstile 的站必须走真实浏览器上下文，原生 POST 拿不到令牌。
     */
    public static boolean checkinViaWebView(Kind k) { return k == Kind.WELFARE; }

    /* ================= 子站点（福利站）支持 ================= */

    /**
     * 站点内福利站地址（可选）。
     *
     * 真机 + 站点实测：fengwind 的福利站是**主站的子站点**，不是独立站点：
     *   · 主站首页直接导航到 https://api-welfalre.fengwind.com/
     *   · 福利站登录走主站 SSO：
     *     GET https://api-welfalre.fengwind.com/api/auth/login-url
     *     → https://api.fengwind.com/sso/continue?client_id=welfare&redirect_uri=…/auth/callback
     *     → 换得 welfare_token（Bearer）
     *   · 福利站自己的接口：/api/me、/api/level、/api/checkin/status、/api/checkin、/api/ledger
     *
     * 因此「签到」和「额度」发生在福利站上，而「登录」发生在主站上。
     * 用户添加的是主站，福利站地址作为该站点的一个可选字段存放。
     */
    public static String welfareUrl(JSONObject site) {
        if (site == null) return "";
        JSONObject meta = site.optJSONObject("meta");
        String v = meta == null ? "" : meta.optString("welfareUrl", "");
        if (v.isEmpty()) v = site.optString("welfareUrl", "");
        v = v.trim().replaceAll("/+$", "");
        return v;
    }

    /** 该站点是否配置了福利站（配置了就走福利站的签到/额度端点）。 */
    public static boolean hasWelfare(JSONObject site) {
        return !welfareUrl(site).isEmpty();
    }

    /**
     * 福利站需要独立的 Bearer 令牌（welfare_token），与主站 auth_token 不同。
     * 账号上单独存，取不到就说明还没走完 SSO。
     */
    public static String welfareToken(JSONObject acc) {
        if (acc == null) return "";
        String t = acc.optString("welfareToken", "").trim();
        return "null".equals(t) ? "" : t;
    }

    /* ================= 形态判定 ================= */

    /**
     * 判定站点形态。优先用已持久化的探测结果，未探测过则按主机名启发式判断，
     * 最后回落 New API（保证既有站点行为不变）。
     *
     * 主机名启发式只用于「第一次见到这个站」时少发几个注定 404 的请求；
     * 真正的判据是探测结果（见 detect）。
     */
    public static Kind kindOf(JSONObject site) {
        if (site == null) return Kind.NEWAPI;
        /* ① 显式配置的福利站优先级最高：站点自己声明了子站点，
         *    说明签到/额度都在子站点上（fengwind 就是这个模式）。 */
        if (hasWelfare(site)) return Kind.WELFARE;
        /* ② 探测结果（meta.protocolKind，与 Store.putSiteMeta 一致；也接受顶层）。 */
        String stored = "";
        JSONObject meta = site.optJSONObject("meta");
        if (meta != null) stored = meta.optString("protocolKind", "");
        if (stored.isEmpty()) stored = site.optString("protocolKind", "");
        Kind k = parse(stored);
        if (k != null) return k;
        /* ③ 主机名启发式（仅首见兜底）。 */
        String base = site.optString("baseUrl", site.optString("homeUrl", ""));
        return kindFromHost(base);
    }

    /** 主机名启发式（仅首见兜底；探测结果优先）。 */
    public static Kind kindFromHost(String baseUrl) {
        String h = hostOf(baseUrl);
        if (h.isEmpty()) return Kind.NEWAPI;
        /* fengwind 系：主站/捐赠站是 sub2api，福利子域是 welfare。 */
        if (h.contains("welfalre") || h.contains("welfare")) return Kind.WELFARE;
        if (h.contains("fengwind") || h.contains("donation")) return Kind.SUB2API;
        return Kind.NEWAPI;
    }

    static String hostOf(String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase(java.util.Locale.US);
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int j = s.indexOf('/');
        if (j >= 0) s = s.substring(0, j);
        return s;
    }

    static Kind parse(String s) {
        if (s == null) return null;
        switch (s.trim().toUpperCase(java.util.Locale.US)) {
            case "NEWAPI":  return Kind.NEWAPI;
            case "SUB2API": return Kind.SUB2API;
            case "WELFARE": return Kind.WELFARE;
            default:        return null;
        }
    }

    public static String kindName(Kind k) { return k == null ? "NEWAPI" : k.name(); }
    /* ================= 签到模式判定 ================= */

    /**
     * 判定签到方式。优先级：
     *   1. 站点显式声明（持久化 checkinMode）
     *   2. 站点形态
     *   3. 主机名（仅实测确认过的差异）
     *
     * 推断规则全部来自真实浏览器实测（同源请求 + 阴性对照），不是猜测：
     *   WELFARE   → BUTTON      POST /api/checkin 存在，需 Turnstile
     *   SUB2API   → LOGIN_GRANT 无任何签到端点（全部 404），登录时发放
     *   AnyRouter → RELOGIN_SILENT  早八点重登入账、无提示（用户实证）
     *   agentrouter → RELOGIN   POST /api/user/checkin = 404，当日额度靠登录动作发放
     *                           （其前端只在登录响应里判 checked_in 提示「签到成功，新增额度已到账」）
     *   其它 NEWAPI → BUTTON    POST /api/user/checkin 存在（401 而非 404）
     */
    public static CheckinMode checkinMode(JSONObject site) {
        if (site == null) return CheckinMode.BUTTON;
        CheckinMode declared = parseMode(site.optString("checkinMode", ""));
        if (declared != null) return declared;

        Kind kind = kindOf(site);
        if (kind == Kind.WELFARE) return CheckinMode.BUTTON;
        if (kind == Kind.SUB2API) return CheckinMode.LOGIN_GRANT;

        /* checkinType 是站点清单里的历史字段，值域 newapi/manual/login/web。 */
        String t = site.optString("checkinType", "");
        if ("web".equals(t)) return CheckinMode.WEB_ONLY;

        /* AnyRouter：早八点重登直接入账、无提示（用户实证的第三类）。 */
        if (SiteProtocol.isAnyRouter(site)) return CheckinMode.RELOGIN_SILENT;

        /* agentrouter：实测 POST /api/user/checkin = 404（没有按钮端点），
         * 而其前端代码在登录响应里判 ut.checked_in 才提示「签到成功，新增额度已到账」
         * —— 说明当日额度是**登录动作**发放的，必须主动重登才触发。
         * 因此归 RELOGIN（登出→重登→按余额增量核对到账）。 */
        String host = hostOf(site.optString("baseUrl", site.optString("homeUrl", "")));
        if (host.contains("agentrouter")) return CheckinMode.RELOGIN;

        /* 其余 New API 系（seekai / kktoken / justwoker / gorouter）实测都有
         * POST /api/user/checkin，走按钮式直连签到。 */
        return CheckinMode.BUTTON;
    }

    static CheckinMode parseMode(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return CheckinMode.valueOf(s.trim().toUpperCase(java.util.Locale.US)); }
        catch (Exception e) { return null; }
    }

    public static String modeName(CheckinMode m) { return m == null ? "BUTTON" : m.name(); }

    /**
     * 该模式是否「到账即置已签」——用户明确要求：到账就置已签，这是最能被感知的信号。
     *
     * 对重登类站点，唯一可信的到账证据是【余额增量】（原 Engine.anyRouterReloginLocked 的判据），
     * 不采信 sign_in 之类不可靠的响应。
     */
    public static boolean marksCheckedOnCredit(CheckinMode m) {
        return m == CheckinMode.RELOGIN || m == CheckinMode.RELOGIN_SILENT;
    }

    /** 成功时是否打扰用户。RELOGIN_SILENT 成功不弹提示，但日志照实记录。 */
    public static boolean silentOnSuccess(CheckinMode m) {
        return m == CheckinMode.RELOGIN_SILENT;
    }

    /** 该模式是否必须走真实浏览器上下文（需要站点自身 JS 取令牌）。 */
    public static boolean needsBrowserContext(CheckinMode m, Kind k) {
        return m == CheckinMode.WEB_ONLY || checkinNeedsTurnstile(k);
    }
    /**
     * 由探测结果推断形态：New API 的 /api/user/self 存在（任意非 404）即 NEWAPI；
     * 否则按福利站专有端点 /api/checkin/status 是否存在区分 WELFARE / SUB2API。
     *
     * @param selfHttp   GET /api/user/self 的 HTTP 状态（0 表示传输失败）
     * @param checkinHttp GET /api/checkin/status 的 HTTP 状态
     */
    public static Kind infer(int selfHttp, int checkinHttp) {
        if (selfHttp > 0 && selfHttp != 404 && selfHttp != 405) return Kind.NEWAPI;
        if (checkinHttp > 0 && checkinHttp != 404 && checkinHttp != 405) return Kind.WELFARE;
        return Kind.SUB2API;
    }
}
