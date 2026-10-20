package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 销量序列单点：{date: yyyy-MM-dd, qty}，无销量日期补 0，series 连续不跳日。
 */
@Data
@AllArgsConstructor
public class SalesSeriesPoint {

    private String date;

    private Integer qty;
}
