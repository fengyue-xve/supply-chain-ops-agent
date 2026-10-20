package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * query-stock-level 响应 data：命中条目 + 缺失 SKU 列表（缺货不整单失败）。
 */
@Getter
@AllArgsConstructor
public class StockLevelData {

    private final List<StockLevelItem> items;

    private final List<String> missingSkus;

    private final int total;

    public static StockLevelData empty() {
        return new StockLevelData(List.of(), List.of(), 0);
    }
}
