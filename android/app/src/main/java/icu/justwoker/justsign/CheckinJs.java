package icu.justwoker.justsign;

import android.util.Base64;

import org.json.JSONObject;

/**
 * CheckinJs（v0.1.8）— 签到 JS 模板（CheckinActivity 可见兜底 与 OffscreenCheckin 纯后台 共用同一份）：
 *   1) 先用 Cookie+sid(+旧 token) 续期换新 access_token（解 15 分钟短时 token 过期）
 *   2) POST /api/user/checkin；需要 Turnstile → 动态挂官方 invisible 挂件
 *      （真实浏览器指纹，无感自动通过，不伪造、不答题）→ POST /api/user/checkin?turnstile=<token>
 *   3) 成功取 data.quota_awarded（原始 quota 单位，按 quota_per_unit 折算美元）；已签文案识别；结果回传
 *
 * v0.1.8 修复：
 *   - siteKey 缺失时不再直接放弃：改为现场 GET /api/status 取 turnstile_site_key（同源，无跨域问题），
 *     并把它随结果回传给 Java 落库到站点 meta。
 *   - Turnstile 判定不再只看 message 关键字：只要 http=403 / 含 captcha/verify/人机 等信号即触发挑战流程。
 *   - 挂件容器改为独立 div（size:invisible + 不可见定位），不再 render 到 document.body
 *     （body 已有内容时官方脚本可能拒绝 render 或破坏页面）。
 *   - quota 折算改用服务端 quota_per_unit（原写死 500000）。
 *   - 所有字符串插值统一 JSON 转义，杜绝引号/反斜杠注入破坏 JS 语法。
 *   - 每一步失败都带上 http 状态码与阶段名，便于线上定位。
 */
public final class CheckinJs {
    private CheckinJs() {}

    /** 与 Java 侧对齐的模板渲染：所有变量以 JSON 字面量注入（自带引号与转义） */
    public static String render(String sid, String sitekey, String seedToken) {
        return TEMPLATE
                .replace("/*__SID__*/null", jsStr(sid))
                .replace("/*__SITEKEY__*/null", jsStr(sitekey))
                .replace("/*__TOKEN__*/null", jsStr(seedToken));
    }

    /** Java String → 安全 JS 字符串字面量（JSONObject.quote 处理引号/反斜杠/控制字符） */
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
        "var UNIT=500000;" +
        "var usd=function(q){q=Number(q||0);if(!isFinite(q)||q<=0)return 0;" +
        "  return q>=1000?Math.round(q/UNIT*100)/100:q;};" +
        "var H=function(){var hh={'Accept':'application/json'};" +
        "  if(window.__jstoken)hh['Authorization']='Bearer '+window.__jstoken;return hh;};" +
        /* ---- /api/status：拿 quota_per_unit + turnstile_site_key（同源请求，无跨域） ---- */
        "async function loadStatus(){try{" +
        "  var r=await fetch('/api/status',{headers:{'Accept':'application/json'},credentials:'include'});" +
        "  var j=await r.json();var d=(j&&j.data)||{};" +
        "  var u=Number(d.quota_per_unit||0);if(u>0)UNIT=u;" +
        "  var k=d.turnstile_site_key||'';" +
        "  if(k&&!sitekey)sitekey=k;" +
        "  return k;}catch(e){return '';}}" +
        /* ---- 续期：Cookie + sid（+旧 token）换新 access_token ---- */
        "async function refresh(){if(!sid)return;try{P('正在续期登录状态…');" +
        "  var hh={'X-Auth-Session':sid,'Content-Type':'application/json','Accept':'application/json'};" +
        "  if(window.__jstoken)hh['Authorization']='Bearer '+window.__jstoken;" +
        "  var r=await fetch('/api/user/auth/refresh',{method:'POST',credentials:'include',headers:hh});" +
        "  var j=await r.json();var d=(j&&(j.data||j))||{};" +
        "  var tk=d.access_token||d.accessToken||(d.session&&(d.session.access_token||d.session.token))" +
        "    ||(d.token_bundle&&d.token_bundle.access_token)||'';" +
        "  if(tk)window.__jstoken=tk;}catch(e){}}" +
        /* ---- POST 封装：返回 {j,status} 便于按状态码判定 ---- */
        "async function post(u){var st=0;try{" +
        "  var r=await fetch(u,{method:'POST',headers:H(),credentials:'include'});st=r.status;" +
        "  var j=null;try{j=await r.json()}catch(e){}" +
        "  return {j:(j&&typeof j==='object')?j:null,status:st};" +
        "}catch(e){return {j:null,status:st,err:String((e&&e.message)||e)};}}" +
        "var ALREADY=/已签|已领|重复|already|签到过/i;" +
        "var NEEDCAP=/turnstile|captcha|验证|校验|人机|challenge|robot/i;" +
        "var AUTHBAD=/AUTH_TOKEN|unauthorized|无效的?令牌|token.*(过期|无效)|未登录|请先登录/i;" +
        /* ---- Turnstile：官方 invisible 挂件（独立容器，真实指纹，不伪造） ---- */
        "async function turnstileToken(){" +
        "  if(!sitekey)return '';" +
        "  if(!window.turnstile||typeof window.turnstile.render!=='function'){P('加载人机验证组件…');" +
        "    var loaded=await new Promise(function(res){var done=false;" +
        "      var fin=function(v){if(!done){done=true;res(v);}};" +
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
        "    if(!loaded||!window.turnstile||!window.turnstile.render)return '';}" +
        "  P('正在通过人机验证（自动）…');" +
        "  var box=document.getElementById('__jsts_box');" +
        "  if(!box){box=document.createElement('div');box.id='__jsts_box';" +
        "    box.style.cssText='position:fixed;left:0;top:0;width:1px;height:1px;overflow:hidden;opacity:0.01;z-index:-1';" +
        "    document.body.appendChild(box);}" +
        "  box.innerHTML='';" +
        "  return await new Promise(function(res){var done=false;" +
        "    var fin=function(t){if(!done){done=true;res(t||'');}};" +
        "    try{window.turnstile.render(box,{sitekey:sitekey,size:'invisible'," +
        "      callback:function(t){fin(t)},'error-callback':function(){fin('')}," +
        "      'timeout-callback':function(){fin('')},'expired-callback':function(){fin('')}});" +
        "    }catch(e){fin('');}" +
        "    setTimeout(function(){fin('')},45000);});}" +
        /* ---- 主流程 ---- */
        "var siteKeyFromServer=await loadStatus();" +
        "await refresh();" +
        "P('正在签到…');" +
        "var res1=await post('/api/user/checkin');" +
        "var r1=res1.j;var st1=res1.status;" +
        "var out={token:window.__jstoken||'',siteKey:siteKeyFromServer||sitekey||'',unit:UNIT};" +
        "function ok(reward,already,msg){out.ok=true;out.already=!!already;out.reward=reward;out.message=msg;R(out);}" +
        "function bad(msg,auth){out.ok=false;out.message=msg;if(auth)out.auth=true;R(out);}" +
        "if(r1&&r1.success){ok(usd(r1.data&&r1.data.quota_awarded),false,'签到成功');return;}" +
        "var m1=(r1&&r1.message)||('HTTP '+st1);" +
        "if(ALREADY.test(m1)){ok(0,true,'今日已签到');return;}" +
        "if(st1===401||(AUTHBAD.test(m1)&&!NEEDCAP.test(m1))){bad('授权已过期，请回主页点击「重新授权」',true);return;}" +
        "if(NEEDCAP.test(m1)||st1===403){" +
        "  if(!sitekey){bad('需要人机验证，但站点未返回 siteKey（请点刷新后重试）');return;}" +
        "  var tk=await turnstileToken();" +
        "  if(!tk){bad('人机验证未通过（组件超时或被拦截），请重试');return;}" +
        "  P('验证通过，正在签到…');" +
        "  var res2=await post('/api/user/checkin?turnstile='+encodeURIComponent(tk));" +
        "  var r2=res2.j;var st2=res2.status;" +
        "  out.token=window.__jstoken||'';" +
        "  if(r2&&r2.success){ok(usd(r2.data&&r2.data.quota_awarded),false,'签到成功');return;}" +
        "  var m2=(r2&&r2.message)||('HTTP '+st2);" +
        "  if(ALREADY.test(m2)){ok(0,true,'今日已签到');return;}" +
        "  if(st2===401||AUTHBAD.test(m2)){bad('授权已过期，请回主页点击「重新授权」',true);return;}" +
        "  bad(m2||'签到失败');return;}" +
        "bad(m1||'签到失败');" +
        "}catch(e){try{R({ok:false,message:'JS 异常: '+((e&&e.message)||String(e)),token:window.__jstoken||''});}catch(e2){}}" +
        "})();";
}
