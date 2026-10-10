package com.tongji.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第七阶段第一课动手练习：把「缓存三大问题」里最难看见的两个——击穿与穿透——
 * 变成可以断言的数字。
 *
 * <p><b>为什么是纯单元测试、不碰 Redis 也不启 Spring</b>：
 * 我们要验证的是「并发回源的次数」这个纯粹的逻辑性质，和 Redis/Caffeine 的具体实现无关。
 * 用一个 {@link ConcurrentHashMap} 当假缓存、一个 {@link AtomicInteger} 当「回源计数器」、
 * 一个 {@code Thread.sleep} 当「慢查询」，就能把击穿的本质隔离出来。
 * 没有 Spring 上下文，4 个断言零点几秒跑完；换成 {@code @SpringBootTest} 光启动就十几秒。</p>
 *
 * <p>这与项目里 {@code KnowPostServiceImpl.getDetail} 的结构一一对应：
 * 假缓存 = Redis+L1，回源计数器 = {@code mapper.findDetailById} 被调了几次，
 * sleep = 那次真实的 DB 查询耗时。</p>
 */
class CacheThreeProblemsTest {

    /** 并发线程数。就是题目要的「100 个线程」。 */
    private static final int THREADS = 100;

    /**
     * 模拟一次「数据库回源」的耗时（毫秒）。
     *
     * <p>这一行是让实验成立的关键，不是可有可无的装饰：它把「回源」这个动作拉长到 50ms，
     * 从而撑开一个足够宽的并发窗口——保证 100 个线程全都挤进「缓存还没被写回」的那段时间里，
     * 全部 miss、全部回源。真实 DB 查询本来就是毫秒级的，这里只是把它放大到肉眼可辨。</p>
     */
    private static final long DB_LOAD_MILLIS = 50;

    // ==================================================================
    //  被对照的两种缓存读实现
    // ==================================================================

    /**
     * 无防护版：最朴素的 cache-aside（旁路缓存）。
     * 查缓存 -> 未命中 -> 回源 -> 写缓存。没有任何互斥。
     * 高并发下同一个 key 会被 N 个线程同时回源 N 次，这就是「击穿」。
     */
    private static String naiveGet(ConcurrentHashMap<String, String> cache,
                                   String key, AtomicInteger dbHits) throws InterruptedException {
        String v = cache.get(key);
        if (v != null) {
            return v;                       // 命中，不回源
        }
        dbHits.incrementAndGet();           // ← 回源计数（击穿时这里会被加很多次）
        Thread.sleep(DB_LOAD_MILLIS);       // 模拟慢查询
        v = "value-of-" + key;
        cache.put(key, v);                  // 写回缓存
        return v;
    }

    /**
     * SingleFlight 版：与项目 getDetail L349-365 同构。
     * 每个 key 一把锁；未命中时加锁，锁内「双重检查」再决定是否回源。
     * 结果：无论多少并发，同一个 key 只回源 1 次。
     */
    private static String singleFlightGet(ConcurrentHashMap<String, String> cache,
                                          ConcurrentHashMap<String, Object> locks,
                                          String key, AtomicInteger dbHits) throws InterruptedException {
        String v = cache.get(key);
        if (v != null) {
            return v;
        }
        // 每个 key 一把独立的锁对象，锁 9001 不会挡住锁 9002
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            v = cache.get(key);             // ← 双重检查：排队期间可能已被别的线程写回
            if (v != null) {
                return v;                   // 命中就直接走，不回源
            }
            dbHits.incrementAndGet();       // 只有第一个抢到锁、且双检仍未命中的线程走到这
            Thread.sleep(DB_LOAD_MILLIS);
            v = "value-of-" + key;
            cache.put(key, v);
            return v;
        }
    }

    /**
     * 并发跑批的公共骨架：建 THREADS 个线程，让它们「同一瞬间」扑向同一个 key，
     * 返回总共回源了多少次。
     *
     * <p><b>如何建立 100 个线程（这是你问的重点，拆成三块看）：</b></p>
     * <ol>
     *   <li><b>线程从哪来</b>：{@code Executors.newFixedThreadPool(THREADS)} 建一个固定 100 条线程的池。
     *       我们提交 100 个任务，池里正好 100 条线程各领一个 —— 于是「100 个线程」就到位了。
     *       <b>坑：池大小必须 ≥ 下面的 CyclicBarrier 参与方数量，否则死锁</b>
     *       （若只有 50 条线程却有 100 个任务在 barrier 上互等，永远凑不齐）。</li>
     *   <li><b>怎么让它们「同时」冲</b>：光建 100 个线程还不够 —— 如果它们先后启动，
     *       第一个可能已经跑完、把缓存写好了，后面的全命中，你就看不到击穿了。
     *       {@link CyclicBarrier}(100) 是一道「集合闸门」：每条线程跑到 {@code startGate.await()}
     *       就停下等，直到第 100 条也到齐，barrier 才在同一瞬间把 100 条全部放行。
     *       这才制造出「100 个请求同时命中同一个过期 key」的真实场景。</li>
     *   <li><b>怎么知道它们都跑完了</b>：{@link CountDownLatch}(100) 是另一道「点名闸」，
     *       每条线程在 finally 里 {@code countDown()} 一次；主线程 {@code doneGate.await()}
     *       阻塞到计数归零，才去读 {@code dbHits}。否则主线程会在子线程还没跑完时就断言，读到错的数。</li>
     * </ol>
     *
     * <p>一句话：<b>建线程是 trivial 的，让 100 条线程「撞在同一瞬间」才是这门手艺。</b>
     * CyclicBarrier 管「一起出发」，CountDownLatch 管「一起到达终点」。</p>
     */
    private int runConcurrent(boolean singleFlight) throws Exception {
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
        AtomicInteger dbHits = new AtomicInteger();
        String key = "knowpost:detail:9001:v1";     // 就是那个「爆款热点 key」

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier startGate = new CyclicBarrier(THREADS);   // 一起出发
        CountDownLatch doneGate = new CountDownLatch(THREADS);  // 一起到达

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();              // 在此集合，凑齐 100 条才放行
                    if (singleFlight) {
                        singleFlightGet(cache, locks, key, dbHits);
                    } else {
                        naiveGet(cache, key, dbHits);
                    }
                } catch (Exception ignored) {
                    // barrier/latch 被打断等，测试里不关心
                } finally {
                    doneGate.countDown();           // 无论成败都要点名，否则主线程永久等待
                }
            });
        }

        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(finished).as("所有线程都在超时前跑完").isTrue();
        return dbHits.get();
    }

    // ==================================================================
    //  击穿：无防护 vs SingleFlight
    // ==================================================================

    @Test
    @DisplayName("击穿-无防护：100 并发命中同一个 key，几乎每个都回源（≈100 次）")
    void naiveCacheAsideBreaksDown() throws Exception {
        int hits = runConcurrent(false);
        // 50ms 的回源窗口保证绝大多数线程都 miss。实测通常就是 100。
        // 用 isGreaterThan(50) 而不是 isEqualTo(100)，是给极端调度留余量，避免 flaky。
        assertThat(hits).isGreaterThan(50);
    }

    @Test
    @DisplayName("击穿-SingleFlight：同样 100 并发，只回源 1 次")
    void singleFlightCollapsesToOneLoad() throws Exception {
        int hits = runConcurrent(true);
        assertThat(hits).isEqualTo(1);      // 100 -> 1，这就是击穿防御的全部意义
    }

    // ==================================================================
    //  穿透：NULL 哨兵把「不存在」也缓存起来
    // ==================================================================

    @Test
    @DisplayName("穿透：查一个不存在的 key，第一次回源写 NULL 哨兵，第二次命中哨兵不再回源")
    void nullSentinelStopsPenetration() {
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        AtomicInteger dbHits = new AtomicInteger();
        String ghostKey = "knowpost:detail:999999999:v1";   // 数据库里根本没有的内容

        loadWithSentinel(cache, ghostKey, dbHits);   // 第一次：回源，查不到，写 "NULL"
        loadWithSentinel(cache, ghostKey, dbHits);   // 第二次：命中 "NULL"，直接判不存在，不回源

        // 若没有哨兵，两次都会回源（dbHits=2）——那就是穿透。
        // 有了哨兵，DB 只被打了一次。
        assertThat(dbHits).hasValue(1);
        assertThat(cache.get(ghostKey)).isEqualTo("NULL");
    }

    /**
     * 带 NULL 哨兵的读实现，与项目 tryProcessCacheHit + getDetail L372-373 同构。
     * 关键：把「查不到」这个结果本身也写进缓存（值为字符串 "NULL"），
     * 下次同样的查询命中哨兵、直接返回不存在，不再骚扰数据库。
     */
    private static String loadWithSentinel(ConcurrentHashMap<String, String> cache,
                                           String key, AtomicInteger dbHits) {
        String cached = cache.get(key);
        if ("NULL".equals(cached)) {
            return null;                    // 命中哨兵：不查库
        }
        if (cached != null) {
            return cached;                  // 命中真值：不查库
        }
        dbHits.incrementAndGet();           // 回源
        String row = queryDb(key);          // 模拟查库
        cache.put(key, row == null ? "NULL" : row);   // 查不到就写哨兵
        return row;
    }

    /** 模拟数据库：只有幽灵 id 查不到，其余都能查到。 */
    private static String queryDb(String key) {
        return key.contains("999999999") ? null : "value-of-" + key;
    }
}
