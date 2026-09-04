package icu.justwoker.justsign;

import android.util.Base64;

import org.json.JSONObject;

/**
 * CheckinJs（v0.1.6）— 签到 JS 模板（CheckinActivity 可见兑底 与 OffscreenCheckin 纯后台 共用同一份）：
 *   1) 先用 Cookie+sid(+旧 token) 续期换新 access_token（解 15 分钟短时 token 过期）
 *   2) POST /api/user/checkin；message 含 Turnstile → 动态挂官方 invisible 挂件
 *      （真实浏览器指纹，无感自动通过，不伪造、不答题）→ POST /api/user/checkin?turnstile=<token>
 *   3) 成功取 data.quota_awarded（原始 quota 单位，≥1000 折算美元）；已签文案识别；结果回传
 */
public final class CheckinJs {
    private CheckinJs() {}

    public static String render(String sid, String sitekey, String seedToken) {
        return TEMPLATE
                .replace("__SID__", sid == null ? "" : sid)
                .replace("__SITEKEY__", sitekey == null ? "" : sitekey)
                .replace("__TOKEN__", seedToken == null ? "" : seedToken);
    }

    /** 从 JWT payload 解出会话 sid（X-Auth-Session 续期头用）；解析失败返回空串 */
    public static String extractSid(String jwt) {
        try {
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
        "const R=(o)=>{try{window.JustSign.onResult(JSON.stringify(o))}catch(e){}};" +
        "const P=(m)=>{try{window.JustSign.onProgress(m)}catch(e){}};" +
        "const sid='__SID__';const sitekey='__SITEKEY__';const seedtk='__TOKEN__';" +
        "if(seedtk&&!window.__jstoken)window.__jstoken=seedtk;" +
        "const usd=(q)=>{q=Number(q||0);return q>=1000?Math.round(q/500000*100)/100:q;};" +
        "const H=()=>{const hh={'Accept':'application/json'};" +
        "  if(window.__jstoken) hh['Authorization']='Bearer '+window.__jstoken;return hh;};" +
        "async function refresh(){" +
        "  if(!sid)return;" +
        "  try{P('正在续期登录状态…');" +
        "    const hh={'X-Auth-Session':sid,'Content-Type':'application/json'};" +
        "    if(window.__jstoken)hh['Authorization']='Bearer '+window.__jstoken;" +
        "    const r=await fetch('/api/user/auth/refresh',{method:'POST',credentials:'include',headers:hh});" +
        "    const j=await r.json();const d=(j&&(j.data||j))||{};" +
        "    const tk=d.access_token||d.accessToken||(d.session&&(d.session.access_token||d.session.token))" +
        "      ||(d.token_bundle&&d.token_bundle.access_token)||'';" +
        "    if(tk)window.__jstoken=tk;" +
        "  }catch(e){}}" +
        "async function post(u){" +
        "  const r=await fetch(u,{method:'POST',headers:H(),credentials:'include'});" +
        "  let j=null;try{j=await r.json()}catch(e){}" +
        "  return (j&&typeof j==='object')?j:{success:false,message:'HTTP '+r.status};}" +
        "const ALREADY=/已签|已领|重复|already/i;" +
        "async function turnstileToken(){" +
        "  if(!window.turnstile||typeof window.turnstile.render!=='function'){" +
        "    P('加载人机验证组件…');" +
        "    await new Promise((res,rej)=>{const sc=document.createElement('script');" +
        "      sc.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';" +
        "      sc.onload=res;sc.onerror=()=>rej(new Error('人机验证组件加载失败'));" +
        "      document.head.appendChild(sc);" +
        "      setTimeout(()=>rej(new Error('人机验证组件加载超时')),15000);});}" +
        "  P('正在通过人机验证（自动）…');" +
        "  return await new Promise((res)=>{let done=false;" +
        "    const fin=(t)=>{if(!done){done=true;res(t||'');}};" +
        "    try{window.turnstile.render(document.body,{sitekey:sitekey,size:'invisible'," +
        "      callback:(t)=>fin(t),'error-callback':()=>fin(''),'expired-callback':()=>fin('')});}catch(e){fin('');}" +
        "    setTimeout(()=>fin(''),45000);});}" +
        "try{" +
        "  await refresh();" +
        "  P('正在签到…');" +
        "  let r=await post('/api/user/checkin');" +
        "  if(r&&r.success&&r.data){R({ok:true,reward:usd(r.data.quota_awarded),message:'签到成功',token:window.__jstoken||''});return;}" +
        "  let msg=(r&&r.message)||'';" +
        "  if(ALREADY.test(msg)){R({ok:true,already:true,reward:0,message:'今日已签到',token:window.__jstoken||''});return;}" +
        "  if(/AUTH_TOKEN|Unauthorized|未登录|登录/i.test(msg))" +
        "    {R({ok:false,auth:true,message:'授权已过期，请回主页点击「重新授权」',token:''});return;}" +
        "  if(/turnstile/i.test(msg)){" +
        "    if(!sitekey){R({ok:false,message:'站点已启用人机验证但未配置 siteKey'});return;}" +
        "    const tk=await turnstileToken();" +
        "    if(!tk){R({ok:false,message:'人机验证未通过，请重试'});return;}" +
        "    P('验证通过，正在签到…');" +
        "    r=await post('/api/user/checkin?turnstile='+encodeURIComponent(tk));" +
        "    if(r&&r.success&&r.data){R({ok:true,reward:usd(r.data.quota_awarded),message:'签到成功',token:window.__jstoken||''});return;}" +
        "    const m2=(r&&r.message)||'';" +
        "    if(ALREADY.test(m2)){R({ok:true,already:true,reward:0,message:'今日已签到',token:window.__jstoken||''});return;}" +
        "    R({ok:false,message:m2||'签到失败',token:window.__jstoken||''});return;}" +
        "  R({ok:false,message:msg||'签到失败',token:window.__jstoken||''});" +
        "}catch(e){R({ok:false,message:(e&&e.message)||String(e),token:window.__jstoken||''});}" +
        "})();";
}
