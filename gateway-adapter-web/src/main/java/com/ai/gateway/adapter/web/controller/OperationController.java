package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.operation.OperationConfirmUseCase;
import com.ai.gateway.application.operation.OperationCancelUseCase;
import com.ai.gateway.application.operation.OperationPrepareUseCase;
import com.ai.gateway.application.operation.OperationStatusUseCase;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.OperationRecord;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.port.AuthenticationPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ai.gateway.domain.service.Sha256Digest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * 写操作 API 的 REST 控制器。
 *
 * <p>该控制器为两阶段 Prepare/Confirm 写操作协议暴露四个端点：</p>
 * <ul>
 * <li>{@code POST /api/v1/natural-language/actions:prepare} — 预准备写操作
 * 并返回确认令牌。</li>
 * <li>{@code POST /api/v1/operations/{operationId}:confirm} — 使用确认令牌
 * 确认并执行已预准备的操作。</li>
 * <li>{@code POST /api/v1/operations/{operationId}:cancel} — 在确认前取消
 * 已预准备的写操作。</li>
 * <li>{@code GET /api/v1/operations/{operationId}} — 查询写操作的当前状态。</li>
 * </ul>
 *
 * <p>该控制器绝不暴露加密参数、服务端签名或内部堆栈跟踪。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
@Slf4j
public class OperationController {

    private static final String AUTH_HEADER = "Authorization";

    private final OperationPrepareUseCase prepareUseCase;
    private final OperationConfirmUseCase confirmUseCase;
    private final OperationCancelUseCase cancelUseCase;
    private final OperationStatusUseCase statusUseCase;
    private final AuthenticationPort authenticationPort;
    private final RequestContextFactory requestContextFactory;

    /**
     * 构造新的 OperationController。
     *
     * @param prepareUseCase 写操作预准备用例
     * @param confirmUseCase 写操作确认用例
     * @param cancelUseCase 写操作取消用例
     * @param statusUseCase 写操作状态查询用例
     * @param authenticationPort 用于构建 Principal 的身份认证端口
     * @param requestContextFactory 将 Servlet 请求适配为领域 {@link RequestContext} 的工厂
     * @throws NullPointerException 任意参数为 null 时抛出
     */
    public OperationController(OperationPrepareUseCase prepareUseCase,
                                OperationConfirmUseCase confirmUseCase,
                                OperationCancelUseCase cancelUseCase,
                                OperationStatusUseCase statusUseCase,
                                AuthenticationPort authenticationPort,
                                RequestContextFactory requestContextFactory) {
        this.prepareUseCase = Objects.requireNonNull(prepareUseCase,
                "prepareUseCase must not be null");
        this.confirmUseCase = Objects.requireNonNull(confirmUseCase,
                "confirmUseCase must not be null");
        this.cancelUseCase = Objects.requireNonNull(cancelUseCase,
                "cancelUseCase must not be null");
        this.statusUseCase = Objects.requireNonNull(statusUseCase,
                "statusUseCase must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory,
                "requestContextFactory must not be null");
    }

    /**
     * 预准备一个待确认的写操作。
     *
     * @param request 含文本、locale、timezone 的预准备请求
     * @param servletRequest 用于构建 {@link RequestContext} 的底层 Servlet 请求
     * @return 含 operationId、确认令牌与摘要的预准备结果
     */
    @PostMapping("/natural-language/actions:prepare")
    public ResponseEntity<Map<String, Object>> prepare(
            @RequestBody @Valid PrepareRequest request,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @RequestHeader(value = AUTH_HEADER, required = false) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("Prepare phase requested");

        OperationPrepareUseCase.PrepareResult result =
                prepareUseCase.prepare(requestContext, request.text(),
                        request.locale() != null ? request.locale() : "zh-CN",
                        request.timezone() != null ? request.timezone() : "UTC",
                        idempotencyKey);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", "PREPARED");
            body.put("operationId", result.operationId());
            body.put("confirmationToken", result.token() != null ? result.token().token() : null);
            body.put("summary", result.summary());
            body.put("expiresAt", result.expiresAt());
            return ResponseEntity.ok(body);
        } else {
            body.put("status", "FAILED");
            body.put("message", sanitizeErrorMessage(result.error()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
    }

    /**
     * 确认并执行已预准备的写操作。
     *
     * @param operationId 预准备阶段返回的操作标识
     * @param request 含确认令牌的确认请求
     * @param servletRequest 用于构建 {@link RequestContext} 的底层 Servlet 请求
     * @return 含最终操作状态的确认结果
     */
    @PostMapping("/operations/{operationId}:confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String operationId,
            @RequestBody @Valid ConfirmRequest request,
            @RequestHeader(value = AUTH_HEADER, required = false) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("Confirm phase requested: operationId={}", operationId);

        // 认证调用方以构建 Principal
        Principal principal;
        try {
            principal = authenticationPort.authenticate(requestContext);
        } catch (Exception e) {
            log.warn("Authentication failed for confirm: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "FAILED");
            body.put("message", "Authentication failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        OperationConfirmUseCase.ConfirmResult result =
                confirmUseCase.confirm(operationId, request.token(), principal);

        Map<String, Object> body = new LinkedHashMap<>();

        if (result.success()) {
            body.put("status", result.finalState());
            body.put("operationId", operationId);
            body.put("message", sanitizeErrorMessage(result.message()));
            return ResponseEntity.ok(body);
        } else {
            body.put("status", result.finalState() != null ? result.finalState() : "FAILED");
            body.put("operationId", operationId);
            body.put("message", sanitizeErrorMessage(result.message()));
            return ResponseEntity.status(mapConfirmErrorToStatus(result.finalState())).body(body);
        }
    }

    /**
     * 在确认前取消已预准备的写操作。
     *
     * @param operationId 待取消的操作标识
     * @return 取消结果
     */
    @PostMapping("/operations/{operationId}:cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String operationId,
            HttpServletRequest servletRequest) {

        log.info("Cancel requested: operationId={}", operationId);

        Principal principal = authenticate(servletRequest);
        if (principal == null) {
            return authenticationFailed();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        OperationCancelUseCase.CancelResult result = cancelUseCase.cancel(operationId, principal);
        body.put("status", result.state());
        body.put("operationId", operationId);
        body.put("message", result.message());
        if (result.success()) {
            return ResponseEntity.ok(body);
        }
        HttpStatus status = "NOT_FOUND".equals(result.state())
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 查询写操作的当前状态。
     *
     * @param operationId 操作标识
     * @return 操作状态
     */
    @GetMapping("/operations/{operationId}")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable String operationId,
            HttpServletRequest servletRequest) {

        log.debug("Status query: operationId={}", operationId);

        Principal principal = authenticate(servletRequest);
        if (principal == null) {
            return authenticationFailed();
        }

        OperationRecord record = statusUseCase.query(operationId);

        Map<String, Object> body = new LinkedHashMap<>();

        if (record == null) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }


        if (!isOwner(record, principal)) {
            body.put("status", "NOT_FOUND");
            body.put("message", "Operation not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        body.put("status", record.state().name());
        body.put("operationId", record.operationId());
        body.put("capabilityId", record.capabilityId());
        body.put("capabilityVersion", record.capabilityVersion());
        body.put("snapshotVersion", record.snapshotVersion());
        body.put("expiresAt", record.expiresAt());

        // 绝不暴露加密参数、argumentsDigest 或 principalDigest
        return ResponseEntity.ok(body);
    }

    private Principal authenticate(HttpServletRequest servletRequest) {
        try {
            return authenticationPort.authenticate(requestContextFactory.from(servletRequest));
        } catch (RuntimeException e) {
            log.warn("Operation authentication failed");
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> authenticationFailed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "FAILED");
        body.put("message", "Authentication failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private boolean isOwner(OperationRecord record, Principal principal) {
        return record.orgId() == principal.orgId()
                && record.principalDigest().equals(subjectDigest(principal.subject()));
    }

    private String subjectDigest(String subject) {
        return Sha256Digest.sha256Hex(subject);
    }

    /**
     * 将确认结果的终态映射为对应的 HTTP 状态码。
     *
     * @param finalState 终态名称
     * @return 对应的 HTTP 状态码
     */
    private HttpStatus mapConfirmErrorToStatus(String finalState) {
        if (finalState == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (finalState) {
            case "EXPIRED" -> HttpStatus.GONE;
            case "FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "SUCCEEDED", "EXECUTING", "PREPARED" -> HttpStatus.OK;
            default -> HttpStatus.CONFLICT;
        };
    }

    /**
     * 对外暴露前清理错误消息中的内部信息。
     *
     * @param message 原始错误消息
     * @return 可安全对外暴露的清理后消息
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        return sanitized;
    }

    /**
     * POST /natural-language/actions:prepare 的请求体。
     *
     * @param text 自然语言请求文本
     * @param locale 请求 locale
     * @param timezone 请求时区
     */
    public record PrepareRequest(
            @NotBlank
            @Size(max = 4096)
            String text,
            @Size(max = 32)
            String locale,
            @Size(max = 64)
            String timezone
    ) {
    }

    /**
     * POST /operations/{operationId}:confirm 的请求体。
     *
     * @param token 预准备阶段返回的确认令牌字符串
     */
    public record ConfirmRequest(
            @NotBlank
            @Size(max = 8192)
            String token
    ) {
    }
}
