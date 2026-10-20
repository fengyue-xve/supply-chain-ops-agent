package com.demo.supplyagent.security;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.service.tool.ToolAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已认证但无权限的统一出口:写 HTTP 403 + {"code":403, "message":"无权限执行该操作", data:null}。
 * 挂在 SecurityConfig.exceptionHandling 兜底授权阶段(AuthorizationFilter)的拒绝;
 * MVC 层(工具拦截器)的拒绝走 ApiException(403) → ApiExceptionHandler,文案同一常量,信封同形。
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(403, ToolAccessService.ACCESS_DENIED_MESSAGE));
    }
}
