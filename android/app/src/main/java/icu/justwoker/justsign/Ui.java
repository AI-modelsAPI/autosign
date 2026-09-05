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
 * Ui（v0.2.0）— 共享 View 工厂 + 双套图标集。
 *
 * 【图标双套方案】部分定制 ROM 字库缺 emoji 字形（渲染成方框豆腐块）。
 *   启动时用 Paint.hasGlyph 探测代表字符（🗑 U+1F5D1），缺失则整体切到几何符号集。
 *   设置里另留手动开关（uiPrefs.geometricIcons）覆盖自动判定。
 *
 * 【尺寸】本项目历史代码全部用 px 直写（如 setPadding(36,32,36,28)），
 *   为保持一致并避免混用，这里提供 dp(ctx,int) 统一换算，新代码一律走它。
 */
public final class Ui {
    private Ui() {}

    /* ================= 配色（gemini 定稿） ================= */
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

    /* ================= 图标集 ================= */

    /** true = 用几何符号集（设备缺 emoji 字形，或用户手动开启） */
    private static Boolean geometric = null;

    public static void initIcons(Context c) {
        boolean forced = false;
        try {
            forced = new Store(c).uiPref("geometricIcons", false);
        } catch (Exception ignored) {}
        if (forced) { geometric = true; return; }
        boolean ok = true;
        try {
            Paint p = new Paint();
            /* 代表性 emoji：🗑 与 🔄，任一缺失就整体降级 */
            ok = p.hasGlyph("\uD83D\uDDD1") && p.hasGlyph("\uD83D\uDD04");
        } catch (Throwable t) { ok = false; }
        geometric = !ok;
    }

    public static boolean isGeometric() { return geometric != null && geometric; }

    /** 图标查表：emoji 主选 / 几何备选（用途名 → 字符） */
    public static String ic(String name) {
        boolean g = isGeometric();
        switch (name) {
            case "tab_board":   return g ? "\u2611"  : "\uD83D\uDCCB"; // ☑ / 📋
            case "tab_checkin": return g ? "\u25B6"  : "\u26A1";       // ▶ / ⚡
            case "tab_refresh": return g ? "\u27F3"  : "\uD83D\uDD04"; // ⟳ / 🔄
            case "tab_log":     return g ? "\u2261"  : "\uD83D\uDCDC"; // ≡ / 📜
            case "tab_settings":return g ? "\u25C8"  : "\u2699";       // ◈ / ⚙
            case "link":        return g ? "\u2794"  : "\u2197";       // ➔ / ↗
            case "more":        return g ? "\u22EE"  : "\u22EF";       // ⋮ / ⋯
            case "check":       return "\u2713";                        // ✓（全设备可用）
            case "cross":       return "\u2715";                        // ✕
            case "edit":        return g ? "\u270E"  : "\u270F";       // ✎ / ✏
            case "trash":       return g ? "\u2715"  : "\uD83D\uDDD1"; // ✕ / 🗑
            case "user":        return g ? "\u03A9"  : "\uD83D\uDC64"; // Ω / 👤
            case "eye":         return g ? "\u25CE"  : "\uD83D\uDC41"; // ◎ / 👁
            case "eye_off":     return g ? "\u2298"  : "\uD83D\uDD76"; // ⊘ / 🕶
            case "clock":       return g ? "\u25F7"  : "\u23F0";       // ◷ / ⏰
            case "plug":        return g ? "\u260D"  : "\uD83D\uDD0C"; // ☍ / 🔌
            case "bell":        return g ? "\u25CB"  : "\uD83D\uDD14"; // ○ / 🔔
            case "globe":       return g ? "\u25C9"  : "\uD83C\uDF10"; // ◉ / 🌐
            case "back":        return g ? "\u25C0"  : "\u2190";       // ◀ / ←
            case "plus":        return "\uFF0B";                        // ＋
            case "chevron":     return "\u203A";                        // ›
            case "dot_ok":      return g ? "\u25CF"  : "\uD83D\uDFE2"; // ● / 🟢
            case "dot_err":     return g ? "\u25A0"  : "\uD83D\uDD34"; // ■ / 🔴
            case "dot_info":    return g ? "\u25C6"  : "\uD83D\uDD35"; // ◆ / 🔵
            case "hourglass":   return g ? "\u25F4"  : "\u23F3";       // ◴ / ⏳
            case "pkg":         return g ? "\u25A3"  : "\uD83D\uDCE6"; // ▣ / 📦
            case "info":        return g ? "\u24D8"  : "\u2139";       // ⓘ / ℹ
            default:            return "";
        }
    }

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