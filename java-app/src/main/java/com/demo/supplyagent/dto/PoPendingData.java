package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * W4 待确认采购单查询结果：items（当前页）+ total（全量 pending 行数）+ page/size 回显。
 */
@Data
@AllArgsConstructor
public class PoPendingData {

    private List<PoPendingItem> items;

    private long total;

    private int page;

    private int size;
}
