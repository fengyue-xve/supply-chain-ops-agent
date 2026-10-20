package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.PoPendingData;
import com.demo.supplyagent.dto.PoPendingRequest;
import com.demo.supplyagent.service.PoPendingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * W4 待确认采购单查询（只读）。
 * 权限由 SecurityConfig 的 URL matcher 统一强制（POST /api/po/pending → 仅 admin），
 * Controller 不自行判定角色，前端 v-if 只是体验层。
 */
@RestController
public class PoPendingController {

    private final PoPendingService poPendingService;

    public PoPendingController(PoPendingService poPendingService) {
        this.poPendingService = poPendingService;
    }

    @PostMapping("/api/po/pending")
    public ApiResponse<PoPendingData> pending(@RequestBody(required = false) PoPendingRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        return ApiResponse.ok(poPendingService.query(request));
    }
}
