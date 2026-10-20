package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * 待确认采购单一行（查询结果）：createdAt 为 SQL 内格式化的字符串（yyyy-MM-dd HH:mm:ss）。
 * manualConfirmRequired 来自列值：写路径（create-purchase-order）只落 pending 草稿且置 1。
 */
@Data
public class PoPendingItem {

    private String poNo;

    private String skuCode;

    private Integer qty;

    private String status;

    private Boolean manualConfirmRequired;

    private String createdAt;
}
