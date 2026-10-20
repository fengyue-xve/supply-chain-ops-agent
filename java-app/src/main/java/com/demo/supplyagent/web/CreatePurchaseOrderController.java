package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.CreatePurchaseOrderData;
import com.demo.supplyagent.dto.CreatePurchaseOrderRequest;
import com.demo.supplyagent.dto.WriteContext;
import com.demo.supplyagent.service.PoWriteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感写工具 create-purchase-order 入口（readOnly=false → 拦截器自动仅 admin）。
 * 薄壳：请求体结构校验（items 1–20、SKU 大写格式且不重复、qty 非空）+ Idempotency-Key 校验，
 * 随后装配 WriteContext 交给 PoWriteService（qty 正数/包装倍数是业务校验，在 Service 层落 errors[]）。
 * SKU 限定大写字母/数字/短横线：与库的 utf8mb4_0900_ai_ci 大小写不敏感比较对齐，
 * 保证「摘要字符串」与「业务查得的 SKU」语义一致。
 */
@RestController
public class CreatePurchaseOrderController {

    static final int MAX_ITEMS = 20;

    /** 与 po_confirmation.sku_code VARCHAR(32) 对齐。 */
    private static final Pattern SKU_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,31}");

    private final PoWriteService poWriteService;

    public CreatePurchaseOrderController(PoWriteService poWriteService) {
        this.poWriteService = poWriteService;
    }

    @PostMapping("/api/agent/tools/create-purchase-order")
    public ApiResponse<CreatePurchaseOrderData> create(
            @RequestBody(required = false) CreatePurchaseOrderRequest request,
            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        String idempotencyKey = WriteHeaders.requireIdempotencyKey(httpRequest);
        List<CreatePurchaseOrderRequest.Item> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new ApiException(400, "items 不能为空");
        }
        if (items.size() > MAX_ITEMS) {
            throw new ApiException(400, "items 不能超过 " + MAX_ITEMS + " 条");
        }
        Set<String> seen = new HashSet<>();
        for (CreatePurchaseOrderRequest.Item item : items) {
            if (item == null) throw new ApiException(400, "items 不能包含空条目");
            String sku = item.getSku();
            if (sku == null || !SKU_PATTERN.matcher(sku).matches()) {
                throw new ApiException(400, "sku 格式非法（仅大写字母、数字与短横线，最长 32 位）: " + sku);
            }
            if (item.getQty() == null) {
                throw new ApiException(400, "qty 不能为空: " + sku);
            }
            if (!seen.add(sku)) {
                throw new ApiException(400, "items 中存在重复 SKU: " + sku);
            }
        }
        WriteContext ctx = WriteHeaders.context(httpRequest, idempotencyKey, WriteHeaders.authenticatedUser());
        return ApiResponse.ok(poWriteService.create(request, ctx));
    }
}
