package com.demo.supplyagent.security;

import com.demo.supplyagent.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证失败统一出口:写 HTTP 401 + {"code":401, message, data:null} 信封。
 * 被两处调用:① SecurityConfig.exceptionHandling(无头/匿名访问受保护,由授权阶段触发);
 * ② JwtAuthFilter 内部主动调用(格式坏/伪造/过期),filter 不依赖后置过滤器兜底。
 * 语义:JwtAuthException 按 reason 给文案;其余(匿名)一律「未登录或登录已过期」。
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String message;
        if (authException instanceof JwtAuthException jwtException) {
            message = jwtException.getReason() == JwtAuthException.Reason.EXPIRED
                    ? "登录已过期,请重新登录"
                    : "token 无效";
        } else {
            message = "未登录或登录已过期";
        }
        writeUnauthorized(response, message);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(401, message));
    }
}
