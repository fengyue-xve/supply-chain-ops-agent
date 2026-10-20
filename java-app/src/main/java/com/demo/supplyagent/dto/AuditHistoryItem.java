package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * 审计时间线一行（查询结果）：executedAt 为 SQL 内格式化的字符串（yyyy-MM-dd HH:mm:ss）；
 * beforeJson/afterJson/statsJson 保持数据库原字符串，不解析；NULL 原样为 null。
 */
@Data
public class AuditHistoryItem {

    private Long id;

    private String executedAt;

    private String actionType;

    private String who;

    private String operator;

    private String taskKey;

    private String beforeJson;

    private String afterJson;

    private String statsJson;

    private String requestId;

    private String conversationId;

    private String toolCallId;
}
