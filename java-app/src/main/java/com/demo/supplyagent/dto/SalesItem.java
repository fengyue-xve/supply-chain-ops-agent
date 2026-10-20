package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * query-sales 响应条目：days 内逐日 series（连续、补 0）+ 总量/日均。
 */
@Data
@AllArgsConstructor
public class SalesItem {

    private String sku;

    private String productName;

    private String category;

    private Integer days;

    private Integer totalQty;

    private BigDecimal avgDailyQty;

    private List<SalesSeriesPoint> series;
}
