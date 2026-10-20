package com.demo.supplyagent.dto;

/**
 * 写工具的请求上下文（由 Controller 从请求头 + 已认证主体提取后传给 Service）：
 *   idempotencyKey = Idempotency-Key（业务幂等，create 必填）
 *   requestId      = X-Request-Id（仅链路追踪）
 *   conversationId = X-Conversation-Id（会话关联，可空）
 *   toolCallId     = X-Tool-Call-Id（仅 Agent 关联，可空）
 *   operator/role  = JWT sub 与角色（写审计：operator 存 sub，who 存角色）
 */
public record WriteContext(String idempotencyKey, String requestId, String conversationId,
                           String toolCallId, String operator, String role) {
}
