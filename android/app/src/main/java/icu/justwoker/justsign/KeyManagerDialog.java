package icu.justwoker.justsign;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * KeyManagerDialog（v0.6.0）— API Key 管理弹窗（UI设计师方案：BottomSheet 式）。
 * 三行列表项：名称+状态+复制/删除 / 脱敏 key / 已用·剩余。
 * 新建成功自动复制完整 key；删除走 AlertDialog 确认。
 */
public class KeyManagerDialog {
    private final Activity act;
    private final String siteKey, accountKey, alias, siteName;
    private final Engine engine;
    private Dialog dlg;
    private LinearLayout listBody;
    private TextView emptyHint;
    private LinearLayout loadingBox;

    public static void show(Activity act, JSONObject site, JSONObject acc) {
        new KeyManagerDialog(act, site, acc).open();
    }

    private KeyManagerDialog(Activity act, JSONObject site, JSONObject acc) {
        this.act = act;
        this.siteKey = site.optString("key", "");
        this.accountKey = acc.optString("key", "");
        this.alias = acc.optString("alias", accountKey);
        this.siteName = site.optString("name", "");
        this.engine = new Engine(act);
    }

    private void open() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0xB3000000);

        /* 底部面板 */
        LinearLayout panel = Ui.col(act);
        panel.setBackgroundColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{Ui.dp(act,16),Ui.dp(act,16),0,0,0,0,0,0});
        panel.setBackground(bg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);

        /* 拖动手柄 */
        View handle = new View(act);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(act,36), Ui.dp(act,4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Ui.dp(act,8);
        handle.setBackgroundColor(0xFFE2E8F0);
        handle.setBackground(new GradientDrawable());
        ((GradientDrawable)handle.getBackground()).setCornerRadius(Ui.dp(act,2));
        ((GradientDrawable)handle.getBackground()).setColor(0xFFE2E8F0);
        panel.addView(handle, hlp);

        /* 标题栏：标题+账号标记 | 新建按钮 */
        LinearLayout header = Ui.row(act);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(act,16), Ui.dp(act,12), Ui.dp(act,16), Ui.dp(act,12));
        LinearLayout titleCol = Ui.col(act);
        titleCol.addView(Ui.tv(act, "API Key 管理", 16, Ui.TXT, true));
        TextView sub = Ui.tv(act, alias + " · " + siteName, 12, Ui.SUB);
        titleCol.addView(sub);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView addBtn = Ui.iconBtnText(act, "plus", "新建", 13, Ui.BLUE, 0xFFEFF6FF, 12, 6);
        addBtn.setOnClickListener(v -> promptCreate());
        header.addView(addBtn);
        panel.addView(header);

        panel.addView(Ui.divider(act, 0xFFF1F5F9, 0));

        /* 列表区（ScrollView 内动态填充） */
        ScrollView sc = new ScrollView(act);
        listBody = Ui.col(act);
        listBody.setPadding(0, Ui.dp(act,4), 0, Ui.dp(act,16));
        sc.addView(listBody);
        panel.addView(sc, new LinearLayout.LayoutParams(-1, Ui.dp(act,420)));

        /* 加载中 */
        loadingBox = Ui.col(act);
        loadingBox.setGravity(Gravity.CENTER);
        loadingBox.setPadding(0, Ui.dp(act,60), 0, Ui.dp(act,60));
        ProgressBar pb = new ProgressBar(act);
        loadingBox.addView(pb);
        TextView lt = Ui.tv(act, "正在获取 Key 列表…", 12, Ui.SUB);
        LinearLayout.LayoutParams ltlp = new LinearLayout.LayoutParams(-2, -2);
        ltlp.topMargin = Ui.dp(act,10);
        loadingBox.addView(lt, ltlp);
        panel.addView(loadingBox, new LinearLayout.LayoutParams(-1, Ui.dp(act,420)));

        /* 空态 */
        emptyHint = Ui.tv(act, "暂无 API Key，点右上「新建」创建", 13, Ui.SUB2);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, Ui.dp(act,60), 0, Ui.dp(act,60));
        emptyHint.setVisibility(View.GONE);
        panel.addView(emptyHint, new LinearLayout.LayoutParams(-1, Ui.dp(act,420)));

        root.addView(panel, plp);
        root.setOnClickListener(v -> dlg.dismiss());
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        dlg.show();
        reload();
    }

    private void reload() {
        loadingBox.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);
        listBody.removeAllViews();
        new Thread(() -> {
            String err = "";
            JSONArray items = new JSONArray();
            try {
                items = engine.tokenList(accountKey).optJSONArray("items");
                if (items == null) items = new JSONArray();
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final JSONArray fItems = items;
            final String fErr = err;
            act.runOnUiThread(() -> {
                if (dlg == null || !dlg.isShowing()) return;
                loadingBox.setVisibility(View.GONE);
                if (!fErr.isEmpty()) {
                    emptyHint.setText("获取 Key 列表失败：" + fErr);
                    emptyHint.setVisibility(View.VISIBLE);
                    return;
                }
                if (fItems.length() == 0) {
                    emptyHint.setText("暂无 API Key，点右上「新建」创建");
                    emptyHint.setVisibility(View.VISIBLE);
                    return;
                }
                long unit = 500000;
                try { unit = new Store(act).siteMetaLong(siteKey, "quotaPerUnit", 500000); } catch (Exception ignored) {}
                if (unit <= 0) unit = 500000;
                for (int i = 0; i < fItems.length(); i++) {
                    JSONObject t = fItems.optJSONObject(i);
                    if (t == null) continue;
                    listBody.addView(keyRow(t, unit));
                    if (i < fItems.length() - 1) {
                        View gap = new View(act);
                        listBody.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(act,8)));
                    }
                }
            });
        }, "token-list").start();
    }

    /** 单个 Key 行：名称+状态+复制/删除 / 脱敏key / 已用·剩余 */
    private LinearLayout keyRow(JSONObject t, long unit) {
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act,12), Ui.dp(act,10), Ui.dp(act,12), Ui.dp(act,10));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF8FAFC);
        cardBg.setCornerRadius(Ui.dp(act,8));
        cardBg.setStroke(1, 0xFFE2E8F0);
        box.setBackground(cardBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.leftMargin = Ui.dp(act,16); blp.rightMargin = Ui.dp(act,16); blp.topMargin = Ui.dp(act,8);
        box.setLayoutParams(blp);

        final String name = t.optString("name", "未命名");
        final String keyStr = t.optString("key", "");
        final long id = t.optLong("id", 0);
        final int status = t.optInt("status", 1);
        double used = t.optDouble("used_quota", 0);
        double remain = t.optDouble("remain_quota", 0);

        /* 第一行：名称 + 状态徽标 + 复制 + 删除 */
        LinearLayout r1 = Ui.row(act);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = Ui.tv(act, name, 14, Ui.TXT, true);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r1.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
        /* 状态徽标 */
        boolean enabled = status == 1;
        TextView badge = Ui.tv(act, enabled ? "启用" : "禁用", 10,
                enabled ? 0xFF15803D : 0xFFB91C1C);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(enabled ? 0xFFDCFCE7 : 0xFFFEE2E2);
        bbg.setCornerRadius(Ui.dp(act,4));
        badge.setBackground(bbg);
        badge.setPadding(Ui.dp(act,6), Ui.dp(act,2), Ui.dp(act,6), Ui.dp(act,2));
        r1.addView(badge);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-2, -2);
        blp2.leftMargin = Ui.dp(act,8);
        badge.setLayoutParams(blp2);
        /* 复制按钮 */
        View copyBtn = Ui.iconBtn(act, "copy", 18, Ui.BLUE, 6);
        copyBtn.setOnClickListener(v -> {
            if (keyStr.isEmpty()) { toast("该 Key 无内容"); return; }
            copyToClipboard(keyStr);
            toast("已复制 Key 到剪贴板");
        });
        r1.addView(copyBtn);
        /* 删除按钮 */
        View delBtn = Ui.iconBtn(act, "trash", 18, Ui.RED, 6);
        delBtn.setOnClickListener(v -> confirmDelete(name, id));
        r1.addView(delBtn);

        box.addView(r1);

        /* 第二行：脱敏 key */
        if (!keyStr.isEmpty()) {
            String masked = keyStr.length() > 8
                    ? keyStr.substring(0, Math.min(6, keyStr.length())) + "••••••" + keyStr.substring(keyStr.length() - 4)
                    : keyStr;
            TextView keyTv = Ui.tv(act, masked, 12, Ui.SUB);
            keyTv.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
            klp.topMargin = Ui.dp(act,6);
            box.addView(keyTv, klp);
        }

        /* 第三行：已用 · 剩余 */
        LinearLayout r3 = Ui.row(act);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(-2, -2);
        r3lp.topMargin = Ui.dp(act,4);
        r3.setLayoutParams(r3lp);
        r3.addView(Ui.tv(act, "已用 $" + String.format("%.2f", used / unit), 11, Ui.SUB2));
        TextView dot = Ui.tv(act, " · ", 11, 0xFFCBD5E1);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = Ui.dp(act,4); dlp.rightMargin = Ui.dp(act,4);
        r3.addView(dot, dlp);
        String remainTxt = remain <= 0 ? "剩余 无限" : "剩余 $" + String.format("%.2f", remain / unit);
        r3.addView(Ui.tv(act, remainTxt, 11, Ui.SUB2));
        box.addView(r3);

        if (!enabled) box.setAlpha(0.6f);
        return box;
    }

    /** 新建：输入名称 → POST → 成功自动复制并刷新 */
    private void promptCreate() {
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act,20), Ui.dp(act,10), Ui.dp(act,20), 0);
        EditText[] nameOut = new EditText[1];
        box.addView(Ui.field(act, "Key 名称（如 Cursor / NextChat）", "我的 Key", nameOut));
        EditText nameEt = nameOut[0];
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT);
        AlertDialog ad = new AlertDialog.Builder(act)
                .setTitle("新建 API Key")
                .setView(box)
                .setPositiveButton("创建", (d, w) -> {
                    String name = nameEt.getText().toString().trim();
                    if (name.isEmpty()) { toast("请输入名称"); return; }
                    doCreate(name);
                })
                .setNegativeButton("取消", null)
                .show();
        nameEt.postDelayed(() -> {
            nameEt.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(nameEt, 0);
        }, 200);
    }

    private void doCreate(String name) {
        toast("正在创建…");
        new Thread(() -> {
            String err = "";
            try { engine.tokenCreate(accountKey, name); }
            catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final String fErr = err;
            act.runOnUiThread(() -> {
                if (!fErr.isEmpty()) { toast("创建失败：" + fErr); return; }
                toast("Key 创建成功");
                new Store(act).opLog(siteKey, accountKey, "Key 管理", "ok",
                        "新建 API Key", name, "user");
                reload();
            });
        }, "token-create").start();
    }

    /** 删除：确认 → DELETE → 刷新 */
    private void confirmDelete(String name, long id) {
        new AlertDialog.Builder(act)
                .setTitle("删除 API Key")
                .setMessage("确定删除 Key「" + name + "」吗？\n删除后使用该 Key 的应用将立即失效。")
                .setPositiveButton("删除", (d, w) -> {
                    new Thread(() -> {
                        String err = "";
                        try { engine.tokenDelete(accountKey, id); }
                        catch (Exception e) { err = String.valueOf(e.getMessage()); }
                        final String fErr = err;
                        act.runOnUiThread(() -> {
                            if (!fErr.isEmpty()) { toast("删除失败：" + fErr); return; }
                            toast("已删除");
                            new Store(act).opLog(siteKey, accountKey, "Key 管理", "ok",
                                    "删除 API Key", name, "user");
                            reload();
                        });
                    }, "token-delete").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(text);
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }
}