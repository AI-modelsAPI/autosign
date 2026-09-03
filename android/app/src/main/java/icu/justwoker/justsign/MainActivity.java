package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MainActivity — App 主界面：
 *   WebView 以 https://appassets.androidplatform.net/assets/index.html 加载本地 UI，
 *   fetch('/api/...') 被 shouldInterceptRequest 拦截 → Java 引擎直接执行（UI 零改动）。
 *   POST body 由注入的 fetch 重写塞进 X-Js-Body header（base64/UTF-8）。
 */
public class MainActivity extends Activity {
    private static final int AUTH_REQ = 1001;

    private WebView web;
    private Store store;
    private Engine engine;
    private WebViewAssetLoader assetLoader;

    private volatile String authResultJson = "{\"ts\":0}";

    /** POST body → header（支持中文：UTF-8 字节映射 Latin1 后 btoa） */
    private static final String FETCH_OVERRIDE =
            "(function(){if(window.__jsPatched)return;window.__jsPatched=true;" +
            "var _f=window.fetch;window.fetch=function(p,opt){opt=opt||{};" +
            "if(opt.method&&opt.method.toUpperCase()==='POST'&&opt.body){" +
            "opt.headers=Object.assign({},opt.headers||{},{'X-Js-Body':btoa(unescape(encodeURIComponent(opt.body)))});}" +
            "return _f(p,opt);};})();";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        engine = new Engine(this);
        Engine.schedule(this); // 注册后台 12h 周期签到

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) {
                    String path = u.getPath() == null ? "" : u.getPath();
                    if (path.startsWith("/api/")) return handleApi(req);
                    return assetLoader.shouldInterceptRequest(u);
                }
                return super.shouldInterceptRequest(v, req);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(FETCH_OVERRIDE, null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "justsignAndroid");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    /* ================= /api/* 路由（Java 版 server.js） ================= */

    private WebResourceResponse handleApi(WebResourceRequest req) {
        try {
            Uri u = req.getUrl();
            String[] seg = u.getPath().split("/");
            String cmd = seg.length > 2 ? seg[2] : "";
            String sub = seg.length > 3 ? seg[3] : null;

            String bodyStr = "";
            Map<String, String> h = req.getRequestHeaders();
            if (h != null && h.containsKey("X-Js-Body") && h.get("X-Js-Body") != null)
                bodyStr = new String(android.util.Base64.decode(h.get("X-Js-Body"), android.util.Base64.DEFAULT),
                        StandardCharsets.UTF_8);
            JSONObject body = new JSONObject(bodyStr.isEmpty() ? "{}" : bodyStr);

            JSONObject out;
            int code = 200;
            switch (cmd) {
                case "health":
                    out = new JSONObject().put("ok", true);
                    break;
                case "config":
                    if ("POST".equals(req.getMethod())) store.saveConfig(body);
                    out = new JSONObject().put("ok", true).put("cfg", store.config());
                    break;
                case "accounts":
                    if ("save".equals(sub)) {
                        if (body.optString("key").isEmpty() || body.optString("siteKey").isEmpty()) {
                            out = err("需要 key 和 siteKey"); code = 400;
                        } else {
                            store.upsertToken(body);
                            store.appendLog(body.optString("siteKey"), body.optString("key"),
                                    "account-save", "hasToken=" + !body.optString("token", "").isEmpty());
                            out = ok();
                        }
                    } else if (sub != null) {
                        store.removeToken(sub); out = ok();
                    } else {
                        JSONArray masked = new JSONArray(), tks = store.tokens();
                        for (int i = 0; i < tks.length(); i++) {
                            JSONObject t = tks.getJSONObject(i);
                            String tk = t.optString("token", "");
                            if (tk.length() > 8) t.put("token", tk.substring(0, 8) + "…");
                            masked.put(t);
                        }
                        out = new JSONObject().put("ok", true).put("tokens", masked);
                    }
                    break;
                case "status":
                    out = engine.status(sub);
                    break;
                case "checkin":
                    out = engine.checkin(sub);
                    break;
                case "logs": {
                    String cat = u.getQueryParameter("category");
                    if (cat == null || cat.isEmpty()) cat = "系统";
                    int limit = 20;
                    try { limit = Integer.parseInt(u.getQueryParameter("limit")); } catch (Exception ignored) {}
                    out = engine.logs(sub, cat, limit);
                    break;
                }
                case "history":
                    out = new JSONObject().put("ok", true).put("data", store.logs());
                    break;
                case "proxy-test":
                    out = engine.proxyTest();
                    break;
                case "refresh":
                    out = err("授权需在 App 内完成：账号页 → 一键 GitHub 授权");
                    break;
                default:
                    out = err("未知接口: " + u.getPath()); code = 404;
            }
            return json(out, code);
        } catch (ArrayIndexOutOfBoundsException e) {
            return json(err("参数缺失"), 400);
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            return json(err(m), m != null && m.contains("不存在") ? 404 : 500);
        }
    }

    private static JSONObject ok() { try { return new JSONObject().put("ok", true); } catch (Exception e) { return new JSONObject(); } }
    private static JSONObject err(String m) { try { return new JSONObject().put("ok", false).put("error", m); } catch (Exception e) { return new JSONObject(); } }

    private WebResourceResponse json(JSONObject o, int code) {
        return new WebResourceResponse("application/json", "utf-8", code, "OK", null,
                new ByteArrayInputStream(o.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /* ================= JS 桥（对齐 Electron preload 暴露的能力） ================= */

    public class Bridge {
        @JavascriptInterface public String version() {
            try { return new JSONObject().put("app", "android").put("version", "0.1.0").toString(); }
            catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void authStart(final String siteKey, final String accountKey) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, AuthActivity.class);
                i.putExtra("siteKey", siteKey);
                i.putExtra("accountKey", accountKey);
                startActivityForResult(i, AUTH_REQ);
            });
        }
        @JavascriptInterface public String authResult() { return authResultJson; }
    }

    @Override protected void onActivityResult(int rq, int rc, Intent data) {
        super.onActivityResult(rq, rc, data);
        if (rq != AUTH_REQ) return;
        try {
            JSONObject r = new JSONObject().put("ts", System.currentTimeMillis());
            if (rc == RESULT_OK && data != null && data.getBooleanExtra("ok", false)) {
                r.put("ok", true).put("user", data.getStringExtra("user"));
            } else {
                r.put("ok", false).put("error", data != null ? data.getStringExtra("error") : "取消");
            }
            authResultJson = r.toString();
        } catch (Exception ignored) {}
    }
}