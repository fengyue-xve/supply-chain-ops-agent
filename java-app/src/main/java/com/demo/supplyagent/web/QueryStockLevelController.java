package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.QueryStockLevelRequest;
import com.demo.supplyagent.dto.StockLevelData;
import com.demo.supplyagent.service.QueryStockLevelService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存水位查询（未来 Agent 工具 query_stock_level 的后端端点，本轮仅 Java 侧闭环）。
 * 校验契约：skus(≤20) 与 category 二选一；两空或同时传都 400；缺失 SKU 走 missingSkus 不整单失败。
 */
@RestController
public class QueryStockLevelController {

    private static final int MAX_SKUS = 20;

    private final QueryStockLevelService queryStockLevelService;

    public QueryStockLevelController(QueryStockLevelService queryStockLevelService) {
        this.queryStockLevelService = queryStockLevelService;
    }

    @PostMapping("/api/agent/tools/query-stock-level")
    public ApiResponse<StockLevelData> queryStockLevel(
            @RequestBody(required = false) QueryStockLevelRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        boolean hasSkus = request.getSkus() != null && !request.getSkus().isEmpty();
        boolean hasCategory = request.getCategory() != null && !request.getCategory().isBlank();
        if (hasSkus && hasCategory) {
            throw new ApiException(400, "skus 与 category 只能二选一");
        }
        if (!hasSkus && !hasCategory) {
            throw new ApiException(400, "skus 与 category 不能同时为空");
        }
        if (hasSkus && request.getSkus().size() > MAX_SKUS) {
            throw new ApiException(400, "skus 数量不能超过 " + MAX_SKUS);
        }
        return ApiResponse.ok(queryStockLevelService.query(request));
    }
}
