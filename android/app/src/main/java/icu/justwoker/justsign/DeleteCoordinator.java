package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 安全删除：先冻结任务，尽力注销远端会话，再原子清理本地记录与 WebView Profile。 */
public final class DeleteCoordinator {
    public interface Callback { void done(Result result); }
    public static final class Result {
        public final boolean localDeleted;
        public final int remoteOk;
        public final int remoteUnconfirmed;
        Result(boolean localDeleted, int remoteOk, int remoteUnconfirmed) {
            this.localDeleted = localDeleted;
            this.remoteOk = remoteOk;
            this.remoteUnconfirmed = remoteUnconfirmed;
        }
        public String message() {
            if (!localDeleted) return "删除失败：本地数据未能清除";
            if (remoteUnconfirmed == 0) return "已完全删除，本地会话与远端登录均已清理";
            return "已从本机删除；" + remoteUnconfirmed + " 个远端会话未确认注销";
        }
    }

    private static final ConcurrentHashMap<String, Boolean> DELETING_ACCOUNTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> DELETING_SITES = new ConcurrentHashMap<>();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
    private DeleteCoordinator() {}

    public static boolean blocked(String siteKey, String accountKey) {
        return (siteKey != null && DELETING_SITES.containsKey(siteKey))
                || (accountKey != null && DELETING_ACCOUNTS.containsKey(accountKey));
    }
    public static boolean blockedAccount(String accountKey) {
        return accountKey != null && DELETING_ACCOUNTS.containsKey(accountKey);
    }
    /** 仅供用户明确重新添加同 key 的账号使用。 */
    public static void allowRecreate(String accountKey) {
        if (accountKey != null) DELETING_ACCOUNTS.remove(accountKey);
    }
    public static void allowRecreateSite(String siteKey) {
        if (siteKey != null) DELETING_SITES.remove(siteKey);
    }

    public static void deleteAccount(Context context, String accountKey, Callback callback) {
        Context app = context.getApplicationContext();
        Store store = new Store(app);
        JSONObject account = store.findAccount(accountKey);
        JSONObject site = store.siteOfAccount(accountKey);
        if (account == null || site == null) {
            deliver(callback, new Result(false, 0, 0));
            return;
        }
        String siteKey = site.optString("key", "");
        DELETING_ACCOUNTS.put(accountKey, true);
        new Thread(() -> {
            int remote = logout(site, account) ? 1 : 0;
            boolean deleted = store.removeAccountSecure(accountKey);
            WebViewProfileUtil.deleteProfileFor(siteKey, accountKey);
            store.purgeLogs(siteKey, accountKey);
            store.opLog(siteKey, "", "删除账号", deleted ? "ok" : "err",
                    deleted ? "账号及本地会话已删除" : "本地数据删除失败",
                    remote == 1 ? "远端已注销" : "远端会话未确认注销", "user");
            deliver(callback, new Result(deleted, remote, remote == 1 ? 0 : 1));
        }, "delete-account").start();
    }

    public static void deleteSite(Context context, String siteKey, Callback callback) {
        Context app = context.getApplicationContext();
        Store store = new Store(app);
        JSONObject site = store.findSite(siteKey);
        if (site == null) { deliver(callback, new Result(false, 0, 0)); return; }
        List<JSONObject> accounts = new ArrayList<>();
        JSONArray arr = site.optJSONArray("accounts");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null) accounts.add(a);
        }
        DELETING_SITES.put(siteKey, true);
        for (JSONObject a : accounts) DELETING_ACCOUNTS.put(a.optString("key", ""), true);
        new Thread(() -> {
            int remote = 0;
            for (JSONObject a : accounts) if (logout(site, a)) remote++;
            boolean deleted = store.removeSiteSecure(siteKey);
            for (JSONObject a : accounts)
                WebViewProfileUtil.deleteProfileFor(siteKey, a.optString("key", ""));
            store.purgeLogs(siteKey, null);
            store.opLog("", "", "删除站点", deleted ? "ok" : "err",
                    deleted ? "站点及本地会话已删除" : "本地数据删除失败",
                    (accounts.size() - remote) == 0 ? "远端已注销" : "部分远端会话未确认注销", "user");
            deliver(callback, new Result(deleted, remote, accounts.size() - remote));
        }, "delete-site").start();
    }

    private static boolean logout(JSONObject site, JSONObject account) {
        String base = site.optString("baseUrl", "").replaceAll("/+$", "");
        if (base.isEmpty()) return false;
        String token = clean(account.optString("token", ""));
        String cookie = clean(account.optString("siteCookie", ""));
        if (token.isEmpty() && cookie.isEmpty()) return true;
        String[] paths = { "/api/user/logout", "/api/auth/logout", "/logout" };
        for (String path : paths) {
            try {
                Request.Builder b = new Request.Builder().url(base + path)
                        .post(RequestBody.create("{}", JSON));
                if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
                if (!cookie.isEmpty()) b.header("Cookie", cookie);
                String uid = clean(account.optString("siteUserId", ""));
                if (!uid.isEmpty()) b.header("New-Api-User", uid);
                try (Response r = HTTP.newCall(b.build()).execute()) {
                    if (r.isSuccessful() || r.code() == 401) return true;
                    if (r.code() != 404 && r.code() != 405) return false;
                }
            } catch (Exception ignored) { return false; }
        }
        return false;
    }
    private static String clean(String s) { return s == null || "null".equals(s) ? "" : s.trim(); }
    private static void deliver(Callback cb, Result result) {
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(result));
    }
}