package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.WriteContext;
import com.demo.supplyagent.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * 写工具入口的请求头校验与上下文装配（create/confirm 共用）：
 *   Idempotency-Key：create 必填（缺失/空值/超长/非法字符 → 400）；confirm 不需要（重复确认由状态机 409 兜底），
 *   但若携带则同样校验合法性，不静默忽略。
 *   X-Request-Id / X-Conversation-Id / X-Tool-Call-Id：可选；给了就必须合法——空值或超长
 *   （列宽即上限）一律入口 400，不落到 DB 才报截断/500。缺省（未携带）= null。
 * 长度上限与 db/02_w3c_write_tools.sql 的列宽一一对应。
 */
final class WriteHeaders {

    static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String HEADER_CONVERSATION_ID = "X-Conversation-Id";
    static final String HEADER_TOOL_CALL_ID = "X-Tool-Call-Id";

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final int MAX_CONVERSATION_ID_LENGTH = 80;
    private static final int MAX_TOOL_CALL_ID_LENGTH = 64;

    /** 幂等键字符集受限：不允许空白/控制字符，避免产生肉眼不可区分的键。 */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");

    private WriteHeaders() {
    }

    static String requireIdempotencyKey(HttpServletRequest request) {
        String key = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "缺少 Idempotency-Key 请求头");
        }
        return checkedIdempotencyKey(key);
    }

    /** confirm 不消费幂等键；但携带了就必须合法（空值/超长/非法字符 400），不静默忽略。 */
    static String optionalIdempotencyKey(HttpServletRequest request) {
        String key = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (key == null) {
            return null;
        }
        if (key.isBlank()) {
            throw new ApiException(400, "Idempotency-Key 不能为空");
        }
        return checkedIdempotencyKey(key);
    }

    private static String checkedIdempotencyKey(String key) {
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(400, "Idempotency-Key 不能超过 " + MAX_IDEMPOTENCY_KEY_LENGTH + " 字符");
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(400, "Idempotency-Key 仅允许字母、数字与 _ . : -");
        }
        return key;
    }

    static WriteContext context(HttpServletRequest request, String idempotencyKey, AuthUser user) {
        return new WriteContext(
                idempotencyKey,
                optionalHeader(request, HEADER_REQUEST_ID, MAX_REQUEST_ID_LENGTH),
                optionalHeader(request, HEADER_CONVERSATION_ID, MAX_CONVERSATION_ID_LENGTH),
                optionalHeader(request, HEADER_TOOL_CALL_ID, MAX_TOOL_CALL_ID_LENGTH),
                user.username(), user.role());
    }

    /** 工具执行入口在 anyRequest().authenticated() 之后，主体必为 JwtAuthFilter 放入的 AuthUser。 */
    static AuthUser authenticatedUser() {
        return (AuthUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private static String optionalHeader(HttpServletRequest request, String header, int maxLength) {
        String value = request.getHeader(header);
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ApiException(400, header + " 不能为空");
        }
        if (value.length() > maxLength) {
            throw new ApiException(400, header + " 不能超过 " + maxLength + " 字符");
        }
        return value;
    }
}
