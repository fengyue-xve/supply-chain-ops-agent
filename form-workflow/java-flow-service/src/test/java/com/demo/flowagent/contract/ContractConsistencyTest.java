package com.demo.flowagent.contract;

import com.demo.flowagent.api.dto.ApplyOutcomeView;
import com.demo.flowagent.api.dto.FlowEventRequest;
import com.demo.flowagent.api.dto.PlanRequest;
import com.demo.flowagent.api.dto.RecordView;
import com.demo.flowagent.api.dto.RunView;
import com.demo.flowagent.api.dto.StatsView;
import com.demo.flowagent.api.dto.SubmitView;
import com.demo.flowagent.api.dto.ValidateView;
import com.demo.flowagent.domain.ApplyResult;
import com.demo.flowagent.domain.AuditEventType;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.RunStatus;
import com.demo.flowagent.engine.StateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 ↔ 实现一致性：contracts/*.json 是唯一真值来源，
 * 本测试双向锁死枚举、DTO 字段、工具路由表与状态机迁移表。
 */
@SpringBootTest
class ContractConsistencyTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private JsonNode schema(String fileName) throws IOException {
        try (InputStream in = Files.newInputStream(CONTRACTS.resolve(fileName))) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void enumDictionariesMatchCodeExactly() throws Exception {
        JsonNode runStatus = schema("run-status.schema.json");
        assertThat(enumNames(runStatus.at("/$defs/run_status/enum")))
                .isEqualTo(namesOf(RunStatus.values()));
        assertThat(enumNames(runStatus.at("/$defs/apply_result/enum")))
                .isEqualTo(namesOf(ApplyResult.values()));
        assertThat(enumNames(runStatus.at("/$defs/audit_entry/properties/event_type/enum")))
                .isEqualTo(namesOf(AuditEventType.values()));

        JsonNode errors = schema("errors.schema.json");
        assertThat(enumNames(errors.at("/$defs/error_code/enum")))
                .isEqualTo(namesOf(ErrorCode.values()));
    }

    @Test
    void everyToolRouteExistsInSpring() throws Exception {
        Set<String> actual = new HashSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues()
                    : info.getPatternsCondition().getPatterns();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                for (String pattern : patterns) {
                    actual.add(method.name() + " " + normalize(pattern));
                }
            }
        }

        JsonNode tools = schema("tools.schema.json");
        List<String> expected = new ArrayList<>();
        for (String group : List.of("agent_tools", "human_actions", "observability")) {
            tools.get(group).forEach(entry -> {
                if (entry.has("method") && entry.has("path")) {
                    expected.add(entry.get("method").asText() + " " + normalize(entry.get("path").asText()));
                }
            });
        }
        assertThat(expected).isNotEmpty();
        assertThat(actual).containsAll(expected);
    }

    @Test
    void dtoFieldsMatchSchemaFields() throws Exception {
        JsonNode runStatus = schema("run-status.schema.json");
        assertThat(snakeFields(RunView.class)).isEqualTo(requiredOf(runStatus));
        assertThat(snakeFields(RunView.ApprovalView.class))
                .containsAll(List.of("decision", "actor_id", "decided_at", "note"));
        assertThat(snakeFields(RunView.AuditEntryView.class))
                .isEqualTo(propertyNamesOf(runStatus.at("/$defs/audit_entry")));

        JsonNode record = schema("record.schema.json");
        assertThat(snakeFields(RecordView.class)).isEqualTo(requiredOf(record));
        assertThat(snakeFields(RecordView.MarkView.class)).isEqualTo(requiredOf(record.at("/properties/outer_box_mark")));

        JsonNode plan = schema("plan.schema.json");
        assertThat(snakeFields(PlanRequest.class)).isEqualTo(requiredOf(plan));

        JsonNode flowEvent = schema("flow-event.schema.json");
        assertThat(snakeFields(FlowEventRequest.class)).isEqualTo(propertyNamesOf(flowEvent));

        JsonNode tools = schema("tools.schema.json");
        assertThat(snakeFields(ValidateView.class))
                .isEqualTo(requiredOf(tools.at("/agent_tools/validate_transition/response")));
        assertThat(snakeFields(StatsView.class))
                .isEqualTo(requiredOf(tools.at("/observability/get_stats/response")));
        assertThat(snakeFields(ApplyOutcomeView.class)).isEqualTo(propertyNamesOf(tools.at("/apply_outcome")));
        assertThat(snakeFields(ApplyOutcomeView.ApplyRunView.class))
                .isEqualTo(requiredOf(tools.at("/apply_outcome/properties/run")));
        assertThat(snakeFields(SubmitView.class))
                .isEqualTo(requiredOf(tools.at("/human_actions/submit_event/response")));
    }

    @Test
    void declaredTransitionsMatchStateMachine() throws Exception {
        JsonNode tools = schema("tools.schema.json");
        int chains = 0;
        for (String group : List.of("agent_tools", "human_actions")) {
            for (JsonNode entry : tools.get(group)) {
                if (!entry.has("transition")) {
                    continue;
                }
                for (String chain : entry.get("transition").asText().split("\\|")) {
                    List<String> steps = Arrays.stream(chain.split("->")).map(String::trim).toList();
                    assertThat(steps.size()).as("迁移链 %s", chain).isGreaterThanOrEqualTo(2);
                    for (int i = 0; i + 1 < steps.size(); i++) {
                        RunStatus from = RunStatus.valueOf(steps.get(i));
                        RunStatus to = RunStatus.valueOf(steps.get(i + 1));
                        assertThat(StateMachine.allows(from, to))
                                .as("契约声明的迁移必须合法: %s -> %s", from, to)
                                .isTrue();
                        chains++;
                    }
                }
            }
        }
        assertThat(chains).isEqualTo(11);
    }

    private static Set<String> requiredOf(JsonNode node) {
        return toSet(node.get("required"));
    }

    private static Set<String> propertyNamesOf(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.get("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> toSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static Set<String> enumNames(JsonNode array) {
        return toSet(array);
    }

    private static Set<String> namesOf(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<String> snakeFields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .map(ContractConsistencyTest::camelToSnake)
                .collect(Collectors.toSet());
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }
}
