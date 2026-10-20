-- ============================================================
-- 01 · W3-② 会话落库：agent_conversation 增补两列
-- 用途：对「已存在的库」补齐 W3-② 结构（新库不需要：schema.sql 已含新列与 CHECK；
--       存量库按 01 → 02 → 03 顺序补）。
-- 幂等保护：列已存在（sentinel=request_id，两列同批增删）/ CHECK 已存在 → 跳过；
--       重复执行是 no-op 成功，不会出现重复列或重复约束。
-- 背景：W3-② 需按 request_id 定位链路、并在助手消息行记录本轮 success/failed。
-- 影响：仅新增两个可空列 + 一个 CHECK，不改既有字段、既有行（旧行两列为 NULL）。
-- 执行（dev 与 test 各跑一次，注意 --default-character-set=utf8mb4）：
--   mysql --default-character-set=utf8mb4 -h<host> -P<port> -u<user> -p <db> < db/01_agent_conversation_request_status.sql
-- 迁移链执行顺序与「新库/存量库」差异见 README「启动与测试」。
-- ============================================================

-- ---------- 1. 两个可空列（两列同批增删，sentinel=request_id 即可判定） ----------
SET @has_conv_req := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation' AND COLUMN_NAME = 'request_id');
SET @ddl_conv := IF(@has_conv_req = 0,
    'ALTER TABLE agent_conversation
        ADD COLUMN request_id VARCHAR(64) NULL COMMENT ''链路请求 ID（X-Request-Id）'' AFTER tool_calls_json,
        ADD COLUMN status     VARCHAR(16) NULL COMMENT ''本轮结果：success/failed（用户消息行 NULL）'' AFTER request_id',
    'DO 0');
PREPARE migrate_01_cols FROM @ddl_conv;
EXECUTE migrate_01_cols;
DEALLOCATE PREPARE migrate_01_cols;

-- ---------- 2. status CHECK 约束 ----------
SET @has_conv_chk := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation'
      AND CONSTRAINT_NAME = 'chk_conversation_status');
SET @ddl_chk := IF(@has_conv_chk = 0,
    'ALTER TABLE agent_conversation
        ADD CONSTRAINT chk_conversation_status CHECK (status IN (''success'', ''failed''))',
    'DO 0');
PREPARE migrate_01_chk FROM @ddl_chk;
EXECUTE migrate_01_chk;
DEALLOCATE PREPARE migrate_01_chk;
