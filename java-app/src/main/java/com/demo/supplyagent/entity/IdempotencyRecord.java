package com.demo.supplyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 请求级幂等登记（唯一约束 uq_idempotency_key）。
 * 同 key + 同摘要 → 回放 response_json（零写入）；
 * 同 key + 异摘要 → 409。仅 INSERT 路径，不 UPDATE（首次请求的溯源信息不被覆盖）。
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("tool_name")
    private String toolName;

    /** 规范化请求摘要（items 按 sku_code 排序后 SHA-256 hex） */
    @TableField("request_digest")
    private String requestDigest;

    /** 首次成功响应 data 快照（重复请求原样回放） */
    @TableField("response_json")
    private String responseJson;

    private String operator;

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
