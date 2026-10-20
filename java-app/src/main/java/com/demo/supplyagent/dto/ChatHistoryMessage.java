package com.demo.supplyagent.dto;

/**
 * 传给 FastAPI 的历史消息（Java 侧裁剪后的纯文本轮次）。
 * 只承载 user/assistant 文本：不传 tool 协议消息、不传 Authorization/异常/DB 行。
 */
public record ChatHistoryMessage(String role, String content) {
}
