package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.domain.model.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Structured capability discovery and invocation API. */
@RestController
@RequestMapping("/api/v1/tools")
public final class ToolController {

    private final StructuredInvocationUseCase structuredInvocationUseCase;
    private final RequestContextFactory requestContextFactory;

    public ToolController(StructuredInvocationUseCase structuredInvocationUseCase,
                          RequestContextFactory requestContextFactory) {
        this.structuredInvocationUseCase = Objects.requireNonNull(structuredInvocationUseCase);
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        return ResponseEntity.ok(Map.of("tools", structuredInvocationUseCase.listTools(context)));
    }

    @PostMapping("/{capabilityId}:invoke")
    public ResponseEntity<Map<String, Object>> invoke(
            @PathVariable String capabilityId,
            @Valid @RequestBody InvokeRequest body,
            HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        StructuredInvocationUseCase.Result result = structuredInvocationUseCase.invoke(
                context, body.requestId(), capabilityId, body.version(), body.arguments(), body.locale());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("requestId", body.requestId());
        response.put("snapshotVersion", result.snapshotVersion());
        if (result.data() != null) {
            response.put("data", result.data());
        }
        if (result.errorCode() != null) {
            response.put("errorCode", result.errorCode());
            response.put("message", result.message());
        }
        return result.status() == StructuredInvocationUseCase.Status.COMPLETED
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(statusFor(result.errorCode())).body(response);
    }

    private HttpStatus statusFor(String errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (errorCode) {
            case "AUTHENTICATION_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "NO_CAPABILITY_MATCH" -> HttpStatus.NOT_FOUND;
            case "ARGUMENT_VALIDATION_FAILED", "INVALID_MODEL_OUTPUT" -> HttpStatus.BAD_REQUEST;
            case "CONFIRMATION_REQUIRED" -> HttpStatus.CONFLICT;
            case "CAPABILITY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record InvokeRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 64) String version,
            @NotNull Map<String, Object> arguments,
            @NotBlank @Size(max = 32) String locale
    ) {
    }
}
