package com.demo.supplyagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * W4-1 会话历史查询请求：conversation_id 必填（<=80，入口 400 拦截）；
 * page 默认 1（>=1），size 默认 20（1~100）；page=1 为最近一页，items 按时间正序返回。
 */
@Data
public class ChatHistoryRequest {

    @JsonProperty("conversation_id")
    private String conversationId;

    private Integer page;

    private Integer size;
}
