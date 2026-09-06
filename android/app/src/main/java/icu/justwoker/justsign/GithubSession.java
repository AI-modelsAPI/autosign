package icu.justwoker.justsign;

import android.os.Build;
import android.webkit.CookieManager;

import org.json.JSONObject;

/**
 * GithubSession（v0.2.9）— 按账号保存/恢复 GitHub 会话 Cookie。
 *
 * 【为什么要这个】多账号同站轮询刷新时，SilentAuth 只有一个全局 WebView，
 * GitHub 会话 Cookie 是共享的。用户上轮反馈：轮流刷新两个账号，若每次会话
 * 都是另一个账号，就会触发无限手动授权。
 *
 * 【方案】每个账号在「授权成功后」把当时 github.com 的会话 Cookie 快照存进
 * Store（account.ghCookie）。SilentAuth 后台交换前先 restore 回目标账号的
 * 会话：清掉 github.com 当前 Cookie，再 set 回目标账号快照 —— 站点看到的
 * 就是该账号，后台自动完成，除非该账号从未授权过（无快照）才需一次手动。
 *
 * 【注意】CookieManager 是全局单例，清 Cookie 会同时影响可见授权页/浏览器，
 * 所以这里只做「github.com 域的、面向本 App WebView 的」会话隔离；
 * 且 restore 后若授权页此刻打开会受影响 —— 因此 restore 只在离屏
 * SilentAuth 流程内调用，调用点为 startOffscreen 加载 authorize URL 之前。
 */
public final class GithubSession {

    private GithubSession() {}

    /** 读取当前 github.com 域全部会话 Cookie（作为该账号的快照） */
    public static String snapshot() {
        try {
            String ck = CookieManager.getInstance().getCookie("https://github.com");
            return ck == null ? "" : ck;
        } catch (Exception e) { return ""; }
    }

    /** 恢复某个账号的 github.com 会话快照；成功返回 true */
    public static boolean restore(String cookie) {
        try {
            if (cookie == null || cookie.isEmpty()) return false;
            CookieManager cm = CookieManager.getInstance();
            /* 先清空 github.com 域当前会话，避免新旧 Cookie 混在一起 */
            clearGithub();
            /* 逐条 set 回；setCookie 是异步的，需要 flush 确保提交 */
            String[] pairs = cookie.split(";\\s*");
            for (String p : pairs) {
                if (p == null || p.trim().isEmpty()) continue;
                cm.setCookie("https://github.com", p.trim() + "; path=/");
            }
            if (Build.VERSION.SDK_INT >= 21) cm.flush();
            /* 等一拍确保 setCookie 生效后由调用方继续 loadUrl */
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            return true;
        } catch (Exception e) { return false; }
    }

    /** 仅清 github.com 域 Cookie（不碰其它站点，避免影响正常 WebView 使用） */
    public static void clearGithub() {
        try {
            CookieManager cm = CookieManager.getInstance();
            /* Android CookieManager 没有按域删除的 API；getCookie 返回所有会话，
             * 用 setCookie 逐条覆盖为过期值实现清域 */
            String ck = cm.getCookie("https://github.com");
            if (ck != null && !ck.isEmpty()) {
                for (String p : ck.split(";\\s*")) {
                    if (p == null || p.trim().isEmpty()) continue;
                    String name = p.trim().split("=", 2)[0];
                    cm.setCookie("https://github.com", name + "=; path=/; max-age=0");
                }
            }
            if (Build.VERSION.SDK_INT >= 21) cm.flush();
        } catch (Exception ignored) {}
    }

    /** 把账号快照写进 Store（account.ghCookie） */
    public static void save(Store store, String accountKey, String cookie) {
        if (store == null || accountKey == null || accountKey.isEmpty()) return;
        try {
            JSONObject patch = new JSONObject().put("ghCookie", cookie == null ? "" : cookie);
            store.patchAccount(accountKey, patch);
        } catch (Exception ignored) {}
    }

    /** 从 Store 读取账号快照；无则返回空串 */
    public static String load(Store store, String accountKey) {
        try {
            JSONObject acc = store.findAccount(accountKey);
            return acc == null ? "" : acc.optString("ghCookie", "");
        } catch (Exception e) { return ""; }
    }
}