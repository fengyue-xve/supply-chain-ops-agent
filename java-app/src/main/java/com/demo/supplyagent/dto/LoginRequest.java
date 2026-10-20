package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * POST /api/auth/login 请求体。
 */
@Data
public class LoginRequest {

    private String username;

    private String password;
}
