-- 演示 DDL（H2 默认路径；写法保持 MySQL 可移植）。
-- 时间列存 epoch 毫秒（BIGINT），演示简化，避免跨库时区差异。

CREATE TABLE IF NOT EXISTS form_record (
    record_id       VARCHAR(32)  PRIMARY KEY,
    flow_type       VARCHAR(64)  NOT NULL,
    business_version INT         NOT NULL,
    sku_code        VARCHAR(32)  NOT NULL,
    mark_rule       VARCHAR(64)  NOT NULL,
    mark_value      VARCHAR(32),
    mark_rule_source VARCHAR(128),
    mark_updated_at BIGINT,
    demo_fault      VARCHAR(32),
    demo_fault_remaining INT,
    updated_at      BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS flow_run (
    run_id          VARCHAR(32)  PRIMARY KEY,
    event_id        VARCHAR(32)  NOT NULL,
    source_record_id VARCHAR(32) NOT NULL,
    flow_type       VARCHAR(64)  NOT NULL,
    business_version INT         NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    apply_result    VARCHAR(16)  NOT NULL,
    plan_json       VARCHAR(4000),
    failure_reason  VARCHAR(400),
    write_count     INT          NOT NULL,
    replay_count    INT          NOT NULL,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL
);

-- 幂等键/事件号唯一：并发重复提交由数据库兜底（INSERT 冲突 → 返回既有 run）
CREATE UNIQUE INDEX IF NOT EXISTS ux_flow_run_event_id ON flow_run (event_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_flow_run_idem_key ON flow_run (idempotency_key);

CREATE TABLE IF NOT EXISTS flow_approval (
    run_id          VARCHAR(32)  PRIMARY KEY,
    decision        VARCHAR(16)  NOT NULL,
    actor_id        VARCHAR(64)  NOT NULL,
    note            VARCHAR(256),
    decided_at      BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS flow_audit (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          VARCHAR(32)  NOT NULL,
    ts              BIGINT       NOT NULL,
    event_type      VARCHAR(48)  NOT NULL,
    actor_id        VARCHAR(64)  NOT NULL,
    detail_json     VARCHAR(4000)
);

CREATE INDEX IF NOT EXISTS ix_flow_audit_run ON flow_audit (run_id);
