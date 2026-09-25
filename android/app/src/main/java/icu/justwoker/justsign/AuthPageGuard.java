package icu.justwoker.justsign;

import java.net.URI;

/** Same-document business-script gate. No fetch, cookie access, UA changes or challenge solving. */
public final class AuthPageGuard {
    private AuthPageGuard() {}

    /** Fail closed when the document is not ready or still contains a full-page challenge. */
    static String readyExpression() {
        return """
            (function(){try{
              var d=document;
              if(!d||!d.documentElement||!d.body)return false;
              if(d.readyState!=='interactive'&&d.readyState!=='complete')return false;
              var h=String(d.documentElement.innerHTML||'').slice(0,262144);
              var title=String(d.title||'');
              if(/acw_sc(?:__v2)?|aliyun_waf|\\barg1\\s*=/i.test(h))return false;
              if(/_cf_chl_opt|id=["']challenge-(?:running|form)["']/i.test(h))return false;
              if(/^(?:just a moment|checking your browser|security verification)/i.test(title))return false;
              if(!String(d.body.innerText||'').trim()&&!d.body.childElementCount)return false;
              return true;
            }catch(e){return false;}})()
            """;
    }

    public static String wrap(String pageUrl, String script) {
        return wrapOnce(pageUrl, script, "");
    }

    /** onceKey is an opaque local attempt counter, never an OAuth code or credential. */
    public static String wrapOnce(String pageUrl, String script, String onceKey) {
        try {
            URI u = URI.create(pageUrl);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || u.getRawUserInfo() != null || script == null) return "'blocked';";
        } catch (Exception e) { return "'blocked';"; }
        String once = onceKey == null ? "" : onceKey;
        // 'blocked'  = permanent (wrong document/params) → caller must NOT retry.
        // 'not-ready'= transient (loading or WAF challenge) → caller may retry later.
        // 'attempted'= business ran exactly once (or already consumed) → never replay.
        return "(function(){if(location.href!==new URL(" + quote(pageUrl) + ").href)return 'blocked';"
                + "if(!" + readyExpression() + ")return 'not-ready';"
                + (once.isEmpty() ? "" : "if(window.__justsignBusinessOnce===" + quote(once)
                    + ")return 'attempted';window.__justsignBusinessOnce=" + quote(once) + ";")
                // Attempt is consumed before running. Exceptions must never replay a write.
                + "try{(function(){\n" + script + "\n})();}catch(e){}return 'attempted';})();";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "\"";
    }
}
