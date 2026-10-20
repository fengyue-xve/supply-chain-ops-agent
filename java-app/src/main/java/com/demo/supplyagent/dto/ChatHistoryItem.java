package com.demo.supplyagent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * W4-1 会话历史单条消息（只返回消息本身，不返回 DB 行全貌）。
 * toolCalls 为落库时的工具明细数组原样解析（含失败明细）；解析失败为 null，不因此 500。
 */
@Data
@AllArgsConstructor
public class ChatHistoryItem {

    private String role;

    private String content;

    private String requestId;

    /** success / failed；用户消息行 null */
    private String status;

    private String createdAt;

    private JsonNode toolCalls;
}
