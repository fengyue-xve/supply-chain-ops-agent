-- ============================================================
-- 03 · W3-③ 修正：幂等登记唯一范围改为 (tool_name, operator, idempotency_key)
-- 用途：把「初版 02」建的全局唯一索引替换为按操作者隔离的复合键。
-- 幂等保护（任意合法状态可安全执行，重复执行为 no-op）：
--   - 已是新键（新库跑过 schema.sql / 修正版 02，或本脚本已执行过）→ 跳过（DO 0）；
--   - 仅旧键 uq_idempotency_key 在（执行过初版 02 的存量库）→ 一次替换。
--   两键皆无（如未跑过 schema.sql/02 就执行本文件）→ 交由 ALTER 报错暴露，
--   不静默跳过：本文件承诺的 no-op 只针对合法建库/迁移顺序下的既有结构。
-- 背景：初版 UNIQUE(idempotency_key) 让「不同操作者 + 同 key」互相冲突，
--       且经唯一键回查可能把 A 的历史结果回放给 B；改为按操作者隔离的作用域。
-- 影响：仅替换唯一索引；列、行、既有数据一律不动。约束放宽（旧键是新键的更严子集），
--       存量行必然满足新键，无数据兼容风险（dev/test 实际执行时两库均 0 行）。
-- 执行（dev 与 test 各跑一次，注意 --default-character-set=utf8mb4）：
--   mysql --default-character-set=utf8mb4 -h<host> -P<port> -u<user> -p <db> < db/03_w3c_idem_scope.sql
-- 迁移链执行顺序与「新库/存量库」差异见 README「启动与测试」。
-- ============================================================

SET @has_new := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'idempotency_record'
      AND INDEX_NAME = 'uq_idem_scope');

SET @ddl := IF(@has_new = 0,
    'ALTER TABLE idempotency_record
        DROP INDEX uq_idempotency_key,
        ADD CONSTRAINT uq_idem_scope UNIQUE (tool_name, operator, idempotency_key)',
    'DO 0');

PREPARE migrate_03 FROM @ddl;
EXECUTE migrate_03;
DEALLOCATE PREPARE migrate_03;

-- 结果回显：正常应只看到 uq_idem_scope 三列（旧键已被替换或本就为新键）
SELECT INDEX_NAME AS final_index, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_order
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'idempotency_record'
  AND INDEX_NAME IN ('uq_idem_scope', 'uq_idempotency_key')
GROUP BY INDEX_NAME;
