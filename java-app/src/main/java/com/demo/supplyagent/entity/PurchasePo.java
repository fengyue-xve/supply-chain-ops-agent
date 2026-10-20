package com.demo.supplyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("purchase_po")
public class PurchasePo {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("po_no")
    private String poNo;

    /** 一行 = 一 SKU 一 po_no */
    private String skuCode;

    private Integer qty;

    /** pending / confirmed / cancelled / completed */
    private String status;

    @TableField("manual_confirm_required")
    private Boolean manualConfirmRequired;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
