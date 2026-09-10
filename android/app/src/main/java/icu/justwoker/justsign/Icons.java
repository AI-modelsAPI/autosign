package icu.justwoker.justsign;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Icons（v0.2.1）— 程序化生成的扁平单色图标。
 *
 * 【为什么不用 emoji】emoji 由系统字体渲染，自带多色（🗑🔄⚡📋 各一个颜色），
 *   放在一起像糖果盒；且不同 ROM 字形差异大、缺字形时变豆腐块。
 *   这里用 Canvas 画 24×24 网格的线性图标（stroke 1.9dp、圆头圆角），
 *   颜色由调用方 setTextColor 同款的 color 参数决定 —— 严格单色、扁平、无渐变无阴影。
 *
 * 用法：
 *   Ui.iconView(ctx,"refresh",16,Ui.BLUE)      独立图标（ImageView）
 *   Ui.lead(tv,"trash",13,Ui.RED)              给 TextView 加前置图标
 *
 * 缓存：同名同尺寸同色只画一次（HashMap 缓存 Bitmap）。
 */
public final class Icons {
    private Icons() {}

    private static final java.util.HashMap<String, Bitmap> CACHE = new java.util.HashMap<>();

    public static Drawable d(Context c, String name, int sizeDp, int color) {
        int px = Math.max(4, Ui.dp(c, sizeDp));
        String k = name + "|" + px + "|" + color;
        Bitmap b;
        synchronized (CACHE) {
            b = CACHE.get(k);
            if (b == null) { b = draw(name, px, color); CACHE.put(k, b); }
        }
        BitmapDrawable bd = new BitmapDrawable(c.getResources(), b);
        bd.setBounds(0, 0, px, px);
        return bd;
    }

    public static ImageView view(Context c, String name, int sizeDp, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(d(c, name, sizeDp, color));
        int px = Math.max(4, Ui.dp(c, sizeDp));
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return iv;
    }

    /* ================= 绘制 ================= */

    private static Bitmap draw(String name, int px, int color) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float s = px / 24f;

        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(Math.max(1f, 1.9f * s));
        st.setStrokeCap(Paint.Cap.ROUND);
        st.setStrokeJoin(Paint.Join.ROUND);
        st.setColor(color);

        Paint fl = new Paint(Paint.ANTI_ALIAS_FLAG);
        fl.setStyle(Paint.Style.FILL);
        fl.setColor(color);

        switch (name == null ? "" : name) {

            /* 底栏：签到看板（剪贴板 + 勾） */
            case "board": {
                cv.drawRoundRect(r(s, 4.5f, 4, 19.5f, 21.5f), 2.5f * s, 2.5f * s, st);
                cv.drawRoundRect(r(s, 9, 2, 15, 6), 1.2f * s, 1.2f * s, st);
                Path p = new Path();
                p.moveTo(8.6f * s, 13.4f * s);
                p.lineTo(11.1f * s, 15.9f * s);
                p.lineTo(15.6f * s, 10.6f * s);
                cv.drawPath(p, st);
                break;
            }

            /* 底栏：一键签到（闪电，填充实心更醒目） */
            case "bolt": {
                Path p = new Path();
                p.moveTo(13.2f * s, 2.2f * s);
                p.lineTo(5.2f * s, 13.6f * s);
                p.lineTo(11f * s, 13.6f * s);
                p.lineTo(10.4f * s, 21.8f * s);
                p.lineTo(18.8f * s, 10.2f * s);
                p.lineTo(13f * s, 10.2f * s);
                p.close();
                cv.drawPath(p, fl);
                break;
            }

            /* 刷新（环 + 箭头） */
            case "refresh": {
                RectF ov = r(s, 4, 4, 20, 20);
                cv.drawArc(ov, -55, 285, false, st);
                Path a = new Path();
                a.moveTo(19.4f * s, 4.4f * s);
                a.lineTo(19.4f * s, 9.6f * s);
                a.lineTo(14.2f * s, 9.6f * s);
                cv.drawPath(a, st);
                break;
            }

            /* 设置（三条滑杆，比齿轮更扁平清晰） */
            case "settings": {
                cv.drawLine(3.5f * s, 7f * s, 20.5f * s, 7f * s, st);
                cv.drawLine(3.5f * s, 12f * s, 20.5f * s, 12f * s, st);
                cv.drawLine(3.5f * s, 17f * s, 20.5f * s, 17f * s, st);
                dot(cv, fl, s, 9f, 7f, 2.4f, color);
                dot(cv, fl, s, 15.5f, 12f, 2.4f, color);
                dot(cv, fl, s, 7.5f, 17f, 2.4f, color);
                break;
            }

            /* 日志（列表：小点 + 行） */
            case "log": {
                for (int i = 0; i < 3; i++) {
                    float y = (6.5f + i * 5.5f) * s;
                    cv.drawCircle(5.2f * s, y, 1.15f * s, fl);
                    cv.drawLine(9f * s, y, 19.5f * s, y, st);
                }
                break;
            }

            /* 外链（方框 + 斜箭头） */
            case "link": {
                Path box = new Path();
                box.moveTo(13f * s, 4.5f * s);
                box.lineTo(6f * s, 4.5f * s);
                box.lineTo(6f * s, 19.5f * s);
                box.lineTo(19.5f * s, 19.5f * s);
                box.lineTo(19.5f * s, 12.5f * s);
                cv.drawPath(box, st);
                cv.drawLine(11.5f * s, 12.6f * s, 19.6f * s, 4.5f * s, st);
                Path h = new Path();
                h.moveTo(14.2f * s, 4.5f * s);
                h.lineTo(19.6f * s, 4.5f * s);
                h.lineTo(19.6f * s, 9.9f * s);
                cv.drawPath(h, st);
                break;
            }

            /* 更多（竖排三点） */
            case "more": {
                cv.drawCircle(12f * s, 5.6f * s, 1.5f * s, fl);
                cv.drawCircle(12f * s, 12f * s, 1.5f * s, fl);
                cv.drawCircle(12f * s, 18.4f * s, 1.5f * s, fl);
                break;
            }

            case "check": {
                Path p = new Path();
                p.moveTo(5f * s, 12.8f * s);
                p.lineTo(9.8f * s, 17.6f * s);
                p.lineTo(19f * s, 6.8f * s);
                cv.drawPath(p, st);
                break;
            }

            case "cross": {
                cv.drawLine(6f * s, 6f * s, 18f * s, 18f * s, st);
                cv.drawLine(18f * s, 6f * s, 6f * s, 18f * s, st);
                break;
            }

            case "plus": {
                cv.drawLine(12f * s, 5f * s, 12f * s, 19f * s, st);
                cv.drawLine(5f * s, 12f * s, 19f * s, 12f * s, st);
                break;
            }

            case "chevron": {
                Path p = new Path();
                p.moveTo(9.5f * s, 5.5f * s);
                p.lineTo(16f * s, 12f * s);
                p.lineTo(9.5f * s, 18.5f * s);
                cv.drawPath(p, st);
                break;
            }

            case "back": {
                cv.drawLine(4.5f * s, 12f * s, 19.5f * s, 12f * s, st);
                Path p = new Path();
                p.moveTo(11f * s, 5.5f * s);
                p.lineTo(4.5f * s, 12f * s);
                p.lineTo(11f * s, 18.5f * s);
                cv.drawPath(p, st);
                break;
            }

            case "edit": {
                Path p = new Path();
                p.moveTo(4.5f * s, 19.5f * s);
                p.lineTo(5.6f * s, 15.2f * s);
                p.lineTo(16.2f * s, 4.6f * s);
                p.lineTo(19.4f * s, 7.8f * s);
                p.lineTo(8.8f * s, 18.4f * s);
                p.close();
                cv.drawPath(p, st);
                break;
            }

            case "trash": {
                cv.drawLine(4.5f * s, 7f * s, 19.5f * s, 7f * s, st);
                Path body = new Path();
                body.moveTo(6.6f * s, 7f * s);
                body.lineTo(7.5f * s, 19.6f * s);
                body.lineTo(16.5f * s, 19.6f * s);
                body.lineTo(17.4f * s, 7f * s);
                cv.drawPath(body, st);
                Path lid = new Path();
                lid.moveTo(9.5f * s, 7f * s);
                lid.lineTo(9.9f * s, 4.4f * s);
                lid.lineTo(14.1f * s, 4.4f * s);
                lid.lineTo(14.5f * s, 7f * s);
                cv.drawPath(lid, st);
                break;
            }

            case "user": {
                cv.drawCircle(12f * s, 8.4f * s, 3.7f * s, st);
                RectF ov = r(s, 5f, 13.6f, 19f, 24.4f);
                cv.drawArc(ov, 180, 180, false, st);
                break;
            }

            case "eye": {
                Path p = new Path();
                p.moveTo(2.8f * s, 12f * s);
                p.cubicTo(6.5f * s, 5.6f * s, 17.5f * s, 5.6f * s, 21.2f * s, 12f * s);
                p.cubicTo(17.5f * s, 18.4f * s, 6.5f * s, 18.4f * s, 2.8f * s, 12f * s);
                cv.drawPath(p, st);
                cv.drawCircle(12f * s, 12f * s, 2.6f * s, st);
                break;
            }

            case "eye_off": {
                Path p = new Path();
                p.moveTo(3.2f * s, 12.6f * s);
                p.cubicTo(7f * s, 6.8f * s, 17f * s, 6.8f * s, 20.8f * s, 12.6f * s);
                cv.drawPath(p, st);
                cv.drawCircle(12f * s, 12.8f * s, 2.4f * s, st);
                cv.drawLine(4.6f * s, 19.6f * s, 19.4f * s, 4.8f * s, st);
                break;
            }

            case "clock": {
                cv.drawCircle(12f * s, 12f * s, 8.4f * s, st);
                cv.drawLine(12f * s, 7.4f * s, 12f * s, 12.4f * s, st);
                cv.drawLine(12f * s, 12.4f * s, 15.6f * s, 14.4f * s, st);
                break;
            }

            /* 代理 / 网络端口 */
            case "plug": {
                cv.drawLine(9f * s, 3.2f * s, 9f * s, 7.6f * s, st);
                cv.drawLine(15f * s, 3.2f * s, 15f * s, 7.6f * s, st);
                RectF b = r(s, 6.4f, 7.6f, 17.6f, 14.2f);
                Path body = new Path();
                body.addRoundRect(b, new float[]{ 1.6f * s, 1.6f * s, 1.6f * s, 1.6f * s,
                        5.4f * s, 5.4f * s, 5.4f * s, 5.4f * s }, Path.Direction.CW);
                cv.drawPath(body, st);
                cv.drawLine(12f * s, 14.2f * s, 12f * s, 20.8f * s, st);
                break;
            }

            case "bell": {
                Path p = new Path();
                p.moveTo(6.2f * s, 16.6f * s);
                p.lineTo(6.2f * s, 11.2f * s);
                p.cubicTo(6.2f * s, 7.4f * s, 8.8f * s, 5f * s, 12f * s, 5f * s);
                p.cubicTo(15.2f * s, 5f * s, 17.8f * s, 7.4f * s, 17.8f * s, 11.2f * s);
                p.lineTo(17.8f * s, 16.6f * s);
                p.close();
                cv.drawPath(p, st);
                cv.drawLine(4.4f * s, 16.6f * s, 19.6f * s, 16.6f * s, st);
                RectF m = r(s, 9.8f, 17.4f, 14.2f, 21.6f);
                cv.drawArc(m, 0, 180, false, st);
                break;
            }

            case "globe": {
                cv.drawCircle(12f * s, 12f * s, 8.6f * s, st);
                cv.drawLine(3.4f * s, 12f * s, 20.6f * s, 12f * s, st);
                cv.drawOval(r(s, 7.6f, 3.4f, 16.4f, 20.6f), st);
                break;
            }

            /* 站点 / 服务器 */
            case "server": {
                cv.drawRoundRect(r(s, 3.6f, 4.4f, 20.4f, 10.4f), 1.6f * s, 1.6f * s, st);
                cv.drawRoundRect(r(s, 3.6f, 13.6f, 20.4f, 19.6f), 1.6f * s, 1.6f * s, st);
                cv.drawCircle(7.4f * s, 7.4f * s, 1.15f * s, fl);
                cv.drawCircle(7.4f * s, 16.6f * s, 1.15f * s, fl);
                break;
            }

            case "hourglass": {
                cv.drawLine(6f * s, 4f * s, 18f * s, 4f * s, st);
                cv.drawLine(6f * s, 20f * s, 18f * s, 20f * s, st);
                Path p = new Path();
                p.moveTo(7.4f * s, 4f * s);
                p.lineTo(7.4f * s, 8.4f * s);
                p.lineTo(12f * s, 12f * s);
                p.lineTo(16.6f * s, 8.4f * s);
                p.lineTo(16.6f * s, 4f * s);
                cv.drawPath(p, st);
                Path q = new Path();
                q.moveTo(7.4f * s, 20f * s);
                q.lineTo(7.4f * s, 15.6f * s);
                q.lineTo(12f * s, 12f * s);
                q.lineTo(16.6f * s, 15.6f * s);
                q.lineTo(16.6f * s, 20f * s);
                cv.drawPath(q, st);
                break;
            }

            case "pkg": {
                Path p = new Path();
                p.moveTo(12f * s, 3.4f * s);
                p.lineTo(20.4f * s, 7.8f * s);
                p.lineTo(20.4f * s, 16.2f * s);
                p.lineTo(12f * s, 20.6f * s);
                p.lineTo(3.6f * s, 16.2f * s);
                p.lineTo(3.6f * s, 7.8f * s);
                p.close();
                cv.drawPath(p, st);
                cv.drawLine(3.6f * s, 7.8f * s, 12f * s, 12.2f * s, st);
                cv.drawLine(20.4f * s, 7.8f * s, 12f * s, 12.2f * s, st);
                cv.drawLine(12f * s, 12.2f * s, 12f * s, 20.6f * s, st);
                break;
            }

            case "info": {
                cv.drawCircle(12f * s, 12f * s, 8.6f * s, st);
                cv.drawLine(12f * s, 11f * s, 12f * s, 16.6f * s, st);
                cv.drawCircle(12f * s, 7.6f * s, 1.2f * s, fl);
                break;
            }

            /* 加密 / 安全 */
            case "shield": {
                Path p = new Path();
                p.moveTo(12f * s, 3.2f * s);
                p.lineTo(19.6f * s, 6.2f * s);
                p.lineTo(19.6f * s, 12f * s);
                p.cubicTo(19.6f * s, 16.8f * s, 16.2f * s, 19.6f * s, 12f * s, 20.8f * s);
                p.cubicTo(7.8f * s, 19.6f * s, 4.4f * s, 16.8f * s, 4.4f * s, 12f * s);
                p.lineTo(4.4f * s, 6.2f * s);
                p.close();
                cv.drawPath(p, st);
                Path k = new Path();
                k.moveTo(9f * s, 12.2f * s);
                k.lineTo(11.2f * s, 14.4f * s);
                k.lineTo(15.2f * s, 9.6f * s);
                cv.drawPath(k, st);
                break;
            }

            /* 批量导入（下载入托盘） */
            case "import": {
                cv.drawLine(12f * s, 3.6f * s, 12f * s, 14.4f * s, st);
                Path a = new Path();
                a.moveTo(7.6f * s, 10.4f * s);
                a.lineTo(12f * s, 14.8f * s);
                a.lineTo(16.4f * s, 10.4f * s);
                cv.drawPath(a, st);
                Path tray = new Path();
                tray.moveTo(4.4f * s, 15.6f * s);
                tray.lineTo(4.4f * s, 19.8f * s);
                tray.lineTo(19.6f * s, 19.8f * s);
                tray.lineTo(19.6f * s, 15.6f * s);
                cv.drawPath(tray, st);
                break;
            }

            /* 状态点（实心圆，日志级别用） */
            case "dot": {
                cv.drawCircle(12f * s, 12f * s, 5f * s, fl);
                break;
            }

            /* 空态大图：站点+勾 */
            case "empty": {
                cv.drawCircle(12f * s, 12f * s, 9f * s, st);
                Path p = new Path();
                p.moveTo(7.6f * s, 12.4f * s);
p.lineTo(10.8f * s, 15.6f * s);
                p.lineTo(16.4f * s, 8.8f * s);
                cv.drawPath(p, st);
                break;
            }
            /* v0.6.0：礼物盒（邀请额度划转开关）——扁平单色：盒身+盖+两个蝴蝶结圆点 */
            case "download": {
                /* 版本更新：向下箭头落入托盘。 */
                cv.drawLine(12f * s, 3.5f * s, 12f * s, 14.5f * s, st);
                Path arrow = new Path();
                arrow.moveTo(7.5f * s, 10.5f * s);
                arrow.lineTo(12f * s, 15f * s);
                arrow.lineTo(16.5f * s, 10.5f * s);
                cv.drawPath(arrow, st);
                Path tray = new Path();
                tray.moveTo(4.5f * s, 16f * s);
                tray.lineTo(4.5f * s, 20f * s);
                tray.lineTo(19.5f * s, 20f * s);
                tray.lineTo(19.5f * s, 16f * s);
                cv.drawPath(tray, st);
                break;
            }
            case "penguin": {
                /* QQ 企鹅：单色扁平轮廓，避免 emoji 在不同 ROM 上变成多彩图标。 */
                cv.drawOval(r(s, 6.2f, 3.2f, 17.8f, 20.2f), st);
                cv.drawOval(r(s, 8.2f, 7.2f, 15.8f, 18.5f), st);
                cv.drawCircle(9.5f * s, 8.2f * s, 0.8f * s, fl);
                cv.drawCircle(14.5f * s, 8.2f * s, 0.8f * s, fl);
                Path beak = new Path();
                beak.moveTo(10.2f * s, 10.3f * s);
                beak.lineTo(13.8f * s, 10.3f * s);
                beak.lineTo(12f * s, 12.2f * s);
                beak.close();
                cv.drawPath(beak, fl);
                cv.drawLine(7.4f * s, 19f * s, 4.8f * s, 21f * s, st);
                cv.drawLine(16.6f * s, 19f * s, 19.2f * s, 21f * s, st);
                break;
            }
            case "gift": {
                cv.drawRect(r(s, 4f, 9f, 20f, 19f), fl);
                cv.drawRect(r(s, 3f, 6f, 21f, 9f), fl);
                cv.drawCircle(8.5f * s, 5f * s, 2f * s, fl);
                cv.drawCircle(15.5f * s, 5f * s, 2f * s, fl);
                break;
            }
            /* v0.6.0：复制（两个叠放圆角矩形）——API Key 复制按钮 */
            case "copy": {
                cv.drawRoundRect(r(s, 8f, 3f, 19f, 14f), 2f * s, 2f * s, st);
                cv.drawRoundRect(r(s, 5f, 8f, 16f, 19f), 2f * s, 2f * s, fl);
                break;
            }
            /* v0.6.1：钥匙（API Key 入口）——圆头+齿 */
            case "key": {
                cv.drawCircle(7.5f * s, 8.5f * s, 4f * s, st);
                cv.drawLine(10.5f * s, 11.5f * s, 18.5f * s, 19.5f * s, st);
                cv.drawLine(15.5f * s, 16.5f * s, 18f * s, 14f * s, st);
                cv.drawLine(13.5f * s, 14.5f * s, 16f * s, 12f * s, st);
                break;
            }
            /* v0.6.1：向上箭头（面板收起） */
            case "collapse": {
                cv.drawLine(6f * s, 14f * s, 12f * s, 8f * s, st);
                cv.drawLine(12f * s, 8f * s, 18f * s, 14f * s, st);
                break;
            }

            default:
                break;
        }
        return bmp;
    }

    private static RectF r(float s, float l, float t, float rr, float b) {
        return new RectF(l * s, t * s, rr * s, b * s);
    }

    private static void dot(Canvas cv, Paint fl, float s, float cx, float cy, float rad, int color) {
        /* 滑杆上的旋钮：先用背景色挖空再描边会更精细，这里直接实心即可（单色扁平） */
        cv.drawCircle(cx * s, cy * s, rad * s, fl);
    }
}
