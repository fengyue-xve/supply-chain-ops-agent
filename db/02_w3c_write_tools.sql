-- ============================================================
-- 02 · W3-③ 敏感写工具：agent_history 结构化列 + 三张新表
-- 用途：对「已存在的库」补齐 W3-③ 结构（新库不需要：schema.sql 已含新列与新表；
--       存量库按 01 → 02 → 03 顺序补；本文件同时是 schema.sql 对应部分的迁移版）。
-- 幂等保护：列已存在（sentinel=operator）/ 表已存在 → 跳过；重复执行是 no-op 成功，
--       不会出现重复建表。约束/索引不在此文件建（idempotency_record 唯一键见 03 说明）。
-- 背景：
--   - agent_history 增补 operator/request_id/conversation_id/tool_call_id 四个结构化列：
--     写工具的审计关键检索字段进结构化列，before/after_json 只留状态快照（不埋检索字段）。
--   - po_confirmation：一行 = 一个 po_no 的确认流当前态（唯一性由 po_no 持有）。
--   - idempotency_record：请求级幂等登记表，UNIQUE(tool_name, operator, idempotency_key) + 规范化请求摘要，
--     覆盖「同 key、不同 SKU 集合」的并发窗口（不用 UNIQUE(key, sku) 列组合方案）。
--     ※ 初版 02 曾建 UNIQUE(idempotency_key)（不同操作者同 key 会互相干扰/串回放）；
--       执行过初版 02 的库需补跑 db/03_w3c_idem_scope.sql 换键（03 自带幂等保护）。
--       本文件已同步为修正后定义，新建表即新键。
--   - po_no_seq：同日序号分配计数器（单语句原子自增，替代无锁 MAX+1）。
-- 影响：只增不删——既有列不动、既有行不 UPDATE（agent_history 旧行新列留 NULL）；
--       三个新表初始 0 行；agent_history 行数不变。MySQL 8 ADD COLUMN 走 INSTANT 算法不重建表。
-- 执行（dev 与 test 各跑一次，注意 --default-character-set=utf8mb4）：
--   mysql --default-character-set=utf8mb4 -h<host> -P<port> -u<user> -p <db> < db/02_w3c_write_tools.sql
-- 迁移链执行顺序与「新库/存量库」差异见 README「启动与测试」。
-- ============================================================

-- ---------- 1. agent_history：增补四个结构化列（四列同批增删，sentinel=operator 即可判定） ----------
SET @has_hist_op := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_history' AND COLUMN_NAME = 'operator');
SET @ddl_hist := IF(@has_hist_op = 0,
    'ALTER TABLE agent_history
        ADD COLUMN operator        VARCHAR(64) NULL COMMENT ''操作者（JWT sub；who 存角色）'' AFTER who,
        ADD COLUMN request_id      VARCHAR(64) NULL COMMENT ''链路请求 ID（X-Request-Id）'' AFTER stats_json,
        ADD COLUMN conversation_id VARCHAR(80) NULL COMMENT ''会话 ID（X-Conversation-Id）'' AFTER request_id,
        ADD COLUMN tool_call_id    VARCHAR(64) NULL COMMENT ''Agent 工具调用关联 ID（X-Tool-Call-Id）'' AFTER conversation_id',
    'DO 0');
PREPARE migrate_02_hist FROM @ddl_hist;
EXECUTE migrate_02_hist;
DEALLOCATE PREPARE migrate_02_hist;

-- ---------- 2. po_confirmation：确认流当前态（一行 = 一个 po_no） ----------
CREATE TABLE IF NOT EXISTS po_confirmation (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    po_no           VARCHAR(40) NOT NULL COMMENT '采购单号（一 SKU 一单）',
    sku_code        VARCHAR(32) NOT NULL COMMENT 'SKU',
    qty             INT         NOT NULL COMMENT '采购数量',
    status          VARCHAR(24) NOT NULL COMMENT '确认流状态',
    idempotency_key VARCHAR(80) NOT NULL COMMENT '来源幂等键（唯一性由 idempotency_record 持有，本列仅溯源）',
    operator        VARCHAR(64) NOT NULL COMMENT '创建者（JWT sub）',
    request_id      VARCHAR(64) NULL COMMENT '链路请求 ID（X-Request-Id）',
    conversation_id VARCHAR(80) NULL COMMENT '会话 ID（X-Conversation-Id）',
    tool_call_id    VARCHAR(64) NULL COMMENT 'Agent 工具调用关联 ID（X-Tool-Call-Id）',
    reason          VARCHAR(255) NULL COMMENT '失败原因（failed 时写入）',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_po_confirmation_po_no UNIQUE (po_no),
    INDEX idx_po_confirmation_idem (idempotency_key),
    INDEX idx_po_confirmation_status (status),
    CONSTRAINT chk_po_confirmation_qty CHECK (qty > 0),
    CONSTRAINT chk_po_confirmation_status CHECK (status IN ('draft', 'pending_confirmation', 'confirmed', 'failed', 'cancelled'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '采购单确认流当前态（draft 保留不产生）';

-- ---------- 3. idempotency_record：请求级幂等登记 ----------
CREATE TABLE IF NOT EXISTS idempotency_record (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    idempotency_key VARCHAR(80) NOT NULL COMMENT '客户端幂等键（Idempotency-Key 头）',
    tool_name       VARCHAR(40) NOT NULL COMMENT '来源工具名（摘要的一部分）',
    request_digest  CHAR(64)    NOT NULL COMMENT '规范化请求摘要（items 按 sku_code 排序后 SHA-256 hex）',
    response_json   TEXT        NOT NULL COMMENT '首次成功响应 data 快照（重复请求原样回放）',
    operator        VARCHAR(64) NOT NULL COMMENT '首次请求操作者（JWT sub）',
    request_id      VARCHAR(64) NULL COMMENT '首次请求链路 ID（X-Request-Id）',
    conversation_id VARCHAR(80) NULL COMMENT '首次请求会话 ID（X-Conversation-Id）',
    tool_call_id    VARCHAR(64) NULL COMMENT '首次请求工具调用关联 ID（X-Tool-Call-Id）',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_idem_scope UNIQUE (tool_name, operator, idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '请求级幂等登记（作用域=(工具,操作者,幂等键)；同键同摘要回放 / 同键异摘要 409）';

-- ---------- 4. po_no_seq：同日序号分配计数器 ----------
-- 计数器表不设自增主键列：LAST_INSERT_ID(expr) 的显式赋值不会被自增 id 覆盖，
-- 分配语义无歧义（刻意偏离「统一自增主键」约定，理由记录在 docs/01 取舍表）。
CREATE TABLE IF NOT EXISTS po_no_seq (
    seq_date   DATE        NOT NULL PRIMARY KEY COMMENT '业务日',
    last_seq   INT         NOT NULL COMMENT '该日已分配到的最大序号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '采购单号同日序号计数器（原子分配）';
