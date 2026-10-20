package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * 写工具校验用的商品主档最小视图：SKU 存在性 + 上架状态 + 包装数量。
 */
@Data
public class ProductWriteInfo {

    private String sku;

    /** on_shelf / off_shelf */
    private String shelfStatus;

    private Integer pkgQty;
}
