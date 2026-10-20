package com.demo.flowagent.store;

import com.demo.flowagent.domain.ApplyResult;
import com.demo.flowagent.domain.FlowRunEntity;
import com.demo.flowagent.domain.FlowType;
import com.demo.flowagent.domain.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class RunStore {

    /** transition 允许附带的列（白名单，防注入）。 */
    private static final Set<String> ALLOWED_EXTRA_COLUMNS = Set.of("plan_json", "failure_reason", "apply_result");

    private final JdbcTemplate jdbc;

    public RunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlowRunEntity> MAPPER = RunStore::mapRow;

    private static FlowRunEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FlowRunEntity(
                rs.getString("run_id"),
                rs.getString("event_id"),
                rs.getString("source_record_id"),
                FlowType.valueOf(rs.getString("flow_type")),
                rs.getInt("business_version"),
                rs.getString("idempotency_key"),
                RunStatus.valueOf(rs.getString("status")),
                ApplyResult.valueOf(rs.getString("apply_result")),
                rs.getString("plan_json"),
                rs.getString("failure_reason"),
                rs.getInt("write_count"),
                rs.getInt("replay_count"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    public void insert(FlowRunEntity run) {
        jdbc.update("""
                INSERT INTO flow_run (run_id, event_id, source_record_id, flow_type, business_version,
                                      idempotency_key, status, apply_result, plan_json, failure_reason,
                                      write_count, replay_count, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                run.runId(), run.eventId(), run.sourceRecordId(), run.flowType().name(), run.businessVersion(),
                run.idempotencyKey(), run.status().name(), run.applyResult().name(), run.planJson(), run.failureReason(),
                run.writeCount(), run.replayCount(),
                run.createdAt().toEpochMilli(), run.updatedAt().toEpochMilli());
    }

    public Optional<FlowRunEntity> findByRunId(String runId) {
        return jdbc.query("SELECT * FROM flow_run WHERE run_id = ?", MAPPER, runId).stream().findFirst();
    }

    public Optional<FlowRunEntity> findByEventId(String eventId) {
        return jdbc.query("SELECT * FROM flow_run WHERE event_id = ?", MAPPER, eventId).stream().findFirst();
    }

    public Optional<FlowRunEntity> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM flow_run WHERE idempotency_key = ?", MAPPER, idempotencyKey).stream().findFirst();
    }

    /**
     * 守卫状态迁移：仅当当前状态 == from 时更新为 to；返回是否发生了迁移。
     * 非法迁移返回 false（调用方抛 INVALID_TRANSITION），目标字段零写入。
     */
    public boolean transition(String runId, RunStatus from, RunStatus to, Map<String, Object> extraColumns) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE flow_run SET status = ?, updated_at = ?");
        args.add(to.name());
        args.add(Instant.now().toEpochMilli());
        for (Map.Entry<String, Object> e : extraColumns.entrySet()) {
            if (!ALLOWED_EXTRA_COLUMNS.contains(e.getKey())) {
                throw new IllegalArgumentException("transition 不允许更新列: " + e.getKey());
            }
            sql.append(", ").append(e.getKey()).append(" = ?");
            args.add(e.getValue());
        }
        sql.append(" WHERE run_id = ? AND status = ?");
        args.add(runId);
        args.add(from.name());
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    public void incrementWriteCount(String runId) {
        jdbc.update("UPDATE flow_run SET write_count = write_count + 1 WHERE run_id = ?", runId);
    }

    public void incrementReplayCount(String runId) {
        jdbc.update("UPDATE flow_run SET replay_count = replay_count + 1 WHERE run_id = ?", runId);
    }

    public int countAll() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flow_run", Integer.class);
    }

    public Map<String, Integer> countByStatus() {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS c FROM flow_run GROUP BY status", rs -> {
            result.put(rs.getString("status"), rs.getInt("c"));
        });
        return result;
    }

    public long totalWriteCount() {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(write_count), 0) FROM flow_run", Long.class);
        return v == null ? 0 : v;
    }

    public long totalSkipCount() {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM flow_run WHERE apply_result = 'SKIPPED'", Integer.class);
        return v == null ? 0 : v;
    }
}
