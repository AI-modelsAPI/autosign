package icu.justwoker.justsign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** SharedPreferences 存取：tokens / config / 运行日志（与 Node 版 db.js + config.js 对齐） */
public class Store {
    private static final String SP = "justsign";
    private final SharedPreferences sp;

    public Store(Context c) { sp = c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE); }

    /* ---------- tokens ---------- */
    public JSONArray tokens() {
        try { return new JSONArray(sp.getString("tokens", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    public void saveTokens(JSONArray a) { sp.edit().putString("tokens", a.toString()).apply(); }

    public JSONObject findToken(String key) {
        JSONArray a = tokens();
        for (int i = 0; i < a.length(); i++) {
            try {
                JSONObject o = a.getJSONObject(i);
                if (key != null && key.equals(o.optString("key"))) return o;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void upsertToken(JSONObject rec) {
        try {
            String key = rec.getString("key");
            JSONArray a = tokens(), out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (key.equals(o.optString("key"))) { out.put(rec); found = true; } else out.put(o);
            }
            if (!found) out.put(rec);
            saveTokens(out);
        } catch (Exception ignored) {}
    }

    public void removeToken(String key) {
        JSONArray a = tokens(), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            try {
                JSONObject o = a.getJSONObject(i);
                if (!key.equals(o.optString("key"))) out.put(o);
            } catch (Exception ignored) {}
        }
        saveTokens(out);
    }

    /* ---------- config ---------- */
    public JSONObject config() {
        try { return new JSONObject(sp.getString("config", "")); } catch (Exception e) { return defaultConfig(); }
    }
    public void saveConfig(JSONObject c) { sp.edit().putString("config", c.toString()).apply(); }

    public static JSONObject defaultConfig() {
        try {
            JSONObject proxy = new JSONObject().put("enabled", true).put("type", "socks5")
                    .put("host", "127.0.0.1").put("port", 10808);
            JSONObject schedule = new JSONObject().put("enabled", true).put("cron", "0 3 * * *");
            JSONObject site = new JSONObject().put("key", "justworker")
                    .put("name", "justworker (小学生公益站)")
                    .put("baseUrl", "https://api.justwoker.icu")
                    .put("checkinType", "login");
            return new JSONObject().put("proxy", proxy).put("schedule", schedule)
                    .put("sites", new JSONArray().put(site));
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