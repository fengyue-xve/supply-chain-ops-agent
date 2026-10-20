package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * query-sales 请求：sku 与 category 二选一（Controller 拦 400），days 只允许 7/30。
 */
@Data
public class QuerySalesRequest {

    private String sku;

    private String category;

    private Integer days;
}
