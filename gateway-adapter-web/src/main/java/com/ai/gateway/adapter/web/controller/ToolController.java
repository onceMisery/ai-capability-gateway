package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.runtime.AgentToolCallUseCase;
import com.ai.gateway.application.runtime.AgentToolCatalogUseCase;
import com.ai.gateway.application.runtime.StructuredInvocationUseCase;
import com.ai.gateway.domain.model.RequestContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化的能力发现与调用 API。
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public final class ToolController {

    private final StructuredInvocationUseCase structuredInvocationUseCase;
    private final RequestContextFactory requestContextFactory;
    private final AgentToolCatalogUseCase agentToolCatalogUseCase;
    private final AgentToolCallUseCase agentToolCallUseCase;

    public ToolController(StructuredInvocationUseCase structuredInvocationUseCase,
                          RequestContextFactory requestContextFactory) {
        this(structuredInvocationUseCase, requestContextFactory, null, null);
    }

    @Autowired
    public ToolController(StructuredInvocationUseCase structuredInvocationUseCase,
                          RequestContextFactory requestContextFactory,
                          AgentToolCatalogUseCase agentToolCatalogUseCase,
                          AgentToolCallUseCase agentToolCallUseCase) {
        this.structuredInvocationUseCase = Objects.requireNonNull(structuredInvocationUseCase);
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory);
        this.agentToolCatalogUseCase = agentToolCatalogUseCase;
        this.agentToolCallUseCase = agentToolCallUseCase;
    }

    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        return ResponseEntity.ok(Map.of("tools", structuredInvocationUseCase.listTools(context)));
    }

    /** 解析请求作用域、对模型可见的工具集以及宿主绑定（host bindings）。 */
    @PostMapping("/tools:resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @Valid @RequestBody ResolveRequest body,
            HttpServletRequest request) {
        AgentToolCatalogUseCase useCase = requireAgentCatalog();
        RequestContext context = requestContextFactory.from(request);
        int requestedTopK = body.topK() == null ? 5 : body.topK();
        AgentToolCatalogUseCase.Resolution result = useCase.resolve(
                context, body.query(), requestedTopK);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "RESOLVED");
        response.put("requestId", body.requestId());
        response.put("snapshotVersion", result.snapshotVersion());
        response.put("tools", result.candidates().stream().map(candidate -> Map.of(
                "toolName", candidate.toolName(),
                "displayName", candidate.displayName(),
                "description", candidate.description(),
                "inputSchema", candidate.inputSchema(),
                "executionMode", candidate.executionMode())).toList());
        response.put("bindings", result.bindings());
        return ResponseEntity.ok(response);
    }

    /** 通过统一的 Agent 调度器调用宿主选定的能力。 */
    @PostMapping("/tools/{capabilityId}:call")
    public ResponseEntity<Map<String, Object>> call(
            @PathVariable String capabilityId,
            @Valid @RequestBody CallRequest body,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        AgentToolCallUseCase useCase = requireAgentCall();
        RequestContext context = requestContextFactory.from(request);
        AgentToolCallUseCase.Result result = useCase.call(
                context, body.requestId(), capabilityId, body.version(), body.arguments(),
                body.locale(), body.snapshotVersion(),
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? body.requestId() : idempotencyKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("requestId", body.requestId());
        response.put("snapshotVersion", result.snapshotVersion());
        if (result.data() != null) {
            response.put("data", result.data());
        }
        if (result.operationId() != null) {
            response.put("operationId", result.operationId());
        }
        if (result.token() != null) {
            response.put("confirmationToken", result.token().token());
        }
        if (result.status() == AgentToolCallUseCase.Status.CONFIRMATION_REQUIRED
                && result.message() != null) {
            response.put("summary", result.message());
        }
        if (result.expiresAt() != null) {
            response.put("expiresAt", result.expiresAt());
        }
        if (result.errorCode() != null) {
            response.put("errorCode", result.errorCode());
            response.put("message", result.message());
        }
        return result.status() == AgentToolCallUseCase.Status.ERROR
                ? ResponseEntity.status(statusFor(result.errorCode())).body(response)
                : ResponseEntity.ok(response);
    }

    private AgentToolCatalogUseCase requireAgentCatalog() {
        if (agentToolCatalogUseCase == null) {
            throw new IllegalStateException("Agent tool catalog is not configured");
        }
        return agentToolCatalogUseCase;
    }

    private AgentToolCallUseCase requireAgentCall() {
        if (agentToolCallUseCase == null) {
            throw new IllegalStateException("Agent tool call is not configured");
        }
        return agentToolCallUseCase;
    }

    @PostMapping("/tools/{capabilityId}:invoke")
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
            case "STALE_SNAPSHOT" -> HttpStatus.CONFLICT;
            case "HIGH_RISK_WRITE_BLOCKED" -> HttpStatus.FORBIDDEN;
            case "PREPARE_FAILED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record ResolveRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 4096) String query,
            @Min(1) @Max(50) Integer topK
    ) {
    }

    public record CallRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 64) String version,
            @NotNull Map<String, Object> arguments,
            @NotBlank @Size(max = 32) String locale,
            @Min(1) long snapshotVersion
    ) {
    }

    public record InvokeRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 64) String version,
            @NotNull Map<String, Object> arguments,
            @NotBlank @Size(max = 32) String locale
    ) {
    }
}
