package icu.justwoker.justsign;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * LogPopup（v0.2.0）— 日志悬浮窗（深色终端风）。
 *
 * 规格（gemini 定稿）：
 *   宽 88%（≤380dp）/ 高 60%，屏幕居中，圆角 14dp，遮罩 #66000000
 *   点遮罩关闭；淡入 + 缩放 0.95→1.0，150ms
 *   自动弹出（可在设置关闭）：批量任务开始时弹一次
 *   防打扰：已显示时只 append 文本 + 自动滚底，绝不重建弹窗；80ms 节流合并刷新
 *
 * 实现方式：直接挂到 Activity 的 android.R.id.content 上（避免 PopupWindow 在
 *   部分 ROM 上的焦点/触摸外部行为差异），dismiss 时移除。
 */
public final class LogPopup {

    private static LogPopup current;

    private final Activity act;
    private final FrameLayout scrim;
    private final LinearLayout body;      // 日志行容器
    private final ScrollView scroller;
    private final TextView footerRight;
    private final Handler h = new Handler(Looper.getMainLooper());

    /* 节流：80ms 内的多条日志合并成一次 UI 刷新 */
    private final java.util.ArrayList<JSONObject> pending = new java.util.ArrayList<>();
    private boolean flushScheduled = false;

    private LogPopup(Activity a) {
        this.act = a;

        int wMax = Ui.dp(a, 380);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int w = Math.min(wMax, (int) (screenW * 0.88f));
        int hh = (int) (screenH * 0.60f);
        int r = Ui.dp(a, 14);

        /* Body 先建好：Header 的清空按钮 lambda 会引用它（final 字段必须先赋值） */
        body = Ui.col(a);
        body.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        scroller = new ScrollView(a);
        scroller.addView(body);

        /* ---- 遮罩层（点击关闭） ---- */
        scrim = new FrameLayout(a);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismiss());

        /* ---- 窗口 ---- */
        LinearLayout win = Ui.col(a);
        win.setBackground(Ui.roundStroke(Ui.LOG_BG, r, Math.max(1, Ui.dp(a, 1)), Ui.LOG_LINE));
        win.setElevation(Ui.dp(a, 16));
        win.setClickable(true);   // 吃掉点击，避免穿透到遮罩

        /* Header */
        LinearLayout header = Ui.row(a);
        header.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        header.addView(Ui.iconText(a, "log", "实时执行日志", 13, Ui.LOG_TXT, true));
        header.addView(Ui.spring(a));
        TextView clear = Ui.iconText(a, "trash", "清空", 11, Ui.LOG_INFO, true);
        clear.setPadding(Ui.dp(a, 6), Ui.dp(a, 5), Ui.dp(a, 6), Ui.dp(a, 5));
        clear.setClickable(true);
        clear.setOnClickListener(v -> {
            new Store(act).clearOpLogs();
            body.removeAllViews();
            body.addView(emptyLine("日志已清空"));
        });
        View close = Ui.iconBtn(a, "cross", 15, Ui.LOG_INFO, 5);
        close.setOnClickListener(v -> dismiss());
        header.addView(clear);
        header.addView(Ui.gapW(a, 6));
        header.addView(close);
        win.addView(header, new LinearLayout.LayoutParams(-1, -2));
        win.addView(hairline(a));

        /* Body 挂载 */
        win.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        /* Footer */
        win.addView(hairline(a));
        LinearLayout footer = Ui.row(a);
        footer.setPadding(Ui.dp(a, 14), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
        footer.addView(Ui.iconText(a, "dot", "监听中", 9, Ui.LOG_OK, false));
        footer.addView(Ui.spring(a));
        boolean autoOn = new Store(a).uiPref("logAutoPopup", true);
        footerRight = Ui.tv(a, "自动弹出: " + (autoOn ? "开" : "关"), 9, Ui.LOG_INFO);
        footer.addView(footerRight);
        win.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hh, Gravity.CENTER);
        scrim.addView(win, lp);

        /* 进入动画：Alpha 0→1 + Scale 0.95→1.0，150ms */
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        ScaleAnimation sc = new ScaleAnimation(0.95f, 1f, 0.95f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        set.addAnimation(sc);
        set.setDuration(150);
        win.startAnimation(set);
    }

    private static View hairline(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(Ui.LOG_LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(a, 0.5f))));
        return v;
    }

    private TextView emptyLine(String text) {
        return Ui.tv(act, text, 11, Ui.LOG_INFO);
    }

    /* ================= 对外 API ================= */

    /** 显示（已显示则不重建，只把最新日志补齐） */
    public static synchronized LogPopup show(Activity a) {
        if (a == null || a.isFinishing()) return null;
        if (current != null && current.act == a && current.scrim.getParent() != null) {
            current.reload();
            return current;
        }
        LogPopup p = new LogPopup(a);
        ViewGroup root = a.findViewById(android.R.id.content);
        if (root == null) return null;
        root.addView(p.scrim, new FrameLayout.LayoutParams(-1, -1));
        current = p;
        p.reload();
        return p;
    }

    /** 若「自动弹出」开启则弹出；已显示时只追加，不重建（防闪烁） */
    public static void autoShow(Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            if (!new Store(a).uiPref("logAutoPopup", true)) return;
        } catch (Exception ignored) {}
        show(a);
    }

    public static boolean isShowing() {
        return current != null && current.scrim.getParent() != null;
    }

    /** 追加一条（若窗口未显示则忽略 UI，日志本身已由 Store.opLog 落盘） */
    public static void append(JSONObject entry) {
        LogPopup p = current;
        if (p == null || entry == null || p.scrim.getParent() == null) return;
        synchronized (p.pending) { p.pending.add(entry); }
        p.scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        h.postDelayed(() -> {
            flushScheduled = false;
            java.util.ArrayList<JSONObject> batch;
            synchronized (pending) {
                batch = new java.util.ArrayList<>(pending);
                pending.clear();
            }
            for (JSONObject e : batch) body.addView(line(e));
            trim();
            scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
        }, 80);
    }

    /** 从 Store 重新加载全部日志（最多 120 条，避免浮窗过长） */
    public void reload() {
        body.removeAllViews();
        JSONArray a = new Store(act).opLogs();
        int from = Math.max(0, a.length() - 120);
        if (a.length() == 0) {
            body.addView(emptyLine("暂无日志 · 执行签到或刷新后这里会实时输出"));
        } else {
            for (int i = from; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null) body.addView(line(e));
            }
        }
        boolean autoOn = new Store(act).uiPref("logAutoPopup", true);
        footerRight.setText("自动弹出: " + (autoOn ? "开" : "关"));
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void trim() {
        while (body.getChildCount() > 200) body.removeViewAt(0);
    }

    public void dismiss() {
        try {
            ViewGroup root = act.findViewById(android.R.id.content);
            if (root != null) root.removeView(scrim);
        } catch (Exception ignored) {}
        if (current == this) current = null;
    }

    public static void dismissIfShowing() {
        LogPopup p = current;
        if (p != null) p.dismiss();
    }

    /* ================= 日志条目渲染 ================= */

    private View line(JSONObject e) {
        String level = e.optString("level", "info");
        int fg = "ok".equals(level) ? Ui.LOG_OK : ("err".equals(level) ? Ui.LOG_ERR : Ui.LOG_INFO);

        LinearLayout box = Ui.col(act);
        box.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));

        String site = e.optString("siteName", "");
        String alias = e.optString("alias", "");
        String who = site.isEmpty() ? "" : ("[" + site + (alias.isEmpty() ? "" : ("/" + alias)) + "] ");
        String head = hhmmss(e.optLong("time", 0)) + " " + who
                + "[" + e.optString("action", "") + "] " + e.optString("summary", "");
        /* 状态用同色小圆点表示（单色扁平，不用彩色 emoji） */
        TextView t1 = Ui.iconText(act, "dot", head, 11, fg, true);
        box.addView(t1);

        String detail = e.optString("detail", "");
        String source = srcText(e.optString("source", "user"));
        if (!detail.isEmpty() || !source.isEmpty()) {
            String sub = detail.isEmpty() ? source : (detail + (source.isEmpty() ? "" : (" · " + source)));
            TextView t2 = Ui.tv(act, "     " + sub, 10, Ui.LOG_INFO);
            box.addView(t2);
        }
        return box;
    }

    private static String srcText(String s) {
        if ("cron".equals(s)) return "触发源: 定时任务";
        if ("auto".equals(s)) return "触发源: 自动";
        if ("user".equals(s)) return "触发源: 用户操作";
        return "";
    }

    private static String hhmmss(long ms) {
        if (ms <= 0) return "--:--:--";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String t = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND));
        boolean sameDay = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                && c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return t;
        return String.format(java.util.Locale.US, "%02d-%02d ",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)) + t;
    }
}