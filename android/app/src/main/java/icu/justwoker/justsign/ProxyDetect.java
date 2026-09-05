package icu.justwoker.justsign;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * ProxyDetect（v0.2.0）— 自动检测本机可用代理。
 *
 * 检测来源（按优先级）：
 *   1. 系统 Wi-Fi / 全局 HTTP 代理（Settings.Global.http_proxy、System.getProperty）
 *   2. 常见本地代理端口逐个 Socket 探活（v2rayNG / Clash / Surfboard 等常用端口）
 * 只做「端口能否 TCP 连通」判定，不发协议握手（避免误伤本地服务）。
 */
public final class ProxyDetect {

    /** 常见本地代理端口（按命中概率排序） */
    private static final int[] PORTS = {
            10808,  // v2rayNG SOCKS5（本机在用）
            10809,  // v2rayNG HTTP
            7890,   // Clash 混合
            7891,   // Clash SOCKS5
            1080,   // 通用 SOCKS5
            8889,   // Surfboard / SagerNet
            2080,   // Nekobox
            8080,   // 通用 HTTP
            6153,   // Shadowsocks 常用
    };

    private static final String[] HOSTS = {"127.0.0.1"};

    private ProxyDetect() {}

    /** 结果条目：{host, port, source, note} */
    public static ArrayList<JSONObject> scan(Context ctx) {
        ArrayList<JSONObject> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        /* 1) 系统代理设置 */
        for (JSONObject sys : systemProxies(ctx)) {
            String key = sys.optString("host") + ":" + sys.optInt("port");
            if (seen.add(key)) {
                boolean alive = reachable(sys.optString("host"), sys.optInt("port"), 1200);
                try { sys.put("alive", alive); } catch (Exception ignored) {}
                out.add(sys);
            }
        }

        /* 2) 本地常见端口探活 */
        for (String host : HOSTS) {
            for (int port : PORTS) {
                String key = host + ":" + port;
                if (!seen.add(key)) continue;
                if (!reachable(host, port, 400)) continue;
                try {
                    out.add(new JSONObject().put("host", host).put("port", port)
                            .put("source", "本地端口探测").put("note", portHint(port))
                            .put("alive", true));
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static ArrayList<JSONObject> systemProxies(Context ctx) {
        ArrayList<JSONObject> list = new ArrayList<>();
        /* Settings.Global.http_proxy，形如 host:port */
        try {
            String g = Settings.Global.getString(ctx.getContentResolver(), "http_proxy");
            JSONObject o = parseHostPort(g, "系统全局代理");
            if (o != null) list.add(o);
        } catch (Exception ignored) {}
        /* JVM 属性（部分 ROM 会同步 Wi-Fi 代理到这里） */
        try {
            String h = System.getProperty("http.proxyHost");
            String p = System.getProperty("http.proxyPort");
            if (h != null && !h.isEmpty() && p != null && !p.isEmpty()) {
                list.add(new JSONObject().put("host", h).put("port", Integer.parseInt(p))
                        .put("source", "系统 HTTP 代理").put("note", "来自 http.proxyHost"));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static JSONObject parseHostPort(String s, String source) {
        try {
            if (s == null || s.trim().isEmpty() || ":0".equals(s.trim())) return null;
            String t = s.trim();
            int idx = t.lastIndexOf(':');
            if (idx <= 0) return null;
            String host = t.substring(0, idx);
            int port = Integer.parseInt(t.substring(idx + 1).trim());
            if (port <= 0 || port > 65535) return null;
            return new JSONObject().put("host", host).put("port", port)
                    .put("source", source).put("note", "系统设置");
        } catch (Exception e) { return null; }
    }

    private static String portHint(int port) {
        switch (port) {
            case 10808: return "v2rayNG SOCKS5";
            case 10809: return "v2rayNG HTTP";
            case 7890:  return "Clash 混合端口";
            case 7891:  return "Clash SOCKS5";
            case 1080:  return "通用 SOCKS5";
            case 8889:  return "Surfboard / SagerNet";
            case 2080:  return "Nekobox";
            case 8080:  return "通用 HTTP";
            case 6153:  return "Shadowsocks";
            default:    return "";
        }
    }

    public static boolean reachable(String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }
}