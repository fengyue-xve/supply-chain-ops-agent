-- ============================================================
-- schema.sql · 供应链智能运营 Agent（虚构项目）· MySQL 8
-- 8 张业务表 + 3 张 W3-③ 写链路表（po_confirmation/idempotency_record/po_no_seq，全虚构，无任何公司真实表名/字段/数据）
-- 统一约定：
--   - 主键 id BIGINT UNSIGNED AUTO_INCREMENT
--   - created_at / updated_at DATETIME(3)（应用层维护）
--   - 业务唯一键 uq_xxx、普通索引 idx_xxx；无物理外键
--   - 状态/角色列用 CHECK（MySQL 8 强制）
-- 幂等：CREATE TABLE IF NOT EXISTS，可重跑。
-- 测试库（supply_agent_test）执行同一文件（USE 语句替换目标库）。
-- ============================================================

USE supply_agent;

-- ---------- 1. app_user：账号（密码只存 bcrypt 哈希） ----------
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username      VARCHAR(50)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'bcrypt 哈希，绝不存明文',
    role          VARCHAR(16)  NOT NULL COMMENT '角色',
    created_at    DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_username UNIQUE (username),
    CONSTRAINT chk_app_user_role CHECK (role IN ('admin', 'viewer'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户账号（admin/viewer）';

-- ---------- 2. product：SKU 主档 ----------
CREATE TABLE IF NOT EXISTS product (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    sku_code     VARCHAR(32)  NOT NULL COMMENT 'SKU 编码',
    name         VARCHAR(128) NOT NULL COMMENT '商品名',
    category     VARCHAR(32)  NOT NULL COMMENT '类目：audio/charging/accessory',
    pkg_qty      INT          NOT NULL DEFAULT 1 COMMENT '每箱/包装数量',
    shelf_status VARCHAR(16)  NOT NULL DEFAULT 'on_shelf' COMMENT '上下架',
    safety_stock INT          NOT NULL DEFAULT 0 COMMENT '安全库存',
    lead_days    INT          NOT NULL DEFAULT 0 COMMENT '采购提前期（天）',
    created_at   DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at   DATETIME(3)  NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_sku_code UNIQUE (sku_code),
    CONSTRAINT chk_product_pkg CHECK (pkg_qty > 0),
    CONSTRAINT chk_product_safety CHECK (safety_stock >= 0),
    CONSTRAINT chk_product_shelf CHECK (shelf_status IN ('on_shelf', 'off_shelf'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品主档';

-- ---------- 3. inventory_snapshot：库存水位快照 ----------
CREATE TABLE IF NOT EXISTS inventory_snapshot (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    sku_code   VARCHAR(32) NOT NULL COMMENT 'SKU',
    snap_date  DATE        NOT NULL COMMENT '快照日期（业务日）',
    stock_qty  INT         NOT NULL COMMENT '当日现存量',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_snapshot_sku_date UNIQUE (sku_code, snap_date),
    CONSTRAINT chk_snapshot_qty CHECK (stock_qty >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '库存快照（每日 upsert）';

-- ---------- 4. sale_order：销售订单（虚构） ----------
CREATE TABLE IF NOT EXISTS sale_order (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no   VARCHAR(40) NOT NULL COMMENT '销售单号',
    sku_code   VARCHAR(32) NOT NULL COMMENT 'SKU',
    order_date DATE        NOT NULL COMMENT '下单日（业务日）',
    qty        INT         NOT NULL COMMENT '数量',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_order_no UNIQUE (order_no),
    CONSTRAINT chk_order_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '销售订单';

-- ---------- 5. purchase_po：采购单（四态，只落草稿） ----------
CREATE TABLE IF NOT EXISTS purchase_po (
    id                      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    po_no                   VARCHAR(40) NOT NULL COMMENT '采购单号',
    sku_code                VARCHAR(32) NOT NULL COMMENT 'SKU',
    qty                     INT         NOT NULL COMMENT '数量',
    status                  VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '状态',
    manual_confirm_required TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否需人工确认',
    created_at              DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at              DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_po_no UNIQUE (po_no),
    KEY idx_po_sku_status (sku_code, status, id),
    CONSTRAINT chk_po_qty CHECK (qty > 0),
    CONSTRAINT chk_po_status CHECK (status IN ('pending', 'confirmed', 'cancelled', 'completed'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '采购单（pending草稿→人工确认生效）';

-- ---------- 6. agent_task：任务状态机主表 ----------
CREATE TABLE IF NOT EXISTS agent_task (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_key        VARCHAR(80) NOT NULL COMMENT '任务键（幂等唯一）',
    task_type       VARCHAR(40) NOT NULL COMMENT '任务类型：snapshot/suggest/po_*',
    status          VARCHAR(16) NOT NULL COMMENT '状态',
    attempt_no      INT         NOT NULL DEFAULT 1 COMMENT '尝试序号',
    execution_mode  VARCHAR(16) NOT NULL COMMENT '触发方式',
    stats_json      TEXT        NULL COMMENT '成功统计（每列独立来源）',
    error_msg       TEXT        NULL COMMENT '失败信息',
    source_task_key VARCHAR(80) NULL COMMENT 'retry 来源任务键',
    start_time      DATETIME(3) NULL COMMENT '开始时间',
    end_time        DATETIME(3) NULL COMMENT '结束时间',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL COMMENT '更新时间',
    CONSTRAINT uq_task_key UNIQUE (task_key),
    CONSTRAINT chk_task_status CHECK (status IN ('running', 'success', 'fail')),
    CONSTRAINT chk_task_mode CHECK (execution_mode IN ('auto', 'retry', 'manual'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务状态机主表（fail 保留可 retry 新键）';

-- ---------- 7. agent_history：审计时间线（应用层永不 DELETE） ----------
-- W3-③ 新增 operator/request_id/conversation_id/tool_call_id 四个结构化列（存量库见 db/02_w3c_write_tools.sql）
CREATE TABLE IF NOT EXISTS agent_history (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_key        VARCHAR(80) NULL COMMENT '关联任务键',
    executed_at     DATETIME(3) NOT NULL COMMENT '业务发生时间',
    action_type     VARCHAR(40) NOT NULL COMMENT '动作类型：task_begin/task_success/task_fail/po_create/po_confirm...',
    who             VARCHAR(16) NOT NULL COMMENT '执行者角色',
    operator        VARCHAR(64) NULL COMMENT '操作者（JWT sub）',
    before_json     TEXT        NULL COMMENT '变更前状态快照',
    after_json      TEXT        NULL COMMENT '变更后状态快照',
    stats_json      TEXT        NULL COMMENT '统计（每列独立来源）',
    requested_at    DATETIME(3) NULL COMMENT '请求到达时间',
    request_id      VARCHAR(64) NULL COMMENT '链路请求 ID（X-Request-Id）',
    conversation_id VARCHAR(80) NULL COMMENT '会话 ID（X-Conversation-Id）',
    tool_call_id    VARCHAR(64) NULL COMMENT 'Agent 工具调用关联 ID（X-Tool-Call-Id）',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL COMMENT '更新时间',
    INDEX idx_history_task_key (task_key),
    CONSTRAINT chk_history_who CHECK (who IN ('admin', 'viewer', 'task', 'agent'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '审计时间线（永不 DELETE，W4 断言扫描）';

-- ---------- 8. agent_conversation：会话落库 ----------
-- W3-② 新增 request_id / status 两列（存量库见 db/01_agent_conversation_request_status.sql）
-- W4-1 新增 operator 归属列 + 归属索引（存量库见 db/04_w4_conversation_owner.sql；
--        NULL = W4-1 前旧数据，对任何用户不可见、不被继承）
CREATE TABLE IF NOT EXISTS agent_conversation (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    session_id      VARCHAR(80) NOT NULL COMMENT '会话 ID',
    operator        VARCHAR(64) NULL COMMENT '消息归属用户名（JWT sub），NULL = W4-1 前旧数据',
    role            VARCHAR(16) NOT NULL COMMENT '消息角色',
    content         TEXT        NULL COMMENT '消息内容',
    tool_calls_json TEXT        NULL COMMENT '工具调用记录（JSON）',
    request_id      VARCHAR(64) NULL COMMENT '链路请求 ID（X-Request-Id）',
    status          VARCHAR(16) NULL COMMENT '本轮结果：success/failed（用户消息行 NULL）',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3) NOT NULL COMMENT '更新时间',
    INDEX idx_conversation_session (session_id),
    INDEX idx_conversation_owner (operator, session_id),
    CONSTRAINT chk_conversation_role CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    CONSTRAINT chk_conversation_status CHECK (status IN ('success', 'failed'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 会话消息';

-- ---------- 9. po_confirmation：确认流当前态（W3-③ 新表，一行 = 一个 po_no） ----------
-- 唯一性由 po_no 持有；idempotency_key 仅溯源（请求级唯一性在 idempotency_record）。
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

-- ---------- 10. idempotency_record：请求级幂等登记（W3-③ 新表） ----------
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

-- ---------- 11. po_no_seq：同日序号分配计数器（W3-③ 新表） ----------
-- 计数器表不设自增主键列：LAST_INSERT_ID(expr) 的显式赋值不会被自增 id 覆盖，
-- 分配语义无歧义（刻意偏离「统一自增主键」约定，理由记录在 docs/01 取舍表）。
CREATE TABLE IF NOT EXISTS po_no_seq (
    seq_date   DATE        NOT NULL PRIMARY KEY COMMENT '业务日',
    last_seq   INT         NOT NULL COMMENT '该日已分配到的最大序号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '采购单号同日序号计数器（原子分配）';
