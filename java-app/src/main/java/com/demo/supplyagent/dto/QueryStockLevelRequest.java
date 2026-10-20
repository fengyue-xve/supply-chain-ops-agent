package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * query-stock-level 请求：skus（≤20）与 category 二选一，都为空由 Controller 拦 400。
 */
@Data
public class QueryStockLevelRequest {

    private java.util.List<String> skus;

    private String category;
}
