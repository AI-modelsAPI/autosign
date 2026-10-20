package icu.justwoker.justsign;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 站点协议表：形态 × 签到模式 × 四功能端点。
 *
 * 为什么必须测：旧实现把四个功能各自写死 New API 端点，
 * 非 New API 站（fengwind 系 /api/user/self 恒 404）四个功能全部静默失效。
 * 这张表就是修复的核心，写错的表现是「签到/刷新/重登都不工作」——
 * 只在运行时暴露，编译期完全看不出来。
 */
public class SiteProfileTest {

    private static JSONObject site(String baseUrl, String checkinType) throws Exception {
        return new JSONObject().put("baseUrl", baseUrl)
                .put("homeUrl", baseUrl)
                .put("checkinType", checkinType == null ? "login" : checkinType);
    }

    /* ================= 形态判定 ================= */

    /** 三套 fengwind 子域分别是哪种形态（真机实测 /api/user/self 全 404）。 */
    @Test public void fengwindHosts() {
        assertEquals(SiteProfile.Kind.SUB2API,
                SiteProfile.kindFromHost("https://api.fengwind.com"));
        assertEquals(SiteProfile.Kind.SUB2API,
                SiteProfile.kindFromHost("https://api-donation.fengwind.com"));
        assertEquals(SiteProfile.Kind.WELFARE,
                SiteProfile.kindFromHost("https://api-welfalre.fengwind.com"));
    }

    /** 未识别的站回落 NEWAPI，保证既有站点不回归。 */
    @Test public void unknownHostFallsBackToNewApi() {
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.kindFromHost("https://seekai.cc"));
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.kindFromHost("https://agentrouter.org"));
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.kindFromHost(""));
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.kindFromHost(null));
    }

    /** 已持久化的探测结果优先于主机名启发式。 */
    @Test public void storedKindWins() throws Exception {
        JSONObject s = site("https://seekai.cc", "newapi").put("protocolKind", "WELFARE");
        assertEquals(SiteProfile.Kind.WELFARE, SiteProfile.kindOf(s));
    }

    /** 探测推断：/api/user/self 非 404 即 New API；否则看 /api/checkin/status。 */
    @Test public void inferFromProbe() {
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.infer(401, 0));   // 存在但未登录
        assertEquals(SiteProfile.Kind.NEWAPI, SiteProfile.infer(200, 0));
        assertEquals(SiteProfile.Kind.WELFARE, SiteProfile.infer(404, 401));
        assertEquals(SiteProfile.Kind.SUB2API, SiteProfile.infer(404, 404));
        assertEquals(SiteProfile.Kind.SUB2API, SiteProfile.infer(0, 0));     // 传输失败不乱判
    }

    /* ================= 四功能端点 ================= */

    /** 刷新端点：New API 一个，sub2api 两个候选。 */
    @Test public void selfPaths() {
        assertArrayEquals(new String[]{"/api/user/self"},
                SiteProfile.selfPaths(SiteProfile.Kind.NEWAPI));
        assertArrayEquals(new String[]{"/api/v1/auth/me", "/api/me"},
                SiteProfile.selfPaths(SiteProfile.Kind.SUB2API));
        assertArrayEquals(new String[]{"/api/me", "/api/level"},
                SiteProfile.selfPaths(SiteProfile.Kind.WELFARE));
    }

    /** 额度字段：New API 用 quota，其它形态用 balance 系。 */
    @Test public void balanceFields() {
        assertArrayEquals(new String[]{"quota"},
                SiteProfile.balanceFields(SiteProfile.Kind.NEWAPI));
        assertTrue(java.util.Arrays.asList(SiteProfile.balanceFields(SiteProfile.Kind.SUB2API))
                .contains("balance"));
        assertTrue(java.util.Arrays.asList(SiteProfile.balanceFields(SiteProfile.Kind.WELFARE))
                .contains("balance"));
    }

    /** 签到端点：福利站是独立端点，sub2api 没有接口签到。 */
    @Test public void checkinEndpoints() {
        assertEquals("/api/user/checkin",
                SiteProfile.checkinStatusPath(SiteProfile.Kind.NEWAPI));
        assertEquals("/api/checkin/status",
                SiteProfile.checkinStatusPath(SiteProfile.Kind.WELFARE));
        assertEquals("", SiteProfile.checkinStatusPath(SiteProfile.Kind.SUB2API));

        assertEquals("/api/user/checkin",
                SiteProfile.checkinActionPath(SiteProfile.Kind.NEWAPI));
        assertEquals("/api/checkin",
                SiteProfile.checkinActionPath(SiteProfile.Kind.WELFARE));
        assertEquals("", SiteProfile.checkinActionPath(SiteProfile.Kind.SUB2API));
    }
    /** SUB2API 默认序：主站 /api/v1/auth/me 优先（真机实测 /api/me 在该站 404）。 */
    @Test public void sub2apiPathOrder() {
        assertArrayEquals(new String[]{"/api/v1/auth/me", "/api/me"},
                SiteProfile.selfPaths(SiteProfile.Kind.SUB2API));
    }

    /** 福利站签到要 Turnstile 令牌，必须走浏览器上下文。 */
    @Test public void welfareNeedsBrowserContext() {
        assertTrue(SiteProfile.needsBrowserContext(
                SiteProfile.CheckinMode.BUTTON, SiteProfile.Kind.WELFARE));
        assertFalse(SiteProfile.needsBrowserContext(
                SiteProfile.CheckinMode.BUTTON, SiteProfile.Kind.NEWAPI));
    }

    /* ================= 签到模式 ================= */

    /**
     * 用户明确的三类签到方式都必须能表达出来。
     *
     * 每一类的归属都来自真实浏览器实测（同源 POST + 阴性对照）：
     *   anyrouter     → 早八点重登入账、无提示（用户实证）
     *   seekai/kktoken/justwoker → POST /api/user/checkin 存在 → 点按钮
     *   agentrouter   → POST /api/user/checkin = 404 → 签到在登录时完成
     */
    @Test public void threeUserStatedModes() throws Exception {
        /* ① 早八点重登直接入账、无任何提示 */
        assertEquals(SiteProfile.CheckinMode.RELOGIN_SILENT,
                SiteProfile.checkinMode(site("https://anyrouter.top", "newapi")));
        /* ② 点签到按钮：实测这三个站 POST /api/user/checkin 回 401（存在） */
        assertEquals(SiteProfile.CheckinMode.BUTTON,
                SiteProfile.checkinMode(site("https://seekai.cc", "newapi")));
        assertEquals(SiteProfile.CheckinMode.BUTTON,
                SiteProfile.checkinMode(site("https://kktoken.cc", "newapi")));
        assertEquals(SiteProfile.CheckinMode.BUTTON,
                SiteProfile.checkinMode(site("https://api.justwoker.icu", "newapi")));
        /* ③ 重登即得：实测该站没有签到端点，当日额度靠登录动作发放 */
        assertEquals(SiteProfile.CheckinMode.RELOGIN,
                SiteProfile.checkinMode(site("https://agentrouter.org", "login")));
        /* 福利站是按钮型（需 Turnstile） */
        assertEquals(SiteProfile.CheckinMode.BUTTON,
                SiteProfile.checkinMode(site("https://api-welfalre.fengwind.com", "login")));
    }

    /** 实测：签到动作端点是否存在，决定能不能发 POST。 */
    @Test public void checkinActionPathByMeasuredEvidence() throws Exception {
        /* agentrouter 实测 404，不能发 POST */
        assertEquals("", SiteProfile.checkinActionPath(
                site("https://agentrouter.org", "login")));
        /* 其余 New API 站实测存在 */
        assertEquals("/api/user/checkin", SiteProfile.checkinActionPath(
                site("https://seekai.cc", "newapi")));
        assertEquals("/api/user/checkin", SiteProfile.checkinActionPath(
                site("https://kktoken.cc", "newapi")));
        assertEquals("/api/user/checkin", SiteProfile.checkinActionPath(
                site("https://api.justwoker.icu", "newapi")));
        /* fengwind 主站无签到端点 */
        assertEquals("", SiteProfile.checkinActionPath(
                site("https://api.fengwind.com", "login")));
    }

    /** 显式声明优先于一切推断。 */
    @Test public void declaredModeWins() throws Exception {
        JSONObject s = site("https://anyrouter.top", "newapi").put("checkinMode", "WEB_ONLY");
        assertEquals(SiteProfile.CheckinMode.WEB_ONLY, SiteProfile.checkinMode(s));
    }

    /** 非法声明不炸，回落到推断。 */
    @Test public void invalidDeclaredModeIgnored() throws Exception {
        JSONObject s = site("https://seekai.cc", "newapi").put("checkinMode", "NONSENSE");
        assertEquals(SiteProfile.CheckinMode.BUTTON, SiteProfile.checkinMode(s));
    }

    /** checkinType=web → 网页手动。 */
    @Test public void webOnlyFromCheckinType() throws Exception {
        assertEquals(SiteProfile.CheckinMode.WEB_ONLY,
                SiteProfile.checkinMode(site("https://example.com", "web")));
    }

    /* ================= 到账即已签 / 无提示 ================= */

    /** 用户要求：到账就置已签。重登类两模式都必须如此。 */
    @Test public void creditMarksChecked() {
        assertTrue(SiteProfile.marksCheckedOnCredit(SiteProfile.CheckinMode.RELOGIN));
        assertTrue(SiteProfile.marksCheckedOnCredit(SiteProfile.CheckinMode.RELOGIN_SILENT));
        assertFalse(SiteProfile.marksCheckedOnCredit(SiteProfile.CheckinMode.BUTTON));
        assertFalse(SiteProfile.marksCheckedOnCredit(SiteProfile.CheckinMode.WEB_ONLY));
    }

    /** 只有 RELOGIN_SILENT 成功时不打扰用户。 */
    @Test public void onlySilentModeIsSilent() {
        assertTrue(SiteProfile.silentOnSuccess(SiteProfile.CheckinMode.RELOGIN_SILENT));
        assertFalse(SiteProfile.silentOnSuccess(SiteProfile.CheckinMode.RELOGIN));
        assertFalse(SiteProfile.silentOnSuccess(SiteProfile.CheckinMode.BUTTON));
    }

    /* ============ 浏览器实测得到的事实（2026-10-17） ============ */

    /**
     * 福利站是主站的子站点，不是独立站点。
     * 实测：福利站所有接口都要求 Authorization: Bearer <welfare_token>，
     * 而 welfare_token 只能通过主站 SSO 换取。
     */
    @Test public void welfareIsSubSite() throws Exception {
        JSONObject s = new JSONObject().put("baseUrl", "https://api.fengwind.com")
                .put("checkinType", "web");
        assertFalse("未配置时不算福利站", SiteProfile.hasWelfare(s));
        /* 配置后形态应变为 WELFARE（签到/额度都在子站点） */
        JSONObject meta = new JSONObject().put("welfareUrl", "https://api-welfalre.fengwind.com");
        s.put("meta", meta);
        assertTrue(SiteProfile.hasWelfare(s));
        assertEquals(SiteProfile.Kind.WELFARE, SiteProfile.kindOf(s));
        assertEquals("https://api-welfalre.fengwind.com", SiteProfile.welfareUrl(s));
    }

    /** 福利站地址末尾斜杠要归一化，避免拼出 //api/me。 */
    @Test public void welfareUrlTrailingSlash() throws Exception {
        JSONObject s = new JSONObject().put("meta",
                new JSONObject().put("welfareUrl", "https://api-welfalre.fengwind.com///"));
        assertEquals("https://api-welfalre.fengwind.com", SiteProfile.welfareUrl(s));
    }

    /**
     * 实测：主站 /api/user/checkin 等签到接口全部 404 —— 主站没有签到。
     * 福利站的签到是 POST /api/checkin（GET 同路径是 404，说明只收 POST）。
     */
    @Test public void welfareCheckinEndpointIsPostOnly() {
        assertEquals("/api/checkin", SiteProfile.checkinActionPath(SiteProfile.Kind.WELFARE));
        assertEquals("/api/checkin/status", SiteProfile.checkinStatusPath(SiteProfile.Kind.WELFARE));
        /* 福利站签到必须带 X-Turnstile-Token */
        assertTrue(SiteProfile.checkinNeedsTurnstile(SiteProfile.Kind.WELFARE));
    }

    /** 实测：福利站额度在 /api/me 的 balance 与 /api/level 的 profile.usage_total_cost。 */
    @Test public void welfareBalanceFields() {
        java.util.List<String> bal =
                java.util.Arrays.asList(SiteProfile.balanceFields(SiteProfile.Kind.WELFARE));
        assertTrue("要认 balance", bal.contains("balance"));
        java.util.List<String> used =
                java.util.Arrays.asList(SiteProfile.usedFields(SiteProfile.Kind.WELFARE));
        assertTrue("要认 usage_total_cost", used.contains("usage_total_cost"));
    }

    /** 福利站没有 New API 的会话端点，必须走 /api/me 或 /api/level。 */
    @Test public void welfareHasNoNewApiSelf() {
        java.util.List<String> p =
                java.util.Arrays.asList(SiteProfile.selfPaths(SiteProfile.Kind.WELFARE));
        assertFalse("不能含 /api/user/self（实测 404）", p.contains("/api/user/self"));
        assertTrue(p.contains("/api/me"));
        assertTrue(p.contains("/api/level"));
    }

    /**
     * 回归锁：按钮型站点必须走「真发 POST」，不能只查状态。
     *
     * 旧实现 checkinLocked 的 BUTTON 分支只查 checkinStatus + todayBonus 就返回，
     * 从不 POST 签到动作端点 —— 于是 seekai / kktoken / justwoker 这一整类
     * 「点按钮签到」的站点永远签不上，表现就是用户说的「签到有问题」。
     *
     * 这个测试锁的是分派契约：mode=BUTTON 且 actionPath 非空 ⇒ 引擎必须发 POST。
     * 一旦有人把 BUTTON 改回「只查询」，这里立刻失败。
     */
    @Test public void buttonModeMustHaveActionEndpoint() throws Exception {
        JSONObject seekai = site("https://seekai.cc", "newapi");
        assertEquals(SiteProfile.CheckinMode.BUTTON, SiteProfile.checkinMode(seekai));
        String action = SiteProfile.checkinActionPath(seekai);
        assertFalse("按钮型站必须有签到动作端点，否则签不上", action.isEmpty());
        assertEquals("/api/user/checkin", action);

        /* 而「没有动作端点」的站不能是 BUTTON，否则引擎会发一个必然 404 的 POST。 */
        JSONObject agent = site("https://agentrouter.org", "login");
        assertNotEquals(SiteProfile.CheckinMode.BUTTON, SiteProfile.checkinMode(agent));
        assertEquals("", SiteProfile.checkinActionPath(agent));
    }

    /* ================= 主站地址推导（余额在这里） ================= */

    /**
     * 余额在主站 api.fengwind.com，不在捐赠站/福利站。
     *
     * 真机事故（2026-10-20）：站点记录的 baseUrl 填的是 api-donation.fengwind.com，
     * 代码却把主站端点 /api/v1/auth/me 发到 baseUrl 上 —— 捐赠站主机没有该接口，
     * 返回 401，于是额度永远读不到，只能退而用福利站的 xp_balance（XP 点数）顶替，
     * 界面显示 $254.56，而真实余额是 $38.79。
     *
     * 这个用例锁死「无论 baseUrl 是哪个子域，主站地址都必须推导到 api.fengwind.com」。
     */
    @Test public void mainSiteUrlAlwaysResolvesToMainHost() throws Exception {
        assertEquals("https://api.fengwind.com",
                SiteProfile.mainSiteUrl(site("https://api-welfalre.fengwind.com", null)));
        assertEquals("https://api.fengwind.com",
                SiteProfile.mainSiteUrl(site("https://api-welfare.fengwind.com", null)));
        /* 主站本身保持不变。 */
        assertEquals("https://api.fengwind.com",
                SiteProfile.mainSiteUrl(site("https://api.fengwind.com", null)));
    }

    /** 非 fengwind 系站点不受影响：原样返回自己的 baseUrl。 */
    @Test public void mainSiteUrlLeavesOtherSitesUntouched() throws Exception {
        assertEquals("https://agentrouter.org",
                SiteProfile.mainSiteUrl(site("https://agentrouter.org", null)));
        assertEquals("https://seekai.cc",
                SiteProfile.mainSiteUrl(site("https://seekai.cc", null)));
    }

    /** 末尾斜杠要归一化，否则拼出来的 URL 会出现双斜杠。 */
    @Test public void mainSiteUrlTrimsTrailingSlash() throws Exception {
        assertEquals("https://api.fengwind.com",
                SiteProfile.mainSiteUrl(site("https://api-welfalre.fengwind.com/", null)));
    }
}
