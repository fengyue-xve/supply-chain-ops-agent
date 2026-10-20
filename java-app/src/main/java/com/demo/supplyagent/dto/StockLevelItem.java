package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * 库存水位条目。Mapper 只回填前五项，stockLevel 由 Service 按档位规则补上。
 */
@Data
public class StockLevelItem {

    private String sku;

    private String productName;

    private String category;

    private Integer availableQty;

    private String stockLevel;

    private String snapDate;

    /** Product master policy; quantities are pieces, lead time is calendar days. */
    private Integer safetyQty;
    private Integer leadDays;
    private Integer pkgQty;

    private String shelfStatus;
}
