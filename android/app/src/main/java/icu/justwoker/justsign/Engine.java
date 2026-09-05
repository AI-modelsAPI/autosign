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
 * Engine — 纯 HTTP 调度引擎（对齐 Node 版 client.js/server.js）：
 *   self      GET  /api/user/self
 *   status    GET  /api/status        （quota_per_unit / turnstile_site_key / price）
 *   logs      GET  /api/log/self      （找“签到”记录作为 lastBonus）
 *   checkin   POST /api/user/checkin  （manual 型走 WebView，见 OffscreenCheckin）
 * 网络：SOCKS5 代理优先（127.0.0.1:10808，v2ray 用户），失败自动直连降级（普通用户）。
 *
 * v0.1.8 修复（全量审计）：
 *   1. attempt() 吞掉了所有异常并返回 null → 上层只能报“均不可达”，看不到真实错误；
 *      现在记录 lastError 并在抛错时带出（含状态码/异常类型），且 Response 显式 close 防泄漏。
 *   2. call() 对 4xx/5xx 也返回，由业务判定；新增 http=429 的显式提示（站点限流，之前会被当成通用失败）。
 *   3. status() 把 turnstile_site_key / quota_per_unit 写入「站点 meta」而不是账号 —— 修复新建账号
 *      永远拿不到 siteKey、签到直接报「未配置 siteKey」的根因。
 *   4. dd() 兼容单层包裹（部分接口返回 {data:{...}} 而非 {data:{data:{}}}），原实现单层时返回空对象。
 *   5. 删除死代码 parseReward（v0.1.6 之后再无调用者）；未使用的私有 findSite 也移除。
 *   6. runAllOnce 原来对每个 manual 账号异步 fire-and-forget，Worker 会在签到完成前返回 →
 *      WorkManager 可能立刻回收进程导致后台签到全部失效。现在改为串行 + CountDownLatch 等待
 *      （每个账号最多 110s），Worker 生命周期覆盖真实签到过程。
 *   7. runAllOnce 跳过「今日已签」账号，避免每次调度重复跑 WebView 白耗电。
 *   8. checkinStatus 的 records 从 stats 内改为兼容 data.records 与 stats.records 两种结构。
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

    /** 代理优先，网络层失败时直连 fallback；HTTP 错误码不算失败（交业务判定） */
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

    /** 返回 {http, data}；网络层异常返回 null（触发直连 fallback），并记录 lastError */
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

    /** New API 响应包裹：优先 data.data，回退单层 data */
    private static JSONObject dd(JSONObject resp) {
        if (resp == null) return new JSONObject();
        JSONObject d = resp.optJSONObject("data");
        if (d == null) return new JSONObject();
        JSONObject inner = d.optJSONObject("data");
        return inner != null ? inner : d;
    }

    /** HTTP 码 → 可读原因（供 UI 提示） */
    private static String httpHint(int code) {
        if (code == 429) return "站点限流（429），请稍后再试";
        if (code == 401) return "授权已过期（401），请重新授权";
        if (code == 403) return "站点拒绝访问（403），可能触发人机验证";
        if (code >= 500) return "站点服务异常（" + code + "）";
        if (code == 0) return "网络不可达";
        return "HTTP " + code;
    }

    /* ================= 业务 ================= */

    public JSONObject status(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        final String sKey = site.optString("key", "");
        /* v0.2.0：先确保 token 新鲜（该站 JWT TTL 仅 15 分钟，不续期必然 401 变红） */
        String token = TokenKeeper.ensureFresh(ctx, store, site, key);
        if (token.isEmpty()) token = tk.optString("token", null);

        JSONObject self = call(site, token, "GET", "/api/user/self");
        int selfHttp = self.optInt("http");

        JSONObject stat;
        try { stat = call(site, token, "GET", "/api/status"); }
        catch (Exception e) { stat = new JSONObject(); }

        long unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT);
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;

        /* v0.1.8：Turnstile siteKey / quota 单位缓存到「站点」而非账号
         * （站点级属性，所有账号共享；否则新建账号必为空 → 签到报未配置 siteKey） */
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
        /* 今日消耗（v0.1.9）：/api/user/self 不返回今日字段（实测确认），
           改用 /api/data/self?start_timestamp=今日0点&end_timestamp=now 累加 quota */
        double todayUsed = -1;
        if (selfHttp == 200) {
            try { todayUsed = todayUsage(site, token, unit); } catch (Exception ignored) {}
        }
        if (todayUsed >= 0) out.put("todayUsed", Math.round(todayUsed * 100.0) / 100.0);
        store.appendLog(sKey, key, "status", "http=" + selfHttp);
        /* v0.2.0 操作日志：刷新结果（成功与失败都记） */
        if (selfHttp == 200) {
            store.opLog(sKey, key, "刷新", "ok", "额度已更新",
                    "可用 $" + Ui.usd(Math.round(quota / unit * 100.0) / 100.0)
                            + (todayUsed >= 0 ? (" · 今日消耗 $" + Ui.usd(todayUsed)) : ""), "user");
        } else {
            store.opLog(sKey, key, "刷新", "err", httpHint(selfHttp), "GET /api/user/self", "user");
        }

        /* 跨设备已签鉴别：官方签到状态接口 /api/user/checkin?month=YYYY-MM，
           不可用时回退当日日志探测。未授权时不必再查。 */
        if (selfHttp == 200) {
            JSONObject cs = null;
            try { cs = checkinStatus(key, unit); } catch (Exception ignored) {}
            if (cs == null) {
                JSONObject probe = null;
                try { probe = todayBonus(key); } catch (Exception ignored) {}
                if (probe != null) cs = new JSONObject().put("checked", true)
                        .put("rewardUSD", quotaToUSD(probe.optLong("quota", 0), unit));
            }
            if (cs != null && cs.optBoolean("checked")) {
                out.put("todayChecked", true);
                out.put("todayRewardUSD", cs.optDouble("rewardUSD", 0));
            } else out.put("todayChecked", false);
        } else out.put("todayChecked", false);
        return out;
    }

    /** 官方签到状态接口：GET /api/user/checkin?month=YYYY-MM
     *  返回 {checked, rewardUSD, checkedDate}；非 200 或结构不符返回 null */
    public JSONObject checkinStatus(String key, long unit) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(new java.util.Date());
        String tok = TokenKeeper.ensureFresh(ctx, store, site, key);
        if (tok.isEmpty()) tok = tk.optString("token", null);
        JSONObject r = call(site, tok, "GET",
                "/api/user/checkin?month=" + URLEncoder.encode(month, "UTF-8"));
        if (r.optInt("http") != 200) return null;
        JSONObject d = dd(r);
        JSONObject stats = d.optJSONObject("stats");
        if (stats == null) return null;
        String today = todayStr();
        boolean checked = stats.optBoolean("checked_in_today", false);
        double reward = 0;
        /* records 可能挂 stats 下，也可能挂 data 下（不同版本） */
        JSONArray recs = stats.optJSONArray("records");
        if (recs == null) recs = d.optJSONArray("records");
        if (recs != null) for (int i = 0; i < recs.length(); i++) {
            JSONObject o = recs.optJSONObject(i);
            if (o == null) continue;
            String date = o.optString("checkin_date", o.optString("date", ""));
            if (today.equals(date)) {
                double raw = o.optDouble("quota_awarded", o.optDouble("quota", 0));
                reward = raw >= 1000 ? Math.round(raw / (double) unit * 100.0) / 100.0 : raw;
                if (!checked) checked = true;   // 有当日记录即视为已签
                break;
            }
        }
        return new JSONObject().put("checked", checked)
                .put("rewardUSD", reward)
                .put("checkedDate", today);
    }

    /**
     * 签到（非 manual 型）。manual 型必须走 WebView（Turnstile），
     * 由 MainActivity / runAllOnce 调用 OffscreenCheckin，这里只回信号。
     */
    public JSONObject checkin(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        JSONObject site = store.siteOfAccount(key);
        if (tk == null || site == null) throw new Exception("账号或站点不存在");
        String token = tk.optString("token", null);
        if (token == null || token.isEmpty()) throw new Exception("账号未授权，请先完成 GitHub 授权");
        String type = site.optString("checkinType", "login");
        JSONObject out = new JSONObject();
        if ("manual".equals(type)) {
            out.put("ok", false)
               .put("needWebview", true)
               .put("message", "该站启用人机验证，需在后台签到窗口完成");
            return out;
        }
        /* 登录即签到型：无独立签到接口 —— 查当日「签到」记录，取奖励展示 */
        long unit = store.siteMetaLong(site.optString("key", ""), "quotaPerUnit", QUOTA_PER_UNIT_DEFAULT);
        JSONObject tb = null;
        try { tb = todayBonus(key); } catch (Exception ignored) {}
        out.put("ok", true).put("skipped", false).put("already", true);
        if (tb != null) {
            out.put("reward", quotaToUSD(tb.optLong("quota", 0), unit))
               .put("message", "登录即签到 · 今日奖励已到账");
        } else {
            JSONObject self = null;
            try { self = call(site, token, "GET", "/api/user/self"); } catch (Exception ignored) {}
            int code = self == null ? 0 : self.optInt("http");
            if (code != 200) {
                out.put("ok", false).put("already", false).put("message", httpHint(code));
                return out;
            }
            out.put("message", "登录即签到 · 每日额度自动发放（今日暂无签到记录）");
        }
        markChecked(key, out.optDouble("reward", 0));
        return out;
    }

    /** 今日消耗（v0.1.9）：GET /api/data/self?start_timestamp&end_timestamp 累加 quota → 美元。
     *  失败或无数据返回 -1（UI 显示为 "—"），0 条记录返回 0。 */
    private double todayUsage(JSONObject site, String token, long unit) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            long start = c.getTimeInMillis() / 1000L;
            long end = System.currentTimeMillis() / 1000L;
            JSONObject r = call(site, token, "GET",
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

    /** 原始 quota → 美元（日志记录里的 quota 是原始单位，如 12500000 = $25） */
    private static double quotaToUSD(long q, long unit) {
        if (q <= 0) return 0;
        if (unit <= 0) unit = QUOTA_PER_UNIT_DEFAULT;
        return q >= 1000 ? Math.round(q / (double) unit * 100.0) / 100.0 : q;
    }

    /** 账号写入当日签到状态（支撑「今日已签」徽章与按钮置灰，次日自动失效） */
    private void markChecked(String accountKey, double reward) {
        try {
            store.patchAccount(accountKey, new JSONObject().put("lastCheckin",
                    new JSONObject().put("date", todayStr())
                            .put("reward", reward)
                            .put("time", System.currentTimeMillis())));
        } catch (Exception ignored) {}
    }

    /** 今日「签到」奖励记录（logs 里最后一条含“签到”且时间为今天的记录） */
    public JSONObject todayBonus(String key) throws Exception {
        JSONObject lg = logs(key, "系统", 30);
        if (!lg.optBoolean("ok")) return null;
        JSONObject lb = lg.optJSONObject("lastBonus");
        if (lb == null || !lb.has("time")) return null;
        long t = parseTimeMs(lb.optString("time"));
        return (t > 0 && isToday(t)) ? lb : null;
    }

    /** 日志时间解析：兼容 unix 秒 / 毫秒 / ISO 字符串 */
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

    /* ================= 当日签到状态 ================= */

    /** 账号今日是否已签到（lastCheckin.date == 今天，次日自动失效） */
    public static boolean isCheckedToday(JSONObject acc) {
        if (acc == null) return false;
        JSONObject lc = acc.optJSONObject("lastCheckin");
        return lc != null && todayStr().equals(lc.optString("date", ""));
    }

    public JSONObject logs(String key, String category, int limit) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        if (limit <= 0 || limit > 200) limit = 20;
        String path = "/api/log/self?category=" + URLEncoder.encode(category, "UTF-8")
                + "&limit=" + limit + "&page=1";
        JSONObject r = call(site, tk.optString("token", null), "GET", path);
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

    public JSONObject proxyTest() throws Exception {
        JSONArray sites = store.config().optJSONArray("sites");
        if (sites == null || sites.length() == 0) throw new Exception("无站点");
        long t0 = System.currentTimeMillis();
        JSONObject r = call(sites.getJSONObject(0), null, "GET", "/api/status");
        return new JSONObject().put("ok", r.optInt("http") == 200)
                .put("http", r.optInt("http")).put("ms", System.currentTimeMillis() - t0);
    }

    /**
     * cron 等价：manual 站点走离屏 WebView 签到，login 站点刷新保活。
     * v0.1.8：串行 + 等待完成（Worker 生命周期必须覆盖签到全过程，否则进程被回收后台签到全废）。
     */
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
            boolean manual = "manual".equals(site.optString("checkinType"));
            for (int j = 0; j < accs.length(); j++) {
                JSONObject tk = accs.optJSONObject(j);
                if (tk == null || tk.optString("token", "").isEmpty()) continue;
                final String key = tk.optString("key");
                if (key.isEmpty()) continue;
                /* 今日已签则跳过（省电、避免重复跑 WebView） */
                if (isCheckedToday(tk)) {
                    store.appendLog(sKey, key, "cron-skip", "今日已签");
                    continue;
                }
                try {
                    if (manual) {
                        final CountDownLatch latch = new CountDownLatch(1);
                        final String[] ev = { "cron-checkin-fail" };
                        final String[] dt = { "未返回" };
                        OffscreenCheckin.run(ctx, sKey, key, 100, (ok, already, reward, msg) -> {
                            ev[0] = ok ? (already ? "cron-checkin-already" : "cron-checkin-ok") : "cron-checkin-fail";
                            dt[0] = ok ? (already ? (msg == null ? "今日已签" : msg) : ("奖励 $" + reward))
                                       : (msg == null ? "" : msg);
                            latch.countDown();
                        });
                        /* 等待签到真正跑完（离屏 WebView 看门狗 100s，这里给 110s 余量） */
                        if (!latch.await(110, TimeUnit.SECONDS)) { ev[0] = "cron-checkin-fail"; dt[0] = "等待超时"; }
                        store.appendLog(sKey, key, ev[0], dt[0]);
                        store.opLog(sKey, key, "定时签到",
                                ev[0].endsWith("fail") ? "err" : "ok", dt[0], "", "cron");
                    } else {
                        JSONObject r = call(site, tk.optString("token"), "GET", "/api/user/self");
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

    /* ================= 后台调度（WorkManager，12h 周期） ================= */

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
            /* daily / weekday：周期 24h，首次延迟到下一个指定时刻
               （WorkManager 无法精确定时，最小周期 15min；weekday 由 runAllOnce 前置判断跳过周末） */
            periodMin = 24 * 60L;
            initialDelayMin = minutesUntil(sch.optInt("hour", 8), sch.optInt("minute", 30));
        }
        if (periodMin < 15) periodMin = 15;

        PeriodicWorkRequest.Builder b = new PeriodicWorkRequest.Builder(
                CheckWorker.class, periodMin, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build());
        if (initialDelayMin > 0) b.setInitialDelay(initialDelayMin, TimeUnit.MINUTES);

        /* 配置可能变化 → REPLACE 让新周期立即生效 */
        wm.enqueueUniquePeriodicWork("justsign-check",
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, b.build());
    }

    /** 距离下一个 hh:mm 还有多少分钟（含跨天） */
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

    /** 定时任务执行前的日期判定（weekday 模式跳过周六日） */
    private boolean scheduleAllowsToday() {
        try {
            JSONObject sch = store.schedule();
            if (!"weekday".equals(sch.optString("mode", "daily"))) return true;
            int dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY;
        } catch (Exception e) { return true; }
    }
}