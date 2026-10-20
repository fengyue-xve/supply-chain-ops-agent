package com.demo.flowagent.store;

import com.demo.flowagent.audit.AuditSanitizer;
import com.demo.flowagent.domain.AuditEventType;
import com.demo.flowagent.domain.AuditRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AuditStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 只追加；detail 先经 AuditSanitizer 校验（fail-closed）。 */
    public void append(String runId, AuditEventType type, String actorId, Map<String, Object> detail) {
        AuditSanitizer.validateDetail(detail);
        String detailJson;
        try {
            detailJson = mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计详情序列化失败", e);
        }
        jdbc.update("""
                INSERT INTO flow_audit (run_id, ts, event_type, actor_id, detail_json)
                VALUES (?,?,?,?,?)
                """,
                runId, Instant.now().toEpochMilli(), type.name(), actorId, detailJson);
    }

    public List<AuditRecord> listByRun(String runId) {
        return jdbc.query("SELECT * FROM flow_audit WHERE run_id = ? ORDER BY id", (rs, rowNum) -> new AuditRecord(
                rs.getLong("id"),
                rs.getString("run_id"),
                Instant.ofEpochMilli(rs.getLong("ts")),
                AuditEventType.valueOf(rs.getString("event_type")),
                rs.getString("actor_id"),
                rs.getString("detail_json")), runId);
    }
}
