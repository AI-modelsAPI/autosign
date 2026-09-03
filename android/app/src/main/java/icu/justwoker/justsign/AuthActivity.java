package icu.justwoker.justsign;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AuthActivity — 授权桥（App 方案的核心）：
 *   真实（非无头）WebView 打开 站点 /api/oauth/github → GitHub OAuth →
 *   完整浏览器指纹可通过 Cloudflare Turnstile → 回调后前端把 access_token
 *   写入 localStorage['new-api:auth-session'] → 这里轮询读取 → 落盘 → 自动关窗。
 *   之后签到/额度全部走纯接口，WebView 不再出现。
 */
public class AuthActivity extends Activity {
    private WebView wv;
    private String siteKey, accountKey, baseUrl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean done = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        siteKey = getIntent().getStringExtra("siteKey");
        accountKey = getIntent().getStringExtra("accountKey");
        baseUrl = findBaseUrl();

        FrameLayout root = new FrameLayout(this);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient()); // 站内/OAuth 跳转都留在本 WebView
        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));

        TextView tip = new TextView(this);
        tip.setText("GitHub 授权中 · 设备登录过 GitHub 会自动完成，成功后本窗口自动关闭");
        tip.setTextColor(Color.WHITE);
        tip.setTextSize(12);
        tip.setBackgroundColor(0xCC1F2550);
        tip.setPadding(28, 20, 28, 20);
        root.addView(tip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP));

        setContentView(root);
        wv.loadUrl(baseUrl.replaceAll("/+$", "") + "/api/oauth/github");
        h.postDelayed(poll, 1500);
    }

    private String findBaseUrl() {
        Store store = new Store(this);
        try {
            JSONArray sites = store.config().getJSONArray("sites");
            for (int i = 0; i < sites.length(); i++) {
                JSONObject st = sites.getJSONObject(i);
                if (siteKey != null && siteKey.equals(st.optString("key"))) return st.optString("baseUrl");
            }
        } catch (Exception ignored) {}
        return "https://api.justwoker.icu";
    }

    /** 1.5s 轮询 localStorage，拿到 access_token 即成功 */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (done || wv == null) return;
            wv.evaluateJavascript("localStorage.getItem('new-api:auth-session')", v -> {
                try {
                    if (v != null && !"null".equals(v) && v.length() > 4) {
                        String raw = v;
                        if (raw.startsWith("\"")) // evaluateJavascript 返回的是 JS 字符串字面量
                            raw = raw.substring(1, raw.length() - 1)
                                    .replace("\\\"", "\"").replace("\\\\", "\\");
                        JSONObject sess = new JSONObject(raw);
                        String token = sess.optString("access_token", null);
                        if (token == null || token.isEmpty()) token = sess.optString("accessToken", null);
                        if (token != null && !token.isEmpty()) { finishOk(sess, token); return; }
                    }
                } catch (Exception ignored) {}
                h.postDelayed(this, 1500);
            });
        }
    };

    private void finishOk(JSONObject sess, String token) {
        done = true;
        Store store = new Store(this);
        try {
            JSONObject rec = store.findToken(accountKey);
            if (rec == null) rec = new JSONObject().put("key", accountKey);
            rec.put("siteKey", siteKey);
            String login = null;
            try { login = sess.getJSONObject("user").optString("login", null); } catch (Exception ignored) {}
            if (login != null) rec.put("githubAccount", login);
            rec.put("token", token);
            rec.put("updatedAt", System.currentTimeMillis());
            store.upsertToken(rec);
            store.appendLog(siteKey, accountKey, "auth", "via=android user=" + (login == null ? "?" : login));

            Intent out = new Intent();
            out.putExtra("ok", true);
            out.putExtra("user", login == null ? "" : login);
            setResult(RESULT_OK, out);
        } catch (Exception e) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", "保存失败: " + e.getMessage()));
        }
        finish();
    }

    @Override public void onBackPressed() {
        done = true;
        setResult(RESULT_CANCELED, new Intent().putExtra("error", "授权已取消"));
        finish();
    }

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
    @Override protected void onDestroy() {
        done = true;
        h.removeCallbacksAndMessages(null);
        if (wv != null) wv.destroy();
        super.onDestroy();
    }
}