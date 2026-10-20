package com.demo.supplyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_conversation")
public class AgentConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    /** 消息归属用户名（JWT sub）；NULL = W4-1 前旧数据 */
    private String operator;

    /** user / assistant / system / tool */
    private String role;

    private String content;

    @TableField("tool_calls_json")
    private String toolCallsJson;

    @TableField("request_id")
    private String requestId;

    /** success / failed；仅助手消息行使用，用户消息行 NULL */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
