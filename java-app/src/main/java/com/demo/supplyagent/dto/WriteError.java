package com.demo.supplyagent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 写工具 item 级校验错误（400 响应的 data.errors[] 条目）。
 * code 取值：invalid_qty / qty_not_pkg_multiple / qty_out_of_range / invalid_pkg_qty / sku_not_found / sku_off_shelf；
 * qty_not_pkg_multiple 附带 qty/pkgQty/suggestedQty（向上取整建议值，不自动修改用户数量）。
 * 超出 INT 存储范围时返回 qty_out_of_range，不输出溢出后的 suggestedQty。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WriteError {

    private String sku;

    private String code;

    private String message;

    private Integer qty;

    private Integer pkgQty;

    private Integer suggestedQty;

    public WriteError(String sku, String code, String message) {
        this.sku = sku;
        this.code = code;
        this.message = message;
    }
}
