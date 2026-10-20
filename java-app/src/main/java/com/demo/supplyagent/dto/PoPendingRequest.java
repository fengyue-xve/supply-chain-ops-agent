package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * W4 待确认采购单查询请求：无过滤条件，仅分页。
 * page 默认 1（>=1），size 默认 20（1~100），归一化口径与审计查询一致。
 */
@Data
public class PoPendingRequest {

    private Integer page;

    private Integer size;
}
