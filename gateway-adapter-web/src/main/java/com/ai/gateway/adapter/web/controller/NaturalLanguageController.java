package com.ai.gateway.adapter.web.controller;

import com.ai.gateway.application.runtime.ClarificationUseCase;
import com.ai.gateway.application.runtime.NaturalLanguageQueryUseCase;
import com.ai.gateway.adapter.web.support.RequestContextFactory;
import com.ai.gateway.domain.model.RequestContext;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.port.AuthenticationPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 自然语言查询 API 的 REST 控制器。
 *
 * <p>该控制器暴露两个端点：</p>
 * <ul>
 * <li>{@code POST /api/v1/natural-language/queries} — 接受自然语言查询并
 * 将其路由到 11 步流水线。</li>
 * <li>{@code POST /api/v1/natural-language/interactions/{interactionId}/messages}
 * — 使用额外的用户输入继续澄清会话。</li>
 * </ul>
 *
 * <p>响应格式如下：</p>
 * <ul>
 * <li>{@code COMPLETED} — HTTP 200，携带结构化结果数据。</li>
 * <li>{@code CLARIFICATION_REQUIRED} — HTTP 200，携带澄清问题与 interactionId。</li>
 * <li>{@code NO_MATCH} — HTTP 200，携带无匹配指示。</li>
 * <li>{@code ERROR} — HTTP 200，携带稳定的错误码与消息。</li>
 * </ul>
 *
 * <p>该控制器绝不暴露：原始 Prompt、完整候选集、协议绑定或任何内部堆栈跟踪。</p>
 *
 * @author cmiracle@163.com
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/natural-language")
@Slf4j
public class NaturalLanguageController {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final NaturalLanguageQueryUseCase nlQueryUseCase;
    private final ClarificationUseCase clarificationUseCase;
    private final RequestContextFactory requestContextFactory;
    private final AuthenticationPort authenticationPort;

    /**
     * 构造新的 NaturalLanguageController。
     *
     * @param nlQueryUseCase 自然语言查询用例
     * @param clarificationUseCase 澄清续聊用例
     * @param requestContextFactory 将 Servlet 请求适配为领域 {@link RequestContext} 的工厂
     * @param authenticationPort 用于构建 Principal 的身份认证端口
     * @throws NullPointerException 任意参数为 null 时抛出
     */
    public NaturalLanguageController(NaturalLanguageQueryUseCase nlQueryUseCase,
                                     ClarificationUseCase clarificationUseCase,
                                     RequestContextFactory requestContextFactory,
                                     AuthenticationPort authenticationPort) {
        this.nlQueryUseCase = Objects.requireNonNull(nlQueryUseCase,
                "nlQueryUseCase must not be null");
        this.clarificationUseCase = Objects.requireNonNull(clarificationUseCase,
                "clarificationUseCase must not be null");
        this.requestContextFactory = Objects.requireNonNull(requestContextFactory,
                "requestContextFactory must not be null");
        this.authenticationPort = Objects.requireNonNull(authenticationPort,
                "authenticationPort must not be null");
    }

    /**
     * 接受自然语言查询并将其路由到完整的 11 步流水线。
     *
     * @param request 含 requestId、text、locale、timezone 的查询请求
     * @param servletRequest 用于构建 {@link RequestContext} 的底层 Servlet 请求
     * @return 按响应格式返回的查询结果
     */
    @PostMapping("/queries")
    public ResponseEntity<Map<String, Object>> query(
            @Valid @RequestBody QueryRequest request,
            @RequestHeader(value = AUTH_HEADER) String authHeader,
            HttpServletRequest servletRequest) {

        RequestContext requestContext = requestContextFactory.from(servletRequest);
        log.info("NL query received: requestId={}, locale={}", request.requestId(), request.locale());

        NaturalLanguageQueryUseCase.QueryResult result =
                nlQueryUseCase.execute(requestContext, request.requestId(),
                        request.text(), request.locale(),
                        request.timezone() != null ? request.timezone() : "UTC");

        return buildQueryResponse(result, request.requestId());
    }

    /**
     * 使用额外的用户输入继续澄清会话。
     *
     * @param interactionId 澄清会话交互 ID
     * @param request 含用户补充文本的消息请求
     * @return 按响应格式返回的澄清结果
     */
    @PostMapping("/interactions/{interactionId}/messages")
    public ResponseEntity<Map<String, Object>> continueClarification(
            @PathVariable String interactionId,
            @Valid @RequestBody ClarificationMessageRequest request,
            @RequestHeader(value = AUTH_HEADER) String authHeader,
            HttpServletRequest servletRequest) {

        log.info("Clarification continuation: interactionId={}", interactionId);

        Principal principal = authenticationPort.authenticate(
                requestContextFactory.from(servletRequest));
        String principalDigest = computePrincipalDigest(principal.subject());

        ClarificationUseCase.ClarificationResult result =
                clarificationUseCase.continueClarification(interactionId, request.text(),
                        principalDigest);

        return buildClarificationResponse(result);
    }

    /**
     * 构建查询结果的 HTTP 响应。
     *
     * <p>响应绝不暴露 Prompt、完整候选集、协议绑定或内部堆栈跟踪。</p>
     *
     * @param result 用例返回的查询结果
     * @return 携带合适响应格式的 ResponseEntity
     */
    private ResponseEntity<Map<String, Object>> buildQueryResponse(
            NaturalLanguageQueryUseCase.QueryResult result, String requestId) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);

        switch (result.status()) {
            case COMPLETED -> {
                body.put("status", "COMPLETED");
                body.put("data", result.data());
                body.put("summary", result.summary());
                body.put("snapshotVersion", result.snapshotVersion());
                if (result.capability() != null) body.put("capability", result.capability());
                if (result.execution() != null) body.put("execution", result.execution());
                return ResponseEntity.ok(body);
            }
            case CLARIFICATION_REQUIRED -> {
                body.put("status", "CLARIFICATION_REQUIRED");
                body.put("question", result.summary());
                body.put("interactionId", result.interactionId());
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                if (result.expiresAt() != null) {
                    body.put("expiresAt", result.expiresAt().toString());
                }
                return ResponseEntity.ok(body);
            }
            case NO_MATCH -> {
                body.put("status", "NO_MATCH");
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                body.put("message", "No capability matched the request");
                return ResponseEntity.ok(body);
            }
            case ERROR -> {
                body.put("status", "ERROR");
                body.put("snapshotVersion", result.snapshotVersion());
                body.put("errorCode", result.errorCode());
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(mapErrorToStatus(result.errorCode())).body(body);
            }
            default -> {
                body.put("status", "ERROR");
                body.put("errorCode", "PROTOCOL_ERROR");
                body.put("message", "Unknown query status");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
        }
    }

    /**
     * 构建澄清续聊结果的 HTTP 响应。
     *
     * @param result 用例返回的澄清结果
     * @return 携带合适响应格式的 ResponseEntity
     */
    private ResponseEntity<Map<String, Object>> buildClarificationResponse(
            ClarificationUseCase.ClarificationResult result) {

        Map<String, Object> body = new LinkedHashMap<>();

        switch (result.status()) {
            case SELECT -> {
                body.put("status", "COMPLETED");
                body.put("data", result.data());
                return ResponseEntity.ok(body);
            }
            case CLARIFY -> {
                body.put("status", "CLARIFICATION_REQUIRED");
                body.put("question", result.question());
                body.put("interactionId", result.interactionId());
                return ResponseEntity.ok(body);
            }
            case INVALID -> {
                body.put("status", "INVALID");
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case INTENT_BREAKOUT -> {
                body.put("status", "INTENT_BREAKOUT");
                body.put("message", "Full pipeline restart required");
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
            }
            case ERROR -> {
                body.put("status", "ERROR");
                body.put("message", sanitizeErrorMessage(result.errorMessage()));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
            default -> {
                body.put("status", "ERROR");
                body.put("message", "Unknown clarification status");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
        }
    }

    /**
     * 将稳定的错误码映射为对应的 HTTP 状态码。
     *
     * @param errorCode 稳定错误码名称
     * @return 对应的 HTTP 状态码
     */
    private HttpStatus mapErrorToStatus(String errorCode) {
        if (errorCode == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (errorCode) {
            case "AUTHENTICATION_FAILED" -> HttpStatus.UNAUTHORIZED;
            case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "NO_CAPABILITY_MATCH" -> HttpStatus.NOT_FOUND;
            case "CLARIFICATION_REQUIRED", "INVALID_MODEL_OUTPUT" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ARGUMENT_VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "CAPABILITY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "LLM_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "PROVIDER_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "PROVIDER_REJECTED" -> HttpStatus.BAD_GATEWAY;
            case "PROTOCOL_ERROR" -> HttpStatus.BAD_GATEWAY;
            case "RESULT_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "EXECUTION_UNKNOWN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * 从 Authorization 头中提取 Bearer 令牌。
     *
     * @param authHeader Authorization 头的值
     * @return 令牌字符串；不存在时返回空字符串
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return "";
    }

    /**
     * 计算主体（subject）的 SHA-256 摘要，用于安全的会话匹配。
     *
     * @param subject 主体标识（如令牌字符串）
     * @return 十六进制编码的 SHA-256 摘要
     */
    private String computePrincipalDigest(String subject) {
        return Sha256Digest.sha256Hex(subject);
    }

    /**
     * 对外暴露前清理错误消息中的内部信息。
     *
     * <p>绝不暴露堆栈跟踪、内部地址、接口类名或敏感参数。</p>
     *
     * @param message 原始错误消息
     * @return 可安全对外暴露的清理后消息
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "服务处理失败，请稍后重试。";
        }
        // 移除任何可能的堆栈跟踪片段
        String sanitized = message.replaceAll("at\\s+\\S+\\.\\S+\\([^)]*\\)", "[internal]");
        // 移除文件路径
        sanitized = sanitized.replaceAll("/\\S+\\.java:\\d+", "[internal]");
        return sanitized;
    }

    /**
     * POST /queries 的请求体。
     *
     * @param requestId 客户端提供的请求标识
     * @param text 自然语言查询文本
     * @param locale 请求 locale（如 "zh-CN"）
     * @param timezone 请求时区（如 "Asia/Shanghai"）
     */
    public record QueryRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 8192) String text,
            @NotBlank @Size(max = 32) String locale,
            @Size(max = 64) String timezone
    ) {
    }

    /**
     * POST /interactions/{interactionId}/messages 的请求体。
     *
     * @param text 用户的补充输入文本
     */
    public record ClarificationMessageRequest(
            @NotBlank @Size(max = 8192) String text
    ) {
    }
}
