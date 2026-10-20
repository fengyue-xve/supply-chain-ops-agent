package com.demo.supplyagent.web;

import lombok.Getter;

/**
 * 业务/参数错误：code 兼作 HTTP 状态码（400/403/404/409），由 ApiExceptionHandler 转成统一信封。
 * data 可空：仅写工具的错误明细（如 400 的 errors[]、409 的当前状态）需要携带结构化补充。
 */
@Getter
public class ApiException extends RuntimeException {

    private final int code;

    private final transient Object data;

    public ApiException(int code, String message) {
        this(code, message, null);
    }

    public ApiException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
