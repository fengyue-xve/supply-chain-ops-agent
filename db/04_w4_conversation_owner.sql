-- ============================================================
-- 04 · W4-1 会话归属：agent_conversation 增补 operator 列 + 归属索引
-- 用途：对「已存在的库」补齐 W4-1 结构（新库不需要：schema.sql 已含该列与索引；
--       存量库按 01 → 02 → 03 → 04 顺序补）。
-- 幂等保护：列已存在（sentinel=operator）→ 跳过；索引已存在（information_schema.STATISTICS）
--       → 跳过；重复执行是 no-op 成功，不会出现重复列或重复索引。
-- 背景：W4-1 会话 Memory 要求「会话历史按用户可访问」，缺列时无法按 operator 过滤，
--       且旧行不携带归属信息会迫使默认归属（禁止）。
-- 影响：仅新增一个可空列 + 一个普通索引，不改既有字段、既有行——旧行的 operator 保持
--       NULL；应用约定 NULL = 「迁移前历史数据」，对任何用户不可见、不被继承、不回放。
-- 执行（dev 与 test 各跑一次，注意 --default-character-set=utf8mb4）：
--   mysql --default-character-set=utf8mb4 -h<host> -P<port> -u<user> -p <db> < db/04_w4_conversation_owner.sql
-- 迁移链执行顺序与「新库/存量库」差异见 README「启动与测试」。
-- ============================================================

-- ---------- 1. operator 归属列（JWT sub，可空：NULL = W4-1 前旧数据） ----------
SET @has_conv_operator := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation' AND COLUMN_NAME = 'operator');
SET @ddl_conv_operator := IF(@has_conv_operator = 0,
    'ALTER TABLE agent_conversation
        ADD COLUMN operator VARCHAR(64) NULL COMMENT ''消息归属用户名（JWT sub），NULL = W4-1 前旧数据'' AFTER session_id',
    'DO 0');
PREPARE migrate_04_col FROM @ddl_conv_operator;
EXECUTE migrate_04_col;
DEALLOCATE PREPARE migrate_04_col;

-- ---------- 2. 归属索引（按用户 + 会话读取历史 / 归属校验用） ----------
SET @has_conv_owner_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation'
      AND INDEX_NAME = 'idx_conversation_owner');
SET @ddl_conv_owner_idx := IF(@has_conv_owner_idx = 0,
    'ALTER TABLE agent_conversation ADD INDEX idx_conversation_owner (operator, session_id)',
    'DO 0');
PREPARE migrate_04_idx FROM @ddl_conv_owner_idx;
EXECUTE migrate_04_idx;
DEALLOCATE PREPARE migrate_04_idx;
