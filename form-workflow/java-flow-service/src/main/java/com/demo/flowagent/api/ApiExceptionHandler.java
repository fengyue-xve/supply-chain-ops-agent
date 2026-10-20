package com.demo.flowagent.api;

import com.demo.flowagent.api.dto.ErrorView;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(FlowException.class)
    public ResponseEntity<ErrorView> flowException(FlowException e) {
        return ResponseEntity.status(e.code().httpStatus())
                .body(new ErrorView(e.code().name(), e.getMessage(), e.details()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorView> badRequest(Exception e) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (e instanceof MethodArgumentNotValidException validation) {
            validation.getFieldErrors().forEach(f ->
                    details.put(f.getField(), f.getCode() == null ? "invalid" : f.getCode()));
        }
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorView(ErrorCode.BAD_REQUEST.name(), "请求参数不合法", details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> internal(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL.httpStatus())
                .body(new ErrorView(ErrorCode.INTERNAL.name(), "服务内部错误", Map.of()));
    }
}
