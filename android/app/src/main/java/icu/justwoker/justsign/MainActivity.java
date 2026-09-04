package icu.justwoker.justsign;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MainActivity — 纯原生 Material 3 风格首页（零 WebView、零网页壳）：
 *   卡片式账号列表（可用/已用/今日消耗大字排版）+ 原生对话框交互 + 自动/手动刷新。
 *   - "一键授权" 跳 AuthActivity（新版 new-api 两步 OAuth，流程内自动完成）
 *   - 签到/日志/删除 全部原生 AlertDialog
 *   - WorkManager 12h 后台调度在此激活
 */
public class MainActivity extends Activity {

    private static final int REQ_AUTH = 1001;

    /* ---------- M3 色板 ---------- */
    private static final int C_BG = 0xFFF1F5F9;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF0F172A;
    private static final int C_SUB = 0xFF64748B;
    private static final int C_ACCENT = 0xFF2563EB;
    private static final int C_GREEN = 0xFF16A34A;
    private static final int C_ORANGE = 0xFFEA580C;
    private static final int C_PURPLE = 0xFF7C3AED;
    private static final int C_RED = 0xFFDC2626;
    private static final int C_CHIP_OK = 0xFFDCFCE7;
    private static final int C_CHIP_WAIT = 0xFFE2E8F0;
    private static final int C_BTN_GHOST = 0xFFEFF6FF;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Store store;
    private Engine engine;
    private LinearLayout list;
    private TextView subtitle;
    private final Map<String, Long> unitCache = new HashMap<>();
    private volatile boolean refreshing = false;
    private long lastRefresh = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        engine = new Engine(this);
        try { Engine.schedule(this); } catch (Exception ignored) {}
        buildUi();
        render();       // 先渲染缓存，秒开
        refresh(false); // 再后台拉真实数据
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        if (System.currentTimeMillis() - lastRefresh > 60_000) refresh(false);
    }

    /* ================= UI 构建 ================= */

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        page.setPadding(pad, dp(28), pad, dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        /* 顶栏：标题 + 刷新按钮 */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("公益签到");
        title.setTextColor(C_TEXT);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView refresh = pillButton("⟳ 刷新", C_ACCENT, Color.WHITE);
        refresh.setOnClickListener(v -> refresh(true));
        top.addView(refresh, new LinearLayout.LayoutParams(-2, dp(38)));
        page.addView(top);

        subtitle = new TextView(this);
        subtitle.setText("正在同步…");
        subtitle.setTextColor(C_SUB);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, 0);
        page.addView(subtitle);

        /* 账号卡片容器 */
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(16), 0, 0);
        page.addView(list);

        /* 添加账号（大按钮卡片） */
        LinearLayout add = card();
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(16), dp(18), dp(16), dp(18));
        TextView addText = new TextView(this);
        addText.setText("＋  添加账号（GitHub 一键授权）");
        addText.setTextColor(C_ACCENT);
        addText.setTextSize(15);
        addText.setTypeface(Typeface.DEFAULT_BOLD);
        add.addView(addText);
        add.setOnClickListener(v -> addAccountDialog());
        page.addView(add, lpCard(dp(4)));

        /* 底部说明 */
        TextView foot = new TextView(this);
        foot.setText("后台每 12 小时自动保活签到 · 数据实时来自站点 API");
        foot.setTextColor(0xFF94A3B8);
        foot.setTextSize(11);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(20), 0, 0);
        page.addView(foot);

        setContentView(scroll);
    }

    /** M3 卡片：白底圆角 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(22));
        c.setBackground(bg);
        c.setElevation(dp(2));
        return c;
    }

    private LinearLayout.LayoutParams lpCard(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMargin;
        return lp;
    }

    /** 胶囊按钮 + 原生按压涟漪 */
    private TextView pillButton(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(19));
        t.setBackground(g);
        ripple(t);
        return t;
    }

    /** 描边幽灵小按钮 */
    private TextView ghostButton(String text) {
        return pillButton(text, C_BTN_GHOST, C_ACCENT);
    }

    private void ripple(View v) {
        try {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (android.os.Build.VERSION.SDK_INT >= 23) v.setForeground(getDrawable(tv.resourceId));
        } catch (Exception ignored) {}
    }

    private TextView label(String text, int color, float sizeSp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /* ================= 渲染 ================= */

    private void render() {
        JSONArray tokens = store.tokens();
        list.removeAllViews();
        int n = tokens.length();

        if (n == 0) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(40), dp(24), dp(40));
            TextView big = label("还没有账号", C_TEXT, 18, true);
            big.setGravity(Gravity.CENTER);
            TextView tip = label("点击下方「添加账号」\n授权过程全自动，无需手动复制 token", C_SUB, 13, false);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(10), 0, 0);
            empty.addView(big);
            empty.addView(tip);
            list.addView(empty);
            subtitle.setText("添加后自动开始同步额度");
            return;
        }

        for (int i = 0; i < n; i++) {
            JSONObject tk = tokens.optJSONObject(i);
            if (tk != null) list.addView(accountCard(tk));
        }
        subtitle.setText(n + " 个账号 · 上次同步 " + fmtClock(lastRefresh));
    }

    /** 单账号卡片（缓存即时渲染，额度有则显示） */
    private LinearLayout accountCard(JSONObject tk) {
        final String key = tk.optString("key");
        LinearLayout c = card();

        /* 行1：别名 + 状态 chip */
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(tk.optString("alias", key), C_TEXT, 17, true);
        row1.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean hasToken = !tk.optString("token", "").isEmpty();
        boolean expired = false;
        JSONObject cached = tk.optJSONObject("lastStatus");
        if (cached != null && cached.has("authorized")) expired = !cached.optBoolean("authorized");

        TextView chip = label(hasToken ? (expired ? "已过期" : "已授权") : "待授权",
                hasToken ? (expired ? 0xFFB91C1C : 0xFF15803D) : C_SUB, 11, true);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(hasToken ? (expired ? 0xFFFEE2E2 : C_CHIP_OK) : C_CHIP_WAIT);
        chipBg.setCornerRadius(dp(20));
        chip.setBackground(chipBg);
        row1.addView(chip);
        c.addView(row1);

        /* 行2：站点 · GitHub 用户名 */
        String sub = siteName(tk.optString("siteKey"));
        String gh = tk.optString("githubAccount", "");
        if (!gh.isEmpty()) sub += "  ·  @" + gh;
        TextView subT = label(sub, C_SUB, 12, false);
        subT.setPadding(0, dp(3), 0, 0);
        c.addView(subT);

        /* 行3：额度大字（缓存优先） */
        LinearLayout quotaRow = new LinearLayout(this);
        quotaRow.setOrientation(LinearLayout.HORIZONTAL);
        quotaRow.setGravity(Gravity.BOTTOM);
        quotaRow.setPadding(0, dp(14), 0, dp(4));
        if (cached != null && cached.optBoolean("ok") && cached.optBoolean("authorized", true)) {
            TextView avail = label("$" + fmtUsd(cached.optDouble("availableUSD", 0)), C_GREEN, 30, true);
            quotaRow.addView(avail, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout side = new LinearLayout(this);
            side.setOrientation(LinearLayout.VERTICAL);
            TextView used = label("已用 $" + fmtUsd(cached.optDouble("usedUSD", 0)), C_ORANGE, 12, true);
            used.setGravity(Gravity.RIGHT);
            side.addView(used);
            if (cached.has("todayUsed")) {
                TextView today = label("今日 $" + fmtUsd(cached.optDouble("todayUsed", 0)), C_PURPLE, 12, false);
                today.setGravity(Gravity.RIGHT);
                today.setPadding(0, dp(2), 0, 0);
                side.addView(today);
            }
            quotaRow.addView(side, new LinearLayout.LayoutParams(-2, -2));
        } else {
            String ph = hasToken ? "额度同步中…" : "完成授权后显示额度";
            TextView phT = label(ph, 0xFF94A3B8, 15, false);
            quotaRow.addView(phT, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        c.addView(quotaRow);

        /* 授权过期提示 */
        if (expired) {
            TextView warn = label("⚠ 授权已过期，请点击「重新授权」", C_RED, 12, false);
            warn.setPadding(0, dp(6), 0, 0);
            c.addView(warn);
        }

        /* 行4：操作按钮组 */
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, 0);
        TextView checkin = ghostButton(hasToken ? "立即签到" : "一键授权");
        checkin.setOnClickListener(v -> {
            if (hasToken) doCheckin(key); else startAuth(key, tk.optString("alias", key));
        });
        btns.addView(checkin, new LinearLayout.LayoutParams(0, dp(34), 1f));
        btns.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));

        if (hasToken) {
            if (expired) {
                TextView reauth = ghostButton("重新授权");
                reauth.setOnClickListener(v -> startAuth(key, tk.optString("alias", key)));
                btns.addView(reauth, new LinearLayout.LayoutParams(0, dp(34), 1f));
                btns.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            }
            TextView logB = ghostButton("日志");
            logB.setOnClickListener(v -> showLogs(key));
            btns.addView(logB, new LinearLayout.LayoutParams(0, dp(34), 1f));
            btns.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        }

        TextView del = ghostButton("删除");
        del.setOnClickListener(v -> confirmDelete(key, tk.optString("alias", key)));
        btns.addView(del, new LinearLayout.LayoutParams(0, dp(34), 1f));
        c.addView(btns);

        return c;
    }

    private String siteName(String siteKey) {
        try {
            JSONArray sites = store.config().optJSONArray("sites");
            if (sites != null) for (int i = 0; i < sites.length(); i++) {
                JSONObject s = sites.optJSONObject(i);
                if (s != null && siteKey != null && siteKey.equals(s.optString("key"))) return s.optString("name", siteKey);
            }
        } catch (Exception ignored) {}
        return siteKey == null ? "" : siteKey;
    }

    /* ================= 数据刷新 ================= */

    private void refresh(boolean manual) {
        if (refreshing) return;
        refreshing = true;
        if (manual) subtitle.setText("正在刷新…");
        JSONArray tokens = store.tokens();
        final int total = tokens.length();
        if (total == 0) {
            refreshing = false;
            lastRefresh = System.currentTimeMillis();
            render();
            return;
        }
        final int[] done = {0};
        for (int i = 0; i < total; i++) {
            JSONObject tk = tokens.optJSONObject(i);
            if (tk == null || tk.optString("token", "").isEmpty()) {
                if (++done[0] == total) h.post(() -> onRefreshDone(manual));
                continue;
            }
            final String key = tk.optString("key");
            new Thread(() -> {
                try {
                    JSONObject r = engine.status(key);
                    if (r.optBoolean("ok")) {
                        unitCache.put(key, r.optLong("unit", 500000L));
                        JSONObject tk2 = store.findToken(key);
                        if (tk2 != null) {
                            tk2.put("lastStatus", r);
                            store.upsertToken(tk2);
                        }
                    }
                } catch (Exception ignored) {}
                h.post(() -> { if (++done[0] == total) onRefreshDone(manual); });
            }).start();
        }
    }

    private void onRefreshDone(boolean manual) {
        refreshing = false;
        lastRefresh = System.currentTimeMillis();
        render();
        if (manual) Toast.makeText(this, "已同步最新额度", Toast.LENGTH_SHORT).show();
    }

    private static String fmtUsd(double v) {
        double a = Math.abs(v);
        if (a >= 1000) return String.format(Locale.US, "%.0f", v);
        if (a >= 100) return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmtClock(long ms) {
        if (ms <= 0) return "—";
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(ms));
    }

    private static String fmtLogTime(String raw) {
        try {
            if (raw != null && raw.matches("\\d{10,13}")) {
                long t = Long.parseLong(raw);
                if (raw.length() == 13) t /= 1000;
                return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(t * 1000));
            }
        } catch (Exception ignored) {}
        return raw == null ? "" : raw;
    }

    /* ================= 交互 ================= */

    /** 添加账号：输入别名 → 直接进授权 */
    private void addAccountDialog() {
        final EditText input = new EditText(this);
        input.setHint("账号别名，如：主号 / 小号");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(15);
        int p = dp(20);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(p, dp(8), p, 0);
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle("添加账号")
                .setMessage("下一步将打开 GitHub 授权页，\n登录后自动返回，全程无需复制粘贴。")
                .setView(box)
                .setPositiveButton("开始授权", (d, w) -> {
                    String alias = input.getText().toString().trim();
                    if (alias.isEmpty()) alias = "账号" + (store.tokens().length() + 1);
                    startAuth(newAliasKey(alias), alias);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 别名 → 唯一 key（同别名复用同一条记录，实现"重新授权"） */
    private String newAliasKey(String alias) {
        JSONArray a = store.tokens();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && alias.equals(o.optString("alias"))) return o.optString("key");
        }
        return "acc_" + System.currentTimeMillis();
    }

    private void startAuth(String key, String alias) {
        // 先占位，授权回来前卡片即可见
        try {
            JSONObject rec = store.findToken(key);
            if (rec == null) {
                rec = new JSONObject().put("key", key).put("alias", alias);
                store.upsertToken(rec);
            } else if (alias != null && !alias.isEmpty()) {
                rec.put("alias", alias);
                store.upsertToken(rec);
            }
        } catch (Exception ignored) {}
        Intent it = new Intent(this, AuthActivity.class);
        it.putExtra("siteKey", "justworker");
        it.putExtra("accountKey", key);
        startActivityForResult(it, REQ_AUTH);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_AUTH) return;
        render();
        if (res == RESULT_OK) {
            String user = data == null ? "" : data.getStringExtra("user");
            Toast.makeText(this, "授权成功 " + (user == null || user.isEmpty() ? "" : "@" + user) + "，正在同步额度", Toast.LENGTH_LONG).show();
            refresh(false);
        } else {
            String err = data == null ? "已取消" : data.getStringExtra("error");
            Toast.makeText(this, err == null ? "授权未完成" : err, Toast.LENGTH_SHORT).show();
        }
    }

    /** 立即签到（login 型站点自动转为"刷新保活"） */
    private void doCheckin(String key) {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("正在签到…").show();
        new Thread(() -> {
            String msg;
            try {
                JSONObject r = engine.checkin(key);
                if (r.optBoolean("skipped")) msg = r.optString("message", "该站点登录即签到，已为你刷新保活");
                else msg = "签到完成 (http " + r.optInt("http") + ")";
            } catch (Exception e) { msg = "签到失败: " + e.getMessage(); }
            String finalMsg = msg;
            h.post(() -> {
                wait.dismiss();
                refresh(false);
                new AlertDialog.Builder(this)
                        .setTitle("签到")
                        .setMessage(finalMsg)
                        .setPositiveButton("好的", null)
                        .show();
            });
        }).start();
    }

    /** 站点日志（含最近一次签到奖励高亮） */
    private void showLogs(String key) {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("正在拉取日志…").show();
        new Thread(() -> {
            String text;
            try {
                JSONObject r = engine.logs(key, "system", 20);
                StringBuilder sb = new StringBuilder();
                JSONObject bonus = r.optJSONObject("lastBonus");
                if (bonus != null) {
                    sb.append("🎁 最近签到奖励\n  ").append(fmtLogTime(bonus.optString("time")));
                    long q = bonus.optLong("quota", 0);
                    if (q > 0) {
                        Long unit = unitCache.get(key);
                        sb.append("  +$").append(fmtUsd(q / (unit == null ? 500000L : unit)));
                    }
                    sb.append("\n\n");
                }
                JSONArray rows = r.optJSONArray("rows");
                int shown = 0;
                if (rows != null) {
                    for (int i = rows.length() - 1; i >= 0 && shown < 10; i--, shown++) {
                        JSONObject o = rows.optJSONObject(i);
                        if (o == null) continue;
                        sb.append(fmtLogTime(o.optString("time"))).append("  ").append(o.optString("text", "")).append('\n');
                    }
                }
                if (shown == 0 && bonus == null) sb.append("暂无系统日志记录");
                text = sb.toString();
            } catch (Exception e) { text = "日志拉取失败: " + e.getMessage(); }
            String finalText = text;
            h.post(() -> {
                wait.dismiss();
                TextView tv = new TextView(this);
                tv.setText(finalText);
                tv.setTextSize(13);
                tv.setTextColor(C_TEXT);
                tv.setTypeface(Typeface.MONOSPACE);
                int p = dp(20);
                ScrollView sv = new ScrollView(this);
                sv.addView(tv, new ScrollView.LayoutParams(-1, -2));
                FrameLayout box = new FrameLayout(this);
                box.setPadding(p, dp(10), p, dp(4));
                box.addView(sv, new FrameLayout.LayoutParams(-1, dp(360)));
                new AlertDialog.Builder(this)
                        .setTitle("系统日志")
                        .setView(box)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }).start();
    }

    private void confirmDelete(final String key, String alias) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定删除「" + alias + "」吗？\n仅删除本地记录，不影响站点账号。")
                .setPositiveButton("删除", (d, w) -> {
                    store.removeToken(key);
                    render();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override public void onBackPressed() {
        // 常驻后台应用：返回键回到桌面而非退出（WorkManager 持续保活签到）
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        startActivity(home);
    }
}