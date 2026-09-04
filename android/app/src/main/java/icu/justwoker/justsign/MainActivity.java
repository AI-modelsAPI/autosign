package icu.justwoker.justsign;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * MainActivity — 纯原生 M3 UI（v0.1.3，站点→账号两级）：
 *   顶部：标题 + ＋添加站点 / ⚙设置
 *   站点卡片（每站点一张，内置清单下拉添加，站名可点击跳转站点主页）：
 *     站点名 · 签到方式 | [今日已签✓] | 右侧 ⟳刷新 ＋账号 ⋯(编辑/删除站点)
 *     └ 账号行 ×N：别名 [状态chip] [今日已签] @GitHub
 *                  $可用(绿) 已用(橙) 今日(紫)
 *                  [立即签到(已签置灰)] [授权/重新授权] [日志] [删除]
 *   所有状态缓存在账号记录 lastStatus / lastCheckin（秒开）。
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFFF1F5F9, CARD = 0xFFFFFFFF, ACCENT = 0xFF2563EB;
    private static final int GREEN = 0xFF16A34A, ORANGE = 0xFFEA580C, PURPLE = 0xFF7C3AED;
    private static final int TXT = 0xFF0F172A, SUB = 0xFF64748B;

    private LinearLayout list;
    private Engine engine; // onCreate 中初始化（构造期 Context 尚未 attach，不可在此 new）
    private final Handler h = new Handler(Looper.getMainLooper());
    private static final int REQ_AUTH = 41;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new Engine(this);
        Engine.schedule(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        /* 顶栏 */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(40, 44, 40, 24);
        TextView title = new TextView(this);
        title.setText("AutoSgin");
        title.setTextColor(TXT); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView spacer = new TextView(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        TextView addSite = ghost(this, "＋ 添加站点", ACCENT);
        addSite.setOnClickListener(v -> promptAddSite());
        TextView settings = ghost(this, "⚙ 设置", SUB);
        settings.setOnClickListener(v -> showSettings());
        top.addView(title); top.addView(spacer);
        top.addView(addSite); top.addView(sp(this, 24));
        top.addView(settings);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(32, 8, 32, 60);
        sv.addView(list);

        root.addView(top);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); render(); }
    @Override public void onBackPressed() { goHome(); }

    /* ================= 渲染 ================= */

    private void render() {
        list.removeAllViews();
        JSONArray sites = new Store(this).sites();
        if (sites == null || sites.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有站点\n\n点右上角「＋ 添加站点」\n从内置公益站清单（15 站）下拉选择添加");
            empty.setTextColor(SUB); empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 120, 0, 0);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site != null) list.addView(siteCard(site));
        }
    }

    /** 站点卡片：站头 + 账号行 */
    private View siteCard(JSONObject site) {
        String siteKey = site.optString("key");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(36, 32, 36, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 28;
        card.setLayoutParams(lp);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(CARD); bgd.setCornerRadius(40);
        card.setBackground(bgd);
        card.setElevation(6);

        /* 站头 */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headL = new LinearLayout(this);
        headL.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(site.optString("name", siteKey));
        name.setTextColor(TXT); name.setTextSize(17); name.setTypeface(Typeface.DEFAULT_BOLD);
        /* 站名可点击 → 跳转站点主页（v0.1.3） */
        name.setClickable(true);
        name.setOnClickListener(v -> {
            String home = site.optString("homeUrl", site.optString("baseUrl", ""));
            if (!home.isEmpty()) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(home))); }
                catch (Exception e) { toast("无法打开链接: " + e.getMessage()); }
            }
        });
        TextView url = new TextView(this);
        url.setText(site.optString("baseUrl", "") + " · " +
                ("manual".equals(site.optString("checkinType")) ? "手动签到" : "登录即签到"));
        url.setTextColor(SUB); url.setTextSize(12);
        headL.addView(name); headL.addView(url);
        /* 站点级「今日已签」徽章：该站有账号且全部已签才显示（v0.1.3） */
        boolean siteAllChecked = false;
        JSONArray accs0 = site.optJSONArray("accounts");
        if (accs0 != null && accs0.length() > 0) {
            siteAllChecked = true;
            for (int i = 0; i < accs0.length(); i++) {
                JSONObject a = accs0.optJSONObject(i);
                if (a == null || !Engine.isCheckedToday(a)) { siteAllChecked = false; break; }
            }
        }
        if (siteAllChecked) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2, 0);
            clp.topMargin = 6;
            headL.addView(chip(this, "今日已签 ✓", GREEN), clp);
        }
        View stretch = new View(this);
        stretch.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        TextView addAcc = ghost(this, "＋账号", ACCENT);
        addAcc.setOnClickListener(v -> promptAddAccount(site));
        TextView refresh = ghost(this, "⟳ 刷新", ACCENT);
        refresh.setOnClickListener(v -> refreshSite(site));
        TextView more = ghost(this, "⋯", SUB);
        more.setOnClickListener(v -> siteMenu(site));
        head.addView(headL, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(addAcc); head.addView(sp(this, 18));
        head.addView(refresh); head.addView(sp(this, 18));
        head.addView(more);
        card.addView(head);

        /* 账号列表 */
        JSONArray accs = site.optJSONArray("accounts");
        if (accs == null || accs.length() == 0) {
            TextView t = new TextView(this);
            t.setText("该站点下还没有账号，点上方「＋账号」添加");
            t.setTextColor(SUB); t.setTextSize(13);
            t.setPadding(0, 24, 0, 4);
            card.addView(t);
        } else {
            for (int i = 0; i < accs.length(); i++) {
                JSONObject a = accs.optJSONObject(i);
                if (a != null) card.addView(accountRow(site, a, i == accs.length() - 1));
            }
        }
        return card;
    }

    /** 账号行 */
    private View accountRow(JSONObject site, JSONObject acc, boolean last) {
        String key = acc.optString("key");
        String token = acc.optString("token", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 28, 0, 24);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(0xFFE2E8F0);
            row.addView(line, new LinearLayout.LayoutParams(-1, 2));
        }

        /* 行1：别名 + 状态chip + 今日已签徽章 + GitHub */
        LinearLayout l1 = new LinearLayout(this);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);
        TextView alias = new TextView(this);
        alias.setText(acc.optString("alias", key));
        alias.setTextColor(TXT); alias.setTextSize(15); alias.setTypeface(Typeface.DEFAULT_BOLD);
        TextView chip = chip(this, chipText(acc), chipColor(acc));
        TextView gh = new TextView(this);
        gh.setText(acc.optString("githubAccount", "").isEmpty() ? "未授权" : "@" + acc.optString("githubAccount"));
        gh.setTextColor(token.isEmpty() ? ORANGE : SUB); gh.setTextSize(12);
        /* 未授权 → 文案本身作为授权入口，点击直接发起（v0.1.5） */
        if (token.isEmpty()) { gh.setClickable(true); gh.setFocusable(true); gh.setOnClickListener(v -> startAuth(site, acc)); }
        View st = new View(this);
        st.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        l1.addView(alias); l1.addView(sp(this, 14)); l1.addView(chip); l1.addView(sp(this, 14));
        /* 「今日已签」徽章（v0.1.3）：lastCheckin.date == 今天 */
        if (Engine.isCheckedToday(acc)) l1.addView(chip(this, "今日已签 ✓", GREEN));
        l1.addView(sp(this, 14));
        l1.addView(gh); l1.addView(st);
        row.addView(l1);

        /* 行2：额度（来自 lastStatus 缓存） */
        JSONObject stt = acc.optJSONObject("lastStatus");
        LinearLayout l2 = new LinearLayout(this);
        l2.setOrientation(LinearLayout.HORIZONTAL);
        l2.setGravity(Gravity.CENTER_VERTICAL);
        if (stt != null && stt.optBoolean("ok")) {
            TextView avail = new TextView(this);
            avail.setText("$" + fmt(stt.optDouble("availableUSD", 0)));
            avail.setTextColor(GREEN); avail.setTextSize(28); avail.setTypeface(Typeface.DEFAULT_BOLD);
            TextView used = new TextView(this);
            used.setText("已用 $" + fmt(stt.optDouble("usedUSD", 0)));
            used.setTextColor(ORANGE); used.setTextSize(13);
            TextView today = new TextView(this);
            today.setText("今日 $" + (stt.has("todayUsed") && !stt.isNull("todayUsed")
                    ? fmt(stt.optDouble("todayUsed", 0)) : "-"));
            today.setTextColor(PURPLE); today.setTextSize(13);
            l2.addView(avail); l2.addView(sp(this, 24));
            l2.addView(used); l2.addView(sp(this, 24));
            l2.addView(today);
        } else {
            TextView t = new TextView(this);
            t.setText(token.isEmpty() ? "尚未授权，请先授权获取额度" : "点「⟳ 刷新」获取额度");
            t.setTextColor(SUB); t.setTextSize(13);
            l2.addView(t);
        }
        row.addView(l2, lpTop(28));

        /* 行3：操作按钮（已签今日 → 立即签到置灰不可点，v0.1.3） */
        LinearLayout l3 = new LinearLayout(this);
        l3.setOrientation(LinearLayout.HORIZONTAL);
        boolean checked = Engine.isCheckedToday(acc) && !token.isEmpty();
        TextView sign = ghost(this, checked ? "今日已签" : "立即签到", checked ? SUB : ACCENT);
        if (checked) { sign.setClickable(false); sign.setFocusable(false); }
        else sign.setOnClickListener(v -> doCheckin(key));
        TextView auth = ghost(this, token.isEmpty() ? "GitHub 授权" : "重新授权", token.isEmpty() ? GREEN : SUB);
        auth.setOnClickListener(v -> startAuth(site, acc));
        TextView logs = ghost(this, "日志", SUB);
        logs.setOnClickListener(v -> showLogs(key));
        TextView del = ghost(this, "删除", 0xFFDC2626);
        del.setOnClickListener(v -> confirmRemoveAccount(acc));
        l3.addView(sign); l3.addView(sp(this, 26));
        l3.addView(auth); l3.addView(sp(this, 26));
        l3.addView(logs); l3.addView(sp(this, 26));
        l3.addView(del);
        row.addView(l3, lpTop(24));
        return row;
    }

    /* ================= 操作 ================= */

    /** 添加站点：内置公益站清单下拉选择（v0.1.3，取消自定义输入） */
    private void promptAddSite() {
        ArrayList<JSONObject> options = Catalog.all();
        JSONArray existing = new Store(this).sites();
        java.util.HashSet<String> added = new java.util.HashSet<>();
        if (existing != null) for (int i = 0; i < existing.length(); i++) {
            JSONObject s = existing.optJSONObject(i);
            if (s != null) added.add(s.optString("key"));
        }
        ArrayList<String> labels = new ArrayList<>();
        for (JSONObject c : options) {
            boolean dup = added.contains(c.optString("key"));
            labels.add(c.optString("name")
                    + ("manual".equals(c.optString("checkinType")) ? " · 每日签到" : " · 登录即签")
                    + (dup ? "（已添加）" : ""));
        }
        new AlertDialog.Builder(this)
                .setTitle("添加站点 · 内置公益站清单")
                .setItems(labels.toArray(new String[0]), (d, w) -> addCatalogSite(options.get(w)))
                .setNegativeButton("取消", null).show();
    }

    private void addCatalogSite(JSONObject c) {
        JSONObject site = new JSONObject();
        try {
            site.put("key", c.optString("key"))
                    .put("name", c.optString("name"))
                    .put("baseUrl", c.optString("homeUrl"))
                    .put("homeUrl", c.optString("homeUrl"))
                    .put("checkinType", c.optString("checkinType", "login"))
                    .put("reward", c.optString("reward", ""))
                    .put("note", c.optString("note", ""))
                    .put("accounts", new JSONArray());
            new Store(this).upsertSite(site);
            render();
            toast("站点已添加：" + c.optString("name"));
        } catch (Exception ignored) {}
    }

    private void siteMenu(JSONObject site) {
        String[] items = {"编辑站点", "签到方式: " + ("manual".equals(site.optString("checkinType")) ? "手动签到（点击切换为登录即签到）" : "登录即签到（点击切换为手动签到）"), "删除站点"};
        new AlertDialog.Builder(this)
                .setTitle(site.optString("name", site.optString("key")))
                .setItems(items, (d, w) -> {
                    if (w == 0) editSite(site);
                    else if (w == 1) toggleCheckinType(site);
                    else confirmRemoveSite(site);
                }).show();
    }

    private void editSite(JSONObject site) {
        LinearLayout box = form(this);
        final EditText name = field(this, "站点名称");
        name.setText(site.optString("name"));
        final EditText url = field(this, "API 地址");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(site.optString("baseUrl"));
        box.addView(name); box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("编辑站点")
                .setView(scrollViewOf(box))
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim().replaceAll("/+$", "");
                    if (u.isEmpty() || !u.startsWith("http")) { toast("API 地址无效"); return; }
                    try {
                        site.put("name", n.isEmpty() ? site.optString("key") : n);
                        site.put("baseUrl", u);
                        new Store(this).upsertSite(site);
                        render();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("取消", null).show();
    }

    private void toggleCheckinType(JSONObject site) {
        try {
            site.put("checkinType", "manual".equals(site.optString("checkinType")) ? "login" : "manual");
            new Store(this).upsertSite(site);
            render();
        } catch (Exception ignored) {}
    }

    private void confirmRemoveSite(JSONObject site) {
        new AlertDialog.Builder(this)
                .setTitle("删除站点")
                .setMessage("删除「" + site.optString("name") + "」及其下所有账号？（不影响站点本身账号）")
                .setPositiveButton("删除", (d, w) -> {
                    new Store(this).removeSite(site.optString("key"));
                    render();
                })
                .setNegativeButton("取消", null).show();
    }

    private void promptAddAccount(JSONObject site) {
        LinearLayout box = form(this);
        final EditText alias = field(this, "账号别名（用于区分，如：主号 / 小号）");
        /* GitHub 用户名全局记忆（v0.1.5）：上次授权过的账号名自动预填，免重复手输 */
        String lastUser = new Store(this).config().optString("lastGithubUser", "");
        if (!lastUser.isEmpty()) alias.setText(lastUser);
        box.addView(alias);
        new AlertDialog.Builder(this)
                .setTitle("添加账号 · " + site.optString("name", site.optString("key")))
                .setView(scrollViewOf(box))
                .setPositiveButton("下一步", (d, w) -> {
                    String al = alias.getText().toString().trim();
                    if (al.isEmpty()) { toast("请填写别名"); return; }
                    // 同名复用（重新授权）；否则新建
                    JSONArray accs = site.optJSONArray("accounts");
                    if (accs != null) for (int i = 0; i < accs.length(); i++) {
                        JSONObject a = accs.optJSONObject(i);
                        if (a != null && al.equals(a.optString("alias"))) {
                            startAuth(site, a);
                            return;
                        }
                    }
                    try {
                        JSONObject acc = new JSONObject()
                                .put("key", "acc_" + System.currentTimeMillis())
                                .put("alias", al).put("siteKey", site.optString("key"));
                        new Store(this).upsertAccount(site.optString("key"), acc);
                        render();
                        JSONObject created = new Store(this).findAccount(acc.optString("key"));
                        if (created != null) startAuth(site, created);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("取消", null).show();
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

    private void startAuth(JSONObject site, JSONObject acc) {
        Intent it = new Intent(this, AuthActivity.class);
        it.putExtra("siteKey", site.optString("key"));
        it.putExtra("accountKey", acc.optString("key"));
        it.putExtra("alias", acc.optString("alias"));
        startActivityForResult(it, REQ_AUTH);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AUTH) {
            if (res == RESULT_OK) { toast("授权成功"); render(); }
            else {
                String err = data == null ? "" : data.getStringExtra("error");
                if (err != null && !err.isEmpty()) toast(err);
                render();
            }
        }
    }

    private void refreshSite(JSONObject site) {
        JSONArray accs = site.optJSONArray("accounts");
        if (accs == null) return;
        for (int i = 0; i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a != null) refreshOne(a);
        }
        toast("正在刷新 " + accs.length() + " 个账号…");
    }

    private void refreshOne(JSONObject acc) {
        final String key = acc.optString("key");
        new Thread(() -> {
            try {
                JSONObject st = engine.status(key);
                Store store = new Store(this);
                JSONObject rec = store.findAccount(key);
                if (rec != null) {
                    rec.put("lastStatus", st);
                    /* 跨设备已签同步：服务器确认今天有签到记录（无论哪台设备签的）→ 写入本地已签状态，徽章与置灰立即生效（v0.1.3.1） */
                    if (st.optBoolean("todayChecked", false)) {
                        rec.put("lastCheckin", new JSONObject()
                                .put("date", Engine.todayStr())
                                .put("reward", st.optDouble("todayRewardUSD", 0))
                                .put("time", System.currentTimeMillis()));
                    }
                    store.upsertAccount(store.siteOfAccount(key).optString("key"), rec);
                }
                h.post(this::render);
            } catch (Exception e) {
                h.post(() -> toast(key + " 刷新失败: " + e.getMessage()));
            }
        }).start();
    }

    private void doCheckin(String key) {
        new Thread(() -> {
            try {
                JSONObject r = engine.checkin(key);
                h.post(() -> {
                    double reward = r.optDouble("reward", 0);
                    if (r.optBoolean("already")) {
                        String m = r.optString("message", "今日已签到");
                        if (reward > 0) m += " · 今日奖励 $" + fmt(reward);
                        toast(m);
                    } else if (reward > 0) {
                        toast("签到成功 🎉 本次奖励 +$" + fmt(reward));
                    } else if (r.optBoolean("skipped")) {
                        toast(r.optString("message", "已刷新保活"));
                    } else {
                        toast(r.optString("message", r.optBoolean("ok") ? "签到完成" : "签到失败"));
                    }
                    render();
                });
            } catch (Exception e) {
                h.post(() -> toast("签到失败: " + e.getMessage()));
            }
        }).start();
    }

    private void showLogs(String key) {
        new Thread(() -> {
            String text;
            try {
                StringBuilder sb = new StringBuilder();
                JSONObject r = engine.logs(key, "系统", 20);
                JSONArray rows = r.optJSONArray("rows");
                JSONObject lb = r.optJSONObject("lastBonus");
                if (lb != null) sb.append("最近签到奖励: $").append(fmt(lb.optDouble("quota", 0) / 500000d)).append("\n\n");
                if (rows == null || rows.length() == 0) sb.append("暂无日志");
                else for (int i = 0; i < rows.length(); i++) {
                    JSONObject o = rows.optJSONObject(i);
                    if (o == null) continue;
                    sb.append(o.optString("time", "").replace('T', ' '));
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) sb.append("  ").append(t.length() > 40 ? t.substring(0, 40) : t);
                    sb.append('\n');
                }
                text = sb.toString();
            } catch (Exception e) { text = "日志获取失败: " + e.getMessage(); }
            final String msg = text;
            h.post(() -> new AlertDialog.Builder(this)
                    .setTitle("账号日志（最近 20 条系统记录）")
                    .setMessage(msg)
                    .setPositiveButton("关闭", null).show());
        }).start();
    }

    private void showSettings() {
        Store store = new Store(this);
        JSONObject cfg = store.config();
        JSONObject proxy = cfg.optJSONObject("proxy");
        LinearLayout box = form(this);
        final EditText host = field(this, "SOCKS5 地址（如 127.0.0.1，留空=直连）");
        final EditText port = field(this, "SOCKS5 端口（如 10808）");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (proxy != null && proxy.optBoolean("enabled")) {
            host.setText(proxy.optString("host"));
            port.setText(String.valueOf(proxy.optInt("port")));
        }
        box.addView(host); box.addView(port);
        new AlertDialog.Builder(this)
                .setTitle("设置 · 代理")
                .setView(scrollViewOf(box))
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        String hh = host.getText().toString().trim();
                        String pp = port.getText().toString().trim();
                        if (hh.isEmpty() || pp.isEmpty()) {
                            proxy.put("enabled", false);
                        } else {
                            proxy.put("enabled", true).put("host", hh).put("port", Integer.parseInt(pp));
                        }
                        cfg.put("proxy", proxy);
                        store.saveConfig(cfg);
                        render();
                        toast("已保存");
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("取消", null).show();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    /* ================= 小工具 ================= */

    private static String chipText(JSONObject acc) {
        String token = acc.optString("token", "");
        if (token.isEmpty()) return "待授权";
        JSONObject st = acc.optJSONObject("lastStatus");
        if (st != null && !st.optBoolean("ok")) return "异常";
        if (st != null && st.optBoolean("ok")) return "正常";
        return "已授权";
    }
    private static int chipColor(JSONObject acc) {
        String t = chipText(acc);
        return "正常".equals(t) ? GREEN : ("异常".equals(t) ? 0xFFDC2626 : (t.equals("已授权") ? ACCENT : ORANGE));
    }
    private static String fmt(double d) {
        if (d >= 1000) return String.format(java.util.Locale.US, "%.0f", d);
        return String.format(java.util.Locale.US, "%.2f", d);
    }
    private static LinearLayout.LayoutParams lpTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px;
        return lp;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static View sp(Context c, int px) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, 1));
        return v;
    }

    private static LinearLayout form(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (c.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    private static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(true);
        return e;
    }

    private View scrollViewOf(View v) {
        ScrollView sv = new ScrollView(this);
        sv.addView(v);
        return sv;
    }

    private static TextView ghost(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(10, 16, 10, 16);
        t.setClickable(true);
        t.setFocusable(true);
        GradientDrawable ripple = new GradientDrawable();
        ripple.setColor(0x0F000000);
        ripple.setCornerRadius(16);
        t.setBackground(ripple);
        return t;
    }

    private static TextView chip(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor((color & 0x00FFFFFF) | 0x18000000);
        bgd.setCornerRadius(24);
        t.setBackground(bgd);
        t.setPadding(18, 6, 18, 6);
        return t;
    }
}