package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.LoginRequest;
import com.demo.supplyagent.dto.LoginResponse;
import com.demo.supplyagent.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名白名单登录接口(与 SecurityConfig 白名单同口径)。
 * 薄壳:只做空参校验(400),业务校验与签发在 AuthService;
 * 凭证失败由 AuthService 抛 401 → ApiExceptionHandler → HTTP 401 + code 401。
 * 登录接口自身的 401 语义 = 「凭证错」,前端登录页内提示,不触发全局回登录
 * (受保护接口的 401 才代表会话失效回登录——前端 D4 按 URL 区分)。
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/login")
    public ApiResponse<LoginResponse> login(@RequestBody(required = false) LoginRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new ApiException(400, "用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().isEmpty()) {
            throw new ApiException(400, "密码不能为空");
        }
        return ApiResponse.ok(authService.login(request.getUsername(), request.getPassword()));
    }
}
