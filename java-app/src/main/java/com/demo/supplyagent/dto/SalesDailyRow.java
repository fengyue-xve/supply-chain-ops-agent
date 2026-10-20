package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * Mapper 聚合行：某 SKU 某业务日销量合计（sku + 日期粒度，sale_order 实时行）。
 */
@Data
public class SalesDailyRow {

    private String sku;

    /** yyyy-MM-dd（SQL DATE_FORMAT 输出，与服务端 LocalDate.toString 同形，可直接对齐） */
    private String orderDate;

    private Integer qty;
}
