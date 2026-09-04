package icu.justwoker.justsign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SharedPreferences 存取：config（含 sites 站点数组，每站点含 accounts 账号数组）/ 运行日志
 * 数据模型（两级）：site{ key,name,baseUrl,checkinType,meta{} } → account{ key,alias,siteKey,token,... }
 * 首次运行自动迁移旧 tokens 数组。
 *
 * v0.1.8 并发安全：所有 config 读改写走静态锁 LOCK。
 *   原因：每处 new Store(ctx) 都是独立实例（实例锁无效），而 refreshSite 会并发刷 N 个账号、
 *   OffscreenCheckin 回调也会写库 —— 无锁时 read-modify-write 互相覆盖会丢账号/丢 token。
 *   写入统一用 commit()（同步落盘），避免进程被回收时 apply() 尚未刷盘导致数据丢失。
 */
public class Store {
    private static final String SP = "justsign";
    /** 全进程共享写锁：Store 是多实例的，必须用静态锁 */
    private static final Object LOCK = new Object();

    private final SharedPreferences sp;

    public Store(Context c) { sp = c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE); }

    /* ---------- config + sites + accounts ---------- */
    public JSONObject config() {
        synchronized (LOCK) {
            try {
                String raw = sp.getString("config", "");
                JSONObject cfg = raw.isEmpty() ? defaultConfig() : new JSONObject(raw);
                migrateLocked(cfg);
                return cfg;
            } catch (Exception e) { return defaultConfig(); }
        }
    }

    public void saveConfig(JSONObject c) {
        synchronized (LOCK) { saveConfigLocked(c); }
    }

    private void saveConfigLocked(JSONObject c) {
        if (c == null) return;
        sp.edit().putString("config", c.toString()).commit();
    }

    /** 旧版 tokens 数组 → sites[].accounts 迁移（一次性）；调用方必须已持有 LOCK */
    private void migrateLocked(JSONObject cfg) {
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
                sp.edit().putBoolean("migrated_v2", true).putString("config", cfg.toString()).commit();
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject findSiteObj(JSONObject cfg, String siteKey) {
        if (cfg == null || siteKey == null) return null;
        try {
            JSONArray sites = cfg.optJSONArray("sites");
            if (sites == null) return null;
            for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s != null && siteKey.equals(s.optString("key"))) return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /* ---------- 站点操作 ---------- */
    public JSONArray sites() {
        JSONArray a = config().optJSONArray("sites");
        return a == null ? new JSONArray() : a;
    }

    public void upsertSite(JSONObject site) {
        if (site == null) return;
        synchronized (LOCK) {
            try {
                JSONObject cfg = config();
                JSONArray arr = cfg.optJSONArray("sites");
                if (arr == null) arr = new JSONArray();
                JSONArray out = new JSONArray();
                String key = site.optString("key");
                boolean found = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) continue;
                    if (key.equals(s.optString("key"))) {
                        /* 保留既有 accounts 与 meta（编辑站点不应清空账号/缓存） */
                        if (!site.has("accounts") && s.has("accounts")) site.put("accounts", s.optJSONArray("accounts"));
                        if (!site.has("meta") && s.has("meta")) site.put("meta", s.optJSONObject("meta"));
                        out.put(site);
                        found = true;
                    } else out.put(s);
                }
                if (!found) out.put(site);
                cfg.put("sites", out);
                saveConfigLocked(cfg);
            } catch (Exception ignored) {}
        }
    }

    public void removeSite(String siteKey) {
        if (siteKey == null) return;
        synchronized (LOCK) {
            try {
                JSONObject cfg = config();
                JSONArray arr = cfg.optJSONArray("sites");
                if (arr == null) return;
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null && !siteKey.equals(s.optString("key"))) out.put(s);
                }
                cfg.put("sites", out);
                saveConfigLocked(cfg);
            } catch (Exception ignored) {}
        }
    }

    /** 由 baseUrl 自动生成站点 key（小写、去协议/特殊字符） */
    public static String siteKeyOf(String baseUrl) {
        String k = baseUrl == null ? "site" : baseUrl.toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return k.isEmpty() ? "site" : k;
    }

    /* ---------- 站点级元数据缓存（v0.1.8） ----------
     * turnstile_site_key / quota_per_unit 是「站点」属性而非「账号」属性。
     * v0.1.6 之前存在账号里，导致新建账号必为空 → 签到报「未配置 siteKey」。 */

    public String siteMeta(String siteKey, String field, String def) {
        JSONObject s = findSite(siteKey);
        if (s == null) return def;
        JSONObject m = s.optJSONObject("meta");
        if (m == null || !m.has(field)) return def;
        String v = m.optString(field, def);
        return v == null ? def : v;
    }

    public long siteMetaLong(String siteKey, String field, long def) {
        JSONObject s = findSite(siteKey);
        if (s == null) return def;
        JSONObject m = s.optJSONObject("meta");
        if (m == null) return def;
        long v = m.optLong(field, def);
        return v > 0 ? v : def;
    }

    /** 写站点级 meta 字段（幂等：值未变化不落盘） */
    public void putSiteMeta(String siteKey, String field, Object value) {
        if (siteKey == null || field == null || value == null) return;
        synchronized (LOCK) {
            try {
                JSONObject cfg = config();
                JSONObject site = findSiteObj(cfg, siteKey);
                if (site == null) return;
                JSONObject m = site.optJSONObject("meta");
                if (m == null) { m = new JSONObject(); site.put("meta", m); }
                if (m.has(field) && String.valueOf(value).equals(m.optString(field, null))) return;
                m.put(field, value);
                saveConfigLocked(cfg);
            } catch (Exception ignored) {}
        }
    }

    /* ---------- 账号操作（账号挂站点之下） ---------- */
    public JSONObject findSite(String siteKey) { return findSiteObj(config(), siteKey); }

    public JSONObject findAccount(String key) {
        if (key == null) return null;
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

    /** 找到账号所属站点（含 site 引用）；找不到返回 null */
    public JSONObject siteOfAccount(String key) {
        if (key == null) return null;
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

    /** 账号所属站点 key；找不到返回空串（替代 siteOfAccount(k).optString() 的 NPE 写法，v0.1.8） */
    public String siteKeyOfAccount(String accountKey) {
        JSONObject s = siteOfAccount(accountKey);
        return s == null ? "" : s.optString("key", "");
    }

    public void upsertAccount(String siteKey, JSONObject acc) {
        if (siteKey == null || siteKey.isEmpty() || acc == null) return;
        synchronized (LOCK) {
            try {
                JSONObject cfg = config();
                JSONArray sites = cfg.optJSONArray("sites");
                if (sites == null) return;
                boolean touched = false;
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
                        if (a == null) continue;
                        if (key.equals(a.optString("key"))) { out.put(acc); found = true; }
                        else out.put(a);
                    }
                    if (!found) out.put(acc);
                    s.put("accounts", out);
                    touched = true;
                    break;
                }
                if (touched) saveConfigLocked(cfg);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 只更新账号的若干字段（v0.1.8 防丢失更新）：
     * 在锁内重读账号最新副本再合并，避免调用方持有的旧快照覆盖别的线程刚写入的字段
     * （典型场景：刷新线程写 lastStatus 的同时，签到线程写 token）。
     */
    public void patchAccount(String accountKey, JSONObject patch) {
        if (accountKey == null || patch == null) return;
        synchronized (LOCK) {
            try {
                JSONObject cfg = config();
                JSONArray sites = cfg.optJSONArray("sites");
                if (sites == null) return;
                for (int i = 0; i < sites.length(); i++) {
                    JSONObject s = sites.optJSONObject(i);
                    if (s == null) continue;
                    JSONArray accs = s.optJSONArray("accounts");
                    if (accs == null) continue;
                    for (int j = 0; j < accs.length(); j++) {
                        JSONObject a = accs.optJSONObject(j);
                        if (a == null || !accountKey.equals(a.optString("key"))) continue;
                        java.util.Iterator<String> it = patch.keys();
                        while (it.hasNext()) { String k = it.next(); a.put(k, patch.get(k)); }
                        saveConfigLocked(cfg);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public void removeAccount(String key) {
        if (key == null) return;
        synchronized (LOCK) {
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
                saveConfigLocked(cfg);
            } catch (Exception ignored) {}
        }
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
        synchronized (LOCK) {
            try { return new JSONArray(sp.getString("logs", "[]")); } catch (Exception e) { return new JSONArray(); }
        }
    }

    public void appendLog(String site, String account, String event, String detail) {
        synchronized (LOCK) {
            try {
                JSONArray a;
                try { a = new JSONArray(sp.getString("logs", "[]")); } catch (Exception e) { a = new JSONArray(); }
                String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                        .format(new java.util.Date());
                JSONObject e = new JSONObject().put("time", now).put("site", site)
                        .put("account", account).put("event", event);
                if (detail != null) e.put("detail", detail);
                a.put(e);
                while (a.length() > 2000) a.remove(0);
                sp.edit().putString("logs", a.toString()).commit();
            } catch (Exception ignored) {}
        }
    }
}