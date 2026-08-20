package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.application.agent.AgentHostConnector;
import com.ai.gateway.application.agent.AgentModelResultMapper;
import com.ai.gateway.domain.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向可信 Agent Host 的 API，提供预解析（pre-resolve）、延迟 Schema 与固定调用能力。
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentHostConnector connector;
    private final RequestContextFactory requestContextFactory;

    @PostMapping("/tools:resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @Valid @RequestBody ResolveRequest body,
            HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        String agentTurnId = turnId(body.agentTurnId(), body.requestId());
        AgentHostConnector.ResolveResult hostResult = connector.resolve(
                context, agentTurnId, body.requestId(), body.query(),
                body.topK() == null ? 5 : body.topK());
        var result = hostResult.resolution();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("requestId", body.requestId());
        response.put("agentTurnId", agentTurnId);
        if (hostResult.state() != null) {
            response.put("catalogVersion", hostResult.state().catalogVersion());
            response.put("policyEpoch", hostResult.state().policyEpoch());
        }
        response.put("catalogVersion", result.catalogVersion());
        response.put("policyEpoch", result.policyEpoch());
        response.put("candidates", result.candidates());
        if (result.selectedSchema() != null) {
            response.put("selectedSchema", result.selectedSchema());
        }
        if (result.expiresAt() != null) {
            response.put("expiresAt", result.expiresAt());
        }
        if (result.errorCode() != null) {
            response.put("errorCode", result.errorCode());
        }
        return result.status() == com.ai.gateway.application.agent.AgentCapabilityResolver.Status.ERROR
                ? ResponseEntity.status(statusFor(result.errorCode())).body(response)
                : ResponseEntity.ok(response);
    }

    @PostMapping("/tools:schema")
    public ResponseEntity<Map<String, Object>> schema(
            @Valid @RequestBody SchemaRequest body,
            HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        String agentTurnId = turnId(body.agentTurnId(), body.requestId());
        AgentHostConnector.SchemaResult hostResult = connector.schema(
                context, agentTurnId, body.toolRef());
        var result = hostResult.result();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("requestId", body.requestId());
        response.put("agentTurnId", agentTurnId);
        if (hostResult.state() != null) {
            response.put("catalogVersion", hostResult.state().catalogVersion());
            response.put("policyEpoch", hostResult.state().policyEpoch());
        }
        if (result.toolRef() != null) {
            response.put("toolRef", result.toolRef());
        }
        if (result.schemaClass() != null) {
            response.put("schemaClass", result.schemaClass().name());
        }
        if (!result.inputSchema().isEmpty()) {
            response.put("inputSchema", result.inputSchema());
        }
        if (result.expiresAt() != null) {
            response.put("expiresAt", result.expiresAt());
        }
        if (result.errorCode() != null) {
            response.put("errorCode", result.errorCode());
        }
        return result.status() == com.ai.gateway.application.agent.AgentCapabilityResolver.Status.ERROR
                ? ResponseEntity.status(statusFor(result.errorCode())).body(response)
                : ResponseEntity.ok(response);
    }

    @PostMapping("/tools:call")
    public ResponseEntity<Map<String, Object>> call(
            @Valid @RequestBody CallRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        RequestContext context = requestContextFactory.from(request);
        String agentTurnId = turnId(body.agentTurnId(), body.requestId());
        AgentHostConnector.CallResult hostResult = connector.call(
                context, agentTurnId, body.requestId(), body.toolRef(), body.arguments(), body.locale(),
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? body.requestId() : idempotencyKey);
        AgentModelResultMapper.ModelResult result = hostResult.result();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("requestId", body.requestId());
        response.put("agentTurnId", agentTurnId);
        if (result.data() != null) {
            response.put("data", result.data());
        }
        if (result.operationId() != null) {
            response.put("operationId", result.operationId());
        }
        if (hostResult.confirmationTokenHostOnly() != null) {
            response.put("confirmationToken", hostResult.confirmationTokenHostOnly());
            response.put("confirmationTokenHostOnly", true);
        }
        if (result.status() == AgentModelResultMapper.ModelResult.Status.CONFIRMATION_REQUIRED
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
        return result.status() == AgentModelResultMapper.ModelResult.Status.ERROR
                ? ResponseEntity.status(statusFor(result.errorCode())).body(response)
                : ResponseEntity.ok(response);
    }

    /** 受信任的 UI 事件。该端点的设计意图是不出现在 MCP tools/list 中。 */
    @PostMapping("/operations/{operationId}:confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable @Size(max = 128) String operationId,
            HttpServletRequest request) {
        AgentHostConnector.ConfirmationResult result = connector.confirm(
                new AgentHostConnector.UserConfirmationEvent(
                        requestContextFactory.from(request), operationId));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.state());
        response.put("operationId", operationId);
        if (result.message() != null) {
            response.put("message", result.message());
        }
        return result.success()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(hostEventStatus(result.state())).body(response);
    }

    /** 受信任的 UI 事件。该端点的设计意图是不出现在 MCP tools/list 中。 */
    @PostMapping("/operations/{operationId}:cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable @Size(max = 128) String operationId,
            HttpServletRequest request) {
        AgentHostConnector.CancellationResult result = connector.cancel(
                new AgentHostConnector.UserCancellationEvent(
                        requestContextFactory.from(request), operationId));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.state());
        response.put("operationId", operationId);
        if (result.message() != null) {
            response.put("message", result.message());
        }
        return result.success()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(hostEventStatus(result.state())).body(response);
    }

    /** 规范的写操作状态查询；绝不返回私有确认状态。 */
    @GetMapping("/operations/{operationId}")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable @Size(max = 128) String operationId,
            HttpServletRequest request) {
        AgentHostConnector.OperationStatusResult result = connector.status(
                requestContextFactory.from(request), operationId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operationId", operationId);
        if (result.found()) {
            response.put("status", result.state());
            response.put("expiresAt", result.expiresAt());
            return ResponseEntity.ok(response);
        }
        response.put("status", result.errorCode());
        HttpStatus httpStatus = switch (result.errorCode()) {
            case "AUTHENTICATION_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "OPERATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(httpStatus).body(response);
    }

    private static HttpStatus hostEventStatus(String state) {
        if ("AUTHENTICATION_FAILED".equals(state)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if ("CONFIRMATION_NOT_AVAILABLE".equals(state)) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static HttpStatus statusFor(String errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (errorCode) {
            case "AUTHENTICATION_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "PERMISSION_DENIED", "HIGH_RISK_WRITE_BLOCKED" -> HttpStatus.FORBIDDEN;
            case "CATALOG_CHANGED", "POLICY_CHANGED", "STALE_SNAPSHOT",
                 "PREPARE_FAILED", "TOOL_REF_NOT_IN_TURN", "TOOL_REF_NOT_SELECTED",
                 "SCHEMA_REQUIRES_INTERACTIVE_FLOW" -> HttpStatus.CONFLICT;
            case "TOOL_REF_EXPIRED" -> HttpStatus.GONE;
            case "ARGUMENT_VALIDATION_FAILED", "INVALID_MODEL_OUTPUT" ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case "CAPABILITY_UNAVAILABLE", "CATALOG_INDEX_NOT_READY",
                    "POLICY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "TURN_STATE_CAPACITY_EXCEEDED",
                    "CONFIRMATION_STATE_CAPACITY_EXCEEDED",
                    "RESOLVE_CAPACITY_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    public record ResolveRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 4096) String query,
            @Size(max = 32) String locale,
            @Min(1) @Max(5) Integer topK,
            @Size(max = 128) String agentTurnId) {
    }

    public record SchemaRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 2048) String toolRef,
            @Size(max = 128) String agentTurnId) {
    }

    public record CallRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 2048) String toolRef,
            @NotNull Map<String, Object> arguments,
            @NotBlank @Size(max = 32) String locale,
            @Size(max = 128) String agentTurnId) {
    }

    private static String turnId(String explicit, String requestId) {
        return explicit == null || explicit.isBlank() ? requestId : explicit;
    }
}
