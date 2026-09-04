package icu.justwoker.justsign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SharedPreferences 存取：config（含 sites 站点数组，每站点含 accounts 账号数组）/ 运行日志
 * 数据模型（两级）：site{ key,name,baseUrl,checkinType } → account{ key,alias,siteKey,token,... }
 * 首次运行自动迁移旧 tokens 数组。
 */
public class Store {
    private static final String SP = "justsign";
    private final SharedPreferences sp;

    public Store(Context c) { sp = c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE); }

    /* ---------- config + sites + accounts ---------- */
    public JSONObject config() {
        try {
            String raw = sp.getString("config", "");
            JSONObject cfg = raw.isEmpty() ? defaultConfig() : new JSONObject(raw);
            migrate(cfg);
            return cfg;
        } catch (Exception e) { return defaultConfig(); }
    }
    public void saveConfig(JSONObject c) { sp.edit().putString("config", c.toString()).apply(); }

    /** 旧版 tokens 数组 → sites[].accounts 迁移（一次性） */
    private void migrate(JSONObject cfg) {
        try {
            if (!cfg.has("sites")) cfg.put("sites", defaultConfig().optJSONArray("sites"));
            if (!sp.getBoolean("migrated_v2", false)) {
                JSONArray old = new JSONArray(sp.getString("tokens", "[]"));
                for (int i = 0; i < old.length(); i++) {
                    JSONObject tk = old.optJSONObject(i);
                    if (tk == null) continue;
                    String siteKey = tk.optString("siteKey", "justworker");
                    JSONObject site = findSiteObj(cfg, siteKey);
                    if (site == null) continue;
                    JSONArray accs = site.optJSONArray("accounts");
                    if (accs == null) { accs = new JSONArray(); site.put("accounts", accs); }
                    // 去重
                    boolean dup = false;
                    for (int j = 0; j < accs.length(); j++) {
                        JSONObject a = accs.optJSONObject(j);
                        if (a != null && tk.optString("key").equals(a.optString("key"))) { dup = true; break; }
                    }
                    if (!dup) accs.put(tk);
                }
                sp.edit().putBoolean("migrated_v2", true).putString("config", cfg.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject findSiteObj(JSONObject cfg, String siteKey) {
        try {
            JSONArray sites = cfg.getJSONArray("sites");
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s != null && siteKey.equals(s.optString("key"))) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /* ---------- 站点操作 ---------- */
    public JSONArray sites() { return config().optJSONArray("sites"); }

    public void upsertSite(JSONObject site) {
        try {
            JSONObject cfg = config();
            JSONArray arr = cfg.optJSONArray("sites");
            if (arr == null) arr = new JSONArray();
            JSONArray out = new JSONArray();
            String key = site.optString("key");
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && key.equals(s.optString("key"))) { out.put(site); found = true; }
                else if (s != null) out.put(s);
            }
            if (!found) out.put(site);
            cfg.put("sites", out);
            saveConfig(cfg);
        } catch (Exception ignored) {}
    }

    public void removeSite(String siteKey) {
        try {
            JSONObject cfg = config();
            JSONArray arr = cfg.optJSONArray("sites");
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && !siteKey.equals(s.optString("key"))) out.put(s);
            }
            cfg.put("sites", out);
            saveConfig(cfg);
        } catch (Exception ignored) {}
    }

    /** 由 baseUrl 自动生成站点 key（小写、去协议/特殊字符） */
    public static String siteKeyOf(String baseUrl) {
        String k = baseUrl == null ? "site" : baseUrl.toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return k.isEmpty() ? "site" : k;
    }

    /* ---------- 账号操作（账号挂站点之下） ---------- */
    public JSONObject findSite(String siteKey) { return findSiteObj(config(), siteKey); }

    public JSONObject findAccount(String key) {
        JSONArray sites = config().optJSONArray("sites");
        if (sites != null) for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            JSONArray accs = s.optJSONArray("accounts");
            if (accs == null) continue;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject a = accs.optJSONObject(j);
                if (a != null && key.equals(a.optString("key"))) return a;
            }
        }
        return null;
    }

    /** 找到账号所属站点（含 site 引用） */
    public JSONObject siteOfAccount(String key) {
        JSONArray sites = config().optJSONArray("sites");
        if (sites != null) for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            JSONArray accs = s.optJSONArray("accounts");
            if (accs == null) continue;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject a = accs.optJSONObject(j);
                if (a != null && key.equals(a.optString("key"))) return s;
            }
        }
        return null;
    }

    public void upsertAccount(String siteKey, JSONObject acc) {
        try {
            JSONObject cfg = config();
            JSONArray sites = cfg.optJSONArray("sites");
            if (sites == null) return;
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null || !siteKey.equals(s.optString("key"))) continue;
                JSONArray accs = s.optJSONArray("accounts");
                if (accs == null) accs = new JSONArray();
                JSONArray out = new JSONArray();
                String key = acc.optString("key");
                boolean found = false;
                for (int j = 0; j < accs.length(); j++) {
                    JSONObject a = accs.optJSONObject(j);
                    if (a != null && key.equals(a.optString("key"))) { out.put(acc); found = true; }
                    else if (a != null) out.put(a);
                }
                if (!found) out.put(acc);
                s.put("accounts", out);
                break;
            }
            saveConfig(cfg);
        } catch (Exception ignored) {}
    }

    public void removeAccount(String key) {
        try {
            JSONObject cfg = config();
            JSONArray sites = cfg.optJSONArray("sites");
            if (sites == null) return;
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s == null) continue;
                JSONArray accs = s.optJSONArray("accounts");
                if (accs == null) continue;
                JSONArray out = new JSONArray();
                for (int j = 0; j < accs.length(); j++) {
                    JSONObject a = accs.optJSONObject(j);
                    if (a != null && !key.equals(a.optString("key"))) out.put(a);
                }
                s.put("accounts", out);
            }
            saveConfig(cfg);
        } catch (Exception ignored) {}
    }

    /* ---------- 默认配置 ---------- */
    public static JSONObject defaultConfig() {
        try {
            JSONObject proxy = new JSONObject().put("enabled", true).put("type", "socks5")
                    .put("host", "127.0.0.1").put("port", 10808);
            JSONObject schedule = new JSONObject().put("enabled", true).put("cron", "0 3 * * *");
            JSONArray sites = new JSONArray(); // 站点由用户手动添加，不预置
            return new JSONObject().put("proxy", proxy).put("schedule", schedule).put("sites", sites);
        } catch (Exception e) { return new JSONObject(); }
    }

    /* ---------- 运行日志 ---------- */
    public JSONArray logs() {
        try { return new JSONArray(sp.getString("logs", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public synchronized void appendLog(String site, String account, String event, String detail) {
        try {
            JSONArray a = logs();
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .format(new java.util.Date());
            JSONObject e = new JSONObject().put("time", now).put("site", site)
                    .put("account", account).put("event", event);
            if (detail != null) e.put("detail", detail);
            a.put(e);
            while (a.length() > 2000) a.remove(0);
            sp.edit().putString("logs", a.toString()).apply();
        } catch (Exception ignored) {}
    }
}