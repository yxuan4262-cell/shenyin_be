package com.tongji.counter.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第七阶段第三课动手练习：把「Redisson 分布式锁」的三个核心语义变成可以断言的数字——
 * 全程<b>不连 Redis、不启 Spring</b>。
 *
 * <p><b>为什么能纯单元测试</b>：Redisson 锁的本质，说到底是「Redis 里一条带 TTL 的共享记录
 * + 一段原子执行的 Lua」。我们要验证的是这条记录的<b>并发语义</b>，和 Redis 本身无关。于是：</p>
 * <ul>
 *   <li>用一个所有线程共享的 {@link ConcurrentHashMap} 当「那个所有实例都连的 Redis」；</li>
 *   <li>用 {@code map.compute(...)} 的「单 key 原子」特性，模拟 Redis 单线程执行 Lua 的原子性；</li>
 *   <li>用一个后台 {@link ScheduledExecutorService} 每 TTL/3 拉满一次过期时刻，模拟<b>看门狗续约</b>；</li>
 *   <li>用一个 {@code alive} 集合模拟「持有者进程是否还活着」——把某持有者移出集合 = 它崩溃了，
 *       看门狗随 JVM 一起消失、不再为它续约。</li>
 * </ul>
 *
 * <p>与项目 {@code CounterServiceImpl.getCounts} 的对应关系：这里的 {@link MiniRedisson#tryAcquire}
 * 就是 {@code lock.tryLock(0L, MILLISECONDS)}（L172），{@link MiniRedisson#release} 就是 finally 里的
 * {@code lock.unlock()}（L210），而「只有 {@code locked==true} 才 release」正是 L208 那条纪律。</p>
 */
class DistributedLockSemanticsTest {

    // ==================================================================
    //  迷你 Redisson：一个共享 map = Redis，一个后台线程 = 看门狗
    // ==================================================================

    /** Redis 里那条锁记录（HASH + TTL 的极简版）。 */
    static final class LockRecord {
        final String owner;          // 谁持有（对应 Redisson 的 {clientId}:{threadId}）
        int holdCount;               // 重入次数
        volatile long expireAt;      // 绝对过期时刻（对应 key 的 TTL 到期时间点）

        LockRecord(String owner, int holdCount, long expireAt) {
            this.owner = owner;
            this.holdCount = holdCount;
            this.expireAt = expireAt;
        }
    }

    /**
     * 迷你 Redisson 客户端。一个实例 = 一个「Redis + 看门狗」；
     * 不同线程用不同的 owner 字符串来代表「不同实例/不同线程」在争同一把锁。
     */
    static final class MiniRedisson implements AutoCloseable {
        /** 这就是「所有实例都连着的那个 Redis」——锁状态存在这里，天然跨线程共享。 */
        final ConcurrentHashMap<String, LockRecord> store = new ConcurrentHashMap<>();
        /** 还活着的持有者。把某 owner 移出 = 模拟它进程崩溃。 */
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        final long ttlMillis;                 // 对应 lockWatchdogTimeout（项目里 30s，这里缩短到几百毫秒）
        final ScheduledExecutorService watchdog;

        MiniRedisson(long ttlMillis) {
            this.ttlMillis = ttlMillis;
            this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mini-watchdog");
                t.setDaemon(true);            // 守护线程，别拖住 JVM 退出
                return t;
            });
            // ★ 第三课 Q2 的答案落地：续约间隔 = TTL/3，给「单次续约丢失/延迟」留出 2 次重试冗余。
            long period = Math.max(1, ttlMillis / 3);
            this.watchdog.scheduleAtFixedRate(this::renewAll, period, period, TimeUnit.MILLISECONDS);
        }

        /** 看门狗：把所有「持有者仍活着」的锁 TTL 拉满。持有者崩溃后不再续约，锁自然走向过期。 */
        private void renewAll() {
            long now = System.currentTimeMillis();
            for (String k : store.keySet()) {
                store.computeIfPresent(k, (key, rec) -> {
                    if (alive.contains(rec.owner)) {
                        rec.expireAt = now + ttlMillis;   // 续约：拉满到 now + TTL
                    }
                    return rec;
                });
            }
        }

        /**
         * 原子加锁，对应 {@code lock.tryLock(0L, MILLISECONDS)}：只试一次，拿不到立刻 false，绝不排队。
         * {@code compute} 对同一个 key 是原子的 —— 这正是「Lua 在单线程 Redis 里原子执行」的替身。
         */
        boolean tryAcquire(String key, String owner) {
            long now = System.currentTimeMillis();
            boolean[] ok = {false};
            store.compute(key, (k, rec) -> {
                if (rec == null || rec.expireAt <= now) {
                    // key 不存在，或已过期（持有者崩溃后 TTL 到期）→ 我抢到
                    ok[0] = true;
                    alive.add(owner);
                    return new LockRecord(owner, 1, now + ttlMillis);
                }
                if (rec.owner.equals(owner)) {
                    rec.holdCount++;                       // 重入
                    rec.expireAt = now + ttlMillis;
                    ok[0] = true;
                    return rec;
                }
                ok[0] = false;                             // 被别人持有且未过期 → 失败
                return rec;
            });
            return ok[0];
        }

        /**
         * 原子解锁，对应 {@code lock.unlock()}：只有持有者能解，解别人的锁抛异常
         * （对应 Redisson 的 IllegalMonitorStateException，也是项目 L208 要先判 {@code if(locked)} 的原因）。
         */
        void release(String key, String owner) {
            store.compute(key, (k, rec) -> {
                if (rec == null) {
                    return null;
                }
                if (!rec.owner.equals(owner)) {
                    throw new IllegalStateException(owner + " 试图释放 " + rec.owner + " 持有的锁");
                }
                rec.holdCount--;
                if (rec.holdCount <= 0) {
                    alive.remove(owner);                   // 释放后看门狗不再为它续约
                    return null;                           // 删除 key
                }
                return rec;
            });
        }

        /** 模拟持有者进程崩溃：看门狗随 JVM 消失，不再为它续约（但锁记录还在，等 TTL 自然过期）。 */
        void crash(String owner) {
            alive.remove(owner);
        }

        @Override
        public void close() {
            watchdog.shutdownNow();
        }
    }

    // ==================================================================
    //  ① 互斥性：同一时刻临界区里最多 1 个线程
    // ==================================================================

    @Test
    @DisplayName("互斥性：100 线程争一把锁，任意时刻临界区内最多 1 个")
    void mutualExclusionHoldsUnder100Threads() throws Exception {
        final int threads = 100;
        try (MiniRedisson r = new MiniRedisson(400)) {
            String key = "lock:sds-rebuild:knowpost:9001";
            AtomicInteger inside = new AtomicInteger();     // 当前在临界区里的线程数
            AtomicInteger maxInside = new AtomicInteger();  // 观测到的峰值
            AtomicInteger completed = new AtomicInteger();  // 完成次数

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier start = new CyclicBarrier(threads);   // 一起出发（同第一课）
            CountDownLatch done = new CountDownLatch(threads);  // 一起到达

            for (int i = 0; i < threads; i++) {
                final String owner = "inst-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        boolean locked = false;
                        while (!locked) {                       // tryLock(0) 拿不到就自旋重试，直到拿到
                            locked = r.tryAcquire(key, owner);
                            if (!locked) {
                                Thread.sleep(1);
                            }
                        }
                        try {
                            int c = inside.incrementAndGet();
                            maxInside.accumulateAndGet(c, Math::max);
                            Thread.sleep(2);                    // 模拟"重建计数"这段临界区工作
                        } finally {
                            inside.decrementAndGet();
                            r.release(key, owner);              // 对应 L208：拿到锁才 release
                            completed.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            boolean finished = done.await(30, TimeUnit.SECONDS);
            pool.shutdownNow();
            assertThat(finished).as("所有线程都在超时前跑完").isTrue();
            assertThat(completed).as("100 个线程都进出临界区一次").hasValue(threads);
            // 关键断言：即使 100 个线程疯狂争抢，也绝不会出现 2 个同时在里面。
            // 看门狗在这里是隐形的功臣——某线程即便被调度器晾在一边超过 TTL，
            // 后台续约也会保住它的锁不被别人抢走，从而 maxInside 恒为 1。
            assertThat(maxInside).as("任意时刻临界区内最多 1 个线程——这就是互斥").hasValue(1);
        }
    }

    // ==================================================================
    //  ② tryLock(0) 的非阻塞语义 + 解不了别人的锁
    // ==================================================================

    @Test
    @DisplayName("tryLock(0)：锁被占时立刻返回 false 不阻塞；且不能解别人的锁")
    void tryLockZeroWaitReturnsImmediately() {
        try (MiniRedisson r = new MiniRedisson(400)) {
            String key = "lock:sds-rebuild:knowpost:9001";
            assertThat(r.tryAcquire(key, "A")).isTrue();     // A 先持有

            long t0 = System.nanoTime();
            boolean bGot = r.tryAcquire(key, "B");           // waitTime=0：一次都不等
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(bGot).as("A 持锁时 B 必须拿不到").isFalse();
            assertThat(elapsedMs).as("立刻返回，没有阻塞排队（对比 synchronized 会一直等）").isLessThan(100);

            // 对应项目 L208 的 if(locked) 纪律：没持锁的人去 unlock，Redisson 抛异常
            assertThatThrownBy(() -> r.release(key, "B"))
                    .isInstanceOf(IllegalStateException.class);

            r.release(key, "A");                             // A 释放后……
            assertThat(r.tryAcquire(key, "B")).as("锁空了 B 立刻就能拿到").isTrue();
            r.release(key, "B");
        }
    }

    // ==================================================================
    //  ③ 看门狗：活着就无限续约，崩溃了就在 TTL 后自动释放
    // ==================================================================

    @Test
    @DisplayName("看门狗：持有者活着时锁永不过期；崩溃后不再续约，TTL 到期自动被他人接管")
    void watchdogRenewsWhileAliveAndReleasesOnCrash() throws Exception {
        final long ttl = 400;
        try (MiniRedisson r = new MiniRedisson(ttl)) {
            String key = "lock:sds-rebuild:knowpost:9001";
            assertThat(r.tryAcquire(key, "A")).isTrue();

            // 阶段一：A 存活并"慢重建" 900ms（> 2×TTL）。若没有看门狗，锁早在 400ms 就过期被人抢走。
            // 有看门狗持续续约，B 在这 900ms 内一次都拿不到。
            long phase1End = System.currentTimeMillis() + 900;
            int bSuccessWhileAlive = 0;
            while (System.currentTimeMillis() < phase1End) {
                if (r.tryAcquire(key, "B")) {
                    bSuccessWhileAlive++;
                    r.release(key, "B");
                }
                Thread.sleep(30);
            }
            assertThat(bSuccessWhileAlive)
                    .as("A 存活期间看门狗每 TTL/3 续约，锁 TTL 名义只有 %dms 却撑过了 900ms，B 一次都不该拿到", ttl)
                    .isZero();

            // 阶段二：A "崩溃"——不调用 release，只是让看门狗不再为它续约。
            long crashAt = System.currentTimeMillis();
            r.crash("A");

            // B 持续尝试，应在 ~TTL 内（锁自然过期后）接管成功。
            long bGotInAt = -1;
            long deadline = crashAt + ttl * 3;
            while (System.currentTimeMillis() < deadline) {
                if (r.tryAcquire(key, "B")) {
                    bGotInAt = System.currentTimeMillis();
                    break;
                }
                Thread.sleep(20);
            }

            assertThat(bGotInAt).as("A 崩溃后锁应在 TTL 到期自动释放，B 得以接管（不会死锁）").isGreaterThan(0L);
            long took = bGotInAt - crashAt;
            // 不会"立刻"接管（说明锁是被 TTL 到期释放的，不是 crash 直接删的）；
            // 也不会拖太久（上限约 2×TTL：最后一次续约到 crash 的间隔 + 一个完整 TTL）。
            assertThat(took).as("接管耗时应在 TTL 量级").isBetween(100L, ttl * 2);
            r.release(key, "B");
        }
    }
}
