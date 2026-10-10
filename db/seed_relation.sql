-- ============================================================
-- 第二课配套：following / follower 双表冗余的数据种子
--
-- 运行：
--   & "C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe" `
--       -uroot -p123456 zhiguang --table < db\seed_relation.sql
--
-- 这个脚本故意做了一件「看起来像 bug」的事：
--   它只写 following 表，一行都不写 follower 表。
-- 这不是遗漏，而是精确复刻了当前系统的真实状态——
-- 伪从同步链路（Outbox -> Canal -> Kafka -> RelationEventProcessor）
-- 因为本地没部署 Canal Server 而整条断掉，follower 表永远是空的。
-- 先让数据长成「线上事故的样子」，再用 EXPLAIN 和接口去观察它。
--
-- 全部种子数据可重复执行（幂等）：users 走 AUTO_INCREMENT 之外的固定 id，
-- following 走 uk_from_to 唯一键去重。
-- ============================================================

SELECT '===== 0. 造数前状态 =====' AS section;
SELECT (SELECT COUNT(*) FROM users)     AS users_rows,
       (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows;

-- ------------------------------------------------------------
-- 1. 40 个种子用户，id 固定在 1001..1040
--    users 表只有 nickname 是 NOT NULL，phone/email/zg_id 的唯一键
--    允许多个 NULL 共存（MySQL 唯一索引不把 NULL 当相等），所以留空即可。
-- ------------------------------------------------------------
INSERT INTO users (id, nickname, bio)
SELECT 1000 + a.i + b.i * 10,
       CONCAT('seed_user_', 1000 + a.i + b.i * 10),
       'seeded for stage5 lesson2'
FROM (SELECT 1 AS i UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
      UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) a,
     (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) b
ON DUPLICATE KEY UPDATE nickname = nickname;

-- ------------------------------------------------------------
-- 2. 种子用户之间的关注关系（约 1500 行，稀疏伪随机图）
--    to_user_id 用 (a.v + b.v*7 + 3) % 40 打散，避免顺序写入让
--    优化器看到「过于规整」的数据而给出失真的执行计划。
--    created_at 在 40 天区间内铺开，ORDER BY created_at DESC 才有意义。
-- ------------------------------------------------------------
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
SELECT 1000000 + n1.v * 100 + n2.v,
       1001 + n1.v,
       1001 + ((n1.v + n2.v * 7 + 3) % 40),
       1,
       NOW(3) - INTERVAL (n1.v * 1440 + n2.v * 37) MINUTE,
       NOW(3) - INTERVAL (n1.v * 1440 + n2.v * 37) MINUTE
FROM (SELECT a.i + b.i * 10 AS v
      FROM (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
           (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) b) n1,
     (SELECT a.i + b.i * 10 AS v
      FROM (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
           (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) b) n2
WHERE ((n1.v + n2.v * 7 + 3) % 40) <> n1.v          -- 不允许自关注
ON DUPLICATE KEY UPDATE rel_status = 1;

-- ------------------------------------------------------------
-- 3. 真实用户 id=1 关注全部 40 个种子用户
--    这是接口验收要用的数据：登录后 /relation/following?userId=1 能返回东西。
-- ------------------------------------------------------------
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
SELECT 2000000 + n.v,
       1,
       1001 + n.v,
       1,
       NOW(3) - INTERVAL (n.v * 53) MINUTE,
       NOW(3) - INTERVAL (n.v * 53) MINUTE
FROM (SELECT a.i + b.i * 10 AS v
      FROM (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
           (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) b) n
ON DUPLICATE KEY UPDATE rel_status = 1;

-- ------------------------------------------------------------
-- 4. 全部 40 个种子用户都关注 id=1（制造一个「小 V」）
--    注意：这些行只进 following 表。
--    按设计，id=1 的粉丝数应该等于 40，但因为 follower 表是空的，
--    /relation/counter 会告诉你 followers=0 ——这就是伪从断链的直接后果。
-- ------------------------------------------------------------
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
SELECT 3000000 + n.v,
       1001 + n.v,
       1,
       1,
       NOW(3) - INTERVAL (n.v * 71) MINUTE,
       NOW(3) - INTERVAL (n.v * 71) MINUTE
FROM (SELECT a.i + b.i * 10 AS v
      FROM (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
           (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) b) n
ON DUPLICATE KEY UPDATE rel_status = 1;

-- ------------------------------------------------------------
-- 5. 让 20% 的关系变成「已取关」（rel_status=0）
--    没有软删除数据，就测不出 WHERE rel_status=1 这个过滤条件
--    到底有没有被索引吃掉。
-- ------------------------------------------------------------
UPDATE following SET rel_status = 0, updated_at = NOW(3)
WHERE from_user_id BETWEEN 1001 AND 1040 AND (to_user_id % 5 = 0);

-- ------------------------------------------------------------
-- 6. 刷新统计信息
--    InnoDB 的行数估算来自统计信息，不刷新的话刚插入的数据
--    可能让优化器仍按「表是空的」来选执行计划，EXPLAIN 结果失真。
-- ------------------------------------------------------------
ANALYZE TABLE following;
ANALYZE TABLE follower;
ANALYZE TABLE users;

SELECT '===== 造数后状态（注意 follower_rows 依然是 0） =====' AS section;
SELECT (SELECT COUNT(*) FROM users)     AS users_rows,
       (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows,
       (SELECT COUNT(*) FROM following WHERE rel_status = 1) AS active_rows,
       (SELECT COUNT(*) FROM following WHERE rel_status = 0) AS canceled_rows;

SELECT '===== id=1 的关系（following 有 40 条，follower 有 0 条） =====' AS section;
SELECT (SELECT COUNT(*) FROM following WHERE from_user_id = 1 AND rel_status = 1) AS user1_following,
       (SELECT COUNT(*) FROM following WHERE to_user_id   = 1 AND rel_status = 1) AS user1_should_have_followers,
       (SELECT COUNT(*) FROM follower  WHERE to_user_id   = 1 AND rel_status = 1) AS user1_actual_followers;
