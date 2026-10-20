package com.demo.supplyagent.db;

import com.demo.supplyagent.support.TestDbInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D1 冒烟：schema/seed 可执行（TestDbInitializer 自举灌入 test 库）+
 * 8 表种子数量断言 + 5 项业务唯一键冲突断言（1062）+
 * 3 项中文内容编码断言（W3-① 防双重编码回归：行数断言拦不住内容损坏）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaSeedSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void initDb(@Autowired DataSource ds) throws Exception {
        TestDbInitializer.init(ds);
    }

    @Test
    void seedRowCountsMatchDesign() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_snapshot", Integer.class)).isEqualTo(610);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_order", Integer.class)).isEqualTo(72);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_po", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_history", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation", Integer.class)).isEqualTo(4);
    }

    @Test
    void seedChineseTextIsNotMangled() {
        String name = jdbc.queryForObject(
                "SELECT name FROM product WHERE sku_code = 'AUD-001'", String.class);
        assertThat(name).isEqualTo("主动降噪蓝牙耳机");
    }

    @Test
    void seedChineseTaskErrorTextIsNotMangled() {
        String errorMsg = jdbc.queryForObject(
                "SELECT error_msg FROM agent_task WHERE id = 3", String.class);
        assertThat(errorMsg).isEqualTo("演示失败:快照写入前触发基线熔断(BaselineGuard 模拟)");
    }

    @Test
    void seedChineseConversationTextIsNotMangled() {
        String content = jdbc.queryForObject(
                "SELECT content FROM agent_conversation WHERE id = 1", String.class);
        assertThat(content).isEqualTo("AUD-001 现在还有多少库存？");
    }

    @Test
    void allSeedDataStaysInFixedBusinessWindow() {
        Integer orderMin = jdbc.queryForObject("SELECT MIN(DATE_FORMAT(order_date,'%Y%m%d')) FROM sale_order", Integer.class);
        Integer orderMax = jdbc.queryForObject("SELECT MAX(DATE_FORMAT(order_date,'%Y%m%d')) FROM sale_order", Integer.class);
        assertThat(orderMin).isGreaterThanOrEqualTo(20260705);
        assertThat(orderMax).isLessThanOrEqualTo(20260903);
        Integer snapMax = jdbc.queryForObject("SELECT MAX(DATE_FORMAT(snap_date,'%Y%m%d')) FROM inventory_snapshot", Integer.class);
        assertThat(snapMax).isEqualTo(20260903);
    }

    @Test
    void usernameUniqueEnforced() {
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO app_user (username, password_hash, role, created_at, updated_at) " +
                        "VALUES ('admin', 'x', 'viewer', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void skuCodeUniqueEnforced() {
        String sku = jdbc.queryForObject("SELECT sku_code FROM product LIMIT 1", String.class);
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO product (sku_code, name, category, created_at, updated_at) " +
                        "VALUES ('" + sku + "', 'dup', 'audio', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void snapshotCompositeUniqueEnforced() {
        String sku = jdbc.queryForObject("SELECT sku_code FROM inventory_snapshot LIMIT 1", String.class);
        String date = jdbc.queryForObject("SELECT DATE_FORMAT(MIN(snap_date),'%Y-%m-%d') FROM inventory_snapshot", String.class);
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO inventory_snapshot (sku_code, snap_date, stock_qty, created_at, updated_at) " +
                        "VALUES ('" + sku + "', '" + date + "', 1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void orderNoUniqueEnforced() {
        String orderNo = jdbc.queryForObject("SELECT order_no FROM sale_order LIMIT 1", String.class);
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO sale_order (order_no, sku_code, order_date, qty, created_at, updated_at) " +
                        "VALUES ('" + orderNo + "', 'AUD-001', '2026-09-03', 1, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void poNoUniqueEnforced() {
        String poNo = jdbc.queryForObject("SELECT po_no FROM purchase_po LIMIT 1", String.class);
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO purchase_po (po_no, sku_code, qty, status, created_at, updated_at) " +
                        "VALUES ('" + poNo + "', 'AUD-001', 1, 'pending', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void w3cWriteTablesExist() {
        for (String table : java.util.List.of("po_confirmation", "idempotency_record", "po_no_seq")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            assertThat(count).as("表存在: " + table).isEqualTo(1);
        }
    }

    @Test
    void agentHistoryHasW3cStructuredColumns() {
        java.util.List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_history'",
                String.class);
        assertThat(columns).contains("operator", "request_id", "conversation_id", "tool_call_id");
    }

    @Test
    void conversationHasW4OwnerColumnAndIndex() {
        // W4-1:operator 归属列必须可空(NULL = 旧数据,不对任何用户可见/可继承)
        String nullable = jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation' "
                        + "AND COLUMN_NAME = 'operator'",
                String.class);
        assertThat(nullable).isEqualTo("YES");

        java.util.List<String> indexedCols = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation' "
                        + "AND INDEX_NAME = 'idx_conversation_owner' ORDER BY SEQ_IN_INDEX",
                String.class);
        assertThat(indexedCols).containsExactly("operator", "session_id");
    }

    @Test
    void poNoSeqHasNoAutoIncrementId() {
        // 刻意不带自增主键列：LAST_INSERT_ID(expr) 的显式赋值不被自增 id 覆盖(见 db/02 说明)
        java.util.List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'po_no_seq'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder("seq_date", "last_seq", "created_at", "updated_at");
    }

    @Test
    void seedIsReRunnableIdempotent() throws Exception {
        TestDbInitializer.init(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_snapshot", Integer.class)).isEqualTo(610);
    }
}
