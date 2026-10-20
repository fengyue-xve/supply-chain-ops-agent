package com.demo.flowagent.api;

import com.demo.flowagent.domain.FlowType;
import com.demo.flowagent.engine.FlowEngine;
import com.demo.flowagent.engine.IdempotencyKeys;
import com.demo.flowagent.fixtures.FixtureSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Java 侧验收场景全集（kickoff 测试矩阵 1-12）。
 * 单库串行执行，顺序即剧本；末尾把 stats 与审计轨迹导出到 target/（供 verify.ps1 汇总）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flowagent_scenario;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowScenarioTest {

    private static final String TOKEN_OP = "token-op-1";
    private static final String TOKEN_AP = "token-ap-1";
    private static final String TOKEN_AGENT = "token-agent-1";
    private static final String FLOW = "PRELOAD_OUTER_BOX_MARK";

    private static final String NOTE_CANARY_SUBMIT = "机密备注-canary-submit";
    private static final String NOTE_CANARY_APPROVAL = "机密备注-canary-approval-req";
    private static final String REJECT_REASON = "业务原因-审批拒绝-canary-reject";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FlowEngine engine;
    @Autowired
    private FixtureSeeder seeder;

    private String run1001;
    private String run1003;
    private String run1002;
    private String run9002;
    private String run9003;
    private String run9004;
    private String run8001;

    private int scannedAuditRows;
    private final List<String> forbiddenHits = new ArrayList<>();

    @BeforeAll
    void resetDatabase() {
        jdbc.update("DELETE FROM flow_audit");
        jdbc.update("DELETE FROM flow_approval");
        jdbc.update("DELETE FROM flow_run");
        jdbc.update("DELETE FROM form_record");
        seeder.run(null);
    }

    // ---------- 0. 鉴权与角色 ----------

    @Test
    @Order(1)
    void authAndRoleBoundaries() throws Exception {
        assertError(performGet("/api/flow/stats", null), 401, "UNAUTHENTICATED");
        assertError(performGet("/api/flow/stats", "token-unknown"), 401, "UNAUTHENTICATED");
        assertError(performGet("/api/flow/stats", TOKEN_OP), 200, null);

        JsonNode record = json(performGet("/api/flow/records/R-1001", TOKEN_OP));
        assertThat(record.get("record_id").asText()).isEqualTo("R-1001");
        assertThat(record.get("business_version").asInt()).isEqualTo(3);
        assertThat(record.get("outer_box_mark").isNull()).isTrue();

        assertError(performGet("/api/flow/records/R-9999", TOKEN_OP), 404, "NOT_FOUND");
        assertError(performPost("/api/flow/validate-transition", TOKEN_OP,
                validateBody("R-1001", 3)), 403, "FORBIDDEN_ROLE");
    }

    // ---------- 1. validate_transition 只读预检 ----------

    @Test
    @Order(2)
    void validateTransitionPreChecks() throws Exception {
        JsonNode ok = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-1001", 3)));
        assertThat(ok.get("allowed").asBoolean()).isTrue();
        assertThat(ok.get("reasons")).isEmpty();
        assertThat(ok.get("expected_record_version").asInt()).isEqualTo(3);

        JsonNode unknown = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-9999", 1)));
        assertThat(unknown.get("allowed").asBoolean()).isFalse();
        assertThat(reasonsOf(unknown)).containsExactly("record_not_found");
        assertThat(unknown.get("expected_record_version").isNull()).isTrue();

        JsonNode stale = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-1001", 2)));
        assertThat(reasonsOf(stale)).containsExactly("stale_version");

        JsonNode ahead = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-1001", 4)));
        assertThat(reasonsOf(ahead)).containsExactly("version_mismatch");
    }

    // ---------- 2. 重复事件去重 + 未审批零写入（矩阵 1、10） ----------

    @Test
    @Order(3)
    void duplicateEventsDedupAndUnapprovedWritesNothing() throws Exception {
        JsonNode submit = json(performPost("/api/flow/events", TOKEN_OP,
                eventBody("evt-001", "R-1001", 3, NOTE_CANARY_SUBMIT)));
        run1001 = submit.get("run_id").asText();
        assertThat(submit.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(submit.get("deduplicated").asBoolean()).isFalse();

        JsonNode dupSameEvent = json(performPost("/api/flow/events", TOKEN_OP,
                eventBody("evt-001", "R-1001", 3, null)));
        assertThat(dupSameEvent.get("deduplicated").asBoolean()).isTrue();
        assertThat(dupSameEvent.get("run_id").asText()).isEqualTo(run1001);

        JsonNode dupSameKey = json(performPost("/api/flow/events", TOKEN_OP,
                eventBody("evt-011", "R-1001", 3, null)));
        assertThat(dupSameKey.get("deduplicated").asBoolean()).isTrue();
        assertThat(dupSameKey.get("run_id").asText()).isEqualTo(run1001);

        JsonNode plan = json(performPost(planPath(run1001), TOKEN_AGENT, planBody("R-1001", 3)));
        assertThat(plan.get("status").asText()).isEqualTo("PLANNED");
        assertThat(plan.get("write_count").asInt()).isZero();

        JsonNode waiting = json(performPost(approvalPath(run1001), TOKEN_AGENT,
                Map.of("note", NOTE_CANARY_APPROVAL)));
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");

        assertError(performPost(applyPath(run1001), TOKEN_AGENT, null), 409, "INVALID_TRANSITION");
        assertThat(json(performGet("/api/flow/records/R-1001", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();

        assertError(performPost(approvePath(run1001), TOKEN_OP, null), 403, "FORBIDDEN_ROLE");
        assertError(performPost(approvePath(run1001), TOKEN_AGENT, null), 403, "FORBIDDEN_ROLE");
        JsonNode stillWaiting = json(performGet("/api/flow/runs/" + run1001, TOKEN_OP));
        assertThat(stillWaiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(stillWaiting.get("approval").isNull()).isTrue();
        assertThat(json(performGet("/api/flow/records/R-1001", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();

        JsonNode revalidate = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-1001", 3)));
        assertThat(revalidate.get("allowed").asBoolean()).isFalse();
        assertThat(reasonsOf(revalidate)).contains("flow_already_exists");
    }

    // ---------- 3. 审批后单次写入 + 幂等复跑（矩阵 2、3） ----------

    @Test
    @Order(4)
    void approvedAppliesExactlyOnceThenSkipsRerun() throws Exception {
        JsonNode executing = json(performPost(approvePath(run1001), TOKEN_AP, null));
        assertThat(executing.get("status").asText()).isEqualTo("EXECUTING");
        assertThat(executing.get("approval").get("decision").asText()).isEqualTo("APPROVED");
        assertThat(executing.get("approval").get("actor_id").asText()).isEqualTo("ap-1");

        JsonNode applied = json(performPost(applyPath(run1001), TOKEN_AGENT, null));
        assertThat(applied.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(applied.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(applied.get("run").get("write_count").asInt()).isEqualTo(1);

        JsonNode record = json(performGet("/api/flow/records/R-1001", TOKEN_OP));
        assertThat(record.get("outer_box_mark").get("value").asText()).isEqualTo("LZL04");
        assertThat(record.get("outer_box_mark").get("rule_source").asText()).isEqualTo("fixture-rule:box-a-v1");

        JsonNode rerun = json(performPost(applyPath(run1001), TOKEN_AGENT, null));
        assertThat(rerun.get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(rerun.get("run").get("write_count").asInt()).isEqualTo(1);

        JsonNode run = json(performGet("/api/flow/runs/" + run1001, TOKEN_OP));
        List<String> auditTypes = new ArrayList<>();
        run.get("audit").forEach(entry -> auditTypes.add(entry.get("event_type").asText()));
        assertThat(auditTypes).containsExactly(
                "RUN_CREATED", "EVENT_DEDUPLICATED", "EVENT_DEDUPLICATED",
                "PLAN_RECORDED", "APPROVAL_REQUESTED", "APPROVED",
                "APPLY_STARTED", "RECORD_WRITTEN", "APPLY_SKIPPED");
    }

    // ---------- 4. 非法迁移零副作用（矩阵 5） ----------

    @Test
    @Order(5)
    void illegalTransitionsAreRejected() throws Exception {
        assertError(performPost(planPath(run1001), TOKEN_AGENT, planBody("R-1001", 3)), 409, "INVALID_TRANSITION");
        assertError(performPost("/api/flow/runs/" + run1001 + "/replay", TOKEN_AGENT, null), 409, "INVALID_TRANSITION");
        assertError(performPost(approvePath(run1001), TOKEN_AP, null), 409, "INVALID_TRANSITION");
        assertError(performPost(rejectPath(run1001), TOKEN_AP, Map.of("reason", "late reject")), 409, "INVALID_TRANSITION");
        assertError(performPost(approvalPath(run1001), TOKEN_AGENT, null), 409, "INVALID_TRANSITION");
        assertError(performGet("/api/flow/runs/run-000000000000", TOKEN_OP), 404, "NOT_FOUND");
        assertThat(json(performGet("/api/flow/stats", TOKEN_OP)).get("total_write_count").asLong()).isEqualTo(1);
    }

    // ---------- 5. 同值写入零重复（矩阵 4） ----------

    @Test
    @Order(6)
    void sameValueWriteSkipsWithoutDuplicateChange() throws Exception {
        run1003 = toExecuting("evt-003", "R-1003", 2);

        JsonNode outcome = json(performPost(applyPath(run1003), TOKEN_AGENT, null));
        assertThat(outcome.get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(outcome.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(outcome.get("run").get("apply_result").asText()).isEqualTo("SKIPPED");
        assertThat(outcome.get("run").get("write_count").asInt()).isZero();

        JsonNode record = json(performGet("/api/flow/records/R-1003", TOKEN_OP));
        assertThat(record.get("outer_box_mark").get("value").asText()).isEqualTo("LZL04");

        JsonNode run = json(performGet("/api/flow/runs/" + run1003, TOKEN_OP));
        List<String> auditTypes = new ArrayList<>();
        run.get("audit").forEach(entry -> auditTypes.add(entry.get("event_type").asText()));
        assertThat(auditTypes).contains("APPLY_SKIPPED");

        // 总写入次数仍为第一次 R-1001 的 1 次
        assertThat(json(performGet("/api/flow/stats", TOKEN_OP)).get("total_write_count").asLong()).isEqualTo(1);
    }

    // ---------- 6. 拒绝路径零写入 + 审批意见留存边界 ----------

    @Test
    @Order(7)
    void rejectedRunNeverWritesAndKeepsReasonOnlyInApprovalRecord() throws Exception {
        run1002 = toWaitingApproval("evt-002", "R-1002", 1);

        assertError(performPost(applyPath(run1002), TOKEN_AGENT, null), 409, "INVALID_TRANSITION");
        assertThat(json(performGet("/api/flow/records/R-1002", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();

        JsonNode rejected = json(performPost(rejectPath(run1002), TOKEN_AP, Map.of("reason", REJECT_REASON)));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("approval").get("decision").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("approval").get("note").asText()).isEqualTo(REJECT_REASON);

        assertError(performPost(applyPath(run1002), TOKEN_AGENT, null), 409, "INVALID_TRANSITION");
        assertError(performPost(approvePath(run1002), TOKEN_AP, null), 409, "INVALID_TRANSITION");
        assertThat(json(performGet("/api/flow/records/R-1002", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();
    }

    // ---------- 7. 注入失败 -> MANUAL_REVIEW，replay 后成功（矩阵 7、8） ----------

    @Test
    @Order(8)
    void injectedFailureGoesToManualReviewAndReplaySucceeds() throws Exception {
        run9002 = toExecuting("evt-101", "R-9002", 4);

        JsonNode failed = json(performPost(applyPath(run9002), TOKEN_AGENT, null));
        assertThat(failed.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(failed.get("run").get("status").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(failed.get("run").get("write_count").asInt()).isZero();
        JsonNode failedRun = json(performGet("/api/flow/runs/" + run9002, TOKEN_OP));
        assertThat(failedRun.get("failure_reason").asText()).isEqualTo("WRITE_FAIL_ONCE");
        assertThat(json(performGet("/api/flow/records/R-9002", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();

        JsonNode replayed = json(performPost("/api/flow/runs/" + run9002 + "/replay", TOKEN_AGENT, null));
        assertThat(replayed.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(replayed.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(replayed.get("run").get("write_count").asInt()).isEqualTo(1);
        assertThat(json(performGet("/api/flow/records/R-9002", TOKEN_OP))
                .get("outer_box_mark").get("value").asText()).isEqualTo("LZL04");

        assertError(performPost("/api/flow/runs/" + run9002 + "/replay", TOKEN_AGENT, null),
                409, "INVALID_TRANSITION");
    }

    // ---------- 8. 计划校验 422 + 持续失败 replay 仍失败（矩阵 8） ----------

    @Test
    @Order(9)
    void planValidationAndPersistentFailureStayInManualReview() throws Exception {
        JsonNode submit = json(performPost("/api/flow/events", TOKEN_OP, eventBody("evt-102", "R-9003", 5, null)));
        run9003 = submit.get("run_id").asText();

        assertError(performPost(planPath(run9003), TOKEN_OP, planBody("R-9003", 5)), 403, "FORBIDDEN_ROLE");

        Map<String, Object> base = planBody("R-9003", 5);
        assertPlanInvalid(with(base, "proposed_value", "ZZZWRONG"));
        assertPlanInvalid(with(base, "rule_source", "fixture-rule:unknown-v9"));
        assertPlanInvalid(with(base, "idempotency_key", "FLOW_IDK_0123456789abcdef"));
        assertPlanInvalid(with(base, "plan_version", "2.0"));
        assertPlanInvalid(with(base, "requires_human_approval", false));
        assertPlanInvalid(with(base, "target_field", "sku_code"));
        assertPlanInvalid(with(base, "business_version", 999));
        assertPlanInvalid(with(base, "preconditions", List.of(
                Map.of("kind", "record_exists"),
                Map.of("kind", "business_version_matches"),
                Map.of("kind", "mark_rule_known"))));
        assertPlanInvalid(with(base, "preconditions", List.of(Map.of("kind", "skip_all_checks"))));

        JsonNode stillReceived = json(performGet("/api/flow/runs/" + run9003, TOKEN_OP));
        assertThat(stillReceived.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(stillReceived.get("plan").isNull()).isTrue();

        JsonNode planned = json(performPost(planPath(run9003), TOKEN_AGENT, base));
        assertThat(planned.get("status").asText()).isEqualTo("PLANNED");
        json(performPost(approvalPath(run9003), TOKEN_AGENT, null));
        json(performPost(approvePath(run9003), TOKEN_AP, null));

        JsonNode failed = json(performPost(applyPath(run9003), TOKEN_AGENT, null));
        assertThat(failed.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(failed.get("run").get("status").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(json(performGet("/api/flow/runs/" + run9003, TOKEN_OP))
                .get("failure_reason").asText()).isEqualTo("WRITE_FAIL_ALWAYS");

        JsonNode replayed = json(performPost("/api/flow/runs/" + run9003 + "/replay", TOKEN_AGENT, null));
        assertThat(replayed.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(replayed.get("run").get("status").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(json(performGet("/api/flow/runs/" + run9003, TOKEN_OP)).get("replay_count").asInt()).isEqualTo(1);
        assertThat(json(performGet("/api/flow/records/R-9003", TOKEN_OP)).get("outer_box_mark").isNull()).isTrue();
    }

    // ---------- 9. 结果不确定 -> replay 对账去重（矩阵 8） ----------

    @Test
    @Order(10)
    void uncertainWriteIsReconciledByReplayWithoutDoubleWrite() throws Exception {
        run9004 = toExecuting("evt-103", "R-9004", 6);

        JsonNode uncertain = json(performPost(applyPath(run9004), TOKEN_AGENT, null));
        assertThat(uncertain.get("outcome").asText()).isEqualTo("UNCERTAIN");
        assertThat(uncertain.get("run").get("status").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(uncertain.get("run").get("write_count").asInt()).isEqualTo(1);
        assertThat(json(performGet("/api/flow/records/R-9004", TOKEN_OP))
                .get("outer_box_mark").get("value").asText()).isEqualTo("BA99");

        JsonNode replayed = json(performPost("/api/flow/runs/" + run9004 + "/replay", TOKEN_AGENT, null));
        assertThat(replayed.get("outcome").asText()).isEqualTo("SKIPPED");
        assertThat(replayed.get("run").get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(replayed.get("run").get("apply_result").asText()).isEqualTo("SKIPPED");
        assertThat(replayed.get("run").get("write_count").asInt()).isEqualTo(1);
    }

    // ---------- 10. 乱序/旧版本事件不可覆盖（矩阵 9） ----------

    @Test
    @Order(11)
    void staleAndOutOfOrderEventsCannotOverwriteNewerVersions() throws Exception {
        long runsBefore = json(performGet("/api/flow/stats", TOKEN_OP)).get("run_count").asLong();

        assertError(performPost("/api/flow/events", TOKEN_OP, eventBody("evt-201", "R-8001", 6, null)),
                409, "STALE_EVENT");
        assertError(performPost("/api/flow/events", TOKEN_OP, eventBody("evt-202", "R-8001", 9, null)),
                409, "VERSION_MISMATCH");
        assertThat(json(performGet("/api/flow/stats", TOKEN_OP)).get("run_count").asLong()).isEqualTo(runsBefore);

        JsonNode revalidate = json(performPost("/api/flow/validate-transition", TOKEN_AGENT, validateBody("R-8001", 6)));
        assertThat(revalidate.get("allowed").asBoolean()).isFalse();
        assertThat(reasonsOf(revalidate)).contains("stale_version");

        run8001 = toExecuting("evt-203", "R-8001", 7);
        JsonNode applied = json(performPost(applyPath(run8001), TOKEN_AGENT, null));
        assertThat(applied.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(json(performGet("/api/flow/records/R-8001", TOKEN_OP))
                .get("outer_box_mark").get("value").asText()).isEqualTo("BA99");
    }

    // ---------- 11. 终局统计对账 ----------

    @Test
    @Order(12)
    void finalStatsMatchEveryScenario() throws Exception {
        JsonNode stats = json(performGet("/api/flow/stats", TOKEN_OP));
        assertThat(stats.get("run_count").asInt()).isEqualTo(7);
        assertThat(stats.get("runs_by_status").get("SUCCEEDED").asInt()).isEqualTo(5);
        assertThat(stats.get("runs_by_status").get("REJECTED").asInt()).isEqualTo(1);
        assertThat(stats.get("runs_by_status").get("MANUAL_REVIEW").asInt()).isEqualTo(1);
        assertThat(stats.get("total_write_count").asLong()).isEqualTo(4);
        assertThat(stats.get("total_skip_count").asLong()).isEqualTo(2);
        assertThat(stats.get("total_manual_review").asLong()).isEqualTo(1);
    }

    // ---------- 12. 审计脱敏扫描（矩阵 12） ----------

    @Test
    @Order(13)
    void auditTrailContainsNoSensitivePatterns() {
        Pattern rawRecordId = Pattern.compile("R-[0-9]{4}");
        List<String> tokens = List.of(TOKEN_OP, TOKEN_AP, TOKEN_AGENT);
        List<String> forbiddenLiterals = List.of("bearer", "authorization:", "token-",
                NOTE_CANARY_SUBMIT, NOTE_CANARY_APPROVAL, REJECT_REASON);

        jdbc.query("SELECT detail_json, actor_id FROM flow_audit", (RowCallbackHandler) rs -> {
            scannedAuditRows++;
            String haystack = (String.valueOf(rs.getString("detail_json")) + " " + rs.getString("actor_id"))
                    .toLowerCase();
            for (String token : tokens) {
                if (haystack.contains(token)) {
                    forbiddenHits.add(token);
                }
            }
            for (String literal : forbiddenLiterals) {
                if (haystack.contains(literal.toLowerCase())) {
                    forbiddenHits.add(literal);
                }
            }
            Matcher matcher = rawRecordId.matcher(haystack);
            while (matcher.find()) {
                forbiddenHits.add(matcher.group());
            }
        });
        assertThat(scannedAuditRows).isGreaterThan(20);
        assertThat(forbiddenHits).isEmpty();

        // 提交/申请备注（自由文本）不得落入任何 Java 存储；审批决定意见属于业务记录表
        List<String> allStoredText = new ArrayList<>();
        jdbc.query("SELECT plan_json, failure_reason FROM flow_run", (RowCallbackHandler) rs -> {
            allStoredText.add(String.valueOf(rs.getString("plan_json")));
            allStoredText.add(String.valueOf(rs.getString("failure_reason")));
        });
        jdbc.query("SELECT note FROM flow_approval",
                (RowCallbackHandler) rs -> allStoredText.add(String.valueOf(rs.getString("note"))));
        String joined = String.join(" ", allStoredText);
        assertThat(joined).doesNotContain(NOTE_CANARY_SUBMIT).doesNotContain(NOTE_CANARY_APPROVAL);
        assertThat(joined).contains(REJECT_REASON);
    }

    // ---------- 导出：审计轨迹 + 汇总（verify.ps1 读取） ----------

    @AfterAll
    void exportTracesAndSummary() throws Exception {
        Path traceDir = Path.of("target", "trace");
        Files.createDirectories(traceDir);
        try (var oldTraces = Files.list(traceDir)) {
            for (Path stale : oldTraces.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.deleteIfExists(stale);
            }
        }
        List<String> runIds = jdbc.queryForList("SELECT run_id FROM flow_run ORDER BY created_at", String.class);
        for (String runId : runIds) {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(traceDir.resolve(runId + ".json").toFile(), engine.getRun(runId));
        }

        JsonNode stats = mapper.valueToTree(engine.stats());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generated_at", java.time.Instant.now().toString());
        summary.put("java_tests", "见 target/surefire-reports 汇总");
        summary.put("stats", stats);
        summary.put("trace_dir", traceDir.toAbsolutePath().toString());
        summary.put("trace_files", runIds);
        summary.put("redaction_scan", Map.of(
                "scanned_audit_rows", scannedAuditRows,
                "forbidden_hits", forbiddenHits,
                "ok", forbiddenHits.isEmpty()));
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target", "verify-summary.json").toFile(), summary);
    }

    // ---------- 工具方法 ----------

    private String toExecuting(String eventId, String recordId, int version) throws Exception {
        JsonNode submit = json(performPost("/api/flow/events", TOKEN_OP, eventBody(eventId, recordId, version, null)));
        assertThat(submit.get("deduplicated").asBoolean()).isFalse();
        String runId = submit.get("run_id").asText();
        assertThat(json(performPost(planPath(runId), TOKEN_AGENT,
                planBody(recordId, version))).get("status").asText()).isEqualTo("PLANNED");
        assertThat(json(performPost(approvalPath(runId), TOKEN_AGENT, null)).get("status").asText())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(json(performPost(approvePath(runId), TOKEN_AP, null)).get("status").asText())
                .isEqualTo("EXECUTING");
        return runId;
    }

    private String toWaitingApproval(String eventId, String recordId, int version) throws Exception {
        JsonNode submit = json(performPost("/api/flow/events", TOKEN_OP, eventBody(eventId, recordId, version, null)));
        String runId = submit.get("run_id").asText();
        assertThat(json(performPost(planPath(runId), TOKEN_AGENT,
                planBody(recordId, version))).get("status").asText()).isEqualTo("PLANNED");
        assertThat(json(performPost(approvalPath(runId), TOKEN_AGENT, null)).get("status").asText())
                .isEqualTo("WAITING_APPROVAL");
        return runId;
    }

    private String planPath(String runId) {
        return "/api/flow/runs/" + runId + "/plan";
    }

    private String approvalPath(String runId) {
        return "/api/flow/runs/" + runId + "/request-approval";
    }

    private String approvePath(String runId) {
        return "/api/flow/runs/" + runId + "/approve";
    }

    private String rejectPath(String runId) {
        return "/api/flow/runs/" + runId + "/reject";
    }

    private String applyPath(String runId) {
        return "/api/flow/runs/" + runId + "/apply";
    }

    private Map<String, Object> eventBody(String eventId, String recordId, int version, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("source_record_id", recordId);
        body.put("flow_type", FLOW);
        body.put("business_version", version);
        body.put("submitted_by", "op-1");
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private Map<String, Object> validateBody(String recordId, int version) {
        return Map.of("source_record_id", recordId, "flow_type", FLOW, "business_version", version);
    }

    private Map<String, Object> planBody(String recordId, int version) {
        boolean ruleB = usesRuleB(recordId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan_version", "1.0");
        body.put("source_record_id", recordId);
        body.put("flow_type", FLOW);
        body.put("business_version", version);
        body.put("target_field", "outer_box_mark");
        body.put("proposed_value", ruleB ? "BA99" : "LZL04");
        body.put("rule_source", ruleB ? "fixture-rule:box-b-v1" : "fixture-rule:box-a-v1");
        body.put("preconditions", List.of(
                Map.of("kind", "record_exists"),
                Map.of("kind", "business_version_matches"),
                Map.of("kind", "mark_rule_known"),
                Map.of("kind", "target_field_differs"),
                Map.of("kind", "idempotency_key_absent")));
        body.put("idempotency_key", keyFor(recordId, version));
        body.put("risk", "MEDIUM");
        body.put("requires_human_approval", true);
        return body;
    }

    private static boolean usesRuleB(String recordId) {
        return List.of("R-1002", "R-8001", "R-9003", "R-9004").contains(recordId);
    }

    private String keyFor(String recordId, int version) {
        return IdempotencyKeys.derive(recordId, FlowType.PRELOAD_OUTER_BOX_MARK, version);
    }

    private Map<String, Object> with(Map<String, Object> base, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(field, value);
        return copy;
    }

    private void assertPlanInvalid(Map<String, Object> body) throws Exception {
        assertError(performPost(planPath(run9003), TOKEN_AGENT, body), 422, "PLAN_INVALID");
    }

    private List<String> reasonsOf(JsonNode node) {
        List<String> reasons = new ArrayList<>();
        node.get("reasons").forEach(r -> reasons.add(r.asText()));
        return reasons;
    }

    private void assertError(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status));
        if (code != null) {
            assertThat(json(actions).get("error_code").asText()).isEqualTo(code);
        }
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions performGet(String path, String token) throws Exception {
        var builder = get(path);
        if (token != null) {
            builder.header(ActorResolver.TOKEN_HEADER, token);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions performPost(String path, String token, Object body) throws Exception {
        var builder = post(path).contentType("application/json");
        if (token != null) {
            builder.header(ActorResolver.TOKEN_HEADER, token);
        }
        if (body != null) {
            builder.content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder);
    }
}
