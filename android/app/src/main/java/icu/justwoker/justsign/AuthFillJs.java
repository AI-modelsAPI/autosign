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
    /**
     * v1.1.10：LinuxDO 登录页观察者 —— 诊断上报 + 「使用 LinuxDO 继续」单次有效点击。
     *
     * 关键修复（真机 1.1.10 实测：17:08:30 与 17:08:32 各点了一次）：旧脚本用
     * window.__ldObserve 防重复，但那只在**单个 document** 内有效；登录页因「未登录/
     * 已过期」重载后新文档重新注入 → 又点一次。改用 sessionStorage 作跨文档（同源同标签
     * 页持久）单次闸门：点过一次就不再点，像人一样只点一下。
     *
     * 仍保留「轮询等按钮就绪再点」（不是死等固定时间）：每 500ms 找一次按钮，
     * 找到即点、点完即停并回报；最多 25 轮（~12.5s）安全网。点击后是否生效由
     * Java 侧观察 /api/user/self 与导航事实判断，脚本不 sleep 空等。
     */
    public static String linuxdoObserverJs() {
        return "(function(){"
                + "if(window.__ldObserve)return;window.__ldObserve=1;"
                + "try{window.JustSign.onFill(JSON.stringify({ok:true,action:'observerInjected',path:location.pathname}));}catch(e){}"
                + "try{var els=document.querySelectorAll('button,[role=button],a,input[type=submit]');"
                + "var arr=[];for(var i=0;i<els.length&&i<12;i++){arr.push(els[i].tagName+':'+((els[i].innerText||els[i].value||'')+'').trim().slice(0,20));}"
                + "window.JustSign.onFill(JSON.stringify({ok:true,action:'observerDump',count:els.length,els:arr.join('|')}));}catch(e){}"
                /* v1.1.17：登录页「使用 LinuxDO 继续」只点一次。
                 * 真机 07:10 实证：自动点 5 次页面仍不跳转，每次点后都是「登录态 HTTP 401（登录仍在进行）」
                 * ——说明页面在加载/等人机验证，不是点击没点中。「没跳转就重点」的假设是错的：可见页本来
                 * 就要人工过人机，重点只是徒增导航、还可能触发导航熔断(连续 8 次)。所以回到点一次即停，
                 * 剩下交给用户手动完成（用户已明确：可见页反正要人操作，自动点不点无所谓）。
                 * 稳健点击（派发 pointer/mouse 前置事件 + 一次 el.click()）保留，提高这一次点中的概率。 */
                + "function alreadyClicked(){try{return sessionStorage.getItem('__js_ld_clicked')==='1';}catch(e){return window.__jsLdClicked===true;}}"
                + "function markClicked(){try{sessionStorage.setItem('__js_ld_clicked','1');}catch(e){}window.__jsLdClicked=true;}"
                + "function findLinuxdoBtn(){try{"
                + "var bs=document.querySelectorAll('button,[role=button],a');"
                + "for(var i=0;i<bs.length;i++){var t=((bs[i].innerText||'')+'').trim();"
                + "if(/linux\\s*do/i.test(t)&&t.length<40&&!bs[i].disabled)return bs[i];}"
                + "}catch(e){}return null;}"
                + "function robustClick(el){try{el.scrollIntoView({block:'center'});}catch(e){}"
                + "try{var r=el.getBoundingClientRect();var cx=r.left+r.width/2,cy=r.top+r.height/2;"
                + "var seq=['pointerdown','mousedown','pointerup','mouseup'];"
                + "for(var i=0;i<seq.length;i++){var ev=null;try{ev=new MouseEvent(seq[i],{bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy});}catch(e){}"
                + "if(ev){try{el.dispatchEvent(ev);}catch(e){}}}"
                + "}catch(e){}"
                + "try{el.click();}catch(e){}}"
                /* v1.1.19：加「就绪守卫」——用户实测按钮刚出现就被点、页面还没就绪(JS 未挂载/还在加载)
                 * 导致点了不跳转，得人工再点才行。改为：必须 document.readyState==='complete' 且同一按钮
                 * 连续 STABLE(2×500ms=1s) 轮都在，才点这一次。仍是「只点一次」，只是把这一次推迟到页面稳定后，
                 * 让按钮的事件处理器有时间绑定，提高一次点中的概率。 */
                + "var STABLE=2,seen=0,cn=0,ci=setInterval(function(){cn++;if(cn>40){clearInterval(ci);return;}"
                + "if(alreadyClicked()){clearInterval(ci);return;}"   /* 已点过（含重载前那次）：不再点，交给人工 */
                + "if(document.readyState!=='complete')return;"       /* 页面还在加载：等 */
                + "var b=findLinuxdoBtn();if(!b){seen=0;return;}"      /* 按钮还没出现：等，重置稳定计数 */
                + "seen++;if(seen<STABLE)return;"                     /* 按钮要连续在 STABLE 轮(稳定~1s)才点 */
                + "markClicked();clearInterval(ci);"
                + "robustClick(b);"
                + "try{window.JustSign.onFill(JSON.stringify({ok:true,action:'linuxdoBtnClicked',text:((b.innerText||'')+'').trim().slice(0,20)}));}catch(e){}"
                + "},500);"
                + "})();";
    }

    public static String authorizeJs() {
        return "(function(){" +
                "try{" +
                "if(window.__jsAuthorize)return;window.__jsAuthorize=1;" +
                "var tries=0;" +
                "function safeText(b){try{return((b.innerText||b.value||b.getAttribute('aria-label')||'')+'').trim().slice(0,40);}catch(e){return '';}}" +
                "function meta(b){try{return{id:(b&&b.id||'').slice(0,40),name:(b&&b.name||'').slice(0,40),type:(b&&b.type||'').slice(0,20),text:safeText(b),host:location.host,path:location.pathname};}catch(e){return{};}}" +
                "function report(action,b,extra){try{var o=meta(b);o.ok=true;o.action=action;if(extra)for(var k in extra)o[k]=extra[k];window.JustSign.onFill(JSON.stringify(o));}catch(e){}}" +
/* 严格判据：支持 GitHub OAuth 与 Linux DO (connect.linux.do) 授权确认页。 */
                "function pageOk(){try{" +
                "  if(location.host==='github.com'&&location.pathname==='/login/oauth/authorize')return true;" +
                "  if(location.host.indexOf('linux.do')>=0&&location.pathname.indexOf('authorize')>=0)return true;" +
                "  return false;}catch(e){return false;}}" +
                "function isAuthorizeBtn(b){try{if(!b||b.disabled)return false;" +
                "  var nm=(b.getAttribute&&b.getAttribute('name')||'').toLowerCase();" +
                "  if(nm==='cancel'||nm==='deny')return false;" +
                "  var val=((b.getAttribute&&b.getAttribute('value'))||'').toLowerCase();" +
                "  if(val==='0'||val==='false'||val==='no')return false;" +
                "  var t=safeText(b).toLowerCase();" +
                "  if(t.indexOf('拒绝')>=0||t.indexOf('取消')>=0||t.indexOf('deny')>=0||t.indexOf('cancel')>=0)return false;" +
                "  return true;}catch(e){return false;}}" +
                "function find(){" +
                "  if(!pageOk())return null;" +
                "  if(document.querySelector('#login_field,#login-account-name,input[type=password]'))return null;" +   /* 登录页/2FA页不点 */
"/* 0. v1.1.0 实测定稿：connect.linux.do (Doorkeeper) 确认页无 <form>，「允许」是" +
                "     <a href='/oauth2/approve/{token}'>链接（「拒绝」= /oauth2/decline）。" +
                "     必须精确匹配 approve 链接，绝不误点 decline。 */" +
                "  var aApp=document.querySelector('a[href*=\\\"/oauth2/approve\\\"]');" +
                "  if(aApp)return aApp;" +
                "  var aTxt=document.querySelectorAll('a,button,input[type=submit]');" +
                "  for(var ai=0;ai<aTxt.length;ai++){var at=((aTxt[ai].innerText||aTxt[ai].value||'')+'').trim();" +
                "    if(at==='允许'||at.toLowerCase()==='approve'||at.toLowerCase()==='authorize')return aTxt[ai];}" +
"/* 1. Linux DO (Doorkeeper) 明确的 approve 授权表单：表单本身就是同意动作，直接取其 submit 按钮 */" +
                "  var fApprove=document.querySelector('form[action*=\\\"approve\\\"]');" +
                "  if(fApprove){" +
                "    var bApp=fApprove.querySelector('input[type=submit],button[type=submit],button,input[type=button],.btn');" +
                "    if(bApp&&!bApp.disabled)return bApp;" +
                "    if(bApp){try{bApp.disabled=false;}catch(e){}return bApp;}" +
                "  }" +
                "  var f=document.querySelector('form[action*=\\\"oauth\\\"],form[action*=\\\"authorize\\\"]');" +
                "  if(f){var b=f.querySelector('button[name=authorize],button[type=submit],input[type=submit],#js-oauth-authorize-btn,.btn-primary');" +
                "    if(isAuthorizeBtn(b))return b;}" +
                "  var b2=document.getElementById('js-oauth-authorize-btn');" +
                "  if(isAuthorizeBtn(b2))return b2;" +
                "  var list=document.querySelectorAll('button[name=authorize],button.btn-primary,input[value*=\\\"Authorize\\\" i],input[value*=\\\"授权\\\"]');" +
                "  for(var i=0;i<list.length;i++){if(isAuthorizeBtn(list[i]))return list[i];}" +
                "  return null;}" +
                "function dumpButtons(){try{" +
"  var bs=document.querySelectorAll('button,input[type=submit],input[type=button],a.btn,.btn,form');" +
                "  var r=[];" +
                "  for(var i=0;i<bs.length&&i<15;i++){var x=bs[i];" +
                "    r.push({t:x.tagName,id:x.id||'',nm:x.name||'',act:x.getAttribute('action')||'',cls:(x.className||'').slice(0,30),val:x.value||'',txt:safeText(x)});" +
                "  }" +
                "  return JSON.stringify(r);" +
                "}catch(e){return String(e);}}" +
                "report('authorizeScan',null,{title:(document.title||'').slice(0,60),elements:dumpButtons()});" +
/* v1.1.17 关键修复：授权「允许」是一次性动作——approve/{token} 授权码被第一次点击消费后，
 * 再点第二次会重新提交已消费的 token → 站点回调拿到废码 → HTTP 400（真机 07:11:38 实证：
 * 同一 token TeoOy 被点两次，导航#17 成功回调后导航#19 又提交一次，agentrouter 回调返回 400）。
 * 因此这里必须「只点一次，绝不重试」。1.1.16 我错误加的重试逻辑正是 400 的元凶，已回退。
 * 稳健点击（派发 pointer/mouse 前置事件 + 一次 el.click()）保留——它提高「这一次」点中的概率，
 * 不改变「只点一次」语义。跨文档再加 sessionStorage 闸门：即便确认页重载重注入，也绝不再点已授权的 token。 */
                "function approveGate(b){try{var href=(b&&b.getAttribute&&b.getAttribute('href'))||location.pathname;" +
                "  var key='__js_approved:'+href;" +
                "  if(sessionStorage.getItem(key)==='1')return false;" +   /* 该 approve token 已点过：绝不再点 */
                "  sessionStorage.setItem(key,'1');return true;}catch(e){return !window.__jsApproved&&(window.__jsApproved=true);}}" +
                "function authRobustClick(el){try{el.scrollIntoView({block:'center'});}catch(e){}" +
                "  try{var r=el.getBoundingClientRect();var cx=r.left+r.width/2,cy=r.top+r.height/2;" +
                "  var seq=['pointerdown','mousedown','pointerup','mouseup'];" +
                "  for(var i=0;i<seq.length;i++){var ev=null;try{ev=new MouseEvent(seq[i],{bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy});}catch(e){}" +
                "  if(ev){try{el.dispatchEvent(ev);}catch(e){}}}}catch(e){}" +
                "  try{el.click();}catch(e){}}" +
                "var iv=setInterval(function(){tries++;" +
                "  if(!pageOk())return;" +   /* 质询页/过渡页：不烧轮询次数，等真授权页出现 */
                "  var b=find();" +
                "  if(b){clearInterval(iv);" +   /* 找到即停：只点一次 */
                "    if(!approveGate(b)){report('authorizeSkipped',b,{tries:tries,reason:'already-approved'});return;}" +
                "    report('authorizeFound',b,{tries:tries,detail:dumpButtons()});" +
                "    authRobustClick(b);" +
                "    report('autoAuthorize',b,{tries:tries});}" +
                "  else if(tries>=75){clearInterval(iv);report('authorizeMissing',null,{tries:tries,title:(document.title||'').slice(0,60),elements:dumpButtons()});}},200);" +
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
        /* v1.0.7：兼容 SPA（Discourse 等单页应用）无刷新跳转 —— 不再盲目拦截二次注入。
         * 当路由变动（如从 /session/sso_provider 到 /login）或尚未填完时允许继续。 */
        "if(window.__jsfill_done&&window.__jsfill_path===location.pathname)return;" +
        "window.__jsfill_path=location.pathname;" +
        "var ACC=/*__ACC__*/null;var PWD=/*__PWD__*/null;var OTP=/*__OTP__*/null;" +
        "var AUTO=/*__AUTO__*/false;" +
        "var autoOffReported=false;" +
        "var R=function(o){try{window.JustSign.onFill(JSON.stringify(o))}catch(e){}};" +
        /* 高亮已填字段 0.5s */
        "function mark(el){try{var o=el.style.outline;el.style.outline='2px solid #22C55E';" +
        "  setTimeout(function(){try{el.style.outline=o}catch(e){}},500);}catch(e){}}" +
        /* 用原生 setter 赋值，保证 React/Vue/Ember 受控组件能感知（直接改 .value 会被框架覆盖） */
        "function setVal(el,v){try{" +
        "  var d=Object.getOwnPropertyDescriptor(el.__proto__,'value');" +
        "  if(d&&d.set)d.set.call(el,v);else el.value=v;" +
        "  el.dispatchEvent(new Event('focus',{bubbles:true}));" +
        "  el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "  el.dispatchEvent(new Event('change',{bubbles:true}));" +
        "  el.dispatchEvent(new Event('blur',{bubbles:true}));" +
        "  mark(el);return true;}catch(e){return false;}}" +
        "function visible(el){try{var r=el.getBoundingClientRect();" +
        "  return r.width>0&&r.height>0&&getComputedStyle(el).visibility!=='hidden';}catch(e){return true;}}" +
        "function pick(sel){try{var a=document.querySelectorAll(sel);" +
        "  for(var i=0;i<a.length;i++){if(visible(a[i])&&!a[i].disabled&&!a[i].readOnly)return a[i];}}catch(e){}return null;}" +
        "function txt(el){try{return((el.innerText||el.textContent||'')+' '+(el.value||'')+' '" +
        "  +(el.getAttribute('aria-label')||'')).toLowerCase();}catch(e){return '';}}" +
        /* ---- v1.0.7：自动关闭 Linux DO/Discourse 登录前出现的模态公告/通知弹窗 ---- */
        "function dismissNotices(){try{" +
        "  var closes=document.querySelectorAll('.modal-close,.btn-close,.close,button[aria-label*=\\\"close\\\" i],button[aria-label*=\\\"关闭\\\"],.d-modal-cancel,.alert .close');" +
        "  for(var i=0;i<closes.length;i++){var c=closes[i];" +
        "    if(visible(c)&&!c.disabled){c.click();R({ok:true,action:'dismissModal'});return true;}}" +
        "  var modalBtns=document.querySelectorAll('.modal-footer button,.d-modal__footer button,.bootbox button,.alert button,[role=\\\"dialog\\\"] button');" +
        "  var KW=/我知道了|我同意|确认|确定|关闭|dismiss|got it|agree|accept|close/i;" +
        "  for(var j=0;j<modalBtns.length;j++){var mb=modalBtns[j];" +
        "    if(!visible(mb)||mb.disabled||mb.id==='login-button'||mb.type==='submit')continue;" +
        "    if(KW.test(txt(mb))){mb.click();R({ok:true,action:'dismissNotice',btn:txt(mb).slice(0,20)});return true;}}" +
        "}catch(e){}return false;}" +
        /* 账号框：Linux DO/Discourse 用 #login-account-name；GitHub 用 #login_field，站点常见 name=username/email */
        "function accField(){return pick('#login-account-name')||pick('#login_field')||pick('input[autocomplete=\"username\"]')" +
        "  ||pick('input[name=\"login\"]')||pick('input[name=\"username\"]')||pick('input[name=\"email\"]')" +
        "  ||pick('input[type=\"email\"]')||pick('input[id*=\"user\" i]')||pick('input[placeholder*=\"账号\"]')" +
        "  ||pick('input[placeholder*=\"邮箱\"]')||pick('input[placeholder*=\"用户名\"]');}" +
        "function pwdField(){return pick('#login-account-password')||pick('#password')||pick('input[type=\"password\"]');}" +
        /* 2FA 框：GitHub #app_totp / #otp，通用 one-time-code */
        "function otpField(){return pick('#app_totp')||pick('#otp')" +
        "  ||pick('input[autocomplete=\"one-time-code\"]')||pick('input[name*=\"otp\" i]')" +
        "  ||pick('input[name*=\"totp\" i]')||pick('input[name*=\"two\" i]')" +
        "  ||pick('input[placeholder*=\"验证码\"]');}" +
        /* ---- 人机验证挂件：存在且未产出 token 时，不自动提交 ---- */
        "function captchaPending(){try{" +
        "  var boxes=document.querySelectorAll('.cf-turnstile,#cf-turnstile,[data-sitekey],.g-recaptcha,.h-captcha,.altcha,#altcha');" +
        "  if(!boxes.length)return false;" +
        "  var any=false;" +
        "  for(var i=0;i<boxes.length;i++){if(visible(boxes[i]))any=true;}" +
        "  if(!any)return false;" +
        "  var f=document.querySelector('[name=\"cf-turnstile-response\"],[name=\"g-recaptcha-response\"]," +
        "[name=\"h-captcha-response\"],[name=\"altcha\"]');" +
        "  if(f&&f.value&&f.value.length>10)return false;" +   /* 已有 token = 已通过 */
        "  return true;}catch(e){return false;}}" +
        /* ---- 提交按钮：优先表单内 submit，其次按文案匹配 ---- */
        "function submitBtn(near){" +
        "  var b=pick('#login-button')||pick('button[id=\"login-button\"]');" +
        "  if(b&&visible(b)&&!b.disabled)return b;" +
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
        /* ================= 状态机分步执行（阶段0: 关弹窗 -> 阶段1: 填账密 -> 阶段2: 唯一点击登录） ================= */
        "var PHASE=0;" +
        "var loginClicked=false,submittedOtp=false;" +
        "var doneAcc=false,donePwd=false,doneOtp=false;" +
        "function modalPresent(){try{" +
        "  var m=document.querySelectorAll('.modal-container,.d-modal,.modal-inner-container,.bootbox.modal,[role=\"dialog\"]');" +
        "  for(var i=0;i<m.length;i++){if(visible(m[i])&&!m[i].querySelector('#login-account-name,#login_field'))return true;}" +
        "}catch(e){}return false;}" +
        "function tryFill(){" +
        "  if(loginClicked)return;" + /* 已触发登录点击：彻底停止后续动作，绝对不重复点，专心等待人机答题 */
        "  if(PHASE===0){" +
        "    dismissNotices();" +
        "    if(modalPresent())return;" + /* 只要通告弹窗还在，绝不推进到填账密阶段 */
        "    PHASE=1;" +
        "  }" +
        "  var filled=[];" +
        "  if(PHASE===1){" +
        "    if(!doneAcc&&ACC){var a=accField();if(a){" +
        "      if(!a.value){if(setVal(a,ACC)){doneAcc=true;filled.push('account');}}" +
        "      else if(a.value.length>=1){doneAcc=true;}}}" +
        "    if(!donePwd&&PWD){var p=pwdField();if(p){" +
        "      if(!p.value){if(setVal(p,PWD)){donePwd=true;filled.push('password');}}" +
        "      else if(p.value.length>=1){donePwd=true;}}}" +
        "    if(filled.length)R({ok:true,filled:filled,hasOtpField:!!otpField(),otpConfigured:!!OTP});" +
        "    if(donePwd&&(doneAcc||!ACC))PHASE=2;" +
        "  }" +
        "  if(PHASE===2&&AUTO&&!loginClicked){" +
        "    var b=submitBtn(pwdField());" +
        "    if(b&&visible(b)){" +
        "      loginClicked=true;" + /* 严格单次抢占锁，生命周期内只触发一次！ */
        "      try{b.disabled=false;b.removeAttribute('disabled');}catch(e){}" +
        "      if(click(b)){" +
        "        window.__jsfill_done=1;" +
        "        R({ok:true,action:'submitLogin',msg:'已点击登录，请完成人机答题'});" +
        "      }else{loginClicked=false;}" +
        "    }" +
        "  }" +
        "  return doneAcc&&donePwd;}" +
        "tryFill();" +
        "var iv=setInterval(tryFill,120);" + /* 120ms 高频轮询，不依赖固定延时 */
        "var mo=null;" +
        "try{mo=new MutationObserver(function(){tryFill();});" +
        "  mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}" +
        "setTimeout(function(){try{clearInterval(iv);if(mo)mo.disconnect();}catch(e){}" +
        "  R({ok:doneAcc||donePwd,done:true,filledAccount:doneAcc,filledPassword:donePwd,loginClicked:loginClicked});},60000);" +
        "}catch(e){try{window.JustSign.onFill(JSON.stringify({ok:false,message:String(e)}))}catch(e2){}}" +
        "})();";
}