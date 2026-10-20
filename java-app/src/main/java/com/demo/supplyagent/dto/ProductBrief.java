package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * 商品主档摘要（sku 存在性 + 名称/类目），供销量等查询组装响应条目。
 */
@Data
public class ProductBrief {

    private String sku;

    private String productName;

    private String category;
}
