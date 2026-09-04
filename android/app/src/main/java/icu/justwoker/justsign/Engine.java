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
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Engine — 纯 HTTP 调度引擎（对齐 Node 版 client.js/server.js）：
 *   self      GET  /api/user/self
 *   status    GET  /api/status        （quota_per_unit / price）
 *   logs      GET  /api/log/self      （找“签到”记录作为 lastBonus）
 *   checkin   POST /api/user/checkin  （仅 manual 型站点）
 * 网络：SOCKS5 代理优先（127.0.0.1:10808，v2ray 用户），失败自动直连降级（普通用户）。
 */
public class Engine {
    public static final long QUOTA_PER_UNIT_DEFAULT = 500000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private final Store store;
    private final OkHttpClient plain = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    public Engine(Context c) { store = new Store(c); }

    /* ================= HTTP ================= */

    /** 代理优先，IOException 时直连 fallback */
    private JSONObject call(JSONObject site, String token, String method, String path) throws Exception {
        JSONObject proxy = store.config().optJSONObject("proxy");
        boolean useProxy = proxy != null && proxy.optBoolean("enabled");
        String url = site.optString("baseUrl").replaceAll("/+$", "") + path;

        JSONObject r = attempt(url, token, method, buildClient(useProxy, proxy));
        if (r == null && useProxy) r = attempt(url, token, method, plain);
        if (r == null) throw new Exception("网络请求失败（代理与直连均不可达）");
        return r;
    }

    private OkHttpClient buildClient(boolean useProxy, JSONObject proxy) {
        if (!useProxy) return plain;
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .proxy(new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(proxy.optString("host", "127.0.0.1"), proxy.optInt("port", 10808))))
                .build();
    }

    /** 返回 {http, data}；网络层异常返回 null（触发直连 fallback） */
    private JSONObject attempt(String url, String token, String method, OkHttpClient client) {
        try {
            Request.Builder rb = new Request.Builder().url(url)
                    .header("User-Agent", UA).header("Accept", "application/json");
            if (token != null && !token.isEmpty()) rb.header("Authorization", "Bearer " + token);
            if ("POST".equalsIgnoreCase(method))
                rb.post(RequestBody.create("{}", MediaType.parse("application/json")));
            Response resp = client.newCall(rb.build()).execute();
            String txt = resp.body() != null ? resp.body().string() : "";
            JSONObject out = new JSONObject().put("http", resp.code());
            try { out.put("data", new JSONObject(txt)); }
            catch (Exception e) { out.put("data", new JSONObject()); }
            return out;
        } catch (Exception e) { return null; }
    }

    /** resp.data.data（New API 响应包裹） */
    private static JSONObject dd(JSONObject resp) {
        try { return resp.getJSONObject("data").getJSONObject("data"); } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject findSite(String key) throws Exception {
        JSONArray sites = store.config().optJSONArray("sites");
        if (sites != null) for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s != null && key.equals(s.optString("key"))) return s;
        }
        throw new Exception("站点不存在: " + key);
    }

    /* ================= 业务 ================= */

    public JSONObject status(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        String token = tk.optString("token", null);

        JSONObject self = call(site, token, "GET", "/api/user/self");
        JSONObject stat = call(site, token, "GET", "/api/status");
        long unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT);
        double quota = dd(self).optDouble("quota", 0);
        double used = dd(self).optDouble("used_quota", 0);
        String user = dd(self).optString("display_name", null);
        if (user == null) user = dd(self).optString("username", null);

        JSONObject out = new JSONObject()
                .put("ok", true)
                .put("account", key).put("site", site.optString("name"))
                .put("http", self.optInt("http"))
                .put("authorized", self.optInt("http") == 200)
                .put("availableUSD", Math.round(quota / unit * 100.0) / 100.0)
                .put("usedUSD", Math.round(used / unit * 100.0) / 100.0)
                .put("user", user == null ? JSONObject.NULL : user);
        if (dd(self).has("today_used_quota"))
            out.put("todayUsed", Math.round(dd(self).optDouble("today_used_quota") / unit * 10000.0) / 10000.0);
        store.appendLog(site.optString("key"), key, "status", "http=" + self.optInt("http"));
        /* 跨设备已签鉴别：服务器日志里今天若有「签到」记录，无论哪台设备签的，本地同步为已签（v0.1.3.1） */
        JSONObject probe = null;
        try { probe = todayBonus(key); } catch (Exception ignored) {}
        if (probe != null) {
            out.put("todayChecked", true);
            out.put("todayRewardUSD", quotaToUSD(probe.optLong("quota", 0), unit));
        } else {
            out.put("todayChecked", false);
        }
        return out;
    }

    public JSONObject checkin(String key) throws Exception {
        JSONObject tk = store.findAccount(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = store.siteOfAccount(key);
        if (site == null) throw new Exception("站点不存在");
        String token = tk.optString("token", null);
        String type = site.optString("checkinType", "login");
        JSONObject out = new JSONObject();

        if ("manual".equals(type)) {
            /* 手动签到型：真实 POST /api/user/checkin 领取奖励 */
            JSONObject r = call(site, token, "POST", "/api/user/checkin");
            int http = r.optInt("http");
            JSONObject body = r.optJSONObject("data");
            int code = body == null ? -1 : body.optInt("code", -1);
            String message = body == null ? "" : body.optString("message", "");
            boolean already = message.contains("已签") || message.contains("重复")
                    || message.contains("已领") || message.toLowerCase().contains("already");
            double reward = 0;
            boolean success = http == 200 && !already && (code == 200 || body == null || body.optBoolean("success", code == -1 && body.has("data")));
            if (success) reward = parseReward(body, dd(r));
            store.appendLog(site.optString("key"), key, "checkin",
                    "http=" + http + " code=" + code + (success ? " reward=" + reward : "") + (already ? " already" : ""));
            out.put("ok", true).put("http", http).put("already", already && !success);
            if (success) {
                out.put("reward", reward);
                markChecked(tk, reward);
            } else if (already) {
                /* 已签过（含在其他设备签的）：从服务器日志取今日奖励，quota 需原始单位→美元换算 */
                JSONObject tb = null;
                try { tb = todayBonus(key); } catch (Exception ignored) {}
                if (tb != null) {
                    JSONObject stat = null;
                    long unit = QUOTA_PER_UNIT_DEFAULT;
                    try { stat = call(site, token, "GET", "/api/status"); unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT); } catch (Exception ignored) {}
                    out.put("reward", quotaToUSD(tb.optLong("quota", 0), unit));
                }
                out.put("message", "今日已签到");
                markChecked(tk, out.optDouble("reward", 0));
            } else {
                out.put("message", message.isEmpty() ? "签到失败（http " + http + "）" : message);
            }
            return out;
        }

        /* 登录即签到型：无独立签到接口 —— 查当日「签到」记录，取奖励展示 */
        JSONObject tb = null;
        try { tb = todayBonus(key); } catch (Exception ignored) {}
        out.put("ok", true).put("skipped", false).put("already", true);
        if (tb != null) {
            long unit = QUOTA_PER_UNIT_DEFAULT;
            try { JSONObject stat = call(site, token, "GET", "/api/status"); unit = dd(stat).optLong("quota_per_unit", QUOTA_PER_UNIT_DEFAULT); } catch (Exception ignored) {}
            out.put("reward", quotaToUSD(tb.optLong("quota", 0), unit))
               .put("message", "登录即签到 · 今日奖励已到账");
        } else {
            try { call(site, token, "GET", "/api/user/self"); } catch (Exception ignored) {}
            out.put("message", "登录即签到 · 每日额度自动发放（今日暂无签到记录）");
        }
        markChecked(tk, out.optDouble("reward", 0));
        return out;
    }

    /** 从签到响应解析奖励（美元）：兼容 {data:{quota}} 单层与 {data:{data:{quota}}} 双层包裹 */
    private static double parseReward(JSONObject body, JSONObject d) {
        double unit = QUOTA_PER_UNIT_DEFAULT;
        JSONObject cands[] = {d, body == null ? null : body.optJSONObject("data")};
        for (JSONObject o : cands) {
            if (o == null || !o.has("quota")) continue;
            double q = o.optDouble("quota", 0);
            if (q <= 0) continue;
            /* 原始 quota 单位（如 12500000 = $25）折算；小于 1000 视为已是美元/积分数 */
            return q >= 1000 ? Math.round(q / unit * 100.0) / 100.0 : q;
        }
        return 0;
    }

    /** 原始 quota → 美元（日志记录里的 quota 是原始单位，如 12500000 = $25） */
    private static double quotaToUSD(long q, long unit) {
        if (q <= 0) return 0;
        return q >= 1000 ? Math.round(q / (double) unit * 100.0) / 100.0 : q;
    }

    /** 账号写入当日签到状态（支撑「今日已签」徽章与按钮置灰，次日自动失效） */
    private void markChecked(JSONObject tk, double reward) {
        try {
            String key = tk.optString("key");
            JSONObject rec = store.findAccount(key);
            JSONObject site = store.siteOfAccount(key);
            if (rec == null || site == null) return;
            rec.put("lastCheckin", new JSONObject()
                    .put("date", todayStr())
                    .put("reward", reward)
                    .put("time", System.currentTimeMillis()));
            store.upsertAccount(site.optString("key"), rec);
        } catch (Exception ignored) {}
    }

    /** 今日「签到」奖励记录（logs 里最后一条含“签到”且时间为今天的记录） */
    public JSONObject todayBonus(String key) throws Exception {
        JSONObject lg = logs(key, "系统", 30);
        if (!lg.optBoolean("ok")) return null;
        JSONObject lb = lg.optJSONObject("lastBonus");
        if (lb == null || lb == JSONObject.NULL || !lb.has("time")) return null;
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
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            return f.parse(s).getTime();
        } catch (Exception ignored) {}
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

    /* ================= 当日签到状态（v0.1.3） ================= */

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
        String path = "/api/log/self?category=" + URLEncoder.encode(category, "UTF-8")
                + "&limit=" + limit + "&page=1";
        JSONObject r = call(site, tk.optString("token", null), "GET", path);
        JSONObject out = new JSONObject().put("ok", true).put("http", r.optInt("http"));
        JSONArray rows = new JSONArray();
        JSONObject lastBonus = null;
        if (r.optInt("http") == 200) {
            JSONArray list = null;
            JSONObject d = dd(r);
            if (d.has("items")) list = d.optJSONArray("items");
            else if (d.has("list")) list = d.optJSONArray("list");
            if (list == null) {
                try { list = r.getJSONObject("data").getJSONArray("data"); } catch (Exception ignored) {}
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

    /** cron 等价：manual 站点签到，login 站点刷新保活 */
    public void runAllOnce() {
        JSONArray sites = store.config().optJSONArray("sites");
        if (sites == null) return;
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            JSONArray accs = site.optJSONArray("accounts");
            if (accs == null) continue;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject tk = accs.optJSONObject(j);
                if (tk == null || tk.optString("token", "").isEmpty()) continue;
                String key = tk.optString("key");
                try {
                    if ("manual".equals(site.optString("checkinType"))) {
                        JSONObject r = call(site, tk.optString("token"), "POST", "/api/user/checkin");
                        store.appendLog(site.optString("key"), key, "cron-checkin", "http=" + r.optInt("http"));
                    } else {
                        JSONObject r = call(site, tk.optString("token"), "GET", "/api/user/self");
                        store.appendLog(site.optString("key"), key, "cron-login-refresh", "http=" + r.optInt("http"));
                    }
                } catch (Exception e) {
                    store.appendLog(site.optString("key"), key, "cron-error", e.getMessage());
                }
            }
        }
    }

    /* ================= 后台调度（WorkManager，12h 周期） ================= */

    public static class CheckWorker extends Worker {
        public CheckWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
        @NonNull @Override public Result doWork() {
            try { new Engine(getApplicationContext()).runAllOnce(); } catch (Exception ignored) {}
            return Result.success();
        }
    }

    public static void schedule(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(CheckWorker.class, 12, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("justsign-check",
                ExistingPeriodicWorkPolicy.KEEP, req);
    }
}