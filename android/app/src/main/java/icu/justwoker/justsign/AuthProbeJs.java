package icu.justwoker.justsign;

import java.net.URI;

/** 只观察站点自己的会话，不兑换授权码、不将浏览器缓存当作认证结果。 */
public final class AuthProbeJs {
    private AuthProbeJs() {}

    public static boolean sameOrigin(String current, String base) {
        try {
            URI a = URI.create(current), b = URI.create(base);
            return "https".equalsIgnoreCase(a.getScheme())
                    && "https".equalsIgnoreCase(b.getScheme())
                    && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                    && port(a) == port(b);
        } catch (Exception e) { return false; }
    }
    private static int port(URI u) { return u.getPort() < 0 ? 443 : u.getPort(); }

    public static String render(String base, String fallbackId) {
        URI uri;
        try { uri = URI.create(base); } catch (Exception e) { return "void 0;"; }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            return "void 0;";
        String origin = "https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (port(uri) == 443 ? "" : ":" + port(uri));
        String uid = fallbackId != null && fallbackId.matches("[1-9][0-9]{0,14}") ? fallbackId : "";
        return TEMPLATE.replace("__ORIGIN__", origin).replace("__UID__", uid)
                .replace("__READY__", AuthPageGuard.readyExpression());
    }
    private static final String TEMPLATE = """
        (function(){
          if(location.origin!=='__ORIGIN__')return;
          if(!__READY__)return;
          if(window.__justsignSelfBusy)return;
          var headers={'Accept':'application/json'},uid='__UID__';
          function valid(x){return /^(?:[1-9][0-9]{0,14})$/.test(String(x));}
          try{var user=JSON.parse(localStorage.getItem('user')||'null');
            if(user&&valid(user.id))uid=String(user.id);
          }catch(e){}
          if(valid(uid))headers['New-Api-User']=uid;
          window.__justsignSelfBusy=true;
          var controller=new AbortController();
          var timer=setTimeout(function(){controller.abort();},10000);
          function release(){clearTimeout(timer);window.__justsignSelfBusy=false;}
          function report(s,b){if(location.origin==='__ORIGIN__')window.JustSign.onSelfResult(s,b);}
          function fail(e){if(location.origin==='__ORIGIN__')
            window.JustSign.onSelfFailed(e&&e.name==='AbortError'?'timeout':'network-or-response');}
          function tryNewApi(){
            return fetch('/api/user/self',{credentials:'include',headers:headers,
                cache:'no-store',redirect:'error',signal:controller.signal})
              .then(function(r){return r.text().then(function(t){
                if(t.length>262144)throw new Error('response-too-large');
                return {s:r.status,b:t};
              });});
          }
          /* v1.3.4 根因修复（真机+抓包实证）：fengwind 系站点都不是 New API，
           * 其 /api/user/self 恒 404（实测三套子域全是），真实会话端点与 token 键各异：
           *   · api.fengwind.com          → /api/v1/auth/me + auth_token
           *   · api-donation.fengwind.com → /api/me         + donation_token
           *   · api-welfalre.fengwind.com → /api/me         + welfare_token
           * 凭据是页面自己写进 localStorage 的 Bearer token。
           * 此前只探 /api/user/self → 登录明明成功也永远探测不到 → 「登录失败/额度为空」。
           * 仅当 New API 端点不存在（404）时才回退，不影响既有站点。
           * 安全：token 只作为凭据回传落库（与其它站同待遇），绝不写日志。 */
          function bearer(){
            try{
              var ks=['auth_token','donation_token','welfare_token'];
              for(var i=0;i<ks.length;i++){var v=localStorage.getItem(ks[i]);
                if(v)return v;}
              return '';
            }catch(e){return '';}
          }
          function unwrap(t){
            var j=null;try{j=JSON.parse(t);}catch(e){return null;}
            if(!j)return null;
            if(j.code===0&&j.data)return j.data;
            if(j.success===true)return j.data||j.user||null;
            return null;
          }
          function tryOne(url,T){
            return fetch(url,{credentials:'include',cache:'no-store',
                headers:{'Accept':'application/json','Authorization':'Bearer '+T}})
              .then(function(r){if(r.status!==200)return null;
                return r.text().then(function(t){
                  var u=unwrap(t);if(!u)return null;
                  var id2=u.id||u.sub2api_user_id||u.linuxdo_id||u.user_id||'';
                  var nm=u.display_name||u.username||u.name||'';
                  return JSON.stringify({success:true,data:{id:id2,username:nm,access_token:T}});
                });})
              .catch(function(){return null;});
          }
          function trySub2Api(){
            var T=bearer();
            if(!T)return Promise.resolve(null);
            var urls=['/api/me','/api/v1/auth/me'];
            return urls.reduce(function(p,u){
              return p.then(function(hit){return hit!=null?hit:tryOne(u,T);});
            },Promise.resolve(null));
          }
          return tryNewApi().then(function(r){
            if(r.s!==404){report(r.s,r.b);return;}
            return trySub2Api().then(function(b){
              if(b!=null)report(200,b);else report(r.s,r.b);
            });
          }).catch(fail).then(release,release);
        })();
        """;
}
