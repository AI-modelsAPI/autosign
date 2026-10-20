package icu.justwoker.justsign;

import android.util.Base64;

import org.json.JSONObject;

/**
 * CheckinJs（v0.2.1）— 站点内签到脚本（CheckinActivity 可见兜底 与 OffscreenCheckin 纯后台 共用）。
 *
 * 【v0.2.1 关键修复：先查状态，别硬撞人机验证】
 *   实测该站 turnstile_check=true 时，POST /api/user/checkin 在「今日已签」情况下依然先过人机验证，
 *   于是旧版流程变成：POST → 被要求验证 → 挂 Turnstile → 超时 → 报「人机验证未通过」，
 *   明明今天已签、奖励已到账（$28.07），却判定为失败（用户实测 18:01/18:03 两次失败即此因）。
 *   新流程：
 *     GET /api/user/checkin?month=YYYY-MM  ← 只读接口，不需要人机验证
 *       → stats.checked_in_today == true 或当月 records 里有今天的记录
 *          ⇒ 直接判「今日已签」，奖励取该记录 quota_awarded（可能为 0 = 本站签到不发奖励）
 *       → 未签才 POST 签到；只有真正需要时才碰 Turnstile
 *     人机验证失败后再复查一次状态，避免「其实已签」被误报失败。
 *
 * 【v0.2.1 Turnstile 修复】
 *   官方当前合法 size 只有 normal/flexible/compact，旧写法 size:'invisible' 会让 render 直接
 *   走 error-callback → 必然超时。改用 appearance:'interaction-only'：无需交互时静默通过，
 *   需要交互时挂件才显形。容器改为屏幕居中可见（可见兜底页里用户点得到），
 *   并把 Cloudflare 错误码回传，日志能看到是哪一步被拦。
 *
 * 【奖励判定（有的站发奖励，有的不发）】
 *   已签 + quota_awarded > 0  → 有奖励，展示金额
 *   已签 + quota_awarded == 0 → 已签但本站无奖励（rewardKnown=true, reward=0）
 *   查不到记录                → rewardKnown=false，UI 不编造数字
 */
public final class CheckinJs {
    private CheckinJs() {}

    /** JS 通过 onProgress 发出的特殊信号：Turnstile 可能需要用户点一下 */
    public static final String SIGNAL_NEED_UI = "__NEED_UI__";

    public static String render(String sid, String sitekey, String seedToken) {
        return render(sid, sitekey, seedToken, "");
    }
    /** v0.3.9：siteUserId 为站点数字用户 ID（New API 系请求须带 New-Api-User 头）。
     * 空串时不加该头（兼容非 New API 站/旧数据）。 */
    public static String render(String sid, String sitekey, String seedToken, String siteUserId) {
        return render(sid, sitekey, seedToken, siteUserId, "NEWAPI");
    }

    /**
     * 按站点形态渲染。
     *
     * WELFARE（fengwind 福利站）与 New API 的差异（真机 + 站点 bundle 实证）：
     *   状态：GET  /api/checkin/status        → can_check_in / checked_in_today
     *   签到：POST /api/checkin               → 需 X-Turnstile-Token 头
     *   流水：GET  /api/ledger?limit=N        → items[].amount / status
     * 其它形态沿用 New API 的 /api/user/checkin 与 /api/log/self。
     */
    public static String render(String sid, String sitekey, String seedToken,
                                String siteUserId, String kind) {
        boolean welfare = "WELFARE".equalsIgnoreCase(kind == null ? "" : kind);
        return TEMPLATE
                .replace("/*__SID__*/null", jsStr(sid))
                .replace("/*__SITEKEY__*/null", jsStr(sitekey))
                .replace("/*__TOKEN__*/null", jsStr(seedToken))
                .replace("/*__SITEUID__*/null", jsStr(siteUserId))
                .replace("/*__WELFARE__*/false", welfare ? "true" : "false");
    }

    static String jsStr(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /** 从 JWT payload 解出会话 sid（X-Auth-Session 续期头用）；解析失败返回空串 */
    public static String extractSid(String jwt) {
        try {
            if (jwt == null) return "";
            String[] p = jwt.split("\\.");
            if (p.length < 2) return "";
            String pl = p[1];
            int pad = (4 - pl.length() % 4) % 4;
            for (int i = 0; i < pad; i++) pl += "=";
            byte[] raw = Base64.decode(pl, Base64.URL_SAFE | Base64.NO_WRAP);
            return new JSONObject(new String(raw, "UTF-8")).optString("sid", "");
        } catch (Exception e) { return ""; }
    }

    private static final String TEMPLATE =
        /* 幂等门：同一文档内只跑一次。
         *
         * 关键：整段脚本必须包在 IIFE 里，幂等门用 if/else 而不是顶层 return。
         * 真机血泪（SeekAI，2026-10-19）：写成 `if(x)return;` 会让整段脚本在
         * **编译阶段**就失败 —— "Illegal return statement"（return 只能在函数体内）。
         * Android WebView 的 evaluateJavascript 对编译失败是静默丢弃的：
         * 不抛异常、不走结果回调；而探针脚本却能正常执行，表现就是
         * 「页面已加载、脚本已注入、但永远零回调，100s 后报后台签到超时」。
         * 这个坑用字符串断言和 new Function 都发现不了（后者自动包函数体），
         * 只有 vm.Script 的脚本模式才复现。 */
        "(function(){if(window.__jsCheckin)return;window.__jsCheckin=1;" +
        "(async function(){" +
        "var R=function(o){try{window.JustSign.onResult(JSON.stringify(o))}catch(e){}};" +
        "var P=function(m){try{window.JustSign.onProgress(String(m))}catch(e){}};" +
        "try{" +
        "var sid=/*__SID__*/null;var sitekey=/*__SITEKEY__*/null;var seedtk=/*__TOKEN__*/null;var siteuid=/*__SITEUID__*/null;" +
        "if(seedtk&&!window.__jstoken)window.__jstoken=seedtk;" +
        "var UNIT=500000;var CHECKIN_ON=true;var TS_ON=false;var tsErr='';" +
        "var WELFARE=/*__WELFARE__*/false;" +
        "var usd=function(q){q=Number(q||0);if(!isFinite(q)||q<=0)return 0;" +
        "  return q>=1000?Math.round(q/UNIT*100)/100:q;};" +
        /* 福利站用自己 localStorage 里的 welfare_token；其它站用 seedtk/页面会话。
         * 实测：福利站所有接口都要求 Authorization: Bearer <welfare_token>。 */
        "var H=function(){var hh={'Accept':'application/json'};" +
        "  var t='';" +
        "  if(WELFARE){try{t=localStorage.getItem('welfare_token')||'';}catch(e){}}" +
        "  if(!t&&window.__jstoken)t=window.__jstoken;" +
        "  if(t)hh['Authorization']='Bearer '+t;" +
        "  if(!WELFARE&&siteuid)hh['New-Api-User']=siteuid;return hh;};" +
        "function pad2(n){return n<10?('0'+n):(''+n)}" +
        "function today(){var d=new Date();return d.getFullYear()+'-'+pad2(d.getMonth()+1)+'-'+pad2(d.getDate());}" +
        "function month(){var d=new Date();return d.getFullYear()+'-'+pad2(d.getMonth()+1);}" +
        /* ---- 带超时的 fetch ----
         * 真机实测（SeekAI，2026-10-19）：脚本注入成功（probe=js-alive 有日志），
         * 但全程零回调、连第一句 onProgress 都没出现 —— 说明卡在第一个 await 上。
         * WebView 里的 fetch 在网络异常/被 WAF 挂起时不会 reject，而是一直 pending，
         * 于是整个 async 函数永久停住，Java 侧只能干等到超时报「后台签到超时」。
         * 所有请求一律走带超时的 fetchT：超时即 reject，由各自的 try/catch 收尾，
         * 保证任何一步出问题都能走回 R(...) 回传结果，而不是静默挂死。 */
        "function fetchT(u,o,ms){return new Promise(function(res,rej){" +
        "  var done=false;" +
        "  var t=setTimeout(function(){if(!done){done=true;rej(new Error('fetch-timeout'))}},ms||12000);" +
        "  fetch(u,o).then(function(r){if(!done){done=true;clearTimeout(t);res(r)}}," +
        "    function(e){if(!done){done=true;clearTimeout(t);rej(e)}});});}" +
        /* ---- /api/status：quota 单位 + turnstile siteKey + 站点是否开启签到 ---- */
        "async function loadStatus(){try{" +
        "  var r=await fetchT('/api/status',{headers:{'Accept':'application/json'},credentials:'include'},12000);" +
        "  var j=await r.json();var d=(j&&j.data)||{};" +
        "  var u=Number(d.quota_per_unit||0);if(u>0)UNIT=u;" +
        "  if(d.checkin_enabled===false)CHECKIN_ON=false;" +
        "  TS_ON=!!d.turnstile_check;" +
        "  var k=d.turnstile_site_key||'';" +
        "  if(k&&!sitekey)sitekey=k;" +
        "  return k;}catch(e){return '';}}" +
        /* ---- 页面内会话兜底（v0.2.3）：
           token 由 Java 侧 SilentAuth 保证新鲜，这里不再做续期。
           但离屏 WebView 加载的是站点主页，站点自己的 SPA 可能已把新 token 写进
           localStorage['new-api:auth-session']；若我们带来的 seed token 不可用，
           就用页面里现成的那个，避免白跑。 ---- */
        "function pageToken(){try{" +
        "  var keys=['new-api:auth-session','auth-session','user'];" +
        "  for(var i=0;i<keys.length;i++){" +
        "    var raw=localStorage.getItem(keys[i]);if(!raw)continue;" +
        "    var o=null;try{o=JSON.parse(raw)}catch(e){continue}" +
        "    if(!o)continue;" +
        "    var t=o.access_token||o.accessToken||(o.state&&o.state.accessToken)||''" +
        "      ||(o.session&&(o.session.access_token||o.session.token))||'';" +
        "    if(t&&String(t).length>20)return String(t);}" +
        "}catch(e){}return '';}" +
        "async function refresh(){try{" +
        "  var pt=pageToken();" +
        "  if(pt&&pt!==window.__jstoken){window.__jstoken=pt;P('已采用页面内会话');}" +
        "}catch(e){}}" +
        /* ---- GET /api/user/checkin?month=YYYY-MM：只读状态（无需人机验证） ---- */
        "async function stateOf(){try{" +
        /* WELFARE（福利站）：独立的状态端点与字段。 */
        "  if(WELFARE){" +
        "    var r=await fetchT('/api/checkin/status',{headers:H(),credentials:'include'},12000);" +
        "    var st=r.status;var j=null;try{j=await r.json()}catch(e){}" +
        "    if(st!==200)return {ok:false,http:st,message:'HTTP '+st};" +
        "    var d=(j&&j.data)||j||{};" +
        "    var fin=(d.checked_in_today===true)||(d.checked_in===true);" +
        "    var tdy=d.today||{};" +
        "    var rw=Number(tdy.amount!==undefined?tdy.amount:0)||0;" +
        "    return {ok:true,http:200,checked:fin,reward:rw,rewardKnown:fin&&rw>0," +
        "            eligible:(d.can_check_in===true)||(d.checkin_eligible===true)};" +
        "  }" +
        "  var r=await fetchT('/api/user/checkin?month='+encodeURIComponent(month())," +
        "    {headers:H(),credentials:'include'});" +
        "  var st=r.status;var j=null;try{j=await r.json()}catch(e){}" +
        "  if(st!==200||!j||!j.success)return {ok:false,http:st,message:(j&&j.message)||('HTTP '+st)};" +
        "  var d=j.data||{};var s=d.stats||{};" +
        "  var recs=(s.records||d.records||[]);" +
        "  var t=today();var rec=null;" +
        "  for(var i=0;i<recs.length;i++){var o=recs[i]||{};" +
        "    if((o.checkin_date||o.date||'')===t){rec=o;break;}}" +
        "  var fin=(s.checked_in_today===true)||!!rec;" +
        "  var rw=rec?usd(rec.quota_awarded!==undefined?rec.quota_awarded:rec.quota):0;" +
        "  return {ok:true,http:200,checked:fin,reward:rw,rewardKnown:!!rec,streak:Number(s.streak||0)};" +
        "}catch(e){return {ok:false,http:0,message:String((e&&e.message)||e)};}}" +
        /* ---- GET /api/log/self：查今日「签到」类日志（登录即签到型的确证来源） ----
         * 这批站登录时就已发放奖励，日志里会留一条 type=4/签到 记录。
         * 只读接口，不触发人机验证。奖励可能为 0（本站当日不发奖励）。 */
        "async function todayBonusLog(){try{" +
        "  var t=today();" +
        "  var d0=new Date(t+'T00:00:00');" +
        "  var s0=Math.floor(d0.getTime()/1000);" +
        "  var e0=s0+86400;" +
        /* 实测：过滤签到记录必须用 type=4；category=系统 站点不认（返回全部日志）。
         * 条目里 quota 恒为 0，金额只写在 content：「用户签到，获得额度 ＄20.64 额度」。 */
        "  var urls=['/api/log/self?type=4&limit=30&page=1'," +
        "            '/api/log/self?limit=50&page=1'];" +
        "  for(var u=0;u<urls.length;u++){" +
        "    var r=null;try{r=await fetchT(urls[u],{headers:H(),credentials:'include'},12000);}catch(e){continue}" +
        "    if(!r||r.status!==200)continue;" +
        "    var j=null;try{j=await r.json()}catch(e){continue}" +
        "    if(!j||!j.success)continue;" +
        "    var d=j.data;var items=[];" +
        "    if(Array.isArray(d))items=d;" +
        "    else if(d&&Array.isArray(d.items))items=d.items;" +
        "    else if(d&&Array.isArray(d.data))items=d.data;" +
        "    else if(d&&Array.isArray(d.logs))items=d.logs;" +
        "    for(var i=0;i<items.length;i++){var o=items[i]||{};" +
        "      var txt=String(o.content||o.description||o.remark||o.message||'');" +
        "      if(!/签到|check.?in/i.test(txt))continue;" +
        /* 同为 type=4 的还有「新用户注册赠送」「使用邀请码赠送」，不是每日签到 */
        "      if(/注册|邀请|兑换/.test(txt))continue;" +
        "      var ts=Number(o.created_at||o.createdAt||o.timestamp||o.created_time||0);" +
        "      if(ts>0){if(ts>1e12)ts=Math.floor(ts/1000);" +
        "        if(ts<s0||ts>=e0)continue;}" +
        "      var mm=txt.match(/[＄$]\\s*([0-9]+(?:\\.[0-9]+)?)/);" +
        "      if(mm)return {found:true,reward:Math.round(Number(mm[1])*100)/100,known:true};" +
        "      return {found:true,reward:0,known:false};}" +
        "  }" +
        "  return {found:false,reward:0,known:false};" +
        "}catch(e){return {found:false,reward:0,known:false};}}" +
        /* ---- POST 封装 ---- */
        "async function post(u){var st=0;try{" +
        "  var r=await fetchT(u,{method:'POST',headers:H(),credentials:'include'},15000);st=r.status;" +
        "  var j=null;try{j=await r.json()}catch(e){}" +
        "  return {j:(j&&typeof j==='object')?j:null,status:st};" +
        "}catch(e){return {j:null,status:st,err:String((e&&e.message)||e)};}}" +
        "var ALREADY=/已签|已领|重复|already|签到过|checked.?in/i;" +
        "var NEEDCAP=/turnstile|captcha|验证|校验|人机|challenge|robot/i;" +
        "var AUTHBAD=/AUTH_TOKEN|AUTH_UNAUTHORIZED|unauthorized|无效的?令牌|token.*(过期|无效)|未登录|请先登录/i;" +
        /* ---- Turnstile：官方挂件，appearance=interaction-only（无需交互静默过） ---- */
        "async function turnstileToken(){" +
        "  if(!sitekey)return '';" +
        "  if(!window.turnstile||typeof window.turnstile.render!=='function'){P('加载人机验证组件…');" +
        "    var loaded=await new Promise(function(res){var fired=false;" +
        "      var fin=function(v){if(!fired){fired=true;res(v);}};" +
        "      var ex=document.getElementById('cf-turnstile')||document.querySelector('script[data-jsts]');" +
        "      var sc=ex||document.createElement('script');" +
        "      if(!ex){sc.id='cf-turnstile';sc.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';" +
        "        sc.async=true;sc.defer=true;sc.setAttribute('data-jsts','1');" +
        "        sc.onload=function(){fin(true)};sc.onerror=function(){fin(false)};" +
        "        (document.head||document.documentElement).appendChild(sc);}" +
        "      var t0=Date.now();var iv=setInterval(function(){" +
        "        if(window.turnstile&&window.turnstile.render){clearInterval(iv);fin(true);}" +
        "        else if(Date.now()-t0>20000){clearInterval(iv);fin(false);}},200);" +
        "    });" +
        "    if(!loaded||!window.turnstile||!window.turnstile.render){tsErr='script-load-failed';return '';}}" +
        "  P('正在通过人机验证…');" +
        "  var box=document.getElementById('__jsts_box');" +
        "  if(!box){box=document.createElement('div');box.id='__jsts_box';" +
        "    box.style.cssText='position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);" +
        "z-index:2147483647;background:#fff;padding:10px;border-radius:10px;width:300px;height:65px;min-width:300px;min-height:65px;';" +
        "    (document.body||document.documentElement).appendChild(box);}" +
        "  box.innerHTML='';await new Promise(function(w){setTimeout(w,150);});" +
        "  return await new Promise(function(res){var fired=false;" +
        "    var fin=function(t){if(!fired){fired=true;res(t||'');}};" +
        "    try{var wid=window.turnstile.render(box,{sitekey:sitekey," +
        "      callback:function(t){fin(t)}," +
        "      'error-callback':function(c){tsErr='cf-'+(c||'error');fin('')}," +
        "      'timeout-callback':function(){tsErr='cf-timeout';fin('')}," +
        "      'expired-callback':function(){tsErr='cf-expired';fin('')}});" +
        "      if(wid&&window.turnstile.execute){try{window.turnstile.execute(wid);}catch(e){}}" +
        "    }catch(e){tsErr='render-throw';fin('');}" +
        "    setTimeout(function(){if(!fired)P('" + SIGNAL_NEED_UI + "');},6000);" +
        /* 25s 足够 interaction-only 静默通过；过不去说明本次必须人工交互。
         * 这批站登录即发奖励，随后会走日志兜底确认，没必要白等 60 秒。 */
        "    setTimeout(function(){tsErr=tsErr||'wait-timeout';fin('')},25000);});}" +
        "/* ---- 站点自己发出的签到 POST 结果（只作提示，成功与否以服务端状态为准） ----\n" +
        " * 只认 POST、路径精确为 /api/user/checkin（去掉 query）。\n" +
        " * 旧版缺陷：对任何方法、URL 含 /api/user/checkin 的响应都判成功，\n" +
        " * 于是 stateOf() 的 GET 状态查询（success:true、无 quota_awarded）被当成「签到成功，奖励 0」。 */\n" +
        "var hookRes=null;\n" +
        "try{var origF=window.fetch;window.fetch=async function(){\n" +
        "  var u=String((arguments&&arguments[0])||'');\n" +
        "  var mth=String((arguments&&arguments[1]&&arguments[1].method)||'GET').toUpperCase();\n" +
        "  var r=await origF.apply(this,arguments);\n" +
        "  try{\n" +
        "    var path=u.split('?')[0];\n" +
        "    if(mth==='POST'&&/\\/api\\/user\\/checkin$/.test(path)){\n" +
        "      r.clone().json().then(function(j){hookRes={status:r.status,j:j};}).catch(function(){});\n" +
        "    }\n" +
        "  }catch(e){}\n" +
        "  return r;};}catch(e){}\n" +
        "function sleep(ms){return new Promise(function(w){setTimeout(w,ms);});}\n" +
        "/* 主流程 ================= */\n" +
        "var siteKeyFromServer=await loadStatus();\n" +
        "await refresh();\n" +
        "var out={token:window.__jstoken||'',siteKey:siteKeyFromServer||sitekey||'',unit:UNIT};\n" +
        "var finished=false;\n" +
        "var finish=function(ok,already,reward,rewardKnown,msg,extra){\n" +
        "  if(finished)return;finished=true;\n" +
        "  out.ok=ok;out.already=!!already;out.reward=reward;out.rewardKnown=!!rewardKnown;\n" +
        "  out.message=msg;out.token=window.__jstoken||out.token;\n" +
        "  if(tsErr)out.tsError=tsErr;if(extra)out.detail=extra;R(out);};\n" +
        "/* 只有服务端状态 checked_in_today==true 才调用本函数 */\n" +
        "var doneOk=function(c,already,src){\n" +
        "  var rw=c.ok?Number(c.reward||0):0;var known=!!(c.ok&&c.rewardKnown);\n" +
        "  var head=already?'今日已签到':'签到成功';\n" +
        "  var msg=known?(rw>0?(head+' +$'+rw):(head+'（本站无奖励）')):(head+'（奖励未知）');\n" +
        "  finish(true,already,rw,known,msg,'来源: '+src);};\n" +
        "/* 重新读取服务端状态，最多 4 次（每次间隔 1.5s），应对站点写入延迟 */\n" +
        "var verifyChecked=async function(){\n" +
        "  var c=null;\n" +
        "  for(var k=0;k<4;k++){c=await stateOf();if(c.ok&&c.checked)return c;await sleep(1500);}\n" +
        "  return c||{ok:false};};\n" +
        "async function postCheckin(tk){\n" +
        "  return await post('/api/user/checkin'+(tk?('?turnstile='+encodeURIComponent(tk)):''));}\n" +
        "/* 分类站点返回（POST /api/user/checkin 的响应） */\n" +
        "function classify(res){\n" +
        "  var j=res.j,st=res.status;var m=(j&&j.message)||('HTTP '+st);\n" +
        "  if(j&&j.success===true)return 'ok';\n" +
        "  if(ALREADY.test(m)&&!NEEDCAP.test(m))return 'already';\n" +
        "  if(st===401||(AUTHBAD.test(m)&&!NEEDCAP.test(m)))return 'auth';\n" +
        "  if(st===429)return 'ratelimit';\n" +
        "  if(NEEDCAP.test(m)||st===403||TS_ON)return 'captcha';\n" +
        "  return 'fail';}\n" +
        "/* DOM 驱动：仅 SeekAI /profile。点击站点自己的「立即签到」，由站点自己的 JS 完成 POST 与人机验证；\n" +
        " * 成功与否只看服务端状态。超时返回 false，交给 API 路径（API 路径同样以服务端状态收尾）。 */\n" +
        "async function tryDomCheckin(){try{\n" +
        "  if(WELFARE)return false;\n" +
        "  var t0=Date.now();var btn=null;\n" +
        "  while(Date.now()-t0<8000&&!btn){\n" +
        "    var btns=document.querySelectorAll('button,[role=\"button\"],a');\n" +
        "    for(var i=0;i<btns.length;i++){\n" +
        "      var b=btns[i];var txt=String(b.textContent||b.innerText||'').trim();\n" +
        "      if(/Check\\s*in\\s*now|立即签到/i.test(txt)&&!b.disabled){btn=b;break;}\n" +
        "    }\n" +
        "    if(!btn)await sleep(500);\n" +
        "  }\n" +
        "  if(!btn)return false;\n" +
        "  P('找到页面签到按钮，正在点击…');\n" +
        "  btn.click();\n" +
        "  var w0=Date.now();\n" +
        "  while(Date.now()-w0<120000){\n" +
        "    await sleep(2000);\n" +
        "    var c=await stateOf();\n" +
        "    if(c.ok&&c.checked){doneOk(c,false,'页面签到按钮 · 服务端 stats.checked_in_today');return true;}\n" +
        "    if(document.querySelector('.cf-turnstile,iframe[src*=\"challenges.cloudflare.com\"]'))P('正在进行人机验证…');\n" +
        "  }\n" +
        "  return false;\n" +
        "}catch(e){return false;}}\n" +
        "if(!CHECKIN_ON){finish(false,false,0,false,'该站点未开启每日签到');return;}\n" +
        "/* 第 1 步：只读状态。已签即结束，绝不触碰人机验证 */\n" +
        "P('正在查询今日签到状态…');\n" +
        "var s1=await stateOf();\n" +
        "if(!s1.ok){\n" +
        "  if(s1.http===401||AUTHBAD.test(String(s1.message||''))){out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}\n" +
        "  finish(false,false,0,false,'签到状态读取失败：'+(s1.message||('HTTP '+s1.http)));return;}\n" +
        "if(s1.checked){doneOk(s1,true,'服务端 stats.checked_in_today');return;}\n" +
        "/* 登录即签到型（just / kk）：站点日志今日有签到记录即确证 */\n" +
        "var lb0=await todayBonusLog();\n" +
        "if(lb0.found){finish(true,true,lb0.reward,lb0.known,\n" +
        "  (lb0.known&&lb0.reward>0)?'登录即签到 · 今日奖励已到账':'登录即签到 · 今日已签（无奖励）',\n" +
        "  '来源: 站点日志今日签到记录');return;}\n" +
        "/* WELFARE（福利站）：独立端点，POST 需 X-Turnstile-Token 头 */\n" +
        "if(WELFARE){\n" +
        "  var tk0=await turnstileToken();\n" +
        "  if(!tk0){finish(false,false,0,false,'turnstile-wait-timeout：验证码未就绪');return;}\n" +
        "  var rr=null,ss=0;\n" +
        "  try{var rr0=await fetchT('/api/checkin',{method:'POST',credentials:'include',\n" +
        "        headers:Object.assign({},H(),{'Content-Type':'application/json','X-Turnstile-Token':tk0}),\n" +
        "        body:'{}'},15000);ss=rr0.status;try{rr=await rr0.json()}catch(e){}}catch(e){}\n" +
        "  var mm=(rr&&(rr.message||rr.error))||('HTTP '+ss);\n" +
        "  if(ss===200&&rr&&rr.success!==false){\n" +
        "    var cw=await verifyChecked();\n" +
        "    if(cw.ok&&cw.checked){doneOk(cw,false,'福利站签到 · 服务端状态核验');return;}\n" +
        "    finish(false,false,0,false,'福利站返回成功，但服务端未核验到今日记录');return;}\n" +
        "  if(ALREADY.test(mm)){var cwa=await stateOf();doneOk(cwa,true,'福利站已签');return;}\n" +
        "  if(ss===401||AUTHBAD.test(mm)){out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}\n" +
        "  finish(false,false,0,false,mm);return;\n" +
        "}\n" +
        "/* 第 2 步：SeekAI /profile 走站点自己的按钮 */\n" +
        "if(/seekai\\.cc/i.test(location.hostname)&&location.pathname.indexOf('/profile')!==-1){\n" +
        "  var domDone=await tryDomCheckin();\n" +
        "  if(domDone)return;\n" +
        "  P('页面按钮未完成，改用接口签到…');\n" +
        "  var sNow=await stateOf();\n" +
        "  if(sNow.ok&&sNow.checked){doneOk(sNow,true,'服务端 stats.checked_in_today');return;}\n" +
        "}\n" +
        "/* 第 3 步：接口签到。先无令牌 POST（站点同样如此），被要求人机再挂 Turnstile */\n" +
        "P('正在签到…');\n" +
        "var r1=await postCheckin(null);\n" +
        "var k1=classify(r1);\n" +
        "if(k1==='ok'||k1==='already'){\n" +
        "  var c1=await verifyChecked();\n" +
        "  if(c1.ok&&c1.checked){doneOk(c1,k1==='already','接口签到 · 服务端状态核验');return;}\n" +
        "  finish(false,false,0,false,k1==='already'?'站点提示已签，但服务端未核验到今日记录':'站点返回成功，但服务端未核验到今日记录（请人工核对）');return;}\n" +
        "if(k1==='auth'){out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}\n" +
        "if(k1==='ratelimit'){finish(false,false,0,false,'站点限流（429），请稍后重试');return;}\n" +
        "if(k1!=='captcha'){\n" +
        "  var cf=await verifyChecked();\n" +
        "  if(cf.ok&&cf.checked){doneOk(cf,true,'服务端状态核验（POST 失败后）');return;}\n" +
        "  finish(false,false,0,false,(r1.j&&r1.j.message)||('HTTP '+r1.status));return;}\n" +
        "/* 第 3 步：人机验证（站点 turnstile_check 开启，或响应明确要求人机） */\n" +
        "if(!sitekey){finish(false,false,0,false,'需要人机验证，但站点未返回 siteKey（请点刷新后重试）');return;}\n" +
        "P('正在进行无感人机验证…');\n" +
        "var tk=await turnstileToken();\n" +
        "if(!tk){\n" +
        "  var c2=await stateOf();\n" +
        "  if(c2.ok&&c2.checked){doneOk(c2,true,'人机未完成，服务端已有今日记录');return;}\n" +
        "  var lb=await todayBonusLog();\n" +
        "  if(lb.found){finish(true,true,lb.reward,lb.known,\n" +
        "    (lb.known&&lb.reward>0)?'登录即签到 · 今日奖励已到账':'登录即签到 · 今日已签（无奖励）',\n" +
        "    '来源: 站点日志今日签到记录（人机未完成）');return;}\n" +
        "  finish(false,false,0,false,'人机验证未通过'+(tsErr?('（'+tsErr+'）'):''),'sitekey='+String(sitekey).slice(0,10)+'…');return;}\n" +
        "P('验证通过，正在签到…');\n" +
        "var r2=await postCheckin(tk);\n" +
        "var k2=classify(r2);\n" +
        "if(k2==='ok'||k2==='already'){\n" +
        "  var c3=await verifyChecked();\n" +
        "  if(c3.ok&&c3.checked){doneOk(c3,k2==='already','人机验证后签到 · 服务端状态核验');return;}\n" +
        "  finish(false,false,0,false,'站点返回成功，但服务端未核验到今日记录（请人工核对）');return;}\n" +
        "if(k2==='auth'){out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}\n" +
        "var c4=await verifyChecked();\n" +
        "if(c4.ok&&c4.checked){doneOk(c4,true,'服务端状态核验（POST 失败后）');return;}\n" +
        "finish(false,false,0,false,(r2.j&&r2.j.message)||('HTTP '+r2.status));\n" +
        "}catch(e){try{R({ok:false,message:'JS 异常: '+((e&&e.message)||String(e)),token:window.__jstoken||''});}catch(e2){}}\n" +
        "})();})();\n";
}