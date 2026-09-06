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
        return TEMPLATE
                .replace("/*__SID__*/null", jsStr(sid))
                .replace("/*__SITEKEY__*/null", jsStr(sitekey))
                .replace("/*__TOKEN__*/null", jsStr(seedToken));
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
        "(async function(){" +
        "var R=function(o){try{window.JustSign.onResult(JSON.stringify(o))}catch(e){}};" +
        "var P=function(m){try{window.JustSign.onProgress(String(m))}catch(e){}};" +
        "try{" +
        "var sid=/*__SID__*/null;var sitekey=/*__SITEKEY__*/null;var seedtk=/*__TOKEN__*/null;" +
        "if(seedtk&&!window.__jstoken)window.__jstoken=seedtk;" +
        "var UNIT=500000;var CHECKIN_ON=true;var TS_ON=false;var tsErr='';" +
        "var usd=function(q){q=Number(q||0);if(!isFinite(q)||q<=0)return 0;" +
        "  return q>=1000?Math.round(q/UNIT*100)/100:q;};" +
        "var H=function(){var hh={'Accept':'application/json'};" +
        "  if(window.__jstoken)hh['Authorization']='Bearer '+window.__jstoken;return hh;};" +
        "function pad2(n){return n<10?('0'+n):(''+n)}" +
        "function today(){var d=new Date();return d.getFullYear()+'-'+pad2(d.getMonth()+1)+'-'+pad2(d.getDate());}" +
        "function month(){var d=new Date();return d.getFullYear()+'-'+pad2(d.getMonth()+1);}" +
        /* ---- /api/status：quota 单位 + turnstile siteKey + 站点是否开启签到 ---- */
        "async function loadStatus(){try{" +
        "  var r=await fetch('/api/status',{headers:{'Accept':'application/json'},credentials:'include'});" +
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
        "  var r=await fetch('/api/user/checkin?month='+encodeURIComponent(month())," +
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
        "    var r=null;try{r=await fetch(urls[u],{headers:H(),credentials:'include'});}catch(e){continue}" +
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
        "  var r=await fetch(u,{method:'POST',headers:H(),credentials:'include'});st=r.status;" +
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
        "      var ex=document.querySelector('script[data-jsts]');" +
        "      var sc=ex||document.createElement('script');" +
        "      if(!ex){sc.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';" +
        "        sc.async=true;sc.defer=true;sc.setAttribute('data-jsts','1');" +
        "        sc.onload=function(){fin(true)};sc.onerror=function(){fin(false)};" +
        "        document.head.appendChild(sc);}" +
        "      var t0=Date.now();var iv=setInterval(function(){" +
        "        if(window.turnstile&&window.turnstile.render){clearInterval(iv);fin(true);}" +
        "        else if(Date.now()-t0>20000){clearInterval(iv);fin(false);}},200);" +
        "    });" +
        "    if(!loaded||!window.turnstile||!window.turnstile.render){tsErr='script-load-failed';return '';}}" +
        "  P('正在通过人机验证…');" +
        "  var box=document.getElementById('__jsts_box');" +
        "  if(!box){box=document.createElement('div');box.id='__jsts_box';" +
        "    box.style.cssText='position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);" +
        "z-index:2147483647;background:#fff;padding:10px;border-radius:10px';" +
        "    document.body.appendChild(box);}" +
        "  box.innerHTML='';" +
        "  return await new Promise(function(res){var fired=false;" +
        "    var fin=function(t){if(!fired){fired=true;res(t||'');}};" +
        "    try{window.turnstile.render(box,{sitekey:sitekey,appearance:'interaction-only'," +
        "      callback:function(t){fin(t)}," +
        "      'error-callback':function(c){tsErr='cf-'+(c||'error');fin('')}," +
        "      'timeout-callback':function(){tsErr='cf-timeout';fin('')}," +
        "      'expired-callback':function(){tsErr='cf-expired';fin('')}});" +
        "    }catch(e){tsErr='render-throw';fin('');}" +
        "    setTimeout(function(){if(!fired)P('" + SIGNAL_NEED_UI + "');},6000);" +
        /* 25s 足够 interaction-only 静默通过；过不去说明本次必须人工交互。
         * 这批站登录即发奖励，随后会走日志兜底确认，没必要白等 60 秒。 */
        "    setTimeout(function(){tsErr=tsErr||'wait-timeout';fin('')},25000);});}" +
        /* ================= 主流程 ================= */
        "var siteKeyFromServer=await loadStatus();" +
        "await refresh();" +
        "var out={token:window.__jstoken||'',siteKey:siteKeyFromServer||sitekey||'',unit:UNIT};" +
        "function finish(ok,already,reward,rewardKnown,msg,extra){" +
        "  out.ok=ok;out.already=!!already;out.reward=reward;out.rewardKnown=!!rewardKnown;" +
        "  out.message=msg;out.token=window.__jstoken||out.token;" +
        "  if(tsErr)out.tsError=tsErr;if(extra)out.detail=extra;R(out);}" +
        "if(!CHECKIN_ON){finish(false,false,0,false,'该站点未开启每日签到');return;}" +
        /* 第 1 步：只读状态 —— 已签就到此为止，绝不去碰人机验证 */
        "P('正在查询今日签到状态…');" +
        "var s1=await stateOf();" +
        "if(s1.ok&&s1.checked){" +
        "  finish(true,true,s1.reward,s1.rewardKnown," +
        "    (s1.rewardKnown&&s1.reward>0)?'今日已签到':(s1.rewardKnown?'今日已签到（本站无奖励）':'今日已签到')," +
        "    '来源: 服务端签到记录');return;}" +
        /* 登录即签到型（just / kk）：站点在 OAuth 登录时就已发奖励，
         * checkin 接口的 stats 有时并不回写。先查日志确证，
         * 命中就直接判成功，完全不碰人机验证。 */
        "var lb0=await todayBonusLog();" +
        "if(lb0.found){finish(true,true,lb0.reward,lb0.known," +
        "  (lb0.known&&lb0.reward>0)?'登录即签到 · 今日奖励已到账':'登录即签到 · 今日已签（无奖励）'," +
        "  '来源: 站点日志今日签到记录');return;}" +
        "if(!s1.ok&&(s1.http===401||AUTHBAD.test(String(s1.message||'')))){" +
        "  out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}" +
        /* 第 2 步：未签 → 正式签到 */
        "P('正在签到…');" +
        "var res1=await post('/api/user/checkin');" +
        "var r1=res1.j;var st1=res1.status;" +
        "if(r1&&r1.success){var rw=usd(r1.data&&r1.data.quota_awarded);" +
        "  finish(true,false,rw,true,rw>0?'签到成功':'签到成功（本站无奖励）');return;}" +
        "var m1=(r1&&r1.message)||('HTTP '+st1);" +
        "if(ALREADY.test(m1)){var c1=await stateOf();" +
        "  finish(true,true,c1.ok?c1.reward:0,c1.ok&&c1.rewardKnown,'今日已签到');return;}" +
        "if(st1===401||(AUTHBAD.test(m1)&&!NEEDCAP.test(m1))){" +
        "  out.auth=true;finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}" +
        "if(st1===429){finish(false,false,0,false,'站点限流（429），代理节点可能被拦，请换节点后重试');return;}" +
        /* 第 3 步：需要人机验证 */
        "if(NEEDCAP.test(m1)||st1===403||TS_ON){" +
        "  if(!sitekey){finish(false,false,0,false,'需要人机验证，但站点未返回 siteKey（请点刷新后重试）');return;}" +
        "  var tk=await turnstileToken();" +
        "  if(!tk){" +
        "    var c2=await stateOf();" +
        "    if(c2.ok&&c2.checked){finish(true,true,c2.reward,c2.rewardKnown,'今日已签到'," +
        "      '人机验证未完成，但服务端已有今日记录');return;}" +
        /* 登录即签到型兜底：这批站（just / kk）登录时就已发放奖励，
         * 人机验证只是签到按钮的附加校验。日志里若已有今日签到记录，
         * 说明奖励其实已到账，不该再报失败。奖励可能为 0（本站当日不发）。 */
        "    var lb=await todayBonusLog();" +
        "    if(lb.found){finish(true,true,lb.reward,lb.known," +
        "      (lb.known&&lb.reward>0)?'登录即签到 · 今日奖励已到账':'登录即签到 · 今日已签（无奖励）'," +
        "      '来源: 站点日志今日签到记录（人机验证未完成但奖励已发）');return;}" +
        "    finish(false,false,0,false,'人机验证未通过'+(tsErr?('（'+tsErr+'）'):'')," +
        "      'sitekey='+String(sitekey).slice(0,10)+'…');return;}" +
        "  P('验证通过，正在签到…');" +
        "  var res2=await post('/api/user/checkin?turnstile='+encodeURIComponent(tk));" +
        "  var r2=res2.j;var st2=res2.status;" +
        "  if(r2&&r2.success){var rw2=usd(r2.data&&r2.data.quota_awarded);" +
        "    finish(true,false,rw2,true,rw2>0?'签到成功':'签到成功（本站无奖励）');return;}" +
        "  var m2=(r2&&r2.message)||('HTTP '+st2);" +
        "  if(ALREADY.test(m2)){var c3=await stateOf();" +
        "    finish(true,true,c3.ok?c3.reward:0,c3.ok&&c3.rewardKnown,'今日已签到');return;}" +
        "  if(st2===401||AUTHBAD.test(m2)){out.auth=true;" +
        "    finish(false,false,0,false,'授权已过期，请回主页点击「重新授权」');return;}" +
        "  finish(false,false,0,false,m2||'签到失败');return;}" +
        "finish(false,false,0,false,m1||'签到失败');" +
        "}catch(e){try{R({ok:false,message:'JS 异常: '+((e&&e.message)||String(e)),token:window.__jstoken||''});}catch(e2){}}" +
        "})();";
}