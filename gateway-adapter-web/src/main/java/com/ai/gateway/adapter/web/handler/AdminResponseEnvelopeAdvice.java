package com.ai.gateway.adapter.web.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adds the admin contract envelope while preserving legacy top-level fields. */
@ControllerAdvice
public final class AdminResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  org.springframework.http.server.ServerHttpRequest request,
                                  org.springframework.http.server.ServerHttpResponse response) {
        if (!(body instanceof Map<?, ?> raw)
                || !(request instanceof org.springframework.http.server.ServletServerHttpRequest servlet)) {
            return body;
        }
        HttpServletRequest httpRequest = servlet.getServletRequest();
        if (!httpRequest.getRequestURI().startsWith("/admin/v1/") || raw.containsKey("data")) {
            return body;
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        raw.forEach((key, value) -> legacy.put(String.valueOf(key), value));
        Map<String, Object> payload = new LinkedHashMap<>(legacy);
        payload.keySet().removeAll(java.util.Set.of(
                "status", "data", "error", "errorCode", "message"));
        boolean errorStatus = isError(legacy.get("status"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", errorStatus ? "ERROR" : "OK");
        envelope.put("data", payload);
        envelope.put("error", errorStatus ? structuredError(legacy) : null);
        // Preserve non-reserved legacy business fields for existing clients.
        payload.forEach(envelope::putIfAbsent);
        return envelope;
    }

    private Map<String, Object> structuredError(Map<String, Object> legacy) {
        Object existing = legacy.get("error");
        if (existing instanceof Map<?, ?> map) {
            Object code = map.containsKey("errorCode") ? map.get("errorCode") : "REQUEST_FAILED";
            Object message = map.containsKey("message") ? map.get("message") : "Request failed";
            return Map.of(
                    "errorCode", String.valueOf(code),
                    "message", String.valueOf(message));
        }
        return Map.of(
                "errorCode", String.valueOf(legacy.getOrDefault("errorCode", "REQUEST_FAILED")),
                "message", String.valueOf(legacy.getOrDefault(
                        "message", existing == null ? "Request failed" : existing)));
    }

    private boolean isError(Object status) {
        if (status == null) return false;
        String value = status.toString();
        return value.equals("ERROR") || value.equals("FAILED") || value.equals("REJECTED") || value.equals("NOT_FOUND")
                || value.equals("INVALID");
    }
}
