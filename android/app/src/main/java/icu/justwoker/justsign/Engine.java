package icu.justwoker.justsign;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Engine（v0.2.3）— 纯 HTTP 调度引擎：
 *   self      GET  /api/user/self
 *   status    GET  /api/status        （quota_per_unit / turnstile_site_key / price）
 *   logs      GET  /api/log/self      （找“签到”记录作为 lastBonus）
 *   checkin   POST /api/user/checkin  （newapi 型走 WebView，见 OffscreenCheckin）
 *
 * 【v0.2.3 架构重构（完全删除续期逻辑）】
 *   1. 彻底删除不可靠的 TokenKeeper 续期逻辑。
 *   2. 请求遇 401 或 token 为空时，直接调用 SilentAuth.exchangeSync 在纯后台
 *      自动跑一次 GitHub OAuth 换取新鲜 token（零弹窗、零感知），再重试业务请求。
 *   3. 刷新/签到流程先保证凭据有效再读取数据。
 */
public class Engine {
    public static final long QUOTA_PER_UNIT_DEFAULT = 500000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private final Store store;
    private final Context ctx;
    private final OkHttpClient plain = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    /** 最近一次网络失败原因（供错误提示带出真实原因） */
    private volatile String lastError = "";

    public Engine(Context c) { store = new Store(c); ctx = c.getApplicationContext(); }

    /* ================= HTTP ================= */

    private JSONObject call(JSONObject site, String token, String method, String path) throws Exception {
        JSONObject proxy = store.config().optJSONObject("proxy");
        boolean useProxy = proxy != null && proxy.optBoolean("enabled");
        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) throw new Exception("站点 baseUrl 为空");
        String url = base + path;

        lastError = "";
        JSONObject r = attempt(url, token, method, buildClient(useProxy, proxy));
        if (r == null && useProxy) r = attempt(url, token, method, plain);
        if (r == null) throw new Exception("网络请求失败（代理与直连均不可达）"
                + (lastError.isEmpty() ? "" : ": " + lastError));
        return r;
    }

    /**
     * 带 401 自动后台换凭据重试的请求（v0.2.3 核心）。
     * 遇到 401 时不报错，直接在后台通过 SilentAuth 换新 token，换完自动重试一次。
     *
     * v0.2.3 补强：一次刷新会连打 4 个接口，若每个都各自 OAuth 一遍必然把站点打到 429。
     * 这里的 tokenCache 让同一次刷新内的后续请求直接用已换好的新 token；
     * SilentAuth 内部也有同账号串行 + 8s 复用 + 失败 90s 冷却三重保护。
     */
    public JSONObject callWithAuth(JSONObject site, String accountKey, String method, String path) throws Exception {
        String token = cachedToken(accountKey);
        if (token.isEmpty()) {
            /* 先按 JWT exp 判定：过期/即将过期就直接换，不等 401 再补救（省一次往返） */
            token = SilentAuth.ensureToken(ctx, store, site, accountKey);
            if (!token.isEmpty()) cacheToken(accountKey, token);
        }

        JSONObject r = call(site, token, method, path);
        if (r.optInt("http") != 401) return r;

        /* 仍然 401（token 被服务端提前作废）→ 再换一次 */
        String freshToken = SilentAuth.exchangeSync(ctx, store, site, accountKey);
        if (!freshToken.isEmpty() && !freshToken.equals(token)) {
            cacheToken(accountKey, freshToken);
            JSONObject r2 = call(site, freshToken, method, path);
            try { r2.put("reauthed", true); } catch (Exception ignored) {}
            return r2;
        }
        return r;
    }

    /** 一轮刷新/签到开始时清缓存，保证读到最新 token */
    public void resetTokenCache() {
        synchronized (tokenCache) { tokenCache.clear(); }
    }

    /* 单次刷新周期内的 token 缓存（避免同一批请求各自触发 OAuth） */
    private final java.util.HashMap<String, String> tokenCache = new java.util.HashMap<>();

    private String cachedToken(String accountKey) {
        synchronized (tokenCache) {
            String t = tokenCache.get(accountKey);
            return t == null ? "" : t;
        }
    }

    private void cacheToken(String accountKey, String token) {
        synchronized (tokenCache) { tokenCache.put(accountKey, token); }
    }

    private OkHttpClient buildClient(boolean useProxy, JSONObject proxy) {
        if (!useProxy || proxy == null) return plain;
        try {
            return new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                    .proxy(new Proxy(Proxy.Type.SOCKS,
                            new InetSocketAddress(proxy.optString("host", "127.0.0.1"), proxy.optInt("port", 10808))))
                    .build();
        } catch (Exception e) { return plain; }
    }

    private JSONObject attempt(String url, String token, String method, OkHttpClient client) {
        Response resp = null;
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Accept", "application/json");
            if (token != null && !token.isEmpty()) rb.header("Authorization", "Bearer " + token);
            if ("POST".equalsIgnoreCase(method))
                rb.post(RequestBody.create("{}", MediaType.parse("application/json")));
            resp = client.newCall(rb.build()).execute();
            String txt = resp.body() != null ? resp.body().string() : "";
            JSONObject out = new JSONObject().put("http", resp.code());
            try { out.put("data", new JSONObject(txt)); }
            catch (Exception e) { out.put("data", new JSONObject()); }
            return out;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (" " + e.getMessage()));
            return null;
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject dd(JSONObject resp) {
        if (resp == null) return new JSONObject();
        JSONObject d = resp.optJSONObject("data");
        if (d == null) return new JSONObject();
        JSONObject inner = d.optJSONObject("data");
        return inner != null ? inner : d;
    }

    private static String httpHint(int code) {
        if (code == 429) return "站点限流（429），请稍后再试";
        if (code == 401) return "授权已过期（401），GitHub 会话失效需手动登录一次";
        if (code == 403) return "站点拒绝访问（403），可能触发人机验证";
        if (code >= 500) return "站点服务异常（" + code + "）";
        if (code == 0) return "网络不可达";
        return "HTTP " + code;
    }

    /* ================= 站点类型 ================= */

    public static String siteKind(JSONObject site) {
        String t = site == null ? "" : site.optString("checkinType", "login");
        if ("manual".equals(t) || "newapi".equals(t)) return "newapi";
        if ("web".equals(t)) return "web";
        return "login";
    }

    public static boolean isAutoCheckin(JSONObject site) { return "newapi".equals(siteKind(site)); }
    public static boolean isWebOnly(JSONObject site) { return "web".equals(siteKind(site)); }

    public static String kindLabel(JSONObject site) {
        switch (siteKind(site)) {
            case "newapi": return "每日签到";
            case "web":    return "网页手动";
            default:       return "登录即得";
        }
    }

    public static String kindLabelOf(String checkinType) {
        try { return kindLabel(new JSONObject().put("checkinType", checkinType == null ? "" : checkinType)); }
        catch (Exception e) { return "登录即得"; }
    }

    /* ================= 业务：刷新（读取三大额度 + 签到奖励） ================= */

    public JSONObject status(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        final String sKey = site.optString("key", "");

        /* 每轮刷新开头清一次缓存，读取本轮最新 token */
        resetTokenCache();

        /* 统一走 callWithAuth：过期先换、401 再换，全程后台无弹窗 */
        JSONObject self = callWithAuth(site, key, "GET", "/api/user/self");
        int selfHttp = self.optInt("http");

        JSONObject stat;
        try { stat = call(site, null, "GET", "/api/status"); }
        catch (Exception e) { stat = new JSONObject(); }

        long unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT);
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;

        if (!sKey.isEmpty()) {
            String tsk = dd(stat).optString("turnstile_site_key", "");
            if (!tsk.isEmpty()) store.putSiteMeta(sKey, "turnstileSiteKey", tsk);
            store.putSiteMeta(sKey, "quotaPerUnit", unit);
        }

        double quota = dd(self).optDouble("quota", 0);
        double used = dd(self).optDouble("used_quota", 0);
        String user = dd(self).optString("display_name", null);
        if (user == null || user.isEmpty()) user = dd(self).optString("username", null);

        JSONObject out = new JSONObject()
                .put("ok", selfHttp == 200)
                .put("account", key).put("site", site.optString("name"))
                .put("http", selfHttp)
                .put("authorized", selfHttp == 200)
                .put("availableUSD", Math.round(quota / unit * 100.0) / 100.0)
                .put("usedUSD", Math.round(used / unit * 100.0) / 100.0)
                .put("user", (user == null || user.isEmpty()) ? JSONObject.NULL : user);
        if (selfHttp != 200) out.put("message", httpHint(selfHttp));

        /* 今日消耗 */
        double todayUsed = -1;
        if (selfHttp == 200) {
            try { todayUsed = todayUsage(site, key, unit); } catch (Exception ignored) {}
        }
        if (todayUsed >= 0) out.put("todayUsed", Math.round(todayUsed * 100.0) / 100.0);
        store.appendLog(sKey, key, "status", "http=" + selfHttp);

        if (selfHttp == 200) {
            store.opLog(sKey, key, "刷新", "ok", "额度已更新",
                    "可用 $" + Ui.usd(Math.round(quota / unit * 100.0) / 100.0)
                            + (todayUsed >= 0 ? (" · 今日消耗 $" + Ui.usd(todayUsed)) : ""), "user");
        } else {
            store.opLog(sKey, key, "刷新", "err", httpHint(selfHttp), "GET /api/user/self", "user");
        }

        /* 签到状态与奖励检测 */
        if (selfHttp == 200) {
            JSONObject cs = null;
            try { cs = checkinStatus(key, unit); } catch (Exception ignored) {}
            if (cs == null) {
                JSONObject probe = null;
                try { probe = todayBonus(key); } catch (Exception ignored) {}
                if (probe != null) cs = new JSONObject().put("checked", true)
                        .put("rewardUSD", quotaToUSD(probe.optLong("quota", 0), unit))
                        .put("rewardKnown", true);
            }
            if (cs != null && cs.optBoolean("checked")) {
                out.put("todayChecked", true);
                out.put("todayRewardUSD", cs.optDouble("rewardUSD", 0));
                out.put("todayRewardKnown", cs.optBoolean("rewardKnown", false));
            } else {
                out.put("todayChecked", false);
                out.put("todayRewardKnown", false);
            }
        } else {
            out.put("todayChecked", false);
            out.put("todayRewardKnown", false);
        }
        return out;
    }

    public JSONObject checkinStatus(String key, long unit) throws Exception {
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(new java.util.Date());
        JSONObject r = callWithAuth(site, key, "GET",
                "/api/user/checkin?month=" + URLEncoder.encode(month, "UTF-8"));
        if (r.optInt("http") != 200) return null;
        JSONObject d = dd(r);
        JSONObject stats = d.optJSONObject("stats");
        if (stats == null) return null;
        String today = todayStr();
        boolean checked = stats.optBoolean("checked_in_today", false);
        double reward = 0;
        boolean rewardKnown = false;
        JSONArray recs = stats.optJSONArray("records");
        if (recs == null) recs = d.optJSONArray("records");
        if (recs != null) for (int i = 0; i < recs.length(); i++) {
            JSONObject o = recs.optJSONObject(i);
            if (o == null) continue;
            String date = o.optString("checkin_date", o.optString("date", ""));
            if (today.equals(date)) {
                double raw = o.optDouble("quota_awarded", o.optDouble("quota", 0));
                reward = raw >= 1000 ? Math.round(raw / (double) unit * 100.0) / 100.0 : raw;
                rewardKnown = true;
                if (!checked) checked = true;
                break;
            }
        }
        return new JSONObject().put("checked", checked)
                .put("rewardUSD", reward)
                .put("rewardKnown", rewardKnown)
                .put("checkedDate", today);
    }

    public JSONObject checkin(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        JSONObject site = store.siteOfAccount(key);
        if (tk == null || site == null) throw new Exception("账号或站点不存在");
        JSONObject out = new JSONObject();
        if (isAutoCheckin(site)) {
            out.put("ok", false)
               .put("needWebview", true)
               .put("message", "该站启用人机验证，需在后台签到窗口完成");
            return out;
        }
        if (isWebOnly(site)) {
            out.put("ok", false).put("webOnly", true).put("rewardKnown", false)
               .put("message", "该站不开放签到接口，请点站点名打开网页手动操作");
            return out;
        }
        long unit = store.siteMetaLong(site.optString("key", ""), "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT);

        JSONObject cs = null;
        try { cs = checkinStatus(key, unit); } catch (Exception ignored) {}
        if (cs != null && cs.optBoolean("checked")) {
            double rw = cs.optDouble("rewardUSD", 0);
            boolean known = cs.optBoolean("rewardKnown", false);
            out.put("ok", true).put("already", true)
               .put("reward", rw).put("rewardKnown", known)
               .put("message", known && rw > 0 ? "今日已签到" : "今日已签到（本站无奖励）");
            markChecked(key, rw, known);
            return out;
        }

        JSONObject tb = null;
        try { tb = todayBonus(key); } catch (Exception ignored) {}
        if (tb != null) {
            double rw = quotaToUSD(tb.optLong("quota", 0), unit);
            out.put("ok", true).put("already", true)
               .put("reward", rw).put("rewardKnown", true)
               .put("message", rw > 0 ? "登录即签到 · 今日奖励已到账" : "登录即签到 · 今日已记录");
            markChecked(key, rw, true);
            return out;
        }

        JSONObject self = null;
        try { self = callWithAuth(site, key, "GET", "/api/user/self"); } catch (Exception ignored) {}
        int code = self == null ? 0 : self.optInt("http");
        if (code != 200) {
            out.put("ok", false).put("already", false).put("rewardKnown", false)
               .put("message", httpHint(code));
            return out;
        }
        /* AgentRouter 一类站在 /api/user/self 里直接给 checked_in 布尔，
         * 这是「登录即签到」最可靠的确证信号，优先采信。 */
        JSONObject su = dd(self);
        if (su != null && su.has("checked_in")) {
            boolean ci = su.optBoolean("checked_in", false);
            out.put("ok", ci).put("already", ci).put("reward", 0).put("rewardKnown", false)
               .put("message", ci ? "登录即签到 · 站点已标记今日已签"
                                  : "站点显示今日未签到，请打开网页登录一次以触发发放");
            if (ci) markChecked(key, 0, false);
            return out;
        }
        out.put("ok", true).put("already", true).put("reward", 0).put("rewardKnown", false)
           .put("message", "登录即签到 · 已保活（该站无签到记录）");
        markChecked(key, 0, false);
        return out;
    }

    private double todayUsage(JSONObject site, String accountKey, long unit) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            long start = c.getTimeInMillis() / 1000L;
            long end = System.currentTimeMillis() / 1000L;
            JSONObject r = callWithAuth(site, accountKey, "GET",
                    "/api/data/self?start_timestamp=" + start + "&end_timestamp=" + end + "&default_time=hour");
            if (r.optInt("http") != 200) return -1;
            JSONArray list = null;
            JSONObject wrap = r.optJSONObject("data");
            if (wrap != null) list = wrap.optJSONArray("data");
            if (list == null) return -1;
            double sum = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) sum += o.optDouble("quota", 0);
            }
            if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
            return sum / unit;
        } catch (Exception e) { return -1; }
    }

    private static double quotaToUSD(long q, long unit) {
        if (q <= 0) return 0;
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        return q >= 1000 ? Math.round(q / (double) unit * 100.0) / 100.0 : q;
    }

    private void markChecked(String accountKey, double reward, boolean rewardKnown) {
        try {
            JSONObject lc = new JSONObject()
                    .put("date", todayStr())
                    .put("time", System.currentTimeMillis());
            if (rewardKnown) lc.put("reward", reward);
            store.patchAccount(accountKey, new JSONObject().put("lastCheckin", lc));
        } catch (Exception ignored) {}
    }

    public JSONObject todayBonus(String key) throws Exception {
        JSONObject lg = logs(key, "系统", 30);
        if (!lg.optBoolean("ok")) return null;
        JSONObject lb = lg.optJSONObject("lastBonus");
        if (lb == null || !lb.has("time")) return null;
        long t = parseTimeMs(lb.optString("time"));
        return (t > 0 && isToday(t)) ? lb : null;
    }

    private static long parseTimeMs(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            long v = Long.parseLong(s.trim());
            if (v > 100000000000L) return v;
            if (v > 1000000000L) return v * 1000L;
        } catch (Exception ignored) {}
        String[] pats = { "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss" };
        for (String p : pats) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                java.util.Date d = f.parse(s.length() > 19 ? s.substring(0, 19) : s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    public static boolean isToday(long ms) {
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.setTimeInMillis(ms);
        java.util.Calendar b = java.util.Calendar.getInstance();
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    public static String todayStr() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    public static boolean isCheckedToday(JSONObject acc) {
        if (acc == null) return false;
        JSONObject lc = acc.optJSONObject("lastCheckin");
        return lc != null && todayStr().equals(lc.optString("date", ""));
    }

    public JSONObject logs(String key, String category, int limit) throws Exception {
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (limit <= 0 || limit > 200) limit = 20;
        String path = "/api/log/self?category=" + URLEncoder.encode(category, "UTF-8")
                + "&limit=" + limit + "&page=1";
        JSONObject r = callWithAuth(site, key, "GET", path);
        int code = r.optInt("http");
        JSONObject out = new JSONObject().put("ok", code == 200).put("http", code);
        if (code != 200) out.put("message", httpHint(code));
        JSONArray rows = new JSONArray();
        JSONObject lastBonus = null;
        if (code == 200) {
            JSONObject d = dd(r);
            JSONArray list = d.optJSONArray("items");
            if (list == null) list = d.optJSONArray("list");
            if (list == null) list = d.optJSONArray("data");
            if (list == null) {
                JSONObject wrap = r.optJSONObject("data");
                if (wrap != null) list = wrap.optJSONArray("data");
            }
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String text = o.optString("description", o.optString("content", ""));
                JSONObject row = new JSONObject()
                        .put("time", o.optString("created_at", o.optString("time", "")))
                        .put("category", o.optString("category", category))
                        .put("text", text)
                        .put("quota", o.optLong("quota", 0));
                rows.put(row);
                if (text.contains("签到")) lastBonus = row;
            }
        }
        out.put("rows", rows);
        out.put("lastBonus", lastBonus == null ? JSONObject.NULL : lastBonus);
        return out;
    }

    public void runAllOnce() {
        if (!scheduleAllowsToday()) {
            store.opLog("", "", "定时签到", "info", "今日跳过（仅工作日执行）", "", "cron");
            return;
        }
        JSONArray sites = store.config().optJSONArray("sites");
        if (sites == null) return;
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            final String sKey = site.optString("key", "");
            JSONArray accs = site.optJSONArray("accounts");
            if (accs == null) continue;
            boolean auto = isAutoCheckin(site);
            boolean webOnly = isWebOnly(site);
            for (int j = 0; j < accs.length(); j++) {
                JSONObject tk = accs.optJSONObject(j);
                if (tk == null) continue;
                final String key = tk.optString("key");
                if (key.isEmpty()) continue;
                if (isCheckedToday(tk)) {
                    store.appendLog(sKey, key, "cron-skip", "今日已签");
                    continue;
                }
                try {
                    if (webOnly) {
                        store.appendLog(sKey, key, "cron-skip", "网页手动型站点，需人工操作");
                        store.opLog(sKey, key, "定时签到", "info",
                                "跳过（该站需网页手动签到）", "", "cron");
                    } else if (auto) {
                        final CountDownLatch latch = new CountDownLatch(1);
                        final String[] ev = { "cron-checkin-fail" };
                        final String[] dt = { "未返回" };
                        OffscreenCheckin.run(ctx, sKey, key, 100, (ok, already, reward, rewardKnown, msg) -> {
                            ev[0] = ok ? (already ? "cron-checkin-already" : "cron-checkin-ok") : "cron-checkin-fail";
                            if (!ok) dt[0] = msg == null ? "" : msg;
                            else if (already) dt[0] = (msg == null || msg.isEmpty()) ? "今日已签" : msg;
                            else dt[0] = rewardKnown ? ("奖励 $" + Ui.usd(reward)) : "签到成功";
                            latch.countDown();
                        });
                        if (!latch.await(110, TimeUnit.SECONDS)) { ev[0] = "cron-checkin-fail"; dt[0] = "等待超时"; }
                        store.appendLog(sKey, key, ev[0], dt[0]);
                        store.opLog(sKey, key, "定时签到",
                                ev[0].endsWith("fail") ? "err" : "ok", dt[0], "", "cron");
                    } else {
                        JSONObject r = callWithAuth(site, key, "GET", "/api/user/self");
                        store.appendLog(sKey, key, "cron-login-refresh", "http=" + r.optInt("http"));
                        store.opLog(sKey, key, "定时刷新",
                                r.optInt("http") == 200 ? "ok" : "err",
                                r.optInt("http") == 200 ? "登录保活成功" : httpHint(r.optInt("http")), "", "cron");
                    }
                } catch (Exception e) {
                    store.appendLog(sKey, key, "cron-error", String.valueOf(e.getMessage()));
                    store.opLog(sKey, key, "定时任务", "err", "执行异常", String.valueOf(e.getMessage()), "cron");
                } catch (Throwable t) {
                    store.appendLog(sKey, key, "cron-error", "fatal: " + t);
                    store.opLog(sKey, key, "定时任务", "err", "严重异常", String.valueOf(t), "cron");
                }
            }
        }
    }

    public static class CheckWorker extends Worker {
        public CheckWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
        @NonNull @Override public Result doWork() {
            try { new Engine(getApplicationContext()).runAllOnce(); }
            catch (Throwable t) { return Result.retry(); }
            return Result.success();
        }
    }

    public static void schedule(Context c) {
        JSONObject sch;
        try { sch = new Store(c).schedule(); } catch (Exception e) { sch = new JSONObject(); }
        boolean enabled = sch.optBoolean("enabled", true);
        WorkManager wm = WorkManager.getInstance(c);
        if (!enabled) { wm.cancelUniqueWork("justsign-check"); return; }

        String mode = sch.optString("mode", "daily");
        long periodMin;
        long initialDelayMin = 0;
        if ("interval".equals(mode)) {
            int h = Math.max(1, Math.min(24, sch.optInt("intervalHours", 12)));
            periodMin = h * 60L;
        } else {
            periodMin = 24 * 60L;
            initialDelayMin = minutesUntil(sch.optInt("hour", 8), sch.optInt("minute", 30));
        }
        if (periodMin < 15) periodMin = 15;

        PeriodicWorkRequest.Builder b = new PeriodicWorkRequest.Builder(
                CheckWorker.class, periodMin, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build());
        if (initialDelayMin > 0) b.setInitialDelay(initialDelayMin, TimeUnit.MINUTES);

        wm.enqueueUniquePeriodicWork("justsign-check",
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, b.build());
    }

    private static long minutesUntil(int hour, int minute) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.set(java.util.Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour)));
        t.set(java.util.Calendar.MINUTE, Math.max(0, Math.min(59, minute)));
        t.set(java.util.Calendar.SECOND, 0);
        t.set(java.util.Calendar.MILLISECOND, 0);
        if (!t.after(now)) t.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long diff = (t.getTimeInMillis() - now.getTimeInMillis()) / 60000L;
        return Math.max(1, diff);
    }

    private boolean scheduleAllowsToday() {
        try {
            JSONObject sch = store.schedule();
            if (!"weekday".equals(sch.optString("mode", "daily"))) return true;
            int dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY;
        } catch (Exception e) { return true; }
    }
}