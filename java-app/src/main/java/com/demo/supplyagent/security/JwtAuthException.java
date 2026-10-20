package com.demo.supplyagent.security;

import org.springframework.security.core.AuthenticationException;

/**
 * JWT 解析失败语义化异常。继承 AuthenticationException:
 * 让安全层(ExceptionTranslationFilter/EntryPoint)按统一路径处理;
 * 但 JwtAuthFilter 不依赖后置过滤器兜底,解析失败在 filter 内主动收口为 401。
 */
public class JwtAuthException extends AuthenticationException {

    public enum Reason {
        /** 签名错/格式错/算法不符/claims 缺失或非法 */
        INVALID,
        /** token 已过 exp */
        EXPIRED
    }

    private final Reason reason;

    public JwtAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
