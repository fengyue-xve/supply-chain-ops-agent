package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * suggest-replenish 响应 data（结构与 stock/sales 对齐：items + missingSkus + total）。
 */
@Getter
@AllArgsConstructor
public class SuggestData {

    private final List<SuggestItem> items;

    private final List<String> missingSkus;

    private final int total;

    public static SuggestData empty() {
        return new SuggestData(List.of(), List.of(), 0);
    }
}
