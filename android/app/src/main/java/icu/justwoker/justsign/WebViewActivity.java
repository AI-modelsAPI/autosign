package icu.justwoker.justsign;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.Profile;

import org.json.JSONObject;

/**
 * 应用内可见网页容器。
 *
 * 为什么需要它（实测发现的问题）：
 *   原来「去网页」用 Intent.ACTION_VIEW 打开系统浏览器（Chrome）。
 *   Chrome 与本应用的 WebView **分区不同、Cookie 不共享** →
 *   用户在 Chrome 里登录了主站，应用的 WebView 里仍然是未登录 →
 *   福利站 SSO 永远拿不到主站会话 → 每次刷新都报「需要人工登录主站」。
 *
 * 本 Activity 用**账号专属分区**打开页面，与 AuthActivity / OffscreenCheckin
 * 同一个 profileNameFor(siteKey, accountKey)，因此：
 *   用户在这里登录一次 → 会话落在该账号分区 → 后续签到/刷新全静默。
 * 这正是「首登人工、后续静默」的落点。
 */
public class WebViewActivity extends Activity {

    private WebView wv;
    private Profile profile;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String siteKey = getIntent().getStringExtra("siteKey");
        String accountKey = getIntent().getStringExtra("accountKey");
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (url == null || url.isEmpty()) { finish(); return; }
        if (siteKey == null) siteKey = "";
        if (accountKey == null) accountKey = "";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);

        /* 顶栏：标题 + 关闭。让用户知道这是应用内页面，登录会留在应用里。 */
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFFF8FAFC);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView t = Ui.tv(this, title == null || title.isEmpty() ? "网页" : title,
                14, 0xFF111827, true);
        bar.addView(t);
        bar.addView(Ui.spring(this));
        TextView close = Ui.tv(this, "关闭", 13, 0xFF2563EB, true);
        close.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        close.setClickable(true);
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        wv = new WebView(this);
        if (!siteKey.isEmpty() || !accountKey.isEmpty()) {
            try {
                profile = WebViewProfileUtil.bindProfile(wv,
                        WebViewProfileUtil.profileNameFor(siteKey, accountKey));
                CookieManager pcm = WebViewProfileUtil.cookieManagerFor(profile);
                pcm.setAcceptCookie(true);
                pcm.setAcceptThirdPartyCookies(wv, true);
            } catch (Exception ignored) {}
        }
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setSupportMultipleWindows(true);
        wv.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        wv.setWebViewClient(new WebViewClient() {
            /* 站内跳转一律留在本 WebView（含 SSO 回跳），不开系统浏览器。 */
            @Override public boolean shouldOverrideUrlLoading(WebView v,
                    android.webkit.WebResourceRequest req) { return false; }
        });

        root.addView(wv, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        root.addView(bar, blp);
        /* 顶栏压在最上层 */
        bar.bringToFront();

        setContentView(root);
        wv.loadUrl(url);
    }

    @Override protected void onDestroy() {
        try { if (profile != null) WebViewProfileUtil.flush(profile); } catch (Exception ignored) {}
        try {
            if (wv != null) {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.destroy();
                wv = null;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    /** 便捷入口：用应用内 WebView 打开（会话与签到共用同一账号分区）。 */
    public static void open(Activity a, String siteKey, String accountKey, String url, String title) {
        if (a == null || url == null || url.isEmpty()) return;
        try {
            android.content.Intent i = new android.content.Intent(a, WebViewActivity.class);
            i.putExtra("siteKey", siteKey == null ? "" : siteKey);
            i.putExtra("accountKey", accountKey == null ? "" : accountKey);
            i.putExtra("url", url);
            i.putExtra("title", title == null ? "" : title);
            a.startActivity(i);
        } catch (Exception ignored) {}
    }
}
