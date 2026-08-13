package com.ai.gateway.adapter.web.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified API response envelope for the admin console.
 *
 * <p>All responses follow the contract {@code {status, data, error}} where:
 * <ul>
 *   <li>{@code status} is {@code "OK"} for success or {@code "ERROR"} for failure.</li>
 *   <li>{@code data} carries the successful payload (absent on error).</li>
 *   <li>{@code error} carries {@code {errorCode, message}} (absent on success).</li>
 * </ul>
 *
 * <p>Error codes follow the spec taxonomy (AUTHENTICATION_FAILED,
 * PERMISSION_DENIED, ARGUMENT_VALIDATION_FAILED, etc.).</p>
 *
 * @since 0.1.0
 */
public final class ApiResponse {

    private ApiResponse() {
        // utility class
    }

    /**
     * Creates a successful response with status 200.
     *
     * @param data the response payload
     * @return 200 OK with {@code {status: "OK", data: ...}}
     */
    public static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /**
     * Creates a successful response with a custom HTTP status.
     *
     * @param data   the response payload
     * @param status the HTTP status
     * @return response with {@code {status: "OK", data: ...}}
     */
    public static ResponseEntity<Map<String, Object>> ok(Object data, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("data", data);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Creates an error response with the given error code and message.
     *
     * @param errorCode the stable error code (e.g., AUTHENTICATION_FAILED)
     * @param message   the user-facing error message (no internals)
     * @param status    the HTTP status
     * @return response with {@code {status: "ERROR", error: {errorCode, message}}}
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
     * Creates a 400 Bad Request error.
     *
     * @param message the user-facing error message
     * @return 400 BAD REQUEST
     */
    public static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return error("ARGUMENT_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Creates a 401 Unauthorized error.
     *
     * @param message the user-facing error message
     * @return 401 UNAUTHORIZED
     */
    public static ResponseEntity<Map<String, Object>> unauthorized(String message) {
        return error("AUTHENTICATION_FAILED", message, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Creates a 403 Forbidden error.
     *
     * @param message the user-facing error message
     * @return 403 FORBIDDEN
     */
    public static ResponseEntity<Map<String, Object>> forbidden(String message) {
        return error("PERMISSION_DENIED", message, HttpStatus.FORBIDDEN);
    }

    /**
     * Creates a 404 Not Found error.
     *
     * @param message the user-facing error message
     * @return 404 NOT FOUND
     */
    public static ResponseEntity<Map<String, Object>> notFound(String message) {
        return error("NO_CAPABILITY_MATCH", message, HttpStatus.NOT_FOUND);
    }

    /**
     * Creates a 409 Conflict error.
     *
     * @param message the user-facing error message
     * @return 409 CONFLICT
     */
    public static ResponseEntity<Map<String, Object>> conflict(String message) {
        return error("EXECUTION_UNKNOWN", message, HttpStatus.CONFLICT);
    }

    /**
     * Sanitizes an error message for external exposure.
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