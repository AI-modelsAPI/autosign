package icu.justwoker.justsign;

import android.os.Handler;

/**
 * 条件轮询器（v1.1.6）——「等待」一律由可靠参考对象驱动，不写死时长。
 *
 * 与 Thread.sleep / postDelayed 的区别：
 *   - postDelayed(400) 猜的是「大概要 400ms」，慢页面猜短了、快页面白等；
 *   - until(...) 问的是「那个事实现在成立了吗」，成立就走，不成立就看下一眼。
 *
 * TICK_MS 只是「多久看一眼」的探测节拍，不决定「等多久」；
 * capMs 只是安全网，正常路径永远由条件先满足而结束。
 */
public final class Waiters {

    /** 探测节拍：仅决定多久看一眼参考对象，不决定等待时长。 */
    public static final long TICK_MS = 100L;

    /** 参考对象：一个「现在是否成立」的可观测事实。 */
    public interface Cond { boolean ok(); }

    public static void until(Handler h, Cond cond, long capMs, Runnable onReady, Runnable onFail) {
        until(h, TICK_MS, cond, capMs, onReady, onFail);
    }

    /**
     * 轮询 cond 直到成立 → onReady；到 capMs 仍未成立 → onFail。
     * cond 抛异常按「未成立」处理，绝不让探测本身炸掉调用方。
     */
    public static void until(Handler h, long tickMs, Cond cond, long capMs,
                             Runnable onReady, Runnable onFail) {
        if (h == null || cond == null) { run(onFail); return; }
        final long deadline = System.currentTimeMillis() + Math.max(0L, capMs);
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            boolean ok;
            try { ok = cond.ok(); } catch (Throwable t) { ok = false; }
            if (ok) { run(onReady); return; }
            if (System.currentTimeMillis() >= deadline) { run(onFail); return; }
            h.postDelayed(tick[0], Math.max(16L, tickMs));
        };
        h.postDelayed(tick[0], Math.max(16L, tickMs));
    }

    /** 轮询到条件成立只执行一次；超时什么也不做（用于「等页面就绪」这类可省略的等待）。 */
    public static void untilQuiet(Handler h, Cond cond, long capMs, Runnable onReady) {
        until(h, TICK_MS, cond, capMs, onReady, null);
    }

    private static void run(Runnable r) {
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) {}
    }
}
