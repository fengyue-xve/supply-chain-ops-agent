package com.demo.supplyagent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * create-purchase-order 成功响应 data：一 SKU 一单（status 恒为 pending，人工确认后才变更）。
 * duplicated=true 表示同幂等键 + 同摘要的重复请求回放（零写入，内容与首响一致）。
 */
@Data
@NoArgsConstructor
public class CreatePurchaseOrderData {

    /** 按 sku 排序（规范化顺序，输入顺序无关） */
    private List<Line> items;

    private int total;

    private boolean duplicated;

    public CreatePurchaseOrderData(List<Line> items, boolean duplicated) {
        this.items = items;
        this.total = items.size();
        this.duplicated = duplicated;
    }

    @Data
    @NoArgsConstructor
    public static class Line {

        private String poNo;

        private String sku;

        private Integer qty;

        /** 恒为 pending（create 只落草稿） */
        private String status;

        public Line(String poNo, String sku, Integer qty, String status) {
            this.poNo = poNo;
            this.sku = sku;
            this.qty = qty;
            this.status = status;
        }
    }
}
