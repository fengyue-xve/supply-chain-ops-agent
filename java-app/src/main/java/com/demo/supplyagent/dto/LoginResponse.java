package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * POST /api/auth/login 成功响应(data 部分)。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private String tokenType;

    private long expiresInSeconds;

    private String role;

    private String username;
}
