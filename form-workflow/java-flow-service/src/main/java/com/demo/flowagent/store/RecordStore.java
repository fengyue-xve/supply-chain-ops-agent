package com.demo.flowagent.store;

import com.demo.flowagent.domain.FlowType;
import com.demo.flowagent.domain.FormRecord;
import com.demo.flowagent.fixtures.FixtureLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@Repository
public class RecordStore {

    private final JdbcTemplate jdbc;

    public RecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FormRecord> MAPPER = RecordStore::mapRow;

    private static FormRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FormRecord(
                rs.getString("record_id"),
                FlowType.valueOf(rs.getString("flow_type")),
                rs.getInt("business_version"),
                rs.getString("sku_code"),
                rs.getString("mark_rule"),
                rs.getString("mark_value"),
                rs.getString("mark_rule_source"),
                toInstant(rs.getObject("mark_updated_at")),
                rs.getString("demo_fault"),
                (Integer) rs.getObject("demo_fault_remaining"),
                toInstant(rs.getObject("updated_at")));
    }

    private static Instant toInstant(Object epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(((Number) epochMillis).longValue());
    }

    public boolean seedIfAbsent(FixtureLoader.RecordFixture f) {
        return jdbc.update("""
                INSERT INTO form_record (record_id, flow_type, business_version, sku_code, mark_rule,
                                         mark_value, mark_rule_source, mark_updated_at,
                                         demo_fault, demo_fault_remaining, updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?
                WHERE NOT EXISTS (SELECT 1 FROM form_record WHERE record_id = ?)
                """,
                f.recordId(), f.flowType(), f.businessVersion(), f.skuCode(), f.markRule(),
                f.currentMark() == null ? null : f.currentMark().value(),
                f.currentMark() == null ? null : f.currentMark().ruleSource(),
                f.currentMark() == null ? null : Instant.now().toEpochMilli(),
                f.demoFault() == null ? null : f.demoFault().kind(),
                f.demoFault() == null ? null : f.demoFault().remaining(),
                Instant.now().toEpochMilli(),
                f.recordId()) > 0;
    }

    public Optional<FormRecord> find(String recordId) {
        return jdbc.query("SELECT * FROM form_record WHERE record_id = ?", MAPPER, recordId).stream().findFirst();
    }

    /** 守卫写入：仅当记录仍处于计划时的业务版本才允许写入（乱序/旧版本保护的最后一道闸）。 */
    public int applyMark(String recordId, int expectedVersion, String value, String ruleSource, Instant now) {
        return jdbc.update("""
                UPDATE form_record
                   SET mark_value = ?, mark_rule_source = ?, mark_updated_at = ?, updated_at = ?
                 WHERE record_id = ? AND business_version = ?
                """,
                value, ruleSource, now.toEpochMilli(), now.toEpochMilli(), recordId, expectedVersion);
    }

    /** 消耗一次故障注入额度（仅 remaining 非空且 >0 时递减；WRITE_FAIL_ALWAYS 无额度）。 */
    public void consumeFault(String recordId) {
        jdbc.update("""
                UPDATE form_record
                   SET demo_fault_remaining = demo_fault_remaining - 1
                 WHERE record_id = ? AND demo_fault_remaining IS NOT NULL AND demo_fault_remaining > 0
                """, recordId);
    }

    public int countAll() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM form_record", Integer.class);
    }
}
