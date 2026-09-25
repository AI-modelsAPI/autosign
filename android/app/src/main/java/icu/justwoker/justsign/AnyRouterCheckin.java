package icu.justwoker.justsign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.Profile;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** AnyRouter仅使用现有账号分区，原始status页处理WAF，不载入会自动签到的SPA。 */
public final class AnyRouterCheckin {
    private static final Set<String> ACTIVE = new HashSet<>();
    private static final String BASE = "https://anyrouter.top";
    private AnyRouterCheckin() {}

    public static void run(Context context, String siteKey, String accountKey, OffscreenCheckin.Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        synchronized (ACTIVE) {
            if (!ACTIVE.add(accountKey)) {
                main.post(() -> callback.onResult(false, false, 0, false, "本账号签到正在进行，不重复提交"));
                return;
            }
        }
        main.post(() -> new Runner(context.getApplicationContext(), siteKey, accountKey, callback).start());
    }

    private static final class Runner {
        final Context ctx;
        final String siteKey, accountKey;
        final OffscreenCheckin.Callback callback;
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean posted = new AtomicBoolean(false);
        volatile long submittedAt;
        final Store store;
        volatile boolean done;
        volatile String currentUrl = "";
        WebView web;
        Profile profile;
        String uid;
        final Runnable timeout = () -> finish(false, false, posted.get()
                ? "签到已提交但未确认结果；不会自动重复发送，请稍后刷新"
                : "站点质询或会话检查超时，未提交签到；请检查网络后重试");
        Runner(Context c, String sk, String ak, OffscreenCheckin.Callback cb) {
            ctx=c; siteKey=sk; accountKey=ak; callback=cb; store=new Store(c);
        }
        void start() {
            try {
                JSONObject site=store.findSite(siteKey), account=store.findAccount(accountKey);
                if (!SiteProtocol.isAnyRouter(site) || account==null) {finish(false,false,"站点或账号不匹配");return;}
                long now=System.currentTimeMillis();
                if (!SiteProtocol.rewardWindowOpen(now)) {
                    finish(false,false,"AnyRouter 每天北京时间08:00后登录自动发放；当前未到时间，未提交领取请求");return;
                }
                if (SiteProtocol.hasSignInReceipt(account,now)) {
                    finish(true,true,"本奖励周期的登录签到请求已确认（到账金额未知）");return;
                }
                uid=account.optString("siteUserId", "");
                if (!uid.matches("[1-9][0-9]{0,14}")) {finish(false,false,"缺少站内用户ID，请重新授权此账号");return;}
                web=new WebView(ctx);
                profile=WebViewProfileUtil.bindProfile(web,WebViewProfileUtil.profileNameFor(siteKey,accountKey));
                if (profile==null) {finish(false,false,"当前WebView不支持账号隔离，已停止后台签到；请更新Android System WebView");return;}
                web.getSettings().setJavaScriptEnabled(true);
                web.getSettings().setDomStorageEnabled(true);
                web.getSettings().setAllowFileAccess(false);
                web.getSettings().setAllowContentAccess(false);
                web.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36");
                web.addJavascriptInterface(this,"JustSign");
                web.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        if (!AuthProbeJs.sameOrigin(req.getUrl().toString(), BASE)) {
                            finish(false,false,"站点要求跳转登录；后台签到不会自动打开授权页");return true;
                        }
                        return false;
                    }
                    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){currentUrl=url;}
                    @Override public void onPageFinished(WebView view,String url){
                        currentUrl=url;
                        if(done||!AuthProbeJs.sameOrigin(url,BASE))return;
                        // 只在原始JSON页执行。WAF质询导航结束后会再次回调，不在SPA里触发第二次签到。
                        view.evaluateJavascript("(function(){try{var j=JSON.parse(document.body.innerText);return j.success===true&&!!j.data&&Object.prototype.hasOwnProperty.call(j.data,'quota_per_unit');}catch(e){return false;}})()", result->{
                            if(!done&&"true".equals(result)&&AuthProbeJs.sameOrigin(currentUrl,BASE))
                                web.evaluateJavascript(AnyRouterSignInJs.render(uid),null);
                        });
                    }
                });
                store.opLog(siteKey,accountKey,"后台签到","info","按官方接口检查现有会话",
                        "protocol=/api/user/sign_in；profile=account；oauth=false；reward=unknown","user");
                main.postDelayed(timeout,60000);
                CookieManager cm=WebViewProfileUtil.cookieManagerFor(profile);
                cm.setAcceptCookie(true);
                String cookie=account.optString("siteCookie", "");
                if(cookie.isEmpty()){applyProxy();return;}
                java.util.ArrayList<String> pairs=new java.util.ArrayList<>();
                for(String part:cookie.split(";")){
                    String p=part.trim();int eq=p.indexOf('=');
                    if(eq>0&&p.substring(0,eq).matches("[A-Za-z0-9_!#$%&'*+.^`|~-]+")&&!p.contains("\r")&&!p.contains("\n"))pairs.add(p);
                }
                if(pairs.isEmpty()){applyProxy();return;}
                final int[] left={pairs.size()};
                for(String p:pairs)cm.setCookie(BASE,p+"; Path=/; Secure; HttpOnly", accepted->{
                    if(--left[0]==0&&!done){WebViewProfileUtil.flush(profile);applyProxy();}
                });
            }catch(Exception e){finish(false,false,"后台会话初始化失败，未提交签到");}
        }
        void applyProxy(){
            if(done)return;
            JSONObject proxy=store.config().optJSONObject("proxy");
            if(proxy!=null&&proxy.optBoolean("enabled")&&WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
                try{
                    String scheme="http".equals(proxy.optString("type"))?"http":"socks5";
                    String address=scheme+"://"+proxy.optString("host","127.0.0.1")+":"+proxy.optInt("port",10808);
                    ProxyController.getInstance().setProxyOverride(new ProxyConfig.Builder().addProxyRule(address).build(),
                            main::post,()->{if(!done)web.loadUrl(BASE+"/api/status");});
                    return;
                }catch(Exception e){finish(false,false,"代理设置失败，未切换到直连或提交签到");return;}
            }
            web.loadUrl(BASE+"/api/status");
        }
        @JavascriptInterface public boolean claimSignIn(){
            long now=System.currentTimeMillis();
            if(done||!AuthProbeJs.sameOrigin(currentUrl,BASE)||!SiteProtocol.rewardWindowOpen(now)
                    ||!posted.compareAndSet(false,true))return false;
            submittedAt=now;
            return true;
        }
        @JavascriptInterface public void onResult(String json){
            main.post(()->{
                if(done||!AuthProbeJs.sameOrigin(currentUrl,BASE))return;
                try{
                    JSONObject r=new JSONObject(json);
                    boolean ok=r.optBoolean("ok")&&posted.get();
                    finish(ok,r.optBoolean("already"),r.optString("message","签到结果未知"));
                }catch(Exception e){finish(false,false,"签到回执无效，未确认结果");}
            });
        }
        @JavascriptInterface public void onProgress(String ignored) {}
        void finish(boolean ok,boolean already,String message){
            if(done)return;
            done=true;main.removeCallbacks(timeout);
            synchronized(ACTIVE){ACTIVE.remove(accountKey);}
            if(ok&&posted.get()&&submittedAt>0&&store.findAccount(accountKey)!=null){
                try{
                    JSONObject receipt=new JSONObject().put("date",SiteProtocol.rewardDate(submittedAt)).put("time",submittedAt).put("source","api_sign_in");
                    JSONObject patch=new JSONObject().put("lastCheckin",receipt).put("lastLogin",submittedAt);
                    String ck=WebViewProfileUtil.cookieHeader(profile,BASE);
                    if(ck!=null&&!ck.isEmpty())patch.put("siteCookie",ck);
                    store.patchAccount(accountKey,patch);
                }catch(Exception ignored){}
            }
            store.opLog(siteKey,accountKey,"后台签到",ok?"ok":"err",message,
                    "protocol=/api/user/sign_in；submitted="+posted.get()+"；oauth=false；rewardKnown=false","user");
            if(web!=null){try{web.removeJavascriptInterface("JustSign");web.stopLoading();web.destroy();}catch(Exception ignored){}}
            callback.onResult(ok,already,0,false,message);
        }
    }
}
