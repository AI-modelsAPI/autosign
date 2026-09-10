package icu.justwoker.justsign;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SettingsView（v0.2.0）— 设置页 + 两个子页（站点管理 / 账号凭据库）。
 *
 * 结构（内部三页切换，返回键由 MainActivity.onBack 转发）：
 *   PAGE_ROOT  设置主页：资产与服务 / 网络与自动化 / 界面 / 关于
 *   PAGE_SITES 站点管理：内置与自定义站点完全平权，均可增删改；顶部一条批量导入横幅
 *   PAGE_CREDS 账号凭据库：站点账号 + 密码 + 2FA（均加密存储）+ GitHub 用户名
 */
public class SettingsView extends FrameLayout {

    private static final int PAGE_ROOT = 0, PAGE_SITES = 1, PAGE_CREDS = 2;

    private final MainActivity act;
    private final LinearLayout rootPage, sitesPage, credsPage;
    private final LinearLayout rootBody, sitesBody, credsBody;
    private int page = PAGE_ROOT;

    public SettingsView(MainActivity a) {
        super(a);
        this.act = a;
        setBackgroundColor(Ui.BG);

        rootPage = Ui.col(a);
        rootBody = Ui.col(a);
        rootBody.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 24));
        rootPage.addView(bar("设置与管理", false));
        rootPage.addView(new ScrollView(a) {{ addView(rootBody); }},
                new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(rootPage, new LayoutParams(-1, -1));

        sitesPage = Ui.col(a);
        sitesBody = Ui.col(a);
        sitesBody.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 24));
        sitesPage.addView(barSites());
        sitesPage.addView(new ScrollView(a) {{ addView(sitesBody); }},
                new LinearLayout.LayoutParams(-1, 0, 1f));
        sitesPage.setVisibility(GONE);
        addView(sitesPage, new LayoutParams(-1, -1));

        credsPage = Ui.col(a);
        credsBody = Ui.col(a);
        credsBody.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 24));
        credsPage.addView(barCreds());
        credsPage.addView(new ScrollView(a) {{ addView(credsBody); }},
                new LinearLayout.LayoutParams(-1, 0, 1f));
        credsPage.setVisibility(GONE);
        addView(credsPage, new LayoutParams(-1, -1));
    }

    /* ================= 顶栏 ================= */

    private View bar(String title, boolean back) {
        LinearLayout bar = Ui.row(act);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 16), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(act, 52)));
        if (back) {
            View b = Ui.iconBtn(act, "back", 19, Ui.TXT2, 6);
            b.setOnClickListener(v -> show(PAGE_ROOT));
            bar.addView(b);
            bar.addView(Ui.gapW(act, 4));
        }
        bar.addView(Ui.tv(act, title, 16, Ui.TXT, true));
        bar.addView(Ui.spring(act));
        return bar;
    }

    private View barSites() {
        LinearLayout bar = (LinearLayout) bar("站点管理", true);
        TextView add = Ui.iconBtnText(act, "plus", "手动添加", 12, Ui.BLUE, Ui.BLUE_BG, 10, 6);
        add.setOnClickListener(v -> editSiteDialog(null));
        bar.addView(add);
        return bar;
    }

    private View barCreds() {
        LinearLayout bar = (LinearLayout) bar("账号凭据库", true);
        TextView add = Ui.iconBtnText(act, "plus", "录入账号", 12, Ui.BLUE, Ui.BLUE_BG, 10, 6);
        add.setOnClickListener(v -> editCredDialog(null));
        bar.addView(add);
        return bar;
    }

    /* ================= 页面切换 ================= */

    private void show(int p) {
        page = p;
        rootPage.setVisibility(p == PAGE_ROOT ? VISIBLE : GONE);
        sitesPage.setVisibility(p == PAGE_SITES ? VISIBLE : GONE);
        credsPage.setVisibility(p == PAGE_CREDS ? VISIBLE : GONE);
        refresh();
    }

    public void openSites() { show(PAGE_SITES); }
    public void openCredentials() { show(PAGE_CREDS); }

    /** 返回键：在子页则回主页并返回 true（拦截）；已在主页返回 false */
    public boolean onBack() {
        if (page != PAGE_ROOT) { show(PAGE_ROOT); return true; }
        return false;
    }

    public void refresh() {
        UpdateChecker.setListener(() -> post(this::refresh));
        if (page == PAGE_ROOT) buildRoot();
        else if (page == PAGE_SITES) buildSites();
        else buildCreds();
    }

    /* ================= 设置主页 ================= */

    private void buildRoot() {
        rootBody.removeAllViews();
        Store store = new Store(act);

        /* --- 资产与服务 --- */
        LinearLayout g1 = group("资产与服务");
        int credN = store.credentials().length();
        g1.addView(item("user", "账号凭据库",
                credN == 0 ? "未录入任何账号" : ("已录入 " + credN + " 个账号"),
                null, v -> show(PAGE_CREDS)));
        g1.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        int siteN = store.sites().length();
        g1.addView(item("server", "站点管理",
                siteN == 0 ? "未添加站点" : ("已配置 " + siteN + " 个站点"),
                null, v -> show(PAGE_SITES)));
        rootBody.addView(g1);

        /* --- 网络与自动化 --- */
        LinearLayout g2 = group("网络与自动化");
        JSONObject px = store.config().optJSONObject("proxy");
        boolean pxOn = px != null && px.optBoolean("enabled");
        String pxSub = pxOn ? (px.optString("host", "127.0.0.1") + ":" + px.optInt("port", 10808))
                : "未启用（直连）";
        LinearLayout proxyRow = item("plug", "SOCKS5 代理", pxSub, null, v -> proxyDialog());
        TextView autoBtn = Ui.iconBtnText(act, "bolt", "自动检测", 11, Ui.BLUE, Ui.BLUE_BG, 8, 4);
        autoBtn.setOnClickListener(v -> autoDetectProxy());
        /* 插到 chevron 之前 */
        proxyRow.addView(autoBtn, proxyRow.getChildCount() - 1);
        proxyRow.addView(Ui.gapW(act, 6), proxyRow.getChildCount() - 1);
        g2.addView(proxyRow);
        g2.addView(Ui.divider(act, Ui.LINE_SOFT, 16));

        JSONObject sch = store.schedule();
        g2.addView(scheduleBlock(sch));
        rootBody.addView(g2);

        /* --- 界面与日志 --- */
        LinearLayout g3 = group("界面与日志");
        g3.addView(switchItem("log", "日志自动弹出",
                "执行签到/刷新时自动打开日志浮窗",
                store.uiPref("logAutoPopup", true),
                on -> new Store(act).setUiPref("logAutoPopup", on)));
        g3.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        g3.addView(switchItem("user", "授权时自动点登录",
                "填好账号密码/2FA 后自动提交；人机验证未过时只填不点",
                store.uiPref("autoSubmitLogin", true),
                on -> new Store(act).setUiPref("autoSubmitLogin", on)));
        g3.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        g3.addView(switchItem("gift", "邀请额度自动划转",
                "刷新时自动把邀请奖励转入可用余额（站点最小划转 $1）",
                store.uiPref("autoAffTransfer", false),
                on -> new Store(act).setUiPref("autoAffTransfer", on)));
        g3.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        g3.addView(item("trash", "清空操作日志",
                "当前 " + store.opLogs().length() + " 条", null, v ->
                new AlertDialog.Builder(act).setTitle("清空日志")
                        .setMessage("清空全部操作日志？")
                        .setPositiveButton("清空", (d, w) -> {
                            new Store(act).clearOpLogs();
                            act.toast("已清空");
                            buildRoot();
                        }).setNegativeButton("取消", null).show()));
        rootBody.addView(g3);

        /* --- 关于 --- */
        LinearLayout g4 = group("关于");
        String ver = "v0.2.0";
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0);
            ver = "v" + pi.versionName + " (build " + pi.versionCode + ")";
        } catch (Exception ignored) {}
        g4.addView(item("info", "版本", null, ver, null));
        if (UpdateChecker.hasUpdate()) {
            g4.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
            g4.addView(item("download", UpdateChecker.updateLabel(),
                    "点击自动下载，安全校验后打开系统安装器",
                    null, v -> UpdateChecker.download(act)));
        }
        g4.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        g4.addView(item("refresh", "检查新版本",
                "无新版本时不会显示下载按钮",
                null, v -> UpdateChecker.manual(act)));
        g4.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        /* 开源主页入口暂时隐藏（未开源阶段），开源后恢复：
           item("pkg", ...) → ACTION_VIEW 仓库地址。 */
        g4.addView(item("penguin", "QQ 交流群", "1060200469", null, v -> {
            /* QQ 9.x 实测：mqqopensdkapi://biz/qr/add 已失效（只拉起 Splash 无反应），
             * mqq://card/show_pslcard?card_type=group 可打开群资料页直接加群。 */
            try {
                act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("mqq://card/show_pslcard?src_type=internal&version=1&uin=1060200469&card_type=group")));
            } catch (Exception e) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setText("1060200469");
                    act.toast("群号已复制，请到 QQ 搜索加入");
                } catch (Exception e2) { act.toast("无法打开，群号 1060200469"); }
            }
        }));
        g4.addView(Ui.divider(act, Ui.LINE_SOFT, 16));
        g4.addView(item("shield", "凭据加密",
                Crypto.available() ? "Android Keystore 可用（AES-256-GCM）" : "不可用：本机无法安全保存密码",
                null, null));
        rootBody.addView(g4);
    }

    /* ---------- 定时签到区块 ---------- */

    private View scheduleBlock(JSONObject sch) {
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 12));

        LinearLayout head = Ui.row(act);
        LinearLayout left = Ui.col(act);
        left.addView(Ui.iconText(act, "clock", "定时自动签到", 14, Ui.TXT2, true));
        left.addView(Ui.tv(act, "后台按周期自动执行全站签到", 11, Ui.SUB2));
        head.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(act);
        sw.setChecked(sch.optBoolean("enabled", true));
        head.addView(sw);
        box.addView(head);

        LinearLayout cfg = Ui.col(act);
        cfg.setVisibility(sch.optBoolean("enabled", true) ? VISIBLE : GONE);

        /* 周期分段 */
        LinearLayout pRow = Ui.row(act);
        pRow.addView(Ui.tv(act, "执行周期", 12, Ui.SUB));
        pRow.addView(Ui.spring(act));
        LinearLayout seg = Ui.row(act);
        seg.setBackground(Ui.round(Ui.LINE_SOFT, Ui.dp(act, 6)));
        seg.setPadding(Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2));
        String mode = sch.optString("mode", "daily");
        String[] modes = { "daily", "weekday", "interval" };
        String[] names = { "每天", "工作日", "间隔" };
        for (int i = 0; i < 3; i++) {
            final String m = modes[i];
            boolean on = m.equals(mode);
            TextView t = Ui.tv(act, names[i], 11, on ? Ui.BLUE : Ui.SUB, on);
            if (on) t.setBackground(Ui.round(Ui.CARD, Ui.dp(act, 4)));
            t.setPadding(Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4));
            t.setClickable(true);
            t.setOnClickListener(v -> saveSchedule("mode", m));
            seg.addView(t);
        }
        pRow.addView(seg);
        cfg.addView(pRow, lpTop(0));

        /* 指定时间（daily / weekday） */
        if (!"interval".equals(mode)) {
            LinearLayout tRow = Ui.row(act);
            tRow.addView(Ui.tv(act, "指定执行时间", 12, Ui.SUB));
            tRow.addView(Ui.spring(act));
            final int hh = sch.optInt("hour", 8), mm = sch.optInt("minute", 30);
            TextView pick = Ui.btn(act, String.format(java.util.Locale.US, "%02d:%02d", hh, mm),
                    13, Ui.BLUE_DEEP, Ui.BLUE_BG, 12, 4);
            pick.setOnClickListener(v -> new TimePickerDialog(act, (tp, h2, m2) -> {
                JSONObject s = new Store(act).schedule();
                try { s.put("hour", h2).put("minute", m2); } catch (Exception ignored) {}
                new Store(act).saveSchedule(s);
                Engine.schedule(act);
                buildRoot();
            }, hh, mm, true).show());
            tRow.addView(pick);
            cfg.addView(tRow, lpTop(10));
        } else {
            LinearLayout iRow = Ui.row(act);
            iRow.addView(Ui.tv(act, "间隔小时数", 12, Ui.SUB));
            iRow.addView(Ui.spring(act));
            final EditText e = Ui.input(act, "6");
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setText(String.valueOf(sch.optInt("intervalHours", 12)));
            e.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(Ui.dp(act, 60), -2);
            iRow.addView(e, elp);
            TextView save = Ui.btn(act, "保存", 11, Ui.BLUE, Ui.BLUE_BG, 10, 4);
            save.setOnClickListener(v -> {
                try {
                    int hv = Integer.parseInt(e.getText().toString().trim());
                    if (hv < 1 || hv > 24) { act.toast("请输入 1~24"); return; }
                    JSONObject s = new Store(act).schedule();
                    s.put("intervalHours", hv);
                    new Store(act).saveSchedule(s);
                    Engine.schedule(act);
                    act.toast("已保存：每 " + hv + " 小时");
                    buildRoot();
                } catch (Exception ex) { act.toast("请输入数字"); }
            });
            iRow.addView(Ui.gapW(act, 6));
            iRow.addView(save);
            cfg.addView(iRow, lpTop(10));
        }
        box.addView(cfg, lpTop(10));

        sw.setOnCheckedChangeListener((b, on) -> {
            saveSchedule("enabled", on);
        });
        return box;
    }

    private void saveSchedule(String key, Object val) {
        try {
            Store store = new Store(act);
            JSONObject s = store.schedule();
            s.put(key, val);
            store.saveSchedule(s);
            Engine.schedule(act);
            store.opLog("", "", "定时设置", "ok", "已更新（" + key + "=" + val + "）", "", "user");
        } catch (Exception ignored) {}
        buildRoot();
    }

    /* ---------- 代理 ---------- */

    private void proxyDialog() {
        Store store = new Store(act);
        JSONObject cfg = store.config();
        JSONObject px = cfg.optJSONObject("proxy");
        if (px == null) px = new JSONObject();

        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 10), Ui.dp(act, 20), 0);
        EditText[] hOut = new EditText[1], pOut = new EditText[1];
        box.addView(Ui.field(act, "地址（留空 = 直连，不走代理）", "127.0.0.1", hOut));
        box.addView(Ui.gapH(act, 10));
        box.addView(Ui.field(act, "端口", "10808", pOut));
        pOut[0].setInputType(InputType.TYPE_CLASS_NUMBER);
        if (px.optBoolean("enabled")) {
            hOut[0].setText(px.optString("host", ""));
            pOut[0].setText(String.valueOf(px.optInt("port", 10808)));
        }
        final JSONObject fpx = px;
        new AlertDialog.Builder(act).setTitle("SOCKS5 代理")
                .setView(Ui.scroll(act, box))
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        String hh = hOut[0].getText().toString().trim();
                        String pp = pOut[0].getText().toString().trim();
                        if (hh.isEmpty() || pp.isEmpty()) fpx.put("enabled", false);
                        else fpx.put("enabled", true).put("host", hh).put("port", Integer.parseInt(pp));
                        JSONObject c2 = store.config();
                        c2.put("proxy", fpx);
                        store.saveConfig(c2);
                        store.opLog("", "", "代理设置", "ok",
                                fpx.optBoolean("enabled")
                                        ? ("已启用 " + fpx.optString("host") + ":" + fpx.optInt("port"))
                                        : "已关闭（直连）", "", "user");
                        act.toast("已保存");
                        buildRoot();
                    } catch (Exception e) { act.toast("端口格式错误"); }
                })
                .setNeutralButton("自动检测", (d, w) -> autoDetectProxy())
                .setNegativeButton("取消", null).show();
    }

    private void autoDetectProxy() {
        act.toast("正在扫描本机代理…");
        new Thread(() -> {
            java.util.ArrayList<JSONObject> found = ProxyDetect.scan(act);
            act.runOnUiThread(() -> {
                if (found.isEmpty()) {
                    new Store(act).opLog("", "", "代理检测", "err", "未发现可用代理",
                            "已扫描常用端口与系统设置", "user");
                    new AlertDialog.Builder(act).setTitle("未发现代理")
                            .setMessage("已扫描常用端口（10808/10809/7890/7891/1080/8889/2080/8080/6153）"
                                    + "与系统代理设置，均无可用项。\n\n请确认代理软件已启动。")
                            .setPositiveButton("知道了", null).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    JSONObject f = found.get(i);
                    labels[i] = f.optString("host") + ":" + f.optInt("port")
                            + "   " + f.optString("note", f.optString("source", ""))
                            + (f.optBoolean("alive", true) ? "  ✓" : "  ✕");
                }
                new AlertDialog.Builder(act).setTitle("发现 " + found.size() + " 个候选")
                        .setItems(labels, (d, w) -> {
                            JSONObject f = found.get(w);
                            try {
                                Store store = new Store(act);
                                JSONObject cfg = store.config();
                                JSONObject px = cfg.optJSONObject("proxy");
                                if (px == null) px = new JSONObject();
                                px.put("enabled", true).put("type", "socks5")
                                  .put("host", f.optString("host")).put("port", f.optInt("port"));
                                cfg.put("proxy", px);
                                store.saveConfig(cfg);
                                store.opLog("", "", "代理检测", "ok",
                                        "已应用 " + f.optString("host") + ":" + f.optInt("port"),
                                        f.optString("source", ""), "user");
                                act.toast("已应用代理");
                                buildRoot();
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("取消", null).show();
            });
        }, "proxy-scan").start();
    }

    /* ================= 站点管理 ================= */

    private void buildSites() {
        sitesBody.removeAllViews();
        Store store = new Store(act);
        JSONArray sites = store.sites();

        /* 批量导入横幅（内置清单 = 普通站点，导入后完全平权） */
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.optJSONObject(i);
            if (s != null) have.add(s.optString("key"));
        }
        java.util.ArrayList<JSONObject> pool = Catalog.all();
        int missing = 0;
        for (JSONObject c : pool) if (!have.contains(c.optString("key"))) missing++;
        if (missing > 0) {
            LinearLayout banner = Ui.row(act);
            banner.setBackground(Ui.round(Ui.BLUE_BG, Ui.dp(act, 8)));
            banner.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            banner.addView(Ui.tv(act, "可一键导入 " + missing + " 个常用公益中转站", 12, Ui.BLUE_DEEP),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            TextView imp = Ui.iconBtnText(act, "import", "批量导入", 11, Ui.white(), Ui.BLUE, 10, 5);
            imp.setOnClickListener(v -> batchImportDialog(pool, have));
            banner.addView(imp);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.bottomMargin = Ui.dp(act, 12);
            sitesBody.addView(banner, blp);
        }

        if (sites.length() == 0) {
            sitesBody.addView(Ui.tv(act, "还没有站点。点右上角「＋手动添加」，或用上方批量导入。", 12, Ui.SUB2));
            return;
        }
        for (int i = 0; i < sites.length(); i++) {
            final JSONObject s = sites.optJSONObject(i);
            if (s == null) continue;
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(act, 8), Math.max(1, Ui.dp(act, 1)), Ui.LINE));
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10));
            LinearLayout info = Ui.col(act);
            info.addView(Ui.tv(act, s.optString("name", s.optString("key")), 14, Ui.TXT, true));
            JSONArray accs = s.optJSONArray("accounts");
            int an = accs == null ? 0 : accs.length();
            info.addView(Ui.tv(act, s.optString("baseUrl", "") + "  ·  "
                    + Engine.kindLabel(s)
                    + "  ·  " + an + " 个账号", 11, Ui.SUB));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout order = Ui.col(act);
            TextView up = Ui.btn(act, "↑", 15, i == 0 ? Ui.SUB2 : Ui.BLUE, Ui.BLUE_BG, 9, 4);
            TextView down = Ui.btn(act, "↓", 15, i == sites.length() - 1 ? Ui.SUB2 : Ui.BLUE, Ui.BLUE_BG, 9, 4);
            TextView handle = Ui.btn(act, "≡ 拖动", 11, Ui.TXT2, Ui.CARD_SUB, 8, 5);
            final int pos = i;
            up.setEnabled(pos > 0);
            down.setEnabled(pos < sites.length() - 1);
            up.setOnClickListener(v -> {
                new Store(act).moveSite(s.optString("key"), -1);
                buildSites();
                act.render();
            });
            down.setOnClickListener(v -> {
                new Store(act).moveSite(s.optString("key"), 1);
                buildSites();
                act.render();
            });
            handle.setOnLongClickListener(v -> {
                android.content.ClipData data = android.content.ClipData.newPlainText("siteKey", s.optString("key"));
                v.startDragAndDrop(data, new View.DragShadowBuilder(row), s.optString("key"), 0);
                row.animate().alpha(0.45f).scaleX(0.98f).scaleY(0.98f).setDuration(120).start();
                return true;
            });
            row.setOnDragListener((v, event) -> {
                if (event.getAction() == android.view.DragEvent.ACTION_DRAG_ENTERED) {
                    row.animate().translationY(-Ui.dp(act, 6)).scaleX(1.02f).scaleY(1.02f).setDuration(100).start();
                    row.setBackground(Ui.roundStroke(Ui.BLUE_BG, Ui.dp(act, 10), Ui.dp(act, 2), Ui.BLUE));
                    return true;
                }
                if (event.getAction() == android.view.DragEvent.ACTION_DRAG_EXITED) {
                    row.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(100).start();
                    row.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(act, 8), Math.max(1, Ui.dp(act, 1)), Ui.LINE));
                    return true;
                }
                if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                    Object state = event.getLocalState();
                    row.animate().translationY(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(100).start();
                    row.setBackground(Ui.roundStroke(Ui.GREEN_BG2, Ui.dp(act, 10), Ui.dp(act, 2), Ui.GREEN));
                    if (state != null) {
                        new Store(act).moveSiteTo(String.valueOf(state), pos);
                        row.postDelayed(() -> {
                            buildSites();
                            act.render();
                        }, 180);
                    }
                    return true;
                }
                if (event.getAction() == android.view.DragEvent.ACTION_DRAG_ENDED) {
                    row.animate().translationY(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
                    row.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(act, 8), Math.max(1, Ui.dp(act, 1)), Ui.LINE));
                    return true;
                }
                return event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED
                        || event.getAction() == android.view.DragEvent.ACTION_DRAG_LOCATION;
            });
            order.addView(handle);
            order.addView(up);
            order.addView(down);
            row.addView(order);
            View edit = Ui.iconBtn(act, "edit", 16, Ui.SUB, 7);
            edit.setOnClickListener(v -> editSiteDialog(s));
            View del = Ui.iconBtn(act, "trash", 16, Ui.RED, 7);
            del.setOnClickListener(v -> new AlertDialog.Builder(act)
                    .setTitle("删除站点")
                    .setMessage("删除「" + s.optString("name") + "」及其下 " + an + " 个账号？不可恢复。")
                    .setPositiveButton("删除", (d, w) -> {
                        DeleteCoordinator.deleteSite(act, s.optString("key"), result -> {
                            act.toast(result.message());
                            buildSites();
                            act.render();
                        });
                    }).setNegativeButton("取消", null).show());
            row.addView(edit);
            row.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(act, 8);
            sitesBody.addView(row, lp);
        }
    }

    private void batchImportDialog(java.util.ArrayList<JSONObject> pool, java.util.HashSet<String> have) {
        java.util.ArrayList<JSONObject> cand = new java.util.ArrayList<>();
        for (JSONObject c : pool) if (!have.contains(c.optString("key"))) cand.add(c);
        String[] labels = new String[cand.size()];
        boolean[] checked = new boolean[cand.size()];
        for (int i = 0; i < cand.size(); i++) {
            JSONObject c = cand.get(i);
            labels[i] = c.optString("name") + "  ("
                    + Engine.kindLabelOf(c.optString("checkinType")) + ")";
            checked[i] = true;
        }
        new AlertDialog.Builder(act).setTitle("批量导入站点")
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("导入", (d, w) -> {
                    Store store = new Store(act);
                    int n = 0;
                    for (int i = 0; i < cand.size(); i++) {
                        if (!checked[i]) continue;
                        JSONObject c = cand.get(i);
                        try {
                            store.upsertSite(new JSONObject()
                                    .put("key", c.optString("key"))
                                    .put("name", c.optString("name"))
                                    .put("baseUrl", c.optString("homeUrl"))
                                    .put("homeUrl", c.optString("homeUrl"))
                                    .put("affUrl", c.optString("affUrl", ""))
                                    .put("checkinType", c.optString("checkinType", "login"))
                                    .put("reward", c.optString("reward", ""))
                                    .put("note", c.optString("note", ""))
                                    .put("accounts", new JSONArray()));
                            n++;
                        } catch (Exception ignored) {}
                    }
                    store.opLog("", "", "批量导入站点", "ok", "已导入 " + n + " 个站点", "", "user");
                    act.toast("已导入 " + n + " 个站点");
                    buildSites();
                })
                .setNegativeButton("取消", null).show();
    }

    /** site==null 表示新增 */
    private void editSiteDialog(JSONObject site) {
        boolean isNew = site == null;
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 10), Ui.dp(act, 20), 0);
        EditText[] nOut = new EditText[1], uOut = new EditText[1];
        box.addView(Ui.field(act, "站点名称", "如 JustDoWork", nOut));
        box.addView(Ui.gapH(act, 10));
        box.addView(Ui.field(act, "API 地址（Base URL）", "https://api.example.com", uOut));
        uOut[0].setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(Ui.gapH(act, 10));

        /* 签到方式单选（三种形态，与 Engine.siteKind 对齐） */
        box.addView(Ui.tv(act, "签到方式", 11, Ui.SUB, true));
        final String[] type = { isNew ? "newapi" : Engine.siteKind(site) };
        LinearLayout seg = Ui.col(act);
        seg.setBackground(Ui.round(Ui.LINE_SOFT, Ui.dp(act, 6)));
        seg.setPadding(Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2));
        final TextView[] segBtns = new TextView[3];
        String[] tKeys = { "newapi", "login", "web" };
        String[] tNames = { "每日签到（有 /api/user/checkin 接口）",
                            "登录即得（无签到接口，只保活刷额度）",
                            "网页手动（接口不通，点签到打开网页）" };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.tv(act, tNames[i], 11, Ui.SUB, false);
            t.setPadding(Ui.dp(act, 10), Ui.dp(act, 7), Ui.dp(act, 10), Ui.dp(act, 7));
            t.setClickable(true);
            t.setOnClickListener(v -> {
                type[0] = tKeys[idx];
                for (int k = 0; k < 3; k++) {
                    boolean on = k == idx;
                    segBtns[k].setTextColor(on ? Ui.BLUE : Ui.SUB);
                    segBtns[k].setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD
                            : android.graphics.Typeface.DEFAULT);
                    segBtns[k].setBackground(on ? Ui.round(Ui.CARD, Ui.dp(act, 4)) : null);
                }
            });
            segBtns[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(act, 5);
        box.addView(seg, slp);

        if (!isNew) {
            nOut[0].setText(site.optString("name"));
            uOut[0].setText(site.optString("baseUrl"));
        }
        /* 初始化选中态 */
        int initIdx = "login".equals(type[0]) ? 1 : ("web".equals(type[0]) ? 2 : 0);
        segBtns[initIdx].setTextColor(Ui.BLUE);
        segBtns[initIdx].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        segBtns[initIdx].setBackground(Ui.round(Ui.CARD, Ui.dp(act, 4)));

        new AlertDialog.Builder(act).setTitle(isNew ? "添加站点" : "编辑站点")
                .setView(Ui.scroll(act, box))
                .setPositiveButton("保存", (d, w) -> {
                    String n = nOut[0].getText().toString().trim();
                    String u = uOut[0].getText().toString().trim().replaceAll("/+$", "");
                    if (u.isEmpty() || !u.startsWith("http")) { act.toast("API 地址无效（需以 http 开头）"); return; }
                    Store store = new Store(act);
                    try {
                        JSONObject s = isNew ? new JSONObject() : site;
                        if (isNew) s.put("key", Store.siteKeyOf(u)).put("accounts", new JSONArray());
                        s.put("name", n.isEmpty() ? Store.siteKeyOf(u) : n);
                        s.put("baseUrl", u);
                        if (!s.has("homeUrl")) s.put("homeUrl", u);
                        s.put("checkinType", type[0]);
                        store.upsertSite(s);
                        store.opLog(s.optString("key"), "", isNew ? "添加站点" : "编辑站点", "ok",
                                s.optString("name"), u, "user");
                        act.toast(isNew ? "站点已添加" : "已保存");
                        buildSites();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("取消", null).show();
    }

    /* ================= 账号凭据库 ================= */

    private void buildCreds() {
        credsBody.removeAllViews();
        Store store = new Store(act);

        if (!Crypto.available()) {
            TextView warn = Ui.iconText(act, "info",
                    "本机 Keystore 不可用，密码与 2FA 将无法保存（拒绝明文落盘）", 11, Ui.RED_D, false);
            warn.setBackground(Ui.round(Ui.RED_BG2, Ui.dp(act, 6)));
            warn.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
            wlp.bottomMargin = Ui.dp(act, 10);
            credsBody.addView(warn, wlp);
        } else {
            TextView tip = Ui.iconText(act, "shield",
                    "密码与 2FA 经 Android Keystore 加密存储；卸载应用后密钥销毁需重录。", 11, Ui.SUB, false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.bottomMargin = Ui.dp(act, 10);
            credsBody.addView(tip, tlp);
        }

        JSONArray creds = store.credentials();
        if (creds.length() == 0) {
            credsBody.addView(Ui.tv(act,
                    "还没有账号。点右上角「＋录入账号」，之后在站点卡片「＋账号」里下拉选择即可自动填充。",
                    12, Ui.SUB2));
            return;
        }
        for (int i = 0; i < creds.length(); i++) {
            final JSONObject c = creds.optJSONObject(i);
            if (c == null) continue;
            final String id = c.optString("id");
            LinearLayout row = Ui.row(act);
            row.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(act, 8), Math.max(1, Ui.dp(act, 1)), Ui.LINE));
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10));

            LinearLayout info = Ui.col(act);
            String gh = c.optString("githubUser", "");
            String sa = c.optString("siteAccount", "");
            info.addView(Ui.tv(act, c.optString("alias", gh.isEmpty() ? sa : gh), 14, Ui.TXT, true));
            StringBuilder sub = new StringBuilder();
            if (!gh.isEmpty()) sub.append("GitHub @").append(gh);
            if (!sa.isEmpty()) { if (sub.length() > 0) sub.append("  ·  "); sub.append(sa); }
            info.addView(Ui.tv(act, sub.length() == 0 ? "未填账号" : sub.toString(), 11, Ui.SUB));
            LinearLayout flags = Ui.row(act);
            boolean hasPwd = !c.optString("password", "").isEmpty();
            flags.addView(Ui.pill(act, hasPwd ? "已存密码" : "无密码", 10,
                    hasPwd ? Ui.GREEN_D : Ui.SUB, hasPwd ? Ui.GREEN_BG : Ui.LINE_SOFT));
            flags.addView(Ui.gapW(act, 5));
            boolean has2fa = store.credHasTwofa(id);
            flags.addView(Ui.pill(act, has2fa ? "2FA 已配置" : "无 2FA", 10,
                    has2fa ? Ui.BLUE : Ui.SUB, has2fa ? Ui.BLUE_BG : Ui.LINE_SOFT));
            int used = store.credentialUseCount(id);
            if (used > 0) {
                flags.addView(Ui.gapW(act, 5));
                flags.addView(Ui.pill(act, "已关联 " + used + " 站", 10, Ui.GREEN_D, Ui.GREEN_BG2));
            }
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            flp.topMargin = Ui.dp(act, 5);
            info.addView(flags, flp);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView edit = Ui.flat(act, "编辑", 11, Ui.SUB);
            edit.setOnClickListener(v -> editCredDialog(c));
            View del = Ui.iconBtn(act, "trash", 16, Ui.RED, 7);
            del.setOnClickListener(v -> new AlertDialog.Builder(act)
                    .setTitle("删除凭据")
                    .setMessage("删除后已关联的 " + used + " 个站点账号将解除绑定（不影响已授权 token）。")
                    .setPositiveButton("删除", (d, w) -> {
                        new Store(act).removeCredential(id);
                        buildCreds();
                    }).setNegativeButton("取消", null).show());
            row.addView(edit);
            row.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(act, 8);
            credsBody.addView(row, lp);
        }
    }

    /** c==null 表示新增 */
    private void editCredDialog(JSONObject c) {
        boolean isNew = c == null;
        Store store = new Store(act);
        final String id = isNew ? "" : c.optString("id");

        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 10), Ui.dp(act, 20), 0);
        EditText[] alias = new EditText[1], ghUser = new EditText[1], siteAcc = new EditText[1];
        box.addView(Ui.field(act, "别名（用于区分，如 主号 / 小号）", "主号", alias));
        box.addView(Ui.gapH(act, 10));
        box.addView(Ui.field(act, "GitHub 用户名（OAuth 授权用）", "your-github-username", ghUser));
        box.addView(Ui.gapH(act, 10));
        box.addView(Ui.field(act, "站点登录账号 / 邮箱", "user@example.com", siteAcc));
        box.addView(Ui.gapH(act, 10));

        /* 密码：明密文切换 */
        box.addView(Ui.tv(act, "登录密码（自动填充用，加密存储）", 11, Ui.SUB, true));
        LinearLayout pwdRow = Ui.row(act);
        pwdRow.setBackground(Ui.roundStroke(Ui.CARD_SUB, Ui.dp(act, 6),
                Math.max(1, Ui.dp(act, 1)), Ui.LINE));
        final EditText pwd = new EditText(act);
        pwd.setHint("留空 = 不修改");
        pwd.setTextSize(13);
        pwd.setTextColor(Ui.TXT2);
        pwd.setHintTextColor(Ui.SUB2);
        pwd.setSingleLine(true);
        pwd.setBackground(null);
        pwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwd.setPadding(Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10), Ui.dp(act, 10));
        pwdRow.addView(pwd, new LinearLayout.LayoutParams(0, -2, 1f));
        final boolean[] shown = { false };
        final LinearLayout eyeBox = Ui.row(act);
        eyeBox.setGravity(Gravity.CENTER);
        eyeBox.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        eyeBox.setClickable(true);
        eyeBox.addView(Ui.icon(act, "eye", 17, Ui.SUB));
        eyeBox.setOnClickListener(v -> {
            shown[0] = !shown[0];
            pwd.setInputType(shown[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pwd.setSelection(pwd.getText().length());
            eyeBox.removeAllViews();
            eyeBox.addView(Ui.icon(act, shown[0] ? "eye_off" : "eye", 17, Ui.SUB));
        });
        pwdRow.addView(eyeBox);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(act, 5);
        box.addView(pwdRow, plp);
        box.addView(Ui.gapH(act, 10));

        /* 2FA */
        box.addView(Ui.tv(act, "2FA（可留空；填 TOTP 密钥则自动算码）", 11, Ui.SUB, true));
        EditText[] twofa = new EditText[1];
        LinearLayout tf = Ui.field(act, "", "如 JBSWY3DPEHPK3PXP，留空 = 该账号无 2FA", twofa);
        tf.removeViewAt(0);   // 去掉重复的空标签
        box.addView(tf);
        TextView tfTip = Ui.tv(act,
                "留空时授权流程完全跳过 2FA（不聚焦、不跳转）；填了才会自动生成并填入动态码。",
                10, Ui.SUB2);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(-1, -2);
        ttlp.topMargin = Ui.dp(act, 4);
        box.addView(tfTip, ttlp);

        if (!isNew) {
            alias[0].setText(c.optString("alias", ""));
            ghUser[0].setText(c.optString("githubUser", ""));
            siteAcc[0].setText(c.optString("siteAccount", ""));
            /* 密码/2FA 不回显明文，留空表示不修改 */
            if (!c.optString("password", "").isEmpty()) pwd.setHint("已保存（留空 = 不修改）");
            if (!c.optString("twofa", "").isEmpty()) twofa[0].setHint("已保存（留空 = 不修改；填 - 清除）");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(isNew ? "录入账号" : "编辑账号")
                .setView(Ui.scroll(act, box))
                .setPositiveButton("保存", (d, w) -> {
                    String al = alias[0].getText().toString().trim();
                    String gh = ghUser[0].getText().toString().trim();
                    String sa = siteAcc[0].getText().toString().trim();
                    String pw = pwd.getText().toString();
                    String tw = twofa[0].getText().toString().trim();
                    if (al.isEmpty() && gh.isEmpty() && sa.isEmpty()) { act.toast("请至少填写别名或账号"); return; }
                    if (al.isEmpty()) al = gh.isEmpty() ? sa : gh;
                    /* null = 不修改；"" = 清除；"-" 视为清除 */
                    String pwArg = pw.isEmpty() ? (isNew ? "" : null) : pw;
                    String twArg = tw.isEmpty() ? (isNew ? "" : null) : ("-".equals(tw) ? "" : tw);
                    boolean ok = store.upsertCredential(isNew ? "" : id, al, gh, sa, pwArg, twArg, null);
                    if (!ok) {
                        act.toast("保存失败：本机无法安全加密，已拒绝明文落盘");
                        store.opLog("", "", "凭据保存", "err", "Keystore 不可用，拒绝明文落盘", "", "user");
                        return;
                    }
                    store.opLog("", "", isNew ? "录入账号" : "编辑账号", "ok", al,
                            (pwArg != null && !pwArg.isEmpty() ? "含密码 " : "")
                                    + (twArg != null && !twArg.isEmpty() ? "含2FA" : ""), "user");
                    act.toast("已保存");
                    buildCreds();
                })
                .setNegativeButton("取消", null);
        if (!isNew) {
            b.setNeutralButton("测试 2FA", (d, w) -> {
                String code = store.credTwofaCode(id);
                act.toast(code.isEmpty() ? "该账号未配置 2FA" : ("当前动态码：" + code));
            });
        }
        b.show();
    }

    /* ================= 通用组件 ================= */

    private LinearLayout group(String title) {
        LinearLayout g = Ui.col(act);
        g.setBackground(Ui.roundStroke(Ui.CARD, Ui.dp(act, 12), Math.max(1, Ui.dp(act, 1)), Ui.LINE));
        TextView t = Ui.tv(act, title, 11, Ui.SUB, true);
        t.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 4));
        g.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(act, 16);
        g.setLayoutParams(lp);
        return g;
    }

    /** 条目：图标+标题+副标题 | 右侧值文本或箭头 */
    private LinearLayout item(String iconName, String title, String sub, String value, OnClickListener onClick) {
        LinearLayout row = Ui.row(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12));
        android.widget.ImageView lead = Ui.icon(act, iconName, 18, Ui.TXT2);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(act, 18), Ui.dp(act, 18));
        ilp.rightMargin = Ui.dp(act, 10);
        row.addView(lead, ilp);
        LinearLayout left = Ui.col(act);
        left.addView(Ui.tv(act, title, 14, Ui.TXT2, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.tv(act, sub, 11, Ui.SUB2);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = Ui.dp(act, 2);
            left.addView(s, slp);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) row.addView(Ui.tv(act, value, 13, Ui.SUB));
        else if (onClick != null) row.addView(Ui.icon(act, "chevron", 15, Ui.SUB2));
        if (onClick != null) { row.setClickable(true); row.setOnClickListener(onClick); }
        return row;
    }

    private interface BoolSink { void set(boolean on); }

    private LinearLayout switchItem(String iconName, String title, String sub, boolean init, BoolSink sink) {
        LinearLayout row = Ui.row(act);
        row.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 10));
        LinearLayout left = Ui.col(act);
        left.addView(Ui.iconText(act, iconName, title, 14, Ui.TXT2, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.tv(act, sub, 11, Ui.SUB2);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = Ui.dp(act, 2);
            slp.leftMargin = Ui.dp(act, 22);
            left.addView(s, slp);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(act);
        sw.setChecked(init);
        sw.setOnCheckedChangeListener((b, on) -> sink.set(on));
        row.addView(sw);
        return row;
    }

    private LinearLayout.LayoutParams lpTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(act, dp);
        return lp;
    }
}