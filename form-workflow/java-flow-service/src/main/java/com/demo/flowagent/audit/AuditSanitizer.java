package com.demo.flowagent.audit;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计/产物写入口的 fail-closed 脱敏校验：
 * 命中敏感键名或敏感值模式时直接抛异常（写得进去 = 已经泄漏）。
 */
public final class AuditSanitizer {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "record_id", "table_id", "field_id", "app_token", "app_id");
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
            "token", "secret", "password", "authorization", "credential");
    private static final Set<String> FORBIDDEN_VALUE_PARTS = Set.of(
            "token-", "bearer ", "authorization:");

    private AuditSanitizer() {
    }

    public static void validateDetail(Map<String, Object> detail) {
        for (Map.Entry<String, Object> e : detail.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalStateException("审计详情键名禁止使用: " + key);
            }
            for (String part : FORBIDDEN_KEY_PARTS) {
                if (key.contains(part)) {
                    throw new IllegalStateException("审计详情键名疑似敏感: " + key);
                }
            }
            Object value = e.getValue();
            if (value instanceof String s) {
                String lower = s.toLowerCase(Locale.ROOT);
                for (String part : FORBIDDEN_VALUE_PARTS) {
                    if (lower.contains(part)) {
                        throw new IllegalStateException("审计详情值疑似敏感(键=" + key + ")");
                    }
                }
            }
        }
    }
}
