package icu.justwoker.justsign;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MainActivity（v0.2.0 重构）— 4 Tab 外壳 + 摘要顶栏 + 手势卡片。
 *
 * 结构：
 *   顶栏（56dp）：AutoSign / "4站 5号 · 4已签 1待签" | 总资产胶囊
 *   内容区：签到看板（卡片列表）或 设置页（SettingsView）
 *   底部 Tab（56dp）：
 *     [页面型] 签到看板   —— 持久高亮
 *     [动作型] 一键签到   —— 不高亮，原地执行，Tab 上显示 "⏳ 签到 1/4" → "✓ 完成"
 *     [动作型] 一键刷新   —— 同上
 *     [页面型] 设置       —— 持久高亮
 *   日志：悬浮窗（LogPopup），批量任务开始时自动弹（可在设置关闭）
 *
 * 卡片（SwipeCard 包裹）：
 *   右滑刷新 / 左滑露删除；站点名 "JustDoWork ↗" 蓝色带箭头一眼是链接
 *   账号行：可用余额 22sp + "+$28.07 今日签到" 绿标；副行 今日消耗 · 累计已用 ······ 日志›
 *   操作：1 主胶囊（签到/已签/去授权）+ ⋯ 溢出菜单
 */
public class MainActivity extends Activity {

    private static final int REQ_AUTH = 41;
    private static final int REQ_CHECKIN = 42;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Engine engine;

    /* 顶栏 */
    private TextView topSummary, topTotal;
    /* 内容区 */
    private FrameLayout content;
    private ScrollView boardScroll;
    private LinearLayout boardList;
    private LinearLayout emptyView;
    private SettingsView settings;
    /* 底部 Tab */
    private LinearLayout tabBar;
    private TextView tabBoardIcon, tabBoardText, tabCheckIcon, tabCheckText,
            tabRefreshIcon, tabRefreshText, tabSetIcon, tabSetText;

    private int page = 0;                       // 0=看板 1=设置
    private volatile boolean bulkBusy = false;  // 全局动作互斥
    private volatile boolean singleBusy = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.initIcons(this);
        engine = new Engine(this);
        Engine.schedule(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        root.addView(Ui.divider(this, Ui.LINE_SOFT, 0));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildBoard();
        settings = new SettingsView(this);
        settings.setVisibility(View.GONE);
        content.addView(settings, new FrameLayout.LayoutParams(-1, -1));

        root.addView(Ui.divider(this, Ui.LINE, 0));
        root.addView(buildTabBar(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        setContentView(root);
        showPage(0);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    @Override public void onBackPressed() {
        if (LogPopup.isShowing()) { LogPopup.dismissIfShowing(); return; }
        if (page == 1 && settings != null && settings.onBack()) return;   // 设置子页返回
        if (page == 1) { showPage(0); return; }
        goHome();
    }

    /* ================= 顶栏 ================= */

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);

        LinearLayout brand = Ui.col(this);
        brand.addView(Ui.tv(this, "AutoSign", 16, Ui.TXT, true));
        topSummary = Ui.tv(this, "", 10, Ui.SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(this, 1);
        brand.addView(topSummary, slp);
        bar.addView(brand);
        bar.addView(Ui.spring(this));

        LinearLayout pill = Ui.row(this);
        pill.setBackground(Ui.roundStroke(Ui.GREEN_BG2, Ui.dp(this, 8),
                Math.max(1, Ui.dp(this, 1)), 0xFFDCFCE7));
        pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        pill.addView(Ui.tv(this, "总资产", 10, 0xFF15803D));
        topTotal = Ui.tv(this, "$0.00", 13, 0xFF15803D, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Ui.dp(this, 4);
        pill.addView(topTotal, tlp);
        pill.setClickable(true);
        pill.setOnClickListener(v -> showAssetBreakdown());
        bar.addView(pill);
        return bar;
    }

    private void showAssetBreakdown() {
        StringBuilder sb = new StringBuilder();
        JSONArray sites = new Store(this).sites();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            JSONArray accs = s.optJSONArray("accounts");
            if (accs == null || accs.length() == 0) continue;
            double sum = 0;
            int n = 0;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject a = accs.optJSONObject(j);
                if (a == null) continue;
                JSONObject st = a.optJSONObject("lastStatus");
                if (st != null && st.optBoolean("ok")) { sum += st.optDouble("availableUSD", 0); n++; }
            }
            sb.append(s.optString("name")).append("  $").append(Ui.usd(sum))
              .append("  (").append(n).append(" 号)\n");
        }
        if (sb.length() == 0) sb.append("暂无数据，先刷新一次");
        new AlertDialog.Builder(this).setTitle("各站点资产")
                .setMessage(sb.toString()).setPositiveButton("关闭", null).show();
    }

    /* ================= 底部 Tab ================= */

    private View buildTabBar() {
        tabBar = Ui.row(this);
        tabBar.setBackgroundColor(Ui.CARD);

        LinearLayout t0 = tabItem(Ui.ic("tab_board"), "签到", true);
        tabBoardIcon = (TextView) t0.getChildAt(0);
        tabBoardText = (TextView) t0.getChildAt(1);
        t0.setOnClickListener(v -> showPage(0));

        LinearLayout t1 = tabItem(Ui.ic("tab_checkin"), "一键签到", false);
        tabCheckIcon = (TextView) t1.getChildAt(0);
        tabCheckText = (TextView) t1.getChildAt(1);
        t1.setOnClickListener(v -> bulkCheckin());

        LinearLayout t2 = tabItem(Ui.ic("tab_refresh"), "一键刷新", false);
        tabRefreshIcon = (TextView) t2.getChildAt(0);
        tabRefreshText = (TextView) t2.getChildAt(1);
        t2.setOnClickListener(v -> bulkRefresh());

        LinearLayout t3 = tabItem(Ui.ic("tab_settings"), "设置", false);
        tabSetIcon = (TextView) t3.getChildAt(0);
        tabSetText = (TextView) t3.getChildAt(1);
        t3.setOnClickListener(v -> showPage(1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        tabBar.addView(t0, lp);
        tabBar.addView(t1, new LinearLayout.LayoutParams(0, -1, 1f));
        tabBar.addView(t2, new LinearLayout.LayoutParams(0, -1, 1f));
        tabBar.addView(t3, new LinearLayout.LayoutParams(0, -1, 1f));
        return tabBar;
    }

    private LinearLayout tabItem(String icon, String label, boolean active) {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);
        int fg = active ? Ui.BLUE : Ui.TXT2;
        box.addView(Ui.tv(this, icon, 17, fg, false));
        TextView t = Ui.tv(this, label, 10, fg, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 2);
        box.addView(t, lp);
        return box;
    }

    private void showPage(int p) {
        page = p;
        boardScroll.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        if (emptyView != null) emptyView.setVisibility(View.GONE);
        settings.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        /* 页面型 Tab 高亮；动作型永不高亮 */
        int on = Ui.BLUE, off = Ui.SUB2;
        tabBoardIcon.setTextColor(p == 0 ? on : off);
        tabBoardText.setTextColor(p == 0 ? on : off);
        tabSetIcon.setTextColor(p == 1 ? on : off);
        tabSetText.setTextColor(p == 1 ? on : off);
        if (p == 1) settings.refresh();
        else render();
    }

    /* ================= 看板 ================= */

    private void buildBoard() {
        boardList = Ui.col(this);
        boardList.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 24));
        boardScroll = new ScrollView(this);
        boardScroll.addView(boardList);
        content.addView(boardScroll, new FrameLayout.LayoutParams(-1, -1));

        emptyView = Ui.col(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 40));
        emptyView.addView(Ui.tv(this, Ui.ic("globe"), 42, Ui.SUB2));
        TextView et = Ui.tv(this, "暂无配置站点", 15, Ui.TXT2, true);
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(-2, -2);
        e1.topMargin = Ui.dp(this, 12);
        emptyView.addView(et, e1);
        TextView ed = Ui.tv(this, "前往「设置 → 站点管理」添加公益中转站", 12, Ui.SUB2);
        ed.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams e2 = new LinearLayout.LayoutParams(-2, -2);
        e2.topMargin = Ui.dp(this, 4);
        emptyView.addView(ed, e2);
        TextView go = Ui.btn(this, "去添加站点 " + Ui.ic("chevron"), 13, Ui.BLUE, Ui.BLUE_BG, 16, 8);
        go.setOnClickListener(v -> { showPage(1); settings.openSites(); });
        LinearLayout.LayoutParams e3 = new LinearLayout.LayoutParams(-2, -2);
        e3.topMargin = Ui.dp(this, 16);
        emptyView.addView(go, e3);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
    }

    void render() {
        if (boardList == null) return;
        Store store = new Store(this);
        /* 顶栏摘要 */
        JSONObject sm = store.summary();
        topSummary.setText(sm.optInt("sites") + "站 " + sm.optInt("accounts") + "号 · "
                + sm.optInt("checked") + "已签 " + sm.optInt("pending") + "待签");
        topTotal.setText("$" + Ui.usd(sm.optDouble("totalUSD", 0)));

        boardList.removeAllViews();
        JSONArray sites = store.sites();
        boolean empty = sites.length() == 0;
        if (emptyView != null) emptyView.setVisibility((empty && page == 0) ? View.VISIBLE : View.GONE);
        boardScroll.setVisibility((!empty && page == 0) ? View.VISIBLE : View.GONE);
        if (empty) return;

        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            SwipeCard sc = new SwipeCard(this);
            sc.setCard(siteCard(site));
            final JSONObject fs = site;
            sc.setListener(new SwipeCard.Listener() {
                @Override public void onRefresh() { refreshSite(fs); }
                @Override public void onDelete() { confirmRemoveSite(fs); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 12);
            boardList.addView(sc, lp);
        }
    }

    /* ---------- 站点卡片 ---------- */

    private View siteCard(JSONObject site) {
        final String siteKey = site.optString("key");
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(this, 12), Math.max(1, Ui.dp(this, 1)), Ui.LINE));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));

        /* 站头 */
        LinearLayout head = Ui.row(this);
        LinearLayout info = Ui.col(this);
        /* 站点名：蓝色 + ↗，一眼可见是链接 */
        TextView name = Ui.tv(this, site.optString("name", siteKey) + " " + Ui.ic("link"), 15, Ui.BLUE, true);
        name.setClickable(true);
        name.setOnClickListener(v -> {
            String home = site.optString("homeUrl", site.optString("baseUrl", ""));
            if (home.isEmpty()) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(home))); }
            catch (Exception e) { toast("无法打开链接"); }
        });
        info.addView(name);
        String host = site.optString("baseUrl", "").replaceFirst("^https?://", "");
        TextView meta = Ui.tv(this, host + " · "
                + ("manual".equals(site.optString("checkinType")) ? "手动签到" : "登录即签到"), 11, Ui.SUB2);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = Ui.dp(this, 2);
        info.addView(meta, mlp);
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        /* 多账号才显示聚合进度（单账号交给账号行，避免重复） */
        JSONArray accs = site.optJSONArray("accounts");
        int total = accs == null ? 0 : accs.length();
        if (total > 1) {
            int done = 0;
            for (int i = 0; i < total; i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null && Engine.isCheckedToday(a)) done++;
            }
            boolean all = done == total;
            head.addView(Ui.pill(this, done + "/" + total + " 已签", 10,
                    all ? Ui.GREEN : Ui.ORANGE, all ? Ui.GREEN_BG2 : Ui.AMBER_BG));
            head.addView(Ui.gapW(this, 6));
        }
        TextView add = Ui.flat(this, Ui.ic("plus") + "账号", 11, Ui.BLUE);
        add.setOnClickListener(v -> promptAddAccount(site));
        TextView more = Ui.flat(this, Ui.ic("more"), 14, Ui.SUB);
        more.setOnClickListener(v -> siteMenu(site));
        head.addView(add);
        head.addView(more);
        card.addView(head);

        /* 账号列表 */
        if (total == 0) {
            TextView t = Ui.tv(this, "该站点下还没有账号，点上方「＋账号」添加", 12, Ui.SUB2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 12);
            card.addView(t, lp);
        } else {
            for (int i = 0; i < total; i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a == null) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = Ui.dp(this, i == 0 ? 10 : 8);
                card.addView(accountItem(site, a), lp);
            }
        }
        return card;
    }

    /* ---------- 账号条目 ---------- */

    private View accountItem(JSONObject site, JSONObject acc) {
        final String key = acc.optString("key");
        final String token = acc.optString("token", "");
        final boolean authed = !token.isEmpty();
        final boolean checked = Engine.isCheckedToday(acc);
        JSONObject st = acc.optJSONObject("lastStatus");
        boolean stOk = st != null && st.optBoolean("ok");

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.roundStroke(Ui.CARD_SUB, Ui.dp(this, 8),
                Math.max(1, Ui.dp(this, 1)), Ui.LINE_SOFT));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        /* 行1：身份 + 主胶囊 + ⋯ */
        LinearLayout r1 = Ui.row(this);
        LinearLayout ident = Ui.row(this);
        ident.addView(Ui.tv(this, acc.optString("alias", key), 13, Ui.TXT2, true));
        String gh = acc.optString("githubAccount", "");
        if (!gh.isEmpty()) {
            ident.addView(Ui.gapW(this, 6));
            ident.addView(Ui.tv(this, "@" + gh, 11, Ui.SUB));
        }
        /* 状态 chip：只有异常/未授权才显示（正常是常态，不打扰） */
        if (!authed) {
            ident.addView(Ui.gapW(this, 6));
            ident.addView(Ui.pill(this, "待授权", 10, Ui.ORANGE, Ui.AMBER_BG));
        } else if (st != null && !stOk) {
            ident.addView(Ui.gapW(this, 6));
            ident.addView(Ui.pill(this, "异常", 10, Ui.RED, Ui.RED_BG));
        }
        r1.addView(ident, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView main;
        if (!authed) {
            main = Ui.btn(this, "去授权", 11, Ui.RED, Ui.RED_BG, 10, 4);
            main.setOnClickListener(v -> startAuth(site, acc));
        } else if (checked) {
            main = Ui.pill(this, Ui.ic("check") + " 已签", 11, Ui.GREEN_D, Ui.GREEN_BG);
            main.setClickable(true);
            main.setOnClickListener(v -> {
                JSONObject lc = acc.optJSONObject("lastCheckin");
                double rw = lc == null ? 0 : lc.optDouble("reward", 0);
                toast(rw > 0 ? ("今日签到奖励 +$" + Ui.usd(rw)) : "今日已签到");
            });
        } else {
            main = Ui.btn(this, "签到", 11, Ui.white(), Ui.BLUE, 12, 4);
            main.setOnClickListener(v -> doCheckin(key, main));
        }
        r1.addView(main);
        TextView ovf = Ui.flat(this, Ui.ic("more"), 14, Ui.SUB2);
        ovf.setOnClickListener(v -> accountMenu(site, acc));
        r1.addView(ovf);
        box.addView(r1);

        /* 行2：可用余额 + 今日签到奖励 */
        LinearLayout r2 = Ui.row(this);
        if (stOk) {
            r2.addView(Ui.tv(this, "可用余额", 11, Ui.SUB));
            TextView bal = Ui.tv(this, "$" + Ui.usd(st.optDouble("availableUSD", 0)), 22, Ui.GREEN, true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ui.dp(this, 6);
            r2.addView(bal, blp);
            /* 今日签到奖励（这就是之前完全没显示的数字） */
            double reward = 0;
            JSONObject lc = acc.optJSONObject("lastCheckin");
            if (lc != null && Engine.todayStr().equals(lc.optString("date", ""))) reward = lc.optDouble("reward", 0);
            if (reward <= 0 && st.optBoolean("todayChecked")) reward = st.optDouble("todayRewardUSD", 0);
            if (reward > 0) {
                TextView rw = Ui.pill(this, "+$" + Ui.usd(reward) + " 今日签到", 11, Ui.GREEN_D, Ui.GREEN_BG);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
                rlp.leftMargin = Ui.dp(this, 8);
                r2.addView(rw, rlp);
            }
        } else {
            r2.addView(Ui.tv(this, authed ? "右滑卡片或点「一键刷新」获取额度" : "尚未授权，请先完成授权", 12, Ui.SUB2));
        }
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = Ui.dp(this, 8);
        box.addView(r2, r2lp);

        /* 行3：今日消耗 · 累计已用 ······ 日志› */
        LinearLayout r3 = Ui.row(this);
        if (stOk) {
            String todayTxt;
            int todayColor;
            if (st.has("todayUsed") && !st.isNull("todayUsed")) {
                double tu = st.optDouble("todayUsed", 0);
                todayTxt = "今日消耗 $" + Ui.usd(tu);
                todayColor = tu > 0 ? Ui.ORANGE : Ui.SUB2;
            } else {
                todayTxt = "今日消耗 —";
                todayColor = Ui.SUB2;
            }
            r3.addView(Ui.tv(this, todayTxt, 11, todayColor));
            r3.addView(Ui.tv(this, "  ·  ", 11, 0xFFD1D5DB));
            r3.addView(Ui.tv(this, "累计已用 $" + Ui.usd(st.optDouble("usedUSD", 0)), 11, Ui.SUB));
        }
        r3.addView(Ui.spring(this));
        TextView logBtn = Ui.flat(this, "日志 " + Ui.ic("chevron"), 11, Ui.SUB);
        logBtn.setOnClickListener(v -> LogPopup.show(this));
        r3.addView(logBtn);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(-1, -2);
        r3lp.topMargin = Ui.dp(this, 6);
        box.addView(r3, r3lp);

        /* 行4：错误横幅（有 message 且异常时） */
        if (st != null && !stOk) {
            String msg = st.optString("message", "");
            if (!msg.isEmpty()) {
                TextView warn = Ui.tv(this, Ui.ic("dot_err") + " " + msg, 11, Ui.RED_D);
                warn.setBackground(Ui.round(Ui.RED_BG2, Ui.dp(this, 4)));
                warn.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
                wlp.topMargin = Ui.dp(this, 6);
                box.addView(warn, wlp);
            }
        }
        return box;
    }

    /* ================= 全局动作 ================= */

    private void bulkCheckin() {
        if (bulkBusy) { toast("任务进行中…"); return; }
        java.util.ArrayList<String[]> targets = new java.util.ArrayList<>(); // {siteKey, accKey, type}
        Store store = new Store(this);
        JSONArray sites = store.sites();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            JSONArray accs = s.optJSONArray("accounts");
            if (accs == null) continue;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject a = accs.optJSONObject(j);
                if (a == null || a.optString("token", "").isEmpty()) continue;
                if (Engine.isCheckedToday(a)) continue;
                targets.add(new String[]{ s.optString("key"), a.optString("key"), s.optString("checkinType", "login") });
            }
        }
        if (targets.isEmpty()) { toast("没有待签账号（未授权或今日已签）"); return; }
        if (page != 0) showPage(0);
        LogPopup.autoShow(this);
        bulkBusy = true;
        runBulkCheckin(targets, 0, new int[]{0, 0});
    }

    /** 串行执行，Tab 上显示进度 */
    private void runBulkCheckin(java.util.ArrayList<String[]> list, int idx, int[] stat) {
        if (idx >= list.size()) {
            bulkBusy = false;
            setTabAction(tabCheckIcon, tabCheckText, Ui.ic("check"), "完成", Ui.GREEN);
            h.postDelayed(() -> setTabAction(tabCheckIcon, tabCheckText,
                    Ui.ic("tab_checkin"), "一键签到", Ui.TXT2), 1200);
            render();
            toast("签到完成：成功 " + stat[0] + " · 失败 " + stat[1]);
            return;
        }
        setTabAction(tabCheckIcon, tabCheckText, Ui.ic("hourglass"),
                "签到 " + (idx + 1) + "/" + list.size(), Ui.GREEN);
        String[] t = list.get(idx);
        final String sk = t[0], ak = t[1], type = t[2];
        Store store = new Store(this);
        if ("manual".equals(type)) {
            OffscreenCheckin.run(this, sk, ak, 100, (ok, already, reward, msg) -> {
                store.opLog(sk, ak, "一键签到", ok ? "ok" : "err",
                        ok ? (already ? "今日已签" : ("签到成功 +$" + Ui.usd(reward))) : msg, "", "user");
                pushLog(store);
                if (ok) stat[0]++; else stat[1]++;
                runBulkCheckin(list, idx + 1, stat);
            });
        } else {
            new Thread(() -> {
                String lvl = "ok", sum;
                try {
                    JSONObject r = engine.checkin(ak);
                    boolean ok = r.optBoolean("ok");
                    lvl = ok ? "ok" : "err";
                    sum = r.optString("message", ok ? "完成" : "失败");
                    if (ok) stat[0]++; else stat[1]++;
                } catch (Exception e) { lvl = "err"; sum = String.valueOf(e.getMessage()); stat[1]++; }
                store.opLog(sk, ak, "一键签到", lvl, sum, "", "user");
                final String flvl = lvl;
                h.post(() -> { pushLog(store); runBulkCheckin(list, idx + 1, stat); });
            }).start();
        }
    }

    private void bulkRefresh() {
        if (bulkBusy) { toast("任务进行中…"); return; }
        java.util.ArrayList<String[]> targets = new java.util.ArrayList<>();
        JSONArray sites = new Store(this).sites();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            JSONArray accs = s.optJSONArray("accounts");
            if (accs == null) continue;
            for (int j = 0; j < accs.length(); j++) {
                JSONObject a = accs.optJSONObject(j);
                if (a == null || a.optString("token", "").isEmpty()) continue;
                targets.add(new String[]{ s.optString("key"), a.optString("key") });
            }
        }
        if (targets.isEmpty()) { toast("没有已授权账号"); return; }
        if (page != 0) showPage(0);
        LogPopup.autoShow(this);
        bulkBusy = true;
        new Thread(() -> {
            Store store = new Store(this);
            int ok = 0, err = 0;
            for (int i = 0; i < targets.size(); i++) {
                final int n = i + 1;
                h.post(() -> setTabAction(tabRefreshIcon, tabRefreshText, Ui.ic("hourglass"),
                        "刷新 " + n + "/" + targets.size(), Ui.BLUE));
                String[] t = targets.get(i);
                try {
                    JSONObject stt = engine.status(t[1]);
                    JSONObject patch = new JSONObject().put("lastStatus", stt);
                    if (stt.optBoolean("todayChecked", false)) {
                        patch.put("lastCheckin", new JSONObject()
                                .put("date", Engine.todayStr())
                                .put("reward", stt.optDouble("todayRewardUSD", 0))
                                .put("time", System.currentTimeMillis()));
                    }
                    store.patchAccount(t[1], patch);
                    if (stt.optBoolean("ok")) ok++; else err++;
                } catch (Exception e) {
                    err++;
                    store.opLog(t[0], t[1], "一键刷新", "err", "刷新失败", String.valueOf(e.getMessage()), "user");
                }
                h.post(() -> pushLog(store));
            }
            final int fok = ok, ferr = err;
            h.post(() -> {
                bulkBusy = false;
                setTabAction(tabRefreshIcon, tabRefreshText, Ui.ic("check"), "完成", Ui.GREEN);
                h.postDelayed(() -> setTabAction(tabRefreshIcon, tabRefreshText,
                        Ui.ic("tab_refresh"), "一键刷新", Ui.TXT2), 1200);
                render();
                toast("刷新完成：成功 " + fok + " · 失败 " + ferr);
            });
        }).start();
    }

    private void setTabAction(TextView icon, TextView text, String ic, String label, int color) {
        if (icon != null) { icon.setText(ic); icon.setTextColor(color); }
        if (text != null) { text.setText(label); text.setTextColor(color); }
    }

    /** 把最新一条 opLog 推给浮窗（浮窗未开则忽略） */
    private void pushLog(Store store) {
        if (!LogPopup.isShowing()) return;
        JSONArray a = store.opLogs();
        if (a.length() > 0) LogPopup.append(a.optJSONObject(a.length() - 1));
    }

    /* ================= 单账号操作 ================= */

    private void doCheckin(String key, TextView btn) {
        if (singleBusy) { toast("签到进行中…"); return; }
        Store store = new Store(this);
        JSONObject site = store.siteOfAccount(key);
        if (site == null) { toast("站点信息缺失"); return; }
        final String sk = site.optString("key", "");
        final String ct = site.optString("checkinType", "");
        if (sk.isEmpty()) { toast("站点信息缺失"); return; }
        singleBusy = true;
        if (btn != null) btn.setText("签到中…");
        LogPopup.autoShow(this);

        if (!"manual".equals(ct)) {
            new Thread(() -> {
                try {
                    JSONObject r = engine.checkin(key);
                    store.opLog(sk, key, "签到", r.optBoolean("ok") ? "ok" : "err",
                            r.optString("message", ""), "", "user");
                    h.post(() -> { singleBusy = false; pushLog(store); applyCheckinResult(r); render(); });
                } catch (Exception e) {
                    store.opLog(sk, key, "签到", "err", "签到失败", String.valueOf(e.getMessage()), "user");
                    h.post(() -> { singleBusy = false; pushLog(store); toast("签到失败: " + e.getMessage()); render(); });
                }
            }).start();
            return;
        }
        OffscreenCheckin.run(this, sk, key, 100, (ok, already, reward, msg) -> {
            singleBusy = false;
            store.opLog(sk, key, "签到", ok ? "ok" : "err",
                    ok ? (already ? "今日已签" : ("签到成功 +$" + Ui.usd(reward))) : msg,
                    ok ? "" : "后台离屏 WebView", "user");
            pushLog(store);
            if (ok) {
                JSONObject r = new JSONObject();
                try { r.put("ok", true).put("already", already).put("reward", reward)
                        .put("message", msg == null ? "" : msg); } catch (Exception ignored) {}
                applyCheckinResult(r);
                render();
                return;
            }
            final String m = msg == null ? "" : msg;
            boolean envFail = m.startsWith("net::") || m.contains("超时") || m.contains("HTTP")
                    || m.contains("加载失败") || m.contains("注入失败") || m.contains("人机验证未通过");
            if (!envFail) { toast(m.isEmpty() ? "签到失败" : m); render(); return; }
            Intent it = new Intent(this, CheckinActivity.class);
            it.putExtra("siteKey", sk);
            it.putExtra("accountKey", key);
            startActivityForResult(it, REQ_CHECKIN);
        });
    }

    private void applyCheckinResult(JSONObject r) {
        double reward = r.optDouble("reward", 0);
        if (r.optBoolean("already")) {
            String m = r.optString("message", "今日已签到");
            if (reward > 0) m += " · 今日奖励 $" + Ui.usd(reward);
            toast(m);
        } else if (r.optBoolean("ok")) {
            toast(reward > 0 ? ("签到成功 本次奖励 +$" + Ui.usd(reward)) : r.optString("message", "签到成功"));
        } else if (r.optBoolean("auth")) {
            toast("授权已过期，请点「重新授权」");
        } else {
            toast(r.optString("message", "签到失败"));
        }
    }

    private void refreshSite(JSONObject site) {
        JSONArray accs = site.optJSONArray("accounts");
        if (accs == null || accs.length() == 0) { toast("该站点没有账号"); return; }
        LogPopup.autoShow(this);
        toast("正在刷新 " + accs.length() + " 个账号…");
        for (int i = 0; i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a != null) refreshOne(a.optString("key"));
        }
    }

    private void refreshOne(String key) {
        new Thread(() -> {
            Store store = new Store(this);
            try {
                JSONObject st = engine.status(key);
                JSONObject patch = new JSONObject().put("lastStatus", st);
                if (st.optBoolean("todayChecked", false)) {
                    patch.put("lastCheckin", new JSONObject()
                            .put("date", Engine.todayStr())
                            .put("reward", st.optDouble("todayRewardUSD", 0))
                            .put("time", System.currentTimeMillis()));
                }
                store.patchAccount(key, patch);
                h.post(() -> { pushLog(store); render(); });
            } catch (Exception e) {
                store.opLog(store.siteKeyOfAccount(key), key, "刷新", "err",
                        "刷新失败", String.valueOf(e.getMessage()), "user");
                h.post(() -> { pushLog(store); toast("刷新失败: " + e.getMessage()); });
            }
        }).start();
    }

    /* ================= 菜单 ================= */

    private void accountMenu(JSONObject site, JSONObject acc) {
        final String key = acc.optString("key");
        String[] items = { "刷新该账号", "重新授权", "查看日志", "绑定凭据", "删除账号" };
        new AlertDialog.Builder(this)
                .setTitle(acc.optString("alias", key))
                .setItems(items, (d, w) -> {
                    if (w == 0) { LogPopup.autoShow(this); refreshOne(key); }
                    else if (w == 1) startAuth(site, acc);
                    else if (w == 2) LogPopup.show(this);
                    else if (w == 3) pickCredential(site, acc);
                    else confirmRemoveAccount(acc);
                }).show();
    }

    private void siteMenu(JSONObject site) {
        String[] items = { "刷新本站全部账号", "站点管理（编辑/删除）" };
        new AlertDialog.Builder(this)
                .setTitle(site.optString("name", site.optString("key")))
                .setItems(items, (d, w) -> {
                    if (w == 0) refreshSite(site);
                    else { showPage(1); settings.openSites(); }
                }).show();
    }

    private void confirmRemoveSite(JSONObject site) {
        new AlertDialog.Builder(this)
                .setTitle("删除站点")
                .setMessage("删除「" + site.optString("name") + "」及其下所有账号？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    Store store = new Store(this);
                    store.removeSite(site.optString("key"));
                    store.opLog(site.optString("key"), "", "删除站点", "ok",
                            "已删除 " + site.optString("name"), "", "user");
                    render();
                })
                .setNegativeButton("取消", (d, w) -> render()).show();
    }

    private void confirmRemoveAccount(JSONObject acc) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("删除「" + acc.optString("alias", acc.optString("key")) + "」？")
                .setPositiveButton("删除", (d, w) -> {
                    new Store(this).removeAccount(acc.optString("key"));
                    render();
                })
                .setNegativeButton("取消", null).show();
    }

    /* ================= 账号 / 凭据 ================= */

    private void promptAddAccount(JSONObject site) {
        Store store = new Store(this);
        JSONArray creds = store.credentials();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < creds.length(); i++) {
            JSONObject c = creds.optJSONObject(i);
            if (c == null) continue;
            String gh = c.optString("githubUser", "");
            String sa = c.optString("siteAccount", "");
            String who = !gh.isEmpty() ? gh : sa;
            labels.add(Ui.ic("user") + " " + c.optString("alias", who)
                    + (who.isEmpty() ? "" : ("  (" + who + ")"))
                    + (store.credHasTwofa(c.optString("id")) ? "  · 2FA" : ""));
            ids.add(c.optString("id"));
        }
        labels.add(Ui.ic("plus") + " 录入新账号…");
        new AlertDialog.Builder(this)
                .setTitle("为「" + site.optString("name") + "」添加账号")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    if (w == labels.size() - 1) { showPage(1); settings.openCredentials(); return; }
                    createAccountFromCredential(site, ids.get(w));
                })
                .setNegativeButton("取消", null).show();
    }

    private void createAccountFromCredential(JSONObject site, String credId) {
        Store store = new Store(this);
        JSONObject c = store.findCredential(credId);
        if (c == null) { toast("凭据不存在"); return; }
        String gh = c.optString("githubUser", "");
        String alias = c.optString("alias", gh.isEmpty() ? c.optString("siteAccount", "账号") : gh);
        String siteKey = site.optString("key");
        /* 同凭据已在本站存在则直接重新授权 */
        JSONArray accs = site.optJSONArray("accounts");
        if (accs != null) for (int i = 0; i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a != null && credId.equals(a.optString("credentialId", ""))) {
                startAuth(site, a);
                return;
            }
        }
        try {
            JSONObject acc = new JSONObject()
                    .put("key", "acc_" + System.currentTimeMillis())
                    .put("alias", alias)
                    .put("siteKey", siteKey)
                    .put("credentialId", credId);
            if (!gh.isEmpty()) acc.put("githubAccount", gh);
            store.upsertAccount(siteKey, acc);
            store.opLog(siteKey, acc.optString("key"), "添加账号", "ok", "已绑定凭据 " + alias, "", "user");
            render();
            JSONObject created = store.findAccount(acc.optString("key"));
            if (created != null) startAuth(site, created);
        } catch (Exception ignored) {}
    }

    private void pickCredential(JSONObject site, JSONObject acc) {
        Store store = new Store(this);
        JSONArray creds = store.credentials();
        if (creds.length() == 0) { toast("凭据库为空，请先在设置里录入"); showPage(1); settings.openCredentials(); return; }
        String[] labels = new String[creds.length()];
        String[] ids = new String[creds.length()];
        for (int i = 0; i < creds.length(); i++) {
            JSONObject c = creds.optJSONObject(i);
            String gh = c == null ? "" : c.optString("githubUser", "");
            labels[i] = (c == null ? "" : c.optString("alias", gh)) + (gh.isEmpty() ? "" : ("  (" + gh + ")"));
            ids[i] = c == null ? "" : c.optString("id");
        }
        new AlertDialog.Builder(this).setTitle("绑定凭据")
                .setItems(labels, (d, w) -> {
                    try {
                        store.patchAccount(acc.optString("key"),
                                new JSONObject().put("credentialId", ids[w]));
                        toast("已绑定");
                        render();
                    } catch (Exception ignored) {}
                }).setNegativeButton("取消", null).show();
    }

    private void startAuth(JSONObject site, JSONObject acc) {
        Intent it = new Intent(this, AuthActivity.class);
        it.putExtra("siteKey", site.optString("key"));
        it.putExtra("accountKey", acc.optString("key"));
        it.putExtra("alias", acc.optString("alias"));
        it.putExtra("credentialId", acc.optString("credentialId", ""));
        startActivityForResult(it, REQ_AUTH);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Store store = new Store(this);
        if (req == REQ_CHECKIN && res == RESULT_OK && data != null) {
            JSONObject r = new JSONObject();
            try {
                r.put("ok", data.getBooleanExtra("ok", false))
                 .put("already", data.getBooleanExtra("already", false))
                 .put("reward", data.getDoubleExtra("reward", 0))
                 .put("auth", data.getBooleanExtra("auth", false))
                 .put("message", data.getStringExtra("message") == null ? "" : data.getStringExtra("message"));
            } catch (Exception ignored) {}
            store.opLog("", "", "可见兜底签到", r.optBoolean("ok") ? "ok" : "err",
                    r.optString("message", ""), "", "user");
            pushLog(store);
            applyCheckinResult(r);
            render();
        }
        if (req == REQ_AUTH) {
            if (res == RESULT_OK) {
                String user = data == null ? "" : data.getStringExtra("user");
                store.opLog("", "", "授权", "ok", "授权成功" + (user == null || user.isEmpty() ? "" : (" · " + user)), "", "user");
                toast("授权成功");
            } else {
                String err = data == null ? "" : data.getStringExtra("error");
                store.opLog("", "", "授权", "err", err == null || err.isEmpty() ? "授权未完成" : err, "", "user");
                if (err != null && !err.isEmpty()) toast(err);
            }
            pushLog(store);
            render();
        }
    }

    /* ================= 工具 ================= */

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void goToBoard() { showPage(0); }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }
}