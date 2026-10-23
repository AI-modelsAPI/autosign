package icu.justwoker.justsign;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ReauthManager 授权事务锁回归（v1.3.2 修复 P0-2）。
 *
 * 旧实现有两个缺陷，本测试各锁一条：
 *   1) 「读-判断-写」三段式：并发时多个线程可同时读到 old == null 并都返回 true
 *      → 同账号并行建两个站点会话（AUTH_SESSION_LIMIT 的成因）。
 *   2) `LOCKS.get(k) == Long.valueOf(now)` 比较 Long 引用：时间戳超出 Long 缓存区间，
 *      判断恒 false → 崩溃/被杀留下的过期锁永远抢不回来（账号被永久锁死）。
 */
public class ReauthManagerLockTest {

    private static final String SITE = "agentrouter-org";
    private static final String ACC = "acc_1";

    @SuppressWarnings("unchecked")
    private static Map<String, Long> locks() throws Exception {
        Field f = ReauthManager.class.getDeclaredField("LOCKS");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(null);
    }

    /** 缺陷 1 回归：8 线程抢同一把锁，每轮只能有一个赢家。 */
    @Test public void concurrentAcquireHasExactlyOneWinner() throws Exception {
        final int threads = 8, rounds = 100;
        for (int round = 0; round < rounds; round++) {
            ReauthManager.release(SITE, ACC);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger wins = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (ReauthManager.acquire(SITE, ACC)) wins.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("并发抢锁未在超时内结束", done.await(20, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertEquals("第 " + round + " 轮：同一账号只允许一个持锁者", 1, wins.get());
        }
        ReauthManager.release(SITE, ACC);
    }

    /** 持锁期间第二次抢占必须失败；释放后可重新抢占。 */
    @Test public void heldLockRejectsSecondAcquire() {
        ReauthManager.release(SITE, ACC);
        assertTrue(ReauthManager.acquire(SITE, ACC));
        assertFalse(ReauthManager.acquire(SITE, ACC));
        assertTrue(ReauthManager.isHeld(SITE, ACC));
        ReauthManager.release(SITE, ACC);
        assertFalse(ReauthManager.isHeld(SITE, ACC));
        assertTrue("释放后应可重新抢占", ReauthManager.acquire(SITE, ACC));
        ReauthManager.release(SITE, ACC);
    }

    /** 缺陷 2 回归：超过 TTL 的残留锁必须能被抢回，且抢回后互斥仍生效。 */
    @Test public void expiredLockIsReclaimable() throws Exception {
        ReauthManager.release(SITE, ACC);
        locks().put(SITE + "|" + ACC, System.currentTimeMillis() - 6 * 60 * 1000L);
        assertTrue("过期锁应可被重新抢占（旧实现此处永久锁死）", ReauthManager.acquire(SITE, ACC));
        assertFalse("抢回后必须重新互斥", ReauthManager.acquire(SITE, ACC));
        ReauthManager.release(SITE, ACC);
        assertFalse(ReauthManager.isHeld(SITE, ACC));
    }

    /** 未过期（TTL 内）的锁不可被抢。 */
    @Test public void freshLockIsNotReclaimable() throws Exception {
        ReauthManager.release(SITE, ACC);
        locks().put(SITE + "|" + ACC, System.currentTimeMillis() - 60 * 1000L);
        assertFalse("TTL 内的锁不得被抢占", ReauthManager.acquire(SITE, ACC));
        assertTrue(ReauthManager.isHeld(SITE, ACC));
        ReauthManager.release(SITE, ACC);
    }

    @Test public void onlyConfirmedOrAbsentMayProceed() {
        assertTrue(ReauthManager.mayProceed(ReauthManager.CONFIRMED));
        assertTrue(ReauthManager.mayProceed(ReauthManager.ABSENT));
        assertFalse(ReauthManager.mayProceed(ReauthManager.UNCONFIRMED));
        assertFalse(ReauthManager.mayProceed(ReauthManager.DENIED));
    }
}
