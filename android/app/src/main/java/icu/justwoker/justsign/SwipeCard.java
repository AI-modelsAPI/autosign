package icu.justwoker.justsign;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SwipeCard（v0.2.0）— 手写左右滑手势容器（不引第三方库）。
 *
 * 结构（三层 FrameLayout）：
 *   底层左：绿色刷新提示板（右滑时从左侧露出）
 *   底层右：红色删除按钮板（左滑时从右侧露出，宽 80dp，点它才真删）
 *   顶层  ：卡片本体（随手指水平位移）
 *
 * 手势规格（gemini 定稿）：
 *   右滑 ≥ 80dp 松手 → 触发刷新回调，卡片回弹
 *   左滑 ≥ 80dp 松手 → 停靠在 -80dp，露出删除按钮
 *   左滑 <  40dp 松手 → 自动归位
 *   超阈值阻尼 0.4；回弹 180ms DecelerateInterpolator
 *
 * 关键实现点：
 *   - onInterceptTouchEvent 里只在「横向位移 > 纵向位移且超过 touchSlop」时才拦截，
 *     否则交给父 ScrollView，避免上下滚动被吃掉。
 *   - 已停靠（露出删除）状态下点卡片本体 → 归位，不触发卡片内的点击。
 */
public class SwipeCard extends FrameLayout {

    public interface Listener {
        void onRefresh();
        void onDelete();
    }

    private final int thresholdPx;      // 80dp
    private final int backPx;           // 40dp 归位阈值
    private final int actionWidthPx;    // 80dp 删除板宽度
    private final int touchSlop;

    private final View leftBoard;       // 刷新提示（右滑露出）
    private final View rightBoard;      // 删除按钮（左滑露出）
    private View card;                  // 卡片本体
    private Listener listener;

    private float downX, downY, lastX;
    private boolean dragging = false;
    private boolean docked = false;     // 是否停靠在露出删除按钮的位置
    private boolean armedRefresh = false;

    public SwipeCard(Context c) {
        super(c);
        thresholdPx = Ui.dp(c, 56);         // 原 80dp 太苛刻：滑一段距离未到阈值就弹回，很难操作
        backPx = Ui.dp(c, 28);              // 归位阈值同步放低
        actionWidthPx = Ui.dp(c, 80);       // 删除板宽度不变（停靠位）
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        int r = Ui.dp(c, 12);

        /* ---- 底层左：右滑刷新提示 ---- */
        LinearLayout lb = Ui.row(c);
        lb.setBackground(Ui.round(Ui.GREEN_BG2, r));
        lb.setPadding(Ui.dp(c, 16), 0, 0, 0);
        TextView lt = Ui.iconText(c, "refresh", "刷新数据", 13, Ui.GREEN, true);
        lb.addView(lt);
        leftBoard = lb;
        addView(leftBoard, new LayoutParams(-1, -1));

        /* ---- 底层右：左滑删除按钮 ---- */
        LinearLayout rbWrap = Ui.row(c);
        rbWrap.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout del = Ui.col(c);
        del.setGravity(android.view.Gravity.CENTER);
        del.setBackground(Ui.roundRight(Ui.RED, r));
        del.setClickable(true);
        del.addView(Ui.icon(c, "trash", 19, Ui.white()));
        del.addView(Ui.tv(c, "删除", 11, Ui.white(), true), lpTop(c, 3));
        del.setOnClickListener(v -> { if (listener != null) listener.onDelete(); });
        rbWrap.addView(del, new LinearLayout.LayoutParams(actionWidthPx, -1));
        rightBoard = rbWrap;
        addView(rightBoard, new LayoutParams(-1, -1));

        setBoardsVisible(false, false);
    }

    private static LinearLayout.LayoutParams lpTop(Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(c, dp);
        return lp;
    }

    /** 装入卡片本体（必须最后添加，保证在最上层） */
    public void setCard(View v) {
        if (card != null) removeView(card);
        card = v;
        addView(card, new LayoutParams(-1, -2));
    }

    public void setListener(Listener l) { this.listener = l; }

    private void setBoardsVisible(boolean left, boolean right) {
        leftBoard.setVisibility(left ? VISIBLE : INVISIBLE);
        rightBoard.setVisibility(right ? VISIBLE : INVISIBLE);
    }

    /* ================= 手势 ================= */

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); lastX = downX;
                dragging = false;
                armedRefresh = false;
                return false;                       // DOWN 不拦截，让子 View 有机会响应点击
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                /* 1.15 倍：原 1.4 倍对斜向滑动太苛刻，稍微带点纵向就永久放弃，
                 * 表现为「滑一段就弹回原位」。拦截后卡死跟随手指。 */
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                    dragging = true;
                    lastX = e.getX();
                    return true;                    // 判定为横滑，开始拦截
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (card == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); lastX = downX;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!dragging) {
                    float dy = e.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.15f) dragging = true;
                    else return true;
                }
                float base = docked ? -actionWidthPx : 0;
                float target = base + dx;
                /* 超过阈值加阻尼 0.4 */
                if (target > thresholdPx) target = thresholdPx + (target - thresholdPx) * 0.4f;
                if (target < -actionWidthPx) target = -actionWidthPx + (target + actionWidthPx) * 0.4f;
                card.setTranslationX(target);
                setBoardsVisible(target > 0, target < 0);
                armedRefresh = target >= thresholdPx;
                /* 到达刷新阈值时提示文案变化 */
                if (leftBoard instanceof LinearLayout) {
                    View t = ((LinearLayout) leftBoard).getChildAt(0);
                    if (t instanceof TextView) {
                        TextView tv = (TextView) t;
                        tv.setText(armedRefresh ? "松开立即刷新" : "刷新数据");
                        Ui.setLead(tv, armedRefresh ? "check" : "refresh", 13, Ui.GREEN);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float tx = card.getTranslationX();
                boolean wasDragging = dragging;
                dragging = false;

                if (!wasDragging) {
                    /* 未拖动：若处于停靠态，点卡片本体 = 归位 */
                    if (docked) { animateTo(0); docked = false; return true; }
                    return false;
                }
                if (tx >= thresholdPx) {                    // 右滑达标 → 刷新
                    animateTo(0);
                    docked = false;
                    if (listener != null) listener.onRefresh();
                } else if (tx <= -thresholdPx) {            // 左滑达标 → 停靠露删除
                    animateTo(-actionWidthPx);
                    docked = true;
                } else if (docked && tx <= -backPx) {       // 已停靠且未拉够回程 → 保持停靠
                    animateTo(-actionWidthPx);
                } else {                                    // 其余 → 归位
                    animateTo(0);
                    docked = false;
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void animateTo(float x) {
        card.animate().cancel();
        card.animate()
                .translationX(x)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> setBoardsVisible(x > 0, x < 0))
                .start();
    }

    /** 外部强制归位（例如刷新完成后重绘列表） */
    public void reset() {
        docked = false;
        if (card != null) card.setTranslationX(0);
        setBoardsVisible(false, false);
    }
}