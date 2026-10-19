package icu.justwoker.justsign;

/**
 * 离屏只读脚本：可在任意路径页面上执行，只做两件事——
 *   1) 在站点当前来源上（含代理/WAF 质询后）请求一次给定路径；
 *   2) 通过只读身份探测拿到的 ID 与本地保存 ID 一致后，才回传响应。
 * 不写 Cookie、不按名称读取受限 Cookie、不点击任何按钮、不重放写操作。
 *
 * v1.3.6 修复（真实行为测试抓到的致命缺陷）：
 *   上一版拼接时把 pollIdentity 写了两遍、第一份还没闭合，生成的是**语法非法**的 JS。
 *   Java 单测只做字符串包含断言，所以全绿；而 tools/test_readjs_behavior.mjs
 *   真正执行脚本时立刻 SyntaxError —— 也就是说离屏只读兜底在真机上从未跑起来过，
 *   日志里的 waf-not-cleared 根本不是"盾过不去"，而是"脚本没跑"。
 *
 *   现在按「单一函数定义」重写，并让行为测试真实执行它。
 *
 * 与 AuthProbeJs（同仓已证明能探到会话的那条路径）保持一致的三个细节：
 *   · 带 New-Api-User: <id>（New API 变体要求，缺失时服务端按匿名处理）；
 *   · cache:'no-store'（避免命中被 WAF 换页后的缓存响应）；
 *   · 由 Java 侧设置 UA_OFFSCREEN（与授权/签到/重授权同一 UA，缺了会被当爬虫）。
 *
 * 证据驱动（不靠等秒数）：唯一成功判据 = 业务接口返回 success===true 且 id 相符。
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
                + "var CAP=30000;var LASTF={st:0,len:0,body:''};"
                + "function send(o){try{JustSign.onReadResult(N,JSON.stringify(o))}catch(e){}}"
                + "function redact(x){return String(x==null?'error':x)"
                + ".replace(/https?:\\/\\/[^\\s,;\"']+/g,'[url]')"
                + ".replace(/[A-Za-z0-9_-]{24,}/g,'[redacted]')"
                + ".replace(/[^\\s]{0,12}(cookie|token|secret|password|authorization|session)[^\\s]{0,12}/gi,'[redacted]')"
                + ".slice(0,80)}"
                + "function fail(e){send({ok:false,reason:redact(e&&e.message||e),status:0,body:''})}"
                /* ---- WAF 质询识别 ----
                 * 真机实测（agentrouter.org，2026-10-18）被拦响应的确切特征：
                 *   st=200; len=16011; html=true; aliyun=true
                 *   head = `<!doctype html><meta charset="UTF-8"><meta name="aliyun_wa…`
                 * 旧实现只认 `var arg1=` / `aliyun_waf_aa|bb` / `__webpack_modules__` 三种具体形态，
                 * 这页三条全落空 → 挑战页被当成"未就绪"空等到超时。
                 * 现在按实测特征放宽：任意 aliyun_waf 变体、meta name=aliyun_wa…、acw_ 系列都算挑战页。 */
                + "function isWafText(t){var s=String(t==null?'':t);"
                + "  if(/var\\s+arg1\\s*=/.test(s))return true;"
                + "  if(/aliyun_waf/i.test(s))return true;"
                + "  if(/name\\s*=\\s*[\"']aliyun_wa/i.test(s))return true;"
                + "  if(/__webpack_modules__/.test(s)&&/aliyun/i.test(s))return true;"
                + "  if(/acw_sc__v2|acw_tc/i.test(s)&&/<html|<!doctype/i.test(s))return true;"
                + "  return false;}"
                + "function wafSolved(text){"
                + "  if(text!=null&&isWafText(String(text)))return true;"
                + "  var h=String(text==null?((document.documentElement&&document.documentElement.innerHTML)||''):text);"
                + "  return isWafText(h);}"
                + "function wafPending(){try{"
                + "  if(/(^|;\\s*)acw_sc__v2=/.test(document.cookie))return false;"
                + "  var h=(document.documentElement&&document.documentElement.innerHTML)||'';"
                + "  return isWafText(h);"
                + "}catch(e){return false;}}"
                + "function pageReady(){try{if(document.readyState==='loading')return false;}catch(e){}"
                + "  return !wafPending();}"
                /* 与 AuthProbeJs 对齐：New API 变体需要 New-Api-User，缓存必须绕过。 */
                + "function hdr(){var h={'Accept':'application/json'};if(ID)h['New-Api-User']=ID;return h;}"
                + "function get(u){return fetch(u,{method:'GET',credentials:'include',cache:'no-store',headers:hdr()})"
                + "  .then(function(r){return r.text().then(function(t){return {st:r.status,text:t}})})}"
                + "function fetchSelf(){return get(B+'/api/user/self')}"
                /* 三态：ok / hard（明确别的身份或明确被拒，立即停手）/ soft（未就绪，继续轮询） */
                + "function probeIdentity(){if(ID=='')return Promise.resolve({ok:false,hard:true});"
                + "  return fetchSelf().then(function(o){"
                + "    LASTF={st:o.st,len:String(o.text).length,body:String(o.text).slice(0,400)};"
                + "    if(wafSolved(o.text))return {ok:false,hard:false};"
                + "    var j=null;try{j=JSON.parse(o.text)}catch(e){}"
                + "    var d=(j&&j.data&&j.data.data)||(j&&j.data);"
                + "    if(o.st==200&&j&&j.success===true&&d&&String(d.id)===ID)return {ok:true};"
                + "    if(o.st==200&&j&&j.success===true&&d)return {ok:false,hard:true};"
                + "    if(o.st==200&&j&&j.success===false)return {ok:false,hard:true,reason:'self-rejected'};"
                + "    return {ok:false,hard:false};"
                + "  }).catch(function(e){return {ok:false,hard:false,err:redact(e&&e.message||e)}});}"
                /* 目标读取：再判一次「没被换页」且必须是 2xx，绝不把挑战页当数据回传。 */
                + "function fetchTarget(){return get(B+P).then(function(o){"
                + "    if(wafSolved(o.text)){send({ok:false,reason:'waf-challenge',status:o.st,body:''});return;}"
                + "    if(!(o.st>=200&&o.st<300)){send({ok:false,reason:'http-'+o.st,status:o.st,body:''});return;}"
                + "    send({ok:true,status:o.st,body:o.text.slice(0,12000)});"
                + "  }).catch(fail);}"
                + "function pollIdentity(){var cap=Date.now()+CAP;"
                + "  (function tick(){probeIdentity().then(function(r){"
                + "    if(r.ok){fetchTarget();return;}"
                + "    if(r.hard){send({ok:false,reason:'identity-mismatch',status:0,body:''});return;}"
                /* 到 CAP 仍无「放行已确认」的证据 → 判失败并标可重试，绝不当成功。
                 * 诊断只回传分类布尔与长度，绝不回传正文（含凭据）。 */
                + "    if(Date.now()>=cap){"
                + "      var f={st:LASTF.st,len:LASTF.len};"
                + "      try{var b=LASTF.body||'';"
                + "        f.html=/<html|<!doctype/i.test(b);"
                + "        f.json=/^\\s*[\\{\\[]/.test(b);"
                + "        f.arg1=/var\\s+arg1\\s*=/.test(b);"
                + "        f.aliyun=/aliyun/i.test(b);"
                + "        f.captcha=/captcha|slider|turnstile|challenge/i.test(b);"
                + "        f.head=b.slice(0,60).replace(/[^\\x20-\\x7e]/g,'.');"
                + "      }catch(e){}"
                + "      send({ok:false,reason:'waf-not-cleared',retryable:true,status:0,body:'',"
                + "        diag:'fetch;st='+f.st+';len='+f.len+';html='+f.html+';json='+f.json"
                + "          +';arg1='+f.arg1+';aliyun='+f.aliyun+';captcha='+f.captcha"
                + "          +';head='+f.head});return;}"
                + "    setTimeout(tick,300);});})();}"
                + "function run(){if(!ID){send({ok:false,reason:'missing-expected-id',status:0,body:''});return;}"
                + "  pollIdentity();}"
                + "var gateCap=Date.now()+CAP;"
                + "(function gate(){if(pageReady()){run();return;}"
                + "  if(Date.now()>=gateCap){"
                + "    send({ok:false,reason:'waf-not-cleared',retryable:true,status:0,body:'',diag:'gate'});return;}"
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
