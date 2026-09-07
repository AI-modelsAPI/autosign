package icu.justwoker.justsign;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * KeyPanel（v0.6.2）— API Key 管理滑出面板（UI设计师定稿方案）。
 * 挂在账号卡片下方：钥匙图标点击展开，ValueAnimator 高度动画（展开 260ms 减速 / 收起 200ms 加速）。
 * 面板浅灰绿（#E1EBEA 聚焦容器）+ 深青黑文字；上滑手势或右上角向上箭头收起。
 * 互斥联动：同一时间只展开一个面板。
 */
public class KeyPanel {
    /** 全局互斥：当前展开的面板（同一时刻只开一个） */
    private static KeyPanel opened = null;

    private final Activity act;
    private final String siteKey, accountKey, alias, siteName, baseUrl;
    private final LinearLayout panel;   // 滑出面板容器（挂在卡片下方）
    private final LinearLayout listBody;
    private final TextView hint;
    private ValueAnimator animator;
    private boolean expanded = false;
    private float downY = 0;

    public static View build(Activity act, JSONObject site, JSONObject acc, LinearLayout parentForPanel) {
        /* 面板本体：默认 GONE 高度 0 */
        KeyPanel kp = new KeyPanel(act, site, acc);
        parentForPanel.addView(kp.panel);
        /* 钥匙入口按钮 */
        View btn = Ui.iconBtn(act, "key", 18, Ui.SUB, 4);
        btn.setOnClickListener(v -> kp.toggle());
        return btn;
    }

    private KeyPanel(Activity act, JSONObject site, JSONObject acc) {
        this.act = act;
        this.siteKey = site.optString("key", "");
        this.accountKey = acc.optString("key", "");
        this.alias = acc.optString("alias", accountKey);
        this.siteName = site.optString("name", "");
        this.baseUrl = site.optString("baseUrl", "").replaceAll("/+$", "");

        /* 面板：浅灰绿聚焦容器（设计稿 225,235,234），圆角 12dp */
        panel = Ui.col(act);
        panel.setVisibility(View.GONE);
        panel.setPadding(Ui.dp(act,16), Ui.dp(act,14), Ui.dp(act,16), Ui.dp(act,14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(act,6);
        panel.setLayoutParams(plp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE1EBEA);
        bg.setCornerRadius(Ui.dp(act,12));
        panel.setBackground(bg);

        /* 标题栏：主标题 + 右上角收起箭头 */
        LinearLayout header = Ui.row(act);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.tv(act, "API 密钥管理", 15, 0xFF122221, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        View collapseBtn = Ui.iconBtn(act, "collapse", 18, 0xFF5A6E6D, 4);
        collapseBtn.setOnClickListener(v -> collapse());
        header.addView(collapseBtn);
        panel.addView(header);

        /* 说明行：Base URL（点击复制） */
        TextView url = Ui.tv(act, "接口地址: " + baseUrl, 12, 0xFF4A605E);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setOnClickListener(v -> {
            copyToClipboard(baseUrl);
            toast("已复制 Base URL");
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
        ulp.topMargin = Ui.dp(act,4);
        panel.addView(url, ulp);

        /* 第一分隔线 */
        panel.addView(divider());

        /* 列表区 + 状态提示（二选一显示） */
        listBody = Ui.col(act);
        panel.addView(listBody);
        hint = Ui.tv(act, "", 12, 0xFF4A605E);
        hint.setPadding(0, Ui.dp(act,20), 0, Ui.dp(act,20));
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);

        /* 第二分隔线 */
        panel.addView(divider());

        /* 底部操作区：新建（次级） | 上滑收起提示 */
        LinearLayout footer = Ui.row(act);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView addBtn = Ui.tv(act, "+ 新建 Key", 12, 0xFF1E4A48, true);
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(0xFFD1DEDC);
        abg.setCornerRadius(Ui.dp(act,6));
        abg.setStroke(1, 0xFF1E4A48);
        addBtn.setBackground(abg);
        addBtn.setPadding(Ui.dp(act,12), Ui.dp(act,7), Ui.dp(act,12), Ui.dp(act,7));
        addBtn.setOnClickListener(v -> promptCreate());
        footer.addView(addBtn);
        TextView upHint = Ui.tv(act, "  上滑或点 ↑ 收起", 11, 0xFF7A8E8D);
        footer.addView(upHint, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(footer);

        /* 上滑手势收起（面板区域整体监听，MOVE 超阈值触发） */
        panel.setOnTouchListener((v, ev) -> {
            if (!expanded) return false;
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return false;
                default:
                    if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                        float dy = ev.getRawY() - downY;
                        if (dy < -Ui.dp(act,48)) { collapse(); return true; }
                    }
                    return false;
            }
        });
    }

    private View divider() {
        View d = new View(act);
        d.setBackgroundColor(0xFFC6D6D4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.topMargin = Ui.dp(act,6); lp.bottomMargin = Ui.dp(act,6);
        d.setLayoutParams(lp);
        return d;
    }

    /* 展开/收起切换（互斥：先收起其他面板） */
    public void toggle() {
        if (expanded) { collapse(); return; }
        if (opened != null && opened != this) opened.collapse();
        expand();
    }

    private void expand() {
        expanded = true;
        opened = this;
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(180).start();
        /* 高度插值动画：0 → 目标高度（260ms 减速） */
        panel.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int target = panel.getMeasuredHeight();
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofInt(0, target);
        animator.setDuration(260);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = panel.getLayoutParams();
            lp.height = (int) a.getAnimatedValue();
            panel.setLayoutParams(lp);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                ViewGroup.LayoutParams lp = panel.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.setLayoutParams(lp);
            }
        });
        animator.start();
        reload();
    }

    public void collapse() {
        if (!expanded) return;
        expanded = false;
        if (opened == this) opened = null;
        /* 收起动画：当前高度 → 0（200ms 加速） */
        int cur = panel.getHeight();
        if (cur <= 0) { panel.setVisibility(View.GONE); return; }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofInt(cur, 0);
        animator.setDuration(200);
        animator.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            ViewGroup.LayoutParams lp = panel.getLayoutParams();
            lp.height = (int) a.getAnimatedValue();
            panel.setLayoutParams(lp);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                panel.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = panel.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.setLayoutParams(lp);
            }
        });
        animator.start();
    }

    private void reload() {
        listBody.removeAllViews();
        hint.setText("正在获取 Key 列表…");
        hint.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String err = "";
            JSONArray items = new JSONArray();
            try {
                items = new Engine(act).tokenList(accountKey).optJSONArray("items");
                if (items == null) items = new JSONArray();
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final JSONArray fItems = items;
            final String fErr = err;
            act.runOnUiThread(() -> {
                if (!expanded) return;   // 已收起则不更新
                if (!fErr.isEmpty()) {
                    hint.setText("获取失败：" + fErr + "\n（可展开操作日志查看诊断详情）");
                    hint.setVisibility(View.VISIBLE);
                    return;
                }
                if (fItems.length() == 0) {
                    hint.setText("暂无 API Key");
                    hint.setVisibility(View.VISIBLE);
                    return;
                }
                hint.setVisibility(View.GONE);
                long unit = 500000;
                try { unit = new Store(act).siteMetaLong(siteKey, "quotaPerUnit", 500000); } catch (Exception ignored) {}
                if (unit <= 0) unit = 500000;
                for (int i = 0; i < fItems.length(); i++) {
                    JSONObject t = fItems.optJSONObject(i);
                    if (t == null) continue;
                    listBody.addView(keyRow(t, unit));
                }
            });
        }, "token-list").start();
    }

    /** 单个 Key 行：名称+状态 | 右侧已用金额；脱敏 key 行带复制/删除 */
    private LinearLayout keyRow(JSONObject t, long unit) {
        LinearLayout box = Ui.col(act);
        box.setPadding(0, Ui.dp(act,6), 0, Ui.dp(act,6));

        final String name = t.optString("name", "未命名");
        final String keyStr = t.optString("key", "");
        final long id = t.optLong("id", 0);
        final int status = t.optInt("status", 1);
        double used = t.optDouble("used_quota", 0);

        /* 第一行：名称+[状态] | 已用 | 复制 | 删除 */
        LinearLayout r1 = Ui.row(act);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = Ui.tv(act, name, 13, 0xFF122221, true);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r1.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
        /* 状态小圆点+文字 */
        boolean enabled = status == 1;
        TextView st = Ui.tv(act, enabled ? "● 正常" : "● 已失效", 10,
                enabled ? 0xFF2E7D32 : 0xFFC62828);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2, -2);
        stlp.leftMargin = Ui.dp(act,6);
        r1.addView(st, stlp);
        r1.addView(Ui.tv(act, " 已用 $" + String.format("%.2f", used / unit), 10, 0xFF7A8E8D));
        View copyBtn = Ui.iconBtn(act, "copy", 16, 0xFF1E4A48, 3);
        copyBtn.setOnClickListener(v -> {
            if (keyStr.isEmpty()) { toast("该 Key 无内容"); return; }
            copyToClipboard(keyStr);
            toast("已复制 Key 到剪贴板");
        });
        r1.addView(copyBtn);
        View delBtn = Ui.iconBtn(act, "trash", 16, 0xFFC62828, 3);
        delBtn.setOnClickListener(v -> confirmDelete(name, id));
        r1.addView(delBtn);
        box.addView(r1);

        /* 第二行：脱敏 key（等宽，浅底块） */
        if (!keyStr.isEmpty()) {
            String masked = keyStr.length() > 8
                    ? keyStr.substring(0, Math.min(3, keyStr.length())) + "••••••" + keyStr.substring(keyStr.length() - 4)
                    : keyStr;
            TextView keyTv = Ui.tv(act, masked, 12, 0xFF334846);
            keyTv.setTypeface(Typeface.MONOSPACE);
            GradientDrawable kb = new GradientDrawable();
            kb.setColor(0xFFDDE7E5);
            kb.setCornerRadius(Ui.dp(act,4));
            keyTv.setBackground(kb);
            keyTv.setPadding(Ui.dp(act,8), Ui.dp(act,3), Ui.dp(act,8), Ui.dp(act,3));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
            klp.topMargin = Ui.dp(act,4);
            box.addView(keyTv, klp);
        }

        if (!enabled) box.setAlpha(0.6f);
        return box;
    }

    private void promptCreate() {
        LinearLayout box = Ui.col(act);
        box.setPadding(Ui.dp(act,20), Ui.dp(act,10), Ui.dp(act,20), 0);
        EditText[] nameOut = new EditText[1];
        box.addView(Ui.field(act, "Key 名称（如 Cursor / NextChat）", "我的 Key", nameOut));
        EditText nameEt = nameOut[0];
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(act)
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
            try { new Engine(act).tokenCreate(accountKey, name); }
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

    private void confirmDelete(String name, long id) {
        new AlertDialog.Builder(act)
                .setTitle("删除 API Key")
                .setMessage("确定删除 Key「" + name + "」吗？\n删除后使用该 Key 的应用将立即失效。")
                .setPositiveButton("删除", (d, w) -> {
                    new Thread(() -> {
                        String err = "";
                        try { new Engine(act).tokenDelete(accountKey, id); }
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