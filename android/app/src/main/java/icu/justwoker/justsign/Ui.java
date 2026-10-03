package icu.justwoker.justsign;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Ui — 统一设计系统与界面组件工厂（v1.4.0 现代美学重构）。
 *
 * 设计语言：
 *   - 现代自然质感（Modern Neo-Slate / Indigo Palette）
 *   - 优雅圆角体系（8dp 小组件 / 12dp 中卡片 / 16dp 大卡片 / 22dp 胶囊）
 *   - 触觉反馈系统（基于原生 RippleDrawable 的平滑涟漪）
 *   - 微对比度层次（通过 Slate-900 / Slate-500 搭配柔和背景形成高级视觉秩序）
 */
public final class Ui {
    private Ui() {}

    /* ================= 调色板（洋气高质感现代色系） ================= */
    public static final int BG        = 0xFFF8FAFC; // 柔和高级冷灰白 (Slate 50)
    public static final int CARD      = 0xFFFFFFFF; // 纯白高光卡片
    public static final int CARD_SUB  = 0xFFF8FAFC; // 微灰层次底板
    public static final int LINE      = 0xFFE2E8F0; // 精细边缘分割线 (Slate 200)
    public static final int LINE_SOFT = 0xFFF1F5F9; // 极淡轻柔内部分割线 (Slate 100)

    public static final int TXT       = 0xFF0F172A; // 深邃 Slate 900，现代感主文本
    public static final int TXT2      = 0xFF334155; // 中度深色 Slate 700，次级标题与正文
    public static final int SUB       = 0xFF64748B; // 沉稳 Slate 500，辅助说明与标签
    public static final int SUB2      = 0xFF94A3B8; // 淡雅 Slate 400，弱化提示与未激活状态

    public static final int BLUE      = 0xFF3B82F6; // 电光蓝主色 (Blue 500)
    public static final int BLUE_BG   = 0xFFEFF6FF; // 极浅冰蓝背景
    public static final int BLUE_DEEP = 0xFF1D4ED8; // 深度品牌蓝

    public static final int INDIGO    = 0xFF4F46E5; // 雅致曜紫 (Indigo 600)
    public static final int INDIGO_BG = 0xFFEEF2FF;

    public static final int GREEN     = 0xFF10B981; // 翡翠绿 (Emerald 500)，资产与成功的积极感
    public static final int GREEN_D   = 0xFF059669; // 沉稳墨绿
    public static final int GREEN_BG  = 0xFFD1FAE5;
    public static final int GREEN_BG2 = 0xFFECFDF5;

    public static final int ORANGE    = 0xFFF59E0B; // 暖金琥珀 (Amber 500)
    public static final int AMBER_BG  = 0xFFFEF3C7;

    public static final int RED       = 0xFFEF4444; // 珊瑚红 (Rose/Red 500)
    public static final int RED_D     = 0xFFDC2626;
    public static final int RED_BG    = 0xFFFEE2E2;
    public static final int RED_BG2   = 0xFFFEF2F2;

    /* 日志浮窗（暗夜极客终端风） */
    public static final int LOG_BG    = 0xFF0F172A; // 极深邃板岩黑
    public static final int LOG_BAR   = 0xFF020617;
    public static final int LOG_LINE  = 0xFF1E293B;
    public static final int LOG_OK    = 0xFF34D399;
    public static final int LOG_ERR   = 0xFFF87171;
    public static final int LOG_INFO  = 0xFF94A3B8;
    public static final int LOG_TXT   = 0xFFF8FAFC;
    public static final int SCRIM     = 0x77000000;

    /* ================= 规格 ================= */
    public static final int DIALOG_PAD = 20;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /* ================= 涟漪与背景工厂 ================= */

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

    public static GradientDrawable roundRight(int color, int rPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{0, 0, rPx, rPx, rPx, rPx, 0, 0});
        return g;
    }

    /** 创建带水波纹点击反馈的现代卡片/按钮背景 */
    public static Drawable ripple(int normalColor, int rippleColor, int radiusPx) {
        GradientDrawable content = round(normalColor, radiusPx);
        GradientDrawable mask = round(Color.WHITE, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /** 创建带细边框与水波纹的交互背景 */
    public static Drawable rippleStroke(int normalColor, int rippleColor, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable content = roundStroke(normalColor, radiusPx, strokePx, strokeColor);
        GradientDrawable mask = round(Color.WHITE, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /* ================= 文本与标签 ================= */

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

    /** 胶囊标签（轻盈精致圆润感） */
    public static TextView pill(Context c, String text, float sp, int fg, int bg) {
        TextView t = tv(c, text, sp, fg, true);
        t.setBackground(round(bg, dp(c, 10)));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 胶囊按钮（带微波反馈） */
    public static TextView btn(Context c, String text, float sp, int fg, int bg, int hPadDp, int vPadDp) {
        TextView t = tv(c, text, sp, fg, true);
        int rippleColor = alpha(fg, 0x22);
        t.setBackground(ripple(bg, rippleColor, dp(c, 8)));
        t.setPadding(dp(c, hPadDp), dp(c, vPadDp), dp(c, hPadDp), dp(c, vPadDp));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 幽灵次级按钮（边框款） */
    public static TextView outlineBtn(Context c, String text, float sp, int fg, int strokeColor, int hPadDp, int vPadDp) {
        TextView t = tv(c, text, sp, fg, true);
        int rippleColor = alpha(fg, 0x18);
        t.setBackground(rippleStroke(Color.TRANSPARENT, rippleColor, dp(c, 8), Math.max(1, dp(c, 1)), strokeColor));
        t.setPadding(dp(c, hPadDp), dp(c, vPadDp), dp(c, hPadDp), dp(c, vPadDp));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 纯文字透明按钮 */
    public static TextView flat(Context c, String text, float sp, int fg) {
        TextView t = tv(c, text, sp, fg, true);
        t.setBackground(ripple(Color.TRANSPARENT, alpha(fg, 0x18), dp(c, 6)));
        t.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), dp(c, 5));
        t.setClickable(true);
        t.setFocusable(true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /* ================= 图标与交互 ================= */

    public static android.widget.ImageView icon(Context c, String name, int sizeDp, int color) {
        return Icons.view(c, name, sizeDp, color);
    }

    /** 纯图标圆形微交互按钮 */
    public static View iconBtn(Context c, String name, int sizeDp, int color, int padDp) {
        LinearLayout box = row(c);
        box.setGravity(Gravity.CENTER);
        int r = dp(c, 16);
        box.setBackground(ripple(Color.TRANSPARENT, alpha(color, 0x1A), r));
        box.setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp));
        box.setClickable(true);
        box.setFocusable(true);
        box.addView(Icons.view(c, name, sizeDp, color));
        return box;
    }

    /** 文字 + 前置图标（同色矢量） */
    public static TextView iconText(Context c, String name, String text, float sp, int color, boolean bold) {
        TextView t = tv(c, text, sp, color, bold);
        int size = Math.round(sp * 1.15f);
        t.setCompoundDrawablesWithIntrinsicBounds(Icons.d(c, name, size, color), null, null, null);
        t.setCompoundDrawablePadding(dp(c, 6));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** 图标 + 文字胶囊按钮（带水波纹） */
    public static TextView iconBtnText(Context c, String name, String text, float sp,
                                       int fg, int bg, int hPadDp, int vPadDp) {
        TextView t = iconText(c, name, text, sp, fg, true);
        t.setBackground(ripple(bg, alpha(fg, 0x22), dp(c, 8)));
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

    /** 文字 + 后置图标 */
    public static TextView textIcon(Context c, String text, String name, float sp, int color, boolean bold) {
        TextView t = tv(c, text, sp, color, bold);
        int size = Math.round(sp * 1.1f);
        t.setCompoundDrawablesWithIntrinsicBounds(null, null, Icons.d(c, name, size, color), null);
        t.setCompoundDrawablePadding(dp(c, 4));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    public static void setLead(TextView t, String name, float sp, int color) {
        if (t == null) return;
        int size = Math.round(sp * 1.15f);
        t.setCompoundDrawablesWithIntrinsicBounds(
                Icons.d(t.getContext(), name, size, color), null, null, null);
        t.setCompoundDrawablePadding(dp(t.getContext(), 6));
    }

    /* ================= 布局容器 ================= */

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

    public static View spring(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    public static View gapW(Context c, int wDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), 1));
        return v;
    }

    public static View gapH(Context c, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, hDp)));
        return v;
    }

    public static View divider(Context c, int color, int leftPadDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.6f)));
        lp.leftMargin = dp(c, leftPadDp);
        v.setLayoutParams(lp);
        return v;
    }

    public static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        s.setVerticalScrollBarEnabled(false);
        s.addView(child);
        return s;
    }

    /* ================= 现代输入表单 ================= */

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(TXT);
        e.setHintTextColor(SUB2);
        e.setSingleLine(true);
        e.setBackground(roundStroke(CARD_SUB, dp(c, 8), Math.max(1, dp(c, 1)), LINE));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    public static LinearLayout field(Context c, String label, String hint, EditText[] out) {
        LinearLayout box = col(c);
        box.addView(tv(c, label, 12, SUB, true));
        EditText e = input(c, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 6);
        box.addView(e, lp);
        if (out != null && out.length > 0) out[0] = e;
        return box;
    }

    /* ================= 金额格式与色彩助手 ================= */

    public static String usd(double d) {
        if (d >= 1000) return String.format(java.util.Locale.US, "%.0f", d);
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public static int tint(int color) { return alpha(color, 0x18); }

    public static int white() { return Color.WHITE; }
}