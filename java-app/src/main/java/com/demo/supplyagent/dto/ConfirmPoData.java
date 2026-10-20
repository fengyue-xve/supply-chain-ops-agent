package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * confirm-po 成功响应 data（status 恒为 confirmed）。
 */
@Data
@AllArgsConstructor
public class ConfirmPoData {

    private String poNo;

    private String sku;

    private Integer qty;

    private String status;
}
