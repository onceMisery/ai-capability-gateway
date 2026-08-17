package com.ai.gateway.adapter.web.handler;

import com.ai.gateway.adapter.web.support.ApiResponse;
import com.ai.gateway.domain.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Global exception handler for the AI Capability Gateway REST API
 *
 * <p>Maps domain and framework exceptions to HTTP status codes and stable
 * error codes. External error responses never contain stack traces,
 * internal addresses, interface class names, or sensitive parameters.</p>
 *
 * <p>Error code mapping:</p>
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
 * @since 0.1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
                "Invalid request parameters",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(
            NullPointerException ex, WebRequest request) {
        log.error("Null pointer exception", ex);
        return ApiResponse.error(
                ErrorCode.PROTOCOL_ERROR.name(),
                "An internal error occurred",
                HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "Malformed request body",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Validation failed: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "Request validation failed",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.ARGUMENT_VALIDATION_FAILED.name(),
                "Invalid parameter type",
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityException(
            SecurityException ex, WebRequest request) {
        String message = ex.getMessage();
        if (message != null && message.startsWith("AUTHENTICATION_FAILED")) {
            log.warn("Authentication failed: {}", message);
            return ApiResponse.unauthorized("Authentication required");
        }
        log.warn("Permission denied: {}", message);
        return ApiResponse.forbidden("Admin permission required");
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedOperation(
            UnsupportedOperationException ex, WebRequest request) {
        log.warn("Unsupported operation: {}", ex.getMessage());
        return ApiResponse.error(
                ErrorCode.CAPABILITY_UNAVAILABLE.name(),
                "The requested protocol or capability is not yet available",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return ApiResponse.error(
                ErrorCode.PROTOCOL_ERROR.name(),
                "An internal error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Maps a stable ErrorCode to the corresponding HTTP status code.
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
        };
    }

    /**
     * Builds a sanitized error response body. Never contains stack traces,
     * internal addresses, interface class names, or sensitive parameters.
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
     * Sanitizes an error message for external exposure.
     */
    private String sanitizeMessage(String message) {
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

    /**
     * Domain exception carrying a stable ErrorCode. Can be thrown by
     * controllers or adapters to signal a domain-level error that should
     * be mapped to a specific HTTP status and stable error code.
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
