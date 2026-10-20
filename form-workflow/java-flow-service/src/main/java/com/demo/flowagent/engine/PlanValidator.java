package com.demo.flowagent.engine;

import com.demo.flowagent.api.dto.PlanRequest;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.domain.FormRecord;
import com.demo.flowagent.fixtures.FixtureLoader;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划校验（服务端真值）：
 * 拟写值与规则来源必须等于 rules.json 中该记录 mark_rule 的声明；
 * 幂等键必须与服务端复算一致；必填前置条件必须齐备。
 */
@Component
public class PlanValidator {

    static final String PLAN_VERSION = "1.0";
    static final String TARGET_FIELD = "outer_box_mark";

    private static final Set<String> KNOWN_PRECONDITION_KINDS = Set.of(
            "record_exists", "business_version_matches", "mark_rule_known",
            "target_field_differs", "idempotency_key_absent");

    private static final Set<String> REQUIRED_PRECONDITION_KINDS = Set.of(
            "record_exists", "business_version_matches", "mark_rule_known", "idempotency_key_absent");

    private final FixtureLoader fixtures;

    public PlanValidator(FixtureLoader fixtures) {
        this.fixtures = fixtures;
    }

    public void validate(PlanRequest plan, FormRecord record) {
        if (!PLAN_VERSION.equals(plan.planVersion())) {
            throw invalid("plan_version 必须为 " + PLAN_VERSION, Map.of("plan_version", plan.planVersion()));
        }
        if (!TARGET_FIELD.equals(plan.targetField())) {
            throw invalid("target_field 必须为 " + TARGET_FIELD, Map.of("target_field", plan.targetField()));
        }
        if (plan.flowType() != record.flowType()) {
            throw invalid("flow_type 与记录不一致", Map.of());
        }
        if (plan.businessVersion() != record.businessVersion()) {
            throw invalid("业务版本与记录当前版本不一致",
                    Map.of("plan_version_no", plan.businessVersion(), "record_version", record.businessVersion()));
        }

        FixtureLoader.RuleEntry rule = fixtures.rules().get(record.markRule());
        if (rule == null) {
            throw invalid("记录规则编码未知: " + record.markRule(), Map.of("mark_rule", record.markRule()));
        }
        if (!rule.proposedValue().equals(plan.proposedValue())) {
            throw invalid("拟写值不在规则表内", Map.of("mark_rule", record.markRule()));
        }
        if (!rule.ruleSource().equals(plan.ruleSource())) {
            throw invalid("规则来源与规则表声明不一致", Map.of("mark_rule", record.markRule()));
        }

        String expectedKey = IdempotencyKeys.derive(plan.sourceRecordId(), plan.flowType(), plan.businessVersion());
        if (!expectedKey.equals(plan.idempotencyKey())) {
            throw invalid("幂等键与服务端复算不一致", Map.of());
        }
        if (!Boolean.TRUE.equals(plan.requiresHumanApproval())) {
            throw invalid("本流转类型必须要求人工确认", Map.of());
        }

        Set<String> kinds = plan.preconditions().stream()
                .map(com.demo.flowagent.api.dto.PreconditionRequest::kind)
                .collect(Collectors.toSet());
        Set<String> unknown = new HashSet<>(kinds);
        unknown.removeAll(KNOWN_PRECONDITION_KINDS);
        if (!unknown.isEmpty()) {
            throw invalid("未知前置条件: " + unknown, Map.of("unknown_kinds", unknown));
        }
        Set<String> missing = new HashSet<>(REQUIRED_PRECONDITION_KINDS);
        missing.removeAll(kinds);
        if (!missing.isEmpty()) {
            throw invalid("缺少必需前置条件: " + missing, Map.of("missing_kinds", missing));
        }
    }

    private static FlowException invalid(String message, Map<String, Object> details) {
        return new FlowException(ErrorCode.PLAN_INVALID, message, details);
    }
}
