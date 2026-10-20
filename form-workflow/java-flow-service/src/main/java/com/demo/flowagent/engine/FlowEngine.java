package com.demo.flowagent.engine;

import com.demo.flowagent.api.RequestCounters;
import com.demo.flowagent.api.dto.ApplyOutcomeView;
import com.demo.flowagent.api.dto.FlowEventRequest;
import com.demo.flowagent.api.dto.NoteRequest;
import com.demo.flowagent.api.dto.PlanRequest;
import com.demo.flowagent.api.dto.RecordView;
import com.demo.flowagent.api.dto.RejectRequest;
import com.demo.flowagent.api.dto.RunView;
import com.demo.flowagent.api.dto.StatsView;
import com.demo.flowagent.api.dto.SubmitView;
import com.demo.flowagent.api.dto.ValidateRequest;
import com.demo.flowagent.api.dto.ValidateView;
import com.demo.flowagent.domain.Actor;
import com.demo.flowagent.domain.ApplyOutcome;
import com.demo.flowagent.domain.ApplyResult;
import com.demo.flowagent.domain.ApprovalDecision;
import com.demo.flowagent.domain.ApprovalEntity;
import com.demo.flowagent.domain.AuditEventType;
import com.demo.flowagent.domain.AuditRecord;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.domain.FlowRunEntity;
import com.demo.flowagent.domain.FormRecord;
import com.demo.flowagent.domain.RunStatus;
import com.demo.flowagent.fixtures.FixtureLoader;
import com.demo.flowagent.store.ApprovalStore;
import com.demo.flowagent.store.AuditStore;
import com.demo.flowagent.store.RecordStore;
import com.demo.flowagent.store.RunStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 流转编排核心：所有写路径的唯一入口。
 * 每次状态迁移都用守卫 UPDATE（WHERE status = ?）保证并发下不产生非法状态；
 * 记录写入只发生在 EXECUTING/REPLAYING 且审批通过之后。
 */
@Service
public class FlowEngine {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RunStore runs;
    private final RecordStore records;
    private final ApprovalStore approvals;
    private final AuditStore audits;
    private final FixtureLoader fixtures;
    private final PlanValidator planValidator;
    private final ObjectMapper mapper;
    private final RequestCounters counters;

    public FlowEngine(RunStore runs, RecordStore records, ApprovalStore approvals, AuditStore audits,
                      FixtureLoader fixtures, PlanValidator planValidator, ObjectMapper mapper,
                      RequestCounters counters) {
        this.runs = runs;
        this.records = records;
        this.approvals = approvals;
        this.audits = audits;
        this.fixtures = fixtures;
        this.planValidator = planValidator;
        this.mapper = mapper;
        this.counters = counters;
    }

    // ---------- 提交事件 ----------

    @Transactional
    public SubmitView submitEvent(FlowEventRequest request, Actor actor) {
        Optional<FlowRunEntity> byEvent = runs.findByEventId(request.eventId());
        if (byEvent.isPresent()) {
            FlowRunEntity existing = byEvent.get();
            audits.append(existing.runId(), AuditEventType.EVENT_DEDUPLICATED, actor.actorId(),
                    Map.of("reason", "duplicate_event_id", "event_id", request.eventId()));
            return new SubmitView(existing.runId(), existing.status(), true);
        }

        String key = IdempotencyKeys.derive(request.sourceRecordId(), request.flowType(), request.businessVersion());
        Optional<FlowRunEntity> byKey = runs.findByIdempotencyKey(key);
        if (byKey.isPresent()) {
            FlowRunEntity existing = byKey.get();
            audits.append(existing.runId(), AuditEventType.EVENT_DEDUPLICATED, actor.actorId(),
                    Map.of("reason", "idempotency_key_exists", "idempotency_key", key));
            return new SubmitView(existing.runId(), existing.status(), true);
        }

        FormRecord record = requireRecord(request.sourceRecordId());
        if (record.flowType() != request.flowType()) {
            throw new FlowException(ErrorCode.BAD_REQUEST, "flow_type 与记录不一致",
                    Map.of("request_flow_type", request.flowType().name(), "record_flow_type", record.flowType().name()));
        }
        if (request.businessVersion() < record.businessVersion()) {
            throw new FlowException(ErrorCode.STALE_EVENT, "事件业务版本落后于记录当前版本",
                    Map.of("event_business_version", request.businessVersion(),
                            "record_business_version", record.businessVersion()));
        }
        if (request.businessVersion() > record.businessVersion()) {
            throw new FlowException(ErrorCode.VERSION_MISMATCH, "事件业务版本超前于记录当前版本",
                    Map.of("event_business_version", request.businessVersion(),
                            "record_business_version", record.businessVersion()));
        }

        String runId = newRunId();
        Instant now = Instant.now();
        runs.insert(new FlowRunEntity(runId, request.eventId(), request.sourceRecordId(),
                request.flowType(), request.businessVersion(), key,
                RunStatus.RECEIVED, ApplyResult.NONE, null, null, 0, 0, now, now));
        audits.append(runId, AuditEventType.RUN_CREATED, actor.actorId(), Map.of(
                "record_ref", IdempotencyKeys.recordRef(request.sourceRecordId()),
                "flow_type", request.flowType().name(),
                "business_version", request.businessVersion(),
                "note_present", notePresent(request.note())));
        return new SubmitView(runId, RunStatus.RECEIVED, false);
    }

    // ---------- 计划 ----------

    @Transactional
    public RunView recordPlan(String runId, PlanRequest plan, Actor actor) {
        FlowRunEntity run = requireRun(runId);
        if (!run.sourceRecordId().equals(plan.sourceRecordId())) {
            throw new FlowException(ErrorCode.PLAN_INVALID, "计划来源记录与 run 不一致", Map.of());
        }
        if (plan.flowType() != run.flowType()) {
            throw new FlowException(ErrorCode.PLAN_INVALID, "计划流转类型与 run 不一致", Map.of());
        }
        if (plan.businessVersion() != run.businessVersion()) {
            throw new FlowException(ErrorCode.PLAN_INVALID, "计划业务版本与 run 不一致", Map.of());
        }
        FormRecord record = requireRecord(run.sourceRecordId());
        planValidator.validate(plan, record);

        requireTransition(runId, RunStatus.RECEIVED, RunStatus.PLANNED,
                Map.of("plan_json", writeJson(plan)), "plan");
        audits.append(runId, AuditEventType.PLAN_RECORDED, actor.actorId(), Map.of(
                "target_field", plan.targetField(),
                "proposed_value", plan.proposedValue(),
                "rule_source", plan.ruleSource(),
                "mark_rule", record.markRule()));
        return getRun(runId);
    }

    @Transactional
    public RunView requestApproval(String runId, NoteRequest request, Actor actor) {
        requireTransition(runId, RunStatus.PLANNED, RunStatus.WAITING_APPROVAL, Map.of(), "request-approval");
        audits.append(runId, AuditEventType.APPROVAL_REQUESTED, actor.actorId(),
                Map.of("note_present", notePresent(request == null ? null : request.note())));
        return getRun(runId);
    }

    // ---------- 审批 ----------

    @Transactional
    public RunView approve(String runId, NoteRequest request, Actor actor) {
        requireTransition(runId, RunStatus.WAITING_APPROVAL, RunStatus.EXECUTING, Map.of(), "approve");
        String note = request == null ? null : request.note();
        approvals.insert(new ApprovalEntity(runId, ApprovalDecision.APPROVED, actor.actorId(), note, Instant.now()));
        audits.append(runId, AuditEventType.APPROVED, actor.actorId(), Map.of("note_present", notePresent(note)));
        return getRun(runId);
    }

    @Transactional
    public RunView reject(String runId, RejectRequest request, Actor actor) {
        requireTransition(runId, RunStatus.WAITING_APPROVAL, RunStatus.REJECTED, Map.of(), "reject");
        approvals.insert(new ApprovalEntity(runId, ApprovalDecision.REJECTED, actor.actorId(), request.reason(), Instant.now()));
        audits.append(runId, AuditEventType.REJECTED, actor.actorId(), Map.of("reason_present", true));
        return getRun(runId);
    }

    // ---------- 执行 ----------

    @Transactional
    public ApplyOutcomeView apply(String runId, Actor actor) {
        FlowRunEntity run = requireRun(runId);
        if (run.status() == RunStatus.SUCCEEDED) {
            audits.append(runId, AuditEventType.APPLY_SKIPPED, actor.actorId(),
                    Map.of("reason", "already_succeeded"));
            return view(ApplyOutcome.SKIPPED, runId);
        }
        if (run.status() != RunStatus.EXECUTING) {
            throw new FlowException(ErrorCode.INVALID_TRANSITION,
                    "apply 仅允许 EXECUTING 状态（当前 " + run.status() + "）",
                    Map.of("current_status", run.status().name(), "expected_status", RunStatus.EXECUTING.name()));
        }
        ApprovalEntity approval = approvals.findByRunId(runId).orElse(null);
        if (approval == null || approval.decision() != ApprovalDecision.APPROVED) {
            throw new FlowException(ErrorCode.NOT_APPROVED, "run 缺少通过的人工审批，禁止写入", Map.of());
        }

        audits.append(runId, AuditEventType.APPLY_STARTED, actor.actorId(), Map.of());
        ApplyOutcome outcome = execute(run, actor, RunStatus.EXECUTING);
        return view(outcome, runId);
    }

    @Transactional
    public ApplyOutcomeView replay(String runId, Actor actor) {
        FlowRunEntity run = requireRun(runId);
        if (run.status() != RunStatus.MANUAL_REVIEW) {
            throw new FlowException(ErrorCode.INVALID_TRANSITION,
                    "replay 仅允许 MANUAL_REVIEW 状态（当前 " + run.status() + "）",
                    Map.of("current_status", run.status().name(), "expected_status", RunStatus.MANUAL_REVIEW.name()));
        }
        requireTransition(runId, RunStatus.MANUAL_REVIEW, RunStatus.REPLAYING, Map.of(), "replay");
        runs.incrementReplayCount(runId);
        audits.append(runId, AuditEventType.REPLAY_STARTED, actor.actorId(),
                Map.of("failure_reason", run.failureReason() == null ? "unknown" : run.failureReason()));

        ApplyOutcome outcome = execute(requireRun(runId), actor, RunStatus.REPLAYING);
        audits.append(runId, AuditEventType.REPLAY_FINISHED, actor.actorId(), Map.of("outcome", outcome.name()));
        return view(outcome, runId);
    }

    /**
     * 执行主体：版本前置复查 -> 同值比对 -> 故障注入 -> 守卫写入。
     * 所有失败路径最多把 run 推进到 MANUAL_REVIEW，绝不伪造成功。
     */
    private ApplyOutcome execute(FlowRunEntity run, Actor actor, RunStatus from) {
        String runId = run.runId();
        String recordId = run.sourceRecordId();
        FormRecord record = requireRecord(recordId);
        String ref = IdempotencyKeys.recordRef(recordId);

        if (record.businessVersion() != run.businessVersion()) {
            return fail(runId, from, actor, "PRECONDITION_VERSION_CHANGED", ref);
        }
        JsonNode plan = readNode(run.planJson());
        if (plan == null || !plan.hasNonNull("proposed_value") || !plan.hasNonNull("rule_source")) {
            throw new FlowException(ErrorCode.PLAN_INVALID, "run 缺少可执行的计划", Map.of());
        }
        String proposed = plan.get("proposed_value").asText();
        String ruleSource = plan.get("rule_source").asText();

        if (Objects.equals(record.markValue(), proposed)) {
            requireTransition(runId, from, RunStatus.SUCCEEDED,
                    Map.of("apply_result", ApplyResult.SKIPPED.name()), "apply");
            audits.append(runId, AuditEventType.APPLY_SKIPPED, actor.actorId(),
                    Map.of("reason", "same_value", "record_ref", ref, "mark_value", proposed));
            return ApplyOutcome.SKIPPED;
        }

        String fault = record.demoFault();
        Integer remaining = record.demoFaultRemaining();
        if ("WRITE_FAIL_ALWAYS".equals(fault)) {
            return fail(runId, from, actor, "WRITE_FAIL_ALWAYS", ref);
        }
        if ("WRITE_FAIL_ONCE".equals(fault) && remaining != null && remaining > 0) {
            records.consumeFault(recordId);
            return fail(runId, from, actor, "WRITE_FAIL_ONCE", ref);
        }
        if ("WRITE_UNCERTAIN".equals(fault) && remaining != null && remaining > 0) {
            records.consumeFault(recordId);
            int rows = records.applyMark(recordId, run.businessVersion(), proposed, ruleSource, Instant.now());
            if (rows != 1) {
                return fail(runId, from, actor, "PRECONDITION_WRITE_GUARD", ref);
            }
            runs.incrementWriteCount(runId);
            requireTransition(runId, from, RunStatus.FAILED, Map.of("failure_reason", "WRITE_UNCERTAIN"), "apply");
            audits.append(runId, AuditEventType.APPLY_UNCERTAIN, actor.actorId(),
                    Map.of("reason", "WRITE_UNCERTAIN", "record_ref", ref));
            enterManualReview(runId, actor, "WRITE_UNCERTAIN", ref);
            return ApplyOutcome.UNCERTAIN;
        }

        int rows = records.applyMark(recordId, run.businessVersion(), proposed, ruleSource, Instant.now());
        if (rows != 1) {
            return fail(runId, from, actor, "PRECONDITION_WRITE_GUARD", ref);
        }
        runs.incrementWriteCount(runId);
        requireTransition(runId, from, RunStatus.SUCCEEDED,
                Map.of("apply_result", ApplyResult.APPLIED.name()), "apply");
        audits.append(runId, AuditEventType.RECORD_WRITTEN, actor.actorId(),
                Map.of("record_ref", ref, "mark_value", proposed, "rule_source", ruleSource));
        return ApplyOutcome.APPLIED;
    }

    private ApplyOutcome fail(String runId, RunStatus from, Actor actor, String reason, String ref) {
        requireTransition(runId, from, RunStatus.FAILED, Map.of("failure_reason", reason), "apply");
        audits.append(runId, AuditEventType.APPLY_FAILED, actor.actorId(),
                Map.of("reason", reason, "record_ref", ref));
        enterManualReview(runId, actor, reason, ref);
        return ApplyOutcome.FAILED;
    }

    private void enterManualReview(String runId, Actor actor, String reason, String ref) {
        requireTransition(runId, RunStatus.FAILED, RunStatus.MANUAL_REVIEW, Map.of(), "apply");
        audits.append(runId, AuditEventType.MANUAL_REVIEW_ENTERED, actor.actorId(),
                Map.of("reason", reason, "record_ref", ref));
    }

    // ---------- 只读 ----------

    public ValidateView validateTransition(ValidateRequest request) {
        List<String> reasons = new ArrayList<>();
        Integer expectedRecordVersion = null;
        Optional<FormRecord> opt = records.find(request.sourceRecordId());
        if (opt.isEmpty()) {
            reasons.add("record_not_found");
        } else {
            FormRecord record = opt.get();
            expectedRecordVersion = record.businessVersion();
            if (request.businessVersion() < record.businessVersion()) {
                reasons.add("stale_version");
            }
            if (request.businessVersion() > record.businessVersion()) {
                reasons.add("version_mismatch");
            }
            if (!fixtures.rules().containsKey(record.markRule())) {
                reasons.add("mark_rule_unknown");
            }
            String key = IdempotencyKeys.derive(request.sourceRecordId(), request.flowType(), request.businessVersion());
            if (runs.findByIdempotencyKey(key).isPresent()) {
                reasons.add("flow_already_exists");
            }
        }
        return new ValidateView(reasons.isEmpty(), List.copyOf(reasons), expectedRecordVersion);
    }

    public RecordView getRecord(String recordId) {
        FormRecord record = requireRecord(recordId);
        RecordView.MarkView mark = record.markValue() == null ? null
                : new RecordView.MarkView(record.markValue(), record.markRuleSource(), record.markUpdatedAt());
        return new RecordView(record.recordId(), record.flowType(), record.businessVersion(),
                record.skuCode(), record.markRule(), mark, record.updatedAt());
    }

    public RunView getRun(String runId) {
        FlowRunEntity run = requireRun(runId);
        ApprovalEntity approval = approvals.findByRunId(runId).orElse(null);
        RunView.ApprovalView approvalView = approval == null ? null
                : new RunView.ApprovalView(approval.decision(), approval.actorId(), approval.decidedAt(), approval.note());
        List<AuditRecord> auditRecords = audits.listByRun(runId);
        List<RunView.AuditEntryView> audit = new ArrayList<>(auditRecords.size());
        for (int i = 0; i < auditRecords.size(); i++) {
            AuditRecord record = auditRecords.get(i);
            audit.add(new RunView.AuditEntryView(i + 1, record.ts(), record.eventType(),
                    record.actorId(), readNode(record.detailJson())));
        }
        return new RunView(run.runId(), run.sourceRecordId(), run.flowType(), run.businessVersion(),
                run.idempotencyKey(), run.status(), run.applyResult(), readNode(run.planJson()),
                approvalView, run.failureReason(), run.writeCount(), run.replayCount(),
                run.createdAt(), run.updatedAt(), audit);
    }

    public StatsView stats() {
        Map<String, Integer> byStatus = runs.countByStatus();
        return new StatsView(runs.countAll(), byStatus,
                runs.totalWriteCount(), runs.totalSkipCount(),
                byStatus.getOrDefault(RunStatus.MANUAL_REVIEW.name(), 0),
                counters.applyEndpointHits(), counters.replayEndpointHits());
    }

    // ---------- 辅助 ----------

    private FlowRunEntity requireRun(String runId) {
        return runs.findByRunId(runId)
                .orElseThrow(() -> new FlowException(ErrorCode.NOT_FOUND, "run 不存在: " + runId, Map.of()));
    }

    private FormRecord requireRecord(String recordId) {
        return records.find(recordId)
                .orElseThrow(() -> new FlowException(ErrorCode.NOT_FOUND, "记录不存在: " + recordId, Map.of()));
    }

    private void requireTransition(String runId, RunStatus from, RunStatus to, Map<String, Object> extra, String action) {
        StateMachine.require(from, to);
        if (!runs.transition(runId, from, to, extra)) {
            FlowRunEntity current = requireRun(runId);
            throw new FlowException(ErrorCode.INVALID_TRANSITION,
                    "非法状态迁移（" + action + "）: " + from + " -> " + to + "，当前状态 " + current.status(),
                    Map.of("current_status", current.status().name(),
                            "expected_status", from.name(), "target_status", to.name()));
        }
    }

    private ApplyOutcomeView view(ApplyOutcome outcome, String runId) {
        FlowRunEntity fresh = requireRun(runId);
        String message = switch (outcome) {
            case APPLIED -> "外箱唛已按计划写入";
            case SKIPPED -> "零重复写入：目标字段已是拟写值或已成功";
            case FAILED -> "执行失败，已转入人工复核";
            case UNCERTAIN -> "写入结果不确定，已转入人工复核，需 replay 对账";
        };
        return new ApplyOutcomeView(outcome,
                new ApplyOutcomeView.ApplyRunView(fresh.runId(), fresh.status(), fresh.applyResult(), fresh.writeCount()),
                message);
    }

    private static boolean notePresent(String note) {
        return note != null && !note.isBlank();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private JsonNode readNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private static String newRunId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("run-");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
