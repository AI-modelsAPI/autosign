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
        JSONObject tk = store.findToken(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = findSite(tk.optString("siteKey"));
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
        return out;
    }

    public JSONObject checkin(String key) throws Exception {
        JSONObject tk = store.findToken(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = findSite(tk.optString("siteKey"));
        if (!"manual".equals(site.optString("checkinType")))
            return new JSONObject().put("ok", true).put("skipped", true)
                    .put("message", "该站点登录即签到，无需单独签到");
        JSONObject r = call(site, tk.optString("token", null), "POST", "/api/user/checkin");
        store.appendLog(site.optString("key"), key, "checkin", "http=" + r.optInt("http"));
        return new JSONObject().put("ok", true).put("http", r.optInt("http")).put("data", r.opt("data"));
    }

    public JSONObject logs(String key, String category, int limit) throws Exception {
        JSONObject tk = store.findToken(key);
        if (tk == null) throw new Exception("账号不存在");
        JSONObject site = findSite(tk.optString("siteKey"));
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
        JSONArray tokens = store.tokens();
        for (int i = 0; i < tokens.length(); i++) {
            JSONObject tk = tokens.optJSONObject(i);
            if (tk == null || tk.optString("token", "").isEmpty()) continue;
            String key = tk.optString("key");
            try {
                JSONObject site = findSite(tk.optString("siteKey"));
                if ("manual".equals(site.optString("checkinType"))) {
                    JSONObject r = call(site, tk.optString("token"), "POST", "/api/user/checkin");
                    store.appendLog(site.optString("key"), key, "cron-checkin", "http=" + r.optInt("http"));
                } else {
                    JSONObject r = call(site, tk.optString("token"), "GET", "/api/user/self");
                    store.appendLog(site.optString("key"), key, "cron-login-refresh", "http=" + r.optInt("http"));
                }
            } catch (Exception e) {
                store.appendLog("?", key, "cron-error", e.getMessage());
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