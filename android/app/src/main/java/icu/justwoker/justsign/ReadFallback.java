package icu.justwoker.justsign;

import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;

/** Finite, read-only recovery policy. A GET is not automatically safe to replay. */
public final class ReadFallback {
    private ReadFallback() {}
    public static boolean allowed(String method, String path) {
        if (!"GET".equalsIgnoreCase(method) || path == null || path.length() > 2048
                || path.contains("\\") || path.contains("\r") || path.contains("\n")) return false;
        try {
            URI u = new URI(path);
            if (u.isAbsolute() || u.getRawAuthority() != null || u.getFragment() != null) return false;
            String p = u.getRawPath();
            if (!java.util.Set.of("/api/user/self", "/api/token/", "/api/log/self",
                    "/api/data/self", "/api/user/checkin").contains(p)) return false;
            String q = u.getRawQuery();
            return q == null || q.matches("[A-Za-z0-9_=&:%+.,-]*");
        } catch (Exception e) { return false; }
    }
    public static boolean recoverable(Throwable error) {
        if (error == null || Thread.currentThread().isInterrupted()) return false;
        boolean transport = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof java.util.concurrent.CancellationException
                    || t instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || t instanceof java.security.cert.CertificateException
                    || t instanceof IllegalArgumentException) return false;
            String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("trust anchor") || m.contains("certificate") || m.contains("hostname")
                    || m.contains("certpath") || m.contains("cancelled") || m.contains("canceled")) return false;
            if (t instanceof java.io.IOException || m.contains("网络请求失败")) transport = true;
        }
        return transport;
    }
    public static boolean shouldTry(String method, String path, JSONObject response, Throwable error) {
        if (!allowed(method, path) || Thread.currentThread().isInterrupted()) return false;
        if (error != null) return recoverable(error);
        if (response == null) return true;
        int code = response.optInt("http", 0);
        if (code == 401 || code == 403 || code == 408 || (code >= 500 && code < 600)) return true;
        JSONObject body = response.optJSONObject("data");
        return code == 200 && (response.optBoolean("waf", false) || body == null || body.length() == 0);
    }
    public static boolean usable(String path, JSONObject r, String uid) {
        if (r == null || r.optInt("http") != 200 || r.optBoolean("waf", false)) return false;
        JSONObject b = response(r);
        if (b == null || !Boolean.TRUE.equals(b.opt("success")) || b.isNull("data")) return false;
        if ("/api/user/self".equals(path)) return SiteProtocol.validSelf(r, uid);
        return b.opt("data") instanceof JSONObject || b.opt("data") instanceof org.json.JSONArray;
    }

    /** 离屏只读回传的响应体既可能是对象，也可能是已解析的JSON字符串。 */
    private static JSONObject response(JSONObject r) {
        JSONObject b = r.optJSONObject("data");
        if (b != null) return b;
        Object raw = r.opt("data");
        if (raw instanceof String) {
            try { return new JSONObject((String) raw); } catch (Exception e) { return null; }
        }
        return null;
    }

    /** 只读结果的薄封装校验（不含各接口的业务字段判断）。 */
    public static boolean usableResponse(String path, JSONObject r, String uid) {
        return usable(path, r, uid);
    }
    /** Error categories, never raw messages/URLs/cookie values. */
    public static String category(Throwable t) {
        if (t == null) return "response";
        if (!recoverable(t)) return "non-retryable";
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof javax.net.ssl.SSLException) return "tls-handshake";
            if (x instanceof java.net.SocketTimeoutException) return "timeout";
            if (x instanceof java.net.UnknownHostException) return "dns";
            if (x instanceof java.net.SocketException) return "socket";
        }
        return "transport";
    }
}
