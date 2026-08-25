package com.ai.gateway.adapter.web.handler;

import com.ai.gateway.adapter.web.support.ApiResponse;
import com.ai.gateway.domain.model.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * AI Capability Gateway REST API 的全局异常处理器。
 *
 * <p>将领域异常与框架异常映射为 HTTP 状态码和稳定的错误码。对外错误响应中
 * 绝不包含堆栈跟踪、内部地址、接口类名或敏感参数。</p>
 *
 * <p>错误码映射关系：</p>
 * <ul>
 * <li>AUTHENTICATION_FAILED - 401</li>
 * <li>PERMISSION_DENIED - 403</li>
 * <li>NO_CAPABILITY_MATCH - 404</li>
 * <li>CLARIFICATION_REQUIRED - 422</li>
 * <li>INVALID_MODEL_OUTPUT - 422</li>
 * <li>ARGUMENT_VALIDATION_FAILED - 400</li>
 * <li>CAPABILITY_UNAVAILABLE - 503</li>
 * <li>PROVIDER_TIMEOUT - 504</li>
 * <li>PROTOCOL_ERROR - 502</li>
 * <li>RESULT_TOO_LARGE - 413</li>
 * <li>EXECUTION_UNKNOWN - 409</li>
 * </ul>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGatewayException(
            GatewayException ex, WebRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = mapErrorCodeToStatus(errorCode);
        log.warn("Gateway exception: errorCode={}, message={}", errorCode, ex.getMessage());
        return ApiResponse.error(errorCode.name(), ex.getMessage(), status);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, WebRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "请求参数无效，请检查后重试。",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(
            NullPointerException ex, WebRequest request) {
        log.error("Null pointer exception", ex);
        return ApiResponse.error(
                ErrorCode.PROTOCOL_ERROR.name(),
                "服务处理失败，请稍后重试。",
                HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "请求内容格式错误，请检查 JSON 或表单内容。",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Validation failed: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "请求参数校验失败，请检查必填字段和字段格式。",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "请求参数类型错误，请检查字段格式。",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityException(
            SecurityException ex, WebRequest request) {
        String message = ex.getMessage();
        if (message != null && message.startsWith("AUTHENTICATION_FAILED")) {
            log.warn("Authentication failed: {}", message);
            return ApiResponse.unauthorized("登录状态无效或已过期，请重新登录。");
        }
        log.warn("Permission denied: {}", message);
        return ApiResponse.forbidden("当前账号没有执行此管理操作的权限。");
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedOperation(
            UnsupportedOperationException ex, WebRequest request) {
        log.warn("Unsupported operation: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.CAPABILITY_UNAVAILABLE.name(),
                "请求的协议或能力当前不可用，请稍后重试。",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return ApiResponse.error(
                ErrorCode.PROTOCOL_ERROR.name(),
                "服务处理失败，请稍后重试。",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 将稳定的 ErrorCode 映射为对应的 HTTP 状态码。
     */
    private HttpStatus mapErrorCodeToStatus(ErrorCode errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (errorCode) {
            case AUTHENTICATION_FAILED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case CONFIRMATION_REQUIRED -> HttpStatus.CONFLICT;
            case NO_CAPABILITY_MATCH -> HttpStatus.NOT_FOUND;
            case CLARIFICATION_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_MODEL_OUTPUT -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ARGUMENT_VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case CAPABILITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case LLM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PROVIDER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case PROVIDER_REJECTED -> HttpStatus.BAD_GATEWAY;
            case PROTOCOL_ERROR -> HttpStatus.BAD_GATEWAY;
            case RESULT_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case EXECUTION_UNKNOWN -> HttpStatus.CONFLICT;
            // 501：该入口在本部署未曝光（曝光策略结果，非能力缺失）。
            case NL_ROUTER_DISABLED -> HttpStatus.NOT_IMPLEMENTED;
        };
    }

    /**
     * 构建经过清理的错误响应体。绝不包含堆栈跟踪、内部地址、接口类名或敏感参数。
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            String errorCode, String message, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", errorCode);
        body.put("message", sanitizeMessage(message));
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 对外暴露前清理错误消息中的内部信息。
     */
    private String sanitizeMessage(String message) {
        if (message == null) {
            return "服务处理失败，请稍后重试。";
        }
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }
        return sanitized;
    }

    /**
     * 携带稳定 ErrorCode 的领域异常。可由控制器或适配器抛出，用于表示
     * 需要映射到特定 HTTP 状态码与稳定错误码的领域级错误。
     */
    public static class GatewayException extends RuntimeException {

        private final ErrorCode errorCode;

        public GatewayException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public GatewayException(ErrorCode errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}
