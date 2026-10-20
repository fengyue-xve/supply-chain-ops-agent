package com.demo.supplyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计时间线一行（应用层永不 DELETE，只有 INSERT 路径）。
 * 关键检索字段全部结构化落列（task_key/who/operator/三 ID）；
 * before_json/after_json 只存状态快照，不埋检索字段。
 */
@Data
@TableName("agent_history")
public class AgentHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_key")
    private String taskKey;

    @TableField("executed_at")
    private LocalDateTime executedAt;

    /** task_begin / task_success / task_fail / po_create / po_confirm / po_confirm_fail */
    @TableField("action_type")
    private String actionType;

    /** 执行者角色（admin/viewer/task/agent） */
    private String who;

    /** 操作者（JWT sub） */
    private String operator;

    /** 变更前状态快照 */
    @TableField("before_json")
    private String beforeJson;

    /** 变更后状态快照 */
    @TableField("after_json")
    private String afterJson;

    @TableField("stats_json")
    private String statsJson;

    @TableField("requested_at")
    private LocalDateTime requestedAt;

    @TableField("request_id")
    private String requestId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("tool_call_id")
    private String toolCallId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
