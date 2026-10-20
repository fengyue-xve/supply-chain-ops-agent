package com.demo.supplyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购单确认流当前态（一行 = 一个 po_no）。
 * 状态迁移全部走条件更新（WHERE status='pending_confirmation'），并发安全；
 * idempotency_key 仅溯源，请求级唯一性由 idempotency_record 持有。
 */
@Data
@TableName("po_confirmation")
public class PoConfirmation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("po_no")
    private String poNo;

    @TableField("sku_code")
    private String skuCode;

    private Integer qty;

    /** draft / pending_confirmation / confirmed / failed / cancelled */
    private String status;

    @TableField("idempotency_key")
    private String idempotencyKey;

    /** 创建者（JWT sub） */
    private String operator;

    @TableField("request_id")
    private String requestId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("tool_call_id")
    private String toolCallId;

    /** failed 原因 */
    private String reason;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
