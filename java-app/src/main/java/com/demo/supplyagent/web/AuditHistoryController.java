package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.AuditHistoryData;
import com.demo.supplyagent.dto.AuditHistoryRequest;
import com.demo.supplyagent.service.AuditHistoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * W3-④ 审计时间线查询（只读）。
 * 权限由 SecurityConfig 的 URL matcher 统一强制（POST /api/audit/history → 仅 admin），
 * Controller 不自行判定角色，前端 v-if 只是体验层。
 */
@RestController
public class AuditHistoryController {

    private final AuditHistoryService auditHistoryService;

    public AuditHistoryController(AuditHistoryService auditHistoryService) {
        this.auditHistoryService = auditHistoryService;
    }

    @PostMapping("/api/audit/history")
    public ApiResponse<AuditHistoryData> history(@RequestBody(required = false) AuditHistoryRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        return ApiResponse.ok(auditHistoryService.query(request));
    }
}
