package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * W3-④ 审计时间线查询请求：五个等值过滤全部可选，字符串 trim 后空白视为未传。
 * page 默认 1（>=1），size 默认 20（1~100）；actionType 不做枚举白名单（未知值返回空结果）。
 */
@Data
public class AuditHistoryRequest {

    private Integer page;

    private Integer size;

    private String actionType;

    private String taskKey;

    private String operator;

    private String requestId;

    private String conversationId;
}
