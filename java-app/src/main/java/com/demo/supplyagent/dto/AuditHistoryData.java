package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * W3-④ 审计时间线查询结果：items（当前页）+ total（同过滤条件下全量行数）+ page/size 回显。
 */
@Data
@AllArgsConstructor
public class AuditHistoryData {

    private List<AuditHistoryItem> items;

    private long total;

    private int page;

    private int size;
}
