package icu.justwoker.justsign;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
/**
 * Ui（v0.2.1）— 共享 View 工厂 + 扁平单色图标接入。
 *
 * 【v0.2.1 图标改造】彻底移除 emoji 图标（🗑🔄⚡ 各自带颜色，混在一起五颜六色，
 *   且不同 ROM 字形不一、缺字形变豆腐块）。改为 Icons 用 Canvas 画的 24×24 线性图标：
 *   严格单色（跟随文字色）、扁平、无渐变无阴影。
 *   文字带图标统一走 iconText / iconBtn / iconPill（CompoundDrawable，与文字同色同基线）。
 *
 * 【尺寸】统一 dp(ctx,v) 换算。
 */
public final class Ui {
    private Ui() {}

    /* ================= 配色 ================= */
    public static final int BG        = 0xFFF8F9FA;
    public static final int CARD      = 0xFFFFFFFF;
    public static final int CARD_SUB  = 0xFFF9FAFB;
    public static final int LINE      = 0xFFE5E7EB;
    public static final int LINE_SOFT = 0xFFF3F4F6;
    public static final int TXT       = 0xFF111827;
    public static final int TXT2      = 0xFF1F2937;
    public static final int SUB       = 0xFF6B7280;
    public static final int SUB2      = 0xFF9CA3AF;
    public static final int BLUE      = 0xFF2563EB;
    public static final int BLUE_BG   = 0xFFEFF6FF;
    public static final int BLUE_DEEP = 0xFF1E40AF;
    public static final int GREEN     = 0xFF059669;
    public static final int GREEN_D   = 0xFF047857;
    public static final int GREEN_BG  = 0xFFD1FAE5;
    public static final int GREEN_BG2 = 0xFFF0FDF4;
    public static final int ORANGE    = 0xFFD97706;
    public static final int AMBER_BG  = 0xFFFEF3C7;
    public static final int RED       = 0xFFDC2626;
    public static final int RED_D     = 0xFFB91C1C;
    public static final int RED_BG    = 0xFFFEE2E2;
    public static final int RED_BG2   = 0xFFFEF2F2;
    /* 日志浮窗（深色终端风） */
    public static final int LOG_BG    = 0xFF1E293B;
    public static final int LOG_BAR   = 0xFF0F172A;
    public static final int LOG_LINE  = 0xFF334155;
    public static final int LOG_OK    = 0xFF4ADE80;
    public static final int LOG_ERR   = 0xFFF87171;
    public static final int LOG_INFO  = 0xFF94A3B8;
    public static final int LOG_TXT   = 0xFFF8FAFC;
    public static final int SCRIM     = 0x66000000;

    /* ================= 尺寸 ================= */

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }


    /* ================= 背景 ================= */

    public static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable roundStroke(int color, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** 左侧直角、右侧圆角（左滑删除底板用） */
    public static GradientDrawable roundRight(int color, int rPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{0, 0, rPx, rPx, rPx, rPx, 0, 0});
        return g;
    }

    public static void styleDialog(android.app.AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(R.drawable.dialog_background);
        android.view.View decor = window.getDecorView();
        decor.setPadding(dp(dialog.getContext(), 6), dp(dialog.getContext(), 4),
                dp(dialog.getContext(), 6), dp(dialog.getContext(), 4));
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = dialog.findViewById(titleId);
        if (title != null) { title.setTextColor(TXT); title.setTextSize(17); }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextColor(TXT2);
            message.setLineSpacing(dp(dialog.getContext(), 3), 1f);
        }
        for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button b = dialog.getButton(which);
            if (b != null) {
                b.setTextColor(BLUE);
                b.setBackground(round(BLUE_BG, dp(dialog.getContext(), 8)));
                b.setPadding(dp(dialog.getContext(), 12), dp(dialog.getContext(), 6),
                        dp(dialog.getContext(), 12), dp(dialog.getContext(), 6));
            }
        }
    }

    /* ================= 文本 ================= */

    public static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text == null ? "" : text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView tv(Context c, String text, float sp, int color) {
        return tv(c, text, sp, color, false);
    }

    /** 胶囊标签（无点击） */
    public static TextView pill(Context c, String text, float sp, int fg, int bg) {
        TextView t = tv(c, text, sp, fg, true);
        t.setBackground(round(bg, dp(c, 10)));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 可点击按钮（浅底） */
    public static TextView btn(Context c, String text, float sp, int fg, int bg, int hPadDp, int vPadDp) {
        TextView t = tv(c, text, sp, fg, true);
        t.setBackground(round(bg, dp(c, 6)));
        t.setPadding(dp(c, hPadDp), dp(c, vPadDp), dp(c, hPadDp), dp(c, vPadDp));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 纯文字按钮（无底） */
    public static TextView flat(Context c, String text, float sp, int fg) {
        TextView t = tv(c, text, sp, fg, true);
        t.setPadding(dp(c, 6), dp(c, 5), dp(c, 6), dp(c, 5));
        t.setClickable(true);
        t.setFocusable(true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /* ================= 图标（Icons 绘制，单色扁平） ================= */

    /** 兼容旧调用；图标改为程序化绘制后无需初始化，保留空实现 */
    public static void initIcons(Context c) { /* no-op since v0.2.1 */ }

    /** 纯图标（无文字）ImageView */
    public static android.widget.ImageView icon(Context c, String name, int sizeDp, int color) {
        return Icons.view(c, name, sizeDp, color);
    }

    /** 纯图标可点击按钮（无底，触控区 ≥ 32dp） */
    public static View iconBtn(Context c, String name, int sizeDp, int color, int padDp) {
        LinearLayout box = row(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp));
        box.setClickable(true);
        box.setFocusable(true);
        box.addView(Icons.view(c, name, sizeDp, color));
        return box;
    }

    /** 文字 + 前置图标（CompoundDrawable，与文字同色同基线） */
    public static TextView iconText(Context c, String name, String text, float sp, int color, boolean bold) {
        TextView t = tv(c, text, sp, color, bold);
        int size = Math.round(sp * 1.15f);
        t.setCompoundDrawablesWithIntrinsicBounds(Icons.d(c, name, size, color), null, null, null);
        t.setCompoundDrawablePadding(dp(c, 5));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** 图标 + 文字的浅底按钮 */
    public static TextView iconBtnText(Context c, String name, String text, float sp,
                                       int fg, int bg, int hPadDp, int vPadDp) {
        TextView t = iconText(c, name, text, sp, fg, true);
        t.setBackground(round(bg, dp(c, 6)));
        t.setPadding(dp(c, hPadDp), dp(c, vPadDp), dp(c, hPadDp), dp(c, vPadDp));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 图标 + 文字的胶囊标签 */
    public static TextView iconPill(Context c, String name, String text, float sp, int fg, int bg) {
        TextView t = iconText(c, name, text, sp, fg, true);
        t.setBackground(round(bg, dp(c, 10)));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    /** 文字 + 后置图标（如「日志 ›」「站点名 ↗」） */
    public static TextView textIcon(Context c, String text, String name, float sp, int color, boolean bold) {
        TextView t = tv(c, text, sp, color, bold);
        int size = Math.round(sp * 1.1f);
        t.setCompoundDrawablesWithIntrinsicBounds(null, null, Icons.d(c, name, size, color), null);
        t.setCompoundDrawablePadding(dp(c, 3));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** 把已存在 TextView 的前置图标换掉（进度态切换用） */
    public static void setLead(TextView t, String name, float sp, int color) {
        if (t == null) return;
        int size = Math.round(sp * 1.15f);
        t.setCompoundDrawablesWithIntrinsicBounds(
                Icons.d(t.getContext(), name, size, color), null, null, null);
        t.setCompoundDrawablePadding(dp(t.getContext(), 5));
    }

    /* ================= 容器 ================= */

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 横向弹簧 */
    public static View spring(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    /** 固定宽度间隔 */
    public static View gapW(Context c, int wDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), 1));
        return v;
    }

    /** 固定高度间隔 */
    public static View gapH(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    /** 分割线 */
    public static View divider(Context c, int color, int leftPadDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.5f)));
        lp.leftMargin = dp(c, leftPadDp);
        v.setLayoutParams(lp);
        return v;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.addView(child);
        return s;
    }

    /* ================= 表单 ================= */

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(TXT2);
        e.setHintTextColor(SUB2);
        e.setSingleLine(true);
        e.setBackground(roundStroke(CARD_SUB, dp(c, 6), Math.max(1, dp(c, 1)), LINE));
        e.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        return e;
    }

    /** 表单字段（标签 + 输入框），返回容器；输入框通过 out[0] 回传 */
    public static LinearLayout field(Context c, String label, String hint, EditText[] out) {
        LinearLayout box = col(c);
        box.addView(tv(c, label, 11, SUB, true));
        EditText e = input(c, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 5);
        box.addView(e, lp);
        if (out != null && out.length > 0) out[0] = e;
        return box;
    }

    /* ================= 金额格式 ================= */

    public static String usd(double d) {
        if (d >= 1000) return String.format(java.util.Locale.US, "%.0f", d);
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** 浅色版（用于 chip 背景） */
    public static int tint(int color) { return alpha(color, 0x18); }

    public static int white() { return Color.WHITE; }
}