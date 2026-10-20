package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.ConfirmPoData;
import com.demo.supplyagent.dto.ConfirmPoRequest;
import com.demo.supplyagent.dto.WriteContext;
import com.demo.supplyagent.service.PoWriteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 敏感写工具 confirm-po 入口（readOnly=false → 拦截器自动仅 admin）。
 * 薄壳：poNo 非空且 ≤40（与 po_confirmation.po_no 列宽对齐）后交给 PoWriteService。
 * 不做 poNo 格式强校验：不存在的单号走 Service 的 404 语义，而非入口 400。
 * 不需要 Idempotency-Key：重复确认由状态机（pending_confirmation 条件迁移）以 409 兜底；
 * 若客户端携带则仅校验合法性（空值/超长/非法字符 400），不参与语义。
 */
@RestController
public class ConfirmPoController {

    static final int MAX_PO_NO_LENGTH = 40;

    private final PoWriteService poWriteService;

    public ConfirmPoController(PoWriteService poWriteService) {
        this.poWriteService = poWriteService;
    }

    @PostMapping("/api/agent/tools/confirm-po")
    public ApiResponse<ConfirmPoData> confirm(
            @RequestBody(required = false) ConfirmPoRequest request,
            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        String poNo = request.getPoNo();
        if (poNo == null || poNo.isBlank()) {
            throw new ApiException(400, "poNo 不能为空");
        }
        if (poNo.length() > MAX_PO_NO_LENGTH) {
            throw new ApiException(400, "poNo 不能超过 " + MAX_PO_NO_LENGTH + " 字符");
        }
        String idempotencyKey = WriteHeaders.optionalIdempotencyKey(httpRequest);
        WriteContext ctx = WriteHeaders.context(httpRequest, idempotencyKey, WriteHeaders.authenticatedUser());
        return ApiResponse.ok(poWriteService.confirm(poNo, ctx));
    }
}
