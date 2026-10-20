package com.demo.supplyagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * POST /api/chat 请求体(与 FastAPI ChatRequest 对齐:conversation_id 可选,message 必填)。
 */
@Data
public class ChatRequest {

    @JsonProperty("conversation_id")
    private String conversationId;

    private String message;
}
