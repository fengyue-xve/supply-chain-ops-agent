package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.QuerySalesRequest;
import com.demo.supplyagent.dto.SalesData;
import com.demo.supplyagent.service.QuerySalesService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 销量查询（未来 Agent 工具 query_sales 的后端端点，本轮仅 Java 侧闭环）。
 * 契约：sku 与 category 二选一；days 仅 7/30；未知 SKU 走 missingSkus；类目无货返回空 items。
 */
@RestController
public class QuerySalesController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30);

    private final QuerySalesService querySalesService;

    public QuerySalesController(QuerySalesService querySalesService) {
        this.querySalesService = querySalesService;
    }

    @PostMapping("/api/agent/tools/query-sales")
    public ApiResponse<SalesData> querySales(
            @RequestBody(required = false) QuerySalesRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        boolean hasSku = request.getSku() != null && !request.getSku().isBlank();
        boolean hasCategory = request.getCategory() != null && !request.getCategory().isBlank();
        if (hasSku && hasCategory) {
            throw new ApiException(400, "sku 与 category 只能二选一");
        }
        if (!hasSku && !hasCategory) {
            throw new ApiException(400, "sku 与 category 不能同时为空");
        }
        Integer days = request.getDays();
        if (days == null || !ALLOWED_DAYS.contains(days)) {
            throw new ApiException(400, "days 必须为 7 或 30");
        }
        return ApiResponse.ok(querySalesService.query(request));
    }
}
