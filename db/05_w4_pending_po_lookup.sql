-- 普通索引不会更改、删除或合并历史 pending 意向。
-- 在目标业务库中执行，可重复运行。新建库的 schema.sql 已包含同名索引。
SET @pending_po_index_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'purchase_po' AND index_name = 'idx_po_sku_status'),
  'SELECT 1',
  'ALTER TABLE purchase_po ADD INDEX idx_po_sku_status (sku_code, status, id)'
);
PREPARE pending_po_index_statement FROM @pending_po_index_ddl;
EXECUTE pending_po_index_statement;
DEALLOCATE PREPARE pending_po_index_statement;
