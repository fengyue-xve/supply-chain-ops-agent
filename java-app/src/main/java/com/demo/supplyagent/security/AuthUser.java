package com.demo.supplyagent.security;

/**
 * 认证主体:JWT payload 与 SecurityContext 间的最小载体。
 * D1 只承载身份;角色授权(ACL)在 W2-D2 消费。
 */
public record AuthUser(String username, String role) {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_VIEWER = "viewer";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
