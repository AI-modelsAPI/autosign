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
          return fetch('/api/user/self',{credentials:'include',headers:headers,
              cache:'no-store',redirect:'error',signal:controller.signal})
            .then(function(r){return r.text().then(function(t){
              if(t.length>262144)throw new Error('response-too-large');
              return {s:r.status,b:t};
            });})
            .then(function(r){if(location.origin==='__ORIGIN__')
              window.JustSign.onSelfResult(r.s,r.b);})
            .catch(function(e){if(location.origin==='__ORIGIN__')
              window.JustSign.onSelfFailed(e&&e.name==='AbortError'?'timeout':'network-or-response');})
            .then(release,release);
        })();
        """;
}
