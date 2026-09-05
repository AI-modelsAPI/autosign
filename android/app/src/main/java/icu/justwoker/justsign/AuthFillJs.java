package icu.justwoker.justsign;

import org.json.JSONObject;

/**
 * AuthFillJs（v0.2.0）— 授权页表单自动填充脚本。
 *
 * 用途：GitHub 登录页 / 站点自身登录页出现账号密码框时，把凭据库里的值填进去。
 *   这是标准的密码管理器行为（表单自动填充），不涉及伪造任何验证。
 *   Turnstile / hCaptcha 等人机验证仍由真实浏览器环境自行完成，本脚本不触碰。
 *
 * 2FA 规则（按用户要求）：
 *   凭据里配了 2FA → 检测到 2FA 输入框时自动聚焦并填入当前验证码
 *   凭据里没配 2FA → 完全不跳转、不聚焦、不做任何 2FA 相关动作
 *
 * 行为约束：
 *   - 只填值，不自动点提交（避免误触、避免在验证码没过时提交）
 *   - 填充后给输入框加 0.5s 绿色高亮边框，让用户看到"已填"
 *   - 用 MutationObserver 监听 SPA 动态渲染（GitHub 登录页与站点 SPA 都是动态的）
 *   - 30 秒后自动停止观察，避免长期占用
 */
public final class AuthFillJs {
    private AuthFillJs() {}

    /**
     * @param account   站点/GitHub 登录账号（空串则不填账号）
     * @param password  密码明文（空串则不填密码）
     * @param twofaCode 2FA 当前验证码（空串 = 该账号没有 2FA，脚本跳过全部 2FA 逻辑）
     */
    public static String render(String account, String password, String twofaCode) {
        return TEMPLATE
                .replace("/*__ACC__*/null", q(account))
                .replace("/*__PWD__*/null", q(password))
                .replace("/*__OTP__*/null", q(twofaCode));
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private static final String TEMPLATE =
        "(function(){" +
        "try{" +
        "if(window.__jsfill)return;window.__jsfill=1;" +
        "var ACC=/*__ACC__*/null;var PWD=/*__PWD__*/null;var OTP=/*__OTP__*/null;" +
        "var R=function(o){try{window.JustSign.onFill(JSON.stringify(o))}catch(e){}};" +
        /* 高亮已填字段 0.5s */
        "function mark(el){try{var o=el.style.outline;el.style.outline='2px solid #22C55E';" +
        "  setTimeout(function(){try{el.style.outline=o}catch(e){}},500);}catch(e){}}" +
        /* 用原生 setter 赋值，保证 React/Vue 受控组件能感知（直接改 .value 会被框架覆盖） */
        "function setVal(el,v){try{" +
        "  var d=Object.getOwnPropertyDescriptor(el.__proto__,'value');" +
        "  if(d&&d.set)d.set.call(el,v);else el.value=v;" +
        "  el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "  el.dispatchEvent(new Event('change',{bubbles:true}));" +
        "  mark(el);return true;}catch(e){return false;}}" +
        "function visible(el){try{var r=el.getBoundingClientRect();" +
        "  return r.width>0&&r.height>0&&getComputedStyle(el).visibility!=='hidden';}catch(e){return true;}}" +
        "function pick(sel){var a=document.querySelectorAll(sel);" +
        "  for(var i=0;i<a.length;i++){if(visible(a[i])&&!a[i].disabled&&!a[i].readOnly)return a[i];}return null;}" +
        /* 账号框：GitHub 用 #login_field，站点常见 name=username/email */
        "function accField(){return pick('#login_field')||pick('input[autocomplete=\"username\"]')" +
        "  ||pick('input[name=\"login\"]')||pick('input[name=\"username\"]')||pick('input[name=\"email\"]')" +
        "  ||pick('input[type=\"email\"]')||pick('input[id*=\"user\" i]')||pick('input[placeholder*=\"账号\"]')" +
        "  ||pick('input[placeholder*=\"邮箱\"]')||pick('input[placeholder*=\"用户名\"]');}" +
        "function pwdField(){return pick('#password')||pick('input[type=\"password\"]');}" +
        /* 2FA 框：GitHub #app_totp / #otp，通用 one-time-code */
        "function otpField(){return pick('#app_totp')||pick('#otp')" +
        "  ||pick('input[autocomplete=\"one-time-code\"]')||pick('input[name*=\"otp\" i]')" +
        "  ||pick('input[name*=\"totp\" i]')||pick('input[name*=\"two\" i]')" +
        "  ||pick('input[placeholder*=\"验证码\"]');}" +
        "var doneAcc=false,donePwd=false,doneOtp=false;" +
        "function tryFill(){" +
        "  var filled=[];" +
        "  if(!doneAcc&&ACC){var a=accField();if(a&&!a.value){if(setVal(a,ACC)){doneAcc=true;filled.push('account');}}}" +
        "  if(!donePwd&&PWD){var p=pwdField();if(p&&!p.value){if(setVal(p,PWD)){donePwd=true;filled.push('password');}}}" +
        /* 关键：OTP 为空串（账号没有 2FA）时，这一段整体不执行 —— 不聚焦、不跳转 */
        "  if(!doneOtp&&OTP){var o=otpField();if(o&&!o.value){" +
        "     if(setVal(o,OTP)){doneOtp=true;filled.push('otp');" +
        "       try{o.focus();o.scrollIntoView({block:'center'});}catch(e){}}}}" +
        "  if(filled.length)R({ok:true,filled:filled,hasOtpField:!!otpField(),otpConfigured:!!OTP});" +
        "  return doneAcc||donePwd||doneOtp;}" +
        "tryFill();" +
        /* SPA 动态渲染：监听 DOM 变化继续尝试，30s 后停 */
        "var mo=null;" +
        "try{mo=new MutationObserver(function(){tryFill();});" +
        "  mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}" +
        "var iv=setInterval(tryFill,600);" +
        "setTimeout(function(){try{clearInterval(iv);if(mo)mo.disconnect();}catch(e){}" +
        "  R({ok:doneAcc||donePwd,done:true,filledAccount:doneAcc,filledPassword:donePwd,filledOtp:doneOtp," +
        "     otpConfigured:!!OTP});},30000);" +
        "}catch(e){try{window.JustSign.onFill(JSON.stringify({ok:false,message:String(e)}))}catch(e2){}}" +
        "})();";
}