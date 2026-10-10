-- ============================================================
-- 第二课配套：follower 表回填 + 「列顺序镜像」陷阱实证
--
-- 运行：
--   Get-Content db\backfill_follower.sql -Raw -Encoding UTF8 |
--     & "C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe" `
--       -uroot -p123456 --default-character-set=utf8mb4 zhiguang --table
--
-- 分三段：
--   第一段  用事务演示「INSERT INTO follower SELECT * FROM following」会静默反转关系
--   第二段  用正确的列清单真正回填 follower
--   第三段  回填后重跑关键 EXPLAIN，让 follower 表的执行计划也有真实数据支撑
-- ============================================================

-- ------------------------------------------------------------
-- 第一段：陷阱演示（事务内，最后 ROLLBACK，不落盘）
--
-- following 的列顺序：id, from_user_id, to_user_id, ...
-- follower  的列顺序：id, to_user_id,   from_user_id, ...
-- 两者第 2、3 列是镜像反转的。
--
-- 不带列清单的 INSERT ... SELECT 是「按位置」映射的，
-- 于是 following.from_user_id 会灌进 follower.to_user_id。
-- 结果是：每一条关注关系都被掉转成它的反向。
-- 最可怕的地方在于——它不报错，不告警，行数完全正确。
-- ------------------------------------------------------------
START TRANSACTION;

SELECT '=== TRAP: before ===' AS section;
SELECT id, from_user_id, to_user_id FROM following WHERE id IN (2000000, 2000001, 3000000, 3000001) ORDER BY id;

INSERT INTO follower SELECT * FROM following WHERE id IN (2000000, 2000001, 3000000, 3000001);

SELECT '=== TRAP: INSERT INTO follower SELECT * FROM following ===' AS section;
SELECT id, to_user_id, from_user_id, 'column order is id,to_user_id,from_user_id' AS note
FROM follower WHERE id IN (2000000, 2000001, 3000000, 3000001) ORDER BY id;

SELECT '=== TRAP: the relationship got INVERTED ===' AS section;
SELECT f.id,
       f.from_user_id AS following_from,
       f.to_user_id   AS following_to,
       r.to_user_id   AS follower_to,
       r.from_user_id AS follower_from,
       CASE WHEN f.from_user_id = r.from_user_id AND f.to_user_id = r.to_user_id
            THEN 'OK' ELSE 'INVERTED' END AS verdict
FROM following f JOIN follower r ON r.id = f.id
WHERE f.id IN (2000000, 2000001, 3000000, 3000001) ORDER BY f.id;

ROLLBACK;

SELECT '=== after ROLLBACK, follower is empty again ===' AS section;
SELECT COUNT(*) AS follower_rows FROM follower;

-- ------------------------------------------------------------
-- 第二段：正确的回填
--
-- 显式写出列清单，让映射关系靠名字而不是靠位置。
-- 这也是「伪从」本该做的事：把主表按反方向重排后落到从表。
--
-- 这里选择「全量重建」而不是「增量补齐」：
--   增量的写法是 INSERT ... ON DUPLICATE KEY UPDATE rel_status = 1，
--   但它有个隐蔽缺陷——脚本重跑时会把 rel_status=0（已取关）的行
--   强行改回 1，等于让取关过的人复活。要避免就得用 VALUES() 或行别名
--   去引用源表的值，写法一下子复杂起来。
--   全量重建没有这个问题：结果只取决于 following 表当前的样子，
--   脚本天然幂等，跑一百次都一样。这正是「对账任务」该有的性质。
--   代价是 DELETE 全表在千万级数据上很贵，真到那个量级要换成分批重建。
--
-- 值得停下来想一个问题：既然一条 INSERT ... SELECT 就能同步，
-- 项目为什么非要绕一圈 Outbox -> Canal -> Kafka -> Consumer？
-- 这是本课的思考题之一。
-- ------------------------------------------------------------
SELECT '=== BACKFILL: full rebuild with explicit column list ===' AS section;

DELETE FROM follower;

INSERT INTO follower (id, to_user_id, from_user_id, rel_status, created_at, updated_at)
SELECT id, to_user_id, from_user_id, rel_status, created_at, updated_at
FROM following;

ANALYZE TABLE follower;

SELECT (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows,
       (SELECT COUNT(*) FROM follower WHERE to_user_id = 1 AND rel_status = 1) AS user1_followers_now;

SELECT '=== verify: no inversion this time ===' AS section;
SELECT COUNT(*) AS mismatched_rows
FROM following f JOIN follower r ON r.id = f.id
WHERE f.from_user_id <> r.from_user_id OR f.to_user_id <> r.to_user_id;

-- ------------------------------------------------------------
-- 第三段：回填后重跑 follower 表的 EXPLAIN
--   这次表里有真数据，优化器的估算才有意义。
--   对照实验：
--     3f = follower 正向（idx_to_created）  期望 Covering + reverse，无 Sort
--     4f = follower 反向（idx_from）        期望出现 Sort
-- ------------------------------------------------------------
SELECT '=== 3f. follower / forward (real data now) ===' AS section;
EXPLAIN
SELECT from_user_id FROM follower
WHERE to_user_id = 1 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

SELECT '=== 4f. follower / REVERSE (real data now) ===' AS section;
EXPLAIN
SELECT to_user_id FROM follower
WHERE from_user_id = 1001 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

SELECT '=== 5f. follower point lookup ===' AS section;
EXPLAIN
SELECT COUNT(1) FROM follower
WHERE to_user_id = 1 AND from_user_id = 1001 AND rel_status = 1;

SELECT '=== 8. count comparison: the two tables now agree ===' AS section;
SELECT (SELECT COUNT(1) FROM following WHERE from_user_id = 1 AND rel_status = 1) AS countFollowingActive,
       (SELECT COUNT(1) FROM follower  WHERE to_user_id   = 1 AND rel_status = 1) AS countFollowerActive;
