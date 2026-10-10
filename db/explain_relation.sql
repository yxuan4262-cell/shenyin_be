-- ============================================================
-- 第二课配套：following / follower 双表冗余的执行计划实证
--
-- 运行：
--   Get-Content db\explain_relation.sql -Raw -Encoding UTF8 |
--     & "C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe" `
--       -uroot -p123456 --default-character-set=utf8mb4 zhiguang --table
--
-- 只看三列：
--   key    实际选中的索引名
--   rows   优化器估算要扫多少行
--   Extra  "Using index" = 覆盖索引不回表；"Using filesort" = 排序没被索引吃掉
--
-- 全部用 EXPLAIN FORMAT=TRADITIONAL，输出窄、好对比。
-- ============================================================

SELECT '=== A. following table: index inventory ===' AS section;
SHOW INDEX FROM following;

SELECT '=== B. follower table: index inventory (column order is REVERSED) ===' AS section;
SHOW INDEX FROM follower;

SELECT '=== C. row counts ===' AS section;
SELECT (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows,
       (SELECT COUNT(*) FROM following WHERE rel_status = 1) AS following_active;

-- ------------------------------------------------------------
-- 实验 1：在 following 表上查「我关注了谁」——正向，索引为它量身定做
--   需要 from_user_id(过滤) + created_at(排序) + to_user_id(投影) + rel_status(过滤)
--   idx_from_created = (from_user_id, created_at, to_user_id, rel_status)
--   四列全在索引里 -> 覆盖索引；created_at 在第 2 位 -> 排序天然有序，免 filesort
-- ------------------------------------------------------------
SELECT '=== 1. following / forward  (who do I follow) ===' AS section;
EXPLAIN
SELECT to_user_id FROM following
WHERE from_user_id = 1001 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- ------------------------------------------------------------
-- 实验 2：在 following 表上查「谁关注了我」——反向，同一张表
--   需要 to_user_id(过滤) + created_at(排序) + from_user_id(投影) + rel_status(过滤)
--   idx_to = (to_user_id, from_user_id, rel_status) —— created_at 不在里面
--   预期：既要回表取 created_at，又要 filesort。同样的查询形状，代价完全不同。
-- ------------------------------------------------------------
SELECT '=== 2. following / REVERSE  (who follows me) -- the pain point ===' AS section;
EXPLAIN
SELECT from_user_id FROM following
WHERE to_user_id = 1 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- ------------------------------------------------------------
-- 实验 3：在 follower 表上查「谁关注了我」——伪从表的正向
--   idx_to_created = (to_user_id, created_at, from_user_id, rel_status)
--   预期：Using index 且无 filesort，和实验 1 一样漂亮。
--   这就是冗余第二张表换来的东西。
-- ------------------------------------------------------------
SELECT '=== 3. follower / forward  (who follows me, after redundancy) ===' AS section;
EXPLAIN
SELECT from_user_id FROM follower
WHERE to_user_id = 1 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- ------------------------------------------------------------
-- 实验 4：在 follower 表上查「我关注了谁」——伪从表的反向
--   idx_from = (from_user_id, to_user_id, rel_status) —— created_at 不在里面
--   预期：filesort 回来了。冗余是对称的代价，两张表各自只擅长一个方向。
-- ------------------------------------------------------------
SELECT '=== 4. follower / REVERSE  (who do I follow, on the wrong table) ===' AS section;
EXPLAIN
SELECT to_user_id FROM follower
WHERE from_user_id = 1001 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- ------------------------------------------------------------
-- 实验 5：等值双条件判定（existsFollowing 的形状）
--   uk_from_to = (from_user_id, to_user_id) 是唯一键，选择性最高
--   但 rel_status 不在这个索引里 -> 必须回表
--   对比 idx_from_created：rel_status 在里面，但 to_user_id 在第 3 位
--   看优化器在两者之间怎么选，这是本课最值得盯的一处。
-- ------------------------------------------------------------
SELECT '=== 5. point lookup: which index wins, uk_from_to or idx_from_created? ===' AS section;
EXPLAIN
SELECT COUNT(1) FROM following
WHERE from_user_id = 1001 AND to_user_id = 1002 AND rel_status = 1;

-- ------------------------------------------------------------
-- 实验 6：深翻页的真实代价
--   OFFSET 10000 意味着 MySQL 要先取出 10020 行再扔掉前 10000 行。
--   覆盖索引能省掉回表，但省不掉「数着扔」。
--   这就是为什么项目里另一套接口用游标分页（cursor = created_at 毫秒值）。
-- ------------------------------------------------------------
SELECT '=== 6. deep offset paging cost (OFFSET 10000) ===' AS section;
EXPLAIN
SELECT to_user_id FROM following
WHERE from_user_id = 1001 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 10000;

-- ------------------------------------------------------------
-- 实验 7：EXPLAIN ANALYZE 给出真实执行耗时与行数（MySQL 8.0.18+）
--   EXPLAIN 只是估算，ANALYZE 是真跑一遍。两者的 rows 差距能暴露统计信息陈旧。
-- ------------------------------------------------------------
SELECT '=== 7. EXPLAIN ANALYZE: actual vs estimated ===' AS section;
EXPLAIN ANALYZE
SELECT to_user_id FROM following
WHERE from_user_id = 1001 AND rel_status = 1
ORDER BY created_at DESC LIMIT 20 OFFSET 0;
