package icu.justwoker.justsign;

import org.json.JSONObject;

/**
 * AuthFillJs（v0.2.3）— 授权页表单自动填充 + 自动提交。
 *
 * 用途：GitHub 登录页 / 站点自身登录页出现账号密码框时，把凭据库里的值填进去，
 *   并在字段齐全时自动点击登录按钮。等价于密码管理器 + 用户点一下，不涉及绕过任何验证。
 *   Turnstile / hCaptcha 等人机验证仍由真实浏览器环境自行完成，本脚本不触碰。
 *
 * 【v0.2.3：提交时机改为「就绪即点」】
 *   原来固定等 700ms —— 慢页面还没就绪就点（没反应），快页面白等。
 *   现在 stableClick() 每 120ms 轮询三个条件，连续 2 次满足立刻点，最多等 6s：
 *     a) 目标字段值仍在（React 受控组件接管完毕的信号）
 *     b) 提交按钮存在 / 可见 / 未 disabled
 *     c) 没有待完成的人机验证
 *   6s 内没就绪就放弃并回传 submitAborted（附原因），不硬点。
 *   2FA 码要求满 6 位才提交，避免半截码被提交掉。
 *
 * 【自动提交的安全边界】
 *   页面有 cf-turnstile/recaptcha/hcaptcha 且尚未产出 token 时，只填不点，
 *   交给用户或真实环境自动过。每种表单只点一次，失败不重试，避免连点触发风控。
 *
 * 【2FA 方式切换】
 *   GitHub 若把「GitHub Mobile 推送批准」设为首选方法，2FA 页默认不是 TOTP 输入框，
 *   页面上会有「Use authenticator app」/「使用验证器应用」之类的切换链接。
 *   凭据里配了 TOTP 时，脚本自动点这个链接切到验证器输入框，再填 6 位码。
 *   注：这只是点页面上本来就存在的切换入口，等价于用户手点。
 *
 * 2FA 规则（保持）：
 *   凭据里配了 2FA → 检测到（或切换出）2FA 输入框时自动填入当前验证码
 *   凭据里没配 2FA → 完全不跳转、不聚焦、不做任何 2FA 相关动作
 */
public final class AuthFillJs {
    private AuthFillJs() {}

    /**
     * OAuth 确认页自动授权（opus4.8 审计方案·需求2）：
     * GitHub /login/oauth/authorize 页注入，800ms 后点击 Authorize 按钮。
     * 防重复：window 标志位（SPA 内多次 onPageFinished 只点一次）。
     * 仅当页面确有授权按钮（button[name=authorize] / #js-oauth-authorize-btn）
     * 时才点，绝不误点其他按钮。按钮 disabled 时每 200ms 重试（最多 5s）。
     */
    public static String authorizeJs() {
        return "(function(){" +
                "try{" +
                "if(window.__jsAuthorize)return;window.__jsAuthorize=1;" +
                "var tries=0;" +
                "function safeText(b){try{return((b.innerText||b.value||b.getAttribute('aria-label')||'')+'').trim().slice(0,40);}catch(e){return '';}}" +
                "function meta(b){try{return{id:(b&&b.id||'').slice(0,40),name:(b&&b.name||'').slice(0,40),type:(b&&b.type||'').slice(0,20),text:safeText(b),host:location.host,path:location.pathname};}catch(e){return{};}}" +
                "function report(action,b,extra){try{var o=meta(b);o.ok=true;o.action=action;if(extra)for(var k in extra)o[k]=extra[k];window.JustSign.onFill(JSON.stringify(o));}catch(e){}}" +
                "function find(){var b=document.querySelector('button[name=authorize]');" +
                "  if(!b)b=document.getElementById('js-oauth-authorize-btn');" +
                "  if(!b){var c=document.querySelectorAll('button.btn-primary,button[type=submit],input[type=submit]');" +
                "    for(var i=0;i<c.length;i++){var t=safeText(c[i]).toLowerCase();" +
                /* 授权确认页只匹配明确的 Allow/Authorize 语义，绝不匹配 Continue 等模糊按钮，防误点 Deny */
                "      if(t.indexOf('authorize')>=0||t.indexOf('授权')>=0||t.indexOf('allow')>=0)return c[i];}}" +
                "  return b;}" +
                "report('authorizeScan',null,{title:(document.title||'').slice(0,60),authorizeCount:document.querySelectorAll('button[name=authorize],#js-oauth-authorize-btn').length,cancelCount:document.querySelectorAll('button[name=cancel],#js-oauth-cancel-btn').length,loginForm:!!document.querySelector('#login_field,input[type=password]')});" +
                "var iv=setInterval(function(){tries++;" +
                "  var b=find();" +
                "  if(b&&!b.disabled){clearInterval(iv);" +
                "    report('authorizeFound',b,{tries:tries});" +
                "    try{b.scrollIntoView({block:'center'});}catch(e){}" +
                "    b.click();" +
                "    report('autoAuthorize',b,{tries:tries});}" +
                "  else if(tries>=25){clearInterval(iv);report('authorizeMissing',null,{tries:tries,title:(document.title||'').slice(0,60),buttons:document.querySelectorAll('button,input[type=submit]').length});}},200);" +
                "}catch(e){try{window.JustSign.onFill(JSON.stringify({ok:false,action:'authorizeError',error:String(e).slice(0,80),host:location.host,path:location.pathname}));}catch(e2){}}})()";
    }

    /**
     * @param account    站点/GitHub 登录账号（空串则不填账号）
     * @param password   密码明文（空串则不填密码）
     * @param twofaCode  2FA 当前验证码（空串 = 该账号没有 2FA，脚本跳过全部 2FA 逻辑）
     * @param autoSubmit 是否在字段齐全时自动点击登录/验证按钮
     */
    public static String render(String account, String password, String twofaCode, boolean autoSubmit) {
        return TEMPLATE
                .replace("/*__ACC__*/null", q(account))
                .replace("/*__PWD__*/null", q(password))
                .replace("/*__OTP__*/null", q(twofaCode))
                .replace("/*__AUTO__*/false", autoSubmit ? "true" : "false");
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private static final String TEMPLATE =
        "(function(){" +
        "try{" +
        "if(window.__jsfill)return;window.__jsfill=1;" +
        "var ACC=/*__ACC__*/null;var PWD=/*__PWD__*/null;var OTP=/*__OTP__*/null;" +
        "var AUTO=/*__AUTO__*/false;" +
        "var autoOffReported=false;" +
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
        "function pick(sel){try{var a=document.querySelectorAll(sel);" +
        "  for(var i=0;i<a.length;i++){if(visible(a[i])&&!a[i].disabled&&!a[i].readOnly)return a[i];}}catch(e){}return null;}" +
        "function txt(el){try{return((el.innerText||el.textContent||'')+' '+(el.value||'')+' '" +
        "  +(el.getAttribute('aria-label')||'')).toLowerCase();}catch(e){return '';}}" +
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
        /* ---- 人机验证挂件：存在且未产出 token 时，不自动提交 ---- */
        "function captchaPending(){try{" +
        "  var boxes=document.querySelectorAll('.cf-turnstile,#cf-turnstile,[data-sitekey],.g-recaptcha,.h-captcha');" +
        "  if(!boxes.length)return false;" +
        "  var any=false;" +
        "  for(var i=0;i<boxes.length;i++){if(visible(boxes[i]))any=true;}" +
        "  if(!any)return false;" +
        "  var f=document.querySelector('[name=\"cf-turnstile-response\"],[name=\"g-recaptcha-response\"]," +
        "[name=\"h-captcha-response\"]');" +
        "  if(f&&f.value&&f.value.length>10)return false;" +   /* 已有 token = 已通过 */
        "  return true;}catch(e){return false;}}" +
        /* ---- 提交按钮：优先表单内 submit，其次按文案匹配 ---- */
        "function submitBtn(near){" +
        "  var form=null;try{form=near&&near.form;}catch(e){}" +
        "  if(form){var b=form.querySelector('button[type=\"submit\"],input[type=\"submit\"]');" +
        "    if(b&&visible(b)&&!b.disabled)return b;}" +
        "  var cands=document.querySelectorAll('button,input[type=\"submit\"],input[type=\"button\"]');" +
        "  var KW=/sign in|log ?in|continue|verify|submit|登录|登陆|立即登录|确认|验证|提交|继续/i;" +
        "  for(var i=0;i<cands.length;i++){var c=cands[i];" +
        "    if(!visible(c)||c.disabled)continue;" +
        "    if(KW.test(txt(c)))return c;}" +
        "  return null;}" +
        "function click(el){try{el.scrollIntoView({block:'center'});el.click();return true;}catch(e){return false;}}" +
        /* ---- 提交时机判定（v0.2.3：不再固定等 700ms） ----
           固定延时的问题：慢页面 700ms 还没就绪（点了没反应），快页面白等。
           改为轮询"就绪条件"，一满足立刻点：
             a) 目标字段的值还在（React 受控组件有时会把值刷掉，说明还没接管完）
             b) 提交按钮存在、可见、未 disabled
             c) 没有待完成的人机验证
           每 120ms 检查一次，连续 2 次满足才点（防抖），最多等 6s。 */
        "function stableClick(getBtn,fields,tag){" +
        "  var okCount=0,tries=0;" +
        "  var iv2=setInterval(function(){" +
        "    tries++;" +
        "    var b=getBtn();" +
        "    var valsOk=true;" +
        "    for(var i=0;i<fields.length;i++){var f=fields[i];" +
        "      if(!f||!f.el){continue;}" +
        "      if(!f.el.value||f.el.value.length<f.min){valsOk=false;break;}}" +
        "    var ready=!!b&&valsOk&&!captchaPending();" +
        "    if(ready){okCount++;}else{okCount=0;}" +
        "    if(okCount>=2){clearInterval(iv2);" +
        "      if(click(b))R({ok:true,action:tag,waitedMs:tries*120});return;}" +
        "    if(tries>=50){clearInterval(iv2);" +
        "      R({ok:true,action:'submitAborted',reason:captchaPending()?'captcha':'notReady'});}" +
        "  },120);}" +
        "var switched=false;" +
        "function switchToTotp(){" +
        "  if(switched||!OTP)return false;" +
        "  if(otpField())return false;" +                       /* 已经是输入框，无需切换 */
        "  var u=location.href.toLowerCase();" +
        "  if(u.indexOf('github.com')<0)return false;" +
        "  if(u.indexOf('two-factor')<0&&u.indexOf('sessions')<0&&u.indexOf('/login')<0)return false;" +
        "  var KW=/authenticator app|authentication app|totp|verification code|use.*authenticator" +
        "|验证器|身份验证器|验证码登录|使用验证码|输入验证码/i;" +
        "  var a=document.querySelectorAll('a,button,summary,[role=\"button\"]');" +
        "  for(var i=0;i<a.length;i++){var e=a[i];" +
        "    if(!visible(e))continue;" +
        "    var t=txt(e);" +
        "    if(!KW.test(t))continue;" +
        "    if(/security key|passkey|sms|短信|通行密钥|安全密钥|recovery|恢复码/i.test(t))continue;" +
        "    switched=true;click(e);" +
        "    R({ok:true,action:'switch2fa',label:t.slice(0,40)});" +
        "    return true;}" +
        /* 有些版本把切换项藏在「更多选项」里，先展开一次 */
        "  var more=document.querySelectorAll('a,button,summary');" +
        "  for(var j=0;j<more.length;j++){var m=more[j];" +
        "    if(visible(m)&&/other options|more options|其他方式|更多选项|其它方式/i.test(txt(m))){" +
        "      switched=true;click(m);R({ok:true,action:'expand2fa'});return true;}}" +
        "  return false;}" +
        /* ================= 主循环 ================= */
        "var doneAcc=false,donePwd=false,doneOtp=false;" +
        "var submittedLogin=false,submittedOtp=false;" +
        "function tryFill(){" +
        "  var filled=[];" +
        "  if(!doneAcc&&ACC){var a=accField();if(a){" +
        "    if(!a.value){if(setVal(a,ACC)){doneAcc=true;filled.push('account');}}" +
        "    else if(a.value.length>=1){doneAcc=true;} /* v0.4.5：浏览器已预填也算就绪 */}}" +
        "  if(!donePwd&&PWD){var p=pwdField();if(p){" +
        "    if(!p.value){if(setVal(p,PWD)){donePwd=true;filled.push('password');}}" +
        "    else if(p.value.length>=1){donePwd=true;} /* v0.4.5：浏览器已预填密码也算就绪，否则 Sign in 永不自动点 */}}" +
        /* OTP 为空串（账号没有 2FA）时整段不执行 —— 不聚焦、不切换、不跳转 */
        "  if(!doneOtp&&OTP){" +
        "    var o=otpField();" +
        "    if(!o){switchToTotp();o=otpField();}" +
        "    if(o&&!o.value){if(setVal(o,OTP)){doneOtp=true;filled.push('otp');" +
        "       try{o.focus();o.scrollIntoView({block:'center'});}catch(e){}}}}" +
        "  if(filled.length)R({ok:true,filled:filled,hasOtpField:!!otpField(),otpConfigured:!!OTP});" +
        /* ---- 自动提交（就绪即点，不用固定延时） ---- */
        "  if(AUTO){" +
        "    if(!submittedOtp&&doneOtp){" +
        "      var oEl=otpField();" +
        "      if(oEl){submittedOtp=true;" +
        "        stableClick(function(){return submitBtn(otpField());}," +
        "          [{el:oEl,min:6}],'submit2fa');}}" +
        "    else if(!submittedLogin&&donePwd&&(doneAcc||!ACC)){" +
        "      var pEl=pwdField();var aEl=accField();" +
        "      if(pEl){submittedLogin=true;" +
        "        var fs=[{el:pEl,min:1}];if(ACC&&aEl)fs.push({el:aEl,min:1});" +
        "        stableClick(function(){return submitBtn(pwdField());},fs,'submitLogin');}}}" +
        "  if(!AUTO&&doneAcc&&!autoOffReported){autoOffReported=true;" +
        "    R({ok:true,action:\"autoOff\",filledAccount:true,filledPassword:donePwd});}" +
        "  return doneAcc||donePwd||doneOtp;}" +
        "tryFill();" +
        /* SPA 动态渲染：监听 DOM 变化继续尝试，45s 后停（2FA 页往往要等一会儿） */
        "var mo=null;" +
        "try{mo=new MutationObserver(function(){tryFill();});" +
        "  mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}" +
        "var iv=setInterval(tryFill,600);" +
        "setTimeout(function(){try{clearInterval(iv);if(mo)mo.disconnect();}catch(e){}" +
        "  R({ok:doneAcc||donePwd,done:true,filledAccount:doneAcc,filledPassword:donePwd,filledOtp:doneOtp," +
        "     submittedLogin:submittedLogin,submittedOtp:submittedOtp,otpConfigured:!!OTP});},45000);" +
        "}catch(e){try{window.JustSign.onFill(JSON.stringify({ok:false,message:String(e)}))}catch(e2){}}" +
        "})();";
}