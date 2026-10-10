package com.tongji.auth.token;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 Redis 的刷新令牌白名单存储。
 * <p>
 * 键空间由两部分组成：
 * <ul>
 *   <li>令牌键 {@code auth:rt:{userId}:{tokenId}}，值固定为 "1"，TTL = 该令牌剩余寿命。
 *       这是 {@link #isTokenValid} 唯一要看的东西，保持一次 GET、O(1)。</li>
 *   <li>索引键 {@code auth:rt:idx:{userId}}，ZSET，member = tokenId，
 *       score = 该令牌的过期时刻（epochSecond）。</li>
 * </ul>
 * <p>
 * <b>索引存在的唯一理由</b>：让 {@link #revokeAll} 不必遍历整个 keyspace。
 * 原实现是 {@code redisTemplate.keys("auth:rt:%d:*")}，也就是 Redis 的 KEYS 命令。
 * KEYS 的成本是 O(全库键数)，与匹配到多少个键<b>无关</b>——它必须走完整个 keyspace
 * 才能断定「没有匹配的」。实测 100 万键的库里：匹配 1 个的 KEYS 耗时 40ms，
 * 匹配 0 个的同样 40ms，而 PING 基线是 0ms；库从 20 万涨到 100 万（5 倍），
 * 耗时也线性涨 5 倍。Redis 单线程，这 40ms 内所有其他客户端的请求都在排队。
 * 本项目的 Redis 里装着计数键（每个帖子、每个用户各一个），键数随业务规模线性增长，
 * 而触发点却是 {@code AuthService.resetPassword}——一旦发生批量改密码（撞库、密码泄漏），
 * N × 40ms 足以让整个 Redis 不可用，把一次安全事件升级成全站可用性事故。
 * <p>
 * <b>为什么不用 SCAN</b>：SCAN 把一次长阻塞切成许多次短阻塞，别的客户端不再被卡住，
 * 但完整遍历的总耗时反而暴涨——同一份 100 万键的数据，KEYS 40ms，SCAN 6.7s，慢 168 倍，
 * 需要 1000 轮往返（COUNT=1000）。放进 HTTP 请求里等于让用户干等。
 * SCAN 治的是「阻塞别人」，没治「自己慢」。唯一的解法是不遍历。
 * <p>
 * <b>本类维护的不变量</b>：任何时刻，「索引里的 tokenId 集合」都是「仍然有效的令牌集合」
 * 的<b>超集</b>。于是 {@code revokeAll} 只可能多删（DEL 不存在的键返回 0，无害），
 * 绝不会漏删。下面 {@link #storeToken} 与 {@link #revokeToken} 的写入顺序<b>相反</b>，
 * 正是为了在两步之间崩溃时也守住它。
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    /** 令牌键模板。 */
    private static final String TOKEN_KEY = "auth:rt:%d:%s";

    /**
     * 索引键模板。
     * <p>
     * 前缀刻意与令牌键错开：令牌键第二段是数字 userId，索引键第二段是字面量 {@code idx}。
     * 这样 {@code KEYS auth:rt:1:*} 之类的模式不会把索引键当成令牌键捞出来。
     * 本类已不再使用任何模式匹配，但排查问题时人肉 redis-cli 会用到，这个区分能省不少困惑。
     */
    private static final String INDEX_KEY = "auth:rt:idx:%d";

    /**
     * 索引键兜底 TTL 的下限，实际取 {@code max(此值, 令牌 TTL × 2)}。
     * <p>
     * <b>约束：索引必须活得比它索引的任何令牌都久。</b>
     * 否则索引先过期消失、令牌却还有效，{@code revokeAll} 就会漏删——
     * 用户改了密码，某个旧会话依然活着，这是安全事故。
     * 取两倍是为了自动适配配置：谁把 {@code auth.jwt.refresh-token-ttl} 改成 60 天，
     * 索引也会跟着变成 120 天，约束不会被悄悄破坏。
     */
    private static final Duration INDEX_MIN_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将刷新令牌写入白名单，并登记进该用户的索引。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID（jti）。
     * @param ttl     生存时间（Redis TTL），必须为正。
     * @throws IllegalArgumentException 当 ttl 为 null、零或负数时。
     */
    @Override
    public void storeToken(long userId, String tokenId, Duration ttl) {
        // Redis 的 SET ... EX 只接受正数：EX 0 与 EX -1 报的是同一个错
        //   ERR invalid expire time in 'set' command
        // 调用方 AuthService.storeRefreshToken 里原本有一段
        //   if (ttl.isNegative()) ttl = Duration.ZERO;
        // 把「会炸的负数」换成了「一样会炸的零」，等于没防。
        // 与其让它冒到 GlobalExceptionHandler 变成一个语焉不详的 500，
        // 不如在边界上把话说明白。
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "refresh token ttl must be positive, got: " + ttl);
        }

        String indexKey = indexKey(userId);
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();

        // 先写索引，再写令牌键。这个顺序是故意的。
        //
        // 两次写入不是原子的（要原子得上 Lua 脚本或 MULTI/EXEC），
        // 所以必须考虑「只成功了一半」的后果：
        //
        //   先索引后令牌：索引里有、令牌键没有 → revokeAll 去 DEL 一个不存在的键，无害
        //   先令牌后索引：令牌有效、索引里没有 → revokeAll 找不到它，漏删
        //
        // 后者是安全事故。失败模式必须偏向「多删」，不能偏向「漏删」。
        //
        // opsForZSet().add(key, value, score) 对应 ZADD：
        // member = tokenId，score = 过期时刻的 epochSecond。
        // score 存时间而不是随便一个常数，是为了以后能按时间做垃圾回收
        // （ZREMRANGEBYSCORE key -inf now），也为了排查时一眼看出哪个会话什么时候到期。
        redisTemplate.opsForZSet().add(indexKey, tokenId, expiresAt);
        redisTemplate.opsForValue().set(tokenKey(userId, tokenId), "1", ttl);

        // 索引自身的兜底 TTL，约束见 INDEX_MIN_TTL 上的说明。
        // 每次写入都重设一次，保证活跃用户的索引不会因为早先的短 TTL 而提前消失。
        Duration indexTtl = ttl.multipliedBy(2);
        if (indexTtl.compareTo(INDEX_MIN_TTL) < 0) {
            indexTtl = INDEX_MIN_TTL;
        }
        redisTemplate.expire(indexKey, indexTtl);
    }

    /**
     * 判断刷新令牌是否仍有效。
     * <p>
     * <b>刻意不碰索引</b>：这是每次 refresh 都要走的热路径，必须保持一次 GET、O(1)。
     * 索引只服务于 {@link #revokeAll} 那条冷路径。
     * 令牌的过期由 Redis 的 TTL 负责，所以这里不需要读 score 来判时间。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     * @return 是否有效（键存在且值为 "1"）。
     */
    @Override
    public boolean isTokenValid(long userId, String tokenId) {
        return Objects.equals("1", redisTemplate.opsForValue().get(tokenKey(userId, tokenId)));
    }

    /**
     * 撤销单个刷新令牌，并从索引中摘除。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     */
    @Override
    public void revokeToken(long userId, String tokenId) {
        // 先删令牌键，再摘索引——与 storeToken 的顺序正好相反，理由也正好相反：
        // 反过来写会留下一个「索引已摘、令牌仍有效」的窗口，
        // 此时 revokeAll 找不到它，又是漏删。
        //
        // 两个方法合起来守住同一个不变量：索引 ⊇ 有效令牌集合。
        redisTemplate.delete(tokenKey(userId, tokenId));
        redisTemplate.opsForZSet().remove(indexKey(userId), tokenId);
    }

    /**
     * 撤销该用户全部刷新令牌（强制该用户所有会话下线）。
     * <p>
     * 成本 O(该用户的令牌数)，<b>与 Redis 全库键数无关</b>。
     * 全程没有任何模式匹配，因此既不会阻塞其他客户端，也不会随业务增长而变慢。
     *
     * @param userId 用户 ID。
     */
    @Override
    public void revokeAll(long userId) {
        String indexKey = indexKey(userId);

        // ZRANGE key 0 -1 取全部成员。
        // range() 在 pipeline / transaction 上下文里返回 null 而不是空集合，
        // 所以判空必须两种都覆盖——原来的 keys() 有同样的坑（直接 .isEmpty() 会 NPE）。
        Set<String> tokenIds = redisTemplate.opsForZSet().range(indexKey, 0, -1);
        if (tokenIds == null || tokenIds.isEmpty()) {
            // 索引不存在或已空。仍删一次，清掉可能残留的空 ZSET。
            redisTemplate.delete(indexKey);
            return;
        }

        List<String> keys = new ArrayList<>(tokenIds.size() + 1);
        for (String tokenId : tokenIds) {
            keys.add(tokenKey(userId, tokenId));
        }
        keys.add(indexKey);

        // 一次 DEL 带走全部令牌键 + 索引自身。
        // 索引里可能混着已过期的 tokenId（其令牌键早被 Redis 自动清掉了），
        // DEL 不存在的键返回 0，无害——所以不必先按 score 过滤一遍，那反而多一次往返。
        redisTemplate.delete(keys);
    }

    /**
     * 生成令牌键名。
     *
     * @param userId  用户 ID。
     * @param tokenId 刷新令牌 ID。
     * @return Redis 键名。
     */
    private static String tokenKey(long userId, String tokenId) {
        return TOKEN_KEY.formatted(userId, tokenId);
    }

    /**
     * 生成该用户的索引键名。
     *
     * @param userId 用户 ID。
     * @return Redis 键名。
     */
    private static String indexKey(long userId) {
        return INDEX_KEY.formatted(userId);
    }
}
