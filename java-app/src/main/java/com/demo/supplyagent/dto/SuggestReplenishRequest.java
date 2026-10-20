package com.demo.supplyagent.dto;

import lombok.Data;

import java.util.List;

/**
 * suggest-replenish 请求：skus(≤20) 与 category 二选一；days ∈ {7,30}；
 * targetCoverDays 是已包含采购提前期的总覆盖期，∈ {7,14,30}，缺省 14；短于提前期时按提前期展开并提示人工复核。
 */
@Data
public class SuggestReplenishRequest {

    private List<String> skus;

    private String category;

    private Integer days;

    private Integer targetCoverDays;
}
