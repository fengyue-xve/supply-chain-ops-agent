package com.demo.flowagent.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** fail-closed：命中敏感键/值模式时必须抛异常，宁可写失败也不许泄漏。 */
class AuditSanitizerTest {

    @Test
    void rejectsForbiddenKeyNames() {
        for (String key : new String[]{"record_id", "table_id", "field_id", "app_token", "app_id"}) {
            assertThatThrownBy(() -> AuditSanitizer.validateDetail(Map.of(key, "x")))
                    .as("键名 %s", key)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsKeysContainingSensitiveParts() {
        for (String key : new String[]{"my_token", "clientSecret", "PASSWORD", "authorizationHeader", "credential_ref"}) {
            assertThatThrownBy(() -> AuditSanitizer.validateDetail(Map.of(key, "x")))
                    .as("键名 %s", key)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsSensitiveValuePatterns() {
        assertThatThrownBy(() -> AuditSanitizer.validateDetail(Map.of("note", "token-op-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditSanitizer.validateDetail(Map.of("header", "Bearer abc")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuditSanitizer.validateDetail(Map.of("header", "Authorization: xyz")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsRedactedBusinessDetails() {
        assertThatCode(() -> AuditSanitizer.validateDetail(Map.of(
                "record_ref", "aeb28f94adb0",
                "mark_value", "LZL04",
                "rule_source", "fixture-rule:box-a-v1",
                "business_version", 3,
                "note_present", true)))
                .doesNotThrowAnyException();
    }
}
