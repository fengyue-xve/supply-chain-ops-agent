package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * query-sales 响应 data（结构与 query-stock-level 对齐：items + missingSkus + total）。
 */
@Getter
@AllArgsConstructor
public class SalesData {

    private final List<SalesItem> items;

    private final List<String> missingSkus;

    private final int total;

    public static SalesData empty() {
        return new SalesData(List.of(), List.of(), 0);
    }
}
