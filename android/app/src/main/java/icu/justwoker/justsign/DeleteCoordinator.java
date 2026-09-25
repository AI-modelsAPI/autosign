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
        /** v1.1.15：本地已删、远端注销仍在后台进行（UI 已可立即刷新）。 */
        public final boolean pendingRemote;
        Result(boolean localDeleted, int remoteOk, int remoteUnconfirmed) {
            this(localDeleted, remoteOk, remoteUnconfirmed, false);
        }
        Result(boolean localDeleted, int remoteOk, int remoteUnconfirmed, boolean pendingRemote) {
            this.localDeleted = localDeleted;
            this.remoteOk = remoteOk;
            this.remoteUnconfirmed = remoteUnconfirmed;
            this.pendingRemote = pendingRemote;
        }
        public String message() {
            if (!localDeleted) return "删除失败：本地数据未能清除";
            if (pendingRemote) return "已从本机删除，正在后台注销远端会话";
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
            /* v1.1.15：先做「本地删除 + 立即回调刷新 UI」，再后台尽力注销远端。
             * 旧实现把阻塞的远端 logout（每账号 connect+read 可达 16s）放在最前，
             * 本地删除与 render() 被拖在其后 —— 顶栏「几站几号」统计要等网络回来才更新，
             * 表现为「删除后统计没变」。凭据在本地删除前已拷贝进 site/account 对象，
             * 后台线程仍可用它完成远端注销，不影响登出效果。 */
            boolean deleted = store.removeAccountSecure(accountKey);
            WebViewProfileUtil.deleteProfileFor(siteKey, accountKey);
            store.purgeLogs(siteKey, accountKey);
            store.opLog(siteKey, "", "删除账号", deleted ? "ok" : "err",
                    deleted ? "账号及本地会话已删除，正在后台注销远端" : "本地数据删除失败",
                    "", "user");
            deliver(callback, new Result(deleted, 0, 0, true));
            /* 后台尽力注销远端会话，结果只落日志，不再回调 UI（UI 已刷新）。 */
            if (deleted) new Thread(() -> {
                int remote = logout(site, account) ? 1 : 0;
                store.opLog(siteKey, "", "删除账号", "info",
                        remote == 1 ? "远端会话已注销" : "远端会话未确认注销",
                        "本地已删除；远端为尽力注销", "auto");
                DELETING_ACCOUNTS.remove(accountKey);
            }, "delete-account-remote").start();
            else DELETING_ACCOUNTS.remove(accountKey);
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
            /* v1.1.15：同 deleteAccount —— 先本地删 + 立即回调刷新，再后台尽力注销远端。
             * 旧实现把 N 个账号的阻塞 logout 串行放在最前（N×16s 最坏），顶栏「几站几号」
             * 统计与看板要等所有网络回来才刷新。凭据已在 accounts 列表对象里，后台仍可注销。 */
            boolean deleted = store.removeSiteSecure(siteKey);
            for (JSONObject a : accounts)
                WebViewProfileUtil.deleteProfileFor(siteKey, a.optString("key", ""));
            store.purgeLogs(siteKey, null);
            store.opLog("", "", "删除站点", deleted ? "ok" : "err",
                    deleted ? "站点及本地会话已删除，正在后台注销远端" : "本地数据删除失败",
                    "", "user");
            deliver(callback, new Result(deleted, 0, 0, true));
            if (deleted) new Thread(() -> {
                int remote = 0;
                for (JSONObject a : accounts) if (logout(site, a)) remote++;
                store.opLog("", "", "删除站点", "info",
                        (accounts.size() - remote) == 0 ? "远端会话已全部注销"
                                : (accounts.size() - remote) + " 个远端会话未确认注销",
                        "本地已删除；远端为尽力注销", "auto");
                for (JSONObject a : accounts) DELETING_ACCOUNTS.remove(a.optString("key", ""));
            }, "delete-site-remote").start();
            else for (JSONObject a : accounts) DELETING_ACCOUNTS.remove(a.optString("key", ""));
        }, "delete-site").start();
    }

    /** 尽力注销站点会话（删除账号/站点复用）。统一走 ReauthManager.logoutVerified
     * 实测端点 GET /api/user/logout（+JSON success 校验），避免与签到链路判据分叉。
     * 返回 true = 已确认登出或旧会话已不存在（幂等）；false = 无法确认/被拒。 */
    public static boolean logout(JSONObject site, JSONObject account) {
        if (site == null || account == null) return false;
        int r = ReauthManager.logoutVerified(site, account);
        return r == ReauthManager.CONFIRMED || r == ReauthManager.ABSENT;
    }
    private static String clean(String s) { return s == null || "null".equals(s) ? "" : s.trim(); }
    private static void deliver(Callback cb, Result result) {
        if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.done(result));
    }
}