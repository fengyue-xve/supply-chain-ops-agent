package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.SuggestData;
import com.demo.supplyagent.dto.SuggestReplenishRequest;
import com.demo.supplyagent.service.SuggestReplenishService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 补货建议（未来 Agent 工具 suggest_replenish 的后端端点，本轮仅 Java 侧闭环；不写库）。
 * 契约：skus(≤20) 与 category 二选一；days ∈ {7,30}；targetCoverDays ∈ {7,14,30} 缺省 14。
 */
@RestController
public class SuggestReplenishController {

    private static final int MAX_SKUS = 20;
    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30);
    private static final Set<Integer> ALLOWED_COVER_DAYS = Set.of(7, 14, 30);

    private final SuggestReplenishService suggestReplenishService;

    public SuggestReplenishController(SuggestReplenishService suggestReplenishService) {
        this.suggestReplenishService = suggestReplenishService;
    }

    @PostMapping("/api/agent/tools/suggest-replenish")
    public ApiResponse<SuggestData> suggestReplenish(
            @RequestBody(required = false) SuggestReplenishRequest request) {
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
        Integer days = request.getDays();
        if (days == null || !ALLOWED_DAYS.contains(days)) {
            throw new ApiException(400, "days 必须为 7 或 30");
        }
        Integer coverDays = request.getTargetCoverDays();
        if (coverDays != null && !ALLOWED_COVER_DAYS.contains(coverDays)) {
            throw new ApiException(400, "targetCoverDays 必须为 7、14 或 30");
        }
        return ApiResponse.ok(suggestReplenishService.suggest(request));
    }
}
