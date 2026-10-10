-- ============================================================
-- 思考题 3 配套：随机主键 + ON DUPLICATE KEY UPDATE 的数据损坏复现
--
-- 运行：
--   Get-Content db\pk_collision_demo.sql -Raw -Encoding UTF8 |
--     & "C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe" `
--       -uroot -p123456 --default-character-set=utf8mb4 zhiguang --table
--
-- 全部实验都在事务里跑，每个实验后面都有 ROLLBACK，不落盘。
-- 末尾有一次完整性校验，确认 following / follower 行数与实验前一致。
--
-- 被复现的代码（RelationMapper.xml insertFollowing）：
--   INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
--   VALUES (#{id}, #{fromUserId}, #{toUserId}, #{relStatus}, NOW(3), NOW(3))
--   ON DUPLICATE KEY UPDATE rel_status=VALUES(rel_status), updated_at=VALUES(updated_at)
--
-- 而 id 来自 RelationServiceImpl.follow()：
--   long id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
--
-- 这张表有两个唯一键：PRIMARY(id) 和 uk_from_to(from_user_id, to_user_id)。
-- MySQL 的规则是：插入的行只要在【任意一个】唯一索引上撞车，就转为 UPDATE 那一行。
-- ============================================================

SELECT '=== baseline ===' AS section;
SELECT (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows;

SELECT '=== the victim row: id=3000005 (belongs to 1006 -> 1) ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, created_at, updated_at
FROM following WHERE id = 3000005;

SELECT '=== the row that owns uk_from_to(1,1001): id=2000000 ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, created_at, updated_at
FROM following WHERE from_user_id = 1 AND to_user_id = 1001;

-- ============================================================
-- 实验 A：只有 PRIMARY 撞车
--   场景：user 1 关注 user 2（一条全新的关系），
--         但 ThreadLocalRandom 恰好吐出了 3000005，
--         而这个 id 属于毫不相干的 (1006 -> 1)。
-- ============================================================
START TRANSACTION;

SELECT '=== A0. before: does (1 -> 2) exist? ===' AS section;
SELECT COUNT(*) AS row_1_to_2 FROM following WHERE from_user_id = 1 AND to_user_id = 2;

SELECT '=== A1. executing insertFollowing(3000005, 1, 2, 1) ===' AS section;
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
VALUES (3000005, 1, 2, 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE rel_status = VALUES(rel_status), updated_at = VALUES(updated_at);

SELECT ROW_COUNT() AS affected_rows_1insert_2update_0noop;

SELECT '=== A2. after: was (1 -> 2) created? ===' AS section;
SELECT COUNT(*) AS row_1_to_2 FROM following WHERE from_user_id = 1 AND to_user_id = 2;

SELECT '=== A3. after: what happened to the VICTIM row id=3000005? ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, created_at, updated_at
FROM following WHERE id = 3000005;

SELECT '=== A4. any row matching id=3000005 OR (1,2) ? ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status FROM following
WHERE id = 3000005 OR (from_user_id = 1 AND to_user_id = 2);

ROLLBACK;

-- ============================================================
-- 实验 B（对照组）：只有 uk_from_to 撞车 —— 这是 upsert 的正确用法
--   场景：user 1 重复关注 1001（已关注），id 用一个保证不冲突的新值。
--   期望：命中的就是 (1 -> 1001) 那一行本身，语义正确。
-- ============================================================
START TRANSACTION;

SELECT '=== B1. executing insertFollowing(999999999, 1, 1001, 1) ===' AS section;
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
VALUES (999999999, 1, 1001, 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE rel_status = VALUES(rel_status), updated_at = VALUES(updated_at);

SELECT ROW_COUNT() AS affected_rows;

SELECT '=== B2. after: which row changed? (id stays 2000000, NOT 999999999) ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, updated_at FROM following
WHERE from_user_id = 1 AND to_user_id = 1001;

SELECT '=== B3. did the stray id 999999999 get inserted? ===' AS section;
SELECT COUNT(*) AS stray_id_rows FROM following WHERE id = 999999999;

ROLLBACK;

-- ============================================================
-- 实验 C：两个唯一键同时撞车，而且撞在【不同的两行】上
--   id=3000005        属于 (1006 -> 1)
--   uk_from_to(1,1001) 属于 id=2000000
--   两个候选行完全不同。MySQL 会更新哪一行？
--   官方文档对这种情况的措辞是「不推荐」，但没说清具体行为——所以实测。
-- ============================================================
START TRANSACTION;

SELECT '=== C1. executing insertFollowing(3000005, 1, 1001, 1) -- BOTH keys collide ===' AS section;
INSERT INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
VALUES (3000005, 1, 1001, 1, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE rel_status = VALUES(rel_status), updated_at = VALUES(updated_at);

SELECT ROW_COUNT() AS affected_rows;

SELECT '=== C2. row A: id=3000005 (the PK owner) ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, created_at, updated_at
FROM following WHERE id = 3000005;

SELECT '=== C3. row B: uk_from_to(1,1001) owner ===' AS section;
SELECT id, from_user_id, to_user_id, rel_status, created_at, updated_at
FROM following WHERE from_user_id = 1 AND to_user_id = 1001;

SELECT '=== C4. verdict: which one got its updated_at bumped? ===' AS section;
SELECT
  (SELECT updated_at FROM following WHERE id = 3000005)                  AS pk_owner_updated_at,
  (SELECT updated_at FROM following WHERE from_user_id=1 AND to_user_id=1001) AS uk_owner_updated_at,
  NOW(3)                                                                 AS now_ts;

ROLLBACK;

-- ============================================================
-- 完整性校验：确认所有 ROLLBACK 都生效了
-- ============================================================
SELECT '=== integrity check: must equal baseline ===' AS section;
SELECT (SELECT COUNT(*) FROM following) AS following_rows,
       (SELECT COUNT(*) FROM follower)  AS follower_rows,
       (SELECT COUNT(*) FROM following WHERE id = 999999999) AS stray_rows,
       (SELECT COUNT(*) FROM following WHERE from_user_id = 1 AND to_user_id = 2) AS should_be_zero;
