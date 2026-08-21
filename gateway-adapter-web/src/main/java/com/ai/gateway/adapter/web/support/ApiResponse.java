package com.ai.gateway.adapter.web.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台统一的 API 响应信封。
 *
 * <p>所有响应均遵循 {@code {status, data, error}} 契约：</p>
 * <ul>
 *   <li>{@code status} 成功为 {@code "OK"}，失败为 {@code "ERROR"}。</li>
 *   <li>{@code data} 携带成功的业务数据（失败时不存在）。</li>
 *   <li>{@code error} 携带 {@code {errorCode, message}}（成功时不存在）。</li>
 * </ul>
 *
 * <p>错误码遵循规范中的分类（AUTHENTICATION_FAILED、PERMISSION_DENIED、
 * ARGUMENT_VALIDATION_FAILED 等）。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
public final class ApiResponse {

    private ApiResponse() {
        // 工具类
    }

    /**
     * 创建状态码为 200 的成功响应。
     *
     * @param data 响应载荷
     * @return 200 OK，响应体为 {@code {status: "OK", data: ...}}
     */
    public static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /**
     * 创建带自定义 HTTP 状态码的成功响应。
     *
     * @param data   响应载荷
     * @param status HTTP 状态码
     * @return 响应体为 {@code {status: "OK", data: ...}}
     */
    public static ResponseEntity<Map<String, Object>> ok(Object data, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("data", data);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 使用给定错误码与消息创建错误响应。
     *
     * @param errorCode 稳定的错误码（如 AUTHENTICATION_FAILED）
     * @param message   面向用户的错误消息（不含内部信息）
     * @param status    HTTP 状态码
     * @return 响应体为 {@code {status: "ERROR", error: {errorCode, message}}}
     */
    public static ResponseEntity<Map<String, Object>> error(
            String errorCode, String message, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", errorCode);
        error.put("message", sanitizeMessage(message));
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 创建 400 Bad Request 错误响应。
     *
     * @param message 面向用户的错误消息
     * @return 400 BAD REQUEST
     */
    public static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return error("ARGUMENT_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建 401 Unauthorized 错误响应。
     *
     * @param message 面向用户的错误消息
     * @return 401 UNAUTHORIZED
     */
    public static ResponseEntity<Map<String, Object>> unauthorized(String message) {
        return error("AUTHENTICATION_FAILED", message, HttpStatus.UNAUTHORIZED);
    }

    /**
     * 创建 403 Forbidden 错误响应。
     *
     * @param message 面向用户的错误消息
     * @return 403 FORBIDDEN
     */
    public static ResponseEntity<Map<String, Object>> forbidden(String message) {
        return error("PERMISSION_DENIED", message, HttpStatus.FORBIDDEN);
    }

    /**
     * 创建 404 Not Found 错误响应。
     *
     * @param message 面向用户的错误消息
     * @return 404 NOT FOUND
     */
    public static ResponseEntity<Map<String, Object>> notFound(String message) {
        return error("NO_CAPABILITY_MATCH", message, HttpStatus.NOT_FOUND);
    }

    /**
     * 创建 409 Conflict 错误响应。
     *
     * @param message 面向用户的错误消息
     * @return 409 CONFLICT
     */
    public static ResponseEntity<Map<String, Object>> conflict(String message) {
        return error("EXECUTION_UNKNOWN", message, HttpStatus.CONFLICT);
    }

    /**
     * 对外暴露前清理错误消息中的内部信息。
     */
    private static String sanitizeMessage(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }
        return sanitized;
    }
}