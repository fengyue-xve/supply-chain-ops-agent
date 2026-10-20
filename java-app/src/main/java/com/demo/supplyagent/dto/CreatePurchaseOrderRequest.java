package com.demo.supplyagent.dto;

import lombok.Data;

import java.util.List;

/**
 * create-purchase-order 请求：items 1–20 条，SKU 不重复，qty>0 且为包装数量整数倍。
 * 幂等键（Idempotency-Key 头）必填；items 摘要按 sku 排序后生成（输入顺序无关）。
 */
@Data
public class CreatePurchaseOrderRequest {

    private List<Item> items;

    @Data
    public static class Item {

        private String sku;

        private Integer qty;
    }
}
