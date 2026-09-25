package icu.justwoker.justsign;

/**
 * 离屏只读脚本：可在任意路径页面上执行，只做两件事——
 *   1) 在站点当前来源上（含代理/WAF 质询后）请求一次给定路径；
 *   2) 通过只读身份探测拿到的 ID 与本地保存 ID 一致后，才回传响应。
 * 不写 Cookie、不按名称读取受限 Cookie、不点击任何按钮、不重放写操作。
 *
 * v1.1.6 修正：
 *   - 不再固定等 400ms 才发请求。改为轮询两个可靠参考对象：
 *     a) document.readyState 已离开 loading；
 *     b) WAF 质询已放行 —— 阿里云 ESA/Tengine 的质询页里带 {@code var arg1='...'} 脚本，
 *        过盾成功后内核写入放行 Cookie（acw_sc__v2）。
 *     两者都成立才发请求。上限 8s 只是安全网。
 *   - 身份确认同样改为轮询：质询刚放行时会话可能尚未生效，单次判定会误报 identity-mismatch。
 *   - 明确区分「硬失配」（拿到了明确是别的身份 → 立即停手）与「未就绪」（继续轮询），
 *     安全属性不变：只要不是本地保存的那个身份，绝不回传数据。
 */
public final class OffscreenReadJs {

    private OffscreenReadJs() {}

    /**
     * @param baseUrl  站点根（形如 https://anyrouter.top），用于拼 /api/user/self
     * @param path     要读取的只读路径（形如 /api/token/?p=0&amp;size=100）
     * @param expectId 本地保存的站点用户 ID，身份不符即拒绝回传
     * @param nonce    回传关联号（仅用于日志串联）
     */
    public static String render(String baseUrl, String path, String expectId, String nonce) {
        String base = js(baseUrl == null ? "" : baseUrl.replaceAll("/+$", ""));
        String pid = js(path == null ? "" : path);
        String id = js(expectId == null ? "" : expectId.trim());
        String n = js(nonce == null ? "" : nonce);
        return "(function(){"
                + "if(window.__jsRead)return;window.__jsRead=1;"
                + "var B='" + base + "',P='" + pid + "',N='" + n + "',ID='" + id + "';"
                + "var CAP=20000;"
                + "function send(o){try{JustSign.onReadResult(N,JSON.stringify(o))}catch(e){}}"
                + "function redact(x){return String(x==null?'error':x)"
                + ".replace(/https?:\\/\\/[^\\s,;\"']+/g,'[url]')"
                + ".replace(/[A-Za-z0-9_-]{24,}/g,'[redacted]')"
                + ".replace(/[^\\s]{0,12}(cookie|token|secret|password|authorization|session)[^\\s]{0,12}/gi,'[redacted]')"
                + ".slice(0,80)}"
                + "function fail(e){send({ok:false,reason:redact(e&&e.message||e),status:0,body:''})}"
                /* ---- 参考对象 1：WAF 质询是否已放行 ---- */
                + "function wafSolved(text){"
                + "  if(text!=null&&/var\\s+arg1\\s*=/.test(String(text)))return true;"
                + "  var h=String(text==null?((document.documentElement&&document.documentElement.innerHTML)||''):text);"
                + "  return /var\\s+arg1\\s*=/.test(h);}"
                + "function wafPending(){try{"
                + "  if(/(^|;\\s*)acw_sc__v2=/.test(document.cookie))return false;"
                + "  var h=(document.documentElement&&document.documentElement.innerHTML)||'';"
                + "  return /var\\s+arg1\\s*=/.test(h);"
                + "}catch(e){return false;}}"
                /* ---- 参考对象 2：文档已离开 loading ---- */
                + "function pageReady(){try{if(document.readyState==='loading')return false;}catch(e){}"
                + "  return !wafPending();}"
                + "function fetchSelf(){return fetch(B+'/api/user/self',{method:'GET',credentials:'include',"
                + "  headers:{'Accept':'application/json'}}).then(function(r){return r.text().then(function(t){"
                + "    return {st:r.status,text:t}})})}"
                /* 三态：ok / hard（明确别的身份，立即停手）/ soft（未就绪，继续轮询） */
                + "function probeIdentity(){if(ID=='')return Promise.resolve({ok:false,hard:true});"
                + "  return fetchSelf().then(function(o){"
                + "    if(wafSolved(o.text))return {ok:false,hard:false};"
                + "    var j=null;try{j=JSON.parse(o.text)}catch(e){}"
                + "    var d=(j&&j.data&&j.data.data)||(j&&j.data);"
                + "    if(o.st==200&&j&&j.success===true&&d&&String(d.id)===ID)return {ok:true};"
                + "    if(o.st==200&&j&&j.success===true&&d)return {ok:false,hard:true};"
                /* v1.1.6：success 明确为 false ＝ 服务端已明确拒绝（未登录/无权限），
                 * 不是「页面未就绪」。原实现把它归进 soft 分支 → 打满 CAP 也无解，
                 * 白白多耗 20s 还发一堆无用请求。必须硬停。 */
                + "    if(o.st==200&&j&&j.success===false)return {ok:false,hard:true,reason:'self-rejected'};"
                /* 未就绪＝继续轮询，绝不硬停：原实现只在 200+JSON 但身份不符时 hard，
                 * 其余一律 soft 而 CAP 只有 8s —— 慢网络下必然误报 identity-not-ready。
                 * 现在有明确结束条件（拿到合身份响应 / 超时上限），故上限放宽到 20s。 */
                + "    return {ok:false,hard:false};"
                + "  }).catch(function(e){return {ok:false,hard:false,err:redact(e&&e.message||e)}});}"
                /* v1.1.6：拿到响应后仍要确认「没被 WAF 换页」，且 HTTP 状态必须是 2xx。
                 * 原实现无条件 ok:true —— 质询页会被当成 Key 列表回传给上层，等于把 WAF 页
                 * 当数据。上层虽有 businessValid 兜底，但这里必须自己判死。 */
                + "function fetchTarget(){return fetch(B+P,{method:'GET',credentials:'include',"
                + "  headers:{'Accept':'application/json'}}).then(function(r){return r.text().then(function(t){"
                + "    if(wafSolved(t)){send({ok:false,reason:'waf-challenge',status:r.status,body:''});return;}"
                + "    if(!(r.status>=200&&r.status<300)){send({ok:false,reason:'http-'+r.status,status:r.status,body:''});return;}"
                + "    send({ok:true,status:r.status,body:t.slice(0,12000)})})}).catch(fail);}"
                + "function pollIdentity(){var cap=Date.now()+CAP;"
                + "  (function tick(){probeIdentity().then(function(r){"
                + "    if(r.ok){fetchTarget();return;}"
                + "    if(r.hard){send({ok:false,reason:'identity-mismatch',status:0,body:''});return;}"
                + "    if(Date.now()>=cap){send({ok:false,reason:'identity-not-ready',status:0,body:''});return;}"
                + "    setTimeout(tick,300);});})();}"
                + "function run(){if(!ID){send({ok:false,reason:'missing-expected-id',status:0,body:''});return;}"
                + "  pollIdentity();}"
                /* ---- 入口：等参考对象成立再跑 ---- */
                + "var gateCap=Date.now()+CAP;"
                + "(function gate(){if(pageReady()||Date.now()>=gateCap){run();return;}"
                + "  setTimeout(gate,120);})();"
                + "})();";
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'' || c == '"') sb.append('\\').append(c);
            else if (c == '<') sb.append("\\u003c");
            else if (c > 0x7e || c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
