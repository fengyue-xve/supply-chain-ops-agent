package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.service.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工具发现（目录只读，不含任何执行）：供 FastAPI/前端在启动或调试时发现
 * 「有哪些工具、怎么调」——执行仍走三个既有 POST /api/agent/tools/* 端点。
 */
@RestController
public class ToolDiscoveryController {

    private final ToolRegistry toolRegistry;

    public ToolDiscoveryController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/api/agent/tools")
    public ApiResponse<Map<String, Object>> listTools() {
        return ApiResponse.ok(toolRegistry.summaryData());
    }

    @GetMapping("/api/agent/tools/schema")
    public ApiResponse<Map<String, Object>> schema() {
        return ApiResponse.ok(toolRegistry.contractData());
    }
}
