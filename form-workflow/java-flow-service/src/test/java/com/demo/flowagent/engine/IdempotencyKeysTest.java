package com.demo.flowagent.engine;

import com.demo.flowagent.domain.FlowType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 对照 fixtures/idempotency-vectors.json 的跨语言已知向量（Node 侧 Phase 2 复用同一文件）。 */
class IdempotencyKeysTest {

    @Test
    void derivesKnownVectorsFromSharedFixture() throws Exception {
        JsonNode file = new ObjectMapper().readTree(Files.newInputStream(Path.of("..", "fixtures", "idempotency-vectors.json")));
        assertThat(file.path("vectors").isArray()).isTrue();
        assertThat(file.path("vectors")).isNotEmpty();
        for (JsonNode vector : file.path("vectors")) {
            String recordId = vector.get("source_record_id").asText();
            FlowType flowType = FlowType.valueOf(vector.get("flow_type").asText());
            int version = vector.get("business_version").asInt();

            assertThat(IdempotencyKeys.derive(recordId, flowType, version))
                    .as("idempotency_key 向量: %s", recordId)
                    .isEqualTo(vector.get("idempotency_key").asText());
            assertThat(IdempotencyKeys.recordRef(recordId))
                    .as("record_ref 向量: %s", recordId)
                    .isEqualTo(vector.get("record_ref").asText());
        }
    }

    @Test
    void keyCarriesAllThreeComponentsSoDifferentVersionsNeverCollide() {
        String v3 = IdempotencyKeys.derive("R-1001", FlowType.PRELOAD_OUTER_BOX_MARK, 3);
        String v4 = IdempotencyKeys.derive("R-1001", FlowType.PRELOAD_OUTER_BOX_MARK, 4);
        String otherRecord = IdempotencyKeys.derive("R-1002", FlowType.PRELOAD_OUTER_BOX_MARK, 3);

        assertThat(v3).matches("^FLOW_IDK_[0-9a-f]{16}$");
        assertThat(v3).isNotEqualTo(v4);
        assertThat(v3).isNotEqualTo(otherRecord);
    }

    @Test
    void recordRefIsTruncatedHashNotTheRawId() {
        String ref = IdempotencyKeys.recordRef("R-1001");
        assertThat(ref).matches("^[0-9a-f]{12}$");
        assertThat(ref).doesNotContain("R-1001");
    }
}
