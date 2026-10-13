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
                + "var CAP=30000;"
                + "function send(o){try{JustSign.onReadResult(N,JSON.stringify(o))}catch(e){}}"
                + "function redact(x){return String(x==null?'error':x)"
                + ".replace(/https?:\\/\\/[^\\s,;\"']+/g,'[url]')"
                + ".replace(/[A-Za-z0-9_-]{24,}/g,'[redacted]')"
                + ".replace(/[^\\s]{0,12}(cookie|token|secret|password|authorization|session)[^\\s]{0,12}/gi,'[redacted]')"
                + ".slice(0,80)}"
                + "function fail(e){send({ok:false,reason:redact(e&&e.message||e),status:0,body:''})}"
                /* ---- WAF 质询识别（两种变体） ----
                 * v1：阿里云 ESA 老式页，正文带 `var arg1='...'`，可由 WafChallenge 纯 Java 求解。
                 * v2：阿里云 WAF v2.0.1 页（webpack 混淆 + meta[name=aliyun_waf_aa/bb]），
                 *     正文【没有 arg1】，只能靠真实内核跑完它的 JS 拿到放行 Cookie。
                 * 旧实现只认 v1：遇到 v2 页时 wafSolved() 返回 false，于是把这段 HTML
                 * 当成「真实业务数据」ok:true 回传，上层解析失败后只报「离屏未拿到可用数据」，
                 * 真正原因（被 v2 盾拦住）被完全掩盖 —— 实测 agentrouter.org 即命中此坑。 */
                + "function isWafText(t){var s=String(t==null?'':t);"
                + "  if(/var\\s+arg1\\s*=/.test(s))return true;"
                + "  if(/aliyun_waf_(?:aa|bb)/.test(s))return true;"
                + "  if(/__webpack_modules__/.test(s)&&/aliyun_waf/i.test(s))return true;"
                + "  return false;}"
                /* ---- 参考对象 1：WAF 质询是否已放行 ---- */
                + "function wafSolved(text){"
                + "  if(text!=null&&isWafText(String(text)))return true;"
                + "  var h=String(text==null?((document.documentElement&&document.documentElement.innerHTML)||''):text);"
                + "  return isWafText(h);}"
                + "function wafPending(){try{"
                + "  if(/(^|;\\s*)acw_sc__v2=/.test(document.cookie))return false;"
                + "  var h=(document.documentElement&&document.documentElement.innerHTML)||'';"
                + "  return isWafText(h);"
                + "}catch(e){return false;}}"
                /* ---- 参考对象 2：文档已离开 loading ---- */
                + "function pageReady(){try{if(document.readyState==='loading')return false;}catch(e){}"
                + "  return !wafPending();}"
                /* ---- 该挑战页是否需要人工交互（阿里云 NoCaptcha 组件） ----
                 * v2 页配置为阿里云验证码（nc-container / captcha-element / AliyunCaptcha.js），
                 * 且 renderData.data 为空 —— 需要现场走一次验证。能无感就静默过，
                 * 要求交互时组件会显形（可见且非 display:none）。此处只做「是否显形」判定，
                 * 绝不代答、不伪造、不点击。 */
                + "function needsHuman(){try{"
                + "  var el=document.getElementById('captcha-element');"
                + "  if(!el)return false;"
                + "  var r=el.getBoundingClientRect();"
                + "  if(r.width<=0||r.height<=0)return false;"
                + "  var st=window.getComputedStyle(el);"
                + "  if(!st)return false;"
                + "  return st.display!=='none'&&st.visibility!=='hidden'&&st.opacity!=='0';"
                + "}catch(e){return false;}}"
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
                /* v1.3.2 严谨化（两件事必须分开）：
                 *   判定：单次尝试内没有「放行已确认」的证据，就绝不能声称成功；
                 *   重试：一次没成 ≠ 永远不成（网络抖动、外部脚本首次加载慢都很常见），
                 *         由上层按「可重试标记」再试，而不是在这里空等或直接放弃。
                 * retryable=true   → 未过盾/未就绪：值得再试一次（放行 Cookie 可能刚落地）
                 * retryable=false  → 需要人工交互 / 身份不符：重试无意义，必须如实上报 */
                + "    if(Date.now()>=cap){"
                + "      var nh=needsHuman();"
                + "      send({ok:false,reason:nh?'captcha-needs-human':'waf-not-cleared',"
                + "            retryable:!nh,status:0,body:''});return;}"
                + "    setTimeout(tick,300);});})();}"
                + "function run(){if(!ID){send({ok:false,reason:'missing-expected-id',status:0,body:''});return;}"
                + "  pollIdentity();}"
                /* ---- 入口：以「可观测证据」驱动，不靠等秒数 ----
                 * 证据 = 已离开 loading 且已不处于 WAF 质询态（放行 cookie 已落地）。
                 * 到 CAP 仍不成立 → 直接判失败（waf-not-cleared / captcha-needs-human），
                 * 绝不把「等够了」当成「过盾了」。 */
                + "var gateCap=Date.now()+CAP;"
                + "(function gate(){if(pageReady()){run();return;}"
                + "  if(Date.now()>=gateCap){"
                + "    var nh2=needsHuman();"
                + "    send({ok:false,reason:nh2?'captcha-needs-human':'waf-not-cleared',"
                + "          retryable:!nh2,status:0,body:''});return;}"
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
