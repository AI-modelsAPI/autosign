package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AuthActivity（v0.2.3）— GitHub 授权（仅在需要人工交互时由 MainActivity 拉起）。
 * 优先在后台静默完成（SilentAuth）；只有需要用户登录/2FA时才展示本界面。
 */
public class AuthActivity extends Activity {

    private WebView wv;
    private LinearLayout boot, errorLayer;
    private TextView bootText, tip, errMsg;
    private FrameLayout root;

    private String siteKey, accountKey, alias, credentialId, baseUrl, siteHost;
    /** v1.0.7：站点 OAuth 方式（github / linuxdo …），决定授权页 URL、兑换端点与身份锚点。 */
    private String oauthProvider = "github";
    /** 取 state 时服务端下发的会话 Cookie；兑换时必须带回
     * （旧版 New API 把 state 存在服务端 session 里，不带 session 会判「state 无效」）。 */
    private volatile String stateSessionCookie = "";
    /** true = 本轮 state 取自账号专属 WebView（与站点 session / WAF Cookie 同源）。 */
    private volatile boolean stateFromWebView = false;
    /** WebView 引导取参：等待 JS 桥回调的闩与载荷（仅授权准备阶段使用）。 */
    private volatile java.util.concurrent.CountDownLatch bootstrapLatch;
    private volatile String bootstrapJson;
    private volatile boolean bootstrapPending = false;
    /** 挂起的兑换脚本：回调页放行加载后，onPageFinished 检测到站点域即注入
     * （同源 fetch 兑换，内核带实时 session + WAF cookie，天然过质询）。 */
    private volatile String pendingExchangeJs = null;
    /** linuxdo 观察者模式：站点前端在 WebView 里自行完成 OAuth 登录，
     * 注入的轮询脚本检测 /api/user/self 成功即收凭据。 */
    private volatile boolean observingLogin = false;
    /** 观察者模式开始时间（5 分钟总超时兜底）。 */
    private volatile long observeStartMs = 0;
    private long lastProbeLogMs = 0;
    private String lastProbeLogKind = "";

    private void logProbeDiagnostic(String kind, String detail) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!kind.equals(lastProbeLogKind) || now - lastProbeLogMs >= 30000) {
            lastProbeLogKind = kind;
            lastProbeLogMs = now;
            authLog("info", "登录态探测（继续等待）", detail);
        }
    }
    /** Business scripts are gated in their own document; never reuse a previous page's WAF flag. */
    private void evaluateBusiness(WebView v, String pageUrl, String js,
                                  java.util.function.Consumer<Boolean> completion) {
        if (v == null || isAuthInactive()) return;
        final int attempt = authAttempt;
        try {
            v.evaluateJavascript(AuthPageGuard.wrap(pageUrl, js), value -> {
                if (isAuthInactive() || attempt != authAttempt) return;
                boolean attempted = "\"attempted\"".equals(value);
                if (!attempted) logProbeDiagnostic("page-guard", "文档未就绪或仍为质询页，未执行业务脚本");
                if (completion != null) completion.accept(attempted);
            });
        } catch (Exception e) { logProbeDiagnostic("injection", "业务脚本未能注入"); }
    }

    private void injectPendingExchange(WebView v, String pageUrl) {
        if (isAuthInactive() || pendingExchangeJs == null
                || !AuthProbeJs.sameOrigin(pageUrl, baseUrl)) return;
        final String js = pendingExchangeJs;
        final int attempt = authAttempt;
        // Disarm before evaluating. Only an explicit 'blocked' proves no write was attempted.
        pendingExchangeJs = null;
        try {
            v.evaluateJavascript(AuthPageGuard.wrapOnce(pageUrl, js, "exchange-" + attempt), value -> {
                if (isAuthInactive() || attempt != authAttempt) return;
                if ("\"not-ready\"".equals(value)) {
                    // Only a transient (loading / challenge) state re-arms; permanent 'blocked' does not.
                    if (pendingExchangeJs == null && exchanging) pendingExchangeJs = js;
                } else if ("\"attempted\"".equals(value)) {
                    authLog("info", "回调页同源就绪，兑换已尝试", "仅一次；不重放写请求");
                }
            });
        } catch (Exception e) {
            showLoadError("兑换脚本执行状态不确定，已停止自动重试，请重新发起授权");
        }
    }
    /** v1.1.8 诊断：抓取页面此刻的可观测事实，用于定位「质询为何不过」。
     * 只读不写，不改变页面行为。输出长度、可见文本、关键标志与 cookie 长度。 */
    private void probePageFacts(android.webkit.WebView v, String tag) {
        if (v == null) return;
        try {
            v.evaluateJavascript(
                    "(function(){try{"
                            + "var d=document, h=d.documentElement?d.documentElement.innerHTML:'';"
                            + "var t=(d.body&&d.body.innerText)?d.body.innerText:'';"
                            + "t=t.replace(/\\s+/g,' ').slice(0,120);"
                            + "var sc=d.scripts?d.scripts.length:0;"
                            + "return JSON.stringify({len:h.length,scripts:sc,txt:t,"
                            + "arg1:h.indexOf('arg1=')>=0,acw:h.indexOf('acw_sc')>=0,"
                            + "ck:(d.cookie||'').length});"
                            + "}catch(e){return 'ERR:'+e;}})();",
                    value -> {
                        try {
                            authLog("info", "页面诊断" + (tag == null ? "" : "·" + tag),
                                    String.valueOf(value));
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
    /** 观察者模式的 Java 侧驱动定时器：SPA 客户端路由跳转不触发 onPageFinished，
     * 页面内脚本可能随整页跳转销毁 —— 由 Activity 级 Handler 周期性注入
     * 一次性 self 检查（无状态、无守卫），直到成功或超时。 */
    private final Runnable loginObserverTick = new Runnable() {
        @Override public void run() {
            if (isAuthInactive() || !observingLogin || isFinishing()) return;
            /* 5 分钟总超时：前端 OAuth 仍未完成（用户未过人机验证/网络异常），
             * 按事实报错，绝不无限轮询。 */
            if (System.currentTimeMillis() - observeStartMs > 5 * 60 * 1000L) {
                observingLogin = false;
                exchanging = false;
                SilentAuth.releaseExchange(accountKey);
                authLog("err", "观察者超时", "5 分钟内未检测到登录成功");
                showLoadError("授权超时：5 分钟内未完成登录。若页面停留在人机验证，请完成验证后点重试");
                return;
            }
            if (wv != null) {
                String cur = wv.getUrl();
                /* v1.1.0：低频探测（3s 一次），与站点的交互全部交给用户/SPA。
                 * 只在 SPA 回调路由可能存在时（站点域）注入，避免无关域请求。 */
                if (AuthProbeJs.sameOrigin(cur, baseUrl)) {
                    JSONObject acc = new Store(AuthActivity.this).findAccount(accountKey);
                    String uid = acc == null ? "" : acc.optString("siteUserId", "");
                    try { wv.evaluateJavascript(AuthProbeJs.render(baseUrl, uid), null); }
                    catch (Exception e) { logProbeDiagnostic("injection", "脚本注入失败"); }
                }
                /* v1.1.10：授权确认页「允许」按钮 —— 之前只在 onPageFinished 注入一次，
                 * 若那一刻确认页还是 CF 质询态，会被文档门控挡住且永不重试（真机 17:08:55
                 * 实测：准备扫描→未就绪→再没机会）。现在只要仍停在授权确认页且尚未点过，
                 * 每轮就绪后再注入一次；authorizeJs 自带 window.__jsAuthorize 单次守卫、
                 * 只精确点 approve 链接绝不点 decline，AuthPageGuard 保证质询态不执行，
                 * authorizeClicked（跨文档、Java 侧）保证「允许」全程只有一次有效点击。 */
                if (!authorizeClicked && cur != null
                        && isAuthorizeEndpoint(cur.toLowerCase(java.util.Locale.US))) {
                    evaluateBusiness(wv, cur, AuthFillJs.authorizeJs(), null);
                }
            }
            h.postDelayed(this, 3000);
        }
    };
    /** 是否已经导航进入过第三方 OAuth 服务商页面（github.com 或 connect.linux.do） */
    private volatile boolean hasVisitedOAuthProvider = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean done = false;
    /** Recoverable failure stops work but keeps the explicit retry UI alive. */
    private volatile boolean loadFailed = false;
    private volatile String lastAuthError = "";
    private volatile int authAttempt = 0;
    private final AuthNavigationGuard navigationGuard = new AuthNavigationGuard();
    private final AuthLoopRecovery loopRecovery = new AuthLoopRecovery();
    private boolean isAuthInactive() { return done || loadFailed || isFinishing(); }
    private volatile boolean proxyApplied = false;
    private volatile boolean exchanging = false;
    private volatile boolean filled = false;
    /** v1.1.6：把「已注入填充脚本」绑定到具体 URL，取代原来的 2.5s 定时重置。 */
    private volatile String filledUrl = "";

    /** 允许在「同一 URL」上重新注入填充脚本（弹窗关闭 / 切换 2FA / 新 OTP 后需要）。 */
    private void allowRefill() {
        filled = false;
        filledUrl = "";
    }
    /** v1.0.8：本轮 LinuxDO 登录已提交后禁止回到登录页重复填充/点击，避免人机验证后循环。 */
    private volatile boolean loginSubmitStarted = false;
    private volatile String authUrl = "";
    /** 本轮 /api/oauth/state 返回值；回调必须严格匹配，防串流/CSRF。 */
    private volatile String expectedOauthState = "";
    /** 本轮授权扫描诊断：true = authorizeJs 已上报扫描（授权页脚本已执行）。
     * access_denied 时据此区分「脚本未跑即被拒」（时序问题→可自动重试）
     * 和「已点击授权仍被拒」（GitHub 侧拒绝→停手转人工）。 */
    private volatile boolean authorizeScanStarted = false;
    private volatile boolean authorizeClicked = false;
    /** 本轮 access_denied 自动恢复次数（防循环建 state）：每次用户操作最多 1 次 */
    private volatile int deniedRetries = 0;
    private volatile int reauthTries = 0;
    /** 同一安全 URL 只记录一次，避免 onPageStarted/onPageFinished 双重刷日志。 */
    private volatile String lastNavLog = "";
    /** 授权导航单调序号：定位“确认页未完成加载就被回调拒绝”的时序问题。 */
    private final java.util.concurrent.atomic.AtomicInteger authNavSeq = new java.util.concurrent.atomic.AtomicInteger();
    private String credAccount = "", credPassword = "", credOtp = "";
    /** v1.0.3+：仅当凭据库明确存有 GitHub 用户名时才非空，专供授权 URL 的 &login= 使用。
     * credAccount 会在 githubUser 为空时回退 siteAccount（站内账号），若把站内账号当作
     * GitHub 用户名传进 &login=，GitHub 可能因账号不匹配走异常流程甚至诱发 access_denied，
     * 故 login 参数只认真正的 githubUser，回退值一律不带。 */
    private String githubLogin = "";
    /** true = 该站的 /api/oauth/state 只认 GET（AgentRouter 型）；由 404 探测得出并记入站点 meta */
    private boolean stateUseGet = false;
    private boolean credHasOtp = false;
    /* 本次授权绑定的「站点×账号」Profile（降级=Default 时为 null） */
    private androidx.webkit.Profile mProfile;

    public class Bridge {
        @JavascriptInterface public void onSession(String json) {
            if (isAuthInactive()) return;
            h.post(() -> handleBundle(json, ""));
        }
        @JavascriptInterface public void onExchangeDone(int status, String body) {
            h.post(() -> {
                if (isAuthInactive() || !exchanging) return;
                try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                /* 兑换 fetch 的 Set-Cookie（登录 session）由内核在响应头处理时写入，
                 * r.text() resolve 时已落地 —— flush 后读全量 Cookie 即登录后凭据。 */
                String freshCk = WebViewProfileUtil.cookieHeader(mProfile, baseUrl);
                authLog("info", "同源兑换响应", "HTTP " + status + "；body=" + body.length() + "B；cookieLen=" + (freshCk == null ? 0 : freshCk.length()));
                if (status >= 200 && status < 300) {
                    handleBundle(body, freshCk == null ? "" : freshCk);
                } else {
                    String errCode = "", errMessage = "";
                    try {
                        JSONObject eo = new JSONObject(body);
                        errCode = eo.optString("code", "");
                        errMessage = eo.optString("message", "");
                    } catch (Exception ignored) {}
                    exchanging = false;
                    SilentAuth.releaseExchange(accountKey);
                    showLoadError(mapExchangeError(errCode, errMessage.isEmpty() ? ("HTTP " + status) : errMessage));
                }
            });
        }
        @JavascriptInterface public void onExchangeFailed(String error) {
            h.post(() -> {
                if (isAuthInactive() || !exchanging) return;
                exchanging = false;
                SilentAuth.releaseExchange(accountKey);
                showLoadError("授权交换网络异常: " + error);
            });
        }
        @JavascriptInterface public void onSelfResult(int status, String body) {
            h.post(() -> {
                if (isAuthInactive() || !observingLogin) return;   /* 已收凭据/已停止：忽略迟到的探测结果 */
                if (!AuthProbeJs.sameOrigin(wv == null ? null : wv.getUrl(), baseUrl)) return;
                try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                String ck = WebViewProfileUtil.cookieHeader(mProfile, baseUrl);
                /* 诊断只记长度与状态码，不落正文/头/凭据 */
                authLog("info", "登录态检测响应", "HTTP " + status + "；body=" + body.length() + "B；cookieLen=" + (ck == null ? 0 : ck.length()));
                if (status == 200) {
                    try {
                        JSONObject r = new JSONObject(body);
                        if (r.optBoolean("success")) {
                            observingLogin = false;
                            /* /api/user/self 的 data 即用户对象，包成兑换响应同构
                             * bundle 交给 handleBundle 收凭据（cookie 型站路径）。 */
                            handleBundle(r.toString(), ck == null ? "" : ck);
                            return;
                        }
                        logProbeDiagnostic("http200-nosuccess", "HTTP 200 但 success=false（未登录或响应异常）");
                    } catch (Exception e) {
                        logProbeDiagnostic("http200-badjson", "HTTP 200 响应非 JSON（疑似质询页）");
                    }
                } else {
                    logProbeDiagnostic("http-" + status, "HTTP " + status + "（登录仍在进行）");
                }
                /* 403/质询页/401：登录仍在进行（前端 OAuth 或 WAF 质询未完成）——
                 * 不报错，Java 侧定时器 3s 后会再探。只有明确 401 且页面已稳定
                 * 才由超时兜底报错（页面内脚本的 80 次轮询负责终态上报）。 */
            });
        }
        @JavascriptInterface public void onSelfFailed(String error) {
            h.post(() -> {
                if (isAuthInactive() || !observingLogin) return;
                if (!AuthProbeJs.sameOrigin(wv == null ? null : wv.getUrl(), baseUrl)) return;
                /* 网络瞬断：不报错，定时器会再探。内容为脱敏类别词，不含 URL/凭据。 */
                logProbeDiagnostic("probe-failed", "探测未完成（" + (error == null ? "unknown" : error) + "），继续等待");
            });
        }
        @JavascriptInterface public void onOAuthBootstrap(String json) {
            /* v1.0.7 实测定稿：state 响应的 Set-Cookie 下发新 session S1（state 存在
             * S1 里），fetch 完成后 CookieManager 里已是 S1 —— 此刻读全量 Cookie
             * （S1 + acw_sc__v2）作为兑换凭据。实测对照：带 S1 → state 校验通过；
             * 带 S0（fetch 前的 session）→ 403 state is empty or not same。
             * statusBlocked（WAF 质询页）时不解 latch —— 质询页刷新后 onPageFinished
             * 会再注入 bootstrapJs，等下一轮真实 JSON。 */
            h.post(() -> {
                if (isAuthInactive() || !bootstrapPending) return;
                boolean blocked = false;
                try { blocked = new JSONObject(json).optBoolean("statusBlocked", false); } catch (Exception ignored) {}
                if (blocked) {
                    authLog("info", "引导取参被质询，等页面刷新后重试", "statusBlocked=true");
                    return;   /* 不写 bootstrapJson、不解 latch，等下一轮注入 */
                }
                try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                String snap = WebViewProfileUtil.cookieHeader(mProfile, baseUrl);
                if (snap != null && !snap.isEmpty())
                    stateSessionCookie = snap;   /* 直接覆盖：fetch 后的快照才是 S1 */
                bootstrapJson = json;
                java.util.concurrent.CountDownLatch l = bootstrapLatch;
                if (l != null) l.countDown();
            });
        }
        @JavascriptInterface public void onFill(String json) {
            h.post(() -> {
                if (isAuthInactive()) return;
                try {
                    JSONObject r = new JSONObject(json);
                    String act = r.optString("action", "");
                    /* OAuth 确认页诊断：即使 ok=false（authorizeError）也必须落日志。
                     * 只记 host/path/title/按钮元数据；不记 query/code/state/账号/密码。 */
                    if (act.startsWith("authorize") || "autoAuthorize".equals(act)) {
                        String safeDetail = "host=" + r.optString("host", "")
                                + " path=" + r.optString("path", "")
                                + " title=" + r.optString("title", "")
                                + " id=" + r.optString("id", "")
                                + " name=" + r.optString("name", "")
                                + " type=" + r.optString("type", "")
                                + " text=" + r.optString("text", "")
                                + " authorizeCount=" + r.optInt("authorizeCount", -1)
                                + " cancelCount=" + r.optInt("cancelCount", -1)
                                + " loginForm=" + r.optBoolean("loginForm", false)
                                + " buttons=" + r.optInt("buttons", -1)
                                + " tries=" + r.optInt("tries", -1)
                                + (r.has("elements") ? " els=" + r.optString("elements", "") : "")
                                + (r.has("error") ? " error=" + r.optString("error", "") : "");
                        String summary;
                        String level = "info";
                        switch (act) {
                            case "authorizeScan": summary = "扫描授权确认页"; authorizeScanStarted = true; break;
                            case "authorizeFound": summary = "找到授权按钮"; break;
                            case "autoAuthorize": summary = "已点击授权按钮（仅一次）"; authorizeClicked = true; break;
                            case "authorizeSkipped": summary = "授权按钮已点过，跳过（防重复提交）"; break;
                            case "authorizeMissing": summary = "未找到授权按钮"; level = "warn"; break;
                            case "authorizeError": summary = "授权脚本异常"; level = "err"; break;
                            default: summary = "授权脚本诊断"; level = "info"; break;
                        }
                        authLog(level, summary, safeDetail);
                    }
                    if (!r.optBoolean("ok")) return;
                    if (!act.isEmpty()) {
                        String label;
                        switch (act) {
                            case "dismissModal":
                                label = "已自动关闭公告弹窗";
                                allowRefill(); /* 弹窗关闭后允许继续填表单 */
                                break;
                            case "dismissNotice":
                                label = "已自动关闭提示 (" + r.optString("btn", "") + ")";
                                allowRefill();
                                break;
                            case "submitLogin":
                                /* LinuxDO 人机验证可能完成后回到登录路由；本轮已提交则
                                 * 不能再次自动填充/点击，避免登录页循环。 */
                                if (observingLogin) loginSubmitStarted = true;
                                label = "已自动点击登录，请在下方完成人机验证答题…";
                                showTip(label);
                                break;
                            case "observerInjected":
                                label = "观察者脚本已注入（" + r.optString("path", "") + "）";
                                showTip(label);
                                break;
                            case "observerDump":
                                label = "登录页元素(" + r.optInt("count", 0) + ")";
                                authLog("info", "登录页元素快照",
                                        "count=" + r.optInt("count", 0) + "；els=" + r.optString("els", ""));
                                break;
                            case "linuxdoBtnClicked":
                                label = "已点击 LinuxDO 登录按钮（" + r.optString("text", "") + "）";
                                showTip(label);
                                break;
                            case "submit2fa":   label = "已自动提交 2FA 验证码"; break;
                            case "switch2fa":   label = "已切换到验证器 App 验证"; break;
                            case "expand2fa":   label = "已展开其他验证方式"; break;
                            case "submitAborted":
                                label = "未自动提交：" + ("captcha".equals(r.optString("reason", ""))
                                        ? "页面有待完成的人机验证" : "登录按钮未就绪");
                                break;
                            case "autoOff":     label = "未自动提交：未存密码，仅自动填充"; break;
                            default:            label = act; break;
                        }
                        showTip(label);
                        new Store(AuthActivity.this).opLog(siteKey, accountKey, "自动登录", "ok",
                                label, r.optString("label", ""), "auto");
                        if ("submitLogin".equals(act) || "switch2fa".equals(act) || "expand2fa".equals(act)) {
                            allowRefill();
                        }
                        return;
                    }
                    Object f = r.opt("filled");
                    if (f == null) return;
                    String what = String.valueOf(f);
                    showTip("已自动填充 " + what.replace("[", "").replace("]", "").replace("\"", ""));
                    new Store(AuthActivity.this).opLog(siteKey, accountKey, "自动填充", "ok",
                            "已填充 " + what, credHasOtp ? "含 2FA 动态码" : "该账号无 2FA", "auto");
                } catch (Exception ignored) {}
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        alias = getIntent().getStringExtra("alias");
        credentialId = getIntent().getStringExtra("credentialId");

        Store store = new Store(this);
        JSONObject site = store.findSite(siteKey);
        baseUrl = (site != null ? site.optString("baseUrl", "") : "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "站点不存在或 baseUrl 为空"));
            finish();
            return;
        }
        try { siteHost = new java.net.URL(baseUrl).getHost(); } catch (Exception e) { siteHost = ""; }
        /* v1.0.7：读取站点 OAuth 方式。AnyRouter 等 Linux DO 登录站不再是 github 专属。 */
        oauthProvider = SiteProtocol.provider(site, store.findAccount(accountKey));

        if (credentialId == null || credentialId.isEmpty()) {
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null) credentialId = acc.optString("credentialId", "");
        }
        if (credentialId != null && !credentialId.isEmpty()) {
            JSONObject c = store.findCredential(credentialId);
            if (c != null) {
                /* 这里填的是 GitHub 登录页，必须优先用 githubUser；
                 * siteAccount 只是站内昵称，两者不同名时用它会登录失败。 */
                /* v1.1.0：按 provider 选账号 —— linuxdo 填的是 linux.do 论坛登录页，
                 * 必须用 siteAccount（论坛用户名 zgj）；github 填 GitHub 登录页，
                 * 优先 githubUser。两者不同名时用错会导致登录失败。 */
                credAccount = "linuxdo".equals(oauthProvider)
                        ? c.optString("siteAccount", "")
                        : c.optString("githubUser", "");
                githubLogin = "linuxdo".equals(oauthProvider) ? "" : credAccount;   // login 参数仅 GitHub 使用
                // 不将另一平台用户名或密码自动送到当前登录页。
                credPassword = credAccount.isEmpty() ? "" : store.credPassword(credentialId);
                credHasOtp = store.credHasTwofa(credentialId);
                credOtp = credHasOtp ? store.credTwofaCode(credentialId) : "";
            }
        }
        /* 已探测过的站点形态直接复用，省掉一次注定 404 的 POST */
        if (siteKey != null) stateUseGet = "get".equals(store.siteMeta(siteKey, "stateMethod", ""));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        wv = new WebView(this);
        /* 需求3（opus4.8 审计）：绑定「站点×账号」专属 Profile —— 必须在任何
         * getSettings/loadUrl 之前。与离屏 SilentAuth 同名 Profile，授权一次
         * 后该账号会话长期保留在自己的分区里，刷新/签到自动交换不再重复授权。 */
        mProfile = WebViewProfileUtil.bindProfile(wv,
                WebViewProfileUtil.profileNameFor(siteKey, accountKey));
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        /* v1.1.8：可见授权不再伪造 UA。
         * 伪造 UA 只在 OkHttp 侧有意义（原生请求需要一个「不是 OkHttp」的标识）；
         * 而 WebView 是真实内核在渲染，真实内核版本（如 151）+ 伪造声明（如 131）
         * 会形成 JS 指纹与 UA 自相矛盾，反而容易被风控（阿里云 ESA 等）判为异常。
         * 这里一律用系统真实 UA，与用户浏览器条件对齐。 */
        /* v1.1.9 关键修复：Cookie 接受必须开在「本 WebView 绑定的 Profile 分区」上，
         * 不是全局 Default 分区。此前只在 CookieManager.getInstance()（=Default）上
         * setAcceptThirdPartyCookies，而 wv 绑的是非 Default 的 mProfile ——
         * 于是阿里云 WAF 质询页 JS 写入的 acw_sc__v2（首方 Cookie）在 mProfile 分区
         * 被丢弃，每次刷新 WAF 都看不到放行 Cookie → 空白页无限重载（真机实测 10s 内
         * 8 次触发导航熔断）。离屏类（OffscreenSelfProbe/AnyRouterCheckin）早已在各自
         * Profile 的 CookieManager 上显式 setAcceptCookie(true)，唯独可见授权页漏了这步，
         * 正好解释「离屏能续期、可见授权却卡死空白」的不对称。 */
        try {
            CookieManager pcm = WebViewProfileUtil.cookieManagerFor(mProfile);
            pcm.setAcceptCookie(true);
            pcm.setAcceptThirdPartyCookies(wv, true);
        } catch (Exception ignored) {}
        wv.addJavascriptInterface(new Bridge(), "JustSign");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (req == null || !req.isForMainFrame() || req.getUrl() == null) return false;
                if (isAuthInactive()) return true;
                return inspectNavigation(req.getUrl().toString(), "导航请求#" + authNavSeq.incrementAndGet());
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (isAuthInactive()) return;
                if (navigationGuard.onPageStarted(url)) {
                    /* 第一次熔断且发生在站点自身域：多为「失效会话反复 302」或「WAF 放行
                     * Cookie 未落分区」。清一次本站 Cookie（保留 connect.linux.do 论坛登录态）
                     * 并重载 /login，让可交互登录页显示出来，而不是直接判死。 */
                    if (loopRecovery.shouldSelfHeal(url, baseUrl)) {
                        authLog("warn", "授权页反复加载·自愈一次",
                                "主框架加载=" + navigationGuard.totalStarts()
                                        + "；清本站会话 Cookie 后重载登录页（保留论坛登录）");
                        try { WebViewProfileUtil.clearCookiesForUrl(mProfile, baseUrl); } catch (Exception ignored) {}
                        navigationGuard.reset();
                        loginSubmitStarted = false;
                        allowRefill();
                        showTip("正在打开登录页…");
                        final String reloadUrl = baseUrl + "/login";
                        authUrl = reloadUrl;
                        h.post(() -> { if (!isAuthInactive() && wv != null) wv.loadUrl(reloadUrl); });
                        return;
                    }
                    authLog("err", "授权导航熔断", "主框架加载=" + navigationGuard.totalStarts() + "；停止重复导航，未绕过站点防护");
                    showLoadError("登录页仍反复跳转，已停止以保护当前节点。可先在系统浏览器打开该站点确认可正常登录后再点重试；此时尚未授权成功。");
                    return;
                }
                if (inspectNavigation(url, "开始加载#" + authNavSeq.incrementAndGet())) return;
                if (url != null && isAuthorizeEndpoint(url.toLowerCase(java.util.Locale.US))) {
                    evaluateBusiness(v, url, AuthFillJs.authorizeJs(), null);
                }
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (isAuthInactive() || url == null || url.startsWith("about:")) return;
                injectPendingExchange(v, url);
                /* linuxdo 观察者：登录页注入「自动点 LinuxDO 按钮 + 轮询登录态」。
                 * SPA 路由跳转不触发 onPageFinished，但轮询脚本在同一 document 里
                 * 持续运行（__ldObserve 守卫防重复注入）。 */
                if (observingLogin && url != null && url.startsWith(baseUrl)) {
                    /* v1.1.8 诊断：加载完成时再取一份页面事实，与「开始」快照对比，
                     * 可直接看出质询 JS 是否执行、页面是否被替换。 */
                    probePageFacts(v, "完成");
                    authLog("info", "观察者脚本注入", "url 已脱敏；click=linuxdo-btn（跨文档单次）；兑换由站点 SPA 原生完成");
                    evaluateBusiness(v, url, AuthFillJs.linuxdoObserverJs(), attempted -> {
                        if (attempted) authLog("info", "观察者脚本已尝试", "文档门控通过；兑换由站点SPA处理");
                    });
                }
                maybeFill(url);
                /* v0.5.0：2FA 页定期重注入（每 25s）——TOTP 码 30s 窗口轮换后旧码过期，
                 * 且「切换到验证器」点击后输入框可能延迟出现；重置 filled 让 maybeFill
                 * 用新算的 OTP 重新渲染脚本，解决偶发不自动填 2FA。 */
                if (url != null && (url.contains("two-factor") || url.contains("/sessions"))) {
                    h.removeCallbacks(otpReinject);
                    h.postDelayed(otpReinject, 25000);
                }
                if (url != null && inspectNavigation(url, "加载完成#" + authNavSeq.incrementAndGet())) return;
                /* OAuth 确认页自动授权：进入页面即注入扫描/点击脚本（支持 GitHub 与 Linux DO） */
                if (url != null && !authorizeClicked
                        && isAuthorizeEndpoint(url.toLowerCase(java.util.Locale.US))) {
                    authLog("info", "准备扫描 " + providerName() + " 授权确认页", "url 已脱敏");
                    evaluateBusiness(v, url, AuthFillJs.authorizeJs(), null);
                }
                /* v1.0.7：浏览器引导取参 —— 每次首页加载完成都在同源环境里 fetch 一次。
                 * WAF 质询页会先跑 JS 再自动刷新，故首轮可能仍拿不到 JSON；
                 * fetchBootstrapInWebView 外层带重试，几轮后放行 Cookie 落地即成功。 */
                if (bootstrapPending && url != null && url.startsWith(baseUrl)) {
                    evaluateBusiness(v, url, bootstrapJs(), null);
                }
                /* 提示文案与当前真实 URL 严格对齐，不使用排队盲弹 */
                if (url != null && "linuxdo".equals(oauthProvider)
                        && url.toLowerCase(java.util.Locale.US).contains("linux.do")) {
                    String low = url.toLowerCase(java.util.Locale.US);
                    if (isAuthorizeEndpoint(low)) {
                        showTip("已进入授权确认页，正在完成授权…");
                    } else if (low.contains("/login")) {
                        showTip("正在自动填入账号密码并点击登录…");
                        maybeFill(url);
                    }
                }
                maybeResumeAuthorize(url);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = err != null ? String.valueOf(err.getDescription()) : "unknown";
                h.post(() -> showLoadError("网络连接异常: net::" + d));
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse rsp) {
                if (req == null || !req.isForMainFrame()) return;
                int code = rsp != null ? rsp.getStatusCode() : 0;
                String u = (req.getUrl() != null ? req.getUrl().toString() : "").toLowerCase(java.util.Locale.US);
                /* 仅 OAuth 服务商域的质询响应放行（Cloudflare 5秒盾常以 403/503 呈现，
                 * 浏览器内核会自动执行 JS 质询放行）。站点自身域的 403 不属于质询场景。 */
                boolean isProviderHost = u.contains("linux.do") || u.contains("github.com");
                if (isProviderHost && (code == 403 || code == 503)) {
                    authLog("info", "放行安全质询响应", "HTTP " + code + "；host=" + (req.getUrl() != null ? req.getUrl().getHost() : ""));
                    return;
                }
                if (code >= 400) h.post(() -> showLoadError("页面加载失败（HTTP " + code + "）"));
            }
        });
        wv.setVisibility(View.GONE);
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        boot = Ui.col(this);
        boot.setGravity(Gravity.CENTER);
        boot.setBackgroundColor(Color.WHITE);
        boot.addView(new ProgressBar(this));
        TextView title = Ui.tv(this, "正在准备 " + providerName() + " 授权…", 16, Ui.TXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = Ui.dp(this, 18);
        boot.addView(title, tlp);
        bootText = Ui.tv(this, "与站点建立安全会话", 13, Ui.SUB);
        boot.addView(bootText);
        root.addView(boot, new FrameLayout.LayoutParams(-1, -1));

        errorLayer = Ui.col(this);
        errorLayer.setGravity(Gravity.CENTER);
        errorLayer.setBackgroundColor(Color.WHITE);
        errorLayer.setVisibility(View.GONE);
        errorLayer.addView(Ui.tv(this, "授权准备失败", 16, Ui.TXT, true));
        errMsg = Ui.tv(this, "", 13, Ui.RED);
        errMsg.setGravity(Gravity.CENTER);
        errMsg.setPadding(Ui.dp(this, 30), Ui.dp(this, 12), Ui.dp(this, 30), 0);
        errorLayer.addView(errMsg);
        TextView retry = Ui.btn(this, "重试", 15, Ui.white(), Ui.BLUE, 40, 12);
        retry.setOnClickListener(v -> {
            errorLayer.setVisibility(View.GONE);
            boot.setVisibility(View.VISIBLE);
            startAuthFlow();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = Ui.dp(this, 22);
        errorLayer.addView(retry, rlp);
        TextView backTip = Ui.tv(this, "返回键退出", 12, Ui.SUB);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-2, -2);
        blp2.topMargin = Ui.dp(this, 14);
        errorLayer.addView(backTip, blp2);
        root.addView(errorLayer, new FrameLayout.LayoutParams(-1, -1));

        tip = Ui.tv(this, "  " + providerName() + " 授权中 · 已登录将自动完成  ", 12, Color.WHITE);
        tip.setBackgroundColor(0xE6111827);
        tip.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(tip, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        startAuthFlow();
    }

    private boolean inspectNavigation(String url, String stage) {
        if (isAuthInactive()) return true;
        if (url == null) return false;
        /* linuxdo 观察者模式：站点前端自己完成全部 OAuth，App 绝不拦截任何导航
         * （拦截会破坏前端的 state/session 配对）。只记日志。 */
        if (observingLogin) {
            try {
                java.net.URI nu = new java.net.URI(url);
                String h0 = nu.getHost() == null ? "" : nu.getHost().toLowerCase(java.util.Locale.US);
                if (h0.contains(siteHost) || h0.contains("linux.do")) {
                    String safe = "https://" + h0 + (nu.getPath() == null ? "/" : nu.getPath());
                    /* 去重只看「地址」，不能把 stage 里的自增序号算进去
                     * （原实现 sig=stage+safe 导致每轮都不同，去重完全失效，
                     * 实测刷出 6756 条等价日志淹没真正的问题线索）。 */
                    String sig = safe;
                    if (!sig.equals(lastNavLog)) {
                        lastNavLog = sig;
                        authLog("info", "授权跳转·" + stage + "（观察）", safe);
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }
        OAuthCallback.Result r = OAuthCallback.parse(url, siteHost, expectedOauthState);
        if (!r.validUrl) return false;

        /* 关键链路只记安全摘要，绝不把 OAuth code/state 写入日志。
         * GitHub 导航也记 host/path（不记 query），用于确认是否真正到达授权确认页。 */
        boolean oauthHostNav = false;
        String oauthHostSafe = "";
        try {
            java.net.URI nu = new java.net.URI(url);
            String h0 = nu.getHost() == null ? "" : nu.getHost().toLowerCase(java.util.Locale.US);
            if ("github.com".equals(h0)) {
                oauthHostNav = true;
                oauthHostSafe = "https://github.com" + (nu.getPath() == null ? "/" : nu.getPath());
            } else if ("connect.linux.do".equals(h0) || "linux.do".equals(h0)) {
                oauthHostNav = true;
                oauthHostSafe = "https://" + h0 + (nu.getPath() == null ? "/" : nu.getPath());
            }
        } catch (Exception ignored) {}
        if (oauthHostNav) hasVisitedOAuthProvider = true;
        if (r.sameHost || r.callbackPath || oauthHostNav) {
            String safeUrl = oauthHostNav ? oauthHostSafe : r.safe;
            String sig = stage + "|" + safeUrl;
            if (!sig.equals(lastNavLog)) {
                lastNavLog = sig;
                authLog("info", "授权跳转·" + stage,
                        safeUrl + " callbackPath=" + r.callbackPath
                                + " code=" + r.hasCode + " state=" + r.hasState
                                + " stateMatch=" + r.stateMatches);
            }
        }
if (exchanging) return r.sameHost;
        if (r.shouldExchange()) {
            /* v0.6.7：全局闸门——与 SilentAuth 共享，同一 accountKey/code 只交换一次。
             * 抢占失败说明 SilentAuth 后台交换仍在途或该 code 已消费，直接丢弃本回调，
             * 绝不二次建 session（just 站 AUTH_SESSION_LIMIT 根因）。
             * v1.0.7 终版（实测三轮对照）：WAF 站的 state 与「state 响应的 session」严格
             * 配对，且 SPA 会持续轮换 session —— OkHttp 无论带哪个快照都可能失配。
             * 唯一稳定路径：放行回调页加载（同源站点域），onPageFinished 注入兑换
             * fetch —— 内核自动带 CookieManager 实时 session（此刻 SPA 初始化请求
             * 已发完，session 即存有 state 的那个）+ 全套 WAF cookie，天然过质询。 */
            if (!SilentAuth.acquireExchange(accountKey, r.code)) {
                authLog("info", "跳过重复交换", "本轮已有交换在途或该授权码已消费");
                return false;
            }
            exchanging = true;
            showTip("已获取授权码，正在交换凭证…");
            authLog("info", "放行回调页，由同源上下文完成兑换",
                    r.safe + "；交换端点=/api/oauth/" + oauthProvider + "；state 校验通过");
            final String fc = r.code, fs = r.state;
            pendingExchangeJs = "(function(){"
                    + "fetch('/api/oauth/" + oauthProvider + "?code=" + URLEncoder.encode(fc) + "&state=" + URLEncoder.encode(fs == null ? "" : fs) + "',"
                    + "{credentials:'include',headers:{'Accept':'application/json'}})"
                    + ".then(function(r){return r.text().then(function(t){return {s:r.status,b:t};});})"
                    + ".then(function(res){window.JustSign.onExchangeDone(res.s, res.b);})"
                    + ".catch(function(err){window.JustSign.onExchangeFailed(String(err));});})();";
            return false;   /* 放行导航：加载回调页建立同源上下文 */
        }
        if (r.badState()) {
            authLog("err", "拒绝异常授权回调", r.safe + "；state 缺失或不匹配");
            showLoadError("授权回调校验失败，请点重试重新授权");
            return true;
        }
        /* v1.0.7 严防误杀：只有授权已进入等待回调阶段（expectedOauthState 已就绪且非取参引导），
         * 且确实属于回调入口（带 error 参数、或属于 /oauth/* 回调路径、或已去过 OAuth 服务商）
         * 时，!hasCode 才能判为授权被拒。普通加载首页（/）或控制台（/console）绝不拦截报错！ */
        boolean isAwaitingCallback = !expectedOauthState.isEmpty() && !bootstrapPending;
        boolean isRealCallback = r.callbackPath || !r.error.isEmpty() || hasVisitedOAuthProvider;
        if (isAwaitingCallback && r.missingCode() && isRealCallback) {
            /* 回调已经在 shouldOverride/onPageStarted 阶段提前拦截。按诊断状态分类处理：
             * A. authorizeScan 未上报 = 授权页脚本还没执行 GitHub 就返回拒绝（导航时序问题）
             *    → 自动重试一次全新 state（旧 state 已被 GitHub 消费/作废，不复用）。
             * B. 已点击授权仍被拒 = GitHub/应用授权策略明确拒绝 → 停手转人工，绝不循环建 state。 */
            String why = r.error.isEmpty() ? "回调未携带授权码" : r.error;
            String safeDesc = r.errorDescription;
            if (safeDesc.length() > 160) safeDesc = safeDesc.substring(0, 160);
            authLog("err", "未获取到授权码", r.safe + "；error=" + why
                    + (safeDesc.isEmpty() ? "" : "；description=" + safeDesc)
                    + "；scanStarted=" + authorizeScanStarted
                    + "；clicked=" + authorizeClicked);
            if ("access_denied".equals(r.error) && !authorizeScanStarted
                    && !exchanging && deniedRetries < 1) {
                deniedRetries++;
                authorizeScanStarted = false;
                authorizeClicked = false;
                authLog("info", "授权被拒·自动重试",
                        "授权页脚本未执行即被拒（导航时序问题），正重新获取全新授权会话重试");
                showTip("授权时序异常，自动重试一次…");
                /* v1.1.6：原来固定等 600ms 再重试。改为轮询可靠事实「内核已空闲」——
                 * 加载进度到 100 且不在过渡/空白页才重试；3s 上限仅作安全网。 */
                final Runnable retryFlow = () -> { if (!isAuthInactive()) startAuthFlow(); };
                Waiters.until(h, () -> wv != null && wv.getProgress() >= 100
                        && wv.getUrl() != null && !wv.getUrl().startsWith("about:"),
                        3000, retryFlow, retryFlow);
                return true;
            }
            if ("access_denied".equals(r.error) && authorizeClicked) {
                authLog("err", providerName() + " 明确拒绝授权",
                        "已点击授权按钮仍被拒，" + providerName() + "/应用策略拒绝，不再自动重试");
            }
            showLoadError(r.error.equals("access_denied")
                    ? (authorizeClicked
                        ? providerName() + " 拒绝了本次授权，请检查该应用的授权设置后重试"
                        : "授权服务返回拒绝，请点重试重新发起授权")
                    : "未获取到授权码，请点重试重新授权");
            return true;
        }
        return false;
    }

    /** v0.6.6：把站点交换失败的 code/message 映射为可操作的中文提示（不暴露技术术语）。 */
    private static String redactMessage(String message) {
        if (message == null || message.isEmpty()) return "-";
        String s = message.replaceAll("(?i)(code|state|token|cookie|secret)\\s*[:=]\\s*[^\\s,;]+", "$1=<redacted>");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private String mapExchangeError(String code, String msg) {
        String c = code == null ? "" : code.trim().toUpperCase(java.util.Locale.US);
        switch (c) {
            case "AUTH_SESSION_LIMIT":
                return "该账号在站点的登录会话数已达上限。请到站点网页端退出多余的登录设备，或稍后再试；也可在本应用里等待旧会话自动过期后重新授权。";
            case "ACCESS_DENIED":
                return "站点拒绝了本次授权。请点重试；若仍失败，请确认所选账号与站点绑定的 " + providerName() + " 账号一致。";
            case "STATE_MISMATCH":
            case "INVALID_STATE":
                return "授权校验未通过（可能多个授权同时进行）。请稍等几秒后点重试。";
            case "RATE_LIMIT":
            case "TOO_MANY_REQUESTS":
                return "站点请求过于频繁，请稍等几分钟后点重试。";
            default:
                break;
        }
        String m = msg == null ? "" : msg.trim();
        if (m.isEmpty() || "conflict".equalsIgnoreCase(m)) {
            return "站点未能完成本次授权" + (c.isEmpty() ? "" : "（" + c + "）") + "。请稍后点重试。";
        }
        return "授权失败：" + m;
    }

    private void authLog(String level, String summary, String detail) {
        try {
            new Store(this).opLog(siteKey, accountKey, "授权链路", level,
                    summary, detail == null ? "" : detail, "auto");
        } catch (Exception ignored) {}
    }

    private void exchange(String provider, String code, String state) { exchange(provider, code, state, null, false); }
    /** wafRetryCookie：WAF 质询重试时携带的新 cookie；viaWafRetry=true 只重试一次。 */
    private void exchange(String provider, String code, String state, String wafRetryCookie, boolean viaWafRetry) {
        Response resp = null;
        String err = null;
        try {
            authLog("info", "开始交换授权凭证",
                    "endpoint=/api/oauth/" + provider + "；code/state 已脱敏");
            OkHttpClient c = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
                    new Store(this).config().optJSONObject("proxy"));
            Request.Builder rb = new Request.Builder()
                    .url(baseUrl + "/api/oauth/" + provider
                            + "?code=" + URLEncoder.encode(code, "UTF-8")
                            + "&state=" + URLEncoder.encode(state == null ? "" : state, "UTF-8"))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36");
            /* v1.0.7：兑换必须落在「取到 state 的那个会话」里，否则旧版 New API
             * 判 state 无效；WAF 站还会直接返回质询页。两处来源：
             *   stateSessionCookie —— state 响应下发的 session（与 state 严格配对）；
             *   wafCookie         —— WebView 预热时缓存的 WAF 放行键（acw_sc__v2 等）。
             * WAF 重试时直接用 wafRetryCookie（已含新算的 acw_sc__v2）。 */
            String wafCk = "";
            try { wafCk = new Store(this).siteMeta(siteKey, "wafCookie", ""); } catch (Exception ignored) {}
            String ck = wafRetryCookie != null && !wafRetryCookie.isEmpty()
                    ? wafRetryCookie
                    : Engine.mergeCookieHeaders(wafCk == null ? "" : wafCk, stateSessionCookie);
            if (!ck.isEmpty()) rb.header("Cookie", ck);
            rb.header("Origin", baseUrl).header("Referer", baseUrl + "/");
            authLog("info", "兑换请求携带会话",
                    "cookieLen=" + ck.length() + "；waf=" + (wafCk != null && !wafCk.isEmpty()) + "；stateSession=" + !stateSessionCookie.isEmpty());
            resp = c.newCall(rb.build()).execute();
            int http = resp.code();
            String body = resp.body() != null ? resp.body().string() : "";
            /* v1.0.7 WAF 自愈：兑换请求可能收到质询页（HTTP 200 + arg1 混淆 JS）。
             * 实测：质询页 JS 可在 JS 引擎执行算出 acw_sc__v2，带新 cookie 重试即过。
             * 只重试一次，绝不循环。 */
            if (http == 200 && body.contains("var arg1=") && !viaWafRetry) {
                authLog("warn", "兑换被 WAF 质询，本地计算放行 Cookie 重试", "bodyLen=" + body.length() + "B");
                /* 阿里云 acw_sc__v2 算法（逆向实测定稿）：固定置换表 + 固定密钥 XOR。
                 * 质询页每次只有 arg1 变化，表与密钥恒定 —— 纯 Java 计算，无需 JS 引擎。
                 * 已实测：算出值带请求即过 WAF（对照实验 [3] status 200 JSON）。 */
                try {
                    String arg1 = body.substring(body.indexOf("var arg1='") + 10);
                    arg1 = arg1.substring(0, arg1.indexOf('\''));
                    String acw = calcAcwScV2(arg1);
                    if (!acw.isEmpty()) {
                        String retryCk = Engine.mergeCookieHeaders(ck, "acw_sc__v2=" + acw);
                        authLog("info", "WAF 放行 Cookie 已算出，重试兑换", "acwLen=" + acw.length());
                        if (resp != null) try { resp.close(); } catch (Exception ignored) {}
                        exchange(provider, code, state, retryCk, true);
                        return;
                    }
                } catch (Exception e) {
                    authLog("err", "WAF 质询解析失败", e.getClass().getSimpleName());
                }
                err = "WAF 质询计算失败（无法算出放行 Cookie）";
            }
            /* opus4.8 审计·B-02：New API 系真凭据是 Set-Cookie session，必须抓取 */
            final String sc = extractCookies(resp.headers("Set-Cookie"));
            boolean ok2xx = http >= 200 && http < 300;
            /* 失败响应也只记录结构化错误字段，禁止写原始响应体（可能回显敏感参数）。 */
            String diag;
            if (ok2xx) {
                diag = "HTTP " + http + "；body=" + body.length() + "B；setCookie=" + !sc.isEmpty();
            } else {
                String errCode = "", errMessage = "";
                try {
                    JSONObject eo = new JSONObject(body);
                    errCode = eo.optString("code", "");
                    errMessage = eo.optString("message", "");
                } catch (Exception ignored) {}
                diag = "HTTP " + http + "；body=" + body.length() + "B；code="
                        + (errCode.isEmpty() ? "-" : errCode) + "；message=" + redactMessage(errMessage);
            }
            authLog(ok2xx ? "info" : "err", "授权交换响应", diag);
            if (body.trim().isEmpty()) err = "站点返回空响应（HTTP " + http + "）";
            else {
                final String fb = body;
                h.post(() -> handleBundle(fb, sc));
                return;
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
        final String fe = err;
        h.post(() -> {
            exchanging = false;
            SilentAuth.releaseExchange(accountKey);
            showLoadError("授权交换失败: " + fe);
        });
    }

    /** 阿里云 WAF acw_sc__v2 计算（逆向实测定稿，Python 对照验证通过）：
     * 固定置换表重排 arg1 字符 → 与固定密钥逐字节 XOR。质询页每次仅 arg1 变化。 */
    private static String calcAcwScV2(String arg1) {
        if (arg1 == null || arg1.isEmpty()) return "";
        int[] perm = {0xf,0x23,0x1d,0x18,0x21,0x10,0x1,0x26,0xa,0x9,0x13,0x1f,0x28,0x1b,0x16,0x17,
                0x19,0xd,0x6,0xb,0x27,0x12,0x14,0x8,0xe,0x15,0x20,0x1a,0x2,0x1e,0x7,0x4,
                0x11,0x5,0x3,0x1c,0x22,0x25,0xc,0x24};
        String key = "3000176000856006061501533003690027800375";
        char[] q = new char[perm.length];
        for (int x = 0; x < arg1.length(); x++) {
            for (int z = 0; z < perm.length; z++) {
                if (perm[z] == x + 1) q[z] = arg1.charAt(x);
            }
        }
        String u = new String(q);
        int len = Math.min(u.length(), key.length());
        StringBuilder v = new StringBuilder();
        for (int i = 0; i + 1 < len; i += 2) {
            int a = Integer.parseInt(u.substring(i, i + 2), 16)
                    ^ Integer.parseInt(key.substring(i, i + 2), 16);
            v.append(String.format("%02x", a));
        }
        return v.toString();
    }

    private void handleBundle(String json, String setCookie) {
        if (isAuthInactive()) return;
        try {
            JSONObject r = new JSONObject(json);
            JSONObject d = r.optJSONObject("data");
            /* v1.1.0：linuxdo 站为 cookie 型会话（实测 /api/user/self data 无 token）。
             * 只要 success=true 且有会话 Cookie 即收凭据；token 仅尽力而为。 */
            boolean cookieProvider = "linuxdo".equals(oauthProvider);
            if (r.optBoolean("success") && (d != null || (cookieProvider && !setCookie.isEmpty()))) {
                /* opus4.8 审计：token 尽力而为（JSON null/缺失视为无，三字段回退）；
                 * cookie 型站（AgentRouter）以 setCookie 为真凭据。 */
                String token = "";
                for (String k : new String[]{"access_token", "accessToken", "token"}) {
                    if (d.has(k) && !d.isNull(k)) {
                        Object at = d.get(k);
                        if (at instanceof String) {
                            String sv = ((String) at).trim();
                            if (!sv.isEmpty() && !"null".equals(sv)) { token = sv; break; }
                        }
                    }
                }
                /* 身份标识：data 直接是用户对象（AgentRouter 型）或 data.user */
                String login = d.optString("username", "");
                if (login.isEmpty() || "null".equals(login)) login = "";
                if (login.isEmpty()) {
                    JSONObject usr = d.optJSONObject("user");
                    if (usr != null) {
                        login = usr.optString("username", "");
                        if (login.isEmpty() || "null".equals(login)) login = usr.optString("login", "");
                        if (login == null || "null".equals(login)) login = "";
                    }
                }
                if (!token.isEmpty() || !setCookie.isEmpty()) {
                    finishOk(d, token, setCookie, login);
                    return;
                }
                /* 两者皆无：诊断（data keys）帮助适配 */
                java.util.Iterator<String> ks = d.keys();
                StringBuilder kb = new StringBuilder();
                int kn = 0;
                while (ks.hasNext() && kn < 20) { kb.append(ks.next()).append(','); kn++; }
                exchanging = false;
                SilentAuth.releaseExchange(accountKey);
                showLoadError("授权响应缺少会话凭据（data keys: " + kb + "…）");
            }
            String msg = r.optString("message", "交换失败");
            String code = r.optString("code", "");
            /* v0.6.6：站点交换失败时 message 常为笼统 "Conflict"，真正原因在 code 字段。
             * 已实测 justwoker 返回 AUTH_SESSION_LIMIT（该账号活跃会话数超上限）。
             * 按 code 给出可操作中文提示，而非笼统术语。 */
            String friendly = mapExchangeError(code, msg);
            exchanging = false;
            SilentAuth.releaseExchange(accountKey);
            showLoadError(friendly);
        } catch (Exception e) {
            exchanging = false;
            SilentAuth.releaseExchange(accountKey);
            showLoadError("授权响应解析失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ("（" + e.getMessage() + "）"))
                    + "；响应长度=" + (json == null ? 0 : json.length()) + "B"
                    + (json != null && json.length() > 0 && json.trim().startsWith("<") ? "；响应为 HTML（非 JSON）" : ""));
        }
    }

    private static boolean isAuthorizeEndpoint(String u) {
        if (u == null) return false;
        return u.contains("/oauth/authorize") || u.contains("/oauth2/authorize");
    }

    private void maybeResumeAuthorize(String url) {
        if (isAuthInactive() || exchanging || url == null || authUrl.isEmpty()) return;
        String u = url.toLowerCase(java.util.Locale.US);
        /* v1.0.7：按 provider 识别服务商首页登录后未自动重定向的状态。 */
        boolean isProviderHost = "github".equals(oauthProvider)
                ? u.startsWith("https://github.com")
                : (u.startsWith("https://connect.linux.do") || u.startsWith("https://linux.do"));
        if (!isProviderHost) return;
        if (u.contains("/login") || u.contains("/session") || u.contains("two-factor")
                || isAuthorizeEndpoint(u) || u.contains("device")
                || u.contains("verified-device") || u.contains("sudo")) return;
        if (reauthTries >= 2) {
            showLoadError(providerName() + " 已登录但未跳回站点，请点重试。");
            return;
        }
        reauthTries++;
        showTip(providerName() + " 已登录，正在返回站点完成授权…");
        /* v1.1.6：原来固定等 400ms 再回站点。改为轮询「内核已空闲」；
         * 3s 上限仅作安全网，正常由条件先满足。 */
        final Runnable goBack = () -> { if (!isAuthInactive() && wv != null) wv.loadUrl(authUrl); };
        Waiters.until(h, () -> wv != null && wv.getProgress() >= 100
                && wv.getUrl() != null && !wv.getUrl().startsWith("about:"),
                3000, goBack, goBack);
    }

    private void maybeFill(String url) {
        /* v1.1.6：不再用「2.5s 后重置 filled」这种猜时间的守卫。
         * 改为把「本页已注入」绑定到具体 URL —— 同一 URL 注入过就跳过（防重复注入），
         * URL 一变（跳转 / SPA 路由）自然允许重新注入。
         * 导航是事实，时间不是。 */
        if (isAuthInactive() || loginSubmitStarted || url == null || url.startsWith("about:")) return;
        if (url.equals(filledUrl)) return;
        if (credAccount.isEmpty() && credPassword.isEmpty()) return;
        String u = url.toLowerCase(java.util.Locale.US);
        /* 授权确认页排除 */
        if (isAuthorizeEndpoint(u)) return;
        boolean loginish = u.contains("github.com/login") || u.contains("/sessions")
                || u.contains("two-factor") || u.contains("/signin")
                || u.contains("/register")
                || u.contains("linux.do/login")
                || (u.startsWith("https://connect.linux.do/") && u.contains("/login"));
        if (!loginish) return;
        filled = true;
        filledUrl = url;
        try {
            boolean autoSubmit = new Store(this).uiPref("autoSubmitLogin", true);
            String otp = credHasOtp ? new Store(this).credTwofaCode(credentialId) : "";
            if (!otp.isEmpty()) credOtp = otp;
            boolean noPwd = credPassword == null || credPassword.isEmpty();
            evaluateBusiness(wv, url, AuthFillJs.render(credAccount, credPassword, otp, autoSubmit && !noPwd), attempted -> {
                if (!attempted && url.equals(filledUrl)) { filledUrl = ""; filled = false; }
            });
            if (noPwd) {
                showTip("仅填入账号，未存密码 —— 请手动输入密码，或到「设置 → 凭据库」补录后重试");
                new Store(this).opLog(siteKey, accountKey, "自动填充", "err",
                        "凭据库无密码，仅填账号", "别名 " + alias + "：密码为空（需在凭据库补录）", "user");
            } else {
                showTip(credHasOtp
                        ? (autoSubmit ? "已填入账号密码与 2FA，正在准备登录…" : "已注入账号密码与 2FA 动态码")
                        : (autoSubmit ? "已自动填入账号密码，完成人机验证后将自动提交…" : "已注入账号密码（无 2FA）"));
            }
        } catch (Exception ignored) {}
    }

    private void startAuthFlow() {
        if (done || isFinishing()) return;
        authAttempt++;
        loadFailed = false;
        lastAuthError = "";
        setResult(Activity.RESULT_CANCELED);
        navigationGuard.reset();
        loopRecovery.reset();
        lastNavLog = "";
        authNavSeq.set(0);
        h.removeCallbacks(loginObserverTick);
        h.removeCallbacks(otpReinject);
        if (wv != null) wv.stopLoading();
        expectedOauthState = "";
        hasVisitedOAuthProvider = false;
        exchanging = false;
        loginSubmitStarted = false;
        lastNavLog = "";
        stateSessionCookie = "";
        stateFromWebView = false;
        pendingExchangeJs = null;
        observingLogin = false;
        authLog("info", "开始可见授权", "siteHost=" + siteHost + "；provider=" + oauthProvider
                + "；账号使用专属会话分区");
        applyProxyThen(() -> new Thread(this::authFlowNetwork, "auth-flow").start());
    }

    private void authFlowNetwork() {
        /* v1.0.7 终版架构（五轮实测对照定稿）：
         * WAF 站（linuxdo 系）的 state 与「state 响应的 session」严格配对，SPA 持续
         * 轮换 session —— App 侧无论 OkHttp 还是 WebView fetch 取 state，到兑换时
         * session 都已失配（实测 S0/S1/同源 fetch 三种路径全部 403）。
         * 唯一稳定路径：完全模拟浏览器登录 —— WebView 直接开站点登录页，注入脚本
         * 自动点「使用 LinuxDO 继续」按钮，站点前端自己完成全部 OAuth（取 state→
         * 跳转→回调→兑换→跳 console，session 全程由内核维护，天然配对）。
         * 我们只轮询 /api/user/self 观察登录结果，成功即收凭据落库。
         * linux.do 侧登录态/人机验证由 Profile 会话与用户完成，App 不代填。 */
        /* v1.1.0 重写（实测定稿）：同构 GitHub 流程 —— 会话保留在「站点×账号」专属
         * Profile 里，首次人工过盾+登录，之后 LinuxDO/论坛会话长期有效，再次授权
         * 自动直通授权确认页。v1.0.8 的「每次清空全部 Cookie」会把论坛登录态一起
         * 清掉，导致每次授权都要重过两道 CF + 登录 + 答题，任何一环超时即整体失败。
         * state/兑换完全交由站点 SPA 原生完成（实测：点按钮→GET /api/oauth/state→
         * window.open connect.linux.do→允许→回 /oauth/oidc→SPA 自行兑换→进 console），
         * App 只观察 /api/user/self 收取登录结果（cookie 型站点，凭据=会话 Cookie）。 */
        if ("linuxdo".equals(oauthProvider)) {
            h.post(() -> {
                if (isAuthInactive()) return;
                boot.setVisibility(View.GONE);
                wv.setVisibility(View.VISIBLE);
                authUrl = baseUrl + "/login";
                observingLogin = true;
                observeStartMs = System.currentTimeMillis();
                boolean haveSession = false;
                try {
                    /* v1.1.10：只有「非 WAF 的站点 Cookie」或论坛登录态才算已保存会话。
                     * 阿里云 WAF 的 acw_sc__v2 在登录前就存在，之前误当会话 → 明明 401
                     * 过期还显示「正在自动完成授权」并复用死会话。 */
                    String snap = WebViewProfileUtil.cookieHeader(mProfile, baseUrl);
                    haveSession = AuthLoopRecovery.hasNonWafSiteCookie(snap);
                    if (!haveSession) haveSession = WebViewProfileUtil.linuxdoLoggedIn(mProfile);
                } catch (Exception ignored) {}
                if (haveSession) {
                    showTip("检测到已保存的登录会话，正在自动完成授权…");
                    authLog("info", "linuxdo 复用会话", "账号分区已有会话 Cookie，已登录则自动直通确认页");
                } else {
                    showTip("首次授权：请在页面完成人机验证与登录（仅需一次）…");
                    authLog("info", "linuxdo 首次授权", "账号分区无会话 Cookie，需人工登录一次；会话将长期保留");
                }
                wv.loadUrl(authUrl);
                /* Java 侧观察器：等「站点登录页真的加载完」再开始观察，而不是猜 3s。
                 * 参考对象＝加载进度到 100 且地址已落在本站；10s 上限仅作安全网，
                 * 真没加载完也照样进观察循环，不让流程卡死。 */
                h.removeCallbacks(loginObserverTick);
                final Runnable startObserve = () -> { if (!isAuthInactive()) loginObserverTick.run(); };
                Waiters.until(h, () -> {
                    if (wv == null) return false;
                    String u = wv.getUrl();
                    return wv.getProgress() >= 100 && u != null && u.startsWith(baseUrl);
                }, 10000, startObserve, startObserve);
            });
            return;
        }
        String state = null, err = null, clientId = "";
        try {
            Store store = new Store(this);
            OkHttpClient c = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
                    store.config().optJSONObject("proxy"));

            try {
                JSONObject st = getJson(c, baseUrl + "/api/status");
                JSONObject d = st == null ? null : st.optJSONObject("data");
                if (d != null) {
                    /* v1.0.7：clientId 跟随站点 provider —— New API 统一下发 <provider>_client_id。
                     * 实测 anyrouter 同时有 github_client_id 与 linuxdo_client_id，
                     * 取错那个会把用户送去已停用的 GitHub 登录。 */
                    clientId = d.optString(oauthProvider + "_client_id", "");
                    if (clientId.isEmpty() && "github".equals(oauthProvider))
                        clientId = d.optString("github_client_id", "");
                    String tsk = d.optString("turnstile_site_key", "");
                    if (!tsk.isEmpty() && siteKey != null) store.putSiteMeta(siteKey, "turnstileSiteKey", tsk);
                    long unit = d.optLong("quota_per_unit", 0);
                    if (unit > 0 && siteKey != null) store.putSiteMeta(siteKey, "quotaPerUnit", unit);
                }
            } catch (Exception ignored) {}

            for (int attempt = 0; attempt < 3 && state == null; attempt++) {
                Response resp = null;
                try {
                    /* 两种站点形态：多数 New API 站用 POST，AgentRouter 一类只认
                     * GET ?mode=login（POST 直接 404 Invalid URL）。attempt 0 先 POST，
                     * 之后回退 GET；两者都返回合法 JSON，故不能只靠解析失败来判别。 */
                    boolean useGet = stateUseGet || attempt > 0;
                    Request.Builder rb = new Request.Builder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Mobile Safari/537.36")
                            .header("Accept", "application/json");
                    /* v1.0.7：WAF 站（如 linuxdo 系）OkHttp 直连会被质询页拦截。
                     * 带上 WebView 预热时缓存的 WAF 放行 Cookie（acw_sc__v2 等），
                     * 实测带它即可拿到真实 JSON。 */
                    String wafCk = "";
                    try { wafCk = new Store(this).siteMeta(siteKey, "wafCookie", ""); } catch (Exception ignored) {}
                    if (wafCk != null && !wafCk.isEmpty()) rb.header("Cookie", wafCk);
                    if (useGet) {
                        rb.url(baseUrl + "/api/oauth/state?mode=login").get();
                    } else {
                        rb.url(baseUrl + "/api/oauth/state")
                                .post(RequestBody.create(
                                                                                "{\"provider\":\"" + oauthProvider + "\",\"intent\":\"login\"}",
                                        MediaType.parse("application/json")));
                    }
                    resp = c.newCall(rb.build()).execute();
                                        int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    /* v1.0.7：旧版 New API（实测 anyrouter 的 data 是裸字符串，非新版的
                     * {flow_token,expires_at}）把 state 存在服务端 session 里。
                     * 必须抓下这次响应的 Set-Cookie，兑换时带回，否则判「state 无效」。 */
                    String scState = extractCookies(resp.headers("Set-Cookie"));
                    if (!scState.isEmpty())
                        stateSessionCookie = Engine.mergeCookieHeaders(stateSessionCookie, scState);
                    if (code == 429) {
                        String ra = resp.header("Retry-After", "");
                        err = "站点限流（429）" + (ra.isEmpty() ? "" : "，建议等待 " + ra + " 秒");
                        if (attempt < 2) { try { Thread.sleep(4000L * (attempt + 1)); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    /* 该站不支持 POST 此路由：立即改用 GET 重试，并记住形态 */
                    if (code == 404 && !useGet) {
                        stateUseGet = true;
                        if (siteKey != null) store.putSiteMeta(siteKey, "stateMethod", "get");
                        err = "站点不支持 POST 取授权会话，已改用 GET 重试";
                        continue;
                    }
                    if (body.trim().isEmpty()) {
                        err = "站点返回空响应（HTTP " + code + "）";
                        if (attempt < 2) { try { Thread.sleep(2000L); } catch (Exception ignored) {} continue; }
                        break;
                    }
                    JSONObject j;
                    try { j = new JSONObject(body); }
                    catch (Exception pe) {
                        err = "站点返回非 JSON 数据（HTTP " + code + "，长度 " + body.length() + "B）";
                        if (!useGet) { stateUseGet = true; continue; }
                        break;
                    }
                    if (code == 200 && j.optBoolean("success")) {
                        Object d = j.opt("data");
                        if (d instanceof String) state = (String) d;
                        else if (d instanceof JSONObject) state = ((JSONObject) d).optString("flow_token", null);
                    }
                    if (state == null || state.isEmpty()) {
                        state = null;
                        err = j.optString("message", "");
                        if (err.isEmpty()) err = "state 获取失败（HTTP " + code + "）";
                        break;
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
                    if (attempt < 2) { try { Thread.sleep(1500L); } catch (Exception ignored) {} }
                } finally {
                    if (resp != null) try { resp.close(); } catch (Exception ignored) {}
                }
            }

                        /* v1.0.7：OkHttp 路径没拿全（被 WAF 质询挡住 / state 需与 session 同源）时，
             * 改用「真实浏览器内核」在同源环境下把 status + state 一起取回。
             * 已验证的 5 个站点两条都能拿到，走不到这里 → 行为零变化。 */
            if (clientId.isEmpty() || state == null || state.isEmpty()) {
                authLog("info", "网络取授权参数不全，改用浏览器会话获取",
                        "needClientId=" + clientId.isEmpty()
                                + "；needState=" + (state == null || state.isEmpty())
                                + "；provider=" + oauthProvider);
                String[] wr = fetchBootstrapInWebView();
                if (wr != null) {
                    if (clientId.isEmpty() && wr[0] != null && !wr[0].isEmpty()) clientId = wr[0];
                    if ((state == null || state.isEmpty()) && wr[1] != null && !wr[1].isEmpty()) {
                        state = wr[1];
                        stateFromWebView = true;
                    }
                    /* stateSessionCookie 已在 onOAuthBootstrap 回调瞬间抓取冻结快照
                     * （session S1 + acw_sc__v2），此处不再补充，防止 SPA 后续轮换值混入。 */
                }
            }
            final String cid = clientId, st2 = state, er = err;
            h.post(() -> {
                if (isAuthInactive()) return;
                if (st2 == null) { showLoadError("获取授权会话失败: " + (er == null ? "未知原因" : er)); return; }
                if (cid == null || cid.isEmpty()) {
                    showLoadError("站点未提供 " + oauthProvider + " 授权配置（" + baseUrl
                            + "），请确认该站实际支持的登录方式后重试");
                    return;
                }
                expectedOauthState = st2;
                authLog("info", "已获取授权会话", "clientId=" + !cid.isEmpty() + "；stateMethod=" + (stateUseGet ? "GET" : "POST") + "；state 已脱敏");
                boot.setVisibility(View.GONE);
                wv.setVisibility(View.VISIBLE);
                AlphaAnimation a = new AlphaAnimation(0f, 1f);
                a.setDuration(220);
                wv.startAnimation(a);
                reauthTries = 0;
                authUrl = buildAuthorizeUrl(cid, st2);
                /* 会话切换策略（opus4.8 审计·需求3 修订版）：
                 * Profile 隔离后，本账号的 Cookie 分区只属于自己 ——
                 * · 首次授权（账号无 token / 无 githubAccount）：分区是空的，
                 *   无需清 Cookie（本来就没有旧会话），直接加载走登录页；
                 *   无密码凭据同样直接加载（靠 login 参数或人工选择账号）。
                 * · 已授权过：分区里就是本账号的会话，直接加载 → GitHub 自动
                 *   302 回站点 → exchange 自动完成，全程无需再输密码。
                 * 注意：绝不清全局 CookieManager（会破坏其他账号分区/Default）。 */
                showTip(mProfile != null ? "使用账号专属会话分区" : "正在准备授权…");
                wv.loadUrl(authUrl);
            });
        } catch (Throwable t) {
            final String er = "授权准备异常: " + t.getMessage();
            h.post(() -> { if (!isFinishing()) showLoadError(er); });
        }
    }

    /* 首次授权用户确认标志（v0.3.7 无锚点兜底） */
    private volatile boolean idConfirmed = false;
    /* v0.5.0：2FA 页 25s 重注入（新 OTP） */
    private final Runnable otpReinject = new Runnable() {
        @Override public void run() {
            if (isAuthInactive()) return;
            allowRefill();   // 允许 maybeFill 重新注入（新 OTP 已在 render 时重算）
        }
    };

    private void finishOk(JSONObject bundle, String token, String setCookie, String loginFromResp) {
        if (isAuthInactive()) return;
        /* v0.6.7：授权成功——释放该账号的在途标记（code 已消费，保留在 USED_CODES 不复用）。 */
        SilentAuth.releaseExchange(accountKey);
        Store store = new Store(this);
        /* 登录名优先用授权响应里的真实 username（AgentRouter 型 data 直层），
         * 回退旧结构 data.user，最后回退 bundle.username */
        String login = (loginFromResp != null && !loginFromResp.isEmpty()) ? loginFromResp : null;
        if (login == null) {
            JSONObject userObj = bundle.optJSONObject("user");
            if (userObj != null) {
                login = userObj.optString("username", "");
                if (login.isEmpty()) login = userObj.optString("login", "");
                if (login.isEmpty() || "null".equals(login)) login = null;
            }
        }
        if (login == null && bundle.has("username") && !bundle.isNull("username")) {
            String u0 = bundle.optString("username", "");
            if (!u0.isEmpty() && !"null".equals(u0)) login = u0;
        }
                /* 身份校验 B1（opus4.8 复审·锚点分层）：
         * 1) 强判据：站点响应含 github_id（New API 系 = GitHub 数字 ID，字符串）
         *    且凭据已缓存 githubId → 两者必须相等，不等即拒（不看 username，
         *    因为站内名是 github_<站内id> 与 GitHub 名无关）。
         * 2) 弱判据（fallback）：无 github_id 数据时回退 username 比对（token 型旧路径）。
         * 3) 都拿不到（无 github_id 数据且缓存缺失）→ 跳过校验，不误拒。
         * 拒绝后不置 done，用户可在授权页切换账号重试。 */
        String expect = expectGithubLogin();
        String respGhId = "";
        Object gidO = bundle.opt("github_id");
        if (gidO != null) {
            respGhId = String.valueOf(gidO).trim();
            if ("null".equals(respGhId)) respGhId = "";
        }
        /* 锚点链 v0.3.7：github_id → github_user_id → null（AgentRouter 老用户两者皆 null） */
        String respGhId2 = "";
        if (respGhId.isEmpty() && bundle.has("github_user_id") && !bundle.isNull("github_user_id")) {
            Object gid2 = bundle.opt("github_user_id");
            if (gid2 != null) {
                respGhId2 = String.valueOf(gid2).trim();
                if ("null".equals(respGhId2)) respGhId2 = "";
            }
        }
                boolean mismatch = false;
        String mmWhy = "";
        /* v1.0.7：GitHub ID 锚点只对 GitHub 登录有意义。linuxdo 站的 github_id
         * 恒为 null、站内名是 linuxdo_<id>，套用 GitHub 锚点只会误拒；
         * 身份由「站点×账号」专属 Profile 分区保证。 */
        if ("github".equals(oauthProvider) && !respGhId.isEmpty()) {
            String cachedId = ensureGithubId(expect);
            if (!cachedId.isEmpty() && !cachedId.equals(respGhId)) {
                mismatch = true;
                mmWhy = "GitHub ID 不符：期望 " + expect + "(" + cachedId + ")，实际 " + respGhId;
            }
                } else if ("github".equals(oauthProvider)
                && expect != null && !expect.isEmpty()
                && login != null && !login.isEmpty()
                && !expect.trim().equalsIgnoreCase(login.trim())) {
            /* v1.0.7：这条弱判据依赖「站内名可与 GitHub 用户名直接比对」的前提，
             * 而该前提连 GitHub 站自己都不总成立（站内名常是 github_<id>）。
             * 对 linuxdo 更是必然不等（站内名 linuxdo_<id>），故只在 github 下启用。 */
            /* 站内名是 github_<站内id>，与 GitHub 名无必然关系：
             * 无可用锚点时改为「首次授权用户确认」而非直接拒（审计定稿）。 */
            boolean confirmed = idConfirmed;
            /* v0.4.8：授权运行在账号专属 Profile（多 Profile 分区）时自动确认——
             * OAuth code 由该账号分区里的 GitHub 会话产生，站点换到的身份必然
             * 属于该账号（会话隔离从机制上保证），无需用户再核对。仅降级到
             * Default 分区（无多 Profile 支持）时才弹确认框。 */
            if (!confirmed && mProfile != null) {
                confirmed = true;
                store.opLog(siteKey, accountKey, "授权", "info",
                        "身份自动确认（专属会话分区）", "站内名 " + login, "auto");
            }
            if (!confirmed && setCookie != null && !setCookie.isEmpty()) {
                /* cookie 型站首次授权（降级路径）：弹确认框让用户核对站内用户名 */
                lastBundle = bundle; lastToken = token; lastCookie = setCookie; lastLogin = login;
                showConfirmDialog(login, expect);
                return;   // 等用户确认后带 confirmed=true 重新进入
            }
            if (!confirmed) {
                mismatch = true;
                mmWhy = "GitHub 用户名不符：期望 " + expect + "，实际 " + login;
            }
        }
        if (mismatch) {
            exchanging = false;
            showLoadError("授权账号不符：" + mmWhy
                                        + "。\n请在 " + providerName() + " 退出后用 " + expect
                    + " 登录，或到「设置 → 凭据库」核对绑定。");
            try {
                store.opLog(siteKey, accountKey, "授权", "err",
                        "身份不符，拒绝落库防串号", mmWhy, "auto");
            } catch (Exception ignored) {}
            return;   // 关键：不写 token、不置 done
        }
        done = true;
        try {
            /* token 不再无条件落库（cookie 型站 token 为空，脏值拦截在下方） */
            long nowMs = System.currentTimeMillis();
            JSONObject patch = new JSONObject()
                    .put("siteKey", siteKey)
                    .put("updatedAt", nowMs)
                    .put("lastLogin", nowMs);
            if (alias != null && !alias.isEmpty()) patch.put("alias", alias);
            if (login != null) patch.put("githubAccount", login);
            if (credentialId != null && !credentialId.isEmpty()) patch.put("credentialId", credentialId);
        /* opus4.8 审计·B-02：cookie 型站点的真会话凭据（gin session） */
        if (setCookie != null && !setCookie.isEmpty()) {
            patch.put("siteCookie", setCookie);
            /* v0.6.7 会话指纹（新·可见授权链路）：与 SilentAuth 同判据。 */
            try {
                new Store(this).opLog(siteKey, accountKey, "授权链路", "info",
                        "新会话凭据指纹", "cookieFP=" + SilentAuth.sessionFingerprint(setCookie), "auto");
            } catch (Exception ignored) {}
        }
        /* v0.3.8：落账号锚点与站内用户ID，供身份校验和 New-Api-User 头使用。 */
        if (login != null && !login.isEmpty()) patch.put("ghAnchor", login);
        /* v1.1.1：记录本账号授权走的 OAuth 方式（github/linuxdo）。
         * 同一站可同时支持两种登录，展示与填表都按它区分。 */
        patch.put("authProvider", oauthProvider);
        Object sid = bundle.opt("id");
        if (sid != null) {
            String su = String.valueOf(sid).trim();
            if (!su.isEmpty() && !"null".equals(su)) patch.put("siteUserId", su);
        }
        /* token 允许为空（cookie 型站）；脏值不落库 */
        if (token != null && !token.trim().isEmpty() && !"null".equals(token.trim())) patch.put("token", token.trim());

        /* v1.1.19：授权成功即落额度，杜绝「授权后刷新额度失败」。
         * 根因（真机日志 07:36 实证）：授权 WebView 里 /api/user/self 已 HTTP 200 拿到含
         * quota 的用户对象（body 586B），但 finishOk 只取身份+cookie、丢弃了额度；随后
         * MainActivity 另起一次冷刷新走原生 OkHttp → 被 WAF 挡 503 → 只读离屏要从零过盾（失败）
         * → 会话明明刚建好却又触发无谓的静默重授权 → 撞上网络瞬断 SocketTimeout，最终 lastStatus
         * 空、额度不显示。修法：bundle 就是那次 200 的 /api/user/self 响应体(data 层)，此处直接
         * 按 Engine.status 同构解析 quota/used_quota 折算 USD，写入 lastStatus。这条数据来自已过盾
         * 的可见 WebView，最可靠；之后冷刷新即便失败也不会覆盖它（buildStatusPatch 仅在 200 时改写）。 */
        try {
            JSONObject selfU = bundle;
            JSONObject uu = bundle.optJSONObject("user");
            if (uu != null && (uu.has("quota") || uu.has("used_quota"))) selfU = uu;
            if (selfU.has("quota") || selfU.has("used_quota")) {
                long unit = store.siteMetaLong(siteKey, "quotaPerUnit", Engine.QUOTA_PER_UNIT_DEFAULT);
                if (unit <= 0) unit = Engine.QUOTA_PER_UNIT_DEFAULT;
                double quota = selfU.optDouble("quota", 0);
                double used = selfU.optDouble("used_quota", 0);
                String uName = selfU.optString("display_name", "");
                if (uName.isEmpty()) uName = selfU.optString("username", "");
                JSONObject siteObj = store.findSite(siteKey);
                JSONObject lastStatus = new JSONObject()
                        .put("ok", true).put("http", 200).put("authorized", true)
                        .put("account", accountKey)
                        .put("site", siteObj == null ? "" : siteObj.optString("name", ""))
                        .put("availableUSD", Math.round(quota / unit * 100.0) / 100.0)
                        .put("usedUSD", Math.round(used / unit * 100.0) / 100.0)
                        .put("user", (uName == null || uName.isEmpty()) ? JSONObject.NULL : uName)
                        .put("statusDate", Engine.todayStr());
                if (SiteProtocol.canStoreStatus(lastStatus)) {
                    patch.put("lastStatus", lastStatus);
                    store.opLog(siteKey, accountKey, "刷新", "ok", "额度已更新（授权直取）",
                            "可用 $" + Ui.usd(lastStatus.optDouble("availableUSD", 0))
                                    + "；来自授权 WebView 的 /api/user/self 200，无需冷刷新", "user");
                }
            }
        } catch (Exception ignored) {}

            JSONObject rec = store.findAccount(accountKey);
            if (rec == null) {
                rec = new JSONObject().put("key", accountKey);
                java.util.Iterator<String> it = patch.keys();
                while (it.hasNext()) { String k = it.next(); rec.put(k, patch.get(k)); }
                store.upsertAccount(siteKey, rec);
            } else {
                store.patchAccount(accountKey, patch);
            }
            if (login != null) {
                try {
                    JSONObject cfg = store.config();
                    cfg.put("lastGithubUser", login);
                    store.saveConfig(cfg);
                } catch (Exception ignored) {}
            }
            /* 需求3：落盘本账号 Profile 会话 —— 授权一次后，该账号分区里的
             * GitHub 会话长期有效，刷新/签到后台自动交换，无需再手动授权 */
            WebViewProfileUtil.flush(mProfile);
            store.appendLog(siteKey, accountKey, "auth", "via=android user=" + (login == null ? "?" : login));
            store.opLog(siteKey, accountKey, "授权", "ok",
                    "授权成功" + (login == null ? "" : (" · " + login)), "", "user");

            Intent out = new Intent();
            out.putExtra("ok", true);
            out.putExtra("user", login == null ? "" : login);
            out.putExtra("accountKey", accountKey == null ? "" : accountKey);
            setResult(RESULT_OK, out);
        } catch (Exception e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "保存失败: " + e.getMessage()));
        }
        finish();
    }

    /* v0.3.7：无锚点时首次授权用户确认（审计定稿：仅交互式 AuthActivity 弹，SilentAuth 不弹） */
    private void showConfirmDialog(String siteUsername, String expectGithub) {
        Store store = new Store(this);
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("请核对授权身份")
                    .setMessage("站点返回的站内用户名是：" + siteUsername
                                                        + "\n\n请在站点网页的「个人设置」核对该用户名属于你的 "
                            + providerName() + " 账号（" + expectGithub + "）。"
                            + "\n\n确认无误请点「确认是本人」，否则点「取消授权」。")
                    .setPositiveButton("确认是本人", (dg, w) -> {
                        idConfirmed = true;
                        store.opLog(siteKey, accountKey, "授权", "info",
                                "用户确认站内身份", "站内名 " + siteUsername, "user");
                        finishOk(lastBundle, lastToken, lastCookie, lastLogin);
                    })
                    .setNegativeButton("取消授权", (dg, w) -> {
                        store.opLog(siteKey, accountKey, "授权", "err",
                                "用户取消：站内身份不符", "站内名 " + siteUsername, "user");
                        setResult(RESULT_CANCELED, new Intent().putExtra("error", "用户确认身份不符"));
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    /* showConfirmDialog 重入所需快照 */
    private JSONObject lastBundle; private String lastToken, lastCookie, lastLogin;

    /** org.json optString 对 JSON null 值返回字面 "null" 字符串（而非 fallback）——显式拦截 */
    static String jsonStr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        String v = o.optString(key, "");
        if (v == null) return "";
        v = v.trim();
        if (v.isEmpty() || v.equals("null") || v.equals("undefined")) return "";
        return v;
    }

    /** opus4.8 审计·B-02：从 OkHttp 响应头拼装 Cookie 头值（仅保留有值 cookie） */
    private static String extractCookies(java.util.List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String sc : setCookies) {
            int semi = sc.indexOf(';');
            String pair = (semi >= 0 ? sc.substring(0, semi) : sc).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String val = pair.substring(eq + 1).trim();
            if (val.isEmpty() || "deleted".equalsIgnoreCase(val)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(pair);
        }
        return sb.toString();
    }

    /** opus4.8 复审·B1 锚点强化：确保凭据缓存了 GitHub 数字 ID（githubId）。
     * New API 系站点 OAuth 响应里的 username 是站内名（github_<站内id>），
     * 不能当 GitHub 用户名比对；github_id 列存的是 GitHub API /user 的
     * 数字 id（字符串形式，官方源码 oauth/github.go 实锤），永久不变。
     * 已缓存 → 直接返回；未缓存 → 查 api.github.com 缓存（失败返回 ""，
     * 调用方跳过校验不误拒）。 */
    private String ensureGithubId(String githubUser) {
        if (githubUser == null || githubUser.isEmpty()) return "";
        try {
            Store store = new Store(this);
            if (credentialId != null && !credentialId.isEmpty()) {
                JSONObject c = store.findCredential(credentialId);
                if (c != null) {
                    String cached = c.optString("githubId", "");
                    if (!cached.isEmpty() && !"null".equals(cached)) return cached;
                }
            }
            /* 查询 GitHub 数字 ID（未认证 60 次/时/IP，缓存后只查一次） */
            OkHttpClient c2 = withProxy(new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
                    store.config().optJSONObject("proxy"));
            Response r = c2.newCall(new Request.Builder()
                    .url("https://api.github.com/users/" + githubUser)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "justsign-app").build()).execute();
            String body = r.body() != null ? r.body().string() : "";
            try { r.close(); } catch (Exception ignored) {}
            if (r.code() == 200) {
                JSONObject ju = new JSONObject(body);
                long id = ju.optLong("id", 0);
                if (id > 0 && credentialId != null && !credentialId.isEmpty()) {
                    store.patchCredential(credentialId,
                            new JSONObject().put("githubId", String.valueOf(id)));
                    return String.valueOf(id);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 本账号期望的 GitHub 用户名：凭据 githubUser 优先，回退账号别名；空=不校验 */
    private String expectGithubLogin() {
        try {
            Store store = new Store(this);
            if (credentialId != null && !credentialId.isEmpty()) {
                JSONObject c = store.findCredential(credentialId);
                if (c != null) {
                    String gu = c.optString("githubUser", "");
                    if (!gu.isEmpty()) return gu;
                }
            }
            JSONObject acc = store.findAccount(accountKey);
            if (acc != null) {
                String cid = acc.optString("credentialId", "");
                if (!cid.isEmpty()) {
                    JSONObject c = store.findCredential(cid);
                    if (c != null) {
                        String gu = c.optString("githubUser", "");
                        if (!gu.isEmpty()) return gu;
                    }
                }
            }
            return alias == null ? "" : alias;
        } catch (Exception e) { return ""; }
    }

    private void applyProxyThen(Runnable then) {
        if (proxyApplied) { then.run(); return; }
        JSONObject proxy;
        try { proxy = new Store(this).config().optJSONObject("proxy"); } catch (Exception e) { proxy = null; }
        final boolean enabled = proxy != null && proxy.optBoolean("enabled");
        if (!enabled || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyApplied = true;
            then.run();
            return;
        }
        final String host = proxy.optString("host", "127.0.0.1");
        final int port = proxy.optInt("port", 10808);
        new Thread(() -> {
            boolean reachable = ProxyDetect.reachable(host, port, 1500);
            h.post(() -> {
                if (isAuthInactive()) return;
                proxyApplied = true;
                if (!reachable) {
                    showTip("未检测到代理 " + host + ":" + port + " · 若加载失败请开 VPN");
                    then.run();
                    return;
                }
                try {
                    ProxyConfig pc = new ProxyConfig.Builder()
                            .addProxyRule("socks5://" + host + ":" + port)
                            .addDirect()
                            .build();
                    ProxyController.getInstance().setProxyOverride(pc, Runnable::run, () -> {
                        showTip("已挂载代理 " + host + ":" + port + " · GitHub 授权中");
                        then.run();
                    });
                } catch (Exception e) { then.run(); }
            });
        }, "auth-proxy").start();
    }

    private static OkHttpClient withProxy(OkHttpClient base, JSONObject proxy) {
        if (proxy != null && proxy.optBoolean("enabled")) {
            try {
                String host = proxy.optString("host", "127.0.0.1");
                int port = proxy.optInt("port", 10808);
                if (!ProxyDetect.reachable(host, port, 1200)) return base;
                return base.newBuilder().proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress(host, port))).build();
            } catch (Exception ignored) {}
        }
        return base;
    }

    /** provider 的用户可读名（提示文案用）。 */
    private String providerName() {
        if ("linuxdo".equals(oauthProvider)) return "Linux DO";
        if ("oidc".equals(oauthProvider)) return "OIDC";
        if ("discord".equals(oauthProvider)) return "Discord";
        return "GitHub";
    }
    /** 按站点 provider 构造授权页 URL（GitHub 与 Linux DO 参数形态不同）。 */
    private String buildAuthorizeUrl(String clientId, String state) {
        if ("linuxdo".equals(oauthProvider)) {
            /* 实测规范（New API web/src/lib/oauth.ts）：
             * https://connect.linux.do/oauth2/authorize?response_type=code&client_id=..&state=..
             * 不带 redirect_uri —— 回调地址由站点服务端注册死为 {site}/api/oauth/linuxdo；
             * 也没有 GitHub 的 &login= 强切账号参数（linuxdo 无此参数）。 */
            return "https://connect.linux.do/oauth2/authorize?response_type=code"
                    + "&client_id=" + enc(clientId) + "&state=" + enc(state);
        }
        return "https://github.com/login/oauth/authorize?client_id=" + enc(clientId)
                + "&state=" + enc(state) + "&scope=user:email"
                + (githubLogin.isEmpty() ? "" : ("&login=" + enc(githubLogin)));
    }
    /** 浏览器内取回 clientId + state。
     * WAF 站（阿里云质询）：acw_sc__v2 由质询页 JS 算出，只有真实内核能拿到。
     * 实测定稿（对照实验）：每次请求都可能 Set-Cookie 轮换 session，state 与
     * 「state 响应下发的 session」严格配对 —— 故 state 必须是本页最后一个请求：
     * 串行（先 status 后 state），state 响应落地后立即回调快照，绝无后续请求覆盖。 */
    private String bootstrapJs() {
        String payload;
        try {
            payload = new JSONObject().put("provider", oauthProvider)
                    .put("intent", "login").toString();
        } catch (Exception e) { payload = "{\"provider\":\"github\",\"intent\":\"login\"}"; }
        String stateUrlJs = "linuxdo".equals(oauthProvider)
                ? "'/api/oauth/state?mode=login'"
                : "null";
        return "(function(){function rep(o){try{JustSign.onOAuthBootstrap(JSON.stringify(o))}catch(e){}}"
                + "function gj(p,o){return fetch(p,o).then(function(r){return r.text().then(function(t){return {s:r.status,b:t};});}).catch(function(e){return {s:0,b:'',err:String(e)};});}"
                + "var H={credentials:'include',headers:{'Accept':'application/json'}};"
                + "var P={credentials:'include',method:'POST',headers:{'Accept':'application/json','Content-Type':'application/json'},body:'" + payload + "'};"
                + "var st=function(x){try{if(!x||!x.b)return '';var j=JSON.parse(x.b);if(!j||!j.success)return '';"
                + "var v=j.data;return (typeof v==='string')?v:((v&&v.flow_token)||'');}catch(e){return '';};};"
                /* 严格串行：status 先行，state 最后 —— state 响应的 Set-Cookie(S1)
                 * 是 CookieManager 的最终值，回调快照即 S1，绝不被后续请求覆盖。 */
                + "gj('/api/status',H).then(function(a){"
                + "var o={ok:true,gh:'',ld:''};"
                + "try{var sj=JSON.parse(a.b);var d=(sj&&sj.data)||{};o.gh=d.github_client_id||'';o.ld=d.linuxdo_client_id||'';o.turnstile=!!d.turnstile_check;}"
                + "catch(e){o.statusBlocked=true;}"
                + "var stateUrl=" + stateUrlJs + ";"
                + "var stateReq=stateUrl?gj(stateUrl,H).then(function(r){return {s:st(r),m:'get'};}):gj('/api/oauth/state',P).then(function(r){var s=st(r);if(s)return {s:s,m:'post'};return gj('/api/oauth/state?mode=login',H).then(function(r2){return {s:st(r2),m:'get'};});});"
                + "stateReq.then(function(sr){sr=sr||{};o.state=sr.s||'';o.stateFrom=sr.m||'';"
                + "rep(o);})"   /* state 刚落地立即回调 —— 快照此刻抓 S1 */
                + ".catch(function(){rep(o);});})"
                + ".catch(function(e){rep({ok:false,error:String((e&&e.message)||e)});});})();";
    }
    /**
     * v1.0.7：用真实浏览器内核（已越过站点防护的 WebView）取回 clientId + state。
     *
     * 为什么必须走 WebView：① AnyRouter 一类站前置阿里云 WAF，按出口 IP 信誉返回
     * JS 质询页（HTTP 200 + <html><script>var arg1=…），OkHttp 拿不到 JSON，表现为
     * 「获取授权会话失败 → 重试 → 一直转圈」；② 旧版 New API 把 state 存在服务端
     * session 里，只有同一个浏览器会话后续兑换才认。两者一次解决。
     *
     * 全程只用该「站点×账号」专属 Profile，不碰全局 Cookie；最多 6 轮、每轮 12s 上限，
     * 失败返回 null 由调用方保留原有错误提示。日志只记布尔与长度，绝不记 state。
     */
    private String[] fetchBootstrapInWebView() {
        if (isAuthInactive()) return null;
        bootstrapJson = null;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        bootstrapLatch = latch;
        bootstrapPending = true;
        /* 单次自然加载：加载站点首页，由浏览器内核自然完成可能存在的 WAF 质询与放行重定向，
         * onPageFinished 会自动执行同源 fetch，拿到参数后立即解开 latch，零 sleep 零无谓重试。 */
        h.post(() -> {
            if (isAuthInactive() || wv == null) { bootstrapPending = false; latch.countDown(); return; }
            try { wv.loadUrl(baseUrl + "/"); }
            catch (Exception e) { bootstrapPending = false; latch.countDown(); }
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                authLog("warn", "浏览器引导取参超时", "timeout=30s；url=" + baseUrl
                        + "；lastJson=" + (bootstrapJson == null ? "null" : "received"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bootstrapLatch = null; bootstrapPending = false;
            return null;
        }
        bootstrapLatch = null;
        String json = bootstrapJson;
        bootstrapPending = false;
        String clientId = "", state = "";
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                String cid = "linuxdo".equals(oauthProvider)
                        ? o.optString("ld", "") : o.optString("gh", "");
                String st = o.optString("state", "");
                if (cid.isEmpty()) cid = o.optString("gh", "");
                authLog("info", "浏览器引导结果", "ok=" + o.optBoolean("ok")
                        + "；cid=" + !cid.isEmpty()
                        + "；state=" + !st.isEmpty()
                        + "；statusBlocked=" + o.optBoolean("statusBlocked")
                        + "；err=" + o.optString("error", "none"));
                clientId = cid;
                state = st;
            } catch (Exception ignored) {}
        }
        /* WAF 放行 Cookie 缓存到站点级（供 OkHttp 兑换复用） */
        String session = "";
        String ck = WebViewProfileUtil.cookieHeader(mProfile, baseUrl);
        if (ck != null && !ck.isEmpty()) session = ck;
        try {
            String wafCk = Engine.pickWafCookies(session);
            if (siteKey != null && !wafCk.isEmpty()) new Store(this).putSiteMeta(siteKey, "wafCookie", wafCk);
        } catch (Exception ignored) {}
        if (clientId.isEmpty() || state.isEmpty()) {
            authLog("warn", "浏览器取授权参数未完成",
                    "clientId=" + !clientId.isEmpty() + "；state=" + !state.isEmpty() + "；provider=" + oauthProvider);
            return clientId.isEmpty() && state.isEmpty() ? null : new String[]{clientId, state, session};
        }
        authLog("info", "浏览器预热完成",
                "clientId=true；state=true；wafCookieCached=" + !Engine.pickWafCookies(session).isEmpty()
                        + "；provider=" + oauthProvider);
        /* 关键：返回完整 Cookie（session S1 + acw_sc__v2）作为 stateSessionCookie，
         * 兑换时 OkHttp 原样带回 —— S1 里存着 state，acw_sc__v2 过 WAF。
         * 调用方拿到 state 后必须立即导航 authorize（页面离开站点域，
         * SPA 停止请求，S1 冻结不再轮换）。 */
        return new String[]{clientId, state, session};
}

    private static JSONObject getJson(OkHttpClient c, String url) {
        Response rs = null;
        try {
            rs = c.newCall(new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json").build()).execute();
            if (rs.code() != 200) return null;
            String body = rs.body() != null ? rs.body().string() : "";
            if (body.trim().isEmpty()) return null;
            return new JSONObject(body);
        } catch (Exception e) { return null; }
        finally { if (rs != null) try { rs.close(); } catch (Exception ignored) {} }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s == null ? "" : s; }
    }

    private void showTip(String text) {
        h.post(() -> { if (tip != null) tip.setText("  " + text + "  "); });
    }

    private void showLoadError(String detail) {
        if (isAuthInactive()) return;
        h.post(() -> {
            if (isAuthInactive()) return;
            loadFailed = true;
            authAttempt++;
            lastAuthError = detail == null || detail.isEmpty() ? "授权准备失败" : detail;
            observingLogin = false;
            pendingExchangeJs = null;
            bootstrapPending = false;
            exchanging = false;
            if (bootstrapLatch != null) bootstrapLatch.countDown();
            // Only cancel this flow's own timers. authAttempt already invalidates queued
            // script/network callbacks, so never wipe the whole Handler queue — the retry
            // button and other UI posts must survive.
            h.removeCallbacks(loginObserverTick);
            h.removeCallbacks(otpReinject);
            SilentAuth.releaseExchange(accountKey);
            setResult(RESULT_CANCELED, new Intent().putExtra("error", lastAuthError));
            try { if (wv != null) { wv.stopLoading(); wv.loadUrl("about:blank"); } } catch (Exception ignored) {}
            if (boot != null) boot.setVisibility(View.GONE);
            if (wv != null) wv.setVisibility(View.GONE);
            if (errMsg != null) errMsg.setText(lastAuthError);
            if (errorLayer != null) errorLayer.setVisibility(View.VISIBLE);
            showTip("授权已停止 · 请稍后手动重试");
            authLog("err", "授权准备失败（已停止）", lastAuthError);
        });
    }

    @Override public void onBackPressed() {
        if (!done) {
            done = true;
            setResult(RESULT_CANCELED, new Intent().putExtra("error",
                    lastAuthError.isEmpty() ? "授权已取消" : lastAuthError));
        }
        finish();
    }

    @Override protected void onPause() {
        super.onPause();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        done = true;
        h.removeCallbacks(loginObserverTick);
        /* v0.6.7：页面销毁兜底释放在途标记——用户取消/返回/异常退出后，
         * 该账号不会被永久锁死，下次授权可正常抢占交换权。 */
        SilentAuth.releaseExchange(accountKey);
        /* v1.0.4：同步释放授权事务锁，防止下次授权被误判"流程进行中"。 */
        ReauthManager.release(siteKey, accountKey);
        h.removeCallbacksAndMessages(null);
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.removeJavascriptInterface("JustSign");
                wv.destroy();
            } catch (Exception ignored) {}
            wv = null;
        }
        super.onDestroy();
    }
}