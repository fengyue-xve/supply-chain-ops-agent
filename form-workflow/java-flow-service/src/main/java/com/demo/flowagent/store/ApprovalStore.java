package com.demo.flowagent.store;

import com.demo.flowagent.domain.ApprovalDecision;
import com.demo.flowagent.domain.ApprovalEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class ApprovalStore {

    private final JdbcTemplate jdbc;

    public ApprovalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApprovalEntity approval) {
        jdbc.update("""
                INSERT INTO flow_approval (run_id, decision, actor_id, note, decided_at)
                VALUES (?,?,?,?,?)
                """,
                approval.runId(), approval.decision().name(), approval.actorId(), approval.note(),
                approval.decidedAt().toEpochMilli());
    }

    public Optional<ApprovalEntity> findByRunId(String runId) {
        return jdbc.query("SELECT * FROM flow_approval WHERE run_id = ?", (rs, rowNum) -> new ApprovalEntity(
                        rs.getString("run_id"),
                        ApprovalDecision.valueOf(rs.getString("decision")),
                        rs.getString("actor_id"),
                        rs.getString("note"),
                        Instant.ofEpochMilli(rs.getLong("decided_at"))),
                runId).stream().findFirst();
    }
}
