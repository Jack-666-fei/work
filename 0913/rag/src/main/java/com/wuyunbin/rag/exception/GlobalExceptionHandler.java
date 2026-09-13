package com.wuyunbin.rag.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理：把未捕获的异常记进日志（带堆栈和 requestId），并返回结构化错误体。
 *
 * <p>在此之前，任何未捕获异常只会被 Servlet 容器记一条，
 * 响应体对调用方也不友好。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务代码主动抛出的参数/状态类异常 → 分别映射到 400 / 500，并记录日志。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex,
                                                                    HttpServletRequest request) {
        log.warn("请求参数错误: {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "参数错误: " + ex.getMessage(), request);
    }

    /**
     * 兜底：其余所有异常。
     *
     * <p>对 Spring 自身的标准异常（404、405、参数校验失败等，均实现 {@link ErrorResponse}）
     * 保留其原有状态码，只记录并包装错误体，避免把 404 误变成 500。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            log.warn("请求处理失败({}): {} {} - {}",
                    status.value(), request.getMethod(), request.getRequestURI(), ex.getMessage());
            return build(status, ex.getMessage(), request);
        }

        // 真正的意外异常：完整堆栈进日志，这是排查问题的主要依据
        log.error("未捕获异常: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getClass().getSimpleName() + ": " + ex.getMessage(), request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        // 与日志中的 [req=...] 一致，便于拿这个值去日志里定位
        body.put("requestId", request.getHeader("X-Request-Id"));
        return ResponseEntity.status(status).body(body);
    }
}
